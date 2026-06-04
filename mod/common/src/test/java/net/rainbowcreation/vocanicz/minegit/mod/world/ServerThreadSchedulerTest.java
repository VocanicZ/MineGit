package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServerThreadScheduler}: world reads/applies must touch the level on the server
 * thread (Spec D §3). When already on-thread the task runs inline; otherwise it is handed to the
 * server's task queue. Driven by a fake {@link ServerThread} — no running server.
 */
class ServerThreadSchedulerTest {

    /** Fake server thread: records submissions; {@code onThread} toggles the caller's location. */
    private static final class FakeServerThread implements ServerThread {
        boolean onThread;
        final List<Runnable> submitted = new ArrayList<Runnable>();

        @Override
        public boolean onServerThread() {
            return onThread;
        }

        @Override
        public void submit(Runnable task) {
            submitted.add(task);
        }
    }

    @Test
    void runsInlineWhenAlreadyOnServerThread() {
        FakeServerThread thread = new FakeServerThread();
        thread.onThread = true;
        ServerThreadScheduler scheduler = new ServerThreadScheduler(thread);
        AtomicInteger ran = new AtomicInteger();
        scheduler.run(ran::incrementAndGet);
        assertEquals(1, ran.get());
        assertTrue(thread.submitted.isEmpty());
    }

    @Test
    void submitsWhenOffServerThread() {
        FakeServerThread thread = new FakeServerThread();
        thread.onThread = false;
        ServerThreadScheduler scheduler = new ServerThreadScheduler(thread);
        AtomicInteger ran = new AtomicInteger();
        scheduler.run(ran::incrementAndGet);
        // Deferred to the server queue — not run inline.
        assertEquals(0, ran.get());
        assertEquals(1, thread.submitted.size());
        thread.submitted.get(0).run();
        assertEquals(1, ran.get());
    }

    @Test
    void executorInterfaceDelegatesToRun() {
        FakeServerThread thread = new FakeServerThread();
        thread.onThread = true;
        ServerThreadScheduler scheduler = new ServerThreadScheduler(thread);
        AtomicInteger ran = new AtomicInteger();
        scheduler.execute(ran::incrementAndGet);
        assertEquals(1, ran.get());
    }
}
