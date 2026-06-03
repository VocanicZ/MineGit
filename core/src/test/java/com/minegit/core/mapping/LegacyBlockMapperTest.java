package com.minegit.core.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.model.BlockState;
import org.junit.jupiter.api.Test;

class LegacyBlockMapperTest {

    private final LegacyBlockMapper mapper = new LegacyBlockMapper();

    @Test
    void mapsCommonBlockWithZeroMeta() {
        assertEquals(new BlockState("minecraft:stone"), mapper.map(1, 0));
        assertEquals(new BlockState("minecraft:cobblestone"), mapper.map(4, 0));
        assertEquals(new BlockState("minecraft:bedrock"), mapper.map(7, 0));
    }

    @Test
    void mapsMetaVariantsToDistinctFlattenedIds() {
        assertEquals(new BlockState("minecraft:stone"), mapper.map(1, 0));
        assertEquals(new BlockState("minecraft:granite"), mapper.map(1, 1));
        assertEquals(new BlockState("minecraft:diorite"), mapper.map(1, 3));
        assertEquals(new BlockState("minecraft:andesite"), mapper.map(1, 5));
    }

    @Test
    void mapsWoolColorMetas() {
        assertEquals(new BlockState("minecraft:white_wool"), mapper.map(35, 0));
        assertEquals(new BlockState("minecraft:red_wool"), mapper.map(35, 14));
        assertEquals(new BlockState("minecraft:black_wool"), mapper.map(35, 15));
    }

    @Test
    void airMapsToMinecraftAir() {
        assertEquals(BlockState.AIR, mapper.map(0, 0));
    }

    @Test
    void unknownIdFallsBackToMinegitUnknownCarryingLegacyCoords() {
        BlockState s = mapper.map(9999, 7);
        assertEquals("minegit:unknown", s.getId());
        assertEquals("9999", s.getProps().get("legacy_id"));
        assertEquals("7", s.getProps().get("legacy_meta"));
    }

    @Test
    void unknownMetaForKnownIdFallsBack() {
        // id 1 (stone family) has no meta 13 defined -> fallback, not silently lost.
        BlockState s = mapper.map(1, 13);
        assertEquals("minegit:unknown", s.getId());
        assertEquals("1", s.getProps().get("legacy_id"));
        assertEquals("13", s.getProps().get("legacy_meta"));
    }

    @Test
    void tableParsesAndCoversManyCommonEntries() {
        // Framework loads the bundled table; sanity-check it parsed a meaningful number of rows.
        assertTrue(mapper.size() >= 40, "expected a non-trivial common table, got " + mapper.size());
    }

    @Test
    void reverseRecoversLegacyCoordsForReversibleEntries() {
        LegacyBlockMapper.LegacyId id = mapper.reverse(new BlockState("minecraft:granite"));
        assertNotNull(id);
        assertEquals(1, id.blockId);
        assertEquals(1, id.meta);
        // round-trip
        assertEquals(new BlockState("minecraft:granite"), mapper.map(id.blockId, id.meta));
    }

    @Test
    void reverseReturnsNullForUnmappedState() {
        assertEquals(null, mapper.reverse(new BlockState("minecraft:does_not_exist")));
    }

    @Test
    void mapperIsStatelessAcrossCalls() {
        // Repeated calls return equal results (no mutation of shared state).
        assertEquals(mapper.map(1, 1), mapper.map(1, 1));
        assertFalse(mapper.map(1, 0).equals(mapper.map(1, 1)));
    }
}
