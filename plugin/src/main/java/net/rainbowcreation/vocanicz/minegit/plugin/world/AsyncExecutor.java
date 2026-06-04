package net.rainbowcreation.vocanicz.minegit.plugin.world;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * An {@link Executor} that runs tasks off the server main thread via
 * {@link BukkitScheduler#runTaskAsynchronously} (Spec B §6). The commit's heavy {@code serialize +
 * git} step is dispatched here so it never stalls a tick; its task must touch no Bukkit world state
 * (the {@link SnapshotWorldAdapter} guarantees that). The counterpart to {@link MainThreadExecutor}.
 */
public final class AsyncExecutor implements Executor {

    private final Plugin plugin;
    private final BukkitScheduler scheduler;

    public AsyncExecutor(Plugin plugin, BukkitScheduler scheduler) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        scheduler.runTaskAsynchronously(plugin, command);
    }
}
