package com.minegit.mod.command;

import com.minegit.mod.MineGitInfo;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionCheck;

/**
 * Builds and registers the {@code /minegit} Brigadier tree — primary literal plus the {@code /mg}
 * and {@code /git} aliases — on both loaders (Spec D §4, issue #60). The tree is assembled from the
 * loader-agnostic {@link Subcommand} catalogue, so subcommands, tab-completion (Brigadier suggests
 * the literal children) and the permission-level gating seam all derive from one source of truth.
 *
 * <p>The Minecraft-touching execution is delegated to a {@link Runtime}, keeping this builder free of
 * world/server resolution so the registered tree's <em>shape</em> (subcommands present, aliases
 * redirecting, every subcommand gated) is unit-testable without a live server.
 */
public final class MineGitCommands {

    private MineGitCommands() {
    }

    /**
     * The execution seam. One method per subcommand; the live implementation
     * ({@code ServerCommandRuntime}) resolves the player's level and repo and messages back, while
     * tests pass a no-op. Methods may throw {@link CommandSyntaxException} (e.g. console-without-player).
     */
    public interface Runtime {
        int init(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        int commit(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        int log(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;
    }

    /** Brigadier argument name carrying the {@code /mg commit -m <message>} text. */
    static final String MESSAGE_ARG = "message";

    /** Message used when {@code /mg commit} is run with no {@code -m} text. */
    public static final String DEFAULT_COMMIT_MESSAGE = "Update world";

    /**
     * The commit message for {@code ctx}: the greedy {@code -m} argument when present, else {@link
     * #DEFAULT_COMMIT_MESSAGE}. The bare {@code /mg commit} form parses without the argument, so the
     * lookup falls back rather than throwing.
     */
    public static String messageOf(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        try {
            return com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, MESSAGE_ARG);
        } catch (IllegalArgumentException noArg) {
            return DEFAULT_COMMIT_MESSAGE;
        }
    }

    /** Registers {@code /minegit} (+ aliases) on {@code dispatcher}, delegating to {@code runtime}. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Runtime runtime) {
        String primary = MineGitInfo.commandAliases().get(0);
        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(build(primary, runtime));
        for (String alias : MineGitInfo.commandAliases()) {
            if (!alias.equals(primary)) {
                dispatcher.register(Commands.literal(alias).redirect(root));
            }
        }
    }

    /** Assembles the {@code /minegit} literal tree under {@code literal}, delegating to {@code runtime}. */
    static LiteralArgumentBuilder<CommandSourceStack> build(String literal, Runtime runtime) {
        return Commands.literal(literal)
                .executes(MineGitCommands::usage)
                .then(subcommand(Subcommand.INIT, runtime::init))
                .then(subcommand(Subcommand.STATUS, runtime::status))
                .then(commitSubcommand(runtime))
                .then(subcommand(Subcommand.LOG, runtime::log));
    }

    /**
     * The {@code commit} literal: gated like any subcommand, runnable bare ({@link
     * #DEFAULT_COMMIT_MESSAGE}) and as {@code commit -m <message...>} where {@code message} greedily
     * captures the rest of the line (Spec D §4). Both forms dispatch to {@code runtime.commit}; the
     * runtime reads the text via {@link #messageOf}.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> commitSubcommand(Runtime runtime) {
        return Commands.literal(Subcommand.COMMIT.literal())
                .requires(Commands.hasPermission(permissionCheck(Subcommand.COMMIT.permissionLevel())))
                .executes(runtime::commit)
                .then(Commands.literal("-m")
                        .then(Commands.argument(MESSAGE_ARG,
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(runtime::commit)));
    }

    /** A gated subcommand literal: {@code requires(hasPermission(level))} + the execution action. */
    private static LiteralArgumentBuilder<CommandSourceStack> subcommand(
            Subcommand sub, Command<CommandSourceStack> action) {
        return Commands.literal(sub.literal())
                .requires(Commands.hasPermission(permissionCheck(sub.permissionLevel())))
                .executes(action);
    }

    /**
     * Maps a {@link Subcommand} permission level to the 1.21.11 {@link PermissionCheck} the
     * Brigadier {@code requires} gate gets. Level 0 is any player ({@code LEVEL_ALL}); the mutating
     * commands' level 2 ({@link Subcommand#OP_PERMISSION_LEVEL}) is vanilla op
     * ({@code LEVEL_GAMEMASTERS}). Other levels are unused by MineGit and rejected loudly.
     */
    private static PermissionCheck permissionCheck(int level) {
        switch (level) {
            case 0:
                return Commands.LEVEL_ALL;
            case Subcommand.OP_PERMISSION_LEVEL:
                return Commands.LEVEL_GAMEMASTERS;
            default:
                throw new IllegalArgumentException("unsupported MineGit permission level: " + level);
        }
    }

    /** Bare {@code /mg}: print the available subcommands. */
    private static int usage(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(
                () -> Component.literal(MineGitInfo.MOD_NAME + " — usage: /mg <"
                        + String.join("|", Subcommand.literals()) + ">").withStyle(ChatFormatting.GOLD),
                false);
        return 1;
    }
}
