package com.minegit.plugin.world;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * An {@link Executor} that runs tasks on the Bukkit server main thread via the
 * {@link BukkitScheduler} (Spec B §6). The {@link WorldAdapter}'s reads and applies must touch the
 * world on-tick; the frontend hands them here while git work runs off-thread.
 */
public final class MainThreadExecutor implements Executor {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    public MainThreadExecutor(Plugin plugin, BukkitScheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        scheduler.runTask(plugin, command);
    }
}
