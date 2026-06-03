package com.minegit.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a {@link DiffPayload} blob into {@link Frame}s small enough to ride a legacy-safe
 * plugin-message channel ({@link Protocol#DIFF_CHANNEL}), and is the producer side of the
 * {@link Reassembler} round trip.
 *
 * <p>Framing is deterministic: the same {@code (payload, maxFrameBytes)} always yields the same
 * frames, including a stable {@code sessionId} derived from the payload bytes — so two equal
 * payloads frame identically without a random session source.
 *
 * <p>This module is platform-agnostic: it has <b>no Minecraft imports</b>.
 */
public final class Framing {

    /** Spec A §5 default cap (~30 KB) — comfortably under the 1.8 plugin-message ceiling. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 30_000;

    private Framing() {}

    /**
     * Splits {@code payload} into frames of at most {@code maxFrameBytes} data bytes each, tagged
     * with a deterministic {@code sessionId}.
     *
     * <p>An empty payload still yields exactly one (empty) frame so it round-trips through a
     * {@link Reassembler}.
     *
     * @throws IllegalArgumentException if {@code maxFrameBytes < 1}
     */
    public static List<Frame> frame(byte[] payload, int maxFrameBytes) {
        return frame(payload, maxFrameBytes, sessionIdFor(payload));
    }

    /** Variant that pins an explicit {@code sessionId} (e.g. when the caller multiplexes). */
    public static List<Frame> frame(byte[] payload, int maxFrameBytes, int sessionId) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be >= 1, got " + maxFrameBytes);
        }

        int len = payload.length;
        int total = len == 0 ? 1 : (len + maxFrameBytes - 1) / maxFrameBytes;
        List<Frame> frames = new ArrayList<Frame>(total);
        for (int seq = 0; seq < total; seq++) {
            int start = seq * maxFrameBytes;
            int end = Math.min(start + maxFrameBytes, len);
            byte[] slice = new byte[end - start];
            System.arraycopy(payload, start, slice, 0, slice.length);
            frames.add(new Frame(sessionId, seq, total, slice));
        }
        return frames;
    }

    /** Stable 32-bit FNV-1a hash of the payload bytes — deterministic session id. */
    private static int sessionIdFor(byte[] payload) {
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        int hash = 0x811C9DC5;
        for (byte b : payload) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }
}
