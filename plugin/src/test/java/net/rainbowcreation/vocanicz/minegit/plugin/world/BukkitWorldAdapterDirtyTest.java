package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.plugin.block.BlockBridge;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/**
 * {@link BukkitWorldAdapter} dirty-set integration: when a {@link DirtyChunkSet} is injected,
 * {@link BukkitWorldAdapter#peekDirty()} reflects the dirty set without clearing it, and
 * {@link BukkitWorldAdapter#drainDirty()} clears it (Spec E task 5).
 *
 * <p>MockBukkit does not target the 1.8.8 API, so the Bukkit {@link World} is mocked with Mockito
 * against the real interface; only the height/environment/name accessors the adapter touches in its
 * constructor are stubbed.
 */
class BukkitWorldAdapterDirtyTest {

    /** An in-memory bridge — unused here (no reads/writes), but the adapter requires a non-null bridge. */
    private static final class MapBridge implements BlockBridge {
        final Map<String, BlockState> blocks = new HashMap<String, BlockState>();

        @Override
        public BlockState read(Block block) {
            return BlockState.AIR;
        }

        @Override
        public void write(Block block, BlockState state) {
            blocks.put(block.getX() + "," + block.getY() + "," + block.getZ(), state);
        }
    }

    private static World mockWorld() {
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        lenient().when(world.getMaxHeight()).thenReturn(16);
        return world;
    }

    @Test
    void peekDirtyReflectsDirtySetWithoutClearingThenDrainClears() {
        DirtyChunkSet dirty = new DirtyChunkSet();
        BukkitWorldAdapter adapter = new BukkitWorldAdapter(mockWorld(), new MapBridge(), dirty);

        ChunkRef ref = new ChunkRef(adapter.dimension(), new ChunkPos(3, 7));
        dirty.markDirty(ref);

        // peek twice — both should see size 1 (non-clearing)
        Set<ChunkRef> peek1 = adapter.peekDirty();
        assertEquals(1, peek1.size(), "peekDirty should return one dirty chunk");
        assertTrue(peek1.contains(ref));

        Set<ChunkRef> peek2 = adapter.peekDirty();
        assertEquals(1, peek2.size(), "peekDirty should still return one dirty chunk on second call");

        // drain returns the one dirty chunk and clears it
        Set<ChunkRef> drained = adapter.drainDirty();
        assertEquals(1, drained.size(), "drainDirty should return one dirty chunk");
        assertTrue(drained.contains(ref));

        // after drain, peek should be empty
        assertTrue(adapter.peekDirty().isEmpty(), "peekDirty should be empty after drainDirty");
    }
}
