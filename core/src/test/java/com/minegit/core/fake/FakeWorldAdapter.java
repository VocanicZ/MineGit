package com.minegit.core.fake;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link WorldAdapter} for tests and the {@code minegit-cli} harness. Backed by a
 * {@code Map<DimensionId, Map<ChunkPos, ...>>} of mutable chunk grids, it implements the full adapter
 * contract — {@link #read}, {@link #allChunks}, {@link #drainDirty} and {@link #dimensions} — with
 * zero Minecraft, so every engine path can run headless.
 *
 * <p>Block columns span section-Y {@code minSection .. minSection + sectionCount - 1}; the defaults
 * ({@code -4}, {@code 24}) cover the modern build range {@code y in [-64, 320)}. World coordinates are
 * mapped to chunks with {@code floorDiv}/{@code floorMod}, so negative coordinates behave correctly.
 */
public final class FakeWorldAdapter implements WorldAdapter {

    private static final int DEFAULT_MIN_SECTION = -4;
    private static final int DEFAULT_SECTION_COUNT = 24;

    private final int minSection;
    private final int sectionCount;

    private final Map<DimensionId, Map<ChunkPos, BlockState[]>> store =
            new LinkedHashMap<DimensionId, Map<ChunkPos, BlockState[]>>();
    private final Set<ChunkRef> dirty = new HashSet<ChunkRef>();

    /** Creates a fake world spanning the modern build range {@code y in [-64, 320)}. */
    public FakeWorldAdapter() {
        this(DEFAULT_MIN_SECTION, DEFAULT_SECTION_COUNT);
    }

    /**
     * Creates a fake world whose columns span sections {@code minSection .. minSection+sectionCount-1}.
     *
     * @throws IllegalArgumentException if {@code sectionCount < 1}
     */
    public FakeWorldAdapter(int minSection, int sectionCount) {
        if (sectionCount < 1) {
            throw new IllegalArgumentException("sectionCount must be >= 1 but was " + sectionCount);
        }
        this.minSection = minSection;
        this.sectionCount = sectionCount;
    }

    /** Section-Y of the lowest section in every column. */
    public int getMinSection() {
        return minSection;
    }

    /** Number of vertical sections in every column. */
    public int getSectionCount() {
        return sectionCount;
    }

    /**
     * Sets the block at world coordinates {@code (x, y, z)} in {@code dimension}, creating the chunk
     * (and dimension) if absent and marking the chunk dirty.
     *
     * @throws IllegalArgumentException if {@code y} is outside this world's vertical range
     */
    public void setBlock(DimensionId dimension, int x, int y, int z, BlockState state) {
        java.util.Objects.requireNonNull(dimension, "dimension");
        java.util.Objects.requireNonNull(state, "state");
        int linear = checkedLinearIndex(y, x, z);
        ChunkPos pos = chunkPos(x, z);
        Map<ChunkPos, BlockState[]> dim = store.get(dimension);
        if (dim == null) {
            dim = new LinkedHashMap<ChunkPos, BlockState[]>();
            store.put(dimension, dim);
        }
        BlockState[] blocks = dim.get(pos);
        if (blocks == null) {
            blocks = new BlockState[sectionCount * NormalizedSection.VOLUME];
            dim.put(pos, blocks);
        }
        blocks[linear] = state;
        dirty.add(new ChunkRef(dimension, pos));
    }

    /**
     * Returns the block at world coordinates {@code (x, y, z)} in {@code dimension}, or
     * {@link BlockState#AIR} if nothing was set there (or the chunk does not exist).
     *
     * @throws IllegalArgumentException if {@code y} is outside this world's vertical range
     */
    public BlockState getBlock(DimensionId dimension, int x, int y, int z) {
        java.util.Objects.requireNonNull(dimension, "dimension");
        int linear = checkedLinearIndex(y, x, z);
        Map<ChunkPos, BlockState[]> dim = store.get(dimension);
        if (dim == null) {
            return BlockState.AIR;
        }
        BlockState[] blocks = dim.get(chunkPos(x, z));
        if (blocks == null || blocks[linear] == null) {
            return BlockState.AIR;
        }
        return blocks[linear];
    }

    @Override
    public Set<DimensionId> dimensions() {
        return new HashSet<DimensionId>(store.keySet());
    }

    @Override
    public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
        java.util.Objects.requireNonNull(dimension, "dimension");
        java.util.Objects.requireNonNull(pos, "pos");
        Map<ChunkPos, BlockState[]> dim = store.get(dimension);
        if (dim == null) {
            return null;
        }
        BlockState[] blocks = dim.get(pos);
        if (blocks == null) {
            return null;
        }
        NormalizedSection[] sections = new NormalizedSection[sectionCount];
        for (int s = 0; s < sectionCount; s++) {
            sections[s] = materializeSection(blocks, s);
        }
        return new NormalizedChunk(
                pos.getCx(),
                pos.getCz(),
                minSection,
                sections,
                new int[0],
                Collections.<com.minegit.core.model.BlockEntity>emptyList());
    }

    @Override
    public Set<ChunkRef> allChunks() {
        Set<ChunkRef> all = new HashSet<ChunkRef>();
        for (Map.Entry<DimensionId, Map<ChunkPos, BlockState[]>> e : store.entrySet()) {
            for (ChunkPos pos : e.getValue().keySet()) {
                all.add(new ChunkRef(e.getKey(), pos));
            }
        }
        return all;
    }

    @Override
    public Set<ChunkRef> drainDirty() {
        Set<ChunkRef> drained = new HashSet<ChunkRef>(dirty);
        dirty.clear();
        return drained;
    }

    /**
     * Builds the {@link NormalizedSection} for section index {@code s}, or returns {@code null} if the
     * section holds nothing but air (the chunk model's convention for an empty section). The palette
     * is ordered by first appearance scanning {@code Y*256 + Z*16 + X}.
     */
    private NormalizedSection materializeSection(BlockState[] blocks, int s) {
        int base = s * NormalizedSection.VOLUME;
        boolean anyNonAir = false;
        for (int i = 0; i < NormalizedSection.VOLUME; i++) {
            BlockState bs = blocks[base + i];
            if (bs != null && !bs.equals(BlockState.AIR)) {
                anyNonAir = true;
                break;
            }
        }
        if (!anyNonAir) {
            return null;
        }
        List<BlockState> palette = new ArrayList<BlockState>();
        Map<BlockState, Integer> paletteIndex = new HashMap<BlockState, Integer>();
        int[] indices = new int[NormalizedSection.VOLUME];
        for (int i = 0; i < NormalizedSection.VOLUME; i++) {
            BlockState bs = blocks[base + i];
            if (bs == null) {
                bs = BlockState.AIR;
            }
            Integer idx = paletteIndex.get(bs);
            if (idx == null) {
                idx = palette.size();
                palette.add(bs);
                paletteIndex.put(bs, idx);
            }
            indices[i] = idx;
        }
        return new NormalizedSection(palette, indices);
    }

    /** Linear index into a chunk's {@code sectionCount * VOLUME} block array; validates the Y range. */
    private int checkedLinearIndex(int y, int x, int z) {
        int sectionY = Math.floorDiv(y, 16);
        int sectionIndex = sectionY - minSection;
        if (sectionIndex < 0 || sectionIndex >= sectionCount) {
            throw new IllegalArgumentException(
                    "y=" + y + " is outside the world range [" + (minSection * 16) + ", "
                            + ((minSection + sectionCount) * 16) + ")");
        }
        int localX = Math.floorMod(x, 16);
        int localY = Math.floorMod(y, 16);
        int localZ = Math.floorMod(z, 16);
        return sectionIndex * NormalizedSection.VOLUME + (localY * 256 + localZ * 16 + localX);
    }

    private static ChunkPos chunkPos(int x, int z) {
        return new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }
}
