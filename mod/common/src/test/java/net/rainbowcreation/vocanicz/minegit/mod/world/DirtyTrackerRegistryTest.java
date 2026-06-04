package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DirtyTrackerRegistry}: verifies per-level identity semantics — the same level
 * key always returns the same {@link DirtyChunkSet} instance, and distinct keys get distinct sets.
 */
class DirtyTrackerRegistryTest {

    @Test
    void sameKeyReturnsSameSet() {
        DirtyTrackerRegistry registry = new DirtyTrackerRegistry();
        DirtyChunkSet first = registry.tracker("minecraft:overworld");
        DirtyChunkSet second = registry.tracker("minecraft:overworld");
        assertNotNull(first);
        assertSame(first, second, "same key should return the same DirtyChunkSet instance");
    }

    @Test
    void differentKeysReturnDifferentSets() {
        DirtyTrackerRegistry registry = new DirtyTrackerRegistry();
        DirtyChunkSet overworld = registry.tracker("minecraft:overworld");
        DirtyChunkSet nether = registry.tracker("minecraft:the_nether");
        assertNotSame(overworld, nether, "different level keys should return distinct DirtyChunkSet instances");
    }
}
