package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.world.CommitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The <strong>hard constraint</strong> of the live-subscription loop (issue #93, Spec C batch 2 §2.3):
 * the live working-vs-HEAD recompute must be <em>non-destructive</em> — it must NOT drain/consume the
 * dirty tracker that {@code /mg commit} relies on. The loop's recompute is exactly
 * {@link MineGitService#status(Path, net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter, Clock, DirtyChunkSet)},
 * which on the primed path diffs over {@code peekDirty()} (read-only). This test proves that even after
 * many live polls a following commit still captures the same uncommitted change — i.e. the dirty set
 * survived the polling.
 */
class LiveSubscriptionNonDestructiveTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1780574400L), ZoneOffset.UTC);
    private static final DimensionId OVERWORLD = new DimensionId("overworld");
    private static final Author AUTHOR = new Author("Tester", "tester@minegit.local");
    private static final Executor INLINE = Runnable::run;

    @TempDir
    Path tmp;

    private static CommitService.Result commit(
            CommitService svc, Path repo, FakeWorldAdapter world, String msg, DirtyChunkSet tracker) {
        AtomicReference<CommitService.Result> out = new AtomicReference<CommitService.Result>();
        svc.commit(repo, world, CLOCK, msg, AUTHOR, tracker, out::set);
        CommitService.Result r = out.get();
        assertNotNull(r, "commit '" + msg + "' must complete inline");
        assertTrue(!r.isError(), "commit '" + msg + "' must not error");
        return r;
    }

    @Test
    void livePollingLeavesDirtySetIntactForTheNextCommit() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        // A baseline block, committed so the tracker is primed and the dirty set drained.
        world.setBlock(OVERWORLD, 0, 64, 0, new BlockState("minecraft:stone"));
        MineGitService.init(repo, world, CLOCK);

        DirtyChunkSet tracker = new DirtyChunkSet();
        CommitService svc = new CommitService(INLINE, INLINE, 16);
        commit(svc, repo, world, "baseline", tracker);
        assertTrue(tracker.isPrimed(), "the baseline commit must prime the tracker (incremental path next)");

        // An UNCOMMITTED change the live overlay would show — marks its chunk dirty.
        world.setBlock(OVERWORLD, 5, 70, 5, new BlockState("minecraft:diamond_block"));

        // Simulate many live-loop recomputes (the throttled per-tick poll). Each must be read-only.
        WorldDiff polled = null;
        for (int i = 0; i < 8; i++) {
            polled = MineGitService.status(repo, world, CLOCK, tracker);
        }
        assertNotNull(polled, "live poll must return a diff");
        assertTrue(polled.getAdded() >= 1, "the live poll must see the uncommitted change");
        assertTrue(tracker.isPrimed(), "live polling must never unprime the tracker");

        // Now commit. If live polling had drained the dirty set, the primed commit would drain nothing
        // and the diamond block would never be committed.
        commit(svc, repo, world, "after live polling", tracker);

        // Proof the change was captured: a full (unprimed) status now sees the world == HEAD.
        WorldDiff afterCommit = MineGitService.status(repo, world, CLOCK, new DirtyChunkSet());
        assertEquals(0, afterCommit.getAdded(),
                "after live polling, /mg commit must still capture the change — dirty set intact");
        assertEquals(0, afterCommit.getChanged(), "no residual change should remain after the commit");
        assertEquals(0, afterCommit.getRemoved(), "no residual removal should remain after the commit");
    }
}
