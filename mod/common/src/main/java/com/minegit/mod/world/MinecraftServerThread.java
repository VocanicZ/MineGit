package com.minegit.mod.world;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;

/**
 * {@link ServerThread} backed by a live {@link MinecraftServer}: {@code isSameThread()} reports
 * whether the caller is on the server thread, and {@code execute(Runnable)} enqueues a task onto the
 * server's tick loop. Used by {@link ServerThreadScheduler} to run {@code WorldAdapter} reads/applies
 * on the server thread (Spec D §3).
 */
public final class MinecraftServerThread implements ServerThread {

    private final MinecraftServer server;

    public MinecraftServerThread(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public boolean onServerThread() {
        return server.isSameThread();
    }

    @Override
    public void submit(Runnable task) {
        server.execute(task);
    }
}
