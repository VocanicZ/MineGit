package net.rainbowcreation.vocanicz.minegit.mod.command;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.UnknownRefException;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.world.BackgroundExecutor;
import net.rainbowcreation.vocanicz.minegit.mod.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.mod.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTrackerRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelRepoRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.world.MinecraftServerThread;
import net.rainbowcreation.vocanicz.minegit.mod.world.ModWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.mod.world.ServerLevelAccess;
import net.rainbowcreation.vocanicz.minegit.mod.world.ServerThreadScheduler;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
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
 * Component}s (Spec D §4). Read/setup runs inline on the server thread (Brigadier dispatches there);
 * {@code commit} drives the throttled-read + off-thread-git dance via {@link CommitService} (Spec D
 * §5), and the op-gated {@code checkout} drives the snapshot → dirty-guard → throttled-apply dance
 * via {@link CheckoutService}.
 *
 * <p>This class is the Minecraft-touching seam, so it is exercised by GameTests / manual play rather
 * than the headless unit tests — the engine path it drives is covered through {@link MineGitService}.
 */
public final class ServerCommandRuntime implements MineGitCommands.Runtime {

    /** Loaded chunks read per server-thread pass, so a commit's reads spread across ticks (Spec D §5). */
    private static final int CHUNKS_PER_TICK = 16;

    private final Clock clock;
    private final Executor background;
    private final DirtyTrackerRegistry trackers = new DirtyTrackerRegistry();

    public ServerCommandRuntime() {
        this(Clock.systemUTC());
    }

    public ServerCommandRuntime(Clock clock) {
        this(clock, new BackgroundExecutor("minegit-git"));
    }

    /** Test/override seam: inject the off-thread git executor (e.g. an inline one). */
    public ServerCommandRuntime(Clock clock, Executor background) {
        this.clock = clock;
        this.background = background;
    }

    /** Exposes the server-lifetime dirty tracker registry (for wiring mixin events). */
    public DirtyTrackerRegistry trackers() {
        return trackers;
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
        DirtyChunkSet tracker = trackers.tracker(levelKey);
        WorldDiff diff = MineGitService.status(registry.repoPath(levelKey), adapterFor(level), clock, tracker);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Status ").withStyle(ChatFormatting.GOLD)
                        .append(Component.literal(levelKey + ": ").withStyle(ChatFormatting.GRAY))
                        .append(MineGitText.summary(diff)),
                false);
        return 1;
    }

    @Override
    public int commit(CommandContext<CommandSourceStack> ctx) {
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
        String message = MineGitCommands.messageOf(ctx);
        boolean full = MineGitCommands.isFull(ctx);
        Author author = authorFor(player);
        DirtyChunkSet tracker = trackers.tracker(levelKey);
        if (full) {
            tracker.unprime(); // force a full rescan on this commit
        }
        WorldAdapter live = adapterFor(level);
        Path repoPath = registry.repoPath(levelKey);
        Executor serverThread = new ServerThreadScheduler(
                new MinecraftServerThread(ctx.getSource().getServer()));
        CommitService service = new CommitService(serverThread, background, CHUNKS_PER_TICK);

        ctx.getSource().sendSuccess(
                () -> MineGitText.notice("Committing '" + levelKey + "'…"), false);
        // Reads hop to the server thread (throttled), git runs off-thread, completion lands back on
        // the server thread where messaging the player is safe.
        service.commit(repoPath, live, clock, message, author, tracker,
                result -> reportCommit(ctx.getSource(), levelKey, result));
        return 1;
    }

    /** Renders the off-thread commit outcome to the player (Spec D §5: "message on completion"). */
    private static void reportCommit(
            CommandSourceStack source, String levelKey, CommitService.Result result) {
        if (result.isError()) {
            source.sendFailure(Component.literal(
                    "Commit failed for '" + levelKey + "': " + result.error().getMessage())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        CommitInfo commit = result.commit();
        if (commit == null) {
            source.sendSuccess(() -> MineGitText.notice(
                    "Nothing to commit — '" + levelKey + "' matches HEAD."), false);
            return;
        }
        source.sendSuccess(() -> Component.literal("Committed ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(MineGitText.shortHash(commit.getId()))
                        .withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" by ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(commit.getAuthor()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" "))
                .append(Component.literal(MineGitText.firstLine(commit.getMessage()))
                        .withStyle(ChatFormatting.WHITE)), false);
    }

    /** The commit author for {@code player}: display name + a UUID-stable placeholder email (Spec D §3). */
    private static Author authorFor(ServerPlayer player) {
        return new Author(
                player.getGameProfile().name(),
                player.getUUID() + "@players.minegit.local");
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
                diff = MineGitService.status(repoPath, adapter, clock, trackers.tracker(levelKey)); // working-vs-HEAD
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

    @Override
    public int checkout(CommandContext<CommandSourceStack> ctx) {
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
        String target = MineGitCommands.refOf(ctx);
        boolean force = MineGitCommands.isForce(ctx);
        WorldAdapter live = adapterFor(level);
        Path repoPath = registry.repoPath(levelKey);
        Executor serverThread = new ServerThreadScheduler(
                new MinecraftServerThread(ctx.getSource().getServer()));
        CheckoutService service = new CheckoutService(serverThread, background, CHUNKS_PER_TICK);

        ctx.getSource().sendSuccess(
                () -> MineGitText.notice("Checking out '" + target + "' in '" + levelKey + "'…"),
                false);
        // Snapshot + dirty-guard reads hop to the server thread, the plan/ref move runs off-thread,
        // applies land back on the server thread (throttled), and completion messages the player there.
        // On success, unprime the tracker: HEAD has moved, so the dirty set is relative to the old HEAD.
        // The next commit/status must do a full reconciliation pass against the new baseline.
        final String capturedLevelKey = levelKey;
        service.checkout(repoPath, live, clock, target, force, result -> {
            if (!result.isError()) {
                trackers.tracker(capturedLevelKey).unprime();
            }
            reportCheckout(ctx.getSource(), capturedLevelKey, target, result);
        });
        return 1;
    }

    /** Renders the throttled checkout outcome to the player (Spec D §4: live revert summary). */
    private static void reportCheckout(
            CommandSourceStack source, String levelKey, String target, CheckoutService.Result result) {
        if (result.isError()) {
            source.sendFailure(Component.literal(
                    "Checkout failed for '" + levelKey + "': " + result.error().getMessage())
                    .withStyle(ChatFormatting.RED));
            return;
        }
        WorldDiff applied = result.applied();
        source.sendSuccess(() -> Component.literal("Checked out ").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(target).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" — applied ").withStyle(ChatFormatting.GREEN))
                .append(MineGitText.summary(applied)), false);
    }

    @Override
    public int rescan(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return notInGame(ctx);
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        trackers.tracker(levelKey).unprime();
        ctx.getSource().sendSuccess(
                () -> MineGitText.good("MineGit will do a full rescan on the next commit/status."),
                false);
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
        return new ModWorldAdapter(new ServerLevelAccess(level), trackers.tracker(levelKey(level)));
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
