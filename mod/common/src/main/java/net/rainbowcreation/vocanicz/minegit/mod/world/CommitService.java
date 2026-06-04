package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
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
 * Orchestrates a {@code /mg commit} across the mod's two execution contexts (Spec D §5) — the
 * loader-agnostic analogue of the plugin's commit service.
 *
 * <p>The level can only be read on the <strong>server thread</strong>, but the {@code serialize +
 * git} work is heavy and must not stall the tick. So a commit is a three-step thread dance:
 *
 * <ol>
 *   <li><strong>Snapshot (server thread, throttled):</strong> the loaded chunk set is read in batches
 *       of {@code chunksPerTick}, each batch re-submitted to the server-thread executor so the reads
 *       spread across ticks instead of spiking one. The captured chunks become a
 *       {@link SnapshotWorldAdapter}.
 *   <li><strong>Git (background):</strong> the snapshot is handed to {@link MineGitRepo#commit} on the
 *       background executor — no Minecraft access, so it is safe off-tick.
 *   <li><strong>Completion (server thread):</strong> the {@link Result} is delivered to {@code
 *       onComplete} back on the server thread, where messaging the player is safe.
 * </ol>
 *
 * <p>Pure of Minecraft types: the executors and {@link WorldAdapter} are injected, so the whole dance
 * is unit-testable with synchronous executors and an in-memory world.
 */
public final class CommitService {

    /** Outcome of a commit: a created commit, "nothing changed", or a failure. */
    public static final class Result {
        private final CommitInfo commit;
        private final RuntimeException error;

        private Result(CommitInfo commit, RuntimeException error) {
            this.commit = commit;
            this.error = error;
        }

        static Result of(CommitInfo commit) {
            return new Result(commit, null);
        }

        static Result error(RuntimeException error) {
            return new Result(null, error);
        }

        /** The created commit, or {@code null} when nothing changed (or on error). */
        public CommitInfo commit() {
            return commit;
        }

        /** The failure that aborted the commit, or {@code null} on success. */
        public RuntimeException error() {
            return error;
        }

        public boolean isError() {
            return error != null;
        }
    }

    private final Executor serverThread;
    private final Executor background;
    private final int chunksPerTick;

    /**
     * @param serverThread runs level reads + the completion callback on the server thread
     * @param background runs the {@code serialize + git} step off-tick
     * @param chunksPerTick how many chunks to read per server-thread pass (throttle); must be {@code >= 1}
     */
    public CommitService(Executor serverThread, Executor background, int chunksPerTick) {
        this.serverThread = Objects.requireNonNull(serverThread, "serverThread");
        this.background = Objects.requireNonNull(background, "background");
        if (chunksPerTick < 1) {
            throw new IllegalArgumentException("chunksPerTick must be >= 1 but was " + chunksPerTick);
        }
        this.chunksPerTick = chunksPerTick;
    }

    /**
     * Snapshots {@code live}'s loaded chunks and commits them to the repo at {@code repoPath} as
     * {@code author}. {@code onComplete} is invoked on the server thread with the {@link Result}.
     */
    public void commit(
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String message,
            Author author,
            Consumer<Result> onComplete) {
        Objects.requireNonNull(repoPath, "repoPath");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(onComplete, "onComplete");
        serverThread.execute(() -> snapshotBegin(repoPath, live, clock, message, author, onComplete));
    }

    private void snapshotBegin(
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String message,
            Author author,
            Consumer<Result> onComplete) {
        List<ChunkRef> refs = new ArrayList<ChunkRef>(live.allChunks());
        Set<DimensionId> dims = live.dimensions();
        Map<ChunkRef, NormalizedChunk> captured = new HashMap<ChunkRef, NormalizedChunk>();
        snapshotBatch(refs, 0, captured, dims, repoPath, live, clock, message, author, onComplete);
    }

    private void snapshotBatch(
            List<ChunkRef> refs,
            int index,
            Map<ChunkRef, NormalizedChunk> captured,
            Set<DimensionId> dims,
            Path repoPath,
            WorldAdapter live,
            Clock clock,
            String message,
            Author author,
            Consumer<Result> onComplete) {
        int end = Math.min(index + chunksPerTick, refs.size());
        for (int i = index; i < end; i++) {
            ChunkRef ref = refs.get(i);
            captured.put(ref, live.read(ref.getDimension(), ref.getPos()));
        }
        if (end < refs.size()) {
            serverThread.execute(() -> snapshotBatch(
                    refs, end, captured, dims, repoPath, live, clock, message, author, onComplete));
            return;
        }
        SnapshotWorldAdapter snapshot = new SnapshotWorldAdapter(dims, captured);
        background.execute(() -> gitCommit(snapshot, repoPath, clock, message, author, onComplete));
    }

    private void gitCommit(
            SnapshotWorldAdapter snapshot,
            Path repoPath,
            Clock clock,
            String message,
            Author author,
            Consumer<Result> onComplete) {
        Result result;
        try (MineGitRepo repo = MineGitRepo.open(repoPath, snapshot, clock)) {
            result = Result.of(repo.commit(message, author));
        } catch (RuntimeException e) {
            result = Result.error(e);
        }
        Result delivered = result;
        serverThread.execute(() -> onComplete.accept(delivered));
    }
}
