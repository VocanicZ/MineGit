package net.rainbowcreation.vocanicz.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The subcommand catalogue and its permission-level gating seam (issue #60). */
class SubcommandTest {

    @Test
    void literalsAreTheReadSetupCommitDiffCheckoutRescanSetInOrder() {
        assertEquals(Arrays.asList("init", "status", "commit", "log", "diff", "checkout", "rescan"),
                Subcommand.literals());
    }

    @Test
    void everySubcommandFallsBackToVanillaOpLevelTwo() {
        // Locked-by-default model: no command is open to non-ops; the op fallback is vanilla op (2).
        for (Subcommand sub : Subcommand.values()) {
            assertEquals(Subcommand.OP_PERMISSION_LEVEL, sub.permissionLevel(),
                    sub + " should fall back to vanilla op level " + Subcommand.OP_PERMISSION_LEVEL);
        }
    }

    @Test
    void checkoutIsTheOnlyAdminNodeEverythingElseIsUse() {
        for (Subcommand sub : Subcommand.values()) {
            String expected = sub == Subcommand.CHECKOUT ? "minegit.admin" : "minegit.use";
            assertEquals(expected, sub.node(), sub + " should map to " + expected);
        }
    }

    @Test
    void opSeamIsVanillaLevelTwo() {
        assertEquals(2, Subcommand.OP_PERMISSION_LEVEL);
    }

    @Test
    void byLiteralIsCaseInsensitiveAndNullForUnknown() {
        assertSame(Subcommand.STATUS, Subcommand.byLiteral("status"));
        assertSame(Subcommand.STATUS, Subcommand.byLiteral("STATUS"));
        assertSame(Subcommand.INIT, Subcommand.byLiteral("init"));
        assertSame(Subcommand.COMMIT, Subcommand.byLiteral("commit"));
        assertSame(Subcommand.CHECKOUT, Subcommand.byLiteral("checkout"));
        assertSame(Subcommand.CHECKOUT, Subcommand.byLiteral("CHECKOUT"));
        assertNull(Subcommand.byLiteral("rebase"));
        assertNull(Subcommand.byLiteral(null));
    }
}
