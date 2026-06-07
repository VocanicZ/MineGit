package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.live.HeadBaselineCache;
import org.junit.jupiter.api.Test;

class HeadBaselineCacheTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1); // Y in [0,16), chunk (0,0)
        level.addLoadedChunk(0, 0);
        return level;
    }

    @Test
    void dirtyPositionTakesHeadFromOldState_notLiveWorld() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE); // working has STONE (a CHANGE from HEAD's DIRT)
        List<BlockChange> dirty = Arrays.asList(BlockChange.change(1, 5, 1, DIRT, STONE));
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), dirty, level);
        assertEquals(DIRT, cache.headAt(DIM, 1, 5, 1)); // HEAD is oldState, not live STONE
    }

    @Test
    void cleanPositionTakesHeadFromCapturedLiveWorld() {
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        assertEquals(STONE, cache.headAt(DIM, 2, 5, 2));
    }

    @Test
    void addPositionHasAirHead() {
        FakeLevelAccess level = world();
        level.setBlock(3, 5, 3, STONE);
        List<BlockChange> dirty = Arrays.asList(BlockChange.add(3, 5, 3, STONE));
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), dirty, level);
        assertEquals(BlockState.AIR, cache.headAt(DIM, 3, 5, 3));
    }

    @Test
    void absentPositionIsAir() {
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), world());
        assertEquals(BlockState.AIR, cache.headAt(DIM, 9, 9, 9));
    }

    @Test
    void lruEvictionDropsOldestChunk() {
        HeadBaselineCache cache = new HeadBaselineCache(1); // cap = 1 chunk
        FakeLevelAccess level = world();
        level.addLoadedChunk(1, 0);
        level.setBlock(0, 5, 0, STONE);
        level.setBlock(16, 5, 0, DIRT);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        cache.seed(DIM, new ChunkPos(1, 0), Collections.emptyList(), level);
        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0))); // evicted
        assertTrue(cache.hasChunk(DIM, new ChunkPos(1, 0)));
    }

    @Test
    void dropDimensionClearsThatDimensionOnly() {
        HeadBaselineCache cache = new HeadBaselineCache(256);
        FakeLevelAccess level = world();
        level.setBlock(0, 5, 0, STONE);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        cache.dropDimension(DIM);
        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0)));
    }
}
