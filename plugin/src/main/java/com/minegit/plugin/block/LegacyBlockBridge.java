package com.minegit.plugin.block;

import com.minegit.core.mapping.LegacyBlockMapper;
import com.minegit.core.mapping.LegacyBlockMapper.LegacyId;
import com.minegit.core.model.BlockState;
import org.bukkit.block.Block;

/**
 * Legacy (1.8–1.12) block bridge: blocks are numeric {@code (id, meta)} on these servers, so reads
 * and writes go through core's {@link LegacyBlockMapper} (Spec B §3).
 *
 * <p>The id translation lives in the Bukkit-free {@link LegacyBlockTranslator}; this class is the thin
 * Bukkit seam — it pulls the numeric coord off a {@link Block} and pushes one back. Those Bukkit calls
 * ({@code Material.getId()}, {@code Block.getData()}, {@code Block.setTypeIdAndData}) only exist on
 * pre-1.13 servers and are validated in-game, not in unit tests.
 */
public final class LegacyBlockBridge implements BlockBridge {

    private final LegacyBlockMapper mapper;

    public LegacyBlockBridge(LegacyBlockMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @SuppressWarnings("deprecation") // Material.getId()/getData()/setTypeIdAndData are the 1.8 API.
    public BlockState read(Block block) {
        return LegacyBlockTranslator.toState(mapper, block.getType().getId(), block.getData());
    }

    @Override
    @SuppressWarnings("deprecation")
    public void write(Block block, BlockState state) {
        LegacyId coord = LegacyBlockTranslator.toCoord(mapper, state);
        if (coord == null) {
            // A modern-only block that has no pre-1.13 representation; leave the world untouched
            // rather than write a wrong block. (Logged by the caller's apply loop in a later issue.)
            return;
        }
        // applyPhysics=false: bulk apply re-lights/neighbours itself; per-block physics would thrash.
        block.setTypeIdAndData(coord.blockId, (byte) coord.meta, false);
    }
}
