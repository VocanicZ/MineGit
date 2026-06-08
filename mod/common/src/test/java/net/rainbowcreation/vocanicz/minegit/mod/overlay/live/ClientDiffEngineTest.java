package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.Collections;
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

    /** A real "world == HEAD" push: NO dimensions at all (not an empty per-chunk list). */
    private static WorldDiff emptyDiff() {
        return new WorldDiff(Collections.<DimensionId, List<ChunkDiff>>emptyMap(), 0, 0, 0);
    }

    @Test
    void emptyDiffStillSeedsCurrentDimensionsLoadedChunks() {
        // Regression (Forge SP "whole chunk shows +"): a world==HEAD diff carries no dimensions,
        // but the current dimension's loaded chunks must still get a frozen HEAD baseline — else
        // every solid block reads as an addition the moment its section is marked dirty.
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE); // existing terrain == HEAD (clean)
        level.setBlock(2, 5, 2, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);

        engine.onServerDiff(emptyDiff());
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay())); // terrain matches HEAD → no boxes

        // Place ONE new block; only it should diff, NOT the whole section's terrain.
        level.setBlock(3, 5, 3, DIRT);
        engine.onBlockChange(DIM, 3, 5, 3);
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay())); // exactly one box, not the whole chunk
    }

    @Test
    void emptyHeadMoveDiffClearsStaleBoxes() {
        // Regression ("commit while overlay open does not reset"): the post-commit push is an
        // empty (no-dimension) diff and must still drop+reseed the current dimension.
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(emptyDiff());
        engine.tick(64);

        level.setBlock(1, 5, 1, DIRT); // uncommitted edit → one box
        engine.onBlockChange(DIM, 1, 5, 1);
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));

        // Commit: working now == HEAD (DIRT). Server re-pushes an empty diff → must reseed → clear.
        engine.onServerDiff(emptyDiff());
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay()));
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
    void tickSeedsChunksThatBecomeLoadedAfterTheDiff() {
        // A chunk loaded after the diff arrives is seeded by the budgeted tick-poll (NOT onChunkLoad).
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1); // chunk (0,0) NOT loaded yet
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay())); // nothing loaded to seed yet

        level.addLoadedChunk(0, 0);
        level.setBlock(1, 5, 1, STONE); // working has the changed block present
        engine.tick(64);               // tick-poll seeds the now-loaded chunk against the held diff
        assertEquals(1, totalBoxes(engine.currentOverlay())); // HEAD=DIRT vs live=STONE → one box
    }

    @Test
    void chunkSeedingIsBudgetedAcrossTicks() {
        // Many loaded chunks must not all seed in one tick. With SEED_BUDGET chunks/tick, N missing
        // chunks take multiple ticks; place a block in a chunk that is only reached on a later tick and
        // confirm its box appears only once its chunk has been seeded.
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        for (int cx = 0; cx < 10; cx++) {
            level.addLoadedChunk(cx, 0); // 10 loaded chunks, SEED_BUDGET (4) per tick → >=3 ticks
        }
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(emptyDiff());

        // Drain seeding: each tick seeds up to SEED_BUDGET chunks. After enough ticks all 10 are seeded.
        for (int i = 0; i < 10; i++) {
            engine.tick(64);
        }
        // All chunks clean vs HEAD → zero boxes regardless; the real assertion is no exception/freeze
        // and that an edit anywhere now diffs to exactly one box (every chunk has a baseline).
        assertEquals(0, totalBoxes(engine.currentOverlay()));
        level.setBlock(9 * 16 + 1, 5, 1, DIRT); // edit in the last chunk
        engine.onBlockChange(DIM, 9 * 16 + 1, 5, 1);
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay())); // one box, not a whole-chunk "+"
    }

    @Test
    void cleanSeedDoesNotBacklogRealEditsBehindEmptySectionScans() {
        // Perf/correctness regression: seeding a clean chunk must NOT mark all its sections dirty.
        // With many tall chunks that backlog would drain at SECTION_BUDGET/tick for many seconds and
        // a real edit would queue behind it. Here, after seeding 10 clean 16-section chunks under a
        // small section budget, a fresh edit must surface within a single tick.
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 16); // 16 sections/chunk
        for (int cx = 0; cx < 10; cx++) {
            level.addLoadedChunk(cx, 0);
        }
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(emptyDiff());
        for (int i = 0; i < 5; i++) {
            engine.tick(8); // clean seeds must enqueue no diff work — old code backlogged ~160 sections
        }
        assertEquals(0, totalBoxes(engine.currentOverlay()));

        level.setBlock(2 * 16 + 1, 5, 1, STONE); // one edit in an already-seeded chunk
        engine.onBlockChange(DIM, 2 * 16 + 1, 5, 1);
        engine.tick(8); // small budget; the edit's section must be the ONLY dirty one
        assertEquals(1, totalBoxes(engine.currentOverlay())); // instant, not stuck behind a backlog
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
        engine.onServerDiff(diffWith());
        engine.tick(64); // tick-poll seeds chunk (0,0), freezing the clean HEAD baseline
        assertEquals(0, totalBoxes(engine.currentOverlay()));
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
