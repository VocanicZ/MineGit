package com.minegit.core.adapter;

import com.minegit.core.model.BlockChange;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import java.util.List;
import java.util.Set;

/**
 * A Minecraft-agnostic view of a world that MineGit can snapshot. Implementations adapt a concrete
 * world source (a Spigot server, a region-file reader, or the in-memory test fake) into the engine's
 * {@linkplain NormalizedChunk normalized} model.
 *
 * <p>The contract is <strong>synchronous</strong>: the caller owns all threading. Core never spawns
 * threads or touches a Minecraft scheduler; frontends are responsible for performing reads on the
 * main thread and running git work off-thread.
 */
public interface WorldAdapter {

    /** The dimensions this world contains. */
    Set<DimensionId> dimensions();

    /**
     * Reads the normalized chunk at {@code (dimension, pos)}, or {@code null} if no chunk exists
     * there (e.g. ungenerated terrain).
     */
    NormalizedChunk read(DimensionId dimension, ChunkPos pos);

    /**
     * Every chunk currently present in the world, across all dimensions. Used by the engine to drive
     * the first (full) commit. The returned set is a snapshot and does not affect the dirty set.
     */
    Set<ChunkRef> allChunks();

    /**
     * Returns the set of chunks marked dirty since the previous drain and <strong>clears</strong> the
     * dirty set, so a subsequent call with no intervening mutations returns an empty set. Drives
     * incremental commits.
     */
    Set<ChunkRef> drainDirty();

    /**
     * Applies a chunk's worth of {@link BlockChange}s to the world, mutating it toward a checkout
     * target. For each change the world is set to the change's {@linkplain BlockChange#getNewState()
     * new state} (an {@link BlockChange.Kind#ADD} or {@link BlockChange.Kind#CHANGE}) or back to air
     * (a {@link BlockChange.Kind#REMOVE}). This is the {@code checkout}/{@code pull} apply hook: the
     * engine computes {@code HEAD → target} as block changes and replays them here, per chunk.
     *
     * <p>A frontend performs this on the main thread, throttled to N chunks per tick, and resends the
     * affected chunks. The in-memory fake applies them immediately. No Minecraft dependencies.
     */
    void apply(DimensionId dimension, ChunkPos pos, List<BlockChange> changes);
}
