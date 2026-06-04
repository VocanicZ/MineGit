package com.minegit.mod;

import com.minegit.mod.command.MineGitCommands;
import com.minegit.mod.command.ServerCommandRuntime;
import com.minegit.mod.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared entrypoint, invoked from both loader entrypoints ({@code MineGitFabric#onInitialize} and
 * {@code MineGitNeoForge}'s constructor). Registers the {@code /minegit} command tree (plus the
 * {@code /mg} and {@code /git} aliases) on both loaders via Architectury's command-registration
 * event — the read/setup subcommands {@code init}, {@code status}, {@code log} (Spec D §4, issue #60).
 */
public final class MineGitMod {

    private static final Logger LOGGER = LoggerFactory.getLogger(MineGitInfo.MOD_NAME);

    private MineGitMod() {
    }

    public static void init() {
        // Touch a (relocated) JGit type so launch proves the bundled JGit is on the classpath and
        // links — the scaffold's "JGit present and loadable" acceptance, before any feature uses it.
        LOGGER.info(
                "[{}] initializing on {} — JGit bundled (object-id length {})",
                MineGitInfo.MOD_NAME,
                Platform.loaderName(),
                Constants.OBJECT_ID_STRING_LENGTH);
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> registerCommands(dispatcher));
    }

    static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        MineGitCommands.register(dispatcher, new ServerCommandRuntime());
    }
}
