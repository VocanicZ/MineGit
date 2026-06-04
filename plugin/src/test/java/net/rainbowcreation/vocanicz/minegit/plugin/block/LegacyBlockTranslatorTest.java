package net.rainbowcreation.vocanicz.minegit.plugin.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper;
import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper.LegacyId;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import org.junit.jupiter.api.Test;

/**
 * The legacy (1.8–1.12) bridge speaks numeric {@code (id, meta)}; core's {@link LegacyBlockMapper}
 * is the platform-agnostic translation table. This covers the Bukkit-free seam the bridge uses:
 * read = {@code (id, meta) -> BlockState}, write = {@code BlockState -> (id, meta)} (Spec B §3,
 * "legacy id/meta mapping round-trip"). The Bukkit {@code Block} plumbing itself is in-game tested.
 */
class LegacyBlockTranslatorTest {

    private final LegacyBlockMapper mapper = new LegacyBlockMapper();

    @Test
    void readMapsIdAndMetaThroughTheCoreMapper() {
        // The translator's read side is exactly the core mapper; assert it delegates faithfully.
        assertEquals(mapper.map(1, 0), LegacyBlockTranslator.toState(mapper, 1, 0));
    }

    @Test
    void unknownCoordRoundTripsViaFallbackProperties() {
        // An id/meta absent from the common-blocks table flattens to a flagged minegit:unknown state
        // carrying legacy_id/legacy_meta; the write side must recover the exact original coord so an
        // unrecognised block survives a commit -> checkout cycle losslessly.
        BlockState unknown = LegacyBlockTranslator.toState(mapper, 2999, 7);
        assertEquals(LegacyBlockMapper.UNKNOWN_ID, unknown.getId());

        LegacyId coord = LegacyBlockTranslator.toCoord(mapper, unknown);
        assertNotNull(coord);
        assertEquals(2999, coord.blockId);
        assertEquals(7, coord.meta);
    }

    @Test
    void knownStateReversesToACoordThatMapsBackToTheSameState() {
        // Reverse lookup may renumber (the table can be lossy/ambiguous), so assert the semantic
        // round-trip: whatever coord we pick must flatten back to the state we started from.
        BlockState state = LegacyBlockTranslator.toState(mapper, 1, 0);
        LegacyId coord = LegacyBlockTranslator.toCoord(mapper, state);
        assertNotNull(coord);
        assertEquals(state, mapper.map(coord.blockId, coord.meta));
    }

    @Test
    void unmappableStateHasNoLegacyCoord() {
        // A flattened-only id (no table entry, not a fallback) cannot be expressed on a 1.8 server;
        // the translator reports null so the bridge can skip it rather than write garbage.
        assertNull(LegacyBlockTranslator.toCoord(mapper, new BlockState("minecraft:cherry_planks")));
    }
}
