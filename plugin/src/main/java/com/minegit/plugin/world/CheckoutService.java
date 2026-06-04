package com.minegit.plugin.world;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.ChunkDiff;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Orchestrates a {@code /mg checkout <ref> [--force]} across the plugin's two execution contexts
 * (Spec B §6, issue #48).
 *
 * <p>The world can only be read <em>and written</em> on the server <strong>main thread</strong>, but
 * the dirty-guard + {@code HEAD → ref} diff is git work that must not stall the tick. So a checkout is
 * a five-step thread dance:
 *
 * <ol>
 *   <li><strong>Snapshot (main thread, throttled):</strong> the loaded chunk set is read in batches of
 *       {@code chunksPerTick} into a {@link SnapshotWorldAdapter}, so the dirty-guard can compare the
 *       live world against {@code HEAD} off-tick.
 *   <li><strong>Plan (async):</strong> {@link MineGitRepo#planCheckout} runs the dirty-guard (unless
 *       {@code force}) and computes the {@code HEAD → ref} {@link WorldDiff} — no Bukkit access, safe
 *       off-tick. A dirty tree (without force) or an unresolvable ref produces a {@link Result} error.
 *   <li><strong>Apply (main thread, throttled):</strong> the diff's {@link ChunkDiff}s are replayed
 *       onto the <em>live</em> world via {@link WorldAdapter#apply}, {@code chunksPerTick} per pass so
 *       the writes spread across ticks instead of spiking one.
 *   <li><strong>Finish (async):</strong> {@link MineGitRepo#finishCheckout} hard-resets the local ref
 *       and {@code .mgc} working tree to the target.
 *   <li><strong>Completion (main thread):</strong> the {@link Result} is delivered to {@code
 *       onComplete} on the main thread, where messaging the player is safe.
 * </ol>
 *
 * <p>Pure of Bukkit types: the executors and {@link WorldAdapter} are injected, so the whole dance is
 * unit-testable with synchronous executors and an in-memory world.
 */
public final class CheckoutService {

    /** Outcome of a checkout: the applied {@code HEAD → ref} delta, or a failure. */
    public static final class Result {
        private final WorldDiff applied;
        private final RuntimeException error;

        private Result(WorldDiff applied, RuntimeException error) {
            this.applied = applied;
            this.error = error;
        }

        static Result of(WorldDiff applied) {
            return new Result(applied, null);
        }

        static Result error(RuntimeException error) {
            return new Result(null, error);
        }

        /** The applied {@code HEAD → ref} delta (its {@code +N/-M/~K} drives the player message). */
        public WorldDiff applied() {
            return applied;
        }

        /** The failure that aborted the checkout, or {@code null} on success. */
        public RuntimeException error() {
            return error;
        }

        public boolean isError() {
            return error != null;
        }
    }

    private final Executor mainThread;
    private final Executor async;
    private final int chunksPerTick;

    /**
     * @param mainThread runs world reads/applies + the completion callback on the server main thread
     * @param async runs the dirty-guard, diff, and ref move (git) off-tick
     * @param chunksPerTick how many chunks to read/apply per main-thread pass; must be {@code >= 1}
     */
    public CheckoutService(Executor mainThread, Executor async, int chunksPerTick) {
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread");
        this.async = Objects.requireNonNull(async, "async");
        if (chunksPerTick < 1) {
            throw new IllegalArgumentException("chunksPerTick must be >= 1 but was " + chunksPerTick);
        }
        this.chunksPerTick = chunksPerTick;
    }

    /**
     * Reverts {@code live} to {@code target}, refusing a dirty world unless {@code force}. The repo at
     * {@code repoPath} is the source of truth; {@code onComplete} is invoked on the main thread with
     * the {@link Result}.
     */
    public void checkout(
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            boolean force,
            Consumer<Result> onComplete) {
        Objects.requireNonNull(repoPath, "repoPath");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(onComplete, "onComplete");
        mainThread.execute(() -> snapshotBegin(repoPath, live, clock, target, force, onComplete));
    }

    // ---- 1. snapshot (main thread, throttled) -------------------------------------------------

    private void snapshotBegin(
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            boolean force,
            Consumer<Result> onComplete) {
        List<ChunkRef> refs = new ArrayList<ChunkRef>(live.allChunks());
        Set<DimensionId> dims = live.dimensions();
        Map<ChunkRef, NormalizedChunk> captured = new HashMap<ChunkRef, NormalizedChunk>();
        snapshotBatch(refs, 0, captured, dims, repoPath, live, clock, target, force, onComplete);
    }

    private void snapshotBatch(
            List<ChunkRef> refs,
            int index,
            Map<ChunkRef, NormalizedChunk> captured,
            Set<DimensionId> dims,
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            boolean force,
            Consumer<Result> onComplete) {
        int end = Math.min(index + chunksPerTick, refs.size());
        for (int i = index; i < end; i++) {
            ChunkRef ref = refs.get(i);
            captured.put(ref, live.read(ref.getDimension(), ref.getPos()));
        }
        if (end < refs.size()) {
            mainThread.execute(() -> snapshotBatch(
                    refs, end, captured, dims, repoPath, live, clock, target, force, onComplete));
            return;
        }
        SnapshotWorldAdapter snapshot = new SnapshotWorldAdapter(dims, captured);
        async.execute(() -> plan(snapshot, repoPath, live, clock, target, force, onComplete));
    }

    // ---- 2. plan (async: dirty-guard + HEAD -> target diff) -----------------------------------

    private void plan(
            SnapshotWorldAdapter snapshot,
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            boolean force,
            Consumer<Result> onComplete) {
        WorldDiff planned;
        try (MineGitRepo repo = MineGitRepo.open(repoPath, snapshot, clock)) {
            planned = repo.planCheckout(target, force);
        } catch (RuntimeException e) {
            mainThread.execute(() -> onComplete.accept(Result.error(e)));
            return;
        }
        WorldDiff diff = planned;
        List<Unit> units = flatten(diff);
        mainThread.execute(() -> applyBatch(units, 0, diff, repoPath, live, clock, target, onComplete));
    }

    /** One chunk's worth of changes to replay onto the live world. */
    private static final class Unit {
        final DimensionId dim;
        final ChunkDiff chunkDiff;

        Unit(DimensionId dim, ChunkDiff chunkDiff) {
            this.dim = dim;
            this.chunkDiff = chunkDiff;
        }
    }

    private static List<Unit> flatten(WorldDiff diff) {
        List<Unit> units = new ArrayList<Unit>();
        for (Map.Entry<DimensionId, List<ChunkDiff>> e : diff.getDimensions().entrySet()) {
            for (ChunkDiff chunkDiff : e.getValue()) {
                units.add(new Unit(e.getKey(), chunkDiff));
            }
        }
        return units;
    }

    // ---- 3. apply (main thread, throttled) ----------------------------------------------------

    private void applyBatch(
            List<Unit> units,
            int index,
            WorldDiff diff,
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            Consumer<Result> onComplete) {
        int end = Math.min(index + chunksPerTick, units.size());
        for (int i = index; i < end; i++) {
            Unit u = units.get(i);
            live.apply(u.dim, u.chunkDiff.getPos(), u.chunkDiff.getChanges());
        }
        if (end < units.size()) {
            mainThread.execute(() ->
                    applyBatch(units, end, diff, repoPath, live, clock, target, onComplete));
            return;
        }
        async.execute(() -> finish(diff, repoPath, live, clock, target, onComplete));
    }

    // ---- 4. finish (async: move the ref) ------------------------------------------------------

    private void finish(
            WorldDiff diff,
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String target,
            Consumer<Result> onComplete) {
        Result result;
        try (MineGitRepo repo = MineGitRepo.open(repoPath, live, clock)) {
            repo.finishCheckout(target);
            result = Result.of(diff);
        } catch (RuntimeException e) {
            result = Result.error(e);
        }
        Result delivered = result;
        mainThread.execute(() -> onComplete.accept(delivered));
    }
}
