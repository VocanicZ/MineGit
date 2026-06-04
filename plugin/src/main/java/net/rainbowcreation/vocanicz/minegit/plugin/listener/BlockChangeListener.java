package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.plugin.world.BukkitWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import java.util.Objects;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Bukkit event listener that feeds the per-world {@code DirtyChunkSet} (Spec E task 6). Every
 * block-mutation event marks the owning chunk dirty so that {@code /mg commit}/{@code status}/
 * {@code diff} only scan chunks that actually moved.
 *
 * <p>The {@link ChunkRef} this listener marks carries the {@link net.rainbowcreation.vocanicz.minegit.core.model.DimensionId}
 * derived from the world's {@link World.Environment} via {@link BukkitWorldAdapter#dimensionOf(World)} —
 * the <em>same</em> mapping the adapter uses for {@link BukkitWorldAdapter#dimension()}. If the dimension
 * did not match, the adapter's {@code read()} would reject the chunk and commit would record nothing.
 * The dirty set is selected from the registry by {@code World.getName()}, matching the adapter factory.
 *
 * <p>Handlers run at {@link EventPriority#MONITOR} with {@code ignoreCancelled = true}: marking is an
 * observation after other plugins have decided the event stands, and over-marking is always safe (the
 * engine diffs and skips unchanged chunks) — the only real risk is under-marking, which the periodic
 * full-world rescan ultimately covers anyway.
 */
public final class BlockChangeListener implements Listener {

    private final WorldDirtyRegistry registry;

    public BlockChangeListener(WorldDirtyRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Marks the chunk owning {@code block} dirty, keyed by world name with the environment's dimension. */
    private void mark(Block block) {
        if (block == null) {
            return;
        }
        markAt(block.getWorld(), block.getX(), block.getZ());
    }

    /** Marks the chunk owning block coordinates {@code (x, z)} in {@code world} dirty. */
    private void markAt(World world, int x, int z) {
        if (world == null) {
            return;
        }
        registry.tracker(world.getName()).markDirty(
                new ChunkRef(BukkitWorldAdapter.dimensionOf(world), new ChunkPos(x >> 4, z >> 4)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        // The fluid/dragon-egg moves into the destination block, which is what actually changes.
        mark(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        mark(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        for (org.bukkit.block.BlockState state : event.getBlocks()) {
            markAt(state.getWorld(), state.getX(), state.getZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkPopulate(ChunkPopulateEvent event) {
        // The chunk itself is the unit here — mark it directly from its chunk coords (no >>4 needed).
        World world = event.getWorld();
        if (world == null) {
            return;
        }
        registry.tracker(world.getName()).markDirty(new ChunkRef(
                BukkitWorldAdapter.dimensionOf(world),
                new ChunkPos(event.getChunk().getX(), event.getChunk().getZ())));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        mark(event.getBlock());
        for (Block block : event.getBlocks()) {
            mark(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        mark(event.getBlock());
        for (Block block : event.getBlocks()) {
            mark(block);
        }
    }
}
