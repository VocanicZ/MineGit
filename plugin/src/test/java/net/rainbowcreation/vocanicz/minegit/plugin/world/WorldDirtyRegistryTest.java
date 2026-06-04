package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WorldDirtyRegistry}: verifies per-world identity semantics — the same world name
 * always returns the same {@link DirtyChunkSet} instance, and distinct names get distinct sets.
 */
class WorldDirtyRegistryTest {

    @Test
    void sameNameReturnsSameSet() {
        WorldDirtyRegistry registry = new WorldDirtyRegistry();
        DirtyChunkSet first = registry.tracker("world");
        DirtyChunkSet second = registry.tracker("world");
        assertNotNull(first);
        assertSame(first, second, "same name should return the same DirtyChunkSet instance");
    }

    @Test
    void differentNamesReturnDifferentSets() {
        WorldDirtyRegistry registry = new WorldDirtyRegistry();
        DirtyChunkSet overworld = registry.tracker("world");
        DirtyChunkSet nether = registry.tracker("world_nether");
        assertNotSame(overworld, nether, "different world names should return distinct DirtyChunkSet instances");
    }
}
