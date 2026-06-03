package com.minegit.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.git.Author;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioural tests for the {@code core.diff} block-diff engine. No Minecraft imports. */
class WorldDifferTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    // ---- core air-aware ADD / REMOVE / CHANGE -------------------------------------------------

    @Test
    void diff_airToSolid_emitsAdd() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(1, diff.getAdded());
        assertEquals(0, diff.getRemoved());
        assertEquals(0, diff.getChanged());
        BlockChange change = onlyChange(diff, DimensionId.OVERWORLD);
        assertEquals(BlockChange.Kind.ADD, change.getKind());
        assertEquals(1, change.getX());
        assertEquals(5, change.getY());
        assertEquals(2, change.getZ());
        assertEquals(STONE, change.getNewState());
    }

    @Test
    void diff_solidToAir_emitsRemove() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        before.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        FakeWorldAdapter after = new FakeWorldAdapter();

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(0, diff.getAdded());
        assertEquals(1, diff.getRemoved());
        assertEquals(0, diff.getChanged());
        BlockChange change = onlyChange(diff, DimensionId.OVERWORLD);
        assertEquals(BlockChange.Kind.REMOVE, change.getKind());
        assertEquals(STONE, change.getOldState());
    }

    @Test
    void diff_solidToDifferentSolid_emitsChange() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        before.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 1, 5, 2, DIRT);

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(0, diff.getAdded());
        assertEquals(0, diff.getRemoved());
        assertEquals(1, diff.getChanged());
        BlockChange change = onlyChange(diff, DimensionId.OVERWORLD);
        assertEquals(BlockChange.Kind.CHANGE, change.getKind());
        assertEquals(STONE, change.getOldState());
        assertEquals(DIRT, change.getNewState());
    }

    @Test
    void diff_equalChunks_shortCircuitsToEmpty() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        before.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(0, diff.getAdded());
        assertEquals(0, diff.getRemoved());
        assertEquals(0, diff.getChanged());
        assertTrue(diff.getDimensions().isEmpty(), "no dimension entries for an unchanged world");
    }

    // ---- position union: chunks present in only one source ------------------------------------

    @Test
    void diff_chunkPresentInOnlyOneSource_isCovered() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        before.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE); // chunk (0,0), only in before
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 200, 5, 200, DIRT); // chunk (12,12), only in after

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        // before-only chunk -> its solid block vanishes (REMOVE); after-only chunk -> ADD.
        assertEquals(1, diff.getAdded());
        assertEquals(1, diff.getRemoved());
        assertEquals(0, diff.getChanged());
        assertEquals(2, diff.getChunkDiffs(DimensionId.OVERWORLD).size());
    }

    @Test
    void diff_absentChunksOnBothSides_yieldNothing() {
        // before holds an empty (absent) chunk grid; after is empty too.
        WorldDiff diff =
                WorldDiffer.diff(
                        ChunkSources.live(new FakeWorldAdapter()),
                        ChunkSources.live(new FakeWorldAdapter()));

        assertEquals(0, diff.getAdded() + diff.getRemoved() + diff.getChanged());
        assertTrue(diff.getDimensions().isEmpty());
    }

    // ---- multi-dimension aggregation ----------------------------------------------------------

    @Test
    void diff_aggregatesAcrossDimensions() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE); // ADD in overworld
        after.setBlock(DimensionId.THE_NETHER, 1, 5, 2, DIRT); // ADD in nether

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(2, diff.getAdded());
        assertEquals(2, diff.getDimensions().size(), "both dimensions reported");
        assertFalse(diff.getChunkDiffs(DimensionId.OVERWORLD).isEmpty());
        assertFalse(diff.getChunkDiffs(DimensionId.THE_NETHER).isEmpty());
    }

    @Test
    void diff_multipleBlocksInOneChunk_countsAndOrders() {
        FakeWorldAdapter before = new FakeWorldAdapter();
        before.setBlock(DimensionId.OVERWORLD, 0, 0, 0, STONE);
        FakeWorldAdapter after = new FakeWorldAdapter();
        after.setBlock(DimensionId.OVERWORLD, 0, 0, 0, DIRT); // CHANGE
        after.setBlock(DimensionId.OVERWORLD, 2, 1, 3, STONE); // ADD

        WorldDiff diff = WorldDiffer.diff(ChunkSources.live(before), ChunkSources.live(after));

        assertEquals(1, diff.getAdded());
        assertEquals(0, diff.getRemoved());
        assertEquals(1, diff.getChanged());
        List<ChunkDiff> diffs = diff.getChunkDiffs(DimensionId.OVERWORLD);
        assertEquals(1, diffs.size());
        assertEquals(2, diffs.get(0).getChanges().size());
    }

    // ---- live-vs-HEAD and ref-vs-ref over a real JGit repo ------------------------------------

    @Test
    void diffWorkingTree_liveVsHead_seesUncommittedEdit(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        try (MineGitRepo repo = MineGitRepo.init(dir, world)) {
            repo.commit("place stone", Author.of("Steve"));

            // No changes yet -> live equals HEAD -> empty diff.
            WorldDiff clean = WorldDiffer.diffWorkingTree(repo, world);
            assertTrue(clean.getDimensions().isEmpty(), "unchanged world short-circuits");

            // Place a new block; it is uncommitted, so it shows up only against HEAD.
            world.setBlock(DimensionId.OVERWORLD, 3, 6, 4, DIRT);
            WorldDiff diff = WorldDiffer.diffWorkingTree(repo, world);
            assertEquals(1, diff.getAdded());
            assertEquals(0, diff.getRemoved());
            BlockChange change = onlyChange(diff, DimensionId.OVERWORLD);
            assertEquals(BlockChange.Kind.ADD, change.getKind());
            assertEquals(DIRT, change.getNewState());
        }
    }

    @Test
    void diffRefs_refVsRef_seesCommittedChange(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        try (MineGitRepo repo = MineGitRepo.init(dir, world)) {
            CommitInfo first = repo.commit("place stone", Author.of("Steve"));

            world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, DIRT); // change the same block
            CommitInfo second = repo.commit("swap to dirt", Author.of("Alex"));

            WorldDiff diff = WorldDiffer.diffRefs(repo, first.getId(), second.getId());
            assertEquals(0, diff.getAdded());
            assertEquals(0, diff.getRemoved());
            assertEquals(1, diff.getChanged());
            BlockChange change = onlyChange(diff, DimensionId.OVERWORLD);
            assertEquals(BlockChange.Kind.CHANGE, change.getKind());
            assertEquals(STONE, change.getOldState());
            assertEquals(DIRT, change.getNewState());
        }
    }

    @Test
    void treeSource_indexesChunksAndDimensions(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, STONE);
        world.setBlock(DimensionId.THE_NETHER, 1, 5, 2, DIRT);
        try (MineGitRepo repo = MineGitRepo.init(dir, world)) {
            repo.commit("place", Author.of("Steve"));

            ChunkSource head = ChunkSources.tree(repo, "HEAD");
            assertEquals(2, head.dimensions().size());
            assertTrue(head.chunks(DimensionId.OVERWORLD).contains(new ChunkPos(0, 0)));
            assertTrue(head.chunks(DimensionId.THE_NETHER).contains(new ChunkPos(0, 0)));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static BlockChange onlyChange(WorldDiff diff, DimensionId dim) {
        List<ChunkDiff> chunkDiffs = diff.getChunkDiffs(dim);
        assertEquals(1, chunkDiffs.size(), "exactly one chunk changed");
        List<BlockChange> changes = chunkDiffs.get(0).getChanges();
        assertEquals(1, changes.size(), "exactly one block changed");
        return changes.get(0);
    }
}
