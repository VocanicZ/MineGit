package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModWorldAdapter} dirty-set integration: when a {@link DirtyChunkSet} is
 * injected, {@link ModWorldAdapter#peekDirty()} reflects the dirty set without clearing it, and
 * {@link ModWorldAdapter#drainDirty()} clears it.
 */
class ModWorldAdapterDirtyTest {

    private static final DimensionId DIM = DimensionId.OVERWORLD;

    @Test
    void peekDirtyReflectsDirtySetWithoutClearing() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        DirtyChunkSet dirty = new DirtyChunkSet();
        ModWorldAdapter adapter = new ModWorldAdapter(level, dirty);

        ChunkRef ref = new ChunkRef(DIM, new ChunkPos(3, 7));
        dirty.markDirty(ref);

        // peek twice — both should see size 1 (non-clearing)
        Set<ChunkRef> peek1 = adapter.peekDirty();
        assertEquals(1, peek1.size(), "peekDirty should return one dirty chunk");
        assertTrue(peek1.contains(ref));

        Set<ChunkRef> peek2 = adapter.peekDirty();
        assertEquals(1, peek2.size(), "peekDirty should still return one dirty chunk on second call");
    }

    @Test
    void drainDirtyReturnsDirtySetAndClearsIt() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        DirtyChunkSet dirty = new DirtyChunkSet();
        ModWorldAdapter adapter = new ModWorldAdapter(level, dirty);

        ChunkRef ref = new ChunkRef(DIM, new ChunkPos(5, -2));
        dirty.markDirty(ref);

        Set<ChunkRef> drained = adapter.drainDirty();
        assertEquals(1, drained.size(), "drainDirty should return one dirty chunk");
        assertTrue(drained.contains(ref));

        // After drain, peek should be empty
        Set<ChunkRef> afterDrain = adapter.peekDirty();
        assertTrue(afterDrain.isEmpty(), "peekDirty should be empty after drainDirty");
    }
}
