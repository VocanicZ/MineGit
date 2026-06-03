package com.minegit.core.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.minegit.core.adapter.ChunkRef;
import com.minegit.core.model.BlockChange;
import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import com.minegit.core.model.NormalizedChunk;
import com.minegit.core.model.NormalizedSection;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FakeWorldAdapterTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    // --- set / read round-trip --------------------------------------------------------------

    @Test
    void setThenGetReturnsBlock() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 5, 70, 9, STONE);
        assertEquals(STONE, world.getBlock(DimensionId.OVERWORLD, 5, 70, 9));
    }

    @Test
    void unsetBlockIsAir() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 0, 0, 0));
    }

    @Test
    void readReflectsSetBlock() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 2, STONE);

        NormalizedChunk chunk = world.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));
        assertNotNull(chunk);
        assertEquals(0, chunk.getCx());
        assertEquals(0, chunk.getCz());

        // y=64 -> sectionY=4 -> index 4 - (-4) = 8
        NormalizedSection section = chunk.getSections()[64 / 16 - chunk.getMinSection()];
        assertNotNull(section);
        int local = (64 % 16) * 256 + 2 * 16 + 1; // localY*256 + localZ*16 + localX
        int paletteIdx = section.getIndices()[local];
        assertEquals(STONE, section.getPalette().get(paletteIdx));
    }

    @Test
    void readAbsentChunkIsNull() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        assertNull(world.read(DimensionId.OVERWORLD, new ChunkPos(0, 0)));
    }

    @Test
    void emptySectionsAreNull() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        NormalizedChunk chunk = world.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));
        // Only the section holding y=64 is populated; all others are air -> null.
        int populated = 64 / 16 - chunk.getMinSection();
        for (int s = 0; s < chunk.getSections().length; s++) {
            if (s == populated) {
                assertNotNull(chunk.getSections()[s]);
            } else {
                assertNull(chunk.getSections()[s]);
            }
        }
    }

    @Test
    void overwriteBlock() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, DIRT);
        assertEquals(DIRT, world.getBlock(DimensionId.OVERWORLD, 0, 64, 0));
    }

    // --- negative / floored coordinate mapping ----------------------------------------------

    @Test
    void negativeCoordinatesMapToFlooredChunk() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, -1, 64, -17, STONE);
        // floorDiv(-1,16) = -1 ; floorDiv(-17,16) = -2
        assertNotNull(world.read(DimensionId.OVERWORLD, new ChunkPos(-1, -2)));
        assertEquals(STONE, world.getBlock(DimensionId.OVERWORLD, -1, 64, -17));
    }

    @Test
    void belowFloorRejected() {
        FakeWorldAdapter world = new FakeWorldAdapter(); // range [-64, 320)
        assertThrows(
                IllegalArgumentException.class,
                () -> world.setBlock(DimensionId.OVERWORLD, 0, -65, 0, STONE));
    }

    @Test
    void aboveCeilingRejected() {
        FakeWorldAdapter world = new FakeWorldAdapter(); // range [-64, 320)
        assertThrows(
                IllegalArgumentException.class,
                () -> world.setBlock(DimensionId.OVERWORLD, 0, 320, 0, STONE));
    }

    @Test
    void negativeYWithinRangeAllowed() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, -64, 0, STONE);
        assertEquals(STONE, world.getBlock(DimensionId.OVERWORLD, 0, -64, 0));
    }

    // --- dirty tracking ---------------------------------------------------------------------

    @Test
    void setBlockMarksChunkDirty() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 5, 64, 9, STONE);
        Set<ChunkRef> dirty = world.drainDirty();
        assertEquals(
                Collections.singleton(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 0))),
                dirty);
    }

    @Test
    void drainClearsDirtySet() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.drainDirty();
        assertTrue(world.drainDirty().isEmpty());
    }

    @Test
    void multipleBlocksSameChunkCoalesceToOneDirtyRef() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.OVERWORLD, 1, 70, 2, DIRT);
        assertEquals(1, world.drainDirty().size());
    }

    @Test
    void dirtyTracksMultipleChunks() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.OVERWORLD, 16, 64, 0, STONE); // chunk (1,0)
        assertEquals(2, world.drainDirty().size());
    }

    @Test
    void freshWorldHasNoDirty() {
        assertTrue(new FakeWorldAdapter().drainDirty().isEmpty());
    }

    // --- multi-dimension storage ------------------------------------------------------------

    @Test
    void dimensionsListsOnlyTouchedDimensions() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        assertTrue(world.dimensions().isEmpty());
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.THE_NETHER, 0, 64, 0, DIRT);
        assertEquals(
                new java.util.HashSet<DimensionId>(
                        java.util.Arrays.asList(DimensionId.OVERWORLD, DimensionId.THE_NETHER)),
                world.dimensions());
        assertFalse(world.dimensions().contains(DimensionId.THE_END));
    }

    @Test
    void dimensionsAreIsolated() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.THE_NETHER, 0, 64, 0, DIRT);
        assertEquals(STONE, world.getBlock(DimensionId.OVERWORLD, 0, 64, 0));
        assertEquals(DIRT, world.getBlock(DimensionId.THE_NETHER, 0, 64, 0));
        assertSame(BlockState.AIR, world.getBlock(DimensionId.THE_END, 0, 64, 0));
    }

    // --- allChunks enumeration --------------------------------------------------------------

    @Test
    void allChunksEnumeratesAcrossDimensions() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.setBlock(DimensionId.OVERWORLD, 16, 64, 0, STONE);
        world.setBlock(DimensionId.THE_NETHER, 0, 64, 0, DIRT);

        Set<ChunkRef> all = world.allChunks();
        assertEquals(3, all.size());
        assertTrue(all.contains(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(0, 0))));
        assertTrue(all.contains(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(1, 0))));
        assertTrue(all.contains(new ChunkRef(DimensionId.THE_NETHER, new ChunkPos(0, 0))));
    }

    @Test
    void allChunksDoesNotConsumeDirty() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE);
        world.allChunks();
        assertFalse(world.drainDirty().isEmpty());
    }

    @Test
    void emptyWorldHasNoChunks() {
        assertTrue(new FakeWorldAdapter().allChunks().isEmpty());
    }

    // --- custom vertical range --------------------------------------------------------------

    @Test
    void customSectionRange() {
        FakeWorldAdapter world = new FakeWorldAdapter(0, 16); // classic y in [0,256)
        assertEquals(0, world.getMinSection());
        assertEquals(16, world.getSectionCount());
        world.setBlock(DimensionId.OVERWORLD, 0, 255, 0, STONE);
        assertEquals(STONE, world.getBlock(DimensionId.OVERWORLD, 0, 255, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> world.setBlock(DimensionId.OVERWORLD, 0, -1, 0, STONE));
        NormalizedChunk chunk = world.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));
        assertEquals(0, chunk.getMinSection());
        assertEquals(16, chunk.getSections().length);
    }

    @Test
    void invalidSectionCountRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FakeWorldAdapter(0, 0));
    }

    // --- apply (checkout delta) -------------------------------------------------------------

    @Test
    void applyAddsChangesAndRemovesBlocks() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, STONE); // will be removed
        world.setBlock(DimensionId.OVERWORLD, 1, 64, 0, STONE); // will be changed to dirt
        ChunkPos pos = new ChunkPos(0, 0);
        world.apply(
                DimensionId.OVERWORLD,
                pos,
                Arrays.asList(
                        BlockChange.remove(0, 64, 0, STONE),
                        BlockChange.change(1, 64, 0, STONE, DIRT),
                        BlockChange.add(2, 64, 0, DIRT)));
        assertSame(BlockState.AIR, world.getBlock(DimensionId.OVERWORLD, 0, 64, 0));
        assertEquals(DIRT, world.getBlock(DimensionId.OVERWORLD, 1, 64, 0));
        assertEquals(DIRT, world.getBlock(DimensionId.OVERWORLD, 2, 64, 0));
    }

    @Test
    void applyRejectsNulls() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        assertThrows(
                NullPointerException.class,
                () -> world.apply(null, new ChunkPos(0, 0), Collections.<BlockChange>emptyList()));
        assertThrows(
                NullPointerException.class,
                () -> world.apply(DimensionId.OVERWORLD, null, Collections.<BlockChange>emptyList()));
        assertThrows(
                NullPointerException.class,
                () -> world.apply(DimensionId.OVERWORLD, new ChunkPos(0, 0), null));
    }

    // --- writeChunk (clone materialize) -----------------------------------------------------

    @Test
    void writeChunkMaterializesEveryNonAirBlock() {
        FakeWorldAdapter source = new FakeWorldAdapter();
        source.setBlock(DimensionId.OVERWORLD, 1, 64, 2, STONE);
        source.setBlock(DimensionId.OVERWORLD, 3, -10, 4, DIRT);
        NormalizedChunk chunk = source.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));

        FakeWorldAdapter dest = new FakeWorldAdapter();
        dest.writeChunk(DimensionId.OVERWORLD, chunk);

        assertEquals(STONE, dest.getBlock(DimensionId.OVERWORLD, 1, 64, 2));
        assertEquals(DIRT, dest.getBlock(DimensionId.OVERWORLD, 3, -10, 4));
        // The round-tripped chunk is byte-for-byte the same normalized chunk.
        assertEquals(chunk, dest.read(DimensionId.OVERWORLD, new ChunkPos(0, 0)));
    }

    @Test
    void writeChunkRejectsNulls() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        NormalizedChunk chunk =
                new NormalizedChunk(
                        0,
                        0,
                        -4,
                        new NormalizedSection[1],
                        new int[0],
                        Collections.<com.minegit.core.model.BlockEntity>emptyList());
        assertThrows(NullPointerException.class, () -> world.writeChunk(null, chunk));
        assertThrows(
                NullPointerException.class,
                () -> world.writeChunk(DimensionId.OVERWORLD, null));
    }

    // --- null guards ------------------------------------------------------------------------

    @Test
    void setBlockRejectsNulls() {
        FakeWorldAdapter world = new FakeWorldAdapter();
        assertThrows(
                NullPointerException.class,
                () -> world.setBlock(null, 0, 64, 0, STONE));
        assertThrows(
                NullPointerException.class,
                () -> world.setBlock(DimensionId.OVERWORLD, 0, 64, 0, null));
    }
}
