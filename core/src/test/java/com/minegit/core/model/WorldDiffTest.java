package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldDiffTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");

    private static Map<DimensionId, List<ChunkDiff>> diffs() {
        Map<DimensionId, List<ChunkDiff>> m = new HashMap<DimensionId, List<ChunkDiff>>();
        m.put(DimensionId.OVERWORLD, Collections.singletonList(
                new ChunkDiff(new ChunkPos(0, 0),
                        Collections.singletonList(BlockChange.add(0, 0, 0, STONE)))));
        return m;
    }

    @Test
    void equalByValue() {
        WorldDiff a = new WorldDiff(diffs(), 1, 0, 0);
        WorldDiff b = new WorldDiff(diffs(), 1, 0, 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void exposesCountsAndMap() {
        WorldDiff d = new WorldDiff(diffs(), 1, 2, 3);
        assertEquals(1, d.getAdded());
        assertEquals(2, d.getRemoved());
        assertEquals(3, d.getChanged());
        assertEquals(diffs(), d.getDimensions());
    }

    @Test
    void chunkDiffsForDimensionAccessor() {
        WorldDiff d = new WorldDiff(diffs(), 1, 0, 0);
        assertEquals(1, d.getChunkDiffs(DimensionId.OVERWORLD).size());
        assertEquals(Collections.emptyList(), d.getChunkDiffs(DimensionId.THE_END));
    }

    @Test
    void unequalWhenCountsDiffer() {
        assertNotEquals(new WorldDiff(diffs(), 1, 0, 0), new WorldDiff(diffs(), 2, 0, 0));
    }

    @Test
    void unequalWhenMapDiffers() {
        assertNotEquals(
                new WorldDiff(diffs(), 1, 0, 0),
                new WorldDiff(Collections.<DimensionId, List<ChunkDiff>>emptyMap(), 1, 0, 0));
    }

    @Test
    void dimensionsMapIsImmutable() {
        WorldDiff d = new WorldDiff(diffs(), 1, 0, 0);
        assertThrows(UnsupportedOperationException.class,
                () -> d.getDimensions().clear());
    }

    @Test
    void mutatingSourceMapDoesNotAffectDiff() {
        Map<DimensionId, List<ChunkDiff>> src = diffs();
        WorldDiff d = new WorldDiff(src, 1, 0, 0);
        src.clear();
        assertEquals(1, d.getDimensions().size());
    }

    @Test
    void nullMapRejected() {
        assertThrows(NullPointerException.class, () -> new WorldDiff(null, 0, 0, 0));
    }

    @Test
    void negativeCountRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WorldDiff(diffs(), -1, 0, 0));
    }
}
