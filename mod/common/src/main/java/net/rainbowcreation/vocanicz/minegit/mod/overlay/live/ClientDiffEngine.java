package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayState;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/**
 * Client live-diff coordinator (Spec SP2 §2d). Owns the frozen baseline, dirty tracker, and differ;
 * turns server snapshot pushes into a frozen HEAD baseline, then re-diffs the live client world each
 * tick over a budget of dirty sections and emits the overlay the renderer reads.
 *
 * <p>The level is supplied lazily ({@code levelSupplier.get()}); the client world swaps on dimension
 * change/reconnect, and a {@code null} supply means "no world this tick" — every world-touching path
 * no-ops in that case.
 */
public final class ClientDiffEngine {

    /** Chunks seeded per {@link #tick} — caps the per-tick HEAD-baseline scan so a burst can't freeze a frame. */
    private static final int SEED_BUDGET = 4;

    private final HeadBaselineCache cache;
    private final DirtySectionTracker tracker = new DirtySectionTracker();
    private final LiveDiffer differ = new LiveDiffer();
    private final Supplier<LevelAccess> levelSupplier;

    /** The assembled live diff: per dirty section, its current non-empty change list. */
    private final Map<DirtySectionTracker.Section, List<BlockChange>> accumulator =
            new LinkedHashMap<DirtySectionTracker.Section, List<BlockChange>>();

    /** The last server push, indexed for chunk-load seeding (per dimension, per chunk → changes). */
    private final Map<DimensionId, Map<ChunkPos, List<BlockChange>>> heldDiff =
            new HashMap<DimensionId, Map<ChunkPos, List<BlockChange>>>();

    private boolean diffReceived;
    private OverlayState overlay;

    public ClientDiffEngine(int cacheCap, Supplier<LevelAccess> levelSupplier) {
        this.cache = new HeadBaselineCache(cacheCap);
        this.levelSupplier = java.util.Objects.requireNonNull(levelSupplier, "levelSupplier");
    }

    /**
     * Seed/reset path. For each dimension in {@code diff}: drop that dimension's
     * baselines/tracker-entries/accumulator-entries (HEAD-move reset semantics) and index its chunk
     * changes for later seeding. Does NOT seed synchronously — bursting a frozen HEAD baseline for
     * the whole loaded set scans ~98k blocks/chunk and would freeze the subscribe-time frame; the
     * budgeted {@link #tick} seeds the near chunks incrementally instead. Marks {@code diffReceived}
     * and rebuilds the overlay.
     */
    public void onServerDiff(WorldDiff diff) {
        diffReceived = true;
        LevelAccess level = levelSupplier.get();

        // Dimensions to reset + re-index: those carried by the diff, PLUS the current client
        // dimension. A "world == HEAD" push carries NO dimensions, yet the current dimension's
        // loaded chunks still need a frozen HEAD baseline — without it every solid block reads as
        // an addition the moment its section is marked dirty (and a post-commit empty push would
        // never clear stale boxes).
        DimensionId current = level == null ? null : level.dimension();
        Set<DimensionId> dims = new LinkedHashSet<DimensionId>(diff.getDimensions().keySet());
        if (current != null) {
            dims.add(current);
        }

        for (DimensionId dim : dims) {
            cache.dropDimension(dim);
            tracker.dropDimension(dim);
            accumulator.keySet().removeIf(s -> s.dimension().equals(dim));

            Map<ChunkPos, List<BlockChange>> byChunk = new HashMap<ChunkPos, List<BlockChange>>();
            List<ChunkDiff> chunkDiffs = diff.getDimensions().get(dim);
            if (chunkDiffs != null) {
                for (ChunkDiff chunkDiff : chunkDiffs) {
                    byChunk.put(chunkDiff.getPos(), chunkDiff.getChanges());
                }
            }
            heldDiff.put(dim, byChunk);
        }

        rebuildOverlay();
    }

    /**
     * No-op. Per-chunk-load seeding is retired: the budgeted tick-poll over the bounded
     * {@link LevelAccess#loadedChunks()} (see {@link #tick}) catches chunks entering the overlay
     * radius as the player moves, while seeding render-edge chunks here would re-thrash the bounded
     * HeadBaselineCache. The signature is kept because the client wiring still calls it.
     */
    public void onChunkLoad(DimensionId dim, ChunkPos pos) {
        // intentionally empty — superseded by the budgeted tick-poll over bounded loadedChunks().
    }

    private void seedChunk(
            DimensionId dim, ChunkPos pos, List<BlockChange> changes, LevelAccess level) {
        cache.seed(dim, pos, changes, level);
        tracker.markChunk(dim, pos, level.minSectionY(), level.sectionCount());
    }

    /** A block in the live world changed; mark its section dirty for re-diff on the next tick. */
    public void onBlockChange(DimensionId dim, int x, int y, int z) {
        tracker.markBlock(dim, x, y, z);
    }

    /**
     * Seed up to {@link #SEED_BUDGET} not-yet-cached chunks among the (bounded) loaded set against
     * the held diff, then pop up to {@code sectionBudget} dirty sections, re-diff each against the
     * frozen HEAD, replace its accumulator entry (removing it when empty), and rebuild the overlay.
     * No-ops before the first diff or when no level is available.
     */
    public void tick(int sectionBudget) {
        if (!diffReceived) {
            return;
        }
        LevelAccess level = levelSupplier.get();
        if (level == null) {
            return;
        }
        seedMissingLoadedChunks(level, SEED_BUDGET);
        List<DirtySectionTracker.Section> batch = tracker.popBudget(sectionBudget);
        if (batch.isEmpty()) {
            rebuildOverlay();
            return;
        }
        for (DirtySectionTracker.Section section : batch) {
            List<BlockChange> changes = differ.diffSection(section, cache, level);
            if (changes.isEmpty()) {
                accumulator.remove(section);
            } else {
                accumulator.put(section, changes);
            }
        }
        rebuildOverlay();
    }

    /**
     * Seed up to {@code budget} of the current dimension's loaded chunks that have no frozen HEAD
     * baseline yet, using the held diff for that chunk (empty list if clean). Each seeded chunk is
     * marked dirty by {@link #seedChunk} so it is diffed by this or a later {@link #tick}.
     */
    private int seedMissingLoadedChunks(LevelAccess level, int budget) {
        DimensionId dim = level.dimension();
        Map<ChunkPos, List<BlockChange>> byChunk = heldDiff.get(dim);
        int seeded = 0;
        for (ChunkPos pos : level.loadedChunks()) {
            if (seeded >= budget) {
                break;
            }
            if (cache.hasChunk(dim, pos)) {
                continue;
            }
            List<BlockChange> ch = byChunk == null ? null : byChunk.get(pos);
            seedChunk(dim, pos, ch == null ? Collections.<BlockChange>emptyList() : ch, level);
            seeded++;
        }
        return seeded;
    }

    /** The overlay the renderer reads, or {@code null} before the first diff / after {@link #reset}. */
    public OverlayState currentOverlay() {
        return overlay;
    }

    /** Clear one dimension from cache/tracker/accumulator/heldDiff and rebuild. */
    public void dropDimension(DimensionId dim) {
        cache.dropDimension(dim);
        tracker.dropDimension(dim);
        accumulator.keySet().removeIf(s -> s.dimension().equals(dim));
        heldDiff.remove(dim);
        rebuildOverlay();
    }

    /** Full clear on disconnect/unsubscribe; {@link #currentOverlay} returns {@code null} after. */
    public void reset() {
        cache.dropAll();
        tracker.clear();
        accumulator.clear();
        heldDiff.clear();
        diffReceived = false;
        overlay = null;
    }

    private void rebuildOverlay() {
        if (!diffReceived) {
            overlay = null;
            return;
        }
        Map<DimensionId, Map<ChunkPos, List<BlockChange>>> grouped =
                new LinkedHashMap<DimensionId, Map<ChunkPos, List<BlockChange>>>();
        int add = 0, rem = 0, chg = 0;
        for (Map.Entry<DirtySectionTracker.Section, List<BlockChange>> e : accumulator.entrySet()) {
            DirtySectionTracker.Section s = e.getKey();
            Map<ChunkPos, List<BlockChange>> byChunk = grouped.get(s.dimension());
            if (byChunk == null) {
                byChunk = new LinkedHashMap<ChunkPos, List<BlockChange>>();
                grouped.put(s.dimension(), byChunk);
            }
            List<BlockChange> list = byChunk.get(s.chunk());
            if (list == null) {
                list = new ArrayList<BlockChange>();
                byChunk.put(s.chunk(), list);
            }
            for (BlockChange c : e.getValue()) {
                list.add(c);
                switch (c.getKind()) {
                    case ADD: add++; break;
                    case REMOVE: rem++; break;
                    default: chg++; break;
                }
            }
        }
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        for (Map.Entry<DimensionId, Map<ChunkPos, List<BlockChange>>> de : grouped.entrySet()) {
            List<ChunkDiff> chunkDiffs = new ArrayList<ChunkDiff>();
            for (Map.Entry<ChunkPos, List<BlockChange>> ce : de.getValue().entrySet()) {
                chunkDiffs.add(new ChunkDiff(ce.getKey(), ce.getValue()));
            }
            dims.put(de.getKey(), chunkDiffs);
        }
        WorldDiff worldDiff = new WorldDiff(dims, add, rem, chg);
        // receivedAt = 0L: expiry is opt-in via OverlayState.isExpired(now, lifetimeTicks); the
        // boxes()/visibleBoxes() path the renderer reads never consults the timestamp, so a 0 stamp
        // can never hide a live overlay. The renderer owns the live-overlay lifetime, not this stamp.
        overlay = new OverlayState(worldDiff, "HEAD", "WORKING", 0L);
    }
}
