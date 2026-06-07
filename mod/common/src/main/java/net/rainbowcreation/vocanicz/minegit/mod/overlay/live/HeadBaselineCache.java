package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/**
 * Frozen per-chunk HEAD reconstruction for the client live-diff overlay (Spec SP2 §2a).
 *
 * <p>A seeded chunk stores, per non-empty section, the HEAD block state of every non-air
 * position. The frozen value is NEVER recomputed from the live world after seeding — that
 * invariant is the correctness crux (a clean block later edited still diffs vs its pre-edit HEAD).
 * Bounded by an LRU cap over chunk entries; eviction drops the chunk (its boxes disappear).
 */
public final class HeadBaselineCache {

    private static final class ChunkBaseline {
        final Map<Integer, Map<Integer, BlockState>> sections =
                new HashMap<Integer, Map<Integer, BlockState>>();
    }

    private static final class Key {
        final DimensionId dim;
        final ChunkPos pos;

        Key(DimensionId dim, ChunkPos pos) {
            this.dim = dim;
            this.pos = pos;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return dim.equals(k.dim) && pos.equals(k.pos);
        }

        @Override
        public int hashCode() {
            return 31 * dim.hashCode() + pos.hashCode();
        }
    }

    private final int cap;
    private final LinkedHashMap<Key, ChunkBaseline> chunks;

    public HeadBaselineCache(int cap) {
        this.cap = cap;
        this.chunks = new LinkedHashMap<Key, ChunkBaseline>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, ChunkBaseline> eldest) {
                return size() > HeadBaselineCache.this.cap;
            }
        };
    }

    /**
     * Seeds (or re-seeds) the HEAD baseline for {@code chunkPos} in {@code dim}.
     *
     * <p>First captures a live-world snapshot of every non-air block in the chunk's Y range, then
     * overlays the dirty changes so the stored value reflects true HEAD state (not working-tree
     * state). After this call the chunk is never re-read from the live world.
     */
    public void seed(
            DimensionId dim,
            ChunkPos chunkPos,
            List<BlockChange> dirtyChanges,
            LevelAccess level) {
        ChunkBaseline baseline = new ChunkBaseline();
        int baseX = chunkPos.getCx() << 4;
        int baseZ = chunkPos.getCz() << 4;
        int minSec = level.minSectionY();
        int maxSec = minSec + level.sectionCount();

        // Step 1: snapshot non-air live blocks (= working-tree state)
        for (int sy = minSec; sy < maxSec; sy++) {
            Map<Integer, BlockState> snapshot = null;
            for (int dy = 0; dy < 16; dy++) {
                int y = sy * 16 + dy;
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        BlockState live = level.getBlock(baseX + dx, y, baseZ + dz);
                        if (!BlockState.AIR.equals(live)) {
                            if (snapshot == null) {
                                snapshot = new HashMap<Integer, BlockState>();
                            }
                            snapshot.put(SectionAddr.pack(dx, dy, dz), live);
                        }
                    }
                }
            }
            if (snapshot != null) {
                baseline.sections.put(sy, snapshot);
            }
        }

        // Step 2: overlay dirty positions with their true HEAD value
        for (BlockChange change : dirtyChanges) {
            int sy = SectionAddr.sectionY(change.getY());
            int packed = SectionAddr.pack(
                    SectionAddr.local(change.getX()),
                    SectionAddr.local(change.getY()),
                    SectionAddr.local(change.getZ()));
            BlockState head = headOf(change);
            Map<Integer, BlockState> section = baseline.sections.get(sy);
            if (BlockState.AIR.equals(head)) {
                // HEAD is air — remove from map
                if (section != null) {
                    section.remove(packed);
                }
            } else {
                // HEAD is non-air — store it
                if (section == null) {
                    section = new HashMap<Integer, BlockState>();
                    baseline.sections.put(sy, section);
                }
                section.put(packed, head);
            }
        }

        chunks.put(new Key(dim, chunkPos), baseline);
    }

    /** Returns the HEAD state from the change: oldState for CHANGE/REMOVE, AIR for ADD. */
    private static BlockState headOf(BlockChange change) {
        BlockState old = change.getOldState();
        return old != null ? old : BlockState.AIR;
    }

    /**
     * Returns the frozen HEAD block state at world {@code (x, y, z)} in {@code dim}.
     * Returns {@link BlockState#AIR} if the chunk is not seeded or the position was air at HEAD.
     */
    public BlockState headAt(DimensionId dim, int x, int y, int z) {
        ChunkBaseline baseline = chunks.get(new Key(dim, new ChunkPos(x >> 4, z >> 4)));
        if (baseline == null) {
            return BlockState.AIR;
        }
        Map<Integer, BlockState> section = baseline.sections.get(SectionAddr.sectionY(y));
        if (section == null) {
            return BlockState.AIR;
        }
        BlockState head = section.get(
                SectionAddr.pack(SectionAddr.local(x), SectionAddr.local(y), SectionAddr.local(z)));
        return head != null ? head : BlockState.AIR;
    }

    /** Returns {@code true} if the chunk at {@code (dim, pos)} has been seeded. */
    public boolean hasChunk(DimensionId dim, ChunkPos pos) {
        return chunks.containsKey(new Key(dim, pos));
    }

    /** Drops all seeded chunks for the given dimension. */
    public void dropDimension(DimensionId dim) {
        chunks.keySet().removeIf(k -> k.dim.equals(dim));
    }

    /** Drops all seeded chunks across all dimensions. */
    public void dropAll() {
        chunks.clear();
    }
}
