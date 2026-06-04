package net.rainbowcreation.vocanicz.minegit.core.adapter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe set of chunks that have changed since the last commit (or drain). Used by MineGit's
 * event-based dirty tracking to narrow incremental commits and status/diff reads to only the chunks
 * that actually moved.
 *
 * <p><strong>markDirty</strong> — may be called from any thread (including Minecraft's server
 * thread or chunk-load threads). Adding the same ref multiple times is idempotent; over-marking
 * (recording a ref that turned out not to change) is safe — the engine will diff-and-skip. The real
 * risk is <em>under-marking</em>: a ref that changed but was never recorded will be silently
 * omitted from the next incremental commit.
 *
 * <p><strong>peekDirty</strong> — returns a snapshot of the current dirty set without clearing it.
 * Used by {@code /mg status} and {@code /mg diff}, which need to see the candidate chunk set but
 * must not disturb it so that a subsequent {@code /mg commit} still picks up the same refs.
 *
 * <p><strong>drainDirty</strong> — returns a snapshot <em>and then clears</em> the internal set.
 * Used by {@code /mg commit}: once the commit records these chunks the dirty set resets, so the
 * next incremental commit only sees changes that arrived after this drain.
 *
 * <p><strong>primed flag</strong> — a once-per-session marker used for initial reconciliation. When
 * {@code false} (fresh start or after {@link #unprime()}), the adapter knows it has not yet done a
 * full-world baseline scan, so it should fall back to {@link WorldAdapter#allChunks()} on the first
 * commit. Once {@link #prime()} is called the adapter trusts the dirty set exclusively. Over-marking
 * is always safe; the flag guards against the under-marking window that exists before the first
 * reconciliation pass completes.
 */
public final class DirtyChunkSet {

    private final Set<ChunkRef> dirty = ConcurrentHashMap.newKeySet();
    private volatile boolean primed;

    /**
     * Marks {@code ref} as dirty. Safe to call from any thread, idempotent for the same ref.
     *
     * @throws NullPointerException if {@code ref} is {@code null}
     */
    public void markDirty(ChunkRef ref) {
        dirty.add(Objects.requireNonNull(ref, "ref"));
    }

    /**
     * Returns a copy of the current dirty set without clearing it. Callers (status, diff) may call
     * this repeatedly without disturbing the set for a future drain.
     *
     * @return an unmodifiable snapshot; it is decoupled from this set
     */
    public Set<ChunkRef> peekDirty() {
        return Collections.unmodifiableSet(new HashSet<ChunkRef>(dirty));
    }

    /**
     * Returns a copy of the current dirty set and then removes those refs from the internal set.
     * Callers (commit) use this so the dirty set resets after each commit.
     *
     * @return an unmodifiable snapshot of the refs that were dirty before the drain
     */
    public Set<ChunkRef> drainDirty() {
        Set<ChunkRef> snapshot = new HashSet<ChunkRef>(dirty);
        dirty.removeAll(snapshot);
        return Collections.unmodifiableSet(snapshot);
    }

    /**
     * Returns {@code true} if this set has been primed (initial reconciliation completed).
     */
    public boolean isPrimed() {
        return primed;
    }

    /**
     * Marks this set as primed. The adapter will now trust the dirty set exclusively for incremental
     * commits instead of falling back to a full-world scan.
     */
    public void prime() {
        primed = true;
    }

    /**
     * Resets the primed flag, causing the next commit to perform a full reconciliation pass before
     * trusting the dirty set. Called on world-unload or adapter re-bind.
     */
    public void unprime() {
        primed = false;
    }
}
