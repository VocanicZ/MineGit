package com.minegit.core.model;

import java.util.Objects;

/**
 * Immutable dimension identifier. Vanilla values are {@code overworld}, {@code the_nether} and
 * {@code the_end}; custom dimensions use their own namespaced id.
 */
public final class DimensionId {

    public static final DimensionId OVERWORLD = new DimensionId("overworld");
    public static final DimensionId THE_NETHER = new DimensionId("the_nether");
    public static final DimensionId THE_END = new DimensionId("the_end");

    private final String id;

    public DimensionId(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public String getId() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DimensionId)) {
            return false;
        }
        return id.equals(((DimensionId) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return id;
    }
}
