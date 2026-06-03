package com.minegit.core.diff;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Factories for the two {@link ChunkSource} implementations the engine compares: a {@code live}
 * source over a {@link WorldAdapter} and a {@code tree} source over a committed git revision.
 */
public final class ChunkSources {

    private ChunkSources() {}

    /** A {@link ChunkSource} reading the world as it currently is, via {@code adapter}. */
    public static ChunkSource live(WorldAdapter adapter) {
        return new LiveChunkSource(adapter);
    }

    /**
     * A {@link ChunkSource} reading the world as it was committed at {@code rev} (e.g. {@code "HEAD"},
     * a branch name, or a commit SHA), decoding {@code .mgc} blobs straight from {@code repo}'s object
     * database with no working-tree checkout.
     */
    public static ChunkSource tree(MineGitRepo repo, String rev) {
        return new TreeChunkSource(repo, rev);
    }

    /** Live source: chunk positions come from {@link WorldAdapter#allChunks()}. */
    private static final class LiveChunkSource implements ChunkSource {

        private final WorldAdapter adapter;

        LiveChunkSource(WorldAdapter adapter) {
            this.adapter = Objects.requireNonNull(adapter, "adapter");
        }

        @Override
        public Set<DimensionId> dimensions() {
            return new HashSet<DimensionId>(adapter.dimensions());
        }

        @Override
        public Set<ChunkPos> chunks(DimensionId dimension) {
            Objects.requireNonNull(dimension, "dimension");
            Set<ChunkPos> out = new HashSet<ChunkPos>();
            for (ChunkRef ref : adapter.allChunks()) {
                if (ref.getDimension().equals(dimension)) {
                    out.add(ref.getPos());
                }
            }
            return out;
        }

        @Override
        public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
            return adapter.read(dimension, pos);
        }
    }

    /**
     * Tree source: the chunk index is read once (a single tree walk) at construction; each chunk's
     * bytes are decoded lazily via {@link MineGitRepo#readChunk}.
     */
    private static final class TreeChunkSource implements ChunkSource {

        private final MineGitRepo repo;
        private final String rev;
        private final Map<DimensionId, Set<ChunkPos>> index;

        TreeChunkSource(MineGitRepo repo, String rev) {
            this.repo = Objects.requireNonNull(repo, "repo");
            this.rev = Objects.requireNonNull(rev, "rev");
            this.index = new HashMap<DimensionId, Set<ChunkPos>>();
            for (ChunkRef ref : repo.listChunks(rev)) {
                Set<ChunkPos> positions = index.get(ref.getDimension());
                if (positions == null) {
                    positions = new HashSet<ChunkPos>();
                    index.put(ref.getDimension(), positions);
                }
                positions.add(ref.getPos());
            }
        }

        @Override
        public Set<DimensionId> dimensions() {
            return new HashSet<DimensionId>(index.keySet());
        }

        @Override
        public Set<ChunkPos> chunks(DimensionId dimension) {
            Objects.requireNonNull(dimension, "dimension");
            Set<ChunkPos> positions = index.get(dimension);
            return positions != null ? new HashSet<ChunkPos>(positions) : new HashSet<ChunkPos>();
        }

        @Override
        public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
            return repo.readChunk(rev, dimension, pos);
        }
    }
}
