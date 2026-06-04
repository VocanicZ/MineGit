package com.minegit.plugin.block;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.minegit.core.mapping.LegacyBlockMapper;
import com.minegit.plugin.version.ServerVersion;
import org.junit.jupiter.api.Test;

/**
 * Runtime bridge selection (Spec B §3): the version string detected at enable picks the bridge. The
 * 1.13 flattening is the boundary — pre-1.13 servers get the numeric {@link LegacyBlockBridge}, 1.13+
 * the reflection {@link ModernBlockBridge}.
 */
class BlockBridgesTest {

    private final LegacyBlockMapper mapper = new LegacyBlockMapper();

    private BlockBridge bridgeFor(String bukkitVersion) {
        return BlockBridges.forVersion(ServerVersion.parseBukkitVersion(bukkitVersion), mapper);
    }

    @Test
    void selectsLegacyBridgeForSpigot188() {
        assertInstanceOf(LegacyBlockBridge.class, bridgeFor("1.8.8-R0.1-SNAPSHOT"));
    }

    @Test
    void selectsLegacyBridgeForAllPre113Versions() {
        assertInstanceOf(LegacyBlockBridge.class, bridgeFor("1.8-R0.1-SNAPSHOT"));
        assertInstanceOf(LegacyBlockBridge.class, bridgeFor("1.12.2-R0.1-SNAPSHOT"));
    }

    @Test
    void selectsModernBridgeAtTheFlatteningBoundary() {
        assertInstanceOf(ModernBlockBridge.class, bridgeFor("1.13-R0.1-SNAPSHOT"));
    }

    @Test
    void selectsModernBridgeForModernVersions() {
        assertInstanceOf(ModernBlockBridge.class, bridgeFor("1.20.4-R0.1-SNAPSHOT"));
        assertInstanceOf(ModernBlockBridge.class, bridgeFor("1.21-R0.1-SNAPSHOT"));
    }
}
