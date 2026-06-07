package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class DirtySectionTrackerTest {

    private static final DimensionId DIM = DimensionId.OVERWORLD;

    @Test
    void markBlockMarksContainingSectionOnce() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 3, 70, 5);   // section (0,0), sy=4
        t.markBlock(DIM, 4, 71, 6);   // same section -> deduped
        assertEquals(1, t.size());
    }

    @Test
    void popBudgetReturnsAtMostNAndRemovesThem() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 0, 0, 0);
        t.markBlock(DIM, 0, 16, 0);
        t.markBlock(DIM, 0, 32, 0);
        List<DirtySectionTracker.Section> first = t.popBudget(2);
        assertEquals(2, first.size());
        assertEquals(1, t.size());
        List<DirtySectionTracker.Section> second = t.popBudget(2);
        assertEquals(1, second.size());
        assertEquals(0, t.size());
    }

    @Test
    void popBudgetOnEmptyReturnsEmpty() {
        DirtySectionTracker t = new DirtySectionTracker();
        assertTrue(t.popBudget(4).isEmpty());
    }

    @Test
    void markChunkMarksEverySectionInRange() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markChunk(DIM, new ChunkPos(0, 0), 0, 4); // sy 0..3
        assertEquals(4, t.size());
    }

    @Test
    void dropDimensionClearsOnlyThatDimension() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 0, 0, 0);
        t.markBlock(DimensionId.THE_NETHER, 0, 0, 0);
        t.dropDimension(DIM);
        assertEquals(1, t.size());
        assertTrue(t.popBudget(8).get(0).dimension().equals(DimensionId.THE_NETHER));
    }

    @Test
    void clearEmptiesTheSet() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 0, 0, 0);
        t.clear();
        assertEquals(0, t.size());
    }

    @Test
    void sectionHasValueEquality() {
        DirtySectionTracker.Section a = new DirtySectionTracker.Section(DIM, new ChunkPos(0, 0), 4);
        DirtySectionTracker.Section b = new DirtySectionTracker.Section(DIM, new ChunkPos(0, 0), 4);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals(4, a.sectionY());
        assertEquals(new ChunkPos(0, 0), a.chunk());
        assertEquals(DIM, a.dimension());
    }
}
