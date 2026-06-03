package com.minegit.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class BlockStateTest {

    @Test
    void equalByValueWhenIdAndPropsMatch() {
        BlockState a = new BlockState("minecraft:stone");
        BlockState b = new BlockState("minecraft:stone");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void propsAreKeySortedSoInsertionOrderDoesNotMatter() {
        Map<String, String> insertionA = new LinkedHashMap<>();
        insertionA.put("facing", "north");
        insertionA.put("half", "top");
        Map<String, String> insertionB = new LinkedHashMap<>();
        insertionB.put("half", "top");
        insertionB.put("facing", "north");

        BlockState a = new BlockState("minecraft:oak_stairs", insertionA);
        BlockState b = new BlockState("minecraft:oak_stairs", insertionB);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        // SortedMap contract: first key is the lexicographically smallest.
        assertEquals("facing", a.getProps().keySet().iterator().next());
    }

    @Test
    void unequalWhenIdDiffers() {
        assertNotEquals(new BlockState("minecraft:stone"), new BlockState("minecraft:dirt"));
    }

    @Test
    void unequalWhenPropsDiffer() {
        Map<String, String> p1 = new TreeMap<>();
        p1.put("facing", "north");
        Map<String, String> p2 = new TreeMap<>();
        p2.put("facing", "south");
        assertNotEquals(
                new BlockState("minecraft:oak_stairs", p1),
                new BlockState("minecraft:oak_stairs", p2));
    }

    @Test
    void propsMapIsImmutable() {
        Map<String, String> props = new TreeMap<>();
        props.put("facing", "north");
        BlockState state = new BlockState("minecraft:oak_stairs", props);
        assertThrows(UnsupportedOperationException.class,
                () -> state.getProps().put("facing", "south"));
    }

    @Test
    void mutatingSourceMapDoesNotAffectState() {
        Map<String, String> props = new TreeMap<>();
        props.put("facing", "north");
        BlockState state = new BlockState("minecraft:oak_stairs", props);
        props.put("facing", "south");
        assertEquals("north", state.getProps().get("facing"));
    }

    @Test
    void nullIdRejected() {
        assertThrows(NullPointerException.class, () -> new BlockState(null));
    }

    @Test
    void airConstantHasAirId() {
        assertTrue(BlockState.AIR.getProps().isEmpty());
        assertEquals("minecraft:air", BlockState.AIR.getId());
    }
}
