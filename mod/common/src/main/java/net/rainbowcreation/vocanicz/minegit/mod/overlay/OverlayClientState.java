package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.Objects;
import java.util.Optional;

import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
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

    private Reassembler reassembler = new Reassembler();
    private OverlayState current;
    private boolean visible;
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

    /**
     * Toggles overlay visibility (the keybind). A no-op when no overlay is held — it cannot become
     * visible without something to show. Returns the resulting visibility.
     */
    public boolean toggle() {
        if (current == null) {
            visible = false;
            return false;
        }
        visible = !visible;
        return visible;
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

    /** Clears on disconnect: drops the overlay, hides it, and forgets the active dimension. */
    public void onDisconnect() {
        clear();
        activeDimension = null;
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
     * Clears the overlay if it has outlived {@code lifetimeTicks} as of {@code now} (the configurable
     * auto-expire timer). A non-positive {@code lifetimeTicks} disables expiry. Returns {@code true}
     * iff this call cleared an expired overlay.
     *
     * @param now the current client tick
     * @param lifetimeTicks the overlay lifetime in ticks ({@code autoExpireSeconds * 20}); {@code <= 0}
     *     disables the timer
     */
    public boolean tickExpiry(long now, long lifetimeTicks) {
        if (current == null) {
            return false;
        }
        if (current.isExpired(now, lifetimeTicks)) {
            clear();
            return true;
        }
        return false;
    }

    /**
     * Whether the renderer should draw this frame: an overlay is held, it is visible, it has not
     * expired, and the active dimension is known and carries at least one box. Mirrors the Spec C
     * render gate ("visible + not expired + dimension matches").
     *
     * @param now the current client tick
     * @param lifetimeTicks the overlay lifetime in ticks; {@code <= 0} disables expiry
     */
    public boolean shouldRender(long now, long lifetimeTicks) {
        if (!isVisible()) {
            return false;
        }
        if (current.isExpired(now, lifetimeTicks)) {
            return false;
        }
        if (activeDimension == null) {
            return false;
        }
        return !current.boxes(activeDimension).isEmpty();
    }
}
