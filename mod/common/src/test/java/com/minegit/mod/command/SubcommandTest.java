package com.minegit.mod.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The subcommand catalogue and its permission-level gating seam (issue #60). */
class SubcommandTest {

    @Test
    void literalsAreTheReadSetupTrioInOrder() {
        assertEquals(Arrays.asList("init", "status", "log"), Subcommand.literals());
    }

    @Test
    void readSetupTrioIsAvailableToEveryPlayer() {
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
        assertNull(Subcommand.byLiteral("checkout"));
        assertNull(Subcommand.byLiteral(null));
    }
}
