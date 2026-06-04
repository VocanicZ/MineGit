package net.rainbowcreation.vocanicz.minegit.mod.command;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.diff.WorldDiffer;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
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
     * Primed-aware working-tree-vs-HEAD diff. When {@code tracker} is primed, only the dirty chunks
     * ({@link net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter#peekDirty()}) are
     * compared, giving a fast incremental result. On the first call (tracker not yet primed) a full
     * diff is done and the tracker is primed so the next call is incremental.
     *
     * @param tracker the per-level dirty set, or {@code null} to always do a full diff (falls back to
     *                the 3-arg {@link #status(Path, WorldAdapter, Clock)})
     */
    public static WorldDiff status(Path repoDir, WorldAdapter adapter, Clock clock, DirtyChunkSet tracker) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            if (tracker != null && tracker.isPrimed()) {
                return WorldDiffer.diffWorkingTreeDirty(repo, adapter);
            }
            WorldDiff full = WorldDiffer.diffWorkingTree(repo, adapter);
            if (tracker != null) tracker.prime();
            return full;
        }
    }

    /**
     * The ref-vs-ref diff for {@code repoDir}: committed {@code refA} (before) against {@code refB}
     * (after). Throws {@link net.rainbowcreation.vocanicz.minegit.core.git.UnknownRefException} when either ref resolves to
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
