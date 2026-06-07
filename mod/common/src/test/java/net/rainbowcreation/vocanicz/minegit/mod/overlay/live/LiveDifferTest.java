package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import org.junit.jupiter.api.Test;

class LiveDifferTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1); // Y in [0,16)
        level.addLoadedChunk(0, 0);
        return level;
    }

    private static DirtySectionTracker.Section section0() {
        return new DirtySectionTracker.Section(DIM, new ChunkPos(0, 0), 0);
    }

    @Test
    void unchangedSectionYieldsNoBoxes() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertTrue(changes.isEmpty());
    }

    @Test
    void freezeSurvivesEdit_cleanBlockEditRaisesBox_baselineUnchanged() {
        // THE §3 CORRECTNESS GUARD (regression for the rejected rebuild-on-load bug).
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE); // clean at seed
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);

        level.setBlock(2, 5, 2, DIRT); // player edits previously-clean block
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);

        assertEquals(1, changes.size());
        BlockChange c = changes.get(0);
        assertEquals(BlockChange.Kind.CHANGE, c.getKind());
        assertEquals(STONE, c.getOldState()); // baseline still pre-edit HEAD
        assertEquals(DIRT, c.getNewState());
        assertEquals(STONE, cache.headAt(DIM, 2, 5, 2)); // baseline itself unchanged
    }

    @Test
    void placingIntoAirHeadEmitsAdd() {
        FakeLevelAccess level = world();
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level); // all air HEAD
        level.setBlock(4, 5, 4, STONE);
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertEquals(1, changes.size());
        assertEquals(BlockChange.Kind.ADD, changes.get(0).getKind());
        assertEquals(STONE, changes.get(0).getNewState());
    }

    @Test
    void breakingHeadBlockEmitsRemove() {
        FakeLevelAccess level = world();
        level.setBlock(6, 5, 6, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        level.setBlock(6, 5, 6, BlockState.AIR); // broken in working
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertEquals(1, changes.size());
        assertEquals(BlockChange.Kind.REMOVE, changes.get(0).getKind());
        assertEquals(STONE, changes.get(0).getOldState());
    }

    @Test
    void revertedEditClearsBox() {
        FakeLevelAccess level = world();
        level.setBlock(7, 5, 7, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        level.setBlock(7, 5, 7, DIRT); // edited
        assertEquals(1, new LiveDiffer().diffSection(section0(), cache, level).size());
        level.setBlock(7, 5, 7, STONE); // reverted to HEAD
        assertTrue(new LiveDiffer().diffSection(section0(), cache, level).isEmpty());
    }

    @Test
    void reportsCorrectWorldCoordinates() {
        FakeLevelAccess level = world();
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        level.setBlock(10, 12, 14, STONE);
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertEquals(1, changes.size());
        BlockChange c = changes.get(0);
        assertEquals(10, c.getX());
        assertEquals(12, c.getY());
        assertEquals(14, c.getZ());
    }
}
