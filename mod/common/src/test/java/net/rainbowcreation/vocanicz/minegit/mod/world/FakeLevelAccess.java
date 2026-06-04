package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * In-memory {@link LevelAccess} for unit-testing {@link ModWorldAdapter} without a running server.
 * Stores blocks in a sparse {@code (x,y,z) -> BlockState} map (absent = air) and a fixed set of
 * loaded chunks; mirrors the contract of the real {@code ServerLevelAccess}.
 */
final class FakeLevelAccess implements LevelAccess {

    private final DimensionId dimension;
    private final int minSectionY;
    private final int sectionCount;
    private final Set<ChunkPos> loaded = new LinkedHashSet<ChunkPos>();
    private final Map<Long, BlockState> blocks = new HashMap<Long, BlockState>();

    FakeLevelAccess(DimensionId dimension, int minSectionY, int sectionCount) {
        this.dimension = dimension;
        this.minSectionY = minSectionY;
        this.sectionCount = sectionCount;
    }

    void addLoadedChunk(int cx, int cz) {
        loaded.add(new ChunkPos(cx, cz));
    }

    @Override
    public DimensionId dimension() {
        return dimension;
    }

    @Override
    public int minSectionY() {
        return minSectionY;
    }

    @Override
    public int sectionCount() {
        return sectionCount;
    }

    @Override
    public Set<ChunkPos> loadedChunks() {
        return new LinkedHashSet<ChunkPos>(loaded);
    }

    @Override
    public BlockState getBlock(int x, int y, int z) {
        BlockState s = blocks.get(key(x, y, z));
        return s != null ? s : BlockState.AIR;
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        if (state == null || state.equals(BlockState.AIR)) {
            blocks.remove(key(x, y, z));
        } else {
            blocks.put(key(x, y, z), state);
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | ((long) y & 0xFFF);
    }
}
