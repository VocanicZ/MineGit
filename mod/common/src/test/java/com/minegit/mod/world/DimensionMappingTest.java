package com.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DimensionMapping}: a level's namespaced key → core {@link DimensionId}. Vanilla
 * dimensions collapse to their bare names; modded dimensions keep their full namespaced id. Pure
 * string logic — no Minecraft (the real {@code ServerLevelAccess} feeds it
 * {@code level.dimension().identifier().toString()}).
 */
class DimensionMappingTest {

    @Test
    void vanillaDimensionsMapToBareConstants() {
        assertEquals(DimensionId.OVERWORLD, DimensionMapping.fromKey("minecraft:overworld"));
        assertEquals(DimensionId.THE_NETHER, DimensionMapping.fromKey("minecraft:the_nether"));
        assertEquals(DimensionId.THE_END, DimensionMapping.fromKey("minecraft:the_end"));
    }

    @Test
    void moddedDimensionKeepsNamespacedId() {
        assertEquals(new DimensionId("mymod:caverns"), DimensionMapping.fromKey("mymod:caverns"));
    }

    @Test
    void unprefixedKeyIsUsedVerbatim() {
        assertEquals(new DimensionId("overworld"), DimensionMapping.fromKey("overworld"));
    }
}
