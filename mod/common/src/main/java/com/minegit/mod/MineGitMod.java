package com.minegit.mod;

import com.minegit.mod.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.eclipse.jgit.lib.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared entrypoint, invoked from both loader entrypoints ({@code MineGitFabric#onInitialize} and
 * {@code MineGitNeoForge}'s constructor). The scaffold registers the {@code /minegit} command tree
 * (plus the {@code /mg} and {@code /git} aliases) as a stub so both loaders prove the command path
 * end-to-end; feature batches replace the stub executor with the real subcommands.
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
        // Primary literal carries the (stub) behaviour; aliases redirect to it.
        String primary = MineGitInfo.commandAliases().get(0);
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(primary)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal(MineGitInfo.MOD_NAME + " ready — engine + JGit loaded (stub)."),
                            false);
                    return 1;
                });
        var registered = dispatcher.register(root);

        for (String alias : MineGitInfo.commandAliases()) {
            if (!alias.equals(primary)) {
                dispatcher.register(Commands.literal(alias).redirect(registered));
            }
        }
    }
}
