package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockChangeTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    @Test
    void addEqualByValue() {
        BlockChange a = BlockChange.add(1, 2, 3, STONE);
        BlockChange b = BlockChange.add(1, 2, 3, STONE);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void addHasNullOldAndKindAdd() {
        BlockChange c = BlockChange.add(1, 2, 3, STONE);
        assertEquals(BlockChange.Kind.ADD, c.getKind());
        assertNull(c.getOldState());
        assertSame(STONE, c.getNewState());
    }

    @Test
    void removeHasNullNewAndKindRemove() {
        BlockChange c = BlockChange.remove(1, 2, 3, STONE);
        assertEquals(BlockChange.Kind.REMOVE, c.getKind());
        assertSame(STONE, c.getOldState());
        assertNull(c.getNewState());
    }

    @Test
    void changeHasBothStatesAndKindChange() {
        BlockChange c = BlockChange.change(1, 2, 3, STONE, DIRT);
        assertEquals(BlockChange.Kind.CHANGE, c.getKind());
        assertSame(STONE, c.getOldState());
        assertSame(DIRT, c.getNewState());
    }

    @Test
    void unequalWhenKindDiffers() {
        assertNotEquals(BlockChange.add(1, 2, 3, STONE), BlockChange.remove(1, 2, 3, STONE));
    }

    @Test
    void unequalWhenCoordinateDiffers() {
        assertNotEquals(BlockChange.add(1, 2, 3, STONE), BlockChange.add(1, 2, 4, STONE));
    }

    @Test
    void unequalWhenStateDiffers() {
        assertNotEquals(BlockChange.add(1, 2, 3, STONE), BlockChange.add(1, 2, 3, DIRT));
    }

    @Test
    void exposesCoordinates() {
        BlockChange c = BlockChange.add(5, 6, 7, STONE);
        assertEquals(5, c.getX());
        assertEquals(6, c.getY());
        assertEquals(7, c.getZ());
    }

    @Test
    void addRejectsNullNewState() {
        assertThrows(NullPointerException.class, () -> BlockChange.add(1, 2, 3, null));
    }

    @Test
    void removeRejectsNullOldState() {
        assertThrows(NullPointerException.class, () -> BlockChange.remove(1, 2, 3, null));
    }

    @Test
    void changeRejectsNullStates() {
        assertThrows(NullPointerException.class, () -> BlockChange.change(1, 2, 3, null, DIRT));
        assertThrows(NullPointerException.class, () -> BlockChange.change(1, 2, 3, STONE, null));
    }
}
