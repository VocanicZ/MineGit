package net.rainbowcreation.vocanicz.minegit.plugin.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Modern (1.13+) servers expose a block as a flattened state string like
 * {@code "minecraft:oak_stairs[facing=east,half=bottom,waterlogged=false]"} via the reflected
 * {@code BlockData.getAsString()} call. The reflection itself is validated in-game (Spec B §3), but
 * the pure string {@code <-> BlockState} translation is Bukkit-free and unit-tested here.
 */
class FlattenedBlockStatesTest {

    @Test
    void parsesBareIdWithoutProperties() {
        BlockState s = FlattenedBlockStates.parse("minecraft:stone");
        assertEquals("minecraft:stone", s.getId());
        assertTrue(s.getProps().isEmpty(), "bare id has no properties");
    }

    @Test
    void parsesIdWithProperties() {
        BlockState s = FlattenedBlockStates.parse("minecraft:oak_stairs[facing=east,half=bottom]");
        assertEquals("minecraft:oak_stairs", s.getId());
        assertEquals("east", s.getProps().get("facing"));
        assertEquals("bottom", s.getProps().get("half"));
        assertEquals(2, s.getProps().size());
    }

    @Test
    void formatsBareId() {
        assertEquals("minecraft:stone", FlattenedBlockStates.format(new BlockState("minecraft:stone")));
    }

    @Test
    void formatsPropertiesSortedByKey() {
        Map<String, String> props = new LinkedHashMap<String, String>();
        // Insertion order is deliberately non-alphabetical to prove the output is key-sorted.
        props.put("half", "bottom");
        props.put("facing", "east");
        BlockState s = new BlockState("minecraft:oak_stairs", props);
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", FlattenedBlockStates.format(s));
    }

    @Test
    void roundTripsFlattenedStringThroughParseAndFormat() {
        String in = "minecraft:redstone_wire[east=up,north=side,power=15,south=none,west=none]";
        assertEquals(in, FlattenedBlockStates.format(FlattenedBlockStates.parse(in)));
    }

    @Test
    void parseTrimsSurroundingWhitespace() {
        BlockState s = FlattenedBlockStates.parse("  minecraft:dirt  ");
        assertEquals("minecraft:dirt", s.getId());
    }

    @Test
    void parseRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> FlattenedBlockStates.parse(""));
        assertThrows(IllegalArgumentException.class, () -> FlattenedBlockStates.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> FlattenedBlockStates.parse(null));
    }

    @Test
    void parseRejectsMalformedProperty() {
        assertThrows(
                IllegalArgumentException.class,
                () -> FlattenedBlockStates.parse("minecraft:oak_stairs[facing]"));
    }
}
