package net.rainbowcreation.vocanicz.minegit.core.diff;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import java.util.Set;

/**
 * A read-only, position-addressable view of a world that the block-diff engine compares. A source
 * yields a {@link NormalizedChunk} for any {@code (dimension, ChunkPos)} it knows about and can
 * enumerate the chunk positions it holds, so the engine can take the union of two sources.
 *
 * <p>Two implementations exist: a <strong>live</strong> source backed by a
 * {@link net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter} (see {@link ChunkSources#live}) and a
 * <strong>tree</strong> source that decodes {@code .mgc} blobs out of a committed git revision
 * (see {@link ChunkSources#tree}). Because both feed the engine through this one interface, a single
 * hot path serves both {@code status} (live-vs-HEAD) and {@code diff} (ref-vs-ref).
 *
 * <p>The contract is synchronous; the source performs no threading. Implementations have no
 * Minecraft dependencies.
 */
public interface ChunkSource {

    /** The dimensions for which this source holds at least one chunk. */
    Set<DimensionId> dimensions();

    /** The chunk positions this source holds in {@code dimension}; empty if it has none. */
    Set<ChunkPos> chunks(DimensionId dimension);

    /**
     * The normalized chunk at {@code (dimension, pos)}, or {@code null} if this source holds no chunk
     * there (an absent chunk is treated as all-air by the diff engine).
     */
    NormalizedChunk read(DimensionId dimension, ChunkPos pos);
}
