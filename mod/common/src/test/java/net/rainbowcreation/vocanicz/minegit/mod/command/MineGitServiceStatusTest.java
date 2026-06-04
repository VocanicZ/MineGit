package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the dirty-tracker priming contract in {@link MineGitService#status}: status
 * must never prime the tracker — only {@code commit} establishes the primed baseline (Fix 1 of Spec
 * E task-3 code review). Running status while unprimed and then trusting the dirty set would cause
 * the next incremental commit to miss blocks placed before the session event-listener registered.
 */
class MineGitServiceStatusTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1780574400L), ZoneOffset.UTC);
    private static final DimensionId OVERWORLD = new DimensionId("overworld");

    @TempDir
    Path tmp;

    /**
     * A fresh (unprimed) tracker must remain unprimed after calling {@code status}, and the returned
     * diff must reflect the real world state (not an empty incremental result).
     */
    @Test
    void statusDoesNotPrimeTheTracker() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK);

        // Place a block so the diff is non-trivial.
        world.setBlock(OVERWORLD, 1, 70, 1, new BlockState("minecraft:stone"));

        DirtyChunkSet tracker = new DirtyChunkSet(); // fresh, unprimed
        assertFalse(tracker.isPrimed(), "tracker must start unprimed");

        WorldDiff diff = MineGitService.status(repo, world, CLOCK, tracker);

        // Status must NOT prime the tracker.
        assertFalse(tracker.isPrimed(),
                "status must not prime the tracker — only commit establishes the baseline");

        // Status must still return the full diff (non-trivial).
        assertTrue(diff.getAdded() >= 1,
                "status with an unprimed tracker must return a full diff showing the placed block");
    }

    /**
     * When the tracker is already primed, {@code status} takes the fast path through
     * {@link net.rainbowcreation.vocanicz.minegit.core.diff.WorldDiffer#diffWorkingTreeDirty} and
     * returns without error, using only the dirty set. The tracker remains primed.
     */
    @Test
    void primedStatusUsesDirtyPath() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK);

        // Place a block so there is at least one dirty chunk.
        world.setBlock(OVERWORLD, 1, 70, 1, new BlockState("minecraft:stone"));

        DirtyChunkSet tracker = new DirtyChunkSet();
        tracker.prime(); // simulate a prior commit having primed the tracker
        assertTrue(tracker.isPrimed(), "tracker must be primed before this call");

        // status with a primed tracker should succeed and leave the tracker primed.
        WorldDiff diff = MineGitService.status(repo, world, CLOCK, tracker);

        assertTrue(tracker.isPrimed(), "tracker must remain primed after a primed-path status call");
        // The dirty path may return a subset diff; we only assert it doesn't throw and returns a value.
        assertTrue(diff != null, "status must return a non-null diff on the primed path");
    }
}
