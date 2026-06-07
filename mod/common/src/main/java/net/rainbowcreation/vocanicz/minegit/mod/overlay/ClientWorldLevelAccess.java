package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.BlockStateBridge;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/**
 * Read-only {@link LevelAccess} over a live {@link ClientLevel} (SP2 task C1), the client mirror of
 * the server's {@code ServerLevelAccess}. Lives in {@code common} — the MC-aware shared module — so
 * it can name both vanilla {@code ClientLevel} and the core {@code DimensionId}/{@code ChunkPos}/{@code
 * BlockState} types, which are <em>not</em> on the loader subprojects' compile classpath. The
 * per-loader {@code ClientLevelAccessImpl} only supplies the {@code ClientLevel} and constructs this.
 *
 * <p>Reads go {@code ClientLevel.getBlockState} → {@link BlockStateBridge#toCore}; writes throw (the
 * overlay is a pure visualization). {@code minSectionY}/{@code sectionCount}/{@code dimension} use the
 * same {@code LevelHeightAccessor}/{@code dimension().identifier()} idioms {@code ServerLevelAccess}
 * uses. Client-only.
 */
@Environment(EnvType.CLIENT)
public final class ClientWorldLevelAccess implements LevelAccess {

    private final ClientLevel level;
    private final DimensionId dimension;

    public ClientWorldLevelAccess(ClientLevel level) {
        this.level = Objects.requireNonNull(level, "level");
        this.dimension = DimensionMapping.fromKey(level.dimension().identifier().toString());
    }

    /** The core {@link DimensionId} for {@code level} — reused by the chunk-load feed so dims align. */
    public static DimensionId dimensionOf(ClientLevel level) {
        return DimensionMapping.fromKey(level.dimension().identifier().toString());
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
        if (Minecraft.getInstance().player == null) {
            return out; // no player → no anchor for the loaded square
        }
        net.minecraft.world.level.ChunkPos center = Minecraft.getInstance().player.chunkPosition();
        // The client cache exposes no public iterator, so probe a square around the player and keep
        // the chunks that are actually loaded + non-empty. Bound the square to the SMALLER of the
        // render distance and the overlay radius (the box-cull distance) — baselining the full client
        // render distance overflows the bounded HeadBaselineCache and evicts the player's own chunk.
        // Emit CENTER-OUT so the player's own chunk (and immediate neighbours, where edits land) are
        // first in the engine's per-tick seed budget; otherwise a block placed right after toggling
        // the overlay would be baked into a not-yet-frozen HEAD baseline and raise no box.
        int radius = Math.min(
                Math.max(2, Minecraft.getInstance().options.getEffectiveRenderDistance()),
                overlayChunkRadius());
        for (int[] off : net.rainbowcreation.vocanicz.minegit.mod.overlay.live.ChunkRingOrder
                .centerOut(radius)) {
            addIfLoaded(out, center.x + off[0], center.z + off[1]);
        }
        return out;
    }

    /**
     * Chunk radius the overlay baselines around the player — tied to the box cull distance, not the
     * full client render distance, so the baseline working set stays small (and never evicts the
     * player's own vicinity from the bounded HeadBaselineCache). +1 chunk margin past the cull radius.
     */
    static int overlayChunkRadius() {
        double blocks = net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayClientHooks.config()
                .getMaxRenderDistance();
        return Math.max(2, (int) Math.ceil(blocks / 16.0) + 1);
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        return BlockStateBridge.toCore(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        throw new UnsupportedOperationException("client overlay is read-only");
    }

    /** Adds chunk {@code (cx, cz)} to {@code out} iff it is currently loaded and non-empty. */
    private void addIfLoaded(Set<ChunkPos> out, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk != null && !chunk.isEmpty()) {
            out.add(new ChunkPos(cx, cz));
        }
    }
}
