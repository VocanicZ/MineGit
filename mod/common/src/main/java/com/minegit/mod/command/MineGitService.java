package com.minegit.mod.command;

import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.diff.WorldDiffer;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * Loader-agnostic git operations behind the read/setup subcommands (Spec D §4, issue #60). Each call
 * opens the per-level MineGit repo at {@code repoDir} over a {@link WorldAdapter}, runs one engine
 * operation, and closes the handle — so the service holds no state and every Minecraft type stays in
 * the command layer above it.
 *
 * <p>Because it speaks only core types, it is unit-testable against the engine's in-memory {@code
 * FakeWorldAdapter} on a temp dir, exercising the real JGit path without a running server.
 */
public final class MineGitService {

    private MineGitService() {
    }

    /** Creates a fresh MineGit repo at {@code repoDir} for {@code adapter}'s world. */
    public static void init(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        MineGitRepo.init(repoDir, adapter, clock).close();
    }

    /** The working-tree-vs-HEAD diff for {@code repoDir}: the live world against the last commit. */
    public static WorldDiff status(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            return WorldDiffer.diffWorkingTree(repo, adapter);
        }
    }

    /**
     * The ref-vs-ref diff for {@code repoDir}: committed {@code refA} (before) against {@code refB}
     * (after). Throws {@link com.minegit.core.git.UnknownRefException} when either ref resolves to
     * nothing, so an unresolvable ref never silently collapses to a misleading empty-tree diff.
     */
    public static WorldDiff diffRefs(
            Path repoDir, WorldAdapter adapter, Clock clock, String refA, String refB) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(refA, "refA");
        Objects.requireNonNull(refB, "refB");
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            return WorldDiffer.diffRefs(repo, refA, refB);
        }
    }

    /** The commit history at {@code repoDir}, newest first. */
    public static List<CommitInfo> log(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            return repo.log();
        }
    }
}
