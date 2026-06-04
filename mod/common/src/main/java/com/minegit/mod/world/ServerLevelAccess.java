package com.minegit.mod.world;

import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

/**
 * Modern, reflection-free {@link LevelAccess} over a live {@link ServerLevel} (Spec D §3). Reads go
 * {@code ServerLevel.getBlockState} → {@link BlockStateBridge#toCore}; writes go {@link
 * BlockStateBridge#toMinecraft} → {@code ServerLevel.setBlock}. The 1.21.11 world is already
 * flattened, so there is no legacy block mapper and no reflection.
 *
 * <p><b>Loaded-chunk enumeration.</b> Vanilla exposes no public iterator over loaded chunks, so this
 * first slice derives the loaded set reflection-free from the server's force-loaded chunks plus a
 * view-distance square around each online player, probing each candidate with {@code
 * ServerChunkCache.getChunkNow} (non-null ⇒ loaded). Exhaustive unloaded-chunk capture is deferred
 * (Spec D §3); for the single-player/integrated target this covers the active region.
 */
public final class ServerLevelAccess implements LevelAccess {

    /** {@code setBlock} flags: notify neighbors + resend to clients (vanilla {@code /setblock}). */
    private static final int APPLY_FLAGS = Block.UPDATE_ALL;

    private final ServerLevel level;
    private final DimensionId dimension;

    public ServerLevelAccess(ServerLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        this.dimension = DimensionMapping.fromKey(level.dimension().identifier().toString());
    }

    @Override
    public DimensionId dimension() {
        return dimension;
    }

    @Override
    public int minSectionY() {
        return level.getMinSectionY();
    }

    @Override
    public int sectionCount() {
        return level.getSectionsCount();
    }

    @Override
    public Set<ChunkPos> loadedChunks() {
        Set<ChunkPos> out = new LinkedHashSet<ChunkPos>();
        // Force-loaded chunks (e.g. /forceload, spawn) — packed (x,z) longs.
        for (long packed : level.getChunkSource().getForceLoadedChunks()) {
            net.minecraft.world.level.ChunkPos mc = new net.minecraft.world.level.ChunkPos(packed);
            addIfLoaded(out, mc.x, mc.z);
        }
        // The square around each player at the server view distance.
        int radius = Math.max(2, level.getServer().getPlayerList().getViewDistance());
        for (ServerPlayer player : level.players()) {
            net.minecraft.world.level.ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    addIfLoaded(out, center.x + dx, center.z + dz);
                }
            }
        }
        return out;
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        return BlockStateBridge.toCore(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), BlockStateBridge.toMinecraft(state), APPLY_FLAGS);
    }

    /** Adds chunk {@code (cx, cz)} to {@code out} iff it is currently loaded. */
    private void addIfLoaded(Set<ChunkPos> out, int cx, int cz) {
        if (level.getChunkSource().getChunkNow(cx, cz) != null) {
            out.add(new ChunkPos(cx, cz));
        }
    }
}
