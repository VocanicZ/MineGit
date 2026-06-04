package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.command.MineGitService;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduces the in-game report: {@code /mg diff} shows a block change but the following
 * {@code /mg commit} says "Nothing to commit — matches HEAD". Mirrors the exact production wiring —
 * {@link ModWorldAdapter} over a {@link DirtyChunkSet}, primed by the first full commit, then an
 * incremental commit of a single dirty chunk through {@link CommitService}.
 */
class IncrementalCommitReproTest {

    @TempDir
    Path repoDir;

    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(1780576496L), ZoneOffset.UTC);

    private static final class Inline implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private CommitService.Result commit(WorldAdapterAndTracker w, DirtyChunkSet tracker, String msg) {
        CommitService service = new CommitService(new Inline(), new Inline(), 16);
        AtomicReference<CommitService.Result> result = new AtomicReference<CommitService.Result>();
        service.commit(repoDir, w.adapter, clock, msg,
                new Author("Player354", "p@players.minegit.local"), tracker, result::set);
        return result.get();
    }

    /** Bundles a ModWorldAdapter with its backing level so a test can both read and edit blocks. */
    private static final class WorldAdapterAndTracker {
        final FakeLevelAccess level;
        final ModWorldAdapter adapter;
        WorldAdapterAndTracker(FakeLevelAccess level, ModWorldAdapter adapter) {
            this.level = level;
            this.adapter = adapter;
        }
    }

    @Test
    void diffShowsChangeButCommitRecordsItToo() {
        DimensionId dim = DimensionId.OVERWORLD;
        FakeLevelAccess level = new FakeLevelAccess(dim, -4, 24);
        level.addLoadedChunk(-4, 2);
        level.setBlock(-54, -61, 42, new BlockState("minecraft:grass_block"));
        DirtyChunkSet tracker = new DirtyChunkSet();
        ModWorldAdapter adapter = new ModWorldAdapter(level, tracker);
        WorldAdapterAndTracker w = new WorldAdapterAndTracker(level, adapter);

        // /mg init
        MineGitRepo.init(repoDir, adapter, clock).close();

        // /mg commit "init" — first content commit, primes the tracker.
        CommitService.Result first = commit(w, tracker, "init");
        assertNotNull(first.commit(), "first commit records the baseline");
        assertTrue(tracker.isPrimed(), "first commit primes the tracker");

        // Player breaks the block: grass -> dirt. Mixin marks chunk (-4,2) dirty.
        level.setBlock(-54, -61, 42, new BlockState("minecraft:dirt"));
        tracker.markDirty(new ChunkRef(dim, new ChunkPos(-4, 2)));

        // /mg diff — primed, dirty path: must show the change.
        WorldDiff diff = MineGitService.status(repoDir, adapter, clock, tracker);
        assertTrue(diff.getChanged() >= 1,
                "diff must show the grass->dirt change, got " + diff);

        // /mg commit — must record the SAME change, not "Nothing to commit".
        CommitService.Result second = commit(w, tracker, "change");
        assertNull(second.error());
        assertNotNull(second.commit(),
                "commit must record the change that diff just showed — got 'nothing to commit'");
    }

    /**
     * The full-scan path (tracker never primed, e.g. mixin never marks): an edit made after the
     * baseline commit must still be recorded by a subsequent full-scan commit.
     */
    @Test
    void fullScanCommitAfterEditStillRecordsTheChange() {
        DimensionId dim = DimensionId.OVERWORLD;
        FakeLevelAccess level = new FakeLevelAccess(dim, -4, 24);
        level.addLoadedChunk(-4, 2);
        level.setBlock(-54, -61, 42, new BlockState("minecraft:grass_block"));
        ModWorldAdapter adapter = new ModWorldAdapter(level, null); // no tracker -> always full scan
        WorldAdapterAndTracker w = new WorldAdapterAndTracker(level, adapter);

        MineGitRepo.init(repoDir, adapter, clock).close();
        assertNotNull(commit(w, null, "baseline").commit(), "baseline commits");

        level.setBlock(-54, -61, 42, new BlockState("minecraft:dirt"));

        // Full diff sees it.
        WorldDiff diff = MineGitService.status(repoDir, adapter, clock, null);
        assertTrue(diff.getChanged() >= 1, "full diff shows the change, got " + diff);

        // Full commit must record it.
        assertNotNull(commit(w, null, "edit").commit(),
                "full-scan commit must record the edit — got 'nothing to commit'");
    }
}
