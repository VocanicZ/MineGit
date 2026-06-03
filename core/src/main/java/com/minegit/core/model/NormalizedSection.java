package com.minegit.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A 16x16x16 block section in palette form.
 *
 * <p>{@code palette} holds the distinct {@link BlockState}s present in the section (in canonical
 * order, enforced by the {@code .mgc} codec) and {@code indices} is a flat {@code int[4096]} of
 * palette indices addressed by {@code Y*256 + Z*16 + X}.
 *
 * <p>Immutable: the palette is copied and exposed unmodifiable; the index array is defensively
 * copied on both input and output.
 */
public final class NormalizedSection {

    /** Number of blocks in a 16x16x16 section. */
    public static final int VOLUME = 4096;

    private final List<BlockState> palette;
    private final int[] indices;

    public NormalizedSection(List<BlockState> palette, int[] indices) {
        Objects.requireNonNull(palette, "palette");
        Objects.requireNonNull(indices, "indices");
        if (indices.length != VOLUME) {
            throw new IllegalArgumentException(
                    "indices length must be " + VOLUME + " but was " + indices.length);
        }
        List<BlockState> copy = new ArrayList<BlockState>(palette.size());
        for (BlockState bs : palette) {
            copy.add(Objects.requireNonNull(bs, "palette entry"));
        }
        this.palette = Collections.unmodifiableList(copy);
        this.indices = indices.clone();
    }

    /** Unmodifiable, canonically ordered palette of distinct block states in this section. */
    public List<BlockState> getPalette() {
        return palette;
    }

    /** A copy of the flat {@code int[4096]} palette indices ({@code Y*256 + Z*16 + X} order). */
    public int[] getIndices() {
        return indices.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NormalizedSection)) {
            return false;
        }
        NormalizedSection that = (NormalizedSection) o;
        return palette.equals(that.palette) && Arrays.equals(indices, that.indices);
    }

    @Override
    public int hashCode() {
        return 31 * palette.hashCode() + Arrays.hashCode(indices);
    }

    @Override
    public String toString() {
        return "NormalizedSection(palette=" + palette + ", indices=int[" + indices.length + "])";
    }
}
