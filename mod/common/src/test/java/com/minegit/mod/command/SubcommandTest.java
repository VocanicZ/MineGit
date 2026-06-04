package com.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The subcommand catalogue and its permission-level gating seam (issue #60). */
class SubcommandTest {

    @Test
    void literalsAreTheReadSetupCommitSetInOrder() {
        assertEquals(Arrays.asList("init", "status", "commit", "log"), Subcommand.literals());
    }

    @Test
    void readSetupAndCommitAreAvailableToEveryPlayer() {
        // Spec D §4: read and commit are ungated (level 0); only checkout will gate at op.
        for (Subcommand sub : Subcommand.values()) {
            assertEquals(0, sub.permissionLevel(), sub + " should be ungated in this slice");
        }
    }

    @Test
    void opSeamIsVanillaLevelTwoForLaterMutatingCommands() {
        assertEquals(2, Subcommand.OP_PERMISSION_LEVEL);
    }

    @Test
    void byLiteralIsCaseInsensitiveAndNullForUnknown() {
        assertSame(Subcommand.STATUS, Subcommand.byLiteral("status"));
        assertSame(Subcommand.STATUS, Subcommand.byLiteral("STATUS"));
        assertSame(Subcommand.INIT, Subcommand.byLiteral("init"));
        assertSame(Subcommand.COMMIT, Subcommand.byLiteral("commit"));
        assertNull(Subcommand.byLiteral("checkout"));
        assertNull(Subcommand.byLiteral(null));
    }
}
