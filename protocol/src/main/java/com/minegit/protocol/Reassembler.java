package com.minegit.protocol;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Consumer side of {@link Framing}: collects {@link Frame}s as they arrive — in any order, from any
 * number of interleaved sessions — and yields the original {@link DiffPayload} blob once a session's
 * full set has been seen.
 *
 * <p>{@link #add(Frame)} returns {@link Optional#empty()} while a session is still incomplete and the
 * reassembled bytes the moment its last missing frame arrives; the session's buffers are then
 * dropped. Re-delivered (duplicate) frames are idempotent and never corrupt the buffer.
 *
 * <p>Not thread-safe — drive it from a single receive loop.
 *
 * <p>This module is platform-agnostic: it has <b>no Minecraft imports</b>.
 */
public final class Reassembler {

    private final Map<Integer, Session> sessions = new HashMap<Integer, Session>();

    /**
     * Records {@code frame} and, if it completes that frame's session, returns the reassembled
     * payload (and forgets the session). Otherwise returns {@link Optional#empty()}.
     *
     * @throws NullPointerException if {@code frame} is null
     * @throws IllegalArgumentException if {@code frame} contradicts earlier frames of its session
     *     (different {@code total})
     */
    public Optional<byte[]> add(Frame frame) {
        if (frame == null) {
            throw new NullPointerException("frame");
        }
        Integer key = frame.getSessionId();
        Session session = sessions.get(key);
        if (session == null) {
            session = new Session(frame.getTotal());
            sessions.put(key, session);
        } else if (session.total != frame.getTotal()) {
            throw new IllegalArgumentException(
                    "frame total " + frame.getTotal() + " contradicts session total "
                            + session.total + " for sessionId " + frame.getSessionId());
        }

        session.put(frame.getSeq(), frame.getData());
        if (!session.isComplete()) {
            return Optional.empty();
        }
        sessions.remove(key);
        return Optional.of(session.join());
    }

    private static final class Session {
        private final int total;
        private final byte[][] parts;
        private int received;

        Session(int total) {
            this.total = total;
            this.parts = new byte[total][];
        }

        void put(int seq, byte[] data) {
            if (parts[seq] == null) {
                received++;
            }
            parts[seq] = data;
        }

        boolean isComplete() {
            return received == total;
        }

        byte[] join() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] part : parts) {
                out.write(part, 0, part.length);
            }
            return out.toByteArray();
        }
    }
}
