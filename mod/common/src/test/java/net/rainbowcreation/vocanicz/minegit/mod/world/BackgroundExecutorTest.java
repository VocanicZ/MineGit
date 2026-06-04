package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link BackgroundExecutor} runs the commit's {@code serialize + git} step off the calling (server)
 * thread (Spec D §5) on a single daemon worker, so it never stalls a tick.
 */
class BackgroundExecutorTest {

    @Test
    void runsTaskOnAnotherThread() throws InterruptedException {
        BackgroundExecutor exec = new BackgroundExecutor("minegit-test");
        try {
            AtomicReference<Thread> ran = new AtomicReference<Thread>();
            CountDownLatch done = new CountDownLatch(1);
            exec.execute(() -> {
                ran.set(Thread.currentThread());
                done.countDown();
            });
            assertTrue(done.await(5, TimeUnit.SECONDS), "task should run");
            assertNotEquals(Thread.currentThread(), ran.get(), "task must run off the caller thread");
            assertTrue(ran.get().isDaemon(), "worker should be a daemon thread");
        } finally {
            exec.shutdown();
        }
    }
}
