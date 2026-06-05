package net.rainbowcreation.vocanicz.minegit.mod.command;

import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import java.util.concurrent.CompletableFuture;
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

        int diff(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        int checkout(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        int rescan(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx)
                throws CommandSyntaxException;

        /**
         * Tab-completion for a ref argument ({@code checkout <ref>}, {@code diff <refA> <refB>}):
         * suggests {@code HEAD}/{@code HEAD~N} aliases, branch names, and recent commit short-hashes
         * (commit message as tooltip). Must never throw — a repo hiccup yields no suggestions.
         */
        CompletableFuture<Suggestions> suggestRefs(
                com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                SuggestionsBuilder builder);
    }

    /** Brigadier argument name carrying the {@code /mg commit -m <message>} text. */
    static final String MESSAGE_ARG = "message";

    /** Brigadier argument name carrying the {@code /mg checkout <ref>} target. */
    static final String REF_ARG = "ref";

    /** The {@code /mg checkout <ref> --force} flag literal that overrides the dirty-guard. */
    static final String FORCE_FLAG = "--force";

    /** The {@code /mg commit --full} flag literal that forces a full rescan (overrides incremental). */
    static final String FULL_FLAG = "--full";

    /** The {@code /mg init --nofreeze} flag literal that routes init to the pumped (non-freezing) snapshot. */
    static final String NOFREEZE_FLAG = "--nofreeze";

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

    /** The {@code /mg checkout <ref>} target for {@code ctx} (the required {@link #REF_ARG} argument). */
    public static String refOf(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        return StringArgumentType.getString(ctx, REF_ARG);
    }

    /**
     * Whether {@code /mg checkout <ref> --force} was typed: true iff the {@link #FORCE_FLAG} literal
     * node was matched on the parsed path. Detecting the literal (rather than a boolean argument)
     * keeps the bare {@code <ref>} form clean while the flag overrides the dirty-guard (Spec D §4).
     */
    public static boolean isForce(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        for (com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            if (FORCE_FLAG.equals(node.getNode().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code /mg commit --full} was typed: true iff the {@link #FULL_FLAG} literal node was
     * matched on the parsed path. Forces a full-world rescan on this commit, ignoring incremental
     * dirty tracking (useful after structural changes or after re-binding the world adapter).
     */
    public static boolean isFull(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        for (com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            if (FULL_FLAG.equals(node.getNode().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code /mg init --nofreeze} was typed: true iff the {@link #NOFREEZE_FLAG} literal node
     * was matched on the parsed path. The bare {@code /mg init} freezes the tick and snapshots
     * synchronously (Spec C batch 2 §4); the flag routes to the tick-pumped snapshot instead.
     */
    public static boolean isNoFreeze(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        for (com.mojang.brigadier.context.ParsedCommandNode<CommandSourceStack> node : ctx.getNodes()) {
            if (NOFREEZE_FLAG.equals(node.getNode().getName())) {
                return true;
            }
        }
        return false;
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
                .then(initSubcommand(runtime))
                .then(subcommand(Subcommand.STATUS, runtime::status))
                .then(commitSubcommand(runtime))
                .then(subcommand(Subcommand.LOG, runtime::log))
                .then(diffSubcommand(runtime))
                .then(checkoutSubcommand(runtime))
                .then(subcommand(Subcommand.RESCAN, runtime::rescan));
    }

    /**
     * The {@code checkout} literal: gated at op ({@link Subcommand#CHECKOUT}, level 2 — Spec D §4),
     * runnable only with a target as {@code checkout <ref>} (force off) or {@code checkout <ref>
     * --force} (overrides the dirty-guard). The bare literal carries no target and is intentionally
     * not executable. Both runnable forms dispatch to {@code runtime.checkout}; the runtime reads the
     * ref via {@link #refOf} and the flag via {@link #isForce}.
     *
     * <p>The ref is a {@linkplain StringArgumentType#string() quotable phrase} — bare hashes and
     * branch names parse unquoted; a ref with Brigadier-reserved characters (e.g. {@code HEAD~1})
     * must be quoted ({@code /mg checkout "HEAD~1"}).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> checkoutSubcommand(Runtime runtime) {
        Command<CommandSourceStack> action = runtime::checkout;
        return Commands.literal(Subcommand.CHECKOUT.literal())
                .requires(Commands.hasPermission(permissionCheck(Subcommand.CHECKOUT.permissionLevel())))
                .then(Commands.argument(REF_ARG, StringArgumentType.string())
                        .suggests(runtime::suggestRefs)
                        .executes(action)
                        .then(Commands.literal(FORCE_FLAG).executes(action)));
    }

    /**
     * The {@code diff} literal: bare {@code /mg diff} runs working-vs-HEAD, while the nested
     * {@code <refA> <refB>} string arguments run ref-vs-ref (Spec D §4). Both forms share the same
     * permission gate and dispatch to {@link Runtime#diff}; the runtime reads the optional refs off
     * the context, so one method serves both shapes.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> diffSubcommand(Runtime runtime) {
        Command<CommandSourceStack> action = runtime::diff;
        return Commands.literal(Subcommand.DIFF.literal())
                .requires(Commands.hasPermission(permissionCheck(Subcommand.DIFF.permissionLevel())))
                .executes(action)
                .then(Commands.argument("refA", StringArgumentType.string())
                        .suggests(runtime::suggestRefs)
                        .then(Commands.argument("refB", StringArgumentType.string())
                                .suggests(runtime::suggestRefs)
                                .executes(action)));
    }

    /**
     * The {@code commit} literal: gated like any subcommand, runnable bare ({@link
     * #DEFAULT_COMMIT_MESSAGE}) and as {@code commit -m <message...>} where {@code message} greedily
     * captures the rest of the line (Spec D §4). Both forms dispatch to {@code runtime.commit}; the
     * runtime reads the text via {@link #messageOf}.
     */
    /**
     * The {@code commit} literal: runnable bare (default message), as {@code commit -m <message>}
     * (greedy message), as {@code commit --full} (force full rescan), or as {@code commit --full -m
     * <message>} (full rescan + explicit message). Both the bare and {@code --full} forms dispatch to
     * {@code runtime.commit}; the runtime reads the message via {@link #messageOf} and the flag via
     * {@link #isFull}.
     *
     * <p>Combining {@code -m} and {@code --full} in the same command is supported as {@code commit
     * --full -m <message>}. The reverse order ({@code commit -m <msg> --full}) is not supported by
     * Brigadier's greedy-string argument (it would consume {@code --full} as part of the message).
     */
    private static LiteralArgumentBuilder<CommandSourceStack> commitSubcommand(Runtime runtime) {
        return Commands.literal(Subcommand.COMMIT.literal())
                .requires(Commands.hasPermission(permissionCheck(Subcommand.COMMIT.permissionLevel())))
                .executes(runtime::commit)
                .then(Commands.literal("-m")
                        .then(Commands.argument(MESSAGE_ARG,
                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                .executes(runtime::commit)))
                .then(Commands.literal(FULL_FLAG)
                        .executes(runtime::commit)
                        .then(Commands.literal("-m")
                                .then(Commands.argument(MESSAGE_ARG,
                                        com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .executes(runtime::commit))));
    }

    /**
     * The {@code init} literal: runnable bare ({@code /mg init} — freeze-by-default, the tick is
     * frozen while the world snapshots synchronously) or as {@code /mg init --nofreeze} (route to the
     * tick-pumped snapshot that spreads across ticks, Spec C batch 2 §4). Both forms dispatch to
     * {@code runtime.init}; the runtime reads the flag via {@link #isNoFreeze}.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> initSubcommand(Runtime runtime) {
        Command<CommandSourceStack> action = runtime::init;
        return Commands.literal(Subcommand.INIT.literal())
                .requires(Commands.hasPermission(permissionCheck(Subcommand.INIT.permissionLevel())))
                .executes(action)
                .then(Commands.literal(NOFREEZE_FLAG).executes(action));
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
