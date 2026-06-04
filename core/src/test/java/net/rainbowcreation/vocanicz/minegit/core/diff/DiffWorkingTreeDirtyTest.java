package net.rainbowcreation.vocanicz.minegit.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link WorldDiffer#diffWorkingTreeDirty}: the dirty-aware working-tree diff that only
 * scans chunks present in {@link net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter#peekDirty()}.
 */
class DiffWorkingTreeDirtyTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    /**
     * After committing a baseline and draining dirty, a new edit in the same chunk must cause
     * {@code diffWorkingTreeDirty} to report the same change counts as the full {@code diffWorkingTree}.
     */
    @Test
    void dirtyDiffMatchesFullDiffForAChangedChunk(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        // Place a block in chunk (0,0) as baseline.
        world.setBlock(DimensionId.OVERWORLD, 0, 0, 0, STONE);
        try (MineGitRepo repo = MineGitRepo.init(dir, world)) {
            repo.commit("base", "tester");
            // Reset the dirty set so only future edits are tracked.
            world.drainDirty();

            // Place another block in the same chunk (0,0) — chunk is now dirty again.
            world.setBlock(DimensionId.OVERWORLD, 1, 0, 0, DIRT);

            WorldDiff dirtyDiff = WorldDiffer.diffWorkingTreeDirty(repo, world);
            WorldDiff fullDiff = WorldDiffer.diffWorkingTree(repo, world);

            assertEquals(fullDiff.getAdded(), dirtyDiff.getAdded(),
                    "dirty diff added count must match full diff");
            assertEquals(fullDiff.getRemoved(), dirtyDiff.getRemoved(),
                    "dirty diff removed count must match full diff");
            assertEquals(fullDiff.getChanged(), dirtyDiff.getChanged(),
                    "dirty diff changed count must match full diff");
        }
    }

    /**
     * After committing and draining dirty, with no further changes, {@code diffWorkingTreeDirty}
     * must return an empty diff (0 added, 0 removed, 0 changed).
     */
    @Test
    void dirtyDiffIsEmptyWhenNothingMarked(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 0, 0, STONE);
        try (MineGitRepo repo = MineGitRepo.init(dir, world)) {
            repo.commit("base", "tester");
            // Drain dirty — nothing is dirty now.
            world.drainDirty();

            WorldDiff dirtyDiff = WorldDiffer.diffWorkingTreeDirty(repo, world);

            assertEquals(0, dirtyDiff.getAdded(), "no additions when dirty set is empty");
            assertEquals(0, dirtyDiff.getRemoved(), "no removals when dirty set is empty");
            assertEquals(0, dirtyDiff.getChanged(), "no changes when dirty set is empty");
        }
    }
}
