package net.rainbowcreation.vocanicz.minegit.mod.net;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;

/**
 * Server-side overlay subscription registry + snapshot push (Spec SP2 §2e).
 *
 * <p>Snapshot model: push the working-vs-HEAD diff only on SUBSCRIBE (immediate, including an initial
 * <em>empty</em> diff so the client starts from a known state) and on HEAD-move (commit/checkout
 * completion, via {@link #pushTo}). The per-tick recompute/dedupe of the original live loop is retired
 * — the CLIENT is now the live-diff engine (closes #93/#94/#100).
 *
 * <p><strong>Pure decision core.</strong> The loop holds only the registry; it never touches the world
 * or the dirty tracker — the caller supplies an already-computed (and non-destructive) diff. Transmission
 * is funneled through the {@link DiffOverlaySender.Sink} seam so the whole path is unit-testable headless
 * with a recording sink; production binds {@link DiffOverlaySender#channelSink()}.
 */
public final class LiveSubscriptionLoop {

    private final DiffOverlaySender.Sink sink;

    /** Live subscribers, keyed by player UUID. Insertion-ordered for deterministic iteration. */
    private final Set<UUID> subscribers = new LinkedHashSet<UUID>();

    /** Production registry: pushes over the real {@link DiffChannel}. */
    public LiveSubscriptionLoop() {
        this(DiffOverlaySender.channelSink());
    }

    /**
     * Test/override seam.
     *
     * @param sink the capability-gated transmit seam (production: {@link DiffOverlaySender#channelSink()})
     */
    public LiveSubscriptionLoop(DiffOverlaySender.Sink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Registers {@code id} as a live subscriber and immediately pushes {@code currentDiff} (the
     * player's working-vs-HEAD at subscribe time) — including an initial <em>empty</em> diff so the
     * client always starts from a known state. A {@code null} {@code currentDiff} (no bound repo)
     * registers the subscription without an initial push.
     */
    public void subscribe(ServerPlayer player, UUID id, WorldDiff currentDiff) {
        Objects.requireNonNull(id, "id");
        subscribers.add(id);
        if (currentDiff != null) {
            DiffOverlaySender.send(player, currentDiff, "HEAD", "WORKING", sink);
        }
    }

    /** Clears {@code id}'s subscription (on {@code UNSUBSCRIBE}). */
    public void unsubscribe(UUID id) {
        Objects.requireNonNull(id, "id");
        subscribers.remove(id);
    }

    /** Clears {@code id}'s subscription on player disconnect — identical to {@link #unsubscribe}. */
    public void disconnect(UUID id) {
        unsubscribe(id);
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
     * HEAD-move push: re-send the fresh snapshot to a subscriber on commit/checkout completion. A no-op
     * (0 frames) when {@code id} is not subscribed or {@code diff} is {@code null}; otherwise gated on
     * {@link DiffOverlaySender.Sink#canSend} like every other push.
     *
     * @return the number of frames sent ({@code 0} when not subscribed, diff null, or {@code canSend} false)
     */
    public int pushTo(ServerPlayer player, UUID id, WorldDiff diff) {
        if (!subscribers.contains(id) || diff == null) {
            return 0;
        }
        return DiffOverlaySender.send(player, diff, "HEAD", "WORKING", sink);
    }
}
