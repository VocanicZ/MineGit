# Client-Side Live Diff (SP2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the diff overlay update in real time as the player edits the world, on both mod and plugin servers, by moving the live-diff computation onto the client and retiring the mod server's per-tick recompute.

**Architecture:** The server becomes a pure *snapshot pusher* — it sends the working-vs-HEAD diff only on subscribe and on HEAD-move (commit/checkout). The client freezes a per-chunk HEAD baseline reconstructed from that diff (`HEAD[pos] = diff.oldState(pos)` for dirty positions, captured client-world for clean ones), then each tick re-diffs its own `ClientLevel` against that frozen baseline over a budget of dirty sections. The frozen baseline is never rebuilt from the live world — that invariant is the correctness crux (a clean block the player edits must still diff against its pre-edit HEAD).

**Tech Stack:** Java 21, Architectury multiloader (mod/common pure logic + mod/fabric & mod/neoforge impls via `@ExpectPlatform`), JUnit 5 (Jupiter) headless tests in `mod/common/src/test`, core value types `net.rainbowcreation.vocanicz.minegit.core.model.{WorldDiff,ChunkDiff,BlockChange,BlockState,ChunkPos,DimensionId}`.

---

## Spec & predecessor

- **Spec:** `docs/superpowers/specs/2026-06-07-client-live-diff-design.md` (read §1–§3 before starting; §4 is the test plan this plan implements).
- **Predecessor (shipped):** SP1 transport parity — `docs/superpowers/specs/2026-06-07-transport-parity-design.md`. The plugin already pushes on subscribe + HEAD-move; the mod server's permission gate (`ServerCommandRuntime.onControl` / `onControlInner`) already exists. SP2 **rebases on that gate — it does not re-add it.**

## Design decisions locked for this plan (concrete choices the spec left to implementation)

These resolve the spec's abstract components into exact data structures. Implement them as written.

1. **Reuse `LevelAccess`, don't invent a new readback interface.** The pure engine (`HeadBaselineCache`, `LiveDiffer`, `ClientDiffEngine`) reads blocks through the existing `net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess` (`getBlock(x,y,z)`, `loadedChunks()`, `dimension()`, `minSectionY()`, `sectionCount()`). Tests inject `FakeLevelAccess`. Production injects a new `ClientLevelAccess` wrapping `ClientLevel`.
2. **Air is the absence marker.** `BlockState.AIR` (`new BlockState("minecraft:air")`). Baselines store only non-air HEAD states; an absent entry means HEAD is air there. A dirty `ADD` position (no `oldState`) means HEAD was air → store nothing.
3. **Section addressing.** A section is `(ChunkPos, sectionY)` where `sectionY ∈ [minSectionY, minSectionY+sectionCount)`. Local block index packs `(dx,dy,dz)` each `0..15` as `((dy & 15) << 8) | ((dz & 15) << 4) | (dx & 15)` → `0..4095`.
4. **Seed captures HEAD densely per non-empty section, then overlays dirty.** On seed, for each section of the chunk: snapshot every non-air live block as HEAD; then overlay each dirty position with its true HEAD (`ADD → air/remove`, `REMOVE/CHANGE → oldState`). Sections with no non-air live block **and** no dirty position store nothing (HEAD all-air). This is the freeze; it bounds storage to roughly the solid volume and is never recomputed from the live world afterward.
5. **The live diff per section** compares frozen HEAD vs current live block at every one of the 4096 local positions and emits: `head==live` → nothing; `head==AIR,live!=AIR` → `ADD(live)`; `head!=AIR,live==AIR` → `REMOVE(head)`; both non-air & differ → `CHANGE(head,live)`. The full-section scan is bounded by the per-tick section budget.
6. **The engine output is a `WorldDiff`**, wrapped by `OverlayClientState` into the same `OverlayState(diff, "HEAD", "WORKING", now)` the renderer already consumes via `current().visibleBoxes(...)`. No renderer change.
7. **LRU bound:** `HeadBaselineCache` caps chunk entries (default `256`); eviction drops the chunk's boxes rather than rebuilding from live. Tunable; see spec §6.

## File structure

**New (mod/common, pure, headless-tested):**
- `mod/common/.../overlay/live/SectionAddr.java` — packing/keys helper.
- `mod/common/.../overlay/live/HeadBaselineCache.java` — frozen per-chunk HEAD, LRU.
- `mod/common/.../overlay/live/DirtySectionTracker.java` — event-dirty section set + budget pop.
- `mod/common/.../overlay/live/LiveDiffer.java` — pure per-section compare.
- `mod/common/.../overlay/live/ClientDiffEngine.java` — owns the three above; seed/reset/tick/currentOverlay.

**New (platform seams — concrete code, user verifies in-game):**
- `mod/common/.../overlay/ClientLevelAccess.java` — `@ExpectPlatform` factory returning a `LevelAccess` over the live client world.
- `mod/fabric/.../overlay/fabric/ClientLevelAccessImpl.java`, `mod/neoforge/.../overlay/neoforge/ClientLevelAccessImpl.java`.
- `mod/common/.../overlay/ClientWorldHooks.java` — `@ExpectPlatform` to register block-change + chunk-load listeners.
- `mod/fabric/.../overlay/fabric/ClientWorldHooksImpl.java`, `mod/neoforge/.../overlay/neoforge/ClientWorldHooksImpl.java`.
- `mod/fabric/.../mixin/client/ClientLevelChunkMixin.java`, `mod/neoforge/.../mixin/client/ClientLevelChunkMixin.java` (+ mixin json wiring) — funnel `LevelChunk.setBlockState` on the client to the listener.

**Modified:**
- `mod/common/.../overlay/OverlayClientState.java` — store→compute flip.
- `mod/common/.../overlay/OverlayClientHooks.java` — wire engine tick + client seams.
- `mod/common/.../net/LiveSubscriptionLoop.java` — retire per-tick recompute/dedupe; keep registry + push; add HEAD-move push primitive.
- `mod/common/.../command/ServerCommandRuntime.java` — drop per-tick `live.tick(...)` recompute; push on commit/checkout completion.

**Tests touched/added:** `mod/common/src/test/.../overlay/live/*Test.java` (new), `OverlayClientStateTest`, `LiveSubscriptionLoopTest`, `ServerCommandRuntimeLiveRefreshTest` (modified), plus a GameTest for the retired loop.

> **Implementer note:** Signatures below were transcribed from a code survey, not copy-pasted from source. **Before writing each task, open the real files named in that task and confirm exact method names/constructors** (e.g. `ChunkDiff.getChanges()`, `WorldDiff.getDimensions()`, `BlockChange.add/remove/change`, `OverlayState(WorldDiff,String,String,long)`, `LevelAccess` methods). Adjust the code to match; the structure stays.

---

## Phase A — Pure live-diff engine (mod/common, headless, AFK)

### Task A1: `SectionAddr` — packing & section-range helpers

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/SectionAddr.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/SectionAddrTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SectionAddrTest {

    @Test
    void packIsBijectiveOverLocalRange() {
        for (int dy = 0; dy < 16; dy++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int packed = SectionAddr.pack(dx, dy, dz);
                    assertEquals(dx, SectionAddr.localX(packed));
                    assertEquals(dy, SectionAddr.localY(packed));
                    assertEquals(dz, SectionAddr.localZ(packed));
                }
            }
        }
    }

    @Test
    void sectionYForBlockYFloorsTowardNegative() {
        assertEquals(0, SectionAddr.sectionY(0));
        assertEquals(0, SectionAddr.sectionY(15));
        assertEquals(1, SectionAddr.sectionY(16));
        assertEquals(-1, SectionAddr.sectionY(-1));
        assertEquals(-4, SectionAddr.sectionY(-64));
    }

    @Test
    void localFromWorldWrapsCorrectlyForNegativeCoords() {
        assertEquals(0, SectionAddr.local(0));
        assertEquals(15, SectionAddr.local(-1));
        assertEquals(0, SectionAddr.local(-16));
    }
}
```

- [ ] **Step 2: Run test, verify it fails**

Run: `./gradlew :mod:common:test --tests "*.SectionAddrTest"`
Expected: FAIL — `SectionAddr` does not exist (compile error).

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

/** Local-position packing and section-Y arithmetic for the client live-diff engine. */
public final class SectionAddr {

    private SectionAddr() {}

    /** Packs a local block position (each component 0..15) into 0..4095. */
    public static int pack(int dx, int dy, int dz) {
        return ((dy & 15) << 8) | ((dz & 15) << 4) | (dx & 15);
    }

    public static int localX(int packed) {
        return packed & 15;
    }

    public static int localY(int packed) {
        return (packed >> 8) & 15;
    }

    public static int localZ(int packed) {
        return (packed >> 4) & 15;
    }

    /** Section index containing this world Y (floor-divide by 16). */
    public static int sectionY(int worldY) {
        return Math.floorDiv(worldY, 16);
    }

    /** Local 0..15 component of a world coordinate (floor-mod by 16). */
    public static int local(int world) {
        return Math.floorMod(world, 16);
    }
}
```

- [ ] **Step 4: Run test, verify it passes**

Run: `./gradlew :mod:common:test --tests "*.SectionAddrTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/SectionAddr.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/SectionAddrTest.java
git commit -m "feat(mod): SectionAddr packing helper for client live-diff engine"
git push
```

---

### Task A2: `HeadBaselineCache` — frozen per-chunk HEAD with LRU

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/HeadBaselineCache.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/HeadBaselineCacheTest.java`

**Behavior:** Keyed by `(DimensionId, ChunkPos)`. `seed(dim, chunkPos, dirtyChanges, level)` snapshots non-air live blocks of every section in the chunk's Y range, then overlays dirty positions with their true HEAD. `headAt(dim, x, y, z)` returns the frozen HEAD `BlockState` (AIR if absent). `dropDimension(dim)` and `dropAll()` clear. LRU caps chunk entries; eviction drops the chunk. **Never recomputed from live after seed.**

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import org.junit.jupiter.api.Test;

class HeadBaselineCacheTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    /** A FakeLevelAccess covering Y in [0,16) for one chunk at (0,0). */
    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        level.addLoadedChunk(0, 0);
        return level;
    }

    @Test
    void dirtyPositionTakesHeadFromOldState_notLiveWorld() {
        FakeLevelAccess level = world();
        // Live world (working tree) has STONE at (1,5,1) — a CHANGE from HEAD's DIRT.
        level.setBlock(1, 5, 1, STONE);
        List<BlockChange> dirty = Arrays.asList(BlockChange.change(1, 5, 1, DIRT, STONE));

        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), dirty, level);

        // HEAD at the dirty position is DIRT (oldState), NOT the live STONE.
        assertEquals(DIRT, cache.headAt(DIM, 1, 5, 1));
    }

    @Test
    void cleanPositionTakesHeadFromCapturedLiveWorld() {
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE); // clean: equals HEAD by definition at seed time
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), java.util.Collections.emptyList(), level);

        assertEquals(STONE, cache.headAt(DIM, 2, 5, 2));
    }

    @Test
    void addPositionHasAirHead() {
        FakeLevelAccess level = world();
        level.setBlock(3, 5, 3, STONE); // present in working
        List<BlockChange> dirty = Arrays.asList(BlockChange.add(3, 5, 3, STONE));
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), dirty, level);

        assertEquals(BlockState.AIR, cache.headAt(DIM, 3, 5, 3));
    }

    @Test
    void absentPositionIsAir() {
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), java.util.Collections.emptyList(), world());
        assertEquals(BlockState.AIR, cache.headAt(DIM, 9, 9, 9));
    }

    @Test
    void lruEvictionDropsOldestChunk() {
        HeadBaselineCache cache = new HeadBaselineCache(1); // cap = 1 chunk
        FakeLevelAccess level = world();
        level.addLoadedChunk(1, 0);
        level.setBlock(0, 5, 0, STONE);
        level.setBlock(16, 5, 0, DIRT);

        cache.seed(DIM, new ChunkPos(0, 0), java.util.Collections.emptyList(), level);
        cache.seed(DIM, new ChunkPos(1, 0), java.util.Collections.emptyList(), level);

        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0))); // evicted
        assertTrue(cache.hasChunk(DIM, new ChunkPos(1, 0)));
    }

    @Test
    void dropDimensionClearsThatDimensionOnly() {
        HeadBaselineCache cache = new HeadBaselineCache(256);
        FakeLevelAccess level = world();
        level.setBlock(0, 5, 0, STONE);
        cache.seed(DIM, new ChunkPos(0, 0), java.util.Collections.emptyList(), level);

        cache.dropDimension(DIM);
        assertFalse(cache.hasChunk(DIM, new ChunkPos(0, 0)));
    }
}
```

- [ ] **Step 2: Run, verify fail**

Run: `./gradlew :mod:common:test --tests "*.HeadBaselineCacheTest"`
Expected: FAIL — class missing.

- [ ] **Step 3: Implement**

```java
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

    /** Per-chunk frozen HEAD: sectionY -> (packedLocal -> non-air HEAD state). */
    private static final class ChunkBaseline {
        final Map<Integer, Map<Integer, BlockState>> sections = new HashMap<Integer, Map<Integer, BlockState>>();
    }

    private static final class Key {
        final DimensionId dim;
        final ChunkPos pos;

        Key(DimensionId dim, ChunkPos pos) {
            this.dim = dim;
            this.pos = pos;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return dim.equals(k.dim) && pos.equals(k.pos);
        }

        @Override public int hashCode() {
            return 31 * dim.hashCode() + pos.hashCode();
        }
    }

    private final int cap;
    private final LinkedHashMap<Key, ChunkBaseline> chunks;

    public HeadBaselineCache(int cap) {
        this.cap = cap;
        this.chunks = new LinkedHashMap<Key, ChunkBaseline>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, ChunkBaseline> eldest) {
                return size() > HeadBaselineCache.this.cap;
            }
        };
    }

    /**
     * Freeze HEAD for a chunk: snapshot non-air live blocks of every loaded section in range,
     * then overlay dirty positions with their true HEAD (ADD -> air/remove, else oldState).
     */
    public void seed(DimensionId dim, ChunkPos chunkPos, List<BlockChange> dirtyChanges, LevelAccess level) {
        ChunkBaseline baseline = new ChunkBaseline();
        int baseX = chunkPos.getCx() << 4;
        int baseZ = chunkPos.getCz() << 4;
        int minSec = level.minSectionY();
        int maxSec = minSec + level.sectionCount();

        for (int sy = minSec; sy < maxSec; sy++) {
            Map<Integer, BlockState> snapshot = null;
            for (int dy = 0; dy < 16; dy++) {
                int y = sy * 16 + dy;
                for (int dz = 0; dz < 16; dz++) {
                    for (int dx = 0; dx < 16; dx++) {
                        BlockState live = level.getBlock(baseX + dx, y, baseZ + dz);
                        if (live != null && !BlockState.AIR.equals(live)) {
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

        // Overlay dirty positions: their HEAD is the diff's oldState (air for ADD).
        for (BlockChange change : dirtyChanges) {
            int sy = SectionAddr.sectionY(change.getY());
            int packed = SectionAddr.pack(
                    SectionAddr.local(change.getX()),
                    SectionAddr.local(change.getY()),
                    SectionAddr.local(change.getZ()));
            BlockState head = headOf(change);
            Map<Integer, BlockState> section = baseline.sections.get(sy);
            if (head == null || BlockState.AIR.equals(head)) {
                if (section != null) {
                    section.remove(packed);
                }
            } else {
                if (section == null) {
                    section = new HashMap<Integer, BlockState>();
                    baseline.sections.put(sy, section);
                }
                section.put(packed, head);
            }
        }

        chunks.put(new Key(dim, chunkPos), baseline);
    }

    private static BlockState headOf(BlockChange change) {
        BlockState old = change.getOldState();
        return old != null ? old : BlockState.AIR;
    }

    /** Frozen HEAD at a world position; AIR if absent or chunk not seeded. */
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

    public boolean hasChunk(DimensionId dim, ChunkPos pos) {
        return chunks.containsKey(new Key(dim, pos));
    }

    public void dropDimension(DimensionId dim) {
        chunks.keySet().removeIf(k -> k.dim.equals(dim));
    }

    public void dropAll() {
        chunks.clear();
    }
}
```

- [ ] **Step 4: Run, verify pass**

Run: `./gradlew :mod:common:test --tests "*.HeadBaselineCacheTest"`
Expected: PASS.

> If `FakeLevelAccess`'s constructor or `addLoadedChunk`/`setBlock` signatures differ from the test, adjust the test to match the real stub (`mod/common/src/test/.../world/FakeLevelAccess.java`); do not change the stub's public API.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/HeadBaselineCache.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/HeadBaselineCacheTest.java
git commit -m "feat(mod): HeadBaselineCache frozen per-chunk HEAD with LRU"
git push
```

---

### Task A3: `DirtySectionTracker` — event-dirty section set + budget pop

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/DirtySectionTracker.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/DirtySectionTrackerTest.java`

**Behavior:** Holds a set of dirty `(DimensionId, ChunkPos, sectionY)` keys. `markBlock(dim,x,y,z)` marks the containing section. `markChunk(dim, chunkPos, minSec, secCount)` marks every section of a chunk (bulk updates / initial seed). `popBudget(n)` removes and returns up to `n` dirty sections. `dropDimension`/`clear`.

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class DirtySectionTrackerTest {

    private static final DimensionId DIM = DimensionId.OVERWORLD;

    @Test
    void markBlockMarksContainingSectionOnce() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 3, 70, 5);   // section (0,0), sy=4
        t.markBlock(DIM, 4, 71, 6);   // same section -> deduped
        assertEquals(1, t.size());
    }

    @Test
    void popBudgetReturnsAtMostNAndRemovesThem() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 0, 0, 0);
        t.markBlock(DIM, 0, 16, 0);
        t.markBlock(DIM, 0, 32, 0);
        List<DirtySectionTracker.Section> first = t.popBudget(2);
        assertEquals(2, first.size());
        assertEquals(1, t.size());
        List<DirtySectionTracker.Section> second = t.popBudget(2);
        assertEquals(1, second.size());
        assertEquals(0, t.size());
    }

    @Test
    void markChunkMarksEverySectionInRange() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markChunk(DIM, new ChunkPos(0, 0), 0, 4); // sy 0..3
        assertEquals(4, t.size());
    }

    @Test
    void dropDimensionClearsOnlyThatDimension() {
        DirtySectionTracker t = new DirtySectionTracker();
        t.markBlock(DIM, 0, 0, 0);
        t.markBlock(DimensionId.THE_NETHER, 0, 0, 0);
        t.dropDimension(DIM);
        assertEquals(1, t.size());
        assertTrue(t.popBudget(8).get(0).dimension().equals(DimensionId.THE_NETHER));
    }
}
```

- [ ] **Step 2: Run, verify fail.** `./gradlew :mod:common:test --tests "*.DirtySectionTrackerTest"` → FAIL (missing class).

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/** Event-driven dirty-section set for the client live differ (Spec SP2 §2b). */
public final class DirtySectionTracker {

    /** A single dirty section address. */
    public static final class Section {
        private final DimensionId dimension;
        private final ChunkPos chunk;
        private final int sectionY;

        public Section(DimensionId dimension, ChunkPos chunk, int sectionY) {
            this.dimension = dimension;
            this.chunk = chunk;
            this.sectionY = sectionY;
        }

        public DimensionId dimension() { return dimension; }
        public ChunkPos chunk() { return chunk; }
        public int sectionY() { return sectionY; }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Section)) return false;
            Section s = (Section) o;
            return sectionY == s.sectionY && dimension.equals(s.dimension) && chunk.equals(s.chunk);
        }

        @Override public int hashCode() {
            int h = dimension.hashCode();
            h = 31 * h + chunk.hashCode();
            h = 31 * h + sectionY;
            return h;
        }
    }

    private final Set<Section> dirty = new LinkedHashSet<Section>();

    public void markBlock(DimensionId dim, int x, int y, int z) {
        dirty.add(new Section(dim, new ChunkPos(x >> 4, z >> 4), SectionAddr.sectionY(y)));
    }

    public void markChunk(DimensionId dim, ChunkPos chunk, int minSectionY, int sectionCount) {
        for (int i = 0; i < sectionCount; i++) {
            dirty.add(new Section(dim, chunk, minSectionY + i));
        }
    }

    /** Remove and return up to {@code n} dirty sections (insertion order). */
    public List<Section> popBudget(int n) {
        List<Section> out = new ArrayList<Section>(Math.min(n, dirty.size()));
        Iterator<Section> it = dirty.iterator();
        while (it.hasNext() && out.size() < n) {
            out.add(it.next());
            it.remove();
        }
        return out;
    }

    public void dropDimension(DimensionId dim) {
        dirty.removeIf(s -> s.dimension.equals(dim));
    }

    public void clear() {
        dirty.clear();
    }

    public int size() {
        return dirty.size();
    }
}
```

- [ ] **Step 4: Run, verify pass.** `./gradlew :mod:common:test --tests "*.DirtySectionTrackerTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/DirtySectionTracker.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/DirtySectionTrackerTest.java
git commit -m "feat(mod): DirtySectionTracker event-dirty set with budget pop"
git push
```

---

### Task A4: `LiveDiffer` — pure per-section compare (the freeze guard)

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/LiveDiffer.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/LiveDifferTest.java`

**Behavior:** `diffSection(section, cache, level)` scans all 4096 local positions, compares frozen HEAD (`cache.headAt`) vs live (`level.getBlock`), and returns the `List<BlockChange>` for that section (ADD/REMOVE/CHANGE per design decision 5). Pure — no Minecraft types.

- [ ] **Step 1: Write the failing test** (includes the §3 freeze-survives-edit regression test)

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import org.junit.jupiter.api.Test;

class LiveDifferTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1); // Y in [0,16)
        level.addLoadedChunk(0, 0);
        return level;
    }

    private static DirtySectionTracker.Section section0() {
        return new DirtySectionTracker.Section(DIM, new ChunkPos(0, 0), 0);
    }

    @Test
    void unchangedSectionYieldsNoBoxes() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);

        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertTrue(changes.isEmpty());
    }

    @Test
    void freezeSurvivesEdit_cleanBlockEditRaisesBox_baselineUnchanged() {
        // THE §3 CORRECTNESS GUARD (regression for the rejected rebuild-on-load bug).
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE); // clean at seed
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);

        // Player edits the previously-clean block: STONE -> DIRT in the live world.
        level.setBlock(2, 5, 2, DIRT);
        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);

        assertEquals(1, changes.size());
        BlockChange c = changes.get(0);
        assertEquals(BlockChange.Kind.CHANGE, c.getKind());
        assertEquals(STONE, c.getOldState()); // baseline still pre-edit HEAD
        assertEquals(DIRT, c.getNewState());
        // And the baseline itself did NOT change:
        assertEquals(STONE, cache.headAt(DIM, 2, 5, 2));
    }

    @Test
    void placingIntoAirHeadEmitsAdd() {
        FakeLevelAccess level = world();
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level); // all air HEAD
        level.setBlock(4, 5, 4, STONE);

        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertEquals(1, changes.size());
        assertEquals(BlockChange.Kind.ADD, changes.get(0).getKind());
        assertEquals(STONE, changes.get(0).getNewState());
    }

    @Test
    void breakingHeadBlockEmitsRemove() {
        FakeLevelAccess level = world();
        level.setBlock(6, 5, 6, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);
        level.setBlock(6, 5, 6, BlockState.AIR); // broken in working

        List<BlockChange> changes = new LiveDiffer().diffSection(section0(), cache, level);
        assertEquals(1, changes.size());
        assertEquals(BlockChange.Kind.REMOVE, changes.get(0).getKind());
        assertEquals(STONE, changes.get(0).getOldState());
    }

    @Test
    void revertedEditClearsBox() {
        FakeLevelAccess level = world();
        level.setBlock(7, 5, 7, STONE);
        HeadBaselineCache cache = new HeadBaselineCache(256);
        cache.seed(DIM, new ChunkPos(0, 0), Collections.emptyList(), level);

        level.setBlock(7, 5, 7, DIRT); // edited
        assertEquals(1, new LiveDiffer().diffSection(section0(), cache, level).size());
        level.setBlock(7, 5, 7, STONE); // reverted to HEAD
        assertTrue(new LiveDiffer().diffSection(section0(), cache, level).isEmpty());
    }
}
```

- [ ] **Step 2: Run, verify fail.** `./gradlew :mod:common:test --tests "*.LiveDifferTest"` → FAIL.

- [ ] **Step 3: Implement**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import java.util.ArrayList;
import java.util.List;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/** Pure per-section compare of the live world against the frozen HEAD baseline (Spec SP2 §2c). */
public final class LiveDiffer {

    /** Returns the block changes (live vs frozen HEAD) for every position in the section. */
    public List<BlockChange> diffSection(
            DirtySectionTracker.Section section, HeadBaselineCache cache, LevelAccess level) {
        List<BlockChange> out = new ArrayList<BlockChange>();
        int baseX = section.chunk().getCx() << 4;
        int baseZ = section.chunk().getCz() << 4;
        int baseY = section.sectionY() * 16;

        for (int dy = 0; dy < 16; dy++) {
            int y = baseY + dy;
            for (int dz = 0; dz < 16; dz++) {
                int z = baseZ + dz;
                for (int dx = 0; dx < 16; dx++) {
                    int x = baseX + dx;
                    BlockState head = cache.headAt(section.dimension(), x, y, z);
                    BlockState live = level.getBlock(x, y, z);
                    if (live == null) {
                        live = BlockState.AIR;
                    }
                    boolean headAir = BlockState.AIR.equals(head);
                    boolean liveAir = BlockState.AIR.equals(live);
                    if (headAir && liveAir) {
                        continue;
                    }
                    if (head.equals(live)) {
                        continue;
                    }
                    if (headAir) {
                        out.add(BlockChange.add(x, y, z, live));
                    } else if (liveAir) {
                        out.add(BlockChange.remove(x, y, z, head));
                    } else {
                        out.add(BlockChange.change(x, y, z, head, live));
                    }
                }
            }
        }
        return out;
    }
}
```

- [ ] **Step 4: Run, verify pass.** `./gradlew :mod:common:test --tests "*.LiveDifferTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/LiveDiffer.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/LiveDifferTest.java
git commit -m "feat(mod): LiveDiffer pure section compare (freeze-survives-edit guard)"
git push
```

---

### Task A5: `ClientDiffEngine` — coordinator (seed ordering, reset, budgeted tick, overlay output)

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/ClientDiffEngine.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/ClientDiffEngineTest.java`

**Behavior:** Owns the cache, tracker, differ, and an accumulator of per-section live changes. The level is supplied lazily (`Supplier<LevelAccess>`) because the client world swaps on dimension change/reconnect; a `null` supply means no world this tick.

API:
- `onServerDiff(WorldDiff diff)` — the **seed/reset** path. Drops baselines for the dimensions the diff covers, holds the diff as the latest, seeds every currently-loaded chunk that the diff touches *plus* every loaded chunk in those dimensions (so clean sections get a HEAD), marks all seeded chunks dirty, clears stale accumulator entries for reset dimensions. Records `fromRef`/`toRef` is unnecessary (engine always emits HEAD/WORKING). **Seed ordering rule:** baselines are only created from a server diff; a chunk loaded before the first diff is seeded when the diff arrives, a chunk loaded after is seeded via `onChunkLoad`.
- `onChunkLoad(DimensionId dim, ChunkPos pos)` — if a diff has been received, seed this chunk against the held diff's changes for it and mark it dirty. If no diff yet, ignore (the eventual `onServerDiff` seeds loaded chunks).
- `onBlockChange(DimensionId dim, int x, int y, int z)` — `tracker.markBlock(...)`.
- `tick(int sectionBudget)` — pop up to `sectionBudget` dirty sections, re-diff each, replace its accumulator entry; rebuild the cached `OverlayState`.
- `currentOverlay()` — the `OverlayState` the renderer reads (or `null` before first diff).
- `reset()` — full clear on disconnect/unsubscribe.

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockChange;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkDiff;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.OverlayState;
import net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess;
import org.junit.jupiter.api.Test;

class ClientDiffEngineTest {

    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final DimensionId DIM = DimensionId.OVERWORLD;

    private static FakeLevelAccess world() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        level.addLoadedChunk(0, 0);
        return level;
    }

    private static WorldDiff diffWith(BlockChange... changes) {
        Map<DimensionId, List<ChunkDiff>> dims = new LinkedHashMap<DimensionId, List<ChunkDiff>>();
        dims.put(DIM, Arrays.asList(new ChunkDiff(new ChunkPos(0, 0), Arrays.asList(changes))));
        int add = 0, rem = 0, chg = 0;
        for (BlockChange c : changes) {
            switch (c.getKind()) {
                case ADD: add++; break;
                case REMOVE: rem++; break;
                default: chg++; break;
            }
        }
        return new WorldDiff(dims, add, rem, chg);
    }

    private static int totalBoxes(OverlayState state) {
        return state == null ? 0 : state.boxes(DIM).size();
    }

    @Test
    void noOverlayBeforeFirstDiff() {
        FakeLevelAccess level = world();
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.tick(8);
        assertNull(engine.currentOverlay());
    }

    @Test
    void serverDiffSeedsAndReproducesTheServerDiffAtPushInstant() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE); // working has the change present
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);

        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64); // drain all dirty sections

        OverlayState overlay = engine.currentOverlay();
        assertNotNull(overlay);
        assertEquals(1, totalBoxes(overlay)); // identical to the server diff at the push instant
    }

    @Test
    void liveEditAfterSeedRaisesBoxWithNoServerTraffic() {
        FakeLevelAccess level = world();
        level.setBlock(2, 5, 2, STONE); // clean at seed
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith()); // empty diff, seeds the loaded chunk's HEAD
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay()));

        // Player edits the clean block; engine learns via onBlockChange, no new diff.
        level.setBlock(2, 5, 2, DIRT);
        engine.onBlockChange(DIM, 2, 5, 2);
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));
    }

    @Test
    void headMoveResetDropsStaleBoxesAndReseeds() {
        FakeLevelAccess level = world();
        level.setBlock(3, 5, 3, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(3, 5, 3, DIRT, STONE)));
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay()));

        // Commit: HEAD now matches working; server pushes an empty diff.
        engine.onServerDiff(diffWith());
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay())); // stale box cleared
    }

    @Test
    void chunkLoadedAfterDiffIsSeededAgainstHeldDiff() {
        FakeLevelAccess level = new FakeLevelAccess(DIM, 0, 1);
        // chunk (0,0) NOT loaded yet when the diff arrives.
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        assertEquals(0, totalBoxes(engine.currentOverlay())); // nothing to seed yet

        // Now the chunk loads; working has the changed block present.
        level.addLoadedChunk(0, 0);
        level.setBlock(1, 5, 1, STONE);
        engine.onChunkLoad(DIM, new ChunkPos(0, 0));
        engine.tick(64);
        assertEquals(1, totalBoxes(engine.currentOverlay())); // seeded HEAD=DIRT, live=STONE -> box
    }

    @Test
    void resetClearsEverything() {
        FakeLevelAccess level = world();
        level.setBlock(1, 5, 1, STONE);
        ClientDiffEngine engine = new ClientDiffEngine(256, () -> level);
        engine.onServerDiff(diffWith(BlockChange.change(1, 5, 1, DIRT, STONE)));
        engine.tick(64);
        engine.reset();
        assertNull(engine.currentOverlay());
    }
}
```

- [ ] **Step 2: Run, verify fail.** `./gradlew :mod:common:test --tests "*.ClientDiffEngineTest"` → FAIL.

- [ ] **Step 3: Implement**

```java
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
 * Client live-diff coordinator (Spec SP2 §2d). Owns the frozen baseline, the dirty tracker, and
 * the differ; turns server snapshot pushes into a frozen HEAD baseline, then re-diffs the live
 * client world each tick over a budget of dirty sections and emits the overlay the renderer reads.
 */
public final class ClientDiffEngine {

    private final HeadBaselineCache cache;
    private final DirtySectionTracker tracker = new DirtySectionTracker();
    private final LiveDiffer differ = new LiveDiffer();
    private final Supplier<LevelAccess> levelSupplier;

    /** Per-section live changes, the assembled live diff. Keyed by dirty section. */
    private final Map<DirtySectionTracker.Section, List<BlockChange>> accumulator =
            new LinkedHashMap<DirtySectionTracker.Section, List<BlockChange>>();

    /** Held server diff per dimension, indexed by chunk, for seeding late-loaded chunks. */
    private final Map<DimensionId, Map<ChunkPos, List<BlockChange>>> heldDiff =
            new HashMap<DimensionId, Map<ChunkPos, List<BlockChange>>>();

    private boolean diffReceived;
    private OverlayState overlay;

    public ClientDiffEngine(int cacheCap, Supplier<LevelAccess> levelSupplier) {
        this.cache = new HeadBaselineCache(cacheCap);
        this.levelSupplier = levelSupplier;
    }

    /** Seed/reset path: a fresh working-vs-HEAD snapshot from the server. */
    public void onServerDiff(WorldDiff diff) {
        diffReceived = true;
        LevelAccess level = levelSupplier.get();

        for (Map.Entry<DimensionId, List<ChunkDiff>> dimEntry : diff.getDimensions().entrySet()) {
            DimensionId dim = dimEntry.getKey();
            // Reset prior baselines/accumulator/tracker for this dimension (HEAD-move semantics).
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
            // Seed every loaded chunk in this dimension (so clean sections get a frozen HEAD too),
            // overlaying any dirty changes the diff carries for that chunk.
            for (ChunkPos pos : level.loadedChunks()) {
                List<BlockChange> changes = byChunk.get(pos);
                seedChunk(dim, pos, changes == null ? Collections.emptyList() : changes, level);
            }
        }
        rebuildOverlay();
    }

    /** A chunk became loaded after a diff was received: seed it against the held diff. */
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
        seedChunk(dim, pos, changes == null ? Collections.emptyList() : changes, level);
        rebuildOverlay();
    }

    private void seedChunk(DimensionId dim, ChunkPos pos, List<BlockChange> changes, LevelAccess level) {
        cache.seed(dim, pos, changes, level);
        tracker.markChunk(dim, pos, level.minSectionY(), level.sectionCount());
    }

    /** A client block changed: mark its section for re-diff. */
    public void onBlockChange(DimensionId dim, int x, int y, int z) {
        tracker.markBlock(dim, x, y, z);
    }

    /** Re-diff up to {@code sectionBudget} dirty sections and refresh the overlay. */
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

    public OverlayState currentOverlay() {
        return overlay;
    }

    public void dropDimension(DimensionId dim) {
        cache.dropDimension(dim);
        tracker.dropDimension(dim);
        accumulator.keySet().removeIf(s -> s.dimension().equals(dim));
        heldDiff.remove(dim);
        rebuildOverlay();
    }

    public void reset() {
        cache.dropAll();
        tracker.clear();
        accumulator.clear();
        heldDiff.clear();
        diffReceived = false;
        overlay = null;
    }

    /** Assemble the accumulator into the OverlayState the renderer consumes. */
    private void rebuildOverlay() {
        if (!diffReceived) {
            overlay = null;
            return;
        }
        // Group accumulated changes per dimension/chunk into a WorldDiff.
        Map<DimensionId, Map<ChunkPos, List<BlockChange>>> grouped =
                new LinkedHashMap<DimensionId, Map<ChunkPos, List<BlockChange>>>();
        int add = 0, rem = 0, chg = 0;
        for (Map.Entry<DirtySectionTracker.Section, List<BlockChange>> e : accumulator.entrySet()) {
            DirtySectionTracker.Section s = e.getKey();
            Map<ChunkPos, List<BlockChange>> byChunk =
                    grouped.computeIfAbsent(s.dimension(), k -> new LinkedHashMap<ChunkPos, List<BlockChange>>());
            List<BlockChange> list = byChunk.computeIfAbsent(s.chunk(), k -> new ArrayList<BlockChange>());
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
        overlay = new OverlayState(worldDiff, "HEAD", "WORKING", 0L);
    }
}
```

> **Confirm against source:** `OverlayState` constructor arity/order (`WorldDiff, fromRef, toRef, receivedAt`) and `boxes(DimensionId)`; `WorldDiff(Map, int, int, int)`; `ChunkDiff(ChunkPos, List)`. Adjust `0L` receivedAt if the renderer's expiry logic needs a real tick (the live overlay should never expire — verify `OverlayState.isExpired` is not consulted on the compute path, or pass a sentinel that never expires).

- [ ] **Step 4: Run, verify pass.** `./gradlew :mod:common:test --tests "*.ClientDiffEngineTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/ClientDiffEngine.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/live/ClientDiffEngineTest.java
git commit -m "feat(mod): ClientDiffEngine seed/reset/budgeted-tick coordinator"
git push
```

---

## Phase B — Store→compute flip (mod/common)

### Task B1: `OverlayClientState` — feed the engine, compute the overlay

**Files:**
- Modify: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientState.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientStateTest.java` (modify)

**Behavior change:** `acceptFrame(...)` still reassembles frames, but on completion it decodes the payload to a `WorldDiff` and calls `engine.onServerDiff(diff)` instead of storing an `OverlayState`. `current()` returns `engine.currentOverlay()`. `shouldRender()` returns true when subscribed/visible, a dimension is known, and `engine.currentOverlay()` has boxes. `onClientTick`-driven `tickEngine(budget)` delegates to `engine.tick(budget)`. The engine needs a `LevelAccess` supplier and a chunk-load/block-change feed — those arrive from Phase C; for now inject the supplier via a setter defaulting to `() -> null` so headless tests pass a `FakeLevelAccess`.

- [ ] **Step 1: Read the current file**

Run: open `mod/common/.../overlay/OverlayClientState.java` and `OverlayClientStateTest.java`. Note the existing fields (`reassembler`, `current`, `visible`, `subscribed`, `activeDimension`), `acceptFrame`, `current()`, `shouldRender()`, `setActiveDimension`, `onDisconnect`, `clear`, `toggleSubscription`.

- [ ] **Step 2: Write/adjust the failing test** — drive the compute behavior

```java
// Add to OverlayClientStateTest. Reuses the existing payload()/feedAll() helpers in the file.

@Test
void acceptFrameSeedsEngineAndComputesOverlayFromLiveWorld() {
    // Build a world whose working tree matches the sample diff (a CHANGE present in working).
    net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess level =
            new net.rainbowcreation.vocanicz.minegit.mod.world.FakeLevelAccess(
                    net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD, 0, 1);
    level.addLoadedChunk(0, 0);
    // sampleDiff()'s CHANGE entry is at (3,64,3) DIRT->STONE in the existing helper; mirror it:
    level.setBlock(3, 64, 3, new net.rainbowcreation.vocanicz.minegit.core.model.BlockState("minecraft:stone"));

    OverlayClientState state = new OverlayClientState();
    state.setLevelSupplier(() -> level);
    state.setActiveDimension(net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD);

    feedAll(state, payload(), Framing.DEFAULT_MAX_FRAME_BYTES, 0L);
    state.tickEngine(64); // drain the budget

    assertNotNull(state.current());
    assertTrue(state.current().boxes(
            net.rainbowcreation.vocanicz.minegit.core.model.DimensionId.OVERWORLD).size() >= 1);
}

@Test
void onDisconnectResetsEngine() {
    OverlayClientState state = new OverlayClientState();
    state.onDisconnect();
    assertNull(state.current());
}
```

> If the existing `payload()`/`sampleDiff()` helper uses different coordinates/states, set the live `FakeLevelAccess` block to match the working-tree (newState) of one CHANGE/ADD entry so a box is produced. The point is: frames in → engine seeded → tick → boxes out.

- [ ] **Step 3: Run, verify fail.** `./gradlew :mod:common:test --tests "*.OverlayClientStateTest"` → FAIL (no `setLevelSupplier`/`tickEngine`; `current()` no longer set by acceptFrame).

- [ ] **Step 4: Implement the flip.** Replace the stored-state mechanics with engine delegation. Key edits:

```java
// Fields: drop `private OverlayState current;` — replace with the engine + a level supplier.
private final java.util.function.Supplier<LevelAccess> DEFAULT_SUPPLIER = () -> null;
private java.util.function.Supplier<LevelAccess> levelSupplier = DEFAULT_SUPPLIER;
private ClientDiffEngine engine = new ClientDiffEngine(256, () -> levelSupplier.get());
private Reassembler reassembler = new Reassembler();
private boolean visible;
private boolean subscribed;
private DimensionId activeDimension;

/** Injected by the client wiring (Phase C); headless tests pass a FakeLevelAccess. */
public void setLevelSupplier(java.util.function.Supplier<LevelAccess> supplier) {
    this.levelSupplier = supplier == null ? DEFAULT_SUPPLIER : supplier;
}

public Optional<OverlayState> acceptFrame(byte[] frameBytes, long now) {
    Optional<byte[]> payload = reassembler.add(Frame.fromBytes(frameBytes));
    if (!payload.isPresent()) {
        return Optional.empty();
    }
    WorldDiff diff = DiffPayload.decode(payload.get()); // seed/reset data only — never rendered directly
    engine.onServerDiff(diff);
    visible = true;
    return Optional.ofNullable(engine.currentOverlay());
}

/** Drives the LiveDiffer budget; called from OverlayClientHooks.onClientTick. */
public void tickEngine(int sectionBudget) {
    engine.tick(sectionBudget);
}

/** Forwarded from the client world hooks (Phase C). */
public void onClientBlockChange(DimensionId dim, int x, int y, int z) {
    engine.onBlockChange(dim, x, y, z);
}

public void onClientChunkLoad(DimensionId dim, ChunkPos pos) {
    engine.onChunkLoad(dim, pos);
}

public OverlayState current() {
    return engine.currentOverlay();
}

public boolean shouldRender() {
    if (!visible || activeDimension == null) {
        return false;
    }
    OverlayState overlay = engine.currentOverlay();
    return overlay != null && !overlay.boxes(activeDimension).isEmpty();
}

public void setActiveDimension(DimensionId dim) {
    if (activeDimension != null && !activeDimension.equals(dim)) {
        engine.dropDimension(activeDimension); // leaving a dimension drops its baselines (Spec §3)
    }
    this.activeDimension = dim;
}

public void onDisconnect() {
    subscribed = false;
    visible = false;
    activeDimension = null;
    reassembler = new Reassembler();
    engine.reset();
}

public void clear() {
    reassembler = new Reassembler();
    engine.reset();
    visible = false;
}
```

Keep `toggleSubscription(...)`, `isVisible()`, `isSubscribed()`, `activeDimension()` as they were; on UNSUBSCRIBE call `engine.reset()` where it previously cleared the held overlay.

> Add imports: `ClientDiffEngine`, `LevelAccess`, `WorldDiff`, `DiffPayload`, `ChunkPos`. Confirm `DiffPayload.decode(byte[])` returns `WorldDiff` (it does per the codec).

- [ ] **Step 5: Run the whole overlay test package.** `./gradlew :mod:common:test --tests "*.overlay.*"` → PASS. Fix any other test that asserted the old "stores pushed OverlayState" behavior by reframing it through the engine (seed a `FakeLevelAccess` + `tickEngine`).

- [ ] **Step 6: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientState.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientStateTest.java
git commit -m "refactor(mod): OverlayClientState store->compute via ClientDiffEngine"
git push
```

---

### Task B2: `OverlayClientHooks.onClientTick` — drive the engine budget

**Files:**
- Modify: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientHooks.java`

**Behavior:** In `init()`, after wiring the receive sink, inject the client level supplier and register the client world hooks (Phase C seams), and in `onClientTick`, after dimension tracking and keybind drain, call `OverlayClientState.CLIENT.tickEngine(SECTION_BUDGET)`.

- [ ] **Step 1: Add the budget constant + supplier wiring in `init()`**

```java
private static final int SECTION_BUDGET = 8; // dirty sections re-diffed per client tick (TickPump-style)

// inside init(), after DiffChannel.setClientHandler(...):
OverlayClientState.CLIENT.setLevelSupplier(ClientLevelAccess::current); // Phase C seam
ClientWorldHooks.register(                                              // Phase C seam
    (dim, x, y, z) -> OverlayClientState.CLIENT.onClientBlockChange(dim, x, y, z),
    (dim, pos)     -> OverlayClientState.CLIENT.onClientChunkLoad(dim, pos));
```

- [ ] **Step 2: Drive the budget in `onClientTick`**

```java
// inside onClientTick(client), AFTER setActiveDimension and the keybind drain loop:
OverlayClientState.CLIENT.tickEngine(SECTION_BUDGET);
```

- [ ] **Step 3: Compile guard.** This references `ClientLevelAccess` and `ClientWorldHooks`, created in Phase C. To keep the tree compiling between commits, **do Task C1 and C2 immediately before building**; if committing B2 alone, create the two `@ExpectPlatform` stub classes (Task C1/C2 Step "stub only") first so the symbol resolves. Plan executes C1/C2 stubs as part of this commit.

- [ ] **Step 4: Build.** `./gradlew :mod:common:compileJava` → success (with C1/C2 stubs present).

- [ ] **Step 5: Commit** (folded with C1/C2 stubs — see Phase C).

---

## Phase C — Client platform seams (concrete code; **user verifies in-game**)

> These are the only non-headless pieces. The logic they feed is fully tested in Phases A/B; here we wire the real `ClientLevel`. Correctness of the **mixin coverage** (bulk updates) is the spec §6 risk that the user's eyes-on matrix validates. Mirror the existing `@ExpectPlatform` pair pattern (`Platform`/`PlatformImpl`) and the server mixin (`mixin/LevelChunkMixin.java`).

### Task C1: `ClientLevelAccess` — read seam over the live client world

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/ClientLevelAccess.java`
- Create: `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/fabric/ClientLevelAccessImpl.java`
- Create: `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/neoforge/ClientLevelAccessImpl.java`

- [ ] **Step 1: Common `@ExpectPlatform` declaration**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

/** Read-only {@link LevelAccess} over the current client world, or null if none. */
public final class ClientLevelAccess {

    private ClientLevelAccess() {}

    @ExpectPlatform
    public static LevelAccess current() {
        throw new AssertionError("@ExpectPlatform stub — replaced by ClientLevelAccessImpl at build time");
    }
}
```

- [ ] **Step 2: Fabric impl** — wrap `Minecraft.getInstance().level` (a `ClientLevel`) as a `LevelAccess`. Reuse the existing `BlockStateBridge` (used by `ServerLevelAccess`) for `BlockState` conversion, and `DimensionMapping` for the dimension id. `setBlock` is unsupported (read-only overlay) — throw or no-op.

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.fabric;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos; // Minecraft's
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.mod.world.BlockStateBridge;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;
import net.rainbowcreation.vocanicz.minegit.mod.world.LevelAccess;

public final class ClientLevelAccessImpl {

    private ClientLevelAccessImpl() {}

    public static LevelAccess current() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return new Wrapper(level);
    }

    private static final class Wrapper implements LevelAccess {
        private final ClientLevel level;

        Wrapper(ClientLevel level) {
            this.level = level;
        }

        @Override public DimensionId dimension() {
            return DimensionMapping.fromKey(level.dimension().location().toString());
        }

        @Override public int minSectionY() {
            return level.getMinSection();
        }

        @Override public int sectionCount() {
            return level.getSectionsCount();
        }

        @Override public Set<net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos> loadedChunks() {
            Set<net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos> out = new HashSet<>();
            // ClientChunkCache has no public iterator; gather around the player's view distance.
            if (Minecraft.getInstance().player == null) {
                return out;
            }
            int viewDist = Minecraft.getInstance().options.getEffectiveRenderDistance();
            ChunkPos centre = Minecraft.getInstance().player.chunkPosition();
            for (int dz = -viewDist; dz <= viewDist; dz++) {
                for (int dx = -viewDist; dx <= viewDist; dx++) {
                    int cx = centre.x + dx, cz = centre.z + dz;
                    LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
                    if (chunk != null && !chunk.isEmpty()) {
                        out.add(new net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos(cx, cz));
                    }
                }
            }
            return out;
        }

        @Override public BlockState getBlock(int x, int y, int z) {
            return BlockStateBridge.toCore(level.getBlockState(new BlockPos(x, y, z)));
        }

        @Override public void setBlock(int x, int y, int z, BlockState state) {
            throw new UnsupportedOperationException("client overlay is read-only");
        }
    }
}
```

- [ ] **Step 3: NeoForge impl** — identical body, package `...overlay.neoforge`. (NeoForge and Fabric share the same Mojang-mapped `ClientLevel` API at 1.21.11, so the code is the same except the package.) Verify `getMinSection`/`getSectionsCount`/`getEffectiveRenderDistance` names against the 1.21.11 mappings in this repo; adjust if the deobf names differ.

- [ ] **Step 4: Build both loaders.** `./gradlew :mod:fabric:compileJava :mod:neoforge:compileJava` → success. (Architectury links the `@ExpectPlatform` stub to each `*Impl.current()`.)

- [ ] **Step 5: Commit** (folded with C2 + B2)

---

### Task C2: `ClientWorldHooks` — block-change & chunk-load feed

**Files:**
- Create: `mod/common/.../overlay/ClientWorldHooks.java`
- Create: `mod/fabric/.../overlay/fabric/ClientWorldHooksImpl.java`, `mod/fabric/.../mixin/client/ClientLevelChunkMixin.java` (+ register in fabric mixins json)
- Create: `mod/neoforge/.../overlay/neoforge/ClientWorldHooksImpl.java`, `mod/neoforge/.../mixin/client/ClientLevelChunkMixin.java` (+ register in neoforge mixins json)

- [ ] **Step 1: Common declaration + listener interfaces**

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/** Registers client-side block-change and chunk-load callbacks that feed the live differ. */
public final class ClientWorldHooks {

    private ClientWorldHooks() {}

    public interface BlockChangeListener {
        void onClientBlockChange(DimensionId dim, int x, int y, int z);
    }

    public interface ChunkLoadListener {
        void onClientChunkLoad(DimensionId dim, ChunkPos pos);
    }

    @ExpectPlatform
    public static void register(BlockChangeListener onBlock, ChunkLoadListener onChunk) {
        throw new AssertionError("@ExpectPlatform stub — replaced by ClientWorldHooksImpl at build time");
    }

    /** Static sink the client mixin calls into; set by the impl on register. */
    private static volatile BlockChangeListener blockSink;

    public static void fireBlockChange(DimensionId dim, int x, int y, int z) {
        BlockChangeListener sink = blockSink;
        if (sink != null) {
            sink.onClientBlockChange(dim, x, y, z);
        }
    }

    public static void setBlockSink(BlockChangeListener sink) {
        blockSink = sink;
    }
}
```

- [ ] **Step 2: Fabric `ClientLevelChunkMixin`** — mirror the server `LevelChunkMixin`, but gate on the *client* side and funnel to `ClientWorldHooks.fireBlockChange`. Per-block `setBlockState` covers single edits; section/bulk paths arrive via `ClientChunkCache.replaceWithPacketData` → those re-fire `setBlockState` per block in 1.21.11, but ALSO mark via chunk-load for safety.

```java
package net.rainbowcreation.vocanicz.minegit.mod.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldHooks;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class ClientLevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void minegit$markClientDirty(
            BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (!(level instanceof ClientLevel)) {
            return;
        }
        ClientWorldHooks.fireBlockChange(
                DimensionMapping.fromKey(level.dimension().location().toString()),
                pos.getX(), pos.getY(), pos.getZ());
    }
}
```

> Confirm the 1.21.11 `LevelChunk.setBlockState` descriptor matches the server mixin's (same method already injected server-side, so the signature is known-good). Register the mixin in `mod/fabric/src/main/resources/minegit.mixins.json` under the **client** `mixins` array (NOT `server` — see the `mixin-server-block-breaks-singleplayer` memory: client-side mixins must be in `mixins`/`client`, not `server`, or single-player hides them).

- [ ] **Step 3: Fabric impl** — register chunk-load via Fabric's `ClientChunkEvents.CHUNK_LOAD`, set the block sink.

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.fabric;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldHooks;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;

public final class ClientWorldHooksImpl {

    private ClientWorldHooksImpl() {}

    public static void register(
            ClientWorldHooks.BlockChangeListener onBlock, ClientWorldHooks.ChunkLoadListener onChunk) {
        ClientWorldHooks.setBlockSink(onBlock);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
                onChunk.onClientChunkLoad(
                        DimensionMapping.fromKey(level.dimension().location().toString()),
                        new ChunkPos(chunk.getPos().x, chunk.getPos().z)));
    }
}
```

- [ ] **Step 4: NeoForge impl + mixin** — same `ClientLevelChunkMixin` (package `...mixin.client`, registered in the neoforge mixins json client list). For chunk-load, use NeoForge's `ChunkEvent.Load` filtered to `level.isClientSide()`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.overlay.neoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.mod.overlay.ClientWorldHooks;
import net.rainbowcreation.vocanicz.minegit.mod.world.DimensionMapping;

public final class ClientWorldHooksImpl {

    private ClientWorldHooksImpl() {}

    public static void register(
            ClientWorldHooks.BlockChangeListener onBlock, ClientWorldHooks.ChunkLoadListener onChunk) {
        ClientWorldHooks.setBlockSink(onBlock);
        NeoForge.EVENT_BUS.addListener(ChunkEvent.Load.class, event -> {
            if (event.getLevel().isClientSide()) {
                onChunk.onClientChunkLoad(
                        DimensionMapping.fromKey(event.getLevel().dimensionType() == null
                                ? "minecraft:overworld"
                                : ((net.minecraft.world.level.Level) event.getLevel()).dimension().location().toString()),
                        new ChunkPos(event.getChunk().getPos().x, event.getChunk().getPos().z));
            }
        });
    }
}
```

> Verify `ChunkEvent.Load.getLevel()` returns a `LevelAccessor` whose `dimension()` is reachable on 1.21.11 NeoForge; simplify the dimension lookup to match the available API. The exact event is less critical than that *some* client chunk-load fires `onChunk`.

- [ ] **Step 5: Build both loaders + run all mod/common tests.**

Run: `./gradlew :mod:fabric:compileJava :mod:neoforge:compileJava :mod:common:test`
Expected: compile success; all headless tests PASS.

- [ ] **Step 6: Commit (B2 + C1 + C2 together — the seam wiring lands as one coherent unit)**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/OverlayClientHooks.java \
        mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/ClientLevelAccess.java \
        mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/ClientWorldHooks.java \
        mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/fabric/ \
        mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/client/ \
        mod/fabric/src/main/resources/minegit.mixins.json \
        mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/overlay/neoforge/ \
        mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/client/ \
        mod/neoforge/src/main/resources/minegit.mixins.json
git commit -m "feat(mod): client world seams — ClientLevelAccess read + block/chunk dirty feed"
git push
```

---

## Phase D — Server: retire the per-tick recompute (mod/common)

### Task D1: `LiveSubscriptionLoop` — keep registry + push, drop the recompute

**Files:**
- Modify: `mod/common/.../net/LiveSubscriptionLoop.java`
- Test: `mod/common/.../net/LiveSubscriptionLoopTest.java` (modify)

**Behavior:** Remove the per-tick recompute (`tick(Poller)` recompute loop) and the `Map<UUID, WorldDiff> lastPushed` dedupe. Keep the subscriber `Set<UUID>`, `subscribe`/`unsubscribe`/`disconnect`/`isSubscribed`/`subscriberCount`, and the push primitive. Add `pushTo(ServerPlayer, UUID, WorldDiff)` (the HEAD-move push) and keep push-on-subscribe. The `Poller`/`Snapshot`/`refreshTicks`/`tickCounter` machinery is deleted.

- [ ] **Step 1: Update the test to the new contract**

```java
// In LiveSubscriptionLoopTest: delete tests asserting per-tick recompute/dedupe; replace with:

@Test
void subscribePushesOnceImmediately() {
    RecordingSink sink = new RecordingSink(); // existing test sink, or a small Sink stub
    LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
    ServerPlayerStub player = new ServerPlayerStub(UUID.randomUUID()); // existing stub or minimal
    loop.subscribe(player.handle(), player.id(), sampleDiff());
    assertEquals(1, sink.pushCount(player.id()));
}

@Test
void unsubscribeStopsFurtherPushes() {
    RecordingSink sink = new RecordingSink();
    LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
    UUID id = UUID.randomUUID();
    loop.subscribe(playerFor(id), id, sampleDiff());
    loop.unsubscribe(id);
    assertFalse(loop.isSubscribed(id));
    loop.pushTo(playerFor(id), id, sampleDiff()); // no-op when not subscribed
    assertEquals(1, sink.pushCount(id)); // only the subscribe push
}

@Test
void pushToSendsToSubscribersOnly() {
    RecordingSink sink = new RecordingSink();
    LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
    UUID id = UUID.randomUUID();
    loop.subscribe(playerFor(id), id, null); // null diff = no immediate push
    loop.pushTo(playerFor(id), id, sampleDiff());
    assertEquals(1, sink.pushCount(id));
}
```

> Reuse whatever sink/player stub `LiveSubscriptionLoopTest` already has. If the existing test built a `DiffOverlaySender.Sink`, keep that; the new tests only need: count pushes per UUID.

- [ ] **Step 2: Run, verify fail.** `./gradlew :mod:common:test --tests "*.LiveSubscriptionLoopTest"` → FAIL (old API references).

- [ ] **Step 3: Rewrite `LiveSubscriptionLoop`**

```java
package net.rainbowcreation.vocanicz.minegit.mod.net;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;

/**
 * Server-side overlay subscription registry + snapshot push (Spec SP2 §2e).
 *
 * <p>Snapshot model: the server pushes the working-vs-HEAD diff only on SUBSCRIBE and on HEAD-move
 * (commit/checkout completion). The per-tick recompute/dedupe of the original live loop is retired —
 * the CLIENT is now the live-diff engine (closes #93/#94/#100).
 */
public final class LiveSubscriptionLoop {

    private final DiffOverlaySender.Sink sink;
    private final Set<UUID> subscribers = new LinkedHashSet<UUID>();

    public LiveSubscriptionLoop() {
        this(DiffOverlaySender.channelSink());
    }

    public LiveSubscriptionLoop(DiffOverlaySender.Sink sink) {
        this.sink = sink;
    }

    /** Register a subscriber and push the current snapshot immediately (if provided). */
    public void subscribe(ServerPlayer player, UUID id, WorldDiff currentDiff) {
        Objects.requireNonNull(id, "id");
        subscribers.add(id);
        if (currentDiff != null) {
            DiffOverlaySender.send(player, currentDiff, "HEAD", "WORKING", sink);
        }
    }

    public void unsubscribe(UUID id) {
        subscribers.remove(id);
    }

    public void disconnect(UUID id) {
        subscribers.remove(id);
    }

    public boolean isSubscribed(UUID id) {
        return subscribers.contains(id);
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    /** HEAD-move push: re-send the fresh snapshot to a subscriber (no-op if not subscribed). */
    public int pushTo(ServerPlayer player, UUID id, WorldDiff diff) {
        if (!subscribers.contains(id) || diff == null) {
            return 0;
        }
        return DiffOverlaySender.send(player, diff, "HEAD", "WORKING", sink);
    }
}
```

- [ ] **Step 4: Run, verify pass.** `./gradlew :mod:common:test --tests "*.LiveSubscriptionLoopTest"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/net/LiveSubscriptionLoop.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/net/LiveSubscriptionLoopTest.java
git commit -m "refactor(mod): retire LiveSubscriptionLoop per-tick recompute — snapshot push only"
git push
```

---

### Task D2: `ServerCommandRuntime` — push on HEAD-move, drop the per-tick recompute call

**Files:**
- Modify: `mod/common/.../command/ServerCommandRuntime.java`
- Test: `mod/common/.../command/ServerCommandRuntimeLiveRefreshTest.java` (modify/replace)

**Behavior:** In `tick(MinecraftServer)`, remove the `live.tick(id -> pollSubscriber(...))` recompute call (keep `pump.pump()`). Remove `pollSubscriber`/the `Poller` lambda. After commit/checkout completion (`reportCommit`/`reportCheckout`, success branch), push a fresh working-vs-HEAD snapshot to every subscriber in that level — mirroring the plugin's `repushSubscribersIn`. Factor a private `pushHeadMove(MinecraftServer, String levelKey)`.

- [ ] **Step 1: Replace the live-refresh test with a HEAD-move push test**

```java
// ServerCommandRuntimeLiveRefreshTest -> assert HEAD-move push, not per-tick recompute.

@Test
void commitCompletionPushesFreshSnapshotToSubscribers() {
    // Arrange a runtime with a recording sink and a bound repo whose status() returns a known diff.
    // (Reuse the test's existing harness for building a ServerCommandRuntime with stubs.)
    TestRuntime rt = TestRuntime.boundWithDiff(sampleDiff());
    UUID id = UUID.randomUUID();
    rt.subscribe(id);                 // registers + initial push
    int before = rt.sink().pushCount(id);

    rt.completeCommit(id);            // invokes reportCommit success path -> pushHeadMove

    assertEquals(before + 1, rt.sink().pushCount(id));
}

@Test
void tickDoesNotPushBetweenHeadMoves() {
    TestRuntime rt = TestRuntime.boundWithDiff(sampleDiff());
    UUID id = UUID.randomUUID();
    rt.subscribe(id);
    int after = rt.sink().pushCount(id);
    for (int i = 0; i < 100; i++) {
        rt.tick();                    // pump only — no recompute/push
    }
    assertEquals(after, rt.sink().pushCount(id)); // unchanged: no per-tick push
}
```

> Adapt to the existing test scaffolding. The existing `ServerCommandRuntimeLiveRefreshTest` already builds a runtime with a sink and a stub repo for the per-tick test — reuse that harness; the only change is the assertion target (HEAD-move push vs per-tick recompute). If the harness can't directly call `reportCommit`, add a package-private `pushHeadMove(server, levelKey)` seam and test that.

- [ ] **Step 2: Run, verify fail.** `./gradlew :mod:common:test --tests "*.ServerCommandRuntimeLiveRefreshTest"` → FAIL.

- [ ] **Step 3: Edit `ServerCommandRuntime`**

```java
// tick(): drop the recompute, keep the pump.
public void tick(MinecraftServer server) {
    pump.pump();
    // (removed: live.tick(id -> pollSubscriber(server, id)); — client is now the live-diff engine)
}

// Delete pollSubscriber(...) and any Poller lambda.

// New HEAD-move push, called from the commit/checkout success paths.
private void pushHeadMove(MinecraftServer server, String levelKey) {
    if (server == null) {
        return;
    }
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
        if (!live.isSubscribed(player.getUUID())) {
            continue;
        }
        if (!levelKey.equals(levelKey(player.level()))) {
            continue; // only subscribers in the level whose HEAD moved
        }
        WorldDiff diff = currentDiffFor(player); // read-only working-vs-new-HEAD
        live.pushTo(player, player.getUUID(), diff);
    }
}
```

Wire it into both completion reporters (success branch only). The commit completion lambda already has the `server`/`levelKey` in scope at the call site:

```java
// In the commit command handler, after building the completion callback, capture the server:
MinecraftServer server = ctx.getSource().getServer();
service.commit(repoPath, live, clock, message, author, tracker, result -> {
    reportCommit(ctx.getSource(), levelKey, result);
    if (!result.isError()) {
        pushHeadMove(server, levelKey);
    }
});

// In the checkout handler:
MinecraftServer server = ctx.getSource().getServer();
service.checkout(repoPath, live, clock, target, force, dirtyScoped, result -> {
    if (!result.isError()) {
        tracker.prime();
    }
    reportCheckout(ctx.getSource(), capturedLevelKey, target, result);
    if (!result.isError()) {
        pushHeadMove(server, capturedLevelKey);
    }
});
```

> `reportCommit`/`reportCheckout` are currently `static`; `pushHeadMove` is an instance method using `live`/`currentDiffFor`/`levelKey`. Keep `pushHeadMove` as the instance call at the lambda site (the lambda already closes over `this`). Confirm `currentDiffFor(ServerPlayer)` and `levelKey(ServerLevel)` are reachable (they are — used by the deleted `pollSubscriber`).

- [ ] **Step 4: Run, verify pass.** `./gradlew :mod:common:test --tests "*.ServerCommandRuntime*"` → PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/ServerCommandRuntime.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/ServerCommandRuntimeLiveRefreshTest.java
git commit -m "feat(mod): push overlay snapshot on commit/checkout HEAD-move; drop per-tick recompute"
git push
```

---

### Task D3: GameTest — subscribe pushes once, no per-tick recompute

**Files:**
- Modify/Create: the existing both-loader GameTest suite (search `mod/common/.../gametest/` or the `CodeTest`/registered gametest class referenced in memory `minegit-gametest-1.21.11`).

**Behavior:** A GameTest that subscribes a fake player (or drives `ServerCommandRuntime.onControlInner` with `permitted=true`), asserts exactly one push occurred, then ticks the server N times and asserts no further push. This guards against accidentally reintroducing the loop.

- [ ] **Step 1: Add the gametest method** (mirror an existing registered gametest's structure — annotation, helper, `helper.succeed()`):

```java
// In the registered MineGit gametest class (both loaders run it via the shared registration).
@GameTest(template = "minegit:empty") // reuse whatever empty template the existing tests use
public void subscribePushesOnceAndSchedulesNoPerTickRecompute(GameTestHelper helper) {
    RecordingSink sink = new RecordingSink();
    LiveSubscriptionLoop loop = new LiveSubscriptionLoop(sink);
    UUID id = UUID.randomUUID();
    ServerPlayer player = helper.makeMockPlayer(); // or the existing mock-player helper

    loop.subscribe(player, id, TestDiffs.sample());
    helper.assertTrue(sink.pushCount(id) == 1, "subscribe must push exactly once");

    // No per-tick recompute exists anymore: ticking changes nothing.
    helper.runAfterDelay(20, () -> {
        helper.assertTrue(sink.pushCount(id) == 1, "no push must occur between HEAD-moves");
        helper.succeed();
    });
}
```

> If `LiveSubscriptionLoop` is better exercised as a plain unit (it now has no tick), this assertion may already be covered by Task D1's unit tests — in that case, instead add a **regression GameTest** asserting that after `ServerCommandRuntime.tick(server)` runs for 20 ticks with an active subscriber, the sink saw only the subscribe push. Keep whichever the existing gametest harness supports cleanly; do not invent a mock-player API that isn't in the repo.

- [ ] **Step 2: Run both loaders' GameTests.**

Run: `./gradlew :mod:fabric:runGametest :mod:neoforge:runGametest` (use the exact gametest task names this repo defines — check `build.gradle`/memory `minegit-gametest-1.21.11`).
Expected: "All N passed" on both loaders.

- [ ] **Step 3: Commit**

```bash
git add mod/common/src/test/... # the gametest file(s)
git commit -m "test(mod): gametest guard — subscribe pushes once, no per-tick recompute"
git push
```

---

## Final verification (AFK gate — before handing back to the user)

- [ ] **Full build + all tests, both loaders:**

```bash
./gradlew build
```
Expected: BUILD SUCCESSFUL; both loaders' GameTests "All passed"; all `mod:common` + `plugin` unit tests green.

- [ ] **Self-check the retirement is clean:** grep for leftover references to the removed API.

```bash
grep -rn "pollSubscriber\|lastPushed\|refreshTicks\|DEFAULT_REFRESH_TICKS\|\.tick(.*poll" mod/
```
Expected: no hits in `src/main` (only historical references in comments/docs are acceptable).

- [ ] **Request holistic review** via `superpowers:requesting-code-review` (mirror SP1's final review): confirm (1) the engine never recomputes a frozen baseline from live; (2) the only two server push sites are subscribe + HEAD-move; (3) the render path is unchanged (`current().visibleBoxes`); (4) client mixin sits in the client mixin list, not server.

- [ ] **Update memory:** rewrite `resume-active-thread.md` (SP2 headless SHIPPED; NEXT = user manual matrix) and the `MEMORY.md` index line.

---

## Handoff to the user (eyes-on — cannot be automated)

After the AFK gate is green, the overlay's *liveness* can only be confirmed in-game. Tell the user up front: **SP2 verification is eyes-on, exactly like SP1's spike.** The manual matrix (spec §4, both loaders, against a Paper plugin server AND a mod server):

1. Subscribe (J) → existing working changes show as boxes.
2. **Edit a block (no commit) → a box appears/updates live within ~1 tick.** ← the headline SP2 behavior.
3. Break a HEAD block → REMOVE box; place into air → ADD box; revert an edit → its box clears.
4. `/mg commit` → committed boxes clear.
5. `/mg checkout <ref>` → boxes swap to the new HEAD's working set.
6. Walk away from an edited chunk and return (within the LRU cap) → its box persists.
7. **Parity:** the same sequence behaves identically on a mod server and a plugin server.
8. **Bulk-update coverage (spec §6 risk):** trigger a large paste/fill/relight near a subscribed view and confirm no stale boxes linger — if any do, the client mixin missed a bulk path; report which operation.

## Optional follow-up (deferred from SP1 review — not blocking)

`MineGitCommand.repushSubscribersIn`/`pushCurrentDiff` (plugin) and the new mod `pushHeadMove` each re-open the repo and recompute the diff on the main thread, moments after the commit/checkout service already read the working tree. A later cleanup can have the service hand the post-op `WorldDiff` straight to the push callback. Out of scope for SP2; note in memory.
