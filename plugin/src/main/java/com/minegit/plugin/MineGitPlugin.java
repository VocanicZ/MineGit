package com.minegit.plugin;

import com.minegit.core.mapping.LegacyBlockMapper;
import com.minegit.plugin.block.BlockBridge;
import com.minegit.plugin.block.BlockBridges;
import com.minegit.plugin.command.MessageService;
import com.minegit.plugin.command.MessageServices;
import com.minegit.plugin.command.MineGitCommand;
import com.minegit.plugin.version.ServerVersion;
import com.minegit.plugin.world.BukkitWorldAdapter;
import com.minegit.plugin.world.MainThreadExecutor;
import com.minegit.plugin.world.WorldRepoRegistry;
import java.time.Clock;
import java.util.concurrent.Executor;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MineGit Spigot plugin entry point (Spec B §2).
 *
 * <p>On enable it detects the server version, selects the version-appropriate {@code BlockBridge}
 * (Spec B §3), binds the per-world {@code WorldAdapter}/repo registry (Spec B §4), and registers the
 * {@code /minegit} dispatcher with its read-only/setup subcommands (Spec B §5, #45). The remaining
 * subcommands ({@code commit}/{@code diff}/{@code checkout}) land in follow-up issues.
 */
public final class MineGitPlugin extends JavaPlugin {

    private ServerVersion serverVersion;
    private BlockBridge blockBridge;
    private WorldRepoRegistry worldRepos;
    private Executor mainThread;
    private MessageService messages;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.serverVersion = detectServerVersion();
        // Pick the block bridge once from the detected version (#42 -> #43): legacy id/meta vs modern
        // reflection. The legacy mapper is loaded eagerly; the modern bridge defers its reflection.
        this.blockBridge = BlockBridges.forVersion(serverVersion, new LegacyBlockMapper());
        // One repo per world under plugins/MineGit/repos/<world>; bindings persist across restarts (#44).
        this.worldRepos = new WorldRepoRegistry(getDataFolder().toPath());
        // Reads/applies run on the server main thread; git work hops off via runTaskAsynchronously (#44).
        this.mainThread = new MainThreadExecutor(this, getServer().getScheduler());
        // Adventure components on modern servers, legacy ChatColor on 1.8-era ones (#45, Spec B §5).
        this.messages = MessageServices.detect();
        registerCommands();
        getLogger().info("MineGit enabled on server version " + serverVersion
                + " (" + (serverVersion.isLegacy() ? "legacy" : "modern") + " block bridge)");
    }

    /** Wires the {@code /minegit} dispatcher (+ tab completer) onto the command declared in plugin.yml. */
    private void registerCommands() {
        MineGitCommand command =
                new MineGitCommand(worldRepos, this::adapterFor, Clock.systemUTC(), messages);
        PluginCommand minegit = getCommand("minegit");
        if (minegit != null) {
            minegit.setExecutor(command);
            minegit.setTabCompleter(command);
        } else {
            getLogger().warning("Command 'minegit' missing from plugin.yml; commands disabled");
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("MineGit disabled");
    }

    /**
     * Detect the running server version from {@code Bukkit.getBukkitVersion()}. Falls back to a modern
     * sentinel if the string is unparseable, so an odd fork never bricks enable — the modern reflection
     * bridge degrades more gracefully than the legacy id+meta path on an unknown server.
     */
    private ServerVersion detectServerVersion() {
        String raw = getServer().getBukkitVersion();
        try {
            return ServerVersion.parseBukkitVersion(raw);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Could not parse server version '" + raw + "'; assuming modern");
            return ServerVersion.parseBukkitVersion("1.20.4-R0.1-SNAPSHOT");
        }
    }

    /** The server version detected at enable, for BlockBridge selection (Spec B §3). */
    public ServerVersion serverVersion() {
        return serverVersion;
    }

    /** The block bridge selected for this server, used by the WorldAdapter (Spec B §3, §4). */
    public BlockBridge blockBridge() {
        return blockBridge;
    }

    /** The world&harr;repo registry: one MineGit repo per Bukkit world (Spec B §4). */
    public WorldRepoRegistry worldRepos() {
        return worldRepos;
    }

    /** An executor that runs tasks on the server main thread (Spec B §6). */
    public Executor mainThread() {
        return mainThread;
    }

    /** Builds a {@link BukkitWorldAdapter} bound to {@code world} using the selected block bridge. */
    public BukkitWorldAdapter adapterFor(World world) {
        return new BukkitWorldAdapter(world, blockBridge);
    }
}
