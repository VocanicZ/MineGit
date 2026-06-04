package com.minegit.plugin.command;

import com.minegit.core.adapter.WorldAdapter;
import com.minegit.core.diff.WorldDiffer;
import com.minegit.core.git.CommitInfo;
import com.minegit.core.git.MineGitRepo;
import com.minegit.core.model.WorldDiff;
import com.minegit.plugin.world.WorldRepoRegistry;
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
 * implements the read-only/setup trio — {@code init}, {@code status}, {@code log} — plus tab
 * completion of subcommand names; {@code commit}/{@code diff}/{@code checkout} land in follow-up
 * issues by adding a case here and an entry to {@link #PERMISSIONS}.
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
        p.put("log", PERM_USE);
        PERMISSIONS = p;
    }

    private final WorldRepoRegistry repos;
    private final Function<World, WorldAdapter> adapters;
    private final Clock clock;
    private final MessageService messages;

    public MineGitCommand(
            WorldRepoRegistry repos,
            Function<World, WorldAdapter> adapters,
            Clock clock,
            MessageService messages) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.adapters = Objects.requireNonNull(adapters, "adapters");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.messages = Objects.requireNonNull(messages, "messages");
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
            case "log":
                return doLog(sender);
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
        return java.util.Collections.emptyList();
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
            WorldDiff diff = WorldDiffer.diffWorkingTree(repo, adapter);
            messages.send(sender,
                    ChatColor.GOLD + "Status " + ChatColor.GRAY + name + ": "
                            + MineGitFormat.summary(diff));
        }
        return true;
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

    private void usage(CommandSender sender) {
        messages.send(sender, ChatColor.GOLD + "MineGit "
                + ChatColor.GRAY + "— usage: " + ChatColor.WHITE + "/mg "
                + joinSubcommands());
    }

    private static String joinSubcommands() {
        return "<" + String.join("|", new ArrayList<String>(PERMISSIONS.keySet())) + ">";
    }
}
