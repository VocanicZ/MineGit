package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ModWorldAdapter}: read/apply over the {@link LevelAccess} seam and loaded-chunk
 * enumeration, driven by a pure {@link FakeLevelAccess} (no running server). The seam isolates the
 * Minecraft-touching {@code ServerLevelAccess}; this exercises the version-agnostic adapter logic.
 */
class ModWorldAdapterTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");

    @Test
    void dimensionsIsTheBoundLevelOnly() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        assertEquals(Collections.singleton(DimensionId.OVERWORLD), adapter.dimensions());
    }

    @Test
    void allChunksMapsLoadedChunksToRefs() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        level.addLoadedChunk(0, 0);
        level.addLoadedChunk(3, -2);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        Set<ChunkRef> refs = adapter.allChunks();
        assertEquals(2, refs.size());
        assertTrue(refs.contains(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 0))));
        assertTrue(refs.contains(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(3, -2))));
    }

    @Test
    void drainDirtyReturnsLoadedChunksFirstSlice() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        level.addLoadedChunk(0, 0);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        assertEquals(adapter.allChunks(), adapter.drainDirty());
    }

    @Test
    void readForeignDimensionIsNull() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        assertNull(adapter.read(DimensionId.THE_NETHER, new ChunkPos(0, 0)));
    }

    @Test
    void readBuildsChunkWithPlacedBlockInPalette() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        // Block at world (1, 2, 3) — chunk (0,0), local (1,2,3) -> index 2*256 + 3*16 + 1 = 561.
        level.setBlock(1, 2, 3, STONE);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        NormalizedChunk chunk = adapter.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));
        assertEquals(0, chunk.getCx());
        assertEquals(0, chunk.getCz());
        NormalizedSection section = chunk.getSections()[0];
        int idx = 2 * 256 + 3 * 16 + 1;
        BlockState placed = section.getPalette().get(section.getIndices()[idx]);
        assertEquals(STONE, placed);
    }

    @Test
    void readAllAirSectionIsNull() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        NormalizedChunk chunk = adapter.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));
        assertNull(chunk.getSections()[0]);
    }

    @Test
    void applyWritesNewStatesAndRemovesToAir() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        level.setBlock(5, 6, 7, STONE);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        List<BlockChange> changes =
                Arrays.asList(
                        BlockChange.add(2, 3, 4, STONE), // ADD
                        BlockChange.remove(5, 6, 7, STONE)); // REMOVE -> air
        adapter.apply(DimensionId.OVERWORLD, new ChunkPos(0, 0), changes);
        assertEquals(STONE, level.getBlock(2, 3, 4));
        assertEquals(BlockState.AIR, level.getBlock(5, 6, 7));
    }

    @Test
    void applyForeignDimensionIsNoOp() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        adapter.apply(
                DimensionId.THE_END,
                new ChunkPos(0, 0),
                Collections.singletonList(BlockChange.add(2, 3, 4, STONE)));
        assertEquals(BlockState.AIR, level.getBlock(2, 3, 4));
    }

    @Test
    void writeChunkMaterializesNonAirBlocks() {
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD, 0, 1);
        NormalizedChunk chunk = chunkWithStoneAt(0, 0, 1, 2, 3);
        ModWorldAdapter adapter = new ModWorldAdapter(level);
        adapter.writeChunk(DimensionId.OVERWORLD, chunk);
        assertEquals(STONE, level.getBlock(1, 2, 3));
    }

    /** Builds a one-section chunk (minSection 0) with a single stone block at the given local pos. */
    private static NormalizedChunk chunkWithStoneAt(int cx, int cz, int lx, int ly, int lz) {
        int[] indices = new int[NormalizedSection.VOLUME];
        int idx = ly * 256 + lz * 16 + lx;
        indices[idx] = 1;
        List<BlockState> palette = Arrays.asList(BlockState.AIR, STONE);
        NormalizedSection section = new NormalizedSection(palette, indices);
        return new NormalizedChunk(
                cx,
                cz,
                0,
                new NormalizedSection[] {section},
                new int[0],
                Collections.<net.rainbowcreation.vocanicz.minegit.core.model.BlockEntity>emptyList());
    }
}
