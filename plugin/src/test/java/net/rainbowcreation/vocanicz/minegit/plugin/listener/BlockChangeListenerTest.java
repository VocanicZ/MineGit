package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.junit.jupiter.api.Test;

/**
 * {@link BlockChangeListener} feeds the per-world dirty tracker on block-mutation events (Spec E
 * task 6). The {@link ChunkRef} it marks must carry the {@link DimensionId} derived from the world's
 * environment (via {@code BukkitWorldAdapter.dimensionOf}) — not the world name — so commit reads it.
 *
 * <p>MockBukkit does not target the 1.8.8 API, so Bukkit types are mocked with Mockito against the
 * real interfaces; only the accessors the listener touches are stubbed.
 */
class BlockChangeListenerTest {

    private static World mockWorld(String name, World.Environment env) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(env);
        return world;
    }

    private static Block mockBlock(World world, int x, int z) {
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(x);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    @Test
    void blockBreakMarksOwningChunkDirty() {
        WorldDirtyRegistry registry = new WorldDirtyRegistry();
        BlockChangeListener listener = new BlockChangeListener(registry);

        World world = mockWorld("world", World.Environment.NORMAL);
        Block block = mockBlock(world, 33, 50); // chunk (2, 3)

        // getBlock() is final in the 1.8.8 API, so the event is constructed real (player unused).
        BlockBreakEvent event = new BlockBreakEvent(block, null);

        listener.onBlockBreak(event);

        Set<ChunkRef> dirty = registry.tracker("world").peekDirty();
        assertEquals(1, dirty.size(), "one chunk should be dirty");
        assertTrue(
                dirty.contains(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(2, 3))),
                "the owning overworld chunk (2,3) should be dirty");
    }

    @Test
    void entityExplodeMarksEachBlockInDifferentChunksInTheNether() {
        WorldDirtyRegistry registry = new WorldDirtyRegistry();
        BlockChangeListener listener = new BlockChangeListener(registry);

        World nether = mockWorld("world_nether", World.Environment.NETHER);
        Block a = mockBlock(nether, 5, 5); // chunk (0, 0)
        Block b = mockBlock(nether, 33, 50); // chunk (2, 3)
        List<Block> blocks = Arrays.asList(a, b);

        // blockList() is final in the 1.8.8 API, so the event is constructed real; entity/location are
        // unused by the listener and the yield is irrelevant.
        EntityExplodeEvent event = new EntityExplodeEvent(null, null, blocks, 0.0f);

        listener.onEntityExplode(event);

        Set<ChunkRef> dirty = registry.tracker("world_nether").peekDirty();
        assertEquals(2, dirty.size(), "both exploded chunks should be dirty");
        assertTrue(
                dirty.contains(new ChunkRef(DimensionId.THE_NETHER, new ChunkPos(0, 0))),
                "chunk (0,0) should be dirty with THE_NETHER dimension");
        assertTrue(
                dirty.contains(new ChunkRef(DimensionId.THE_NETHER, new ChunkPos(2, 3))),
                "chunk (2,3) should be dirty with THE_NETHER dimension");
    }
}
