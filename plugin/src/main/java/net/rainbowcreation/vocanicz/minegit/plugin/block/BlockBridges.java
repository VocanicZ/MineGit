package net.rainbowcreation.vocanicz.minegit.plugin.block;

import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper;
import net.rainbowcreation.vocanicz.minegit.plugin.version.ServerVersion;

/**
 * Selects the {@link BlockBridge} implementation for the running server (Spec B §3).
 *
 * <p>The choice is made once at enable from the {@link ServerVersion} detected from
 * {@code Bukkit.getBukkitVersion()}: pre-1.13 ({@link ServerVersion#isLegacy() legacy}) servers use
 * the numeric {@link LegacyBlockBridge}; 1.13+ servers use the reflection-based
 * {@link ModernBlockBridge}, which bypasses the legacy mapper. Constructing either is cheap — the
 * modern bridge defers its reflection to first use — so this can run off-server in tests.
 */
public final class BlockBridges {

    private BlockBridges() {}

    /**
     * @param version the detected server version
     * @param legacyMapper core's numeric id&harr;state table, used only by the legacy bridge
     * @return a {@link LegacyBlockBridge} for pre-1.13 servers, else a {@link ModernBlockBridge}
     */
    public static BlockBridge forVersion(ServerVersion version, LegacyBlockMapper legacyMapper) {
        if (version.isLegacy()) {
            return new LegacyBlockBridge(legacyMapper);
        }
        return new ModernBlockBridge();
    }
}
