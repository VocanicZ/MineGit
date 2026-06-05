package net.rainbowcreation.vocanicz.minegit.protocol;

import java.io.ByteArrayOutputStream;
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

    /**
     * Serializes this frame to the bytes of one {@code minegit:diff} packet.
     *
     * <p>Wire layout, mirroring the {@link DiffPayload} varint primitive style:
     *
     * <pre>
     * sessionId (int, 4 bytes big-endian) | seq (uvarint) | total (uvarint) | data (remaining bytes)
     * </pre>
     *
     * <p>{@code data} is the trailing remainder — its length is implied by the packet length, so no
     * separate length prefix is written. {@link #fromBytes(byte[])} inverts this losslessly.
     */
    public byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + data.length);
        writeInt(out, sessionId);
        writeUVarint(out, seq);
        writeUVarint(out, total);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    /**
     * Reconstructs the {@link Frame} from a packet produced by {@link #toBytes()}.
     *
     * @throws IllegalArgumentException if {@code bytes} is truncated or otherwise malformed
     * @throws NullPointerException if {@code bytes} is null
     */
    public static Frame fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new NullPointerException("bytes");
        }
        Reader in = new Reader(bytes);
        int sessionId = in.readInt();
        int seq = in.readUVarint();
        int total = in.readUVarint();
        byte[] data = in.readRemaining();
        try {
            return new Frame(sessionId, seq, total, data);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed Frame packet: " + e.getMessage(), e);
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** Unsigned LEB128 varint (32-bit) — matches {@link DiffPayload}'s primitive style. */
    private static void writeUVarint(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v & 0x7F);
    }

    private static final class Reader {
        private final byte[] buf;
        private int pos;

        Reader(byte[] buf) {
            this.buf = buf;
        }

        private int readU8() {
            if (pos >= buf.length) {
                throw new IllegalArgumentException("unexpected end of Frame packet");
            }
            return buf[pos++] & 0xFF;
        }

        int readInt() {
            int b0 = readU8();
            int b1 = readU8();
            int b2 = readU8();
            int b3 = readU8();
            return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
        }

        int readUVarint() {
            int result = 0;
            int shift = 0;
            while (true) {
                int b = readU8();
                result |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift >= 35) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        byte[] readRemaining() {
            byte[] rest = new byte[buf.length - pos];
            System.arraycopy(buf, pos, rest, 0, rest.length);
            pos = buf.length;
            return rest;
        }
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
