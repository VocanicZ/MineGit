package com.minegit.plugin.world;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable, read-only {@link WorldAdapter} backed by chunks already captured from the live world
 * (Spec B §6).
 *
 * <p>The plugin reads loaded chunks on the <strong>main thread</strong> (throttled across ticks),
 * captures them here, then hands this snapshot to {@link com.minegit.core.git.MineGitRepo#commit} on
 * an <strong>async</strong> thread. Because every read is served from the captured map — never from
 * Bukkit — the heavy {@code serialize + git} work runs off-tick without ever touching the world from
 * the wrong thread. {@link #allChunks()} and {@link #drainDirty()} both return the captured ref set
 * (the snapshot is a single commit's worth of work), which matches the Bukkit adapter's
 * loaded-chunks behavior whether core treats this as the first content commit or a later one.
 *
 * <p>Writes ({@link #apply}/{@link #writeChunk}) throw — a snapshot is never the target of a
 * checkout.
 */
public final class SnapshotWorldAdapter implements WorldAdapter {

    private final Set<DimensionId> dimensions;
    private final Map<ChunkRef, NormalizedChunk> chunks;

    /**
     * @param dimensions the live world's dimensions at capture time
     * @param chunks captured chunks keyed by ref; a {@code null} value records a ref whose live read
     *     came back empty (an ungenerated loaded chunk), so core deletes its {@code .mgc}
     */
    public SnapshotWorldAdapter(Set<DimensionId> dimensions, Map<ChunkRef, NormalizedChunk> chunks) {
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(chunks, "chunks");
        this.dimensions = Collections.unmodifiableSet(new HashSet<DimensionId>(dimensions));
        this.chunks = new HashMap<ChunkRef, NormalizedChunk>(chunks);
    }

    @Override
    public Set<DimensionId> dimensions() {
        return dimensions;
    }

    @Override
    public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
        return chunks.get(new ChunkRef(dimension, pos));
    }

    @Override
    public Set<ChunkRef> allChunks() {
        return new HashSet<ChunkRef>(chunks.keySet());
    }

    @Override
    public Set<ChunkRef> drainDirty() {
        return allChunks();
    }

    @Override
    public void apply(DimensionId dimension, ChunkPos pos, List<BlockChange> changes) {
        throw new UnsupportedOperationException("SnapshotWorldAdapter is read-only");
    }

    @Override
    public void writeChunk(DimensionId dimension, NormalizedChunk chunk) {
        throw new UnsupportedOperationException("SnapshotWorldAdapter is read-only");
    }
}
