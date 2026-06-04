package net.rainbowcreation.vocanicz.minegit.core.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineGitVersionTest {

    @Test
    void exposesMgrfFormatVersion() {
        assertEquals(1, MineGitVersion.MGRF_FORMAT_VERSION);
    }

    @Test
    void exposesNonBlankReleaseString() {
        assertTrue(MineGitVersion.RELEASE != null && !MineGitVersion.RELEASE.trim().isEmpty());
    }
}
