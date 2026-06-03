package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkDiffTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");

    private static List<BlockChange> changes() {
        return Arrays.asList(BlockChange.add(0, 0, 0, STONE), BlockChange.remove(1, 0, 0, STONE));
    }

    @Test
    void equalByValue() {
        ChunkDiff a = new ChunkDiff(new ChunkPos(1, 2), changes());
        ChunkDiff b = new ChunkDiff(new ChunkPos(1, 2), changes());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void unequalWhenPosDiffers() {
        assertNotEquals(
                new ChunkDiff(new ChunkPos(1, 2), changes()),
                new ChunkDiff(new ChunkPos(9, 2), changes()));
    }

    @Test
    void unequalWhenChangesDiffer() {
        assertNotEquals(
                new ChunkDiff(new ChunkPos(1, 2), changes()),
                new ChunkDiff(new ChunkPos(1, 2),
                        Collections.singletonList(BlockChange.add(0, 0, 0, STONE))));
    }

    @Test
    void exposesFields() {
        ChunkDiff d = new ChunkDiff(new ChunkPos(1, 2), changes());
        assertEquals(new ChunkPos(1, 2), d.getPos());
        assertEquals(changes(), d.getChanges());
    }

    @Test
    void changesAreImmutable() {
        ChunkDiff d = new ChunkDiff(new ChunkPos(1, 2), changes());
        assertThrows(UnsupportedOperationException.class,
                () -> d.getChanges().add(BlockChange.add(0, 0, 0, STONE)));
    }

    @Test
    void nullPosRejected() {
        assertThrows(NullPointerException.class, () -> new ChunkDiff(null, changes()));
    }
}
