package net.rainbowcreation.vocanicz.minegit.protocol;

/**
 * The 1-byte client→server control message carried on {@link Protocol#DIFF_CONTROL_CHANNEL} (Spec C
 * batch 2 §2.1, issue #91). The diff-overlay keybind toggles a <em>live subscription</em>: turning the
 * overlay on sends {@link #SUBSCRIBE}, off sends {@link #UNSUBSCRIBE}, and the server pushes
 * working-vs-HEAD diffs over {@link Protocol#DIFF_CHANNEL} while subscribed.
 *
 * <p>This codec is platform-agnostic (<b>no Minecraft imports</b>) and deterministic: {@link #encode}
 * yields a single wire byte and {@link #decode} round-trips it, rejecting an unknown byte or a payload
 * that is not exactly one byte with an {@link IllegalArgumentException}.
 */
public enum DiffControl {

    /** Drop the player from the live overlay registry (wire byte {@code 0}). */
    UNSUBSCRIBE(0),

    /** Add the player to the live overlay registry and start pushing working-vs-HEAD (wire byte {@code 1}). */
    SUBSCRIBE(1);

    private final int wire;

    DiffControl(int wire) {
        this.wire = wire;
    }

    /** The single byte this control occupies on the wire ({@code 0} or {@code 1}). */
    public int wireByte() {
        return wire;
    }

    /** Serializes this control to its 1-byte wire form. */
    public byte[] encode() {
        return new byte[] {(byte) wire};
    }

    /**
     * Decodes a 1-byte control payload. Rejects a {@code null}, a payload whose length is not exactly
     * one, or a byte outside the known set — a malformed client packet is a clean
     * {@link IllegalArgumentException}, never a silent mis-decode.
     */
    public static DiffControl decode(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (payload.length != 1) {
            throw new IllegalArgumentException(
                    "DiffControl payload must be exactly 1 byte, was " + payload.length);
        }
        return fromByte(payload[0]);
    }

    /** Maps a single wire byte to its control, rejecting any unknown code. */
    public static DiffControl fromByte(int b) {
        int v = b & 0xFF;
        switch (v) {
            case 0:
                return UNSUBSCRIBE;
            case 1:
                return SUBSCRIBE;
            default:
                throw new IllegalArgumentException("unknown DiffControl byte: " + v);
        }
    }
}
