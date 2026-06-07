package net.rainbowcreation.vocanicz.minegit.plugin.net;

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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip test for {@link DiffPush}: proves that encoding a {@link WorldDiff} and splitting it
 * into wire frames (via {@link DiffPush#frames}) can be reassembled and decoded back to an equal
 * {@link WorldDiff}. Pure/headless — no Bukkit runtime needed.
 */
class DiffPushRoundTripTest {

    // ---- test cases ---------------------------------------------------------------------------

    @Test
    void smallDiffRoundTrips() {
        assertRoundTrip(smallDiff());
    }

    @Test
    void largeDiffMultiFrameRoundTrips() {
        WorldDiff d = largeDiff();
        List<byte[]> frames = DiffPush.frames(d);
        assertTrue(frames.size() > 1,
                "largeDiff should produce >1 frame but got " + frames.size()
                        + " (encoded size may be <= " + Framing.DEFAULT_MAX_FRAME_BYTES + " bytes)");
        assertRoundTrip(d);
    }

    @Test
    void emptyDiffRoundTrips() {
        assertRoundTrip(emptyDiff());
    }

    // ---- round-trip helper --------------------------------------------------------------------

    private static void assertRoundTrip(WorldDiff diff) {
        List<byte[]> frameBytes = DiffPush.frames(diff);
        Reassembler r = new Reassembler();
        WorldDiff out = null;
        for (byte[] fb : frameBytes) {
            Frame frame = Frame.fromBytes(fb);
            Optional<byte[]> result = r.add(frame);
            if (result.isPresent()) {
                out = DiffPayload.decode(result.get());
            }
        }
        assertNotNull(out, "Reassembler never completed (frames never satisfied total)");
        assertEquals(diff, out);
    }

    // ---- diff factories -----------------------------------------------------------------------

    /**
     * A small diff: one dimension, one chunk, three distinct change kinds — enough to exercise the
     * full palette + all three BlockChange.Kind paths in under 30 KB (single frame).
     */
    private static WorldDiff smallDiff() {
        DimensionId overworld = DimensionId.OVERWORLD;
        ChunkPos pos = new ChunkPos(0, 0);

        List<BlockChange> changes = new ArrayList<BlockChange>();
        changes.add(BlockChange.add(1, 64, 1, new BlockState("minecraft:stone")));
        changes.add(BlockChange.remove(2, 64, 2, new BlockState("minecraft:dirt")));
        changes.add(BlockChange.change(3, 64, 3,
                new BlockState("minecraft:grass_block"),
                new BlockState("minecraft:sand")));

        List<ChunkDiff> chunks = new ArrayList<ChunkDiff>();
        chunks.add(new ChunkDiff(pos, changes));

        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(overworld, chunks);
        return new WorldDiff(dims, 1, 1, 1);
    }

    /**
     * An empty diff: zero changes across zero dimensions. Produces a single (minimal) frame.
     */
    private static WorldDiff emptyDiff() {
        return new WorldDiff(Collections.<DimensionId, List<ChunkDiff>>emptyMap(), 0, 0, 0);
    }

    /**
     * A large diff whose encoded payload reliably exceeds {@link Framing#DEFAULT_MAX_FRAME_BYTES}
     * (30 000 bytes). Strategy: 600 changes per chunk × many chunks = many block-id strings in the
     * payload, each carrying a unique long block-id. 50 chunks × 600 changes = 30 000 entries;
     * each entry contributes at least ~40 bytes (two unique palette strings ~20 chars each +
     * headers), giving well over 30 KB total.
     */
    private static WorldDiff largeDiff() {
        DimensionId overworld = DimensionId.OVERWORLD;
        int chunksPerDim = 50;
        int changesPerChunk = 600;

        List<ChunkDiff> chunks = new ArrayList<ChunkDiff>(chunksPerDim);
        int totalAdded = 0;
        int changeIndex = 0;
        for (int ci = 0; ci < chunksPerDim; ci++) {
            ChunkPos pos = new ChunkPos(ci, 0);
            List<BlockChange> changes = new ArrayList<BlockChange>(changesPerChunk);
            for (int i = 0; i < changesPerChunk; i++) {
                // Unique block id per change forces the palette to carry every string in full.
                String id = "minecraft:generated_block_" + changeIndex;
                int localX = i % 16;
                int localZ = (i / 16) % 16;
                int y = 64 + (i / 256);
                int worldX = ci * 16 + localX;
                int worldZ = localZ;
                changes.add(BlockChange.add(worldX, y, worldZ, new BlockState(id)));
                totalAdded++;
                changeIndex++;
            }
            chunks.add(new ChunkDiff(pos, changes));
        }

        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(overworld, chunks);
        return new WorldDiff(dims, totalAdded, 0, 0);
    }
}
