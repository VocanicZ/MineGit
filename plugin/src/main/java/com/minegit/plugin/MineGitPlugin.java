package com.minegit.plugin;

import com.minegit.plugin.version.ServerVersion;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MineGit Spigot plugin entry point (Spec B §2).
 *
 * <p>This first slice is the scaffold: it boots, detects the server version, and exposes it for the
 * {@code BlockBridge} selection that later issues build on. Commands, the Bukkit {@code WorldAdapter},
 * and the block bridges land in follow-up issues (#2–#7).
 */
public final class MineGitPlugin extends JavaPlugin {

    private ServerVersion serverVersion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.serverVersion = detectServerVersion();
        getLogger().info("MineGit enabled on server version " + serverVersion
                + " (" + (serverVersion.isLegacy() ? "legacy" : "modern") + " block bridge)");
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
}
