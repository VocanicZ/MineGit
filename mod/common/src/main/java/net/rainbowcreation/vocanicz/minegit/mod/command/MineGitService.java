package net.rainbowcreation.vocanicz.minegit.mod.command;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.diff.WorldDiffer;
import net.rainbowcreation.vocanicz.minegit.core.git.BranchRef;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * compared, giving a fast incremental result. When the tracker is not yet primed, a full diff is
     * performed and returned — but the tracker is <em>not</em> primed here.
     *
     * <p><strong>Status never primes; only commit establishes the primed baseline.</strong> Priming in
     * status would cause the next incremental commit to trust a dirty set whose starting snapshot was
     * never recorded as a committed baseline, silently missing changes that occurred before the session
     * began. The primed flag is set exclusively by {@link net.rainbowcreation.vocanicz.minegit.mod.world.CommitService} when it
     * completes a full reconciliation pass.
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
            // Tracker is null or not yet primed: do a full diff but do NOT prime here.
            // Only CommitService.commit() establishes the primed baseline.
            return WorldDiffer.diffWorkingTree(repo, adapter);
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

    /** How many recent commits {@link #refCatalog} offers as ref suggestions. */
    static final int REF_SUGGEST_COMMIT_CAP = 50;

    /**
     * The catalogue of checkout/diff ref candidates for {@code repoDir} in one repo open: branch names
     * then the {@value #REF_SUGGEST_COMMIT_CAP} most recent <em>checkout-able</em> commit short-hashes
     * ({@link MineGitRepo#listKnownCommits} — reflog-aware, so commits orphaned by a reset-style
     * checkout still appear, and the empty root is excluded), each mapped to a short tooltip, plus the
     * {@code HEAD}-reachable depth so the caller can offer the matching {@code HEAD~N} aliases. Drives
     * {@code /mg checkout}/{@code /mg diff} tab-completion.
     */
    public static RefCatalog refCatalog(Path repoDir, WorldAdapter adapter, Clock clock) {
        Objects.requireNonNull(repoDir, "repoDir");
        Objects.requireNonNull(adapter, "adapter");
        Objects.requireNonNull(clock, "clock");
        LinkedHashMap<String, String> refs = new LinkedHashMap<String, String>();
        int headDepth;
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            for (BranchRef branch : repo.branches()) {
                refs.putIfAbsent(branch.getName(), branch.isRemote() ? "remote branch" : "branch");
            }
            for (CommitInfo commit : repo.listKnownCommits(REF_SUGGEST_COMMIT_CAP)) {
                refs.putIfAbsent(MineGitText.shortHash(commit.getId()), MineGitText.firstLine(commit.getMessage()));
            }
            headDepth = repo.log().size();
        }
        return new RefCatalog(refs, headDepth);
    }

    /** Ref suggestions ({@code value -> tooltip}, insertion-ordered) plus the {@code HEAD}-reachable depth. */
    public static final class RefCatalog {
        private final Map<String, String> refs;
        private final int headCommitDepth;

        RefCatalog(Map<String, String> refs, int headCommitDepth) {
            this.refs = refs;
            this.headCommitDepth = headCommitDepth;
        }

        /** Suggestable refs (branch names, then checkout-able short-hashes) mapped to their tooltip text. */
        public Map<String, String> refs() {
            return refs;
        }

        /**
         * Number of commits reachable from {@code HEAD} (including the metadata root). The deepest
         * navigable {@code HEAD~N} is {@code headCommitDepth - 2} (one less for the current commit, one
         * less because {@code HEAD~(depth-1)} is the empty root, which checkout refuses).
         */
        public int headCommitDepth() {
            return headCommitDepth;
        }
    }
}
