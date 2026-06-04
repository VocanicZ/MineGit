package net.rainbowcreation.vocanicz.minegit.core.adapter;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.Objects;

/**
 * Immutable locator for a single chunk within a world: a {@link DimensionId} plus a {@link ChunkPos}.
 *
 * <p>{@link WorldAdapter#allChunks()} and {@link WorldAdapter#drainDirty()} return sets of these so
 * the commit loop can iterate {@code (dim, pos)} pairs and call {@link WorldAdapter#read} for each.
 */
public final class ChunkRef {

    private final DimensionId dimension;
    private final ChunkPos pos;

    public ChunkRef(DimensionId dimension, ChunkPos pos) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.pos = Objects.requireNonNull(pos, "pos");
    }

    public DimensionId getDimension() {
        return dimension;
    }

    public ChunkPos getPos() {
        return pos;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChunkRef)) {
            return false;
        }
        ChunkRef that = (ChunkRef) o;
        return dimension.equals(that.dimension) && pos.equals(that.pos);
    }

    @Override
    public int hashCode() {
        return 31 * dimension.hashCode() + pos.hashCode();
    }

    @Override
    public String toString() {
        return "ChunkRef(" + dimension + ", " + pos + ")";
    }
}
