package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockEntityTest {

    private static final String SNBT = "{Items:[],id:\"minecraft:chest\"}";

    @Test
    void equalByValue() {
        assertEquals(new BlockEntity(1, 2, 3, SNBT), new BlockEntity(1, 2, 3, SNBT));
        assertEquals(new BlockEntity(1, 2, 3, SNBT).hashCode(),
                new BlockEntity(1, 2, 3, SNBT).hashCode());
    }

    @Test
    void unequalWhenCoordinateDiffers() {
        assertNotEquals(new BlockEntity(1, 2, 3, SNBT), new BlockEntity(1, 2, 4, SNBT));
    }

    @Test
    void unequalWhenSnbtDiffers() {
        assertNotEquals(new BlockEntity(1, 2, 3, SNBT), new BlockEntity(1, 2, 3, "{id:\"x\"}"));
    }

    @Test
    void exposesFields() {
        BlockEntity be = new BlockEntity(1, 2, 3, SNBT);
        assertEquals(1, be.getX());
        assertEquals(2, be.getY());
        assertEquals(3, be.getZ());
        assertEquals(SNBT, be.getSnbt());
    }

    @Test
    void nullSnbtRejected() {
        assertThrows(NullPointerException.class, () -> new BlockEntity(1, 2, 3, null));
    }
}
