package net.rainbowcreation.vocanicz.minegit.plugin.block;

import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper;
import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper.LegacyId;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;

/**
 * Pure, Bukkit-free bridge between numeric legacy {@code (id, meta)} coords and core
 * {@link BlockState}s, layered on core's {@link LegacyBlockMapper}.
 *
 * <p>{@link LegacyBlockBridge} owns the Bukkit {@code Block} plumbing (reading
 * {@code Material.getId()}/{@code Block.getData()} and calling {@code Block.setTypeIdAndData}); it
 * delegates the actual id translation here so the round-trip logic stays unit-testable without a
 * server (Spec B §3).
 *
 * <p>The read direction is a straight delegate to the mapper. The write direction adds one thing the
 * raw mapper lacks: when a coord is missing from the common-blocks table the mapper flattens it to a
 * flagged {@code minegit:unknown} state carrying {@code legacy_id}/{@code legacy_meta} properties — so
 * here we recover the original coord from those properties, making unrecognised blocks survive a
 * commit&rarr;checkout round-trip losslessly instead of being dropped.
 */
public final class LegacyBlockTranslator {

    private LegacyBlockTranslator() {}

    /** Read side: legacy {@code (id, meta)} to its flattened {@link BlockState}. */
    public static BlockState toState(LegacyBlockMapper mapper, int blockId, int meta) {
        return mapper.map(blockId, meta);
    }

    /**
     * Write side: the legacy coord that produces {@code state}, or {@code null} if the state cannot be
     * expressed on a pre-1.13 server (a genuinely modern-only block).
     *
     * <p>Resolution order: the {@code minegit:unknown} fallback's embedded {@code legacy_id}/
     * {@code legacy_meta} take precedence (exact recovery), then the mapper's reverse table.
     */
    public static LegacyId toCoord(LegacyBlockMapper mapper, BlockState state) {
        if (LegacyBlockMapper.UNKNOWN_ID.equals(state.getId())) {
            String id = state.getProps().get("legacy_id");
            String meta = state.getProps().get("legacy_meta");
            if (id != null && meta != null) {
                try {
                    return new LegacyId(Integer.parseInt(id), Integer.parseInt(meta));
                } catch (NumberFormatException ignored) {
                    // Corrupt fallback props; fall through to the reverse table.
                }
            }
        }
        return mapper.reverse(state);
    }
}
