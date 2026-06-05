package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.protocol.DiffPayload;
import net.rainbowcreation.vocanicz.minegit.protocol.Frame;
import net.rainbowcreation.vocanicz.minegit.protocol.Framing;
import net.rainbowcreation.vocanicz.minegit.protocol.Reassembler;

/** Headless behavior tests for the GPU-agnostic overlay core (Spec C §2.3, §5). */
class OverlayStateTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");

    // ---- builders ------------------------------------------------------------------------------

    private static ChunkDiff chunk(int cx, int cz, BlockChange... changes) {
        return new ChunkDiff(new ChunkPos(cx, cz), Arrays.asList(changes));
    }

    /** A two-dimension diff: overworld has ADD+REMOVE+CHANGE, nether has one ADD. */
    private static WorldDiff sampleDiff() {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(
                chunk(0, 0,
                        BlockChange.add(1, 64, 1, STONE),
                        BlockChange.remove(2, 64, 2, DIRT),
                        BlockChange.change(3, 64, 3, DIRT, STONE))));
        dims.put(DimensionId.THE_NETHER, Arrays.asList(
                chunk(0, 0, BlockChange.add(5, 10, 5, STONE))));
        return new WorldDiff(dims, 2, 1, 1);
    }

    // ---- reassemble → decode → state equality --------------------------------------------------

    @Test
    void framesReassembleDecodeToEqualState() {
        WorldDiff diff = sampleDiff();
        byte[] payload = DiffPayload.encode(diff, "main", "HEAD");

        // Frame → toBytes → fromBytes → Reassembler, delivered out of order.
        List<Frame> frames = Framing.frame(payload, 64);
        List<Frame> shuffled = new ArrayList<Frame>(frames);
        java.util.Collections.reverse(shuffled);

        Reassembler reassembler = new Reassembler();
        byte[] reassembled = null;
        for (Frame f : shuffled) {
            Optional<byte[]> done = reassembler.add(Frame.fromBytes(f.toBytes()));
            if (done.isPresent()) {
                reassembled = done.get();
            }
        }

        OverlayState fromWire = OverlayState.fromPayload(reassembled, 100L);
        OverlayState direct = new OverlayState(diff, "main", "HEAD", 100L);

        assertEquals(direct, fromWire);
        assertEquals(2, fromWire.getAdded());
        assertEquals(1, fromWire.getRemoved());
        assertEquals(1, fromWire.getChanged());
        assertEquals("main", fromWire.getFromRef());
        assertEquals("HEAD", fromWire.getToRef());
        assertEquals(100L, fromWire.getReceivedAt());
    }

    // ---- color mapping per Kind ----------------------------------------------------------------

    @Test
    void colorMatchesKind() {
        assertEquals(OverlayColor.GREEN, OverlayColor.forKind(BlockChange.Kind.ADD));
        assertEquals(OverlayColor.RED, OverlayColor.forKind(BlockChange.Kind.REMOVE));
        assertEquals(OverlayColor.YELLOW, OverlayColor.forKind(BlockChange.Kind.CHANGE));

        OverlayState state = new OverlayState(sampleDiff(), "a", "b", 0L);
        List<OverlayBox> overworld = state.boxes(DimensionId.OVERWORLD);
        assertEquals(new OverlayBox(1, 64, 1, OverlayColor.GREEN), overworld.get(0));
        assertEquals(new OverlayBox(2, 64, 2, OverlayColor.RED), overworld.get(1));
        assertEquals(new OverlayBox(3, 64, 3, OverlayColor.YELLOW), overworld.get(2));
    }

    // ---- per-dimension separation --------------------------------------------------------------

    @Test
    void boxesSeparatedByDimension() {
        OverlayState state = new OverlayState(sampleDiff(), "a", "b", 0L);
        assertEquals(3, state.boxes(DimensionId.OVERWORLD).size());
        assertEquals(1, state.boxes(DimensionId.THE_NETHER).size());
        assertTrue(state.boxes(DimensionId.THE_END).isEmpty());
        // a dimension's boxes never leak into another's
        assertEquals(new OverlayBox(5, 10, 5, OverlayColor.GREEN),
                state.boxes(DimensionId.THE_NETHER).get(0));
    }

    // ---- visibleBoxes: distance cull -----------------------------------------------------------

    @Test
    void visibleBoxesCullsBeyondMaxDistance() {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(chunk(0, 0,
                BlockChange.add(0, 0, 0, STONE),     // ~0.87 from cam at (0,0,0)
                BlockChange.add(100, 0, 0, STONE)))); // ~100 away
        WorldDiff diff = new WorldDiff(dims, 2, 0, 0);
        OverlayState state = new OverlayState(diff, "a", "b", 0L);

        OverlayState.VisibleBoxes vis =
                state.visibleBoxes(DimensionId.OVERWORLD, 0, 0, 0, 64, 4096);
        assertEquals(1, vis.getBoxes().size());
        assertEquals(new OverlayBox(0, 0, 0, OverlayColor.GREEN), vis.getBoxes().get(0));
        assertEquals(0, vis.getDropped(), "out-of-range boxes are not 'dropped'");
    }

    // ---- visibleBoxes: nearest-first ordering --------------------------------------------------

    @Test
    void visibleBoxesSortedNearestFirst() {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(chunk(0, 0,
                BlockChange.add(30, 0, 0, STONE),
                BlockChange.add(5, 0, 0, STONE),
                BlockChange.add(15, 0, 0, STONE))));
        WorldDiff diff = new WorldDiff(dims, 3, 0, 0);
        OverlayState state = new OverlayState(diff, "a", "b", 0L);

        List<OverlayBox> vis =
                state.visibleBoxes(DimensionId.OVERWORLD, 0, 0, 0, 1000, 4096).getBoxes();
        assertEquals(Arrays.asList(
                new OverlayBox(5, 0, 0, OverlayColor.GREEN),
                new OverlayBox(15, 0, 0, OverlayColor.GREEN),
                new OverlayBox(30, 0, 0, OverlayColor.GREEN)), vis);
    }

    // ---- visibleBoxes: cap truncation + dropped count ------------------------------------------

    @Test
    void visibleBoxesCapTruncatesAndReportsDropped() {
        List<BlockChange> changes = new ArrayList<BlockChange>();
        for (int i = 0; i < 10; i++) {
            changes.add(BlockChange.add(i, 0, 0, STONE));
        }
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DimensionId.OVERWORLD, Arrays.asList(
                new ChunkDiff(new ChunkPos(0, 0), changes)));
        WorldDiff diff = new WorldDiff(dims, 10, 0, 0);
        OverlayState state = new OverlayState(diff, "a", "b", 0L);

        OverlayState.VisibleBoxes vis =
                state.visibleBoxes(DimensionId.OVERWORLD, 0, 0, 0, 1000, 3);
        assertEquals(3, vis.getBoxes().size());
        assertEquals(7, vis.getDropped());
        // kept boxes are the nearest three
        assertEquals(Arrays.asList(
                new OverlayBox(0, 0, 0, OverlayColor.GREEN),
                new OverlayBox(1, 0, 0, OverlayColor.GREEN),
                new OverlayBox(2, 0, 0, OverlayColor.GREEN)), vis.getBoxes());
    }

    // ---- expiry stamping -----------------------------------------------------------------------

    @Test
    void expiryUsesReceivedAtStamp() {
        OverlayState state = new OverlayState(sampleDiff(), "a", "b", 1000L);
        assertEquals(1000L, state.getReceivedAt());
        assertFalse(state.isExpired(1000L, 60L), "fresh: not expired");
        assertFalse(state.isExpired(1059L, 60L), "within lifetime: not expired");
        assertTrue(state.isExpired(1060L, 60L), "at lifetime: expired");
        assertTrue(state.isExpired(2000L, 60L), "long past: expired");
    }

    @Test
    void zeroLifetimeDisablesExpiry() {
        OverlayState state = new OverlayState(sampleDiff(), "a", "b", 0L);
        assertFalse(state.isExpired(1_000_000L, 0L), "lifetime 0 disables the timer");
    }
}
