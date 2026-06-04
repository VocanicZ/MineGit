package net.rainbowcreation.vocanicz.minegit.mod.world;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Server-thread scheduler for the modern {@code WorldAdapter} (Spec D §3): the adapter's reads and
 * applies must touch the level on the server thread, while git work runs off-thread. {@link
 * #run(Runnable)} executes the task inline when the caller is already on the server thread, else
 * hands it to the server's task queue via a {@link ServerThread} seam.
 *
 * <p>Implements {@link Executor} so it can be passed where the engine expects a main-thread executor.
 */
public final class ServerThreadScheduler implements Executor {

    private final ServerThread thread;

    public ServerThreadScheduler(ServerThread thread) {
        this.thread = Objects.requireNonNull(thread, "thread");
    }

    /** Runs {@code task} on the server thread — inline if already there, else enqueued. */
    public void run(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (thread.onServerThread()) {
            task.run();
        } else {
            thread.submit(task);
        }
    }

    @Override
    public void execute(Runnable command) {
        run(command);
    }
}
