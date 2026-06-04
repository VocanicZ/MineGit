package net.rainbowcreation.vocanicz.minegit.mod.world;

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
 * The mod's {@link CommitService} owns the commit threading model (Spec D §5): loaded chunks are read
 * on the <strong>server-thread</strong> executor (throttled across ticks), then {@code serialize +
 * git} runs on the <strong>background</strong> executor, and the player is messaged on completion
 * back on the server thread. The synchronous recording executors here run every task inline so the
 * whole hop chain resolves within the test while still proving which executor each step landed on.
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

        RecordingExecutor serverThread = new RecordingExecutor();
        RecordingExecutor background = new RecordingExecutor();
        CommitService service = new CommitService(serverThread, background, 16);

        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "build a tower",
                new Author("Steve", "steve@players.minegit.local"), null, result::set);

        CommitService.Result r = result.get();
        assertNotNull(r, "completion callback fired");
        assertFalse(r.isError(), "commit succeeded");
        CommitInfo info = r.commit();
        assertNotNull(info, "a commit was produced");
        assertEquals("Steve", info.getAuthor(), "author is the in-game player");
        assertEquals("build a tower", info.getMessage());

        // The git work ran on the background executor, never on the server thread.
        assertTrue(background.tasks >= 1, "serialize + git ran on the background executor");

        // And the commit is now visible in the log.
        try (MineGitRepo repo = MineGitRepo.open(repoDir, world, clock)) {
            List<CommitInfo> log = repo.log();
            assertEquals("Steve", log.get(0).getAuthor());
            assertEquals("build a tower", log.get(0).getMessage());
        }
    }

    @Test
    void readsAreThrottledAcrossMultipleServerThreadTasks() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        // Three distinct loaded chunks.
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        world.setBlock(DimensionId.OVERWORLD, 20, 64, 0, new BlockState("minecraft:stone"));
        world.setBlock(DimensionId.OVERWORLD, 40, 64, 0, new BlockState("minecraft:stone"));
        initRepo(world).close();

        RecordingExecutor serverThread = new RecordingExecutor();
        RecordingExecutor background = new RecordingExecutor();
        CommitService service = new CommitService(serverThread, background, 1); // one chunk per tick

        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "spread",
                new Author("Steve", "steve@players.minegit.local"), null, result::set);

        assertNotNull(result.get().commit());
        // 3 chunks at 1/tick => at least 3 server-thread read passes (plus the completion hop).
        assertTrue(serverThread.tasks >= 3,
                "reads spread across ticks, got " + serverThread.tasks + " server tasks");
    }

    @Test
    void nothingToCommitYieldsANullCommitWithoutError() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, new BlockState("minecraft:stone"));
        // init already snapshots all chunks, so a second commit with no edits has nothing to do.
        MineGitRepo repo = initRepo(world);
        repo.commit("first", new Author("Steve", "steve@players.minegit.local"));
        repo.close();

        CommitService service =
                new CommitService(new RecordingExecutor(), new RecordingExecutor(), 16);
        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, world, clock, "nothing changed",
                new Author("Steve", "steve@players.minegit.local"), null, result::set);

        CommitService.Result r = result.get();
        assertFalse(r.isError());
        assertNull(r.commit(), "no delta => no commit");
    }

    @Test
    void readsHappenBeforeTheBackgroundGitStep() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        initRepo(world).close();

        List<String> order = new ArrayList<String>();
        Executor serverThread = command -> {
            order.add("server");
            command.run();
        };
        Executor background = command -> {
            order.add("background");
            command.run();
        };
        CommitService service = new CommitService(serverThread, background, 16);
        service.commit(repoDir, world, clock, "ordered",
                new Author("Steve", "steve@players.minegit.local"), null, r -> { });

        assertEquals("server", order.get(0), "the first hop reads on the server thread");
        assertTrue(order.contains("background"), "git ran on the background executor");
        assertTrue(order.indexOf("server") < order.indexOf("background"),
                "reads precede the background git step");
    }
}
