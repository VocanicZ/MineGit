package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.git.UnknownRefException;
import net.rainbowcreation.vocanicz.minegit.core.git.WorkingTreeDirtyException;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mod's {@link CheckoutService} owns the checkout threading model (Spec D §5, issue #63): loaded
 * chunks are snapshotted on the <strong>server-thread</strong> executor (throttled), the dirty-guard
 * + {@code HEAD → ref} diff are computed on the <strong>background</strong> executor (git), the delta
 * is replayed onto the live world back on the <strong>server thread</strong> (throttled to N
 * chunks/tick), the ref is moved off-thread, and the player is messaged on completion. The
 * synchronous recording executors here run every task inline so the whole hop chain resolves within
 * the test while still proving which executor each step landed on.
 */
class CheckoutServiceTest {

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
    private final Author steve = new Author("Steve", "steve@players.minegit.local");
    private final BlockState stone = new BlockState("minecraft:stone");
    private final BlockState dirt = new BlockState("minecraft:dirt");
    private final BlockState glass = new BlockState("minecraft:glass");

    /** Builds a repo with commit A (stone) then B (+dirt); leaves the live world at B (clean). */
    private FakeWorldAdapter twoCommitWorld() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, stone);
        try (MineGitRepo repo = MineGitRepo.init(repoDir, world, clock)) {
            repo.commit("A", steve);
            world.setBlock(DimensionId.OVERWORLD, 100, 64, 0, dirt);
            repo.commit("B", steve);
        }
        return world;
    }

    @Test
    void checkoutRevertsABuiltChangeLive() {
        FakeWorldAdapter world = twoCommitWorld();
        CheckoutService service =
                new CheckoutService(new RecordingExecutor(), new RecordingExecutor(), 16);

        AtomicReference<CheckoutService.Result> result = new AtomicReference<>();
        service.checkout(repoDir, world, clock, "HEAD~1", false, result::set);

        CheckoutService.Result r = result.get();
        assertNotNull(r, "completion callback fired");
        assertFalse(r.isError(), "clean checkout succeeded");
        // The dirt placed in B is gone from the live world; the stone from A remains.
        assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 100, 64, 0));
        assertEquals(stone, world.getBlock(DimensionId.OVERWORLD, 0, 64, 0));
        assertEquals(1, r.applied().getRemoved(), "one block removed by the revert");
        // The ref moved back to A.
        try (MineGitRepo repo = MineGitRepo.open(repoDir, world, clock)) {
            assertEquals("A", repo.log().get(0).getMessage(), "branch reset to A");
        }
    }

    @Test
    void dirtyGuardBlocksWithoutForceAndProceedsWithIt() {
        FakeWorldAdapter world = twoCommitWorld();
        world.setBlock(DimensionId.OVERWORLD, 200, 64, 0, glass); // uncommitted live edit => dirty

        CheckoutService service =
                new CheckoutService(new RecordingExecutor(), new RecordingExecutor(), 16);

        // Without --force the dirty world refuses the checkout and is left untouched.
        AtomicReference<CheckoutService.Result> blocked = new AtomicReference<>();
        service.checkout(repoDir, world, clock, "HEAD~1", false, blocked::set);
        assertTrue(blocked.get().isError(), "dirty tree refused without force");
        assertTrue(blocked.get().error() instanceof WorkingTreeDirtyException);
        assertEquals(dirt, world.getBlock(DimensionId.OVERWORLD, 100, 64, 0),
                "refused checkout did not revert anything");

        // With --force the checkout proceeds, reverting B's dirt.
        AtomicReference<CheckoutService.Result> forced = new AtomicReference<>();
        service.checkout(repoDir, world, clock, "HEAD~1", true, forced::set);
        assertFalse(forced.get().isError(), "forced checkout succeeded");
        assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 100, 64, 0),
                "forced checkout reverted B");
    }

    @Test
    void appliesAreThrottledAcrossMultipleServerThreadTasks() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        try (MineGitRepo repo = MineGitRepo.init(repoDir, world, clock)) {
            repo.commit("base", steve);
            // Three distinct chunks gain a block in commit B.
            world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, stone);
            world.setBlock(DimensionId.OVERWORLD, 20, 64, 0, stone);
            world.setBlock(DimensionId.OVERWORLD, 40, 64, 0, stone);
            repo.commit("B", steve);
        }

        RecordingExecutor serverThread = new RecordingExecutor();
        RecordingExecutor background = new RecordingExecutor();
        CheckoutService service = new CheckoutService(serverThread, background, 1); // one chunk/tick

        AtomicReference<CheckoutService.Result> result = new AtomicReference<>();
        service.checkout(repoDir, world, clock, "HEAD~1", false, result::set);

        assertFalse(result.get().isError());
        assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 20, 64, 0), "reverted");
        // 3 changed chunks applied at 1/tick => several server-thread passes; git ran on background.
        assertTrue(serverThread.tasks >= 3,
                "applies spread across ticks, got " + serverThread.tasks + " server tasks");
        assertTrue(background.tasks >= 1,
                "dirty-guard + diff + ref move ran on the background executor");
    }

    @Test
    void unknownRefIsReportedAsAnError() {
        FakeWorldAdapter world = twoCommitWorld();
        CheckoutService service =
                new CheckoutService(new RecordingExecutor(), new RecordingExecutor(), 16);

        AtomicReference<CheckoutService.Result> result = new AtomicReference<>();
        service.checkout(repoDir, world, clock, "nonexistent", false, result::set);

        assertTrue(result.get().isError(), "an unresolvable ref fails the checkout");
        assertTrue(result.get().error() instanceof UnknownRefException);
    }
}
