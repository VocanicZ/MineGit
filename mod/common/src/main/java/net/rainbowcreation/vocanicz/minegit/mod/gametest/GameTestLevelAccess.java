package net.rainbowcreation.vocanicz.minegit.mod.gametest;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.BlockStateBridge;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * A {@link LevelAccess} over a live {@link ServerLevel} scoped to a <strong>fixed, explicit chunk
 * set</strong> — the seam the headless GameTests drive (Spec D §6, issue #64).
 *
 * <p>It is the real {@code ServerLevelAccess} reads/writes ({@code getBlockState} →
 * {@link BlockStateBridge#toCore}, {@link BlockStateBridge#toMinecraft} → {@code setBlock}) but with
 * a deterministic loaded-chunk set instead of the player/force-load derivation: a GameTest world has
 * no players, so the live enumeration would be empty. Pinning the captured chunks to the test
 * structure's own chunks keeps each test isolated from its neighbours in the shared GameTest level
 * and proves the genuine block I/O end to end.
 */
public final class GameTestLevelAccess implements LevelAccess {

    /** {@code setBlock} flags: notify neighbors + resend to clients, matching {@code ServerLevelAccess}. */
    private static final int APPLY_FLAGS = Block.UPDATE_ALL;

    private final ServerLevel level;
    private final DimensionId dimension;
    private final Set<ChunkPos> chunks;

    public GameTestLevelAccess(ServerLevel level, Set<ChunkPos> chunks) {
        this.level = Objects.requireNonNull(level, "level");
        this.dimension = DimensionMapping.fromKey(level.dimension().identifier().toString());
        this.chunks = new LinkedHashSet<ChunkPos>(Objects.requireNonNull(chunks, "chunks"));
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
        return Collections.unmodifiableSet(chunks);
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        return BlockStateBridge.toCore(level.getBlockState(new BlockPos(x, y, z)));
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        level.setBlock(new BlockPos(x, y, z), BlockStateBridge.toMinecraft(state), APPLY_FLAGS);
    }
}
