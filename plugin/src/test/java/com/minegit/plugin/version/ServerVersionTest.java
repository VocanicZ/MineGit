package com.minegit.plugin.version;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Version detection is the one piece of the scaffold with real logic, and it is the seam that later
 * BlockBridge selection (Spec B §3) keys off. It must parse the {@code Bukkit.getBukkitVersion()}
 * string without touching any Bukkit class, so it stays unit-testable on a plain JVM.
 */
class ServerVersionTest {

    @Test
    void parsesSpigot188SnapshotAsLegacy() {
        ServerVersion v = ServerVersion.parseBukkitVersion("1.8.8-R0.1-SNAPSHOT");
        assertEquals(1, v.major());
        assertEquals(8, v.minor());
        assertEquals(8, v.patch());
        assertTrue(v.isLegacy(), "1.8.8 predates the 1.13 flattening");
    }

    @Test
    void parsesVersionWithoutPatchAsZero() {
        ServerVersion v = ServerVersion.parseBukkitVersion("1.13-R0.1-SNAPSHOT");
        assertEquals(1, v.major());
        assertEquals(13, v.minor());
        assertEquals(0, v.patch());
        assertFalse(v.isLegacy(), "1.13 is the flattening boundary and is modern");
    }

    @Test
    void treatsPre113AsLegacy() {
        assertTrue(ServerVersion.parseBukkitVersion("1.12.2-R0.1-SNAPSHOT").isLegacy());
        assertTrue(ServerVersion.parseBukkitVersion("1.8-R0.1-SNAPSHOT").isLegacy());
    }

    @Test
    void treats113AndLaterAsModern() {
        assertFalse(ServerVersion.parseBukkitVersion("1.20.4-R0.1-SNAPSHOT").isLegacy());
        assertFalse(ServerVersion.parseBukkitVersion("1.21-R0.1-SNAPSHOT").isLegacy());
    }

    @Test
    void roundTripsToString() {
        assertEquals("1.20.4", ServerVersion.parseBukkitVersion("1.20.4-R0.1-SNAPSHOT").toString());
        assertEquals("1.8.8", ServerVersion.parseBukkitVersion("1.8.8-R0.1-SNAPSHOT").toString());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> ServerVersion.parseBukkitVersion("not-a-version"));
        assertThrows(IllegalArgumentException.class, () -> ServerVersion.parseBukkitVersion(""));
        assertThrows(IllegalArgumentException.class, () -> ServerVersion.parseBukkitVersion(null));
    }
}
