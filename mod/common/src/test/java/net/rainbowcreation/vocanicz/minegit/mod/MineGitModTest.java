package net.rainbowcreation.vocanicz.minegit.mod;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.rainbowcreation.vocanicz.minegit.mod.command.ServerCommandRuntime;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The command-registration lifecycle (issue #73). {@code CommandRegistrationEvent} fires on every
 * server start <em>and every {@code /reload}</em>, so the {@link ServerCommandRuntime} — and the
 * single {@code minegit-git} daemon executor it owns — must be built once and reused across firings.
 * Recreating it per firing leaks an {@link java.util.concurrent.ExecutorService} (and eventually a
 * worker thread) on each reload.
 */
class MineGitModTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void reusesOneRuntimeAcrossRepeatedRegistrations() {
        ServerCommandRuntime first = MineGitMod.sharedRuntime();
        assertNotNull(first, "a shared runtime should be created on first use");

        // Simulate repeated CommandRegistrationEvent firings: server start, then several /reloads.
        for (int i = 0; i < 5; i++) {
            MineGitMod.registerCommands(new CommandDispatcher<CommandSourceStack>());
        }

        assertSame(first, MineGitMod.sharedRuntime(),
                "repeated registrations must reuse the one runtime, not leak a new executor per reload");
    }
}
