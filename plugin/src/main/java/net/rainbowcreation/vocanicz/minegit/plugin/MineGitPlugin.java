package net.rainbowcreation.vocanicz.minegit.plugin;

import net.rainbowcreation.vocanicz.minegit.core.mapping.LegacyBlockMapper;
import net.rainbowcreation.vocanicz.minegit.plugin.block.BlockBridge;
import net.rainbowcreation.vocanicz.minegit.plugin.block.BlockBridges;
import net.rainbowcreation.vocanicz.minegit.plugin.command.MessageService;
import net.rainbowcreation.vocanicz.minegit.plugin.command.MessageServices;
import net.rainbowcreation.vocanicz.minegit.plugin.command.MineGitCommand;
import net.rainbowcreation.vocanicz.minegit.plugin.listener.BlockChangeListener;
import net.rainbowcreation.vocanicz.minegit.plugin.version.ServerVersion;
import net.rainbowcreation.vocanicz.minegit.plugin.world.AsyncExecutor;
import net.rainbowcreation.vocanicz.minegit.plugin.world.BukkitWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CheckoutService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.CommitService;
import net.rainbowcreation.vocanicz.minegit.plugin.world.MainThreadExecutor;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldRepoRegistry;
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
 * {@code /minegit} dispatcher with {@code init}/{@code status}/{@code log} (#45), {@code diff} (#47),
 * {@code commit} (#46, async git via {@link CommitService}), and {@code checkout} (#48, throttled
 * main-thread apply via {@link CheckoutService}).
 */
public final class MineGitPlugin extends JavaPlugin {

    private ServerVersion serverVersion;
    private BlockBridge blockBridge;
    private WorldRepoRegistry worldRepos;
    private WorldDirtyRegistry worldDirty;
    private Executor mainThread;
    private CommitService commitService;
    private CheckoutService checkoutService;
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
        // One dirty set per world, shared by the adapter factory, commit/status, and the event listener
        // (Spec E task 5); the same instance must be used everywhere so events accumulate across commands.
        this.worldDirty = new WorldDirtyRegistry();
        // Reads/applies run on the server main thread; git work hops off via runTaskAsynchronously (#44).
        this.mainThread = new MainThreadExecutor(this, getServer().getScheduler());
        // commit (#46): throttled main-thread reads -> async serialize+git -> completion on main thread.
        Executor async = new AsyncExecutor(this, getServer().getScheduler());
        int chunksPerTick = Math.max(1, getConfig().getInt("commit-chunks-per-tick", 8));
        this.commitService = new CommitService(mainThread, async, chunksPerTick);
        // checkout (#48): throttled main-thread snapshot+apply, async dirty-guard/diff/ref-move.
        int applyChunksPerTick = Math.max(1, getConfig().getInt("checkout-chunks-per-tick", 4));
        this.checkoutService = new CheckoutService(mainThread, async, applyChunksPerTick);
        // Adventure components on modern servers, legacy ChatColor on 1.8-era ones (#45, Spec B §5).
        this.messages = MessageServices.detect();
        // Block-change listener feeds the same dirty registry so incremental commit/status/diff only
        // scan chunks that actually moved (Spec E task 6). Registered against the very same instance.
        getServer().getPluginManager().registerEvents(new BlockChangeListener(worldDirty), this);
        registerCommands();
        getLogger().info("MineGit enabled on server version " + serverVersion
                + " (" + (serverVersion.isLegacy() ? "legacy" : "modern") + " block bridge)");
    }

    /** Wires the {@code /minegit} dispatcher (+ tab completer) onto the command declared in plugin.yml. */
    private void registerCommands() {
        MineGitCommand command = new MineGitCommand(
                worldRepos, worldDirty, this::adapterFor, Clock.systemUTC(), messages, commitService,
                checkoutService);
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

    /**
     * The per-world dirty-set registry: one {@code DirtyChunkSet} per Bukkit world, shared by the
     * adapter factory, commit/status, and the block-change listener (Spec E task 5). Exposed so Task 6's
     * event listener can be registered against the very same registry instance.
     */
    public WorldDirtyRegistry worldDirty() {
        return worldDirty;
    }

    /** An executor that runs tasks on the server main thread (Spec B §6). */
    public Executor mainThread() {
        return mainThread;
    }

    /**
     * Builds a {@link BukkitWorldAdapter} bound to {@code world} using the selected block bridge and the
     * world's shared dirty set, so per-command adapters all read the same accumulating dirty state.
     */
    public BukkitWorldAdapter adapterFor(World world) {
        return new BukkitWorldAdapter(world, blockBridge, worldDirty.tracker(world.getName()));
    }
}
