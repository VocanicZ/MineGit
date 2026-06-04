package com.minegit.plugin.world;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executor;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

/**
 * The {@link AsyncExecutor} hands a {@link Runnable} to the Bukkit scheduler's async pool so the
 * commit's {@code serialize + git} step runs off-tick (Spec B §6).
 */
class AsyncExecutorTest {

    @Test
    void executeSchedulesTheTaskAsynchronously() {
        Plugin plugin = mock(Plugin.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        Runnable task = mock(Runnable.class);

        Executor executor = new AsyncExecutor(plugin, scheduler);
        executor.execute(task);

        verify(scheduler).runTaskAsynchronously(plugin, task);
    }
}
