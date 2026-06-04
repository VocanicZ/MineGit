package net.rainbowcreation.vocanicz.minegit.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A whole-world diff: per-dimension lists of {@link ChunkDiff}, plus aggregate block-level
 * {@code added}/{@code removed}/{@code changed} counts (the totals of the underlying
 * {@link BlockChange.Kind}s across every chunk and dimension).
 *
 * <p>Immutable: the dimension map (and its value lists) are deep-copied and exposed unmodifiable.
 */
public final class WorldDiff {

    private final Map<DimensionId, List<ChunkDiff>> dimensions;
    private final int added;
    private final int removed;
    private final int changed;

    public WorldDiff(
            Map<DimensionId, List<ChunkDiff>> dimensions, int added, int removed, int changed) {
        Objects.requireNonNull(dimensions, "dimensions");
        if (added < 0 || removed < 0 || changed < 0) {
            throw new IllegalArgumentException("counts must be non-negative");
        }
        Map<DimensionId, List<ChunkDiff>> copy =
                new HashMap<DimensionId, List<ChunkDiff>>(dimensions.size());
        for (Map.Entry<DimensionId, List<ChunkDiff>> e : dimensions.entrySet()) {
            DimensionId dim = Objects.requireNonNull(e.getKey(), "dimension");
            List<ChunkDiff> diffs = Objects.requireNonNull(e.getValue(), "chunkDiffs");
            List<ChunkDiff> diffsCopy = new ArrayList<ChunkDiff>(diffs.size());
            for (ChunkDiff d : diffs) {
                diffsCopy.add(Objects.requireNonNull(d, "chunkDiff"));
            }
            copy.put(dim, Collections.unmodifiableList(diffsCopy));
        }
        this.dimensions = Collections.unmodifiableMap(copy);
        this.added = added;
        this.removed = removed;
        this.changed = changed;
    }

    /** Unmodifiable per-dimension map of chunk diffs. */
    public Map<DimensionId, List<ChunkDiff>> getDimensions() {
        return dimensions;
    }

    /** Chunk diffs for {@code dim}, or an empty list when the dimension has no changes. */
    public List<ChunkDiff> getChunkDiffs(DimensionId dim) {
        List<ChunkDiff> diffs = dimensions.get(dim);
        return diffs != null ? diffs : Collections.<ChunkDiff>emptyList();
    }

    /** Total blocks added (air → solid). */
    public int getAdded() {
        return added;
    }

    /** Total blocks removed (solid → air). */
    public int getRemoved() {
        return removed;
    }

    /** Total blocks changed (non-air → different non-air). */
    public int getChanged() {
        return changed;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WorldDiff)) {
            return false;
        }
        WorldDiff that = (WorldDiff) o;
        return added == that.added
                && removed == that.removed
                && changed == that.changed
                && dimensions.equals(that.dimensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimensions, added, removed, changed);
    }

    @Override
    public String toString() {
        return "WorldDiff(+" + added + "/-" + removed + "/~" + changed
                + ", dimensions=" + dimensions.keySet() + ")";
    }
}
