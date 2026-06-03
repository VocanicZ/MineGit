package com.minegit.core.model;

import java.util.Objects;

/**
 * An immutable single-block delta between two world states at {@code (x, y, z)}.
 *
 * <p>Air is the canonical empty baseline, which makes the three kinds well-defined:
 * <ul>
 *   <li>{@link Kind#ADD} — air/absent became a solid state ({@code oldState == null}).</li>
 *   <li>{@link Kind#REMOVE} — a solid state became air/absent ({@code newState == null}).</li>
 *   <li>{@link Kind#CHANGE} — one non-air state became a different non-air state.</li>
 * </ul>
 *
 * <p>Use the {@link #add}, {@link #remove} and {@link #change} factories so the null/kind invariants
 * always hold.
 */
public final class BlockChange {

    /** The kind of delta. See {@link BlockChange} for the air-aware definitions. */
    public enum Kind {
        ADD, REMOVE, CHANGE
    }

    private final int x;
    private final int y;
    private final int z;
    private final Kind kind;
    private final BlockState oldState;
    private final BlockState newState;

    private BlockChange(int x, int y, int z, Kind kind, BlockState oldState, BlockState newState) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.kind = kind;
        this.oldState = oldState;
        this.newState = newState;
    }

    /** Air/absent → {@code newState}. */
    public static BlockChange add(int x, int y, int z, BlockState newState) {
        return new BlockChange(x, y, z, Kind.ADD, null,
                Objects.requireNonNull(newState, "newState"));
    }

    /** {@code oldState} → air/absent. */
    public static BlockChange remove(int x, int y, int z, BlockState oldState) {
        return new BlockChange(x, y, z, Kind.REMOVE,
                Objects.requireNonNull(oldState, "oldState"), null);
    }

    /** {@code oldState} → {@code newState}, both non-air. */
    public static BlockChange change(int x, int y, int z, BlockState oldState, BlockState newState) {
        return new BlockChange(x, y, z, Kind.CHANGE,
                Objects.requireNonNull(oldState, "oldState"),
                Objects.requireNonNull(newState, "newState"));
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

    public Kind getKind() {
        return kind;
    }

    /** The previous state, or {@code null} for {@link Kind#ADD}. */
    public BlockState getOldState() {
        return oldState;
    }

    /** The new state, or {@code null} for {@link Kind#REMOVE}. */
    public BlockState getNewState() {
        return newState;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockChange)) {
            return false;
        }
        BlockChange that = (BlockChange) o;
        return x == that.x
                && y == that.y
                && z == that.z
                && kind == that.kind
                && Objects.equals(oldState, that.oldState)
                && Objects.equals(newState, that.newState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, y, z, kind, oldState, newState);
    }

    @Override
    public String toString() {
        return "BlockChange(" + kind + " @" + x + "," + y + "," + z
                + " " + oldState + "->" + newState + ")";
    }
}
