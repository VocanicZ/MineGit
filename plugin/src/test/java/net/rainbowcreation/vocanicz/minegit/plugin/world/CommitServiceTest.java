package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@link CommitService} owns the commit threading model (Spec B §6): loaded chunks are read on
 * the <strong>main-thread</strong> executor (throttled across ticks), then {@code serialize + git}
 * runs on the <strong>async</strong> executor, and the player is messaged on completion back on the
 * main thread. The synchronous recording executors here run every task inline so the whole hop chain
 * resolves within the test while still proving which executor each step landed on.
 */
class CommitServiceTest {

    @TempDir
    Path repoDir;

    /** Runs each task inline and records how many tasks it was handed. */
    private static final class RecordingExecutor implements Executor {
        int tasks;

        @Override
        public void execute(Runnable command) {
            tasks++;
            command.run();
        }
    }

    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1780576496L), ZoneOffset.UTC);

    private MineGitRepo initRepo(FakeWorldAdapter world) {
        MineGitRepo repo = MineGitRepo.init(repoDir, world, clock);
        repo.close();
        return MineGitRepo.open(repoDir, world, clock);
    }

    @Test
    void commitSnapshotsTheWorldAndRecordsThePlayerAsAuthor() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        initRepo(world).close();

        RecordingExecutor main = new RecordingExecutor();
        RecordingExecutor async = new RecordingExecutor();
        CommitService service = new CommitService(main, async, 16);

        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "build a tower", new Author("Steve", "steve@players.minegit.local"),
                null, result::set);

        CommitService.Result r = result.get();
        assertNotNull(r, "completion callback fired");
        assertFalse(r.isError(), "commit succeeded");
        CommitInfo info = r.commit();
        assertNotNull(info, "a commit was produced");
        assertEquals("Steve", info.getAuthor(), "author is the in-game player");
        assertEquals("build a tower", info.getMessage());

        // The git work ran on the async executor, never on the main thread.
        assertTrue(async.tasks >= 1, "serialize + git ran on the async executor");

        // And the commit is now visible in the log.
        try (MineGitRepo repo = MineGitRepo.open(repoDir, world, clock)) {
            List<CommitInfo> log = repo.log();
            assertEquals("Steve", log.get(0).getAuthor());
            assertEquals("build a tower", log.get(0).getMessage());
        }
    }

    @Test
    void readsAreThrottledAcrossMultipleMainThreadTasks() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        // Three distinct loaded chunks.
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        world.setBlock(DimensionId.OVERWORLD, 20, 64, 0, new BlockState("minecraft:stone"));
        world.setBlock(DimensionId.OVERWORLD, 40, 64, 0, new BlockState("minecraft:stone"));
        initRepo(world).close();

        RecordingExecutor main = new RecordingExecutor();
        RecordingExecutor async = new RecordingExecutor();
        CommitService service = new CommitService(main, async, 1); // one chunk per tick

        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "spread", new Author("Steve", "steve@players.minegit.local"),
                null, result::set);

        assertNotNull(result.get().commit());
        // 3 chunks at 1/tick => at least 3 main-thread read passes (plus the completion hop).
        assertTrue(main.tasks >= 3, "reads spread across ticks, got " + main.tasks + " main tasks");
    }

    @Test
    void nothingToCommitYieldsANullCommitWithoutError() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        // init already snapshots all chunks, so a second commit with no edits has nothing to do.
        MineGitRepo repo = initRepo(world);
        repo.commit("first", new Author("Steve", "steve@players.minegit.local"));
        repo.close();

        CommitService service = new CommitService(new RecordingExecutor(), new RecordingExecutor(), 16);
        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "nothing changed",
                new Author("Steve", "steve@players.minegit.local"), null, result::set);

        CommitService.Result r = result.get();
        assertFalse(r.isError());
        assertNull(r.commit(), "no delta => no commit");
    }

    @Test
    void firstCommitPrimesTheTrackerAndCapturesAllChunks() {
        net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet tracker =
                new net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet();
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        initRepo(world).close();

        CommitService service = new CommitService(new RecordingExecutor(), new RecordingExecutor(), 16);
        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        // Not primed: a full scan runs and the tracker is primed for subsequent incremental commits.
        service.commit(repoDir, world, clock, "full first pass",
                new Author("Steve", "steve@players.minegit.local"), tracker, result::set);

        assertFalse(result.get().isError());
        assertTrue(tracker.isPrimed(), "first commit primes the tracker");
    }

    @Test
    void primedCommitSnapshotsOnlyTheDirtyChunks() {
        net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet tracker =
                new net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet();
        tracker.prime(); // already reconciled; trust the dirty set exclusively
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        initRepo(world).close();
        // The init snapshot already recorded the stone; with a primed tracker and an empty dirty set
        // (no edits since), the commit drains nothing and produces no new commit.
        world.drainDirty(); // clear the adapter's own dirty set so the primed drain is empty

        CommitService service = new CommitService(new RecordingExecutor(), new RecordingExecutor(), 16);
        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "incremental",
                new Author("Steve", "steve@players.minegit.local"), tracker, result::set);

        CommitService.Result r = result.get();
        assertFalse(r.isError());
        assertNull(r.commit(), "primed commit with an empty dirty set captures nothing => no commit");
        assertTrue(tracker.isPrimed(), "tracker stays primed");
    }

    @Test
    void readsHappenBeforeTheAsyncGitStep() {
        // A world whose read() records the order of calls relative to the async hop.
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        initRepo(world).close();

        List<String> order = new ArrayList<String>();
        Executor main = command -> {
            order.add("main");
            command.run();
        };
        Executor async = command -> {
            order.add("async");
            command.run();
        };
        CommitService service = new CommitService(main, async, 16);
        service.commit(repoDir, world, clock, "ordered",
                new Author("Steve", "steve@players.minegit.local"), null, r -> {});

        assertEquals("main", order.get(0), "first hop is a main-thread read pass");
        assertTrue(order.contains("async"), "git step is dispatched to async");
    }
}
