package net.rainbowcreation.vocanicz.minegit.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Round-trip + wire-layout tests for the {@link Frame}⇄bytes codec (issue #76). */
class FrameCodecTest {

    private static byte[] bytes(int... vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) {
            b[i] = (byte) vals[i];
        }
        return b;
    }

    @Test
    void singleFrameRoundTrips() {
        Frame f = new Frame(0x1234ABCD, 0, 1, bytes(7, 8, 9));
        assertEquals(f, Frame.fromBytes(f.toBytes()));
    }

    @Test
    void emptyDataRoundTrips() {
        Frame f = new Frame(0, 0, 1, new byte[0]);
        Frame back = Frame.fromBytes(f.toBytes());
        assertEquals(f, back);
        assertEquals(0, back.getData().length);
    }

    @Test
    void multiFrameMembersRoundTripIndependently() {
        // total > 1, with negative-as-int sessionId (FNV hashes use the full 32-bit range).
        for (int seq = 0; seq < 4; seq++) {
            Frame f = new Frame(0xFFFFFFFF, seq, 4, bytes(seq, seq + 1, seq + 2));
            assertEquals(f, Frame.fromBytes(f.toBytes()));
        }
    }

    @Test
    void maxSizeSliceRoundTrips() {
        byte[] data = new byte[Framing.DEFAULT_MAX_FRAME_BYTES];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 7);
        }
        Frame f = new Frame(42, 0, 1, data);
        Frame back = Frame.fromBytes(f.toBytes());
        assertEquals(f, back);
        assertArrayEquals(data, back.getData());
    }

    @Test
    void wireLayoutIsSessionIntThenVarintsThenRawData() {
        // sessionId 0x01020304 | seq 1 | total 2 | data {0xAA,0xBB}
        Frame f = new Frame(0x01020304, 1, 2, bytes(0xAA, 0xBB));
        assertArrayEquals(bytes(0x01, 0x02, 0x03, 0x04, 1, 2, 0xAA, 0xBB), f.toBytes());
    }

    @Test
    void encodingIsDeterministic() {
        Frame f = new Frame(99, 2, 3, bytes(5, 6, 7));
        assertArrayEquals(f.toBytes(), f.toBytes());
    }

    @Test
    void truncatedHeaderThrowsIllegalArgument() {
        // Only 3 of the 4 sessionId bytes present.
        assertThrows(IllegalArgumentException.class, () -> Frame.fromBytes(bytes(1, 2, 3)));
    }

    @Test
    void truncatedBeforeVarintsThrowsIllegalArgument() {
        // Full sessionId but no seq/total.
        assertThrows(IllegalArgumentException.class, () -> Frame.fromBytes(bytes(1, 2, 3, 4)));
    }

    @Test
    void emptyInputThrowsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Frame.fromBytes(new byte[0]));
    }

    @Test
    void nullInputThrows() {
        assertThrows(NullPointerException.class, () -> Frame.fromBytes(null));
    }

    @Test
    void payloadFramesToBytesAndBackThroughReassembler() {
        WorldDiff diff = sampleDiff();
        byte[] payload = DiffPayload.encode(diff, "HEAD~1", "HEAD");

        // payload -> Framing.frame -> toBytes -> fromBytes -> Reassembler -> decode
        List<Frame> frames = Framing.frame(payload, 17); // tiny cap => many frames
        assertTrue(frames.size() > 1);

        List<byte[]> packets = new ArrayList<byte[]>();
        for (Frame f : frames) {
            packets.add(f.toBytes());
        }

        Reassembler r = new Reassembler();
        byte[] reassembled = null;
        for (byte[] packet : packets) {
            Optional<byte[]> done = r.add(Frame.fromBytes(packet));
            if (done.isPresent()) {
                reassembled = done.get();
            }
        }

        assertArrayEquals(payload, reassembled);
        assertEquals(diff, DiffPayload.decode(reassembled));
    }

    private static WorldDiff sampleDiff() {
        BlockState stone = new BlockState("minecraft:stone");
        Map<String, String> props = new LinkedHashMap<String, String>();
        props.put("facing", "north");
        BlockState chest = new BlockState("minecraft:chest", props);

        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(3, 64, 5, stone));
        changes.add(BlockChange.change(3, 65, 5, stone, chest));
        changes.add(BlockChange.remove(4, 66, 6, chest));

        List<ChunkDiff> chunks = new ArrayList<ChunkDiff>();
        chunks.add(new ChunkDiff(new ChunkPos(0, 0), changes));

        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(new DimensionId("minecraft:overworld"), chunks);
        return new WorldDiff(dims, 1, 1, 1);
    }
}
