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
    void readSetupAndCommitAreAvailableToEveryPlayerButCheckoutAndRescanGateAtOp() {
        // Spec D §4: read and commit are ungated (level 0); the world-mutating checkout and rescan gate at op.
        for (Subcommand sub : Subcommand.values()) {
            if (sub == Subcommand.CHECKOUT || sub == Subcommand.RESCAN) {
                assertEquals(Subcommand.OP_PERMISSION_LEVEL, sub.permissionLevel(),
                        sub + " mutates world/tracking state — gate at op level " + Subcommand.OP_PERMISSION_LEVEL);
            } else {
                assertEquals(0, sub.permissionLevel(), sub + " should be ungated");
            }
        }
    }

    @Test
    void opSeamIsVanillaLevelTwoForTheMutatingCheckout() {
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
