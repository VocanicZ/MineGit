package com.minegit.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FramingTest {

    private static byte[] bytes(int... vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            b[i] = (byte) vals[i];
        }
        return b;
    }

    @Test
    void smallPayloadFitsInSingleFrame() {
        byte[] payload = bytes(1, 2, 3);
        List<Frame> frames = Framing.frame(payload, 30_000);

        assertEquals(1, frames.size());
        Frame only = frames.get(0);
        assertEquals(0, only.getSeq());
        assertEquals(1, only.getTotal());
        assertArrayEquals(payload, only.getData());
    }

    @Test
    void reassembleSingleFrameYieldsOriginal() {
        byte[] payload = bytes(1, 2, 3);
        List<Frame> frames = Framing.frame(payload, 30_000);

        Reassembler r = new Reassembler();
        Optional<byte[]> done = r.add(frames.get(0));

        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());
    }

    @Test
    void tinyMaxFrameBytesSplitsAndReassembles() {
        byte[] payload = bytes(10, 11, 12, 13, 14, 15, 16);
        List<Frame> frames = Framing.frame(payload, 3);

        // ceil(7 / 3) == 3 frames: [10,11,12] [13,14,15] [16]
        assertEquals(3, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            assertEquals(i, frames.get(i).getSeq());
            assertEquals(3, frames.get(i).getTotal());
        }
        assertEquals(1, frames.get(2).getData().length);

        Reassembler r = new Reassembler();
        assertFalse(r.add(frames.get(0)).isPresent());
        assertFalse(r.add(frames.get(1)).isPresent());
        Optional<byte[]> done = r.add(frames.get(2));
        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());
    }

    @Test
    void outOfOrderFramesReassembleToOriginal() {
        byte[] payload = bytes(1, 2, 3, 4, 5, 6, 7, 8, 9);
        List<Frame> frames = Framing.frame(payload, 2); // 5 frames

        Reassembler r = new Reassembler();
        // Deliver shuffled; only the final missing frame should complete it.
        assertFalse(r.add(frames.get(3)).isPresent());
        assertFalse(r.add(frames.get(0)).isPresent());
        assertFalse(r.add(frames.get(4)).isPresent());
        assertFalse(r.add(frames.get(2)).isPresent());
        Optional<byte[]> done = r.add(frames.get(1));

        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());
    }

    @Test
    void duplicateFramesAreIdempotent() {
        byte[] payload = bytes(1, 2, 3, 4, 5);
        List<Frame> frames = Framing.frame(payload, 2); // 3 frames

        Reassembler r = new Reassembler();
        assertFalse(r.add(frames.get(0)).isPresent());
        assertFalse(r.add(frames.get(0)).isPresent()); // dup
        assertFalse(r.add(frames.get(1)).isPresent());
        assertFalse(r.add(frames.get(1)).isPresent()); // dup
        Optional<byte[]> done = r.add(frames.get(2));

        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());
    }

    @Test
    void interleavedSessionsDoNotCrossContaminate() {
        byte[] a = bytes(1, 1, 1, 1, 1);
        byte[] b = bytes(2, 2, 2, 2, 2, 2, 2);
        List<Frame> fa = Framing.frame(a, 2, 100);
        List<Frame> fb = Framing.frame(b, 2, 200);

        Reassembler r = new Reassembler();
        assertFalse(r.add(fa.get(0)).isPresent());
        assertFalse(r.add(fb.get(0)).isPresent());
        assertFalse(r.add(fb.get(2)).isPresent());
        assertFalse(r.add(fa.get(2)).isPresent());
        assertFalse(r.add(fb.get(1)).isPresent());
        Optional<byte[]> doneB = r.add(fb.get(3));
        assertTrue(doneB.isPresent());
        assertArrayEquals(b, doneB.get());

        Optional<byte[]> doneA = r.add(fa.get(1));
        assertTrue(doneA.isPresent());
        assertArrayEquals(a, doneA.get());
    }

    @Test
    void emptyPayloadRoundTrips() {
        byte[] payload = new byte[0];
        List<Frame> frames = Framing.frame(payload, 30_000);

        assertEquals(1, frames.size());
        Reassembler r = new Reassembler();
        Optional<byte[]> done = r.add(frames.get(0));
        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());
    }

    @Test
    void exactMultipleOfCapHasNoTrailingEmptyFrame() {
        byte[] payload = bytes(1, 2, 3, 4, 5, 6);
        List<Frame> frames = Framing.frame(payload, 3); // exactly 2 frames
        assertEquals(2, frames.size());
    }

    @Test
    void deterministicSessionIdForEqualPayloads() {
        byte[] p1 = bytes(7, 8, 9, 10);
        byte[] p2 = bytes(7, 8, 9, 10);
        assertEquals(
                Framing.frame(p1, 2).get(0).getSessionId(),
                Framing.frame(p2, 2).get(0).getSessionId());
    }

    @Test
    void rejectsNonPositiveMaxFrameBytes() {
        assertThrows(IllegalArgumentException.class, () -> Framing.frame(bytes(1, 2), 0));
        assertThrows(IllegalArgumentException.class, () -> Framing.frame(bytes(1, 2), -5));
    }

    @Test
    void rejectsContradictoryTotalForSameSession() {
        Reassembler r = new Reassembler();
        r.add(new Frame(42, 0, 3, bytes(1)));
        assertThrows(
                IllegalArgumentException.class, () -> r.add(new Frame(42, 0, 4, bytes(1))));
    }

    @Test
    void frameConstructorValidatesHeader() {
        assertThrows(IllegalArgumentException.class, () -> new Frame(1, 0, 0, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new Frame(1, 3, 3, bytes(1)));
        assertThrows(IllegalArgumentException.class, () -> new Frame(1, -1, 3, bytes(1)));
    }

    @Test
    void framedPayloadDecodesBackToOriginalWorldDiff() {
        WorldDiff diff = sampleDiff();
        byte[] payload = DiffPayload.encode(diff, "HEAD~1", "HEAD");

        // Tiny cap forces many frames; deliver out of order.
        List<Frame> frames = Framing.frame(payload, 16);
        assertTrue(frames.size() > 1);

        Reassembler r = new Reassembler();
        Optional<byte[]> done = Optional.empty();
        for (int i = frames.size() - 1; i >= 0; i--) {
            done = r.add(frames.get(i));
        }
        assertTrue(done.isPresent());
        assertArrayEquals(payload, done.get());

        WorldDiff round = DiffPayload.decode(done.get());
        assertEquals(diff, round);
    }

    private static WorldDiff sampleDiff() {
        BlockState stone = new BlockState("minecraft:stone");
        BlockState dirt = new BlockState("minecraft:dirt");
        Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("facing", "north");
        BlockState stairs = new BlockState("minecraft:oak_stairs", props);

        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(3, 64, 5, stone));
        changes.add(BlockChange.remove(4, 65, 6, dirt));
        changes.add(BlockChange.change(7, 66, 8, dirt, stairs));
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);

        Map<DimensionId, List<ChunkDiff>> dims =
                new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(new DimensionId("minecraft:overworld"), Collections.singletonList(cd));
        return new WorldDiff(dims, 1, 1, 1);
    }
}
