package com.minegit.mod.command;

import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.UnknownRefException;
import com.minegit.core.model.WorldDiff;
import com.minegit.mod.world.LevelRepoRegistry;
import com.minegit.mod.world.ModWorldAdapter;
import com.minegit.mod.world.ServerLevelAccess;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The live {@link MineGitCommands.Runtime}: resolves the invoking player's {@link ServerLevel},
 * binds it to a per-level MineGit repo under the world save (Spec D §3), runs the requested
 * read/setup operation via {@link MineGitService}, and messages the result back as text {@link
 * Component}s (Spec D §4). Every operation runs on the server thread (Brigadier dispatches there);
 * the heavier off-thread/throttled work arrives with {@code commit}/{@code checkout} in later batches.
 *
 * <p>This class is the Minecraft-touching seam, so it is exercised by GameTests / manual play rather
 * than the headless unit tests — the engine path it drives is covered through {@link MineGitService}.
 */
public final class ServerCommandRuntime implements MineGitCommands.Runtime {

    private final Clock clock;

    public ServerCommandRuntime() {
        this(Clock.systemUTC());
    }

    public ServerCommandRuntime(Clock clock) {
        this.clock = clock;
    }

    @Override
    public int init(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return notInGame(ctx);
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        LevelRepoRegistry registry = registryFor(ctx.getSource().getServer());
        if (registry.isBound(levelKey)) {
            ctx.getSource().sendSuccess(
                    () -> MineGitText.notice("Level '" + levelKey + "' already has a MineGit repo."),
                    false);
            return 0;
        }
        WorldAdapter adapter = adapterFor(level);
        MineGitService.init(registry.repoPath(levelKey), adapter, clock);
        registry.bind(levelKey); // mark bound only once the git repo actually exists
        ctx.getSource().sendSuccess(
                () -> MineGitText.good("Initialized MineGit repo for level '" + levelKey + "'."),
                false);
        return 1;
    }

    @Override
    public int status(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return notInGame(ctx);
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        LevelRepoRegistry registry = registryFor(ctx.getSource().getServer());
        if (!registry.isBound(levelKey)) {
            return noRepo(ctx, levelKey);
        }
        WorldDiff diff = MineGitService.status(registry.repoPath(levelKey), adapterFor(level), clock);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Status ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(levelKey + ": ").withStyle(ChatFormatting.GRAY))
                        .append(MineGitText.summary(diff)),
                false);
        return 1;
    }

    @Override
    public int log(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return notInGame(ctx);
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        LevelRepoRegistry registry = registryFor(ctx.getSource().getServer());
        if (!registry.isBound(levelKey)) {
            return noRepo(ctx, levelKey);
        }
        List<CommitInfo> commits = MineGitService.log(registry.repoPath(levelKey), adapterFor(level), clock);
        if (commits.isEmpty()) {
            ctx.getSource().sendSuccess(
                    () -> MineGitText.notice("No commits yet for '" + levelKey + "'."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(
                () -> Component.literal("MineGit log ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(levelKey + ":").withStyle(ChatFormatting.GRAY)),
                false);
        int shown = Math.min(MineGitText.LOG_LIMIT, commits.size());
        for (int i = 0; i < shown; i++) {
            CommitInfo commit = commits.get(i);
            ctx.getSource().sendSuccess(() -> MineGitText.commitLine(commit), false);
        }
        if (commits.size() > shown) {
            int hidden = commits.size() - shown;
            ctx.getSource().sendSuccess(() -> MineGitText.andMore(hidden), false);
        }
        return 1;
    }

    @Override
    public int diff(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return notInGame(ctx);
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        LevelRepoRegistry registry = registryFor(ctx.getSource().getServer());
        if (!registry.isBound(levelKey)) {
            return noRepo(ctx, levelKey);
        }
        Path repoPath = registry.repoPath(levelKey);
        WorldAdapter adapter = adapterFor(level);
        String refA = optionalArg(ctx, "refA");
        String refB = optionalArg(ctx, "refB");
        WorldDiff diff;
        String scope;
        try {
            if (refA == null) {
                diff = MineGitService.status(repoPath, adapter, clock); // working-vs-HEAD
                scope = levelKey;
            } else {
                diff = MineGitService.diffRefs(repoPath, adapter, clock, refA, refB);
                scope = refA + ".." + refB;
            }
        } catch (UnknownRefException e) {
            // Surface unresolvable refs loudly (core #37) — never silently diff against an empty tree.
            ctx.getSource().sendFailure(
                    Component.literal(e.getMessage()).withStyle(ChatFormatting.RED));
            return 0;
        }
        String header = scope;
        ctx.getSource().sendSuccess(
                () -> Component.literal("Diff ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(header + ":").withStyle(ChatFormatting.GRAY)),
                false);
        for (Component line : MineGitText.diffBody(diff, MineGitText.DIFF_LINE_CAP)) {
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** The string argument {@code name} if Brigadier matched it, or {@code null} when absent. */
    private static String optionalArg(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return StringArgumentType.getString(ctx, name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private WorldAdapter adapterFor(ServerLevel level) {
        return new ModWorldAdapter(new ServerLevelAccess(level));
    }

    private static LevelRepoRegistry registryFor(MinecraftServer server) {
        return new LevelRepoRegistry(server.getWorldPath(LevelResource.ROOT));
    }

    private static String levelKey(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static int notInGame(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendFailure(
                Component.literal("Run this in-game — MineGit acts on your level.")
                        .withStyle(ChatFormatting.RED));
        return 0;
    }

    private static int noRepo(CommandContext<CommandSourceStack> ctx, String levelKey) {
        ctx.getSource().sendFailure(
                Component.literal("No MineGit repo for '" + levelKey + "'. Run /mg init first.")
                        .withStyle(ChatFormatting.YELLOW));
        return 0;
    }
}
