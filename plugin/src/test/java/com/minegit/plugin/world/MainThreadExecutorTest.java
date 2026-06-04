package com.minegit.plugin.world;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

/**
 * The {@link MainThreadExecutor} hands a {@link Runnable} to the Bukkit scheduler's main thread so the
 * frontend can run reads/applies on-tick (Spec B §6).
 */
class MainThreadExecutorTest {

    @Test
    void executeSchedulesTheTaskOnTheMainThread() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Runnable task = mock(Runnable.class);

        Executor executor = new MainThreadExecutor(plugin, scheduler);
        executor.execute(task);

        verify(scheduler).runTask(plugin, task);
    }
}
