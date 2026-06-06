package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.Objects;
import java.util.Optional;

import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffControl;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Reassembler;

/**
 * The loader-agnostic client overlay holder + receiver (Spec C §2.3, §3; issue #80). It owns the
 * receive → reassemble → hold → toggle → expire → clear lifecycle so all of it is exercised headless
 * in JUnit; the per-loader render/HUD/keybind glue is a thin reader/driver over this state and never
 * carries logic that could fail silently.
 *
 * <p>Holds one current {@link OverlayState} (replace-on-new), a {@code visible} flag, and the client's
 * active {@link DimensionId}. A per-session {@link Reassembler} accumulates incoming
 * {@code minegit:diff} frames; the moment a payload completes it builds a fresh overlay, makes it
 * visible, and resets its expiry baseline.
 *
 * <p>Not thread-safe — drive it from the client thread (the render/tick/receive callbacks all run
 * there). The static {@link #CLIENT} singleton is the instance the loader entrypoints wire; tests use
 * their own instances.
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

    private Reassembler reassembler = new Reassembler();
    private OverlayState current;
    private boolean visible;
    private boolean subscribed;
    private DimensionId activeDimension;

    /**
     * Feeds one received {@code minegit:diff} frame's raw bytes into the reassembler. When the frame
     * completes its payload, decodes it into a fresh {@link OverlayState} that <b>replaces</b> any
     * held overlay, becomes visible, and stamps {@code now} as its expiry baseline; the completed
     * overlay is returned. While the payload is still incomplete, returns {@link Optional#empty()}
     * and leaves the held overlay untouched.
     *
     * @param frameBytes the opaque bytes of one {@code minegit:diff} packet ({@link Frame#toBytes})
     * @param now the current client tick (the overlay's {@code receivedAt} on completion)
     */
    public Optional<OverlayState> acceptFrame(byte[] frameBytes, long now) {
        Objects.requireNonNull(frameBytes, "frameBytes");
        Frame frame = Frame.fromBytes(frameBytes);
        Optional<byte[]> payload = reassembler.add(frame);
        if (!payload.isPresent()) {
            return Optional.empty();
        }
        OverlayState overlay = OverlayState.fromPayload(payload.get(), now);
        this.current = overlay;
        this.visible = true;
        return Optional.of(overlay);
    }

    /** The held overlay, or {@code null} when none is held. */
    public OverlayState current() {
        return current;
    }

    /** Whether the held overlay is currently shown. Always {@code false} when none is held. */
    public boolean isVisible() {
        return visible && current != null;
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
     *       server's subsequent pushes render. Subscribing with nothing held is valid — it is what
     *       makes the server start pushing; nothing draws until the first push arrives.</li>
     *   <li><b>on → off:</b> sends {@link DiffControl#UNSUBSCRIBE} and {@link #clear()}s the held
     *       overlay locally so it vanishes immediately, without waiting for the server to stop.</li>
     * </ul>
     * The subscription itself survives a {@link #clear()} (dimension change keeps it live); only this
     * toggle, {@link #onDisconnect()}, flip it off.
     *
     * @param sender the seam that carries the control to the server (the {@code minegit:diffsub} channel)
     * @return the control just sent ({@code SUBSCRIBE} or {@code UNSUBSCRIBE})
     */
    public DiffControl toggleSubscription(ControlSender sender) {
        Objects.requireNonNull(sender, "sender");
        DiffControl control;
        if (subscribed) {
            subscribed = false;
            clear();
            control = DiffControl.UNSUBSCRIBE;
        } else {
            subscribed = true;
            visible = true;
            control = DiffControl.SUBSCRIBE;
        }
        sender.send(control);
        return control;
    }

    /**
     * Updates the client's active dimension. A <b>change</b> from a previously-known dimension
     * auto-clears the held overlay (Spec C §3 lifecycle): a diff computed for one dimension must not
     * bleed into another. The first time the dimension becomes known (from {@code null}) clears
     * nothing — there is no overlay to invalidate yet.
     */
    public void setActiveDimension(DimensionId dim) {
        Objects.requireNonNull(dim, "dim");
        if (activeDimension != null && !activeDimension.equals(dim)) {
            clear();
        }
        activeDimension = dim;
    }

    /**
     * Clears on disconnect: drops the overlay, hides it, forgets the active dimension, and ends the
     * subscription (the server-side registry is dropped on disconnect too, so the client must match).
     */
    public void onDisconnect() {
        clear();
        activeDimension = null;
        subscribed = false;
    }

    /**
     * Drops the held overlay, hides it, and discards any in-flight reassembly session so a stale
     * partial transfer can never complete into a new overlay after the player has moved on.
     */
    public void clear() {
        current = null;
        visible = false;
        reassembler = new Reassembler();
    }

    /**
     * Whether the renderer should draw this frame: an overlay is held, it is visible, and the active
     * dimension is known and carries at least one box. Auto-expire is retired from the live model
     * (issue #92): while subscribed the overlay reflects current live state and never self-expires —
     * the only clears are toggle-off (UNSUB), disconnect, and dimension change.
     */
    public boolean shouldRender() {
        if (!isVisible()) {
            return false;
        }
        if (activeDimension == null) {
            return false;
        }
        return !current.boxes(activeDimension).isEmpty();
    }
}
