package com.minegit.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class ChunkRefTest {

    @Test
    void equalByValue() {
        ChunkRef a = new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(1, -2));
        ChunkRef b = new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(1, -2));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void unequalWhenDimensionDiffers() {
        assertNotEquals(
                new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 0)),
                new ChunkRef(DimensionId.THE_NETHER, new ChunkPos(0, 0)));
    }

    @Test
    void unequalWhenPosDiffers() {
        assertNotEquals(
                new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 0)),
                new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 1)));
    }

    @Test
    void exposesFields() {
        ChunkPos pos = new ChunkPos(3, 4);
        ChunkRef ref = new ChunkRef(DimensionId.THE_END, pos);
        assertSame(DimensionId.THE_END, ref.getDimension());
        assertSame(pos, ref.getPos());
    }

    @Test
    void rejectsNulls() {
        assertThrows(
                NullPointerException.class, () -> new ChunkRef(null, new ChunkPos(0, 0)));
        assertThrows(
                NullPointerException.class, () -> new ChunkRef(DimensionId.OVERWORLD, null));
    }
}
