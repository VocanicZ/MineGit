package com.minegit.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The set of {@link BlockChange}s for a single chunk, identified by its {@link ChunkPos}.
 *
 * <p>Immutable: the change list is copied and exposed unmodifiable.
 */
public final class ChunkDiff {

    private final ChunkPos pos;
    private final List<BlockChange> changes;

    public ChunkDiff(ChunkPos pos, List<BlockChange> changes) {
        this.pos = Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(changes, "changes");
        List<BlockChange> copy = new ArrayList<BlockChange>(changes.size());
        for (BlockChange c : changes) {
            copy.add(Objects.requireNonNull(c, "change"));
        }
        this.changes = Collections.unmodifiableList(copy);
    }

    public ChunkPos getPos() {
        return pos;
    }

    /** Unmodifiable list of block changes in this chunk. */
    public List<BlockChange> getChanges() {
        return changes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChunkDiff)) {
            return false;
        }
        ChunkDiff that = (ChunkDiff) o;
        return pos.equals(that.pos) && changes.equals(that.changes);
    }

    @Override
    public int hashCode() {
        return 31 * pos.hashCode() + changes.hashCode();
    }

    @Override
    public String toString() {
        return "ChunkDiff(" + pos + ", changes=" + changes.size() + ")";
    }
}
