package net.rainbowcreation.vocanicz.minegit.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MineGitInfoTest {

    @Test
    void modIdIsMinegit() {
        assertEquals("minegit", MineGitInfo.MOD_ID);
    }

    @Test
    void commandAliasesArePrimaryFirstThenShortcuts() {
        assertEquals(java.util.Arrays.asList("minegit", "mg", "git"), MineGitInfo.commandAliases());
    }

    @Test
    void commandAliasesAreImmutable() {
        assertTrue(
                org.junit.jupiter.api.Assertions.assertThrows(
                                UnsupportedOperationException.class,
                                () -> MineGitInfo.commandAliases().add("nope"))
                        != null);
    }
}
