package com.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.fake.FakeWorldAdapter;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.git.UnknownRefException;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Headless coverage of {@code init}/{@code status}/{@code log} over the real JGit engine, driven by
 * the in-memory {@link FakeWorldAdapter} (issue #60) — no Minecraft server required.
 */
class MineGitServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(1780574400L), ZoneOffset.UTC);
    private static final DimensionId OVERWORLD = new DimensionId("overworld");

    @TempDir
    Path tmp;

    @Test
    void initCreatesRepoWithAnInitialCommit() {
        Path repo = tmp.resolve("overworld");
        MineGitService.init(repo, new FakeWorldAdapter(), CLOCK);

        List<CommitInfo> log = MineGitService.log(repo, new FakeWorldAdapter(), CLOCK);
        assertEquals(1, log.size());
        assertEquals("Initialize MineGit repository", log.get(0).getMessage());
    }

    @Test
    void statusOfAnUntouchedWorldIsEmpty() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK);

        WorldDiff diff = MineGitService.status(repo, world, CLOCK);
        assertEquals(0, diff.getAdded());
        assertEquals(0, diff.getRemoved());
        assertEquals(0, diff.getChanged());
    }

    @Test
    void statusReportsBlocksPlacedSinceHead() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK);

        world.setBlock(OVERWORLD, 1, 70, 1, new BlockState("minecraft:stone"));

        WorldDiff diff = MineGitService.status(repo, world, CLOCK);
        assertTrue(diff.getAdded() >= 1, "placing a block should show as added");
        assertEquals(0, diff.getRemoved());
        assertFalse(diff.getDimensions().isEmpty());
    }

    @Test
    void diffRefsComparesTwoCommittedRevisions() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK); // commit 1: empty world

        try (MineGitRepo handle = MineGitRepo.open(repo, world, CLOCK)) {
            world.setBlock(OVERWORLD, 1, 70, 1, new BlockState("minecraft:stone"));
            handle.commit("place stone", "Steve"); // commit 2: one block added
        }

        List<CommitInfo> log = MineGitService.log(repo, world, CLOCK);
        String head = log.get(0).getId();
        String previous = log.get(1).getId();

        WorldDiff diff = MineGitService.diffRefs(repo, world, CLOCK, previous, head);
        assertTrue(diff.getAdded() >= 1, "the stone placed between the commits should show as added");
        assertEquals(0, diff.getRemoved());
        assertEquals(0, diff.getChanged());
    }

    @Test
    void diffRefsRejectsAnUnknownRef() {
        Path repo = tmp.resolve("overworld");
        FakeWorldAdapter world = new FakeWorldAdapter();
        MineGitService.init(repo, world, CLOCK);

        assertThrows(UnknownRefException.class,
                () -> MineGitService.diffRefs(repo, world, CLOCK, "HEAD", "no-such-ref"));
    }
}
