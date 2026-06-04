package net.rainbowcreation.vocanicz.minegit.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A version-agnostic normalized chunk.
 *
 * <p>{@code sections} is indexed bottom-up; a {@code null} element means that section is empty (all
 * air). {@code minSection} is the section-Y of {@code sections[0]} (negative for worlds whose build
 * floor is below Y=0).
 *
 * <p>{@code biomes} is the chunk's biome data as a flat {@code int[]} of biome ids. The model is
 * deliberately agnostic about the biome grid resolution (per-column in legacy versions, per-4x4x4
 * cell in modern versions) — it just stores and compares the array by value. (Codec/adapter layers
 * own the resolution semantics; see Spec A §4.)
 *
 * <p>Immutable: the section array and biome array are defensively copied, and the block-entity list
 * is copied and exposed unmodifiable.
 */
public final class NormalizedChunk {

    private final int cx;
    private final int cz;
    private final int minSection;
    private final NormalizedSection[] sections;
    private final int[] biomes;
    private final List<BlockEntity> blockEntities;

    public NormalizedChunk(
            int cx,
            int cz,
            int minSection,
            NormalizedSection[] sections,
            int[] biomes,
            List<BlockEntity> blockEntities) {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(biomes, "biomes");
        Objects.requireNonNull(blockEntities, "blockEntities");
        this.cx = cx;
        this.cz = cz;
        this.minSection = minSection;
        this.sections = sections.clone();
        this.biomes = biomes.clone();
        List<BlockEntity> copy = new ArrayList<BlockEntity>(blockEntities.size());
        for (BlockEntity be : blockEntities) {
            copy.add(Objects.requireNonNull(be, "blockEntity"));
        }
        this.blockEntities = Collections.unmodifiableList(copy);
    }

    public int getCx() {
        return cx;
    }

    public int getCz() {
        return cz;
    }

    /** Section-Y of {@code getSections()[0]}. */
    public int getMinSection() {
        return minSection;
    }

    /** A copy of the section array; {@code null} elements denote empty (all-air) sections. */
    public NormalizedSection[] getSections() {
        return sections.clone();
    }

    /** A copy of the flat biome-id array. */
    public int[] getBiomes() {
        return biomes.clone();
    }

    /** Unmodifiable list of block entities in this chunk. */
    public List<BlockEntity> getBlockEntities() {
        return blockEntities;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NormalizedChunk)) {
            return false;
        }
        NormalizedChunk that = (NormalizedChunk) o;
        return cx == that.cx
                && cz == that.cz
                && minSection == that.minSection
                && Arrays.equals(sections, that.sections)
                && Arrays.equals(biomes, that.biomes)
                && blockEntities.equals(that.blockEntities);
    }

    @Override
    public int hashCode() {
        int result = cx;
        result = 31 * result + cz;
        result = 31 * result + minSection;
        result = 31 * result + Arrays.hashCode(sections);
        result = 31 * result + Arrays.hashCode(biomes);
        result = 31 * result + blockEntities.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "NormalizedChunk(" + cx + ", " + cz + ", minSection=" + minSection
                + ", sections=" + sections.length + ", blockEntities=" + blockEntities.size() + ")";
    }
}
