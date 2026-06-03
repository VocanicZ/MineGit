package com.minegit.core.model;

/**
 * Immutable chunk coordinate pair {@code (cx, cz)} in chunk units (16 blocks each).
 */
public final class ChunkPos {

    private final int cx;
    private final int cz;

    public ChunkPos(int cx, int cz) {
        this.cx = cx;
        this.cz = cz;
    }

    public int getCx() {
        return cx;
    }

    public int getCz() {
        return cz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChunkPos)) {
            return false;
        }
        ChunkPos that = (ChunkPos) o;
        return cx == that.cx && cz == that.cz;
    }

    @Override
    public int hashCode() {
        return 31 * cx + cz;
    }

    @Override
    public String toString() {
        return "ChunkPos(" + cx + ", " + cz + ")";
    }
}
