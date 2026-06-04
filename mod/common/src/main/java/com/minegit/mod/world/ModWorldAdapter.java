package com.minegit.mod.world;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockEntity;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Modern, reflection-free {@link WorldAdapter} over a single bound level, reading and writing blocks
 * through a {@link LevelAccess} seam (Spec D §3). The seam keeps every Minecraft type out of this
 * class, so the read/apply/enumeration logic is unit-testable against a pure fake while the real
 * {@code ServerLevelAccess} bridges to {@code ServerLevel.getBlockState}/{@code setBlock}.
 *
 * <p>This first slice is <strong>blocks only</strong> (no block entities or biomes) and snapshots
 * <strong>currently loaded chunks</strong>; event-based dirty tracking is a follow-up, so {@link
 * #drainDirty()} returns the loaded set and downstream determinism dedupes unchanged chunks. All
 * access is expected on the server thread; the frontend owns thread hopping (Spec D §3).
 */
public final class ModWorldAdapter implements WorldAdapter {

    private static final int SECTION_SIZE = 16;

    private final LevelAccess level;
    private final DimensionId dimension;

    public ModWorldAdapter(LevelAccess level) {
        this.level = Objects.requireNonNull(level, "level");
        this.dimension = level.dimension();
    }

    /** The dimension this adapter's bound level maps to. */
    public DimensionId dimension() {
        return dimension;
    }

    @Override
    public Set<DimensionId> dimensions() {
        return Collections.singleton(dimension);
    }

    @Override
    public Set<ChunkRef> allChunks() {
        Set<ChunkRef> all = new HashSet<ChunkRef>();
        for (ChunkPos pos : level.loadedChunks()) {
            all.add(new ChunkRef(dimension, pos));
        }
        return all;
    }

    @Override
    public Set<ChunkRef> drainDirty() {
        // First slice: no event-based dirty set. The loaded chunks are the candidate set; the engine
        // dedupes unchanged ones by content. Event tracking is the immediate follow-up (Spec D §3).
        return allChunks();
    }

    @Override
    public NormalizedChunk read(DimensionId requested, ChunkPos pos) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(pos, "pos");
        if (!dimension.equals(requested)) {
            return null;
        }
        int minSection = level.minSectionY();
        int sectionCount = level.sectionCount();
        NormalizedSection[] sections = new NormalizedSection[sectionCount];
        for (int s = 0; s < sectionCount; s++) {
            sections[s] = readSection(pos, minSection + s);
        }
        return new NormalizedChunk(
                pos.getCx(),
                pos.getCz(),
                minSection,
                sections,
                new int[0],
                Collections.<BlockEntity>emptyList());
    }

    @Override
    public void apply(DimensionId requested, ChunkPos pos, List<BlockChange> changes) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(changes, "changes");
        if (!dimension.equals(requested)) {
            return;
        }
        for (BlockChange c : changes) {
            BlockState target = c.getNewState() != null ? c.getNewState() : BlockState.AIR;
            level.setBlock(c.getX(), c.getY(), c.getZ(), target);
        }
    }

    @Override
    public void writeChunk(DimensionId requested, NormalizedChunk chunk) {
        Objects.requireNonNull(requested, "dimension");
        Objects.requireNonNull(chunk, "chunk");
        if (!dimension.equals(requested)) {
            return;
        }
        int cx = chunk.getCx();
        int cz = chunk.getCz();
        NormalizedSection[] sections = chunk.getSections();
        for (int s = 0; s < sections.length; s++) {
            NormalizedSection section = sections[s];
            if (section == null) {
                continue; // all-air section, nothing to place
            }
            int sectionY = chunk.getMinSection() + s;
            List<BlockState> palette = section.getPalette();
            int[] indices = section.getIndices();
            for (int i = 0; i < NormalizedSection.VOLUME; i++) {
                BlockState state = palette.get(indices[i]);
                if (state.equals(BlockState.AIR)) {
                    continue;
                }
                int localY = i / 256;
                int localZ = (i % 256) / 16;
                int localX = i % 16;
                level.setBlock(
                        cx * SECTION_SIZE + localX,
                        sectionY * SECTION_SIZE + localY,
                        cz * SECTION_SIZE + localZ,
                        state);
            }
        }
    }

    /**
     * Reads section-Y {@code sectionY} of chunk {@code pos} into a {@link NormalizedSection}, or
     * {@code null} if the section is all air. The palette is ordered by first appearance scanning
     * {@code Y*256 + Z*16 + X}.
     */
    private NormalizedSection readSection(ChunkPos pos, int sectionY) {
        List<BlockState> palette = new ArrayList<BlockState>();
        Map<BlockState, Integer> paletteIndex = new HashMap<BlockState, Integer>();
        int[] indices = new int[NormalizedSection.VOLUME];
        boolean anyNonAir = false;
        int baseX = pos.getCx() * SECTION_SIZE;
        int baseY = sectionY * SECTION_SIZE;
        int baseZ = pos.getCz() * SECTION_SIZE;
        for (int i = 0; i < NormalizedSection.VOLUME; i++) {
            int localY = i / 256;
            int localZ = (i % 256) / 16;
            int localX = i % 16;
            BlockState state = level.getBlock(baseX + localX, baseY + localY, baseZ + localZ);
            if (state == null) {
                state = BlockState.AIR;
            }
            if (!state.equals(BlockState.AIR)) {
                anyNonAir = true;
            }
            Integer idx = paletteIndex.get(state);
            if (idx == null) {
                idx = palette.size();
                palette.add(state);
                paletteIndex.put(state, idx);
            }
            indices[i] = idx;
        }
        return anyNonAir ? new NormalizedSection(palette, indices) : null;
    }
}
