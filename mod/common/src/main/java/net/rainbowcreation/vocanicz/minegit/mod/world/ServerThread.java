package net.rainbowcreation.vocanicz.minegit.mod.world;

/**
 * Narrow seam over the Minecraft server's task loop, so {@link ServerThreadScheduler} is testable
 * without a running server. The real implementation wraps {@code MinecraftServer}
 * ({@code isSameThread()} / {@code execute(Runnable)}).
 */
public interface ServerThread {

    /** Whether the calling thread is the server thread. */
    boolean onServerThread();

    /** Enqueues {@code task} to run on the server thread. */
    void submit(Runnable task);
}
