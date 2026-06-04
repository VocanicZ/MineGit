package com.minegit.mod.world;

import com.minegit.core.model.BlockState;
import com.minegit.core.model.ChunkPos;
import com.minegit.core.model.DimensionId;
import java.util.Set;

/**
 * Narrow seam over a single bound {@code ServerLevel}, in <strong>core</strong> types only (no
 * Minecraft in the signatures). It isolates the version-agnostic {@link ModWorldAdapter} logic from
 * the Minecraft-touching {@code ServerLevelAccess}, so the adapter is unit-testable against a pure
 * {@code FakeLevelAccess}.
 *
 * <p>All methods are expected to run on the server thread; the scheduler owns the thread hop.
 */
public interface LevelAccess {

    /** The dimension the bound level maps to (overworld / the_nether / the_end / custom). */
    DimensionId dimension();

    /** Section-Y of the lowest build section ({@code floorDiv(minBuildHeight, 16)}). */
    int minSectionY();

    /** Number of vertical 16-block sections in the build range. */
    int sectionCount();

    /** The chunks currently loaded in the level. */
    Set<ChunkPos> loadedChunks();

    /** The core block state at world {@code (x, y, z)}; {@link BlockState#AIR} if absent/air. */
    BlockState getBlock(int x, int y, int z);

    /** Sets world {@code (x, y, z)} to {@code state} (air clears the block). */
    void setBlock(int x, int y, int z, BlockState state);
}
