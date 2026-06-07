package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayState;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import org.junit.jupiter.api.Test;

class ClientDiffEngineTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        level.addLoadedChunk(0, 0);
        return level;
    }

    private static WorldDiff diffWith(BlockChange... changes) {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DIM, Arrays.asList(new ChunkDiff(new ChunkPos(0, 0), Arrays.asList(changes))));
        int add = 0, rem = 0, chg = 0;
        for (BlockChange c : changes) {
            switch (c.getKind()) {
                case ADD: add++; break;
                case REMOVE: rem++; break;
                default: chg++; break;
            }
        }
        return new WorldDiff(dims, add, rem, chg);
    }

    private static int totalBoxes(OverlayState state) {
        return state == null ? 0 : state.boxes(DIM).size();
    }

    @Test
    void noOverlayBeforeFirstDiff() {
        FakeLevelAccess level = world();
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.tick(8);
        assertNull(engine.currentOverlay());
    }

    @Test
    void serverDiffSeedsAndReproducesTheServerDiffAtPushInstant() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        OverlayState overlay = engine.currentOverlay();
        assertNotNull(overlay);
        assertEquals(1, totalBoxes(overlay));
    }

    @Test
    void liveEditAfterSeedRaisesBoxWithNoServerTraffic() {
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE); // clean at seed
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith()); // empty diff seeds the loaded chunk's HEAD
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay()));
        level.setBlock(2, 5, 2, DIRT);
        engine.onBlockChange(DIM, 2, 5, 2);
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));
    }

    @Test
    void headMoveResetDropsStaleBoxesAndReseeds() {
        FakeLevelAccess level = world();
        level.setBlock(3, 5, 3, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(3, 5, 3, DIRT, STONE)));
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));
        engine.onServerDiff(diffWith()); // commit: HEAD now matches working, empty diff
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay()));
    }

    @Test
    void chunkLoadedAfterDiffIsSeededAgainstHeldDiff() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1); // chunk (0,0) NOT loaded yet
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay()));
        level.addLoadedChunk(0, 0);
        level.setBlock(1, 5, 1, STONE);
        engine.onChunkLoad(DIM, new ChunkPos(0, 0));
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));
    }

    @Test
    void resetClearsEverything() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        engine.reset();
        assertNull(engine.currentOverlay());
    }

    @Test
    void budgetSpreadsWorkAcrossTicks() {
        // Two distinct dirty sections; a budget of 1 reflects only one per tick, both after two.
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 2); // Y in [0,32): sections 0 and 1
        level.addLoadedChunk(0, 0);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith()); // seeds chunk (0,0): marks sections 0 and 1 dirty
        // Player edits one block in section 0 (y=5) and one in section 1 (y=20).
        level.setBlock(1, 5, 1, STONE);
        engine.onBlockChange(DIM, 1, 5, 1);
        level.setBlock(1, 20, 1, STONE);
        engine.onBlockChange(DIM, 1, 20, 1);

        engine.tick(1); // budget of 1 → only the first dirty section re-diffed
        assertEquals(1, totalBoxes(engine.currentOverlay()));
        engine.tick(1); // second tick drains the remaining section
        assertEquals(2, totalBoxes(engine.currentOverlay()));
    }

    @Test
    void rejectsNullSupplier() {
        try {
            new ClientDiffEngine(256, null);
            org.junit.jupiter.api.Assertions.fail("expected NullPointerException");
        } catch (NullPointerException expected) {
            // ok
        }
    }
}
