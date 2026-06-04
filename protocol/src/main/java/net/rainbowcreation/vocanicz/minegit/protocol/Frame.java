package net.rainbowcreation.vocanicz.minegit.protocol;

import java.util.Arrays;

/**
 * One chunk of a larger {@link DiffPayload} blob, sized to fit under a legacy-safe plugin-message
 * cap and streamed over {@link Protocol#DIFF_CHANNEL}.
 *
 * <p>The header {@code {sessionId, seq, total}} lets a {@link Reassembler} group the fragments of a
 * single payload (the {@code sessionId}), order them ({@code seq} in {@code [0, total)}), and know
 * when the set is complete ({@code total}). The frames of one payload all share the same
 * {@code sessionId} and {@code total}.
 *
 * <p>This module is platform-agnostic: it has <b>no Minecraft imports</b>.
 */
public final class Frame {

    private final int sessionId;
    private final int seq;
    private final int total;
    private final byte[] data;

    /**
     * @param sessionId groups every frame of one payload; distinguishes concurrent transfers
     * @param seq this frame's index within the payload, in {@code [0, total)}
     * @param total number of frames the payload was split into; {@code >= 1}
     * @param data this frame's slice of the payload bytes (may be empty)
     */
    public Frame(int sessionId, int seq, int total, byte[] data) {
        if (total < 1) {
            throw new IllegalArgumentException("total must be >= 1, got " + total);
        }
        if (seq < 0 || seq >= total) {
            throw new IllegalArgumentException("seq " + seq + " out of range [0, " + total + ")");
        }
        if (data == null) {
            throw new NullPointerException("data");
        }
        this.sessionId = sessionId;
        this.seq = seq;
        this.total = total;
        this.data = data.clone();
    }

    public int getSessionId() {
        return sessionId;
    }

    public int getSeq() {
        return seq;
    }

    public int getTotal() {
        return total;
    }

    /** This frame's slice of the payload bytes. Returns a defensive copy. */
    public byte[] getData() {
        return data.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame)) {
            return false;
        }
        Frame other = (Frame) o;
        return sessionId == other.sessionId
                && seq == other.seq
                && total == other.total
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        int result = sessionId;
        result = 31 * result + seq;
        result = 31 * result + total;
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "Frame{sessionId=" + sessionId + ", seq=" + seq + ", total=" + total
                + ", dataLen=" + data.length + "}";
    }
}
