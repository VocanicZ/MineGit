package com.minegit.mod.world;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The mod's off-thread executor for the commit's {@code serialize + git} step (Spec D §5) — the
 * loader-agnostic analogue of the plugin's {@code AsyncExecutor}. Backed by a single daemon worker
 * thread, so heavy JGit work runs off-tick and never blocks the server thread, and a stuck task can
 * never keep the JVM alive on shutdown.
 *
 * <p>Single-threaded by design: commits for a level are serialised, matching the one-repo-per-level
 * model and keeping JGit's per-repo access well-ordered. No Minecraft types — testable directly.
 */
public final class BackgroundExecutor implements Executor {

    private final ExecutorService worker;

    public BackgroundExecutor(String name) {
        Objects.requireNonNull(name, "name");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
        this.worker = Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        worker.execute(command);
    }

    /** Stops the worker; in-flight git work finishes, queued work is dropped. */
    public void shutdown() {
        worker.shutdownNow();
    }
}
