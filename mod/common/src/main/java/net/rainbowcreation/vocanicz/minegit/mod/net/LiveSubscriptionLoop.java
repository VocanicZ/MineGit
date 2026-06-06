package net.rainbowcreation.vocanicz.minegit.mod.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;

/**
 * The server side of the live overlay (Spec C batch 2 §2.3, issue #93): a per-player subscription
 * registry (keyed by UUID) plus a throttled recompute→dedupe→push loop. The
 * {@link DiffControlChannel} handler {@link #subscribe}s a player on {@code SUBSCRIBE} (pushing the
 * current working-vs-HEAD immediately) and {@link #unsubscribe}s on {@code UNSUBSCRIBE}; a player
 * {@link #disconnect}ing is cleared the same way. Every {@code refreshTicks} server ticks the live
 * loop, per subscriber, recomputes the player's current working-vs-HEAD (via the injected
 * {@link Poller}), <strong>dedupes</strong> it against the last {@link WorldDiff} pushed to that
 * player, and otherwise pushes it over {@link DiffOverlaySender} ({@code canSend}-gated).
 *
 * <p><strong>Pure decision core.</strong> The loop holds only the registry, the per-player
 * last-pushed diff (for dedupe), and a tick counter; it never touches the world or the dirty tracker
 * — the {@link Poller} supplies an already-computed (and non-destructive) diff. Transmission is
 * funneled through the {@link DiffOverlaySender.Sink} seam so the whole path is unit-testable headless
 * with a recording sink; production binds {@link DiffOverlaySender#channelSink()}.
 */
public final class LiveSubscriptionLoop {

    /** Default live-refresh cadence: recompute every 10 server ticks (Spec C batch 2 §2.3). */
    public static final int DEFAULT_REFRESH_TICKS = 10;

    /**
     * A subscriber's push target for one recompute: the online player to send to and its freshly
     * computed working-vs-HEAD. The diff must be computed <em>non-destructively</em> (read-only over
     * the dirty set) by the caller so the live loop never drains the tracker {@code /mg commit} relies
     * on. The player is forwarded verbatim to {@link DiffOverlaySender#send} (the sink decides
     * capability), so it may be {@code null} in tests.
     */
    public static final class Snapshot {
        final ServerPlayer player;
        final WorldDiff diff;

        public Snapshot(ServerPlayer player, WorldDiff diff) {
            this.diff = Objects.requireNonNull(diff, "diff");
            this.player = player;
        }
    }

    /** Resolves a subscribed UUID to its current push target, or {@code null} when offline/unbound. */
    public interface Poller {
        /** The snapshot to push for {@code id}, or {@code null} to skip it this tick (no push). */
        Snapshot poll(UUID id);
    }

    private final int refreshTicks;
    private final DiffOverlaySender.Sink sink;

    /** Live subscribers, keyed by player UUID. Insertion-ordered for deterministic iteration. */
    private final Set<UUID> subscribers = new LinkedHashSet<UUID>();

    /** The last {@link WorldDiff} actually pushed to each subscriber, for dedupe. */
    private final Map<UUID, WorldDiff> lastPushed = new HashMap<UUID, WorldDiff>();

    private int tickCounter;

    /** Production loop: the default cadence pushing over the real {@link DiffChannel}. */
    public LiveSubscriptionLoop() {
        this(DEFAULT_REFRESH_TICKS, DiffOverlaySender.channelSink());
    }

    /**
     * Test/override seam.
     *
     * @param refreshTicks how many ticks between live recomputes; must be {@code >= 1}
     * @param sink the capability-gated transmit seam (production: {@link DiffOverlaySender#channelSink()})
     */
    public LiveSubscriptionLoop(int refreshTicks, DiffOverlaySender.Sink sink) {
        if (refreshTicks < 1) {
            throw new IllegalArgumentException("refreshTicks must be >= 1 but was " + refreshTicks);
        }
        this.refreshTicks = refreshTicks;
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Registers {@code id} as a live subscriber and immediately pushes {@code currentDiff} (the
     * player's working-vs-HEAD at subscribe time) — including an initial <em>empty</em> diff so the
     * client always starts from a known state. A {@code null} {@code currentDiff} (no bound repo)
     * registers the subscription without an initial push; the next tick will push once a diff exists.
     */
    public void subscribe(ServerPlayer player, UUID id, WorldDiff currentDiff) {
        Objects.requireNonNull(id, "id");
        subscribers.add(id);
        if (currentDiff != null) {
            pushIfChanged(player, id, currentDiff);
        }
    }

    /** Clears {@code id}'s subscription and dedupe state (on {@code UNSUBSCRIBE}). */
    public void unsubscribe(UUID id) {
        Objects.requireNonNull(id, "id");
        subscribers.remove(id);
        lastPushed.remove(id);
    }

    /** Clears {@code id}'s subscription on player disconnect — identical to {@link #unsubscribe}. */
    public void disconnect(UUID id) {
        unsubscribe(id);
    }

    /** The configured live-refresh cadence in server ticks ({@code >= 1}). */
    public int refreshTicks() {
        return refreshTicks;
    }

    /** Whether {@code id} is currently a live subscriber. */
    public boolean isSubscribed(UUID id) {
        return subscribers.contains(id);
    }

    /** Number of live subscribers. */
    public int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Advances the live loop one server tick. Does nothing until {@code refreshTicks} ticks have
     * elapsed; then, per subscriber, polls the current push target and runs the
     * recompute→dedupe→push step. Returns how many subscribers were pushed this tick.
     */
    public int tick(Poller poller) {
        Objects.requireNonNull(poller, "poller");
        if (++tickCounter < refreshTicks) {
            return 0;
        }
        tickCounter = 0;
        if (subscribers.isEmpty()) {
            return 0;
        }
        int pushed = 0;
        // Iterate a snapshot so a poll-driven unsubscribe/disconnect cannot mutate the set mid-loop.
        for (UUID id : new ArrayList<UUID>(subscribers)) {
            Snapshot snap = poller.poll(id);
            if (snap == null) {
                continue; // offline or no bound repo this tick — keep the subscription, push nothing
            }
            if (pushIfChanged(snap.player, id, snap.diff) > 0) {
                pushed++;
            }
        }
        return pushed;
    }

    /**
     * Pushes {@code diff} to {@code id} only when it differs from the last diff pushed to that player,
     * tagged {@code HEAD}→{@code WORKING} and gated on {@link DiffOverlaySender.Sink#canSend}. Records
     * the diff as last-pushed only when frames actually went out, so an incapable player (0 frames) is
     * re-evaluated every tick rather than being silently "deduped" against a diff it never received.
     *
     * @return the number of frames sent ({@code 0} when deduped or {@code canSend} is false)
     */
    private int pushIfChanged(ServerPlayer player, UUID id, WorldDiff diff) {
        if (diff.equals(lastPushed.get(id))) {
            return 0; // unchanged working-vs-HEAD — no push
        }
        int frames = DiffOverlaySender.send(player, diff, "HEAD", "WORKING", sink);
        if (frames > 0) {
            lastPushed.put(id, diff);
        }
        return frames;
    }
}
