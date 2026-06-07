package net.rainbowcreation.vocanicz.minegit.mod.command.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The loader-agnostic permission seam: {@code require} delegates to the installed checker. */
class MineGitPermissionsTest {

    @AfterEach
    void reset() {
        MineGitPermissions.resetChecker();
    }

    @Test
    void requireDelegatesNodeAndLevelToTheInstalledCheckerAndReturnsItsVerdict() {
        List<String> seenNodes = new ArrayList<String>();
        List<Integer> seenLevels = new ArrayList<Integer>();
        MineGitPermissions.setChecker((source, node, level) -> {
            seenNodes.add(node);
            seenLevels.add(level);
            return "minegit.use".equals(node);
        });

        // A null source is fine: this fake checker never dereferences it.
        assertTrue(MineGitPermissions.require("minegit.use", 2).test((CommandSourceStack) null));
        assertFalse(MineGitPermissions.require("minegit.admin", 2).test((CommandSourceStack) null));

        assertEquals(java.util.Arrays.asList("minegit.use", "minegit.admin"), seenNodes);
        assertEquals(java.util.Arrays.asList(2, 2), seenLevels);
    }

    @Test
    void resetRestoresADefaultCheckerThatIsNeverNull() {
        MineGitPermissions.setChecker((source, node, level) -> true);
        MineGitPermissions.resetChecker();
        // The default checker must be installed (non-null predicate) so registration never NPEs.
        org.junit.jupiter.api.Assertions.assertNotNull(MineGitPermissions.require("minegit.use", 2));
    }
}
