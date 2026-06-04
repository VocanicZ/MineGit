package net.rainbowcreation.vocanicz.minegit.core.diff;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
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

    /**
     * A {@link ChunkSource} reading only the chunks currently marked dirty in {@code adapter}, via
     * {@link WorldAdapter#peekDirty()} (non-clearing). Chunks absent from the dirty set are not
     * enumerated, so the diff engine skips them entirely — that is the speedup.
     */
    public static ChunkSource liveDirty(WorldAdapter adapter) {
        return new LiveDirtyChunkSource(adapter);
    }

    /** Dirty-only live source: chunk positions come from {@link WorldAdapter#peekDirty()}. */
    private static final class LiveDirtyChunkSource implements ChunkSource {

        private final WorldAdapter adapter;

        LiveDirtyChunkSource(WorldAdapter adapter) {
            this.adapter = Objects.requireNonNull(adapter, "adapter");
        }

        @Override
        public Set<DimensionId> dimensions() {
            Set<DimensionId> dims = new HashSet<DimensionId>();
            for (ChunkRef ref : adapter.peekDirty()) {
                dims.add(ref.getDimension());
            }
            return dims;
        }

        @Override
        public Set<ChunkPos> chunks(DimensionId dimension) {
            Objects.requireNonNull(dimension, "dimension");
            Set<ChunkPos> out = new HashSet<ChunkPos>();
            for (ChunkRef ref : adapter.peekDirty()) {
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
