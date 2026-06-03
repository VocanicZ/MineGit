package com.minegit.core.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.diff.WorldDiffer;
import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.WorldDiff;
import com.minegit.core.repo.RepoLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineGitRepoTest {

    private static Clock fixedClock(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    @Test
    void init_createsGitRepoWithMetadataFilesCommitted(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            RepoLayout layout = new RepoLayout(dir);
            assertTrue(Files.isDirectory(dir.resolve(".git")), "git repo created");
            assertTrue(Files.isRegularFile(layout.minegitJsonPath()), "minegit.json present");
            assertTrue(Files.isRegularFile(layout.levelDatPath()), "level.dat.snbt present");

            List<CommitInfo> log = repo.log();
            assertEquals(1, log.size(), "init makes one commit");
        }

        // The metadata files are actually committed (visible via JGit on HEAD).
        try (Git git = Git.open(dir.toFile())) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            PersonIdent committer = head.getCommitterIdent();
            assertEquals("MineGit", committer.getName());
            assertEquals("minegit@local", committer.getEmailAddress());
        }
    }

    @Test
    void commit_writesMgcForDirtyChunks_withPlayerAuthorAndMineGitCommitter(@TempDir Path dir)
            throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, new BlockState("minecraft:stone"));
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            CommitInfo info = repo.commit("place stone", Author.of("Steve"));
            assertNotNull(info, "a real change produces a commit");

            RepoLayout layout = new RepoLayout(dir);
            Path mgc = layout.chunkPath(DimensionId.OVERWORLD, new ChunkPos(0, 0));
            assertTrue(Files.isRegularFile(mgc), ".mgc written for the dirty chunk");

            assertEquals("Steve", info.getAuthor());
            assertEquals("place stone", info.getMessage());
            assertEquals(1000L, info.getEpochSeconds());
        }

        try (Git git = Git.open(dir.toFile())) {
            RevCommit head = git.log().setMaxCount(1).call().iterator().next();
            assertEquals("Steve", head.getAuthorIdent().getName());
            assertEquals("MineGit", head.getCommitterIdent().getName());
            assertEquals("minegit@local", head.getCommitterIdent().getEmailAddress());
        }
    }

    @Test
    void recommit_withNoRealChange_yieldsNoGitDelta(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, new BlockState("minecraft:stone"));
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            repo.commit("place stone", Author.of("Steve"));
            int sizeAfterFirst = repo.log().size();

            // Re-set the SAME block: the chunk is marked dirty but its canonical .mgc is identical.
            world.setBlock(DimensionId.OVERWORLD, 1, 5, 2, new BlockState("minecraft:stone"));
            CommitInfo second = repo.commit("noop", Author.of("Steve"));

            assertNull(second, "byte-identical re-serialization produces no commit");
            assertEquals(sizeAfterFirst, repo.log().size(), "no new commit in the log");
        }
    }

    @Test
    void log_returnsCommitsNewestFirst(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitRepo.init(dir, world, fixedClock(1000)).close();

        world.setBlock(DimensionId.OVERWORLD, 0, 0, 0, new BlockState("minecraft:dirt"));
        try (MineGitRepo repo = MineGitRepo.open(dir, world, fixedClock(2000))) {
            repo.commit("first", Author.of("Alice"));
        }

        world.setBlock(DimensionId.OVERWORLD, 20, 0, 20, new BlockState("minecraft:stone"));
        try (MineGitRepo repo = MineGitRepo.open(dir, world, fixedClock(3000))) {
            repo.commit("second", Author.of("Bob"));

            List<CommitInfo> log = repo.log();
            assertEquals(3, log.size());
            assertEquals("second", log.get(0).getMessage());
            assertEquals("Bob", log.get(0).getAuthor());
            assertEquals(3000L, log.get(0).getEpochSeconds());
            assertEquals("first", log.get(1).getMessage());
            assertEquals("Alice", log.get(1).getAuthor());
            assertEquals(2000L, log.get(1).getEpochSeconds());
            assertEquals("Initialize MineGit repository", log.get(2).getMessage());
        }
    }

    @Test
    void readChunk_decodesFromHeadTree_withoutCheckout(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 3, 7, 4, new BlockState("minecraft:stone"));
        ChunkPos pos = new ChunkPos(0, 0);
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            repo.commit("place", Author.of("Steve"));
            NormalizedChunk expected = world.read(DimensionId.OVERWORLD, pos);

            // Delete the working-tree file: a correct reader sources bytes from the object DB.
            RepoLayout layout = new RepoLayout(dir);
            Files.delete(layout.chunkPath(DimensionId.OVERWORLD, pos));

            NormalizedChunk fromHead = repo.readChunk("HEAD", DimensionId.OVERWORLD, pos);
            assertEquals(expected, fromHead);

            assertNull(repo.readChunk("HEAD", DimensionId.OVERWORLD, new ChunkPos(99, 99)),
                "absent chunk decodes to null");
        }
    }

    @Test
    void branch_createsLocalBranchAtHead(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            BranchRef ref = repo.branch("feature");
            assertEquals("feature", ref.getName());
            assertEquals(false, ref.isRemote());
        }

        // The new ref exists in the object DB and points at the same commit as HEAD.
        try (Git git = Git.open(dir.toFile())) {
            ObjectId head = git.getRepository().resolve("HEAD");
            ObjectId feature = git.getRepository().resolve("refs/heads/feature");
            assertNotNull(feature, "branch ref created");
            assertEquals(head, feature, "branch points at HEAD");
        }
    }

    @Test
    void branches_distinguishesLocalFromRemoteTracking(@TempDir Path dir) throws Exception {
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            repo.branch("feature");

            // Forge a remote-tracking ref directly in the object DB (no network).
            try (Git git = Git.open(dir.toFile())) {
                ObjectId head = git.getRepository().resolve("HEAD");
                RefUpdate ru = git.getRepository().updateRef("refs/remotes/origin/main");
                ru.setNewObjectId(head);
                ru.forceUpdate();
            }

            List<BranchRef> all = repo.branches();
            Set<String> local = new HashSet<String>();
            Set<String> remote = new HashSet<String>();
            for (BranchRef b : all) {
                (b.isRemote() ? remote : local).add(b.getName());
            }

            assertTrue(local.contains("feature"), "local branch listed: " + all);
            assertTrue(local.contains("master"), "default local branch listed: " + all);
            assertTrue(remote.contains("origin/main"), "remote-tracking branch listed: " + all);
            assertTrue(remote.contains("feature") == false, "feature is not remote: " + all);
            assertTrue(local.contains("origin/main") == false, "origin/main is not local: " + all);
        }
    }

    @Test
    void checkout_appliesHeadToTargetDelta_andMovesRef(@TempDir Path dir) throws Exception {
        BlockState stone = new BlockState("minecraft:stone");
        BlockState dirt = new BlockState("minecraft:dirt");
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, stone);
            repo.commit("A", "Steve");
            world.setBlock(DimensionId.OVERWORLD, 100, 64, 0, dirt);
            repo.commit("B", "Steve");

            // Revert the world to commit A (one commit back).
            WorldDiff applied = repo.checkout("HEAD~1");

            // The dirt placed in B was removed from the live world; the stone from A remains.
            assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 100, 64, 0));
            assertEquals(stone, world.getBlock(DimensionId.OVERWORLD, 0, 64, 0));

            // The returned WorldDiff is exactly the applied delta (one removal).
            assertEquals(1, applied.getRemoved(), "one block removed by the revert");
            assertEquals(0, applied.getAdded());
            assertEquals(0, applied.getChanged());

            // The local ref moved: B is no longer on the current branch.
            List<CommitInfo> log = repo.log();
            assertEquals(2, log.size(), "branch reset to A (init + A)");
            assertEquals("A", log.get(0).getMessage());

            // The working tree is now clean against the new HEAD.
            WorldDiff afterCheckout = WorldDiffer.diffWorkingTree(repo, world);
            assertTrue(afterCheckout.getDimensions().isEmpty(), "clean after checkout");
        }
    }

    @Test
    void checkout_refusesDirtyTree_unlessForced(@TempDir Path dir) throws Exception {
        BlockState stone = new BlockState("minecraft:stone");
        BlockState dirt = new BlockState("minecraft:dirt");
        BlockState glass = new BlockState("minecraft:glass");
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(dir, world, fixedClock(1000))) {
            world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, stone);
            repo.commit("A", "Steve");
            world.setBlock(DimensionId.OVERWORLD, 100, 64, 0, dirt);
            repo.commit("B", "Steve");

            // Uncommitted live edit makes the working tree dirty.
            world.setBlock(DimensionId.OVERWORLD, 200, 64, 0, glass);

            assertThrows(
                    WorkingTreeDirtyException.class,
                    () -> repo.checkout("HEAD~1"),
                    "dirty tree refused without force");

            // Forced checkout bypasses the guard and applies HEAD -> target anyway.
            WorldDiff applied = repo.checkout("HEAD~1", true);
            assertEquals(1, applied.getRemoved(), "dirt removed by the forced revert");
            List<CommitInfo> log = repo.log();
            assertEquals("A", log.get(0).getMessage(), "ref moved to A under force");
        }
    }
}
