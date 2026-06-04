package net.rainbowcreation.vocanicz.minegit.cli;

import org.junit.jupiter.api.Test;

import net.rainbowcreation.vocanicz.minegit.core.api.MineGitVersion;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void versionLineReportsCoreRelease() {
        // The CLI wraps core; this smoke test proves the application subproject compiles against
        // and depends on :core.
        String line = Main.versionLine();
        assertTrue(line.contains(MineGitVersion.RELEASE), "version line should embed core release");
    }
}
