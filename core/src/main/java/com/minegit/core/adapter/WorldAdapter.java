package com.minegit.core.adapter;

import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
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
}
