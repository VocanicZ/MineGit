package net.rainbowcreation.vocanicz.minegit.mod.world;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Server-thread executor that spreads queued work across server ticks within a per-tick time budget,
 * so a {@code /mg commit} or {@code /mg checkout} no longer reads/applies the whole loaded-chunk set in
 * one tick (the "Can't keep up!" freeze). Tasks submitted via {@link #execute(Runnable)} are queued;
 * {@link #pump()} — called once per server tick on the server thread — drains them until the budget for
 * that tick is spent.
 *
 * <p>Why a budget rather than one-task-per-tick: {@link CommitService}/{@link CheckoutService} already
 * batch {@code chunksPerTick} chunks per submitted task and re-submit a <em>continuation</em> for the
 * next batch. A budget lets a capable machine run several batches per tick (fast completion) while a
 * slow machine runs fewer (no freeze) — both bounded by the same wall-clock ceiling. A task that
 * re-submits its continuation runs it within the same pump only while budget remains; once the budget
 * is spent the continuation defers to the next tick. {@link #pump()} always runs at least one queued
 * task so progress is guaranteed even if a single batch overruns the budget.
 *
 * <p>This replaces the earlier inline scheduler, which ran tasks on the calling thread when already on
 * the server thread — and Brigadier dispatches commands on the server thread, so the batch loop
 * recursed to completion in a single tick, defeating the throttle entirely.
 *
 * <p>Thread-safe: {@link #execute(Runnable)} may be called from the background git thread (the
 * completion hand-off) or the server thread (the batch continuations); {@link #pump()} runs on the
 * server thread. The backing {@link ConcurrentLinkedQueue} serialises the concurrent producers.
 */
public final class TickPump implements Executor {

    /** Default per-tick wall-clock ceiling: leaves headroom under the 50ms vanilla tick budget. */
    public static final long DEFAULT_BUDGET_NANOS = 20_000_000L; // 20ms

    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();
    private final long budgetNanos;
    private final LongSupplier nanoClock;

    /** Production pump: a 20ms budget read off {@link System#nanoTime()}. */
    public TickPump() {
        this(DEFAULT_BUDGET_NANOS, System::nanoTime);
    }

    /**
     * Test/override seam.
     *
     * @param budgetNanos per-tick budget in the {@code nanoClock}'s units; must be {@code >= 0}
     * @param nanoClock monotonic clock (e.g. {@code System::nanoTime}), injectable for deterministic tests
     */
    public TickPump(long budgetNanos, LongSupplier nanoClock) {
        if (budgetNanos < 0) {
            throw new IllegalArgumentException("budgetNanos must be >= 0 but was " + budgetNanos);
        }
        this.budgetNanos = budgetNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    /** Queues {@code task} to run on a future {@link #pump()}; never runs it inline. */
    @Override
    public void execute(Runnable task) {
        queue.add(Objects.requireNonNull(task, "task"));
    }

    /**
     * Runs queued tasks until the per-tick budget is spent (always at least one). Call once per server
     * tick on the server thread.
     */
    public void pump() {
        long deadline = nanoClock.getAsLong() + budgetNanos;
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
            if (nanoClock.getAsLong() >= deadline) {
                break;
            }
        }
    }
}
