package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

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
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
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
    void removePositionTakesHeadFromOldState_creatingSectionWhenLiveIsAir() {
        // REMOVE: present in HEAD, absent in working. Live snapshot captures nothing for this
        // position, so the overlay must create the section map and store oldState.
        FakeLevelAccess level = world(); // (5,5,5) is air in the live (working) world
        List<BlockChange> dirty = Arrays.asList(BlockChange.remove(5, 5, 5, DIRT));
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), dirty, level);
        assertEquals(DIRT, cache.headAt(DIM, 5, 5, 5));
    }

    @Test
    void baselineIsFrozen_liveEditAfterSeedDoesNotChangeHead() {
        // The §3 correctness guard at the cache level: once seeded, headAt is immutable
        // against subsequent live-world mutation (never re-read from the live world).
        FakeLevelAccess level = world();
        level.setBlock(4, 5, 4, STONE); // clean at seed
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        assertEquals(STONE, cache.headAt(DIM, 4, 5, 4));

        level.setBlock(4, 5, 4, DIRT); // player edits the previously-clean block
        assertEquals(STONE, cache.headAt(DIM, 4, 5, 4)); // frozen — still pre-edit HEAD
    }

    @Test
    void rejectsCapBelowOne() {
        try {
            new HeadBaselineCache(0);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
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
        FakeLevelAccess overworld = world();
        overworld.setBlock(0, 5, 0, STONE);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), overworld);

        FakeLevelAccess nether = new FakeLevelAccess(DimensionId.THE_NETHER, 0, 1);
        nether.addLoadedChunk(0, 0);
        nether.setBlock(0, 5, 0, DIRT);
        cache.seed(DimensionId.THE_NETHER, new ChunkPos(0, 0), Collections.emptyList(), nether);

        cache.dropDimension(DIM);
        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0)));
        assertTrue(cache.hasChunk(DimensionId.THE_NETHER, new ChunkPos(0, 0))); // other dim preserved
    }

    @Test
    void dropAllClearsEverything() {
        HeadBaselineCache cache = new HeadBaselineCache(256);
        FakeLevelAccess level = world();
        level.setBlock(0, 5, 0, STONE);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        cache.dropAll();
        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0)));
    }
}
