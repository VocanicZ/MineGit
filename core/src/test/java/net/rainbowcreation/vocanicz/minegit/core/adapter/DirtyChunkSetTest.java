package net.rainbowcreation.vocanicz.minegit.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirtyChunkSetTest {

    private static ChunkRef ref(int cx, int cz) {
        return new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(cx, cz));
    }

    @Test
    void peekReturnsMarkedRefsWithoutClearing() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(0, 0));
        set.markDirty(ref(1, 2));

        Set<ChunkRef> first = set.peekDirty();
        Set<ChunkRef> second = set.peekDirty();

        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertTrue(first.contains(ref(0, 0)));
        assertTrue(first.contains(ref(1, 2)));
    }

    @Test
    void markIsIdempotent() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(5, 5));
        set.markDirty(ref(5, 5));

        assertEquals(1, set.peekDirty().size());
    }

    @Test
    void drainReturnsRefsAndClears() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(3, 4));

        Set<ChunkRef> drained = set.drainDirty();

        assertEquals(1, drained.size());
        assertTrue(drained.contains(ref(3, 4)));
        assertEquals(0, set.peekDirty().size());
    }

    @Test
    void primedFlagStartsFalseAndCanBeSet() {
        DirtyChunkSet set = new DirtyChunkSet();

        assertFalse(set.isPrimed());

        set.prime();
        assertTrue(set.isPrimed());

        set.unprime();
        assertFalse(set.isPrimed());
    }
}
