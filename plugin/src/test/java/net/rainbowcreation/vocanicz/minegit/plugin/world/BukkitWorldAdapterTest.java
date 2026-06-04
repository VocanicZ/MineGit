package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedChunk;
import net.rainbowcreation.vocanicz.minegit.core.model.NormalizedSection;
import net.rainbowcreation.vocanicz.minegit.plugin.block.BlockBridge;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * The Bukkit {@link net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter} reads loaded chunks through the
 * {@link BlockBridge} into the engine's normalized model and applies block changes back (Spec B §4).
 *
 * <p>MockBukkit does not target the 1.8.8 API, so the Bukkit seam — {@link World}/{@link Chunk}/
 * {@link Block} — is mocked with Mockito against the real 1.8.8 interfaces, and an in-memory
 * {@link BlockBridge} translates blocks to/from {@link BlockState} by world coordinate.
 */
class BukkitWorldAdapterTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");

    /** An in-memory bridge keyed by world coordinate, standing in for the real cross-version I/O. */
    private static final class MapBridge implements BlockBridge {
        final Map<String, BlockState> blocks = new HashMap<String, BlockState>();

        static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        @Override
        public BlockState read(Block block) {
            BlockState s = blocks.get(key(block.getX(), block.getY(), block.getZ()));
            return s != null ? s : BlockState.AIR;
        }

        @Override
        public void write(Block block, BlockState state) {
            blocks.put(key(block.getX(), block.getY(), block.getZ()), state);
        }
    }

    /** A 1.8-style mocked world: height [0,16) (one section), overworld, one loaded chunk at (cx,cz). */
    private static World mockWorld(int height, MapBridge bridge, int cx, int cz) {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        lenient().when(world.getMaxHeight()).thenReturn(height);

        Chunk chunk = mock(Chunk.class);
        lenient().when(chunk.getX()).thenReturn(cx);
        lenient().when(chunk.getZ()).thenReturn(cz);
        lenient().when(chunk.getWorld()).thenReturn(world);
        // chunk.getBlock(localX, y, localZ): build a per-coordinate Block mock so the bridge can key it.
        lenient().when(chunk.getBlock(anyInt(), anyInt(), anyInt())).thenAnswer(inv -> {
            int lx = inv.getArgument(0);
            int y = inv.getArgument(1);
            int lz = inv.getArgument(2);
            return blockAt(cx * 16 + lx, y, cz * 16 + lz);
        });

        lenient().when(world.getChunkAt(cx, cz)).thenReturn(chunk);
        lenient().when(world.getLoadedChunks()).thenReturn(new Chunk[] {chunk});
        // world.getBlockAt(x,y,z) for apply()/writeChunk(): a per-coordinate Block mock.
        lenient().when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(inv ->
                blockAt(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        return world;
    }

    private static Block blockAt(int x, int y, int z) {
        Block b = mock(Block.class);
        lenient().when(b.getX()).thenReturn(x);
        lenient().when(b.getY()).thenReturn(y);
        lenient().when(b.getZ()).thenReturn(z);
        return b;
    }

    @Test
    void dimensionsReflectsTheBoundWorldEnvironment() {
        MapBridge bridge = new MapBridge();
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 0, 0), bridge);

        assertEquals(java.util.Collections.singleton(DimensionId.OVERWORLD), adapter.dimensions());
    }

    @Test
    void allChunksReturnsTheLoadedChunks() {
        MapBridge bridge = new MapBridge();
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 2, -3), bridge);

        Set<ChunkRef> all = adapter.allChunks();

        assertEquals(
                java.util.Collections.singleton(
                        new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(2, -3))),
                all);
    }

    @Test
    void readBuildsNormalizedChunkFromTheBridge() {
        MapBridge bridge = new MapBridge();
        // Build a single stone block at world (1,2,3) in chunk (0,0).
        bridge.blocks.put(MapBridge.key(1, 2, 3), STONE);
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 0, 0), bridge);

        NormalizedChunk chunk = adapter.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));

        assertEquals(0, chunk.getCx());
        assertEquals(0, chunk.getCz());
        assertEquals(0, chunk.getMinSection());
        assertEquals(1, chunk.getSections().length);
        NormalizedSection section = chunk.getSections()[0];
        int idx = 2 * 256 + 3 * 16 + 1; // Y*256 + Z*16 + X
        assertEquals(STONE, section.getPalette().get(section.getIndices()[idx]));
        // A neighbouring block is air.
        int airIdx = 0;
        assertEquals(BlockState.AIR, section.getPalette().get(section.getIndices()[airIdx]));
    }

    @Test
    void readReturnsNullForAnUnloadedChunk() {
        MapBridge bridge = new MapBridge();
        World world = mockWorld(16, bridge, 0, 0);
        when(world.getChunkAt(5, 5)).thenReturn(null);
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(world, bridge);

        assertNull(adapter.read(DimensionId.OVERWORLD, new ChunkPos(5, 5)));
    }

    @Test
    void readReturnsNullForAnEmptyAllAirSection() {
        MapBridge bridge = new MapBridge();
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 0, 0), bridge);

        NormalizedChunk chunk = adapter.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));

        assertNull(chunk.getSections()[0]);
    }

    @Test
    void applyWritesEachChangeBackThroughTheBridge() {
        MapBridge bridge = new MapBridge();
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 0, 0), bridge);

        List<BlockChange> changes = Arrays.asList(
                BlockChange.add(1, 2, 3, STONE),
                BlockChange.remove(4, 5, 6, STONE));
        adapter.apply(DimensionId.OVERWORLD, new ChunkPos(0, 0), changes);

        assertEquals(STONE, bridge.blocks.get(MapBridge.key(1, 2, 3)));
        // REMOVE applies AIR.
        assertEquals(BlockState.AIR, bridge.blocks.get(MapBridge.key(4, 5, 6)));
    }

    @Test
    void readRoundTripsThroughApply() {
        MapBridge bridge = new MapBridge();
        bridge.blocks.put(MapBridge.key(7, 8, 9), STONE);
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(16, bridge, 0, 0), bridge);

        NormalizedChunk read = adapter.read(DimensionId.OVERWORLD, new ChunkPos(0, 0));

        // Re-materialize via writeChunk into a fresh world; the stone must reappear.
        MapBridge target = new MapBridge();
        BukkitWorldAdapter targetAdapter =
                new BukkitWorldAdapter(mockWorld(16, target, 0, 0), target);
        targetAdapter.writeChunk(DimensionId.OVERWORLD, read);

        assertEquals(STONE, target.blocks.get(MapBridge.key(7, 8, 9)));
        // Air blocks are not written.
        assertTrue(target.blocks.size() == 1);
    }
}
