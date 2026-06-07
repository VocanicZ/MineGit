package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.live.ClientDiffEngine;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Reassembler;

/**
 * The loader-agnostic client overlay holder + receiver (Spec C §2.3, §3; SP2 §2d). It owns the
 * receive → reassemble → seed → toggle → clear lifecycle so all of it is exercised headless in JUnit;
 * the per-loader render/HUD/keybind glue is a thin reader/driver over this state and never carries
 * logic that could fail silently.
 *
 * <p><b>Store → compute flip (SP2 B1):</b> this no longer <i>stores</i> the server-pushed
 * {@link OverlayState}. Each completed {@code minegit:diff} payload is decoded to a {@link WorldDiff}
 * and fed to a {@link ClientDiffEngine} as <b>baseline seed/reset</b> data — a frozen HEAD. The
 * overlay the renderer reads is then <b>computed</b> by the engine each tick from the live client
 * world ({@code current() == engine.currentOverlay()}); the pushed diff is never rendered directly.
 *
 * <p>Not thread-safe — drive it from the client thread (the render/tick/receive callbacks all run
 * there). The static {@link #CLIENT} singleton is the instance the loader entrypoints wire; tests use
 * their own instances and inject a {@code FakeLevelAccess} via {@link #setLevelSupplier}.
 */
public final class OverlayClientState {

    /** The client-side singleton the loader entrypoints (receiver, render, HUD, keybind) drive. */
    public static final OverlayClientState CLIENT = new OverlayClientState();

    /**
     * The seam the keybind uses to deliver a subscription control to the server. Production wires it
     * to the {@code minegit:diffsub} client→server channel; tests inject a recording sender so the
     * toggle→control-message logic is exercised headless.
     */
    public interface ControlSender {
        /** Sends one subscription control ({@code SUBSCRIBE}/{@code UNSUBSCRIBE}) to the server. */
        void send(DiffControl control);
    }

    /** The default level supply before one is wired: "no world", so every engine path no-ops. */
    private static final Supplier<LevelAccess> NO_LEVEL = () -> null;

    /** Capacity (chunk baselines) of the engine's HEAD cache; matches the live-overlay budget. */
    private static final int ENGINE_CACHE_CAP = 256;

    /** Live-world supply, re-read each engine call so a dimension swap is picked up. */
    private Supplier<LevelAccess> levelSupplier = NO_LEVEL;

    /** The compute engine: turns seeds + the live world into the overlay the renderer reads. */
    private final ClientDiffEngine engine = new ClientDiffEngine(ENGINE_CACHE_CAP, () -> levelSupplier.get());

    private Reassembler reassembler = new Reassembler();
    private boolean visible;
    private boolean subscribed;
    private DimensionId activeDimension;

    /**
     * Injects the live-world supply the engine diffs against (SP2 §2d). Production wires the bound
     * client {@code ServerLevelAccess}; headless tests inject a {@code FakeLevelAccess}. A {@code null}
     * supplier (or a supply that returns {@code null}) means "no world" and every world-touching engine
     * path no-ops — nothing is computed.
     */
    public void setLevelSupplier(Supplier<LevelAccess> supplier) {
        this.levelSupplier = supplier == null ? NO_LEVEL : supplier;
    }

    /**
     * Feeds one received {@code minegit:diff} frame's raw bytes into the reassembler. When the frame
     * completes its payload, it is decoded to a {@link WorldDiff} and fed to the engine as a
     * <b>baseline seed/reset</b> (the frozen HEAD) — it is <i>never</i> the rendered overlay. The
     * state becomes visible and the engine's freshly-computed overlay is returned (possibly
     * box-empty until the next {@link #tickEngine}). While the payload is still incomplete, returns
     * {@link Optional#empty()}.
     *
     * @param frameBytes the opaque bytes of one {@code minegit:diff} packet ({@link Frame#toBytes})
     * @param now the current client tick (retained for call-site compatibility; the live overlay
     *     does not self-expire, so it is not stamped onto the computed overlay)
     */
    public Optional<OverlayState> acceptFrame(byte[] frameBytes, long now) {
        Objects.requireNonNull(frameBytes, "frameBytes");
        Frame frame = Frame.fromBytes(frameBytes);
        Optional<byte[]> payload = reassembler.add(frame);
        if (!payload.isPresent()) {
            return Optional.empty();
        }
        WorldDiff diff = DiffPayload.decode(payload.get()); // seed/reset data only — never rendered directly
        engine.onServerDiff(diff);
        visible = true;
        return Optional.ofNullable(engine.currentOverlay());
    }

    /**
     * Advances the engine by up to {@code sectionBudget} dirty sections (SP2 §2d), re-diffing the live
     * world against the frozen HEAD and rebuilding the computed overlay. Driven from the client tick
     * hook (B2). No-ops before the first seed or when no level is available.
     */
    public void tickEngine(int sectionBudget) {
        engine.tick(sectionBudget);
    }

    /** A block in the live client world changed; mark its section dirty for the next re-diff. */
    public void onClientBlockChange(DimensionId dim, int x, int y, int z) {
        engine.onBlockChange(dim, x, y, z);
    }

    /** A chunk just loaded in the live client world; seed it against the held diff if one was received. */
    public void onClientChunkLoad(DimensionId dim, ChunkPos pos) {
        engine.onChunkLoad(dim, pos);
    }

    /** The computed overlay, or {@code null} before the first seed / after a reset. */
    public OverlayState current() {
        return engine.currentOverlay();
    }

    /** Whether the computed overlay is currently shown. {@code false} when nothing is computed. */
    public boolean isVisible() {
        return visible && engine.currentOverlay() != null;
    }

    /** The client's active dimension, or {@code null} before it is known / after disconnect. */
    public DimensionId activeDimension() {
        return activeDimension;
    }

    /** Whether the client currently holds a live subscription (the keybind is "on"). */
    public boolean isSubscribed() {
        return subscribed;
    }

    /**
     * Toggles the live overlay subscription (the keybind, Spec C batch 2 §2.2; issue #92). Flips the
     * subscription flag, emits the matching control over {@code sender}, and returns it:
     * <ul>
     *   <li><b>off → on:</b> sends {@link DiffControl#SUBSCRIBE} and marks the overlay visible so the
     *       server's subsequent pushes (which seed the engine) render. Subscribing with nothing
     *       computed is valid — it is what makes the server start pushing; nothing draws until the
     *       first push seeds the engine.</li>
     *   <li><b>on → off:</b> sends {@link DiffControl#UNSUBSCRIBE} and {@link #clear()}s locally
     *       (resetting the engine) so the overlay vanishes immediately, without waiting for the
     *       server to stop.</li>
     * </ul>
     * The subscription itself survives a {@link #clear()} (dimension change keeps it live); only this
     * toggle and {@link #onDisconnect()} flip it off.
     *
     * @param sender the seam that carries the control to the server (the {@code minegit:diffsub} channel)
     * @return the control just sent ({@code SUBSCRIBE} or {@code UNSUBSCRIBE})
     */
    public DiffControl toggleSubscription(ControlSender sender) {
        Objects.requireNonNull(sender, "sender");
        // Send BEFORE mutating local state. A server that doesn't speak minegit:diffsub makes the
        // loader send throw (NeoForge's checkPacket rejects an unnegotiated channel); sending first
        // means such a throw leaves the subscription flags untouched and honest, never half-toggled
        // into a state where the client believes it is subscribed to a server that got nothing.
        DiffControl control = subscribed ? DiffControl.UNSUBSCRIBE : DiffControl.SUBSCRIBE;
        sender.send(control);
        if (subscribed) {
            subscribed = false;
            clear();
        } else {
            subscribed = true;
            visible = true;
        }
        return control;
    }

    /**
     * Updates the client's active dimension. A <b>change</b> from a previously-known dimension drops
     * that dimension's baselines from the engine (Spec C §3 lifecycle): a diff computed for one
     * dimension must not bleed into another, and the now-active dimension recomputes from its own
     * (re-pushed) seed. The first time the dimension becomes known (from {@code null}) drops nothing.
     */
    public void setActiveDimension(DimensionId dim) {
        Objects.requireNonNull(dim, "dim");
        if (activeDimension != null && !activeDimension.equals(dim)) {
            engine.dropDimension(activeDimension);
        }
        activeDimension = dim;
    }

    /**
     * Clears on disconnect: resets the compute engine, hides the overlay, forgets the active
     * dimension, ends the subscription, and discards any in-flight reassembly (the server-side
     * registry is dropped on disconnect too, so the client must match).
     */
    public void onDisconnect() {
        subscribed = false;
        visible = false;
        activeDimension = null;
        reassembler = new Reassembler();
        engine.reset();
    }

    /**
     * Resets the compute engine, hides the overlay, and discards any in-flight reassembly session so a
     * stale partial transfer can never complete into a new seed after the player has moved on. Keeps
     * the active dimension and (for the dimension-change case) the subscription.
     */
    public void clear() {
        reassembler = new Reassembler();
        engine.reset();
        visible = false;
    }

    /**
     * Whether the renderer should draw this frame: the overlay is visible, the active dimension is
     * known, and the <b>computed</b> overlay carries at least one box for it. Auto-expire is retired
     * from the live model (issue #92): while subscribed the overlay reflects current live state and
     * never self-expires — the only clears are toggle-off (UNSUB), disconnect, and dimension change.
     */
    public boolean shouldRender() {
        if (!visible || activeDimension == null) {
            return false;
        }
        OverlayState overlay = engine.currentOverlay();
        return overlay != null && !overlay.boxes(activeDimension).isEmpty();
    }
}
