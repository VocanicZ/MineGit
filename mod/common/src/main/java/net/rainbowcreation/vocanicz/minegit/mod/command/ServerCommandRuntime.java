package net.rainbowcreation.vocanicz.minegit.mod.command;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.UnknownRefException;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.net.DiffOverlaySender;
import net.rainbowcreation.vocanicz.minegit.mod.world.BackgroundExecutor;
import net.rainbowcreation.vocanicz.minegit.mod.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.mod.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTrackerRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelRepoRegistry;
import net.rainbowcreation.vocanicz.minegit.mod.world.ModWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.mod.world.ServerLevelAccess;
import net.rainbowcreation.vocanicz.minegit.mod.world.TickPump;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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

    /**
     * Loaded chunks read/applied per batch. Each batch is one re-submitted task on the {@link #pump};
     * the pump's per-tick time budget decides how many batches run in a tick, so this only needs to be
     * small enough that a single batch stays well under that budget (Spec D §5).
     */
    private static final int CHUNKS_PER_TICK = 8;

    private final Clock clock;
    private final Executor background;
    private final DirtyTrackerRegistry trackers = new DirtyTrackerRegistry();

    /**
     * The server-lifetime server-thread executor. Commit/checkout reads and applies are queued here and
     * drained across ticks by {@link #tick()} (wired to a server-tick event), so a whole-world scan
     * spreads over many ticks instead of freezing one. Shared across commands so a single tick handler
     * drains all in-flight operations.
     */
    private final TickPump pump = new TickPump();

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

    /**
     * Drains a slice of queued commit/checkout work on the server thread. Wire to a server-tick event
     * (once per tick) so throttled reads/applies make progress without freezing the tick.
     */
    public void tick() {
        pump.pump();
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
        Path repoPath = registry.repoPath(levelKey);
        MineGitService.init(repoPath, adapter, clock);
        registry.bind(levelKey); // mark bound only once the git repo actually exists
        // Capture the current world as the initial commit, so init is a real baseline you can check out
        // to (rather than a chunk-less metadata commit that would empty the world). The tracker is
        // unprimed here, so this does the full-world scan and primes it for incremental commits after.
        Author author = authorFor(player);
        DirtyChunkSet tracker = trackers.tracker(levelKey);
        CommitService service = new CommitService(pump, background, CHUNKS_PER_TICK);
        boolean noFreeze = MineGitCommands.isNoFreeze(ctx);
        if (noFreeze) {
            // --nofreeze: spread the snapshot across ticks via the same tick pump as /mg commit, so a
            // large world doesn't freeze the tick. Returns before the snapshot lands (Spec C batch 2 §4).
            ctx.getSource().sendSuccess(
                    () -> MineGitText.good(
                            "Initialized MineGit repo for level '" + levelKey + "' — snapshotting world…"),
                    false);
            service.commit(repoPath, adapter, clock, "Initial world snapshot", author, tracker,
                    result -> reportCommit(ctx.getSource(), levelKey, result));
            return 1;
        }
        // Freeze-by-default: run the snapshot commit synchronously on the server thread, so the tick is
        // frozen until the commit exists at repo HEAD, then resumes (Spec C batch 2 §4).
        ctx.getSource().sendSuccess(
                () -> MineGitText.good(
                        "Initialized MineGit repo for level '" + levelKey
                                + "' — Freezing server to snapshot world…"),
                false);
        CommitService.Result result =
                service.commitBlocking(repoPath, adapter, clock, "Initial world snapshot", author, tracker);
        reportCommit(ctx.getSource(), levelKey, result);
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
        CommitService service = new CommitService(pump, background, CHUNKS_PER_TICK);

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
        String fromRef;
        String toRef;
        try {
            if (refA == null) {
                diff = MineGitService.status(repoPath, adapter, clock, trackers.tracker(levelKey)); // working-vs-HEAD
                scope = levelKey;
                fromRef = "HEAD";
                toRef = "WORKING";
            } else {
                diff = MineGitService.diffRefs(repoPath, adapter, clock, refA, refB);
                scope = refA + ".." + refB;
                fromRef = refA;
                toRef = refB;
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
        // ...and push the same diff as the in-world overlay to the requesting player. Gated on
        // canSend: a player without the client mod is silently skipped; the chat diff above stands.
        DiffOverlaySender.send(player, diff, fromRef, toRef);
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
        DirtyChunkSet tracker = trackers.tracker(levelKey);
        // When primed, the dirty set is a trustworthy record of changes since HEAD, so the guard only
        // needs to inspect those chunks — a post-commit checkout skips the whole-world scan. When not
        // primed (e.g. a fresh bind), fall back to the safe full scan.
        boolean dirtyScoped = tracker.isPrimed();
        CheckoutService service = new CheckoutService(pump, background, CHUNKS_PER_TICK);

        ctx.getSource().sendSuccess(
                () -> MineGitText.notice("Checking out '" + target + "' in '" + levelKey + "'…"),
                false);
        // Snapshot + dirty-guard reads hop to the server thread, the plan/ref move runs off-thread,
        // applies land back on the server thread (throttled), and completion messages the player there.
        // On success the live world now equals the new HEAD, so prime the tracker: future checkouts are
        // dirty-scoped (fast). We deliberately do NOT drain — apply() re-marks the touched chunks via
        // the setBlockState mixin, but those now match HEAD so the next commit diff-and-skips them
        // (over-marking is safe). Draining would risk dropping a block placed during the ref-move
        // window (under-marking, the one unsafe case); priming-only preserves such concurrent edits.
        final String capturedLevelKey = levelKey;
        service.checkout(repoPath, live, clock, target, force, dirtyScoped, result -> {
            if (!result.isError()) {
                tracker.prime();
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

    /** The most {@code HEAD~N} aliases to offer (beyond bare {@code HEAD}). */
    private static final int HEAD_ALIAS_CAP = 9;

    @Override
    public CompletableFuture<Suggestions> suggestRefs(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            return builder.buildFuture();
        }
        ServerLevel level = player.level();
        String levelKey = levelKey(level);
        LevelRepoRegistry registry = registryFor(ctx.getSource().getServer());
        if (!registry.isBound(levelKey)) {
            return builder.buildFuture();
        }
        MineGitService.RefCatalog catalog;
        try {
            catalog = MineGitService.refCatalog(registry.repoPath(levelKey), adapterFor(level), clock);
        } catch (RuntimeException broken) {
            // Tab-completion must never fail the command line; a repo hiccup just yields no suggestions.
            return builder.buildFuture();
        }

        // HEAD / HEAD~N aliases first, then branches + checkout-able short-hashes from the catalogue.
        // Cap HEAD~N at depth-2 so we never offer HEAD~(depth-1) — the empty root, which checkout refuses.
        LinkedHashMap<String, String> all = new LinkedHashMap<String, String>();
        all.put("HEAD", "current commit");
        int back = Math.min(catalog.headCommitDepth() - 2, HEAD_ALIAS_CAP);
        for (int n = 1; n <= back; n++) {
            all.put("HEAD~" + n, n == 1 ? "1 commit back" : n + " commits back");
        }
        all.putAll(catalog.refs());

        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : all.entrySet()) {
            String text = quoteIfNeeded(entry.getKey());
            if (text.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(text, Component.literal(entry.getValue()));
            }
        }
        return builder.buildFuture();
    }

    /** Whether {@code s} parses as a Brigadier unquoted string (so it needs no surrounding quotes). */
    private static boolean unquotedSafe(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '_' || c == '-' || c == '.' || c == '+';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** Wraps {@code s} in quotes (escaping {@code \} and {@code "}) when it isn't unquoted-safe (e.g. {@code HEAD~1}, {@code origin/main}). */
    private static String quoteIfNeeded(String s) {
        if (unquotedSafe(s)) {
            return s;
        }
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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
