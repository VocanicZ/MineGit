package net.rainbowcreation.vocanicz.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ChunkPosTest {

    @Test
    void equalByValue() {
        assertEquals(new ChunkPos(3, -7), new ChunkPos(3, -7));
        assertEquals(new ChunkPos(3, -7).hashCode(), new ChunkPos(3, -7).hashCode());
    }

    @Test
    void unequalWhenCxDiffers() {
        assertNotEquals(new ChunkPos(3, -7), new ChunkPos(4, -7));
    }

    @Test
    void unequalWhenCzDiffers() {
        assertNotEquals(new ChunkPos(3, -7), new ChunkPos(3, 8));
    }

    @Test
    void exposesCoordinates() {
        ChunkPos p = new ChunkPos(3, -7);
        assertEquals(3, p.getCx());
        assertEquals(-7, p.getCz());
    }
}
