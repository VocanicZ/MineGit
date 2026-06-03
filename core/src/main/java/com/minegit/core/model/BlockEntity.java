package com.minegit.core.model;

import java.util.Objects;

/**
 * Immutable block-entity record: world coordinates plus its normalized SNBT (stringified NBT)
 * payload. Used for tile entities such as chests, signs and spawners.
 */
public final class BlockEntity {

    private final int x;
    private final int y;
    private final int z;
    private final String snbt;

    public BlockEntity(int x, int y, int z, String snbt) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.snbt = Objects.requireNonNull(snbt, "snbt");
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getSnbt() {
        return snbt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockEntity)) {
            return false;
        }
        BlockEntity that = (BlockEntity) o;
        return x == that.x && y == that.y && z == that.z && snbt.equals(that.snbt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, snbt);
    }

    @Override
    public String toString() {
        return "BlockEntity(" + x + ", " + y + ", " + z + ", " + snbt + ")";
    }
}
