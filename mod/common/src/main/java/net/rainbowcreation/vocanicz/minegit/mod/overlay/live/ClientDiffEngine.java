package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
     * baselines/tracker-entries/accumulator-entries (HEAD-move reset semantics), index its chunk
     * changes, and — if a level is available — seed every currently-loaded chunk of that dimension
     * against its changes (clean chunks seed with empty changes so their HEAD is captured), marking
     * each seeded chunk dirty. Marks {@code diffReceived} and rebuilds the overlay.
     */
    public void onServerDiff(WorldDiff diff) {
        diffReceived = true;
        LevelAccess level = levelSupplier.get();
        for (Map.Entry<DimensionId, List<ChunkDiff>> dimEntry : diff.getDimensions().entrySet()) {
            DimensionId dim = dimEntry.getKey();
            cache.dropDimension(dim);
            tracker.dropDimension(dim);
            accumulator.keySet().removeIf(s -> s.dimension().equals(dim));

            Map<ChunkPos, List<BlockChange>> byChunk = new HashMap<ChunkPos, List<BlockChange>>();
            for (ChunkDiff chunkDiff : dimEntry.getValue()) {
                byChunk.put(chunkDiff.getPos(), chunkDiff.getChanges());
            }
            heldDiff.put(dim, byChunk);

            if (level == null) {
                continue;
            }
            for (ChunkPos pos : level.loadedChunks()) {
                List<BlockChange> changes = byChunk.get(pos);
                seedChunk(dim, pos, changes == null ? Collections.<BlockChange>emptyList() : changes,
                        level);
            }
        }
        rebuildOverlay();
    }

    /**
     * A chunk just loaded. If a diff has been received, seed this chunk against the held diff's
     * changes for it (empty if none) and mark it dirty, then rebuild. If no diff yet, ignore — the
     * eventual {@link #onServerDiff} seeds all loaded chunks.
     */
    public void onChunkLoad(DimensionId dim, ChunkPos pos) {
        if (!diffReceived) {
            return;
        }
        LevelAccess level = levelSupplier.get();
        if (level == null) {
            return;
        }
        Map<ChunkPos, List<BlockChange>> byChunk = heldDiff.get(dim);
        List<BlockChange> changes = byChunk == null ? null : byChunk.get(pos);
        seedChunk(dim, pos, changes == null ? Collections.<BlockChange>emptyList() : changes, level);
        rebuildOverlay();
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
     * Pop up to {@code sectionBudget} dirty sections, re-diff each against the frozen HEAD, replace
     * its accumulator entry (removing it when empty), and rebuild the overlay. No-ops before the
     * first diff or when no level is available.
     */
    public void tick(int sectionBudget) {
        if (!diffReceived) {
            return;
        }
        LevelAccess level = levelSupplier.get();
        if (level == null) {
            return;
        }
        List<DirtySectionTracker.Section> batch = tracker.popBudget(sectionBudget);
        if (batch.isEmpty()) {
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
