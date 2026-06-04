package net.rainbowcreation.vocanicz.minegit.plugin.command;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.adapter.WorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.diff.WorldDiffer;
import net.rainbowcreation.vocanicz.minegit.core.git.Author;
import net.rainbowcreation.vocanicz.minegit.core.git.CommitInfo;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.git.UnknownRefException;
import net.rainbowcreation.vocanicz.minegit.core.git.WorkingTreeDirtyException;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.plugin.world.Actor;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldRepoRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/**
 * The {@code /minegit} (aliases {@code /mg}, {@code /git}) dispatcher (Spec B §5, issue #45).
 *
 * <p>Routes the first argument to a subcommand, enforcing per-subcommand permissions
 * ({@link #PERM_USE} for read/commit, {@link #PERM_ADMIN} for destructive ops). This slice
 * implements {@code init}, {@code status}, {@code log}, {@code diff} (#45/#47), {@code commit}
 * (#46) — hopping threads via {@link CommitService} (main-thread reads, async git) — and
 * {@code checkout} (#48) — hopping via {@link CheckoutService} (async dirty-guard/diff, throttled
 * main-thread apply) — plus tab completion of subcommand names and checkout refs.
 *
 * <p>Kept Bukkit-light and dependency-injected (registry, adapter factory, clock, messaging) so it
 * is unit-testable without booting a server: the live plugin wires the real collaborators at enable.
 */
public final class MineGitCommand implements CommandExecutor, TabCompleter {

    /** Permission for read/setup/commit commands; default-true so everyone can use them. */
    public static final String PERM_USE = "minegit.use";

    /** Permission for destructive commands (e.g. {@code checkout}); op-only by default. */
    public static final String PERM_ADMIN = "minegit.admin";

    private static final int LOG_LIMIT = 10;

    /** Subcommand -> required permission, in display order. Drives dispatch and tab completion. */
    private static final Map<String, String> PERMISSIONS;

    static {
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("init", PERM_USE);
        p.put("status", PERM_USE);
        p.put("commit", PERM_USE);
        p.put("log", PERM_USE);
        p.put("diff", PERM_USE);
        p.put("rescan", PERM_USE);
        p.put("checkout", PERM_ADMIN);
        PERMISSIONS = p;
    }

    private final WorldRepoRegistry repos;
    private final WorldDirtyRegistry worldDirty;
    private final Function<World, WorldAdapter> adapters;
    private final Clock clock;
    private final MessageService messages;
    private final CommitService commitService;
    private final CheckoutService checkoutService;

    public MineGitCommand(
            WorldRepoRegistry repos,
            WorldDirtyRegistry worldDirty,
            Function<World, WorldAdapter> adapters,
            Clock clock,
            MessageService messages,
            CommitService commitService,
            CheckoutService checkoutService) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.worldDirty = Objects.requireNonNull(worldDirty, "worldDirty");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.commitService = Objects.requireNonNull(commitService, "commitService");
        this.checkoutService = Objects.requireNonNull(checkoutService, "checkoutService");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String perm = PERMISSIONS.get(sub);
        if (perm == null) {
            messages.send(sender, ChatColor.RED + "Unknown subcommand: " + sub);
            usage(sender);
            return true;
        }
        if (!sender.hasPermission(perm)) {
            messages.send(sender, ChatColor.RED + "You don't have permission to do that.");
            return true;
        }
        switch (sub) {
            case "init":
                return doInit(sender);
            case "status":
                return doStatus(sender);
            case "commit":
                return doCommit(sender, args);
            case "log":
                return doLog(sender);
            case "diff":
                return doDiff(sender, args);
            case "rescan":
                return doRescan(sender);
            case "checkout":
                return doCheckout(sender, args);
            default:
                usage(sender);
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<String>();
            for (Map.Entry<String, String> e : PERMISSIONS.entrySet()) {
                if (e.getKey().startsWith(prefix) && sender.hasPermission(e.getValue())) {
                    out.add(e.getKey());
                }
            }
            return out;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("checkout")
                && sender.hasPermission(PERM_ADMIN) && sender instanceof Player) {
            return completeRefs((Player) sender, args[1]);
        }
        return java.util.Collections.emptyList();
    }

    /** Branch names (and {@code HEAD}) of the player's world repo that match {@code prefix}. */
    private List<String> completeRefs(Player player, String prefix) {
        String name = player.getWorld().getName();
        if (!repos.isBound(name)) {
            return java.util.Collections.emptyList();
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> refs = new ArrayList<String>();
        if ("HEAD".toLowerCase(Locale.ROOT).startsWith(lower)) {
            refs.add("HEAD");
        }
        try (MineGitRepo repo = MineGitRepo.open(repos.repoPath(name), adapters.apply(player.getWorld()), clock)) {
            for (net.rainbowcreation.vocanicz.minegit.core.git.BranchRef branch : repo.branches()) {
                if (branch.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                    refs.add(branch.getName());
                }
            }
        }
        return refs;
    }

    // ---- subcommands --------------------------------------------------------------------------

    private boolean doInit(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (repos.isBound(name)) {
            messages.send(sender,
                    ChatColor.YELLOW + "World '" + name + "' already has a MineGit repo.");
            return true;
        }
        Path path = repos.repoPath(name);
        MineGitRepo repo = MineGitRepo.init(path, adapters.apply(world), clock);
        repo.close();
        repos.bind(name); // only mark bound once the git repo actually exists
        messages.send(sender,
                ChatColor.GREEN + "Initialized MineGit repo for world '" + name + "'.");
        return true;
    }

    private boolean doStatus(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (!repos.isBound(name)) {
            messages.send(sender, noRepo(name));
            return true;
        }
        WorldAdapter adapter = adapters.apply(world);
        try (MineGitRepo repo = MineGitRepo.open(repos.repoPath(name), adapter, clock)) {
            WorldDiff diff = workingTreeDiff(repo, adapter, name);
            messages.send(sender,
                    ChatColor.GOLD + "Status " + ChatColor.GRAY + name + ": "
                            + MineGitFormat.summary(diff));
        }
        return true;
    }

    /**
     * The primed-aware working-tree-vs-HEAD diff for {@code worldName}. When the world's tracker is
     * primed, only the dirty chunks are compared ({@link WorldDiffer#diffWorkingTreeDirty}) for a fast
     * incremental result; otherwise a full diff is run ({@link WorldDiffer#diffWorkingTree}).
     *
     * <p><strong>Status/diff never prime — only commit establishes the primed baseline.</strong>
     * Priming here would make a later commit trust a dirty set whose starting snapshot was never
     * recorded as a committed baseline, silently missing changes that occurred before the session began.
     */
    private WorldDiff workingTreeDiff(MineGitRepo repo, WorldAdapter adapter, String worldName) {
        DirtyChunkSet tracker = worldDirty.tracker(worldName);
        if (tracker.isPrimed()) {
            return WorldDiffer.diffWorkingTreeDirty(repo, adapter);
        }
        return WorldDiffer.diffWorkingTree(repo, adapter);
    }

    private boolean doCommit(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String message = parseMessage(args);
        if (message == null) {
            messages.send(sender,
                    ChatColor.RED + "Usage: " + ChatColor.WHITE + "/mg commit -m \"message\"");
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (!repos.isBound(name)) {
            messages.send(sender, noRepo(name));
            return true;
        }
        WorldAdapter live = adapters.apply(world);
        Path path = repos.repoPath(name);
        Author author = authorFor(Actor.fromPlayer(player));
        DirtyChunkSet tracker = worldDirty.tracker(name);
        // --full forces a one-off full reconciliation pass: unprime so the commit re-scans every chunk
        // instead of trusting the dirty set, then re-primes for subsequent incremental commits.
        if (hasFlag(args, "--full")) {
            tracker.unprime();
        }
        messages.send(sender, ChatColor.GRAY + "Committing '" + name + "'…");
        // Reads hop to the main thread (throttled), git runs async, completion lands back on main.
        commitService.commit(path, live, clock, message, author, tracker,
                result -> reportCommit(sender, name, result));
        return true;
    }

    /** Whether {@code args} (skipping the subcommand at index 0) contains {@code flag}. */
    private static boolean hasFlag(String[] args, String flag) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals(flag)) {
                return true;
            }
        }
        return false;
    }

    /** Renders the async commit outcome to the player (Spec B §6: "message on completion"). */
    private void reportCommit(CommandSender sender, String world, CommitService.Result result) {
        if (result.isError()) {
            messages.send(sender,
                    ChatColor.RED + "Commit failed for '" + world + "': "
                            + result.error().getMessage());
            return;
        }
        CommitInfo commit = result.commit();
        if (commit == null) {
            messages.send(sender,
                    ChatColor.YELLOW + "Nothing to commit — '" + world + "' matches HEAD.");
            return;
        }
        messages.send(sender,
                ChatColor.GREEN + "Committed "
                        + ChatColor.YELLOW + MineGitFormat.shortHash(commit.getId())
                        + ChatColor.GREEN + " by " + ChatColor.GRAY + commit.getAuthor()
                        + ChatColor.WHITE + " " + MineGitFormat.firstLine(commit.getMessage()));
    }

    private boolean doLog(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (!repos.isBound(name)) {
            messages.send(sender, noRepo(name));
            return true;
        }
        try (MineGitRepo repo = MineGitRepo.open(repos.repoPath(name), adapters.apply(world), clock)) {
            List<CommitInfo> commits = repo.log();
            if (commits.isEmpty()) {
                messages.send(sender, ChatColor.YELLOW + "No commits yet for '" + name + "'.");
                return true;
            }
            messages.send(sender,
                    ChatColor.GOLD + "MineGit log " + ChatColor.GRAY + name + ":");
            int shown = Math.min(LOG_LIMIT, commits.size());
            for (int i = 0; i < shown; i++) {
                messages.send(sender, MineGitFormat.commitLine(commits.get(i)));
            }
            if (commits.size() > shown) {
                messages.send(sender,
                        ChatColor.DARK_GRAY + "…and " + (commits.size() - shown) + " more.");
            }
        }
        return true;
    }

    private boolean doDiff(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (!repos.isBound(name)) {
            messages.send(sender, noRepo(name));
            return true;
        }
        // args[0] is "diff"; the optional ref pair follows. Zero refs → working-vs-HEAD; two refs →
        // ref-vs-ref; anything else is a usage error (mirrors the CLI `diff [refA refB]`).
        if (args.length != 1 && args.length != 3) {
            messages.send(sender,
                    ChatColor.GOLD + "MineGit " + ChatColor.GRAY + "— usage: "
                            + ChatColor.WHITE + "/mg diff [refA refB]");
            return true;
        }
        WorldAdapter adapter = adapters.apply(world);
        try (MineGitRepo repo = MineGitRepo.open(repos.repoPath(name), adapter, clock)) {
            WorldDiff diff = args.length == 1
                    ? workingTreeDiff(repo, adapter, name)
                    : WorldDiffer.diffRefs(repo, args[1], args[2]);
            String scope = args.length == 1 ? name : args[1] + ".." + args[2];
            messages.send(sender, ChatColor.GOLD + "Diff " + ChatColor.GRAY + scope + ":");
            for (String line : MineGitFormat.diffBody(diff, MineGitFormat.DIFF_LINE_CAP)) {
                messages.send(sender, line);
            }
        } catch (UnknownRefException e) {
            // Surface unresolvable refs loudly and consistently with core #37, never silently diff
            // against an empty tree.
            messages.send(sender, ChatColor.RED + e.getMessage());
        }
        return true;
    }

    /**
     * Forces the next status/commit to do a full reconciliation pass by unpriming the world's dirty
     * tracker. Use after the dirty set may have missed changes (e.g. an external edit), so the engine
     * re-scans every chunk once before trusting event-based tracking again.
     */
    private boolean doRescan(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String name = player.getWorld().getName();
        worldDirty.tracker(name).unprime();
        messages.send(sender,
                ChatColor.GREEN + "Rescan armed for '" + name + "'. "
                        + ChatColor.GRAY + "The next commit will do a full pass.");
        return true;
    }

    private boolean doCheckout(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        // args[0] is "checkout"; the target ref is required, an optional --force/-f may follow it.
        String target = null;
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--force") || args[i].equals("-f")) {
                force = true;
            } else if (target == null) {
                target = args[i];
            }
        }
        if (target == null) {
            messages.send(sender,
                    ChatColor.RED + "Usage: " + ChatColor.WHITE + "/mg checkout <ref> [--force]");
            return true;
        }
        World world = player.getWorld();
        String name = world.getName();
        if (!repos.isBound(name)) {
            messages.send(sender, noRepo(name));
            return true;
        }
        WorldAdapter live = adapters.apply(world);
        Path path = repos.repoPath(name);
        String ref = target;
        messages.send(sender, ChatColor.GRAY + "Checking out '" + ref + "' in '" + name + "'…");
        // Snapshot + dirty-guard + diff hop threads; the apply replays on the main thread (throttled).
        checkoutService.checkout(path, live, clock, ref, force,
                result -> reportCheckout(sender, name, ref, result));
        return true;
    }

    /** Renders the async checkout outcome to the player (Spec B §6: "message on completion"). */
    private void reportCheckout(
            CommandSender sender, String world, String ref, CheckoutService.Result result) {
        if (result.isError()) {
            RuntimeException error = result.error();
            if (error instanceof WorkingTreeDirtyException) {
                messages.send(sender,
                        ChatColor.YELLOW + "'" + world + "' differs from HEAD. "
                                + ChatColor.WHITE + "Commit first, or rerun with "
                                + ChatColor.AQUA + "--force" + ChatColor.WHITE + " to discard.");
                return;
            }
            if (error instanceof UnknownRefException) {
                messages.send(sender, ChatColor.RED + error.getMessage());
                return;
            }
            messages.send(sender,
                    ChatColor.RED + "Checkout failed for '" + world + "': " + error.getMessage());
            return;
        }
        // HEAD moved to a new baseline, so the accumulated dirty set no longer reflects working-vs-HEAD.
        // Unprime (success only) so the next commit/status reconciles against the new baseline with a
        // full pass before trusting event-based tracking again.
        worldDirty.tracker(world).unprime();
        WorldDiff applied = result.applied();
        messages.send(sender,
                ChatColor.GREEN + "Checked out " + ChatColor.YELLOW + ref
                        + ChatColor.GREEN + " in " + ChatColor.GRAY + world
                        + ChatColor.WHITE + " " + MineGitFormat.summary(applied));
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        messages.send(sender, ChatColor.RED + "Run this in-game — MineGit acts on your world.");
        return null;
    }

    private static String noRepo(String world) {
        return ChatColor.YELLOW + "No MineGit repo for '" + world + "'. Run /mg init first.";
    }

    /**
     * Extracts the commit message from {@code commit -m <message...>}. Bukkit splits the raw input on
     * spaces, so a quoted message arrives as several tokens; everything after {@code -m} (or
     * {@code --message}) is rejoined and any single pair of surrounding quotes is stripped. Returns
     * {@code null} when the flag is missing or its message is empty, so the caller can show usage.
     */
    static String parseMessage(String[] args) {
        int flag = -1;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-m") || args[i].equalsIgnoreCase("--message")) {
                flag = i;
                break;
            }
        }
        if (flag < 0 || flag + 1 >= args.length) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = flag + 1; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        String message = stripQuotes(sb.toString()).trim();
        return message.isEmpty() ? null : message;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /**
     * The commit author for a player: display name = in-game name, with the UUID folded into a stable
     * placeholder email so both halves of the player's identity (Spec B §4) survive into git.
     */
    private static Author authorFor(Actor actor) {
        return new Author(actor.name(), actor.uuid() + "@players.minegit.local");
    }

    private void usage(CommandSender sender) {
        messages.send(sender, ChatColor.GOLD + "MineGit "
                + ChatColor.GRAY + "— usage: " + ChatColor.WHITE + "/mg "
                + joinSubcommands());
    }

    private static String joinSubcommands() {
        return "<" + String.join("|", new ArrayList<String>(PERMISSIONS.keySet())) + ">";
    }
}
