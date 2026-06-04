package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TickPump}: the server-thread executor that spreads queued work across server ticks
 * within a per-tick time budget. This is the throttle that stops {@code /mg commit} and
 * {@code /mg checkout} from doing a whole-world scan in a single tick (the "Can't keep up!" freeze).
 *
 * <p>The key property a re-submitting batch loop relies on: when a task that runs during a pump
 * enqueues its <em>continuation</em>, that continuation runs in the same pump only while there is
 * budget left — once the budget is spent it defers to the next pump (next tick). Driven by an
 * injected nanosecond clock so the budget behaviour is deterministic without a real server.
 */
class TickPumpTest {

    /** Budget = 100 clock units for every test, so tasks can step the fake clock to cross it exactly. */
    private static final long BUDGET = 100L;

    @Test
    void drainsEverythingWhenUnderBudget() {
        AtomicLong clock = new AtomicLong();
        TickPump pump = new TickPump(BUDGET, clock::get);
        List<Integer> ran = new ArrayList<Integer>();
        pump.execute(() -> ran.add(1));
        pump.execute(() -> ran.add(2));
        pump.execute(() -> ran.add(3));

        pump.pump(); // clock never advances → all three fit in one tick

        assertEquals(List.of(1, 2, 3), ran);
    }

    @Test
    void runsAtLeastOneEvenWhenABatchExceedsBudget() {
        AtomicLong clock = new AtomicLong();
        TickPump pump = new TickPump(BUDGET, clock::get);
        List<Integer> ran = new ArrayList<Integer>();
        // Each task alone spends the whole budget, so only one runs per pump.
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            pump.execute(() -> {
                ran.add(n);
                clock.addAndGet(BUDGET);
            });
        }

        pump.pump();
        assertEquals(List.of(1), ran, "first pump runs exactly one over-budget task");
        pump.pump();
        assertEquals(List.of(1, 2), ran, "second pump runs the next one");
        pump.pump();
        assertEquals(List.of(1, 2, 3), ran, "third pump runs the last one");
    }

    @Test
    void continuationRunsThisPumpWhileUnderBudget() {
        AtomicLong clock = new AtomicLong();
        TickPump pump = new TickPump(BUDGET, clock::get);
        List<Integer> ran = new ArrayList<Integer>();
        // Task A enqueues its continuation B; neither spends budget, so both run in one pump.
        pump.execute(() -> {
            ran.add(1);
            pump.execute(() -> ran.add(2));
        });

        pump.pump();

        assertEquals(List.of(1, 2), ran);
    }

    @Test
    void continuationDefersToNextPumpOnceBudgetSpent() {
        AtomicLong clock = new AtomicLong();
        TickPump pump = new TickPump(BUDGET, clock::get);
        List<Integer> ran = new ArrayList<Integer>();
        // Task A spends the budget, then enqueues its continuation B — B must wait for the next tick.
        pump.execute(() -> {
            ran.add(1);
            clock.addAndGet(BUDGET);
            pump.execute(() -> ran.add(2));
        });

        pump.pump();
        assertEquals(List.of(1), ran, "continuation must not run in the same over-budget pump");
        pump.pump();
        assertEquals(List.of(1, 2), ran, "continuation runs on the next pump");
    }

    @Test
    void pumpOnEmptyQueueIsANoop() {
        AtomicLong clock = new AtomicLong();
        TickPump pump = new TickPump(BUDGET, clock::get);
        pump.pump(); // must not throw
        assertTrue(true);
    }
}
