package com.minegit.plugin.block;

import com.minegit.core.model.BlockState;
import org.bukkit.block.Block;

/**
 * Cross-version block I/O: translate between a live Bukkit {@link Block} and the version-agnostic
 * core {@link BlockState} (Spec B §3).
 *
 * <p>The 1.13 "flattening" changed block representation, and {@code BlockData} is absent from the
 * 1.8.8 compile classpath, so there are two implementations selected once at enable from the detected
 * server version:
 *
 * <ul>
 *   <li>{@link LegacyBlockBridge} (1.8–1.12): direct {@code Material.getId()}+{@code Block.getData()}
 *       via core's {@code LegacyBlockMapper}.
 *   <li>{@link ModernBlockBridge} (1.13+): reflection over {@code Block.getBlockData().getAsString()}
 *       and {@code Server.createBlockData(String)}, bypassing the legacy mapper.
 * </ul>
 *
 * <p>All calls run on the server main thread (Spec B §6).
 */
public interface BlockBridge {

    /** Read the block at its current position into a normalized {@link BlockState}. */
    BlockState read(Block block);

    /** Apply {@code state} to {@code block} in the live world. */
    void write(Block block, BlockState state);
}
