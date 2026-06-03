package com.minegit.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiffPayloadTest {

    private static BlockState stone() {
        return new BlockState("minecraft:stone");
    }

    private static BlockState stairs() {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("facing", "north");
        p.put("half", "bottom");
        return new BlockState("minecraft:oak_stairs", p);
    }

    private static BlockState dirt() {
        return new BlockState("minecraft:dirt");
    }

    @Test
    void roundTripEmptyDiff() {
        WorldDiff diff = new WorldDiff(
                Collections.<DimensionId, List<ChunkDiff>>emptyMap(), 0, 0, 0);
        WorldDiff back = DiffPayload.decode(DiffPayload.encode(diff, "HEAD", "WORK"));
        assertEquals(diff, back);
    }

    @Test
    void roundTripSingleAdd() {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(3, 70, 5, stone()));
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims =
                new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        WorldDiff diff = new WorldDiff(dims, 1, 0, 0);

        WorldDiff back = DiffPayload.decode(DiffPayload.encode(diff, "a", "b"));
        assertEquals(diff, back);
    }

    @Test
    void roundTripAllKindsMultipleDimensionsAndChunks() {
        // overworld: two chunks, mix of add/remove/change with negative coords.
        List<BlockChange> c00 = new ArrayList<BlockChange>();
        c00.add(BlockChange.add(0, 64, 0, stone()));
        c00.add(BlockChange.change(15, 200, 15, stone(), stairs()));
        c00.add(BlockChange.remove(7, -32, 9, dirt()));
        ChunkDiff cd00 = new ChunkDiff(new ChunkPos(0, 0), c00);

        List<BlockChange> cNeg = new ArrayList<BlockChange>();
        cNeg.add(BlockChange.add(-16, 0, -16, dirt())); // chunk (-1,-1) local (0,0)
        cNeg.add(BlockChange.change(-1, 5, -1, dirt(), stone())); // chunk (-1,-1) local (15,15)
        ChunkDiff cdNeg = new ChunkDiff(new ChunkPos(-1, -1), cNeg);

        // nether: one chunk, single change reusing palette states.
        List<BlockChange> n = new ArrayList<BlockChange>();
        n.add(BlockChange.change(100, 10, 100, stairs(), stone()));
        ChunkDiff cdN = new ChunkDiff(new ChunkPos(6, 6), n);

        Map<DimensionId, List<ChunkDiff>> dims =
                new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd00, cdNeg));
        dims.put(DimensionId.THE_NETHER, Arrays.asList(cdN));
        WorldDiff diff = new WorldDiff(dims, 2, 1, 3);

        WorldDiff back = DiffPayload.decode(DiffPayload.encode(diff, "v1", "v2"));
        assertEquals(diff, back);
    }

    @Test
    void paletteDedupesIdenticalStatesAndKeepsDistinctPropsSeparate() {
        // Many changes referencing the same two states; plus a same-id/different-props state.
        BlockState stairsNorth = stairs();
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("facing", "south");
        p.put("half", "bottom");
        BlockState stairsSouth = new BlockState("minecraft:oak_stairs", p);

        List<BlockChange> changes = new ArrayList<BlockChange>();
        for (int i = 0; i < 8; i++) {
            changes.add(BlockChange.change(i, 64, 0, stairsNorth, stairsSouth));
        }
        changes.add(BlockChange.add(0, 65, 0, stairsNorth));
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims =
                new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        WorldDiff diff = new WorldDiff(dims, 1, 0, 8);

        byte[] payload = DiffPayload.encode(diff, "x", "y");
        WorldDiff back = DiffPayload.decode(payload);
        assertEquals(diff, back);
        // Distinct props must survive as distinct states.
        BlockChange first = back.getChunkDiffs(DimensionId.OVERWORLD)
                .get(0).getChanges().get(0);
        assertEquals(stairsNorth, first.getOldState());
        assertEquals(stairsSouth, first.getNewState());
    }

    @Test
    void refsArePreservedInHeader() {
        WorldDiff diff = new WorldDiff(
                Collections.<DimensionId, List<ChunkDiff>>emptyMap(), 0, 0, 0);
        byte[] payload = DiffPayload.encode(diff, "refs/heads/main", "live");
        assertEquals("refs/heads/main", DiffPayload.readFromRef(payload));
        assertEquals("live", DiffPayload.readToRef(payload));
    }

    @Test
    void encodingIsDeterministic() {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(3, 70, 5, stone()));
        changes.add(BlockChange.remove(4, 70, 5, dirt()));
        ChunkDiff cd = new ChunkDiff(new ChunkPos(0, 0), changes);
        Map<DimensionId, List<ChunkDiff>> dims =
                new HashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(cd));
        WorldDiff diff = new WorldDiff(dims, 1, 1, 0);

        assertArrayEquals(
                DiffPayload.encode(diff, "a", "b"),
                DiffPayload.encode(diff, "a", "b"));
    }

    @Test
    void decodeRejectsBadMagic() {
        assertThrows(IllegalArgumentException.class,
                () -> DiffPayload.decode(new byte[] {1, 2, 3, 4}));
    }
}
