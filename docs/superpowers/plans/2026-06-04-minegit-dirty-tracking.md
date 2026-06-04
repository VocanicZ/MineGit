# MineGit Incremental Commits (Event-Based Dirty Tracking) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/mg commit`, `/mg status`, and `/mg diff` incremental — read only the chunks that actually changed since the last commit, instead of the whole loaded world.

**Architecture:** A Minecraft-free `DirtyChunkSet` (per world/level) is fed by world events — a `setBlockState` mixin on the mod (Fabric + NeoForge), Bukkit event listeners on the plugin. A per-server `DirtyTrackerRegistry` keyed by level/world holds these sets so they outlive any single command. Commit/status/diff read the dirty set (`drainDirty` clears, `peekDirty` does not). A once-per-session "primed" reconciliation does one full pass first (to catch changes made while untracked, e.g. across a restart), then goes incremental. The repo's working-tree-of-`.mgc` model already makes git incremental at the blob level, so writing only the changed `.mgc` files yields an incremental commit.

**Tech Stack:** Java (core = Java 8 bytecode, mod = Java 21), JGit, Gradle, JUnit 5 (core + mod), Mockito 4.11 (plugin), Architectury (Fabric + NeoForge), Mixins (loom), Fabric/NeoForge GameTests.

**Base package:** `net.rainbowcreation.vocanicz.minegit`

**Branch:** create `feat/dirty-tracking` before Task 1 (do NOT work on `master`).

---

## File Structure

**core** (Minecraft-free):
- Create `core/.../core/adapter/DirtyChunkSet.java` — thread-safe dirty set + primed flag.
- Modify `core/.../core/adapter/WorldAdapter.java` — add `peekDirty()`.
- Create `core/.../core/diff/LiveDirtyChunkSource` (inside `ChunkSources.java`) + `ChunkSources.liveDirty(adapter)`.
- Modify `core/.../core/diff/WorldDiffer.java` — add `diffWorkingTreeDirty(repo, adapter)`.
- Modify implementors: `core/.../core/fake/FakeWorldAdapter.java` (testFixtures).

**mod**:
- Create `mod/common/.../mod/world/DirtyTrackerRegistry.java` — per-server level→`DirtyChunkSet`.
- Modify `mod/common/.../mod/world/ModWorldAdapter.java` — read a `DirtyChunkSet`.
- Modify `mod/common/.../mod/world/SnapshotWorldAdapter.java` — add `peekDirty()`.
- Modify `mod/common/.../mod/world/CommitService.java` — capture dirty-only when primed.
- Modify `mod/common/.../mod/command/ServerCommandRuntime.java` + `MineGitService.java` + `MineGitCommands.java` — primed reconciliation, dirty-aware status/diff, `--full`/`rescan`.
- Create mixin in BOTH `mod/fabric` and `mod/neoforge`: `MinegitLevelChunkMixin` + `minegit.mixins.json` + loom mixin wiring + a way to reach the registry.
- Create `mod/.../gametest` test for one-block→one-chunk.

**plugin**:
- Modify `plugin/.../plugin/world/BukkitWorldAdapter.java` — read a `DirtyChunkSet`.
- Modify `plugin/.../plugin/world/SnapshotWorldAdapter.java` — add `peekDirty()`.
- Modify `plugin/.../plugin/world/CommitService.java` — capture dirty-only when primed.
- Create `plugin/.../plugin/world/WorldDirtyRegistry.java` — per-server world→`DirtyChunkSet`.
- Create `plugin/.../plugin/listener/BlockChangeListener.java` — Bukkit events → markDirty.
- Modify `plugin/.../plugin/MineGitPlugin.java` + `command/MineGitCommand.java` — register listener, primed reconciliation, `--full`/`rescan`.

---

## Task 1: core `DirtyChunkSet` + `WorldAdapter.peekDirty()`

**Files:**
- Create: `core/src/main/java/net/rainbowcreation/vocanicz/minegit/core/adapter/DirtyChunkSet.java`
- Modify: `core/src/main/java/net/rainbowcreation/vocanicz/minegit/core/adapter/WorldAdapter.java`
- Modify: `core/src/testFixtures/java/net/rainbowcreation/vocanicz/minegit/core/fake/FakeWorldAdapter.java`
- Modify: `mod/common/.../mod/world/ModWorldAdapter.java`, `mod/common/.../mod/world/SnapshotWorldAdapter.java`
- Modify: `plugin/.../plugin/world/BukkitWorldAdapter.java`, `plugin/.../plugin/world/SnapshotWorldAdapter.java`
- Test: `core/src/test/java/net/rainbowcreation/vocanicz/minegit/core/adapter/DirtyChunkSetTest.java`

- [ ] **Step 1: Write the failing test for `DirtyChunkSet`**

Create `core/src/test/java/net/rainbowcreation/vocanicz/minegit/core/adapter/DirtyChunkSetTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.core.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DirtyChunkSetTest {

    private static ChunkRef ref(int cx, int cz) {
        return new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(cx, cz));
    }

    @Test
    void peekReturnsMarkedRefsWithoutClearing() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(0, 0));
        set.markDirty(ref(1, 2));
        assertEquals(2, set.peekDirty().size());
        // peek does not clear: a second peek still sees them.
        assertEquals(2, set.peekDirty().size());
    }

    @Test
    void markIsIdempotent() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(0, 0));
        set.markDirty(ref(0, 0));
        assertEquals(1, set.peekDirty().size());
    }

    @Test
    void drainReturnsRefsAndClears() {
        DirtyChunkSet set = new DirtyChunkSet();
        set.markDirty(ref(3, 4));
        Set<ChunkRef> drained = set.drainDirty();
        assertEquals(1, drained.size());
        assertTrue(drained.contains(ref(3, 4)));
        assertTrue(set.peekDirty().isEmpty(), "drain must clear the set");
    }

    @Test
    void primedFlagStartsFalseAndCanBeSet() {
        DirtyChunkSet set = new DirtyChunkSet();
        assertFalse(set.isPrimed());
        set.prime();
        assertTrue(set.isPrimed());
        set.unprime();
        assertFalse(set.isPrimed());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:test --tests 'net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSetTest'`
Expected: FAIL — `DirtyChunkSet` does not exist (compile error).

- [ ] **Step 3: Implement `DirtyChunkSet`**

Create `core/src/main/java/net/rainbowcreation/vocanicz/minegit/core/adapter/DirtyChunkSet.java`:

```java
package net.rainbowcreation.vocanicz.minegit.core.adapter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The set of chunks changed since the last commit, for one world/level. World events (off any thread)
 * call {@link #markDirty}; commands (on the server thread) {@link #peekDirty} (non-clearing, for
 * status/diff) or {@link #drainDirty} (clearing, for commit).
 *
 * <p>The {@link #isPrimed() primed} flag drives once-per-session reconciliation: until the first full
 * pass has run this session the set's contents are incomplete (e.g. after a restart), so callers do a
 * full scan and then {@link #prime()}.
 *
 * <p>Over-marking is safe (the engine dedupes by content); under-marking is the risk the event/mixin
 * capture must avoid.
 */
public final class DirtyChunkSet {

    private final Set<ChunkRef> dirty = ConcurrentHashMap.newKeySet();
    private volatile boolean primed;

    /** Marks {@code ref}'s chunk dirty. Thread-safe; idempotent. */
    public void markDirty(ChunkRef ref) {
        dirty.add(Objects.requireNonNull(ref, "ref"));
    }

    /** A snapshot of the dirty refs. Does NOT clear — use for status/diff. */
    public Set<ChunkRef> peekDirty() {
        return new HashSet<ChunkRef>(dirty);
    }

    /** A snapshot of the dirty refs, clearing the set. Use for commit. */
    public Set<ChunkRef> drainDirty() {
        Set<ChunkRef> snapshot = new HashSet<ChunkRef>(dirty);
        dirty.removeAll(snapshot);
        return Collections.unmodifiableSet(snapshot);
    }

    /** True once this session's full reconciliation pass has run. */
    public boolean isPrimed() {
        return primed;
    }

    /** Marks the reconciliation pass done (subsequent ops go incremental). */
    public void prime() {
        primed = true;
    }

    /** Forces another reconciliation pass on the next op (e.g. {@code /mg rescan}). */
    public void unprime() {
        primed = false;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests 'net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSetTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Add `peekDirty()` to `WorldAdapter` and all implementors**

In `core/.../core/adapter/WorldAdapter.java`, add this method right after `drainDirty()` (around line 41):

```java
    /**
     * The chunks marked dirty since the previous drain, <strong>without</strong> clearing the set, so
     * status/diff can inspect pending changes that a later {@link #drainDirty()} (commit) still needs.
     */
    Set<ChunkRef> peekDirty();
```

Now add a compiling `peekDirty()` to each implementor. For implementors that don't yet have real tracking (this task), return the same value their current `drainDirty()` returns so behavior is unchanged until their wiring task.

`core/.../core/fake/FakeWorldAdapter.java` — it has a `Set<ChunkRef> dirty` field; add a non-clearing peek (insert after `drainDirty()`):

```java
    @Override
    public Set<ChunkRef> peekDirty() {
        return new java.util.HashSet<ChunkRef>(dirty);
    }
```

`mod/common/.../mod/world/SnapshotWorldAdapter.java` — add (returns the captured set, mirroring its `drainDirty`):

```java
    @Override
    public Set<ChunkRef> peekDirty() {
        return allChunks();
    }
```

`mod/common/.../mod/world/ModWorldAdapter.java` — add (unchanged-behavior placeholder until Task 3):

```java
    @Override
    public Set<ChunkRef> peekDirty() {
        return allChunks();
    }
```

`plugin/.../plugin/world/SnapshotWorldAdapter.java` — add:

```java
    @Override
    public Set<ChunkRef> peekDirty() {
        return allChunks();
    }
```

`plugin/.../plugin/world/BukkitWorldAdapter.java` — add (unchanged-behavior placeholder until Task 5):

```java
    @Override
    public Set<ChunkRef> peekDirty() {
        return allChunks();
    }
```

Ensure each file imports `java.util.Set` (most already do).

- [ ] **Step 6: Build everything to verify the interface change compiles**

Run: `./gradlew compileJava compileTestJava testFixturesClasses :mod:common:compileJava :plugin:compileJava`
Expected: BUILD SUCCESSFUL (every `WorldAdapter` now implements `peekDirty()`).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(core): DirtyChunkSet + WorldAdapter.peekDirty() (Spec E task 1)"
```

---

## Task 2: core dirty-aware diff (`ChunkSources.liveDirty` + `WorldDiffer.diffWorkingTreeDirty`)

**Files:**
- Modify: `core/src/main/java/net/rainbowcreation/vocanicz/minegit/core/diff/ChunkSources.java`
- Modify: `core/src/main/java/net/rainbowcreation/vocanicz/minegit/core/diff/WorldDiffer.java`
- Test: `core/src/test/java/net/rainbowcreation/vocanicz/minegit/core/diff/DiffWorkingTreeDirtyTest.java`

**Context:** `WorldDiffer.diff(a, b)` unions positions from both sources per dimension. `ChunkSources.live(adapter)` enumerates positions via `adapter.allChunks()`. We add a `liveDirty` source that enumerates via `adapter.peekDirty()` instead, and a `diffWorkingTreeDirty` that diffs it against HEAD. A chunk absent from the dirty set is HEAD by construction, so it contributes nothing — correct as long as the dirty set is complete since the last commit (guaranteed by the primed reconciliation in later tasks).

- [ ] **Step 1: Write the failing test**

Create `core/src/test/java/net/rainbowcreation/vocanicz/minegit/core/diff/DiffWorkingTreeDirtyTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.core.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.rainbowcreation.vocanicz.minegit.core.fake.FakeWorldAdapter;
import net.rainbowcreation.vocanicz.minegit.core.git.MineGitRepo;
import net.rainbowcreation.vocanicz.minegit.core.model.BlockState;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.core.model.WorldDiff;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiffWorkingTreeDirtyTest {

    private static final BlockState STONE = BlockState.of("minecraft:stone");

    @Test
    void dirtyDiffMatchesFullDiffForAChangedChunk(@TempDir Path dir) {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 0, 0, STONE);
        MineGitRepo repo = MineGitRepo.init(dir, world);
        repo.commit("base", "tester");
        // drain so the dirty set reflects only post-commit changes
        world.drainDirty();

        // Change one block in chunk (0,0).
        world.setBlock(DimensionId.OVERWORLD, 1, 0, 0, STONE);

        WorldDiff full = WorldDiffer.diffWorkingTree(repo, world);
        WorldDiff dirty = WorldDiffer.diffWorkingTreeDirty(repo, world);
        assertEquals(full.getAdded(), dirty.getAdded());
        assertEquals(full.getRemoved(), dirty.getRemoved());
        assertEquals(full.getChanged(), dirty.getChanged());
    }

    @Test
    void dirtyDiffIsEmptyWhenNothingMarked(@TempDir Path dir) {
        FakeWorldAdapter world = new FakeWorldAdapter();
        world.setBlock(DimensionId.OVERWORLD, 0, 0, 0, STONE);
        MineGitRepo repo = MineGitRepo.init(dir, world);
        repo.commit("base", "tester");
        world.drainDirty();

        WorldDiff dirty = WorldDiffer.diffWorkingTreeDirty(repo, world);
        assertEquals(0, dirty.getAdded());
        assertEquals(0, dirty.getRemoved());
        assertEquals(0, dirty.getChanged());
    }
}
```

NOTE: if `BlockState.of(String)` is not the exact factory, use the same construction the existing `core` diff tests use (check `core/src/test/.../diff/` for the helper). Keep the test's intent identical.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :core:test --tests 'net.rainbowcreation.vocanicz.minegit.core.diff.DiffWorkingTreeDirtyTest'`
Expected: FAIL — `WorldDiffer.diffWorkingTreeDirty` does not exist.

- [ ] **Step 3: Add `liveDirty` to `ChunkSources`**

In `ChunkSources.java`, add a public factory next to `live(...)`:

```java
    /**
     * Like {@link #live(WorldAdapter)} but enumerates only the chunks the adapter currently reports
     * {@linkplain WorldAdapter#peekDirty() dirty}. Drives incremental status/diff.
     */
    public static ChunkSource liveDirty(WorldAdapter adapter) {
        return new LiveDirtyChunkSource(adapter);
    }
```

And add the inner class (mirror `LiveChunkSource` but source positions from `peekDirty()`):

```java
    /** Live source whose positions come from {@link WorldAdapter#peekDirty()}. */
    private static final class LiveDirtyChunkSource implements ChunkSource {
        private final WorldAdapter adapter;

        LiveDirtyChunkSource(WorldAdapter adapter) {
            this.adapter = adapter;
        }

        @Override
        public java.util.Set<DimensionId> dimensions() {
            return adapter.dimensions();
        }

        @Override
        public java.util.Set<ChunkPos> chunks(DimensionId dimension) {
            java.util.Set<ChunkPos> out = new java.util.HashSet<ChunkPos>();
            for (ChunkRef ref : adapter.peekDirty()) {
                if (ref.getDimension().equals(dimension)) {
                    out.add(ref.getPos());
                }
            }
            return out;
        }

        @Override
        public NormalizedChunk read(DimensionId dimension, ChunkPos pos) {
            return adapter.read(dimension, pos);
        }
    }
```

Add any missing imports (`ChunkRef`, `ChunkPos`, `DimensionId`, `NormalizedChunk`, `WorldAdapter`) — match what `LiveChunkSource` already imports in this file.

- [ ] **Step 4: Add `diffWorkingTreeDirty` to `WorldDiffer`**

In `WorldDiffer.java`, next to `diffWorkingTree`:

```java
    /**
     * Incremental working-vs-HEAD diff: compares only the adapter's
     * {@linkplain WorldAdapter#peekDirty() dirty} chunks against HEAD. Chunks not in the dirty set are
     * HEAD by construction (valid once dirty tracking is primed), so they are skipped.
     */
    public static WorldDiff diffWorkingTreeDirty(MineGitRepo repo, WorldAdapter adapter) {
        return diff(ChunkSources.tree(repo, "HEAD"), ChunkSources.liveDirty(adapter));
    }
```

NOTE: keep the argument order identical to `diffWorkingTree` (tree first = "before", live second = "after") so ADD/REMOVE/CHANGE polarity matches the existing behavior.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:test --tests 'net.rainbowcreation.vocanicz.minegit.core.diff.DiffWorkingTreeDirtyTest'`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the full core suite (no regressions)**

Run: `./gradlew :core:test`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(core): dirty-aware working diff (liveDirty + diffWorkingTreeDirty) (Spec E task 2)"
```

---

## Task 3: mod — `DirtyTrackerRegistry`, adapter wiring, primed reconciliation, `--full`/`rescan`

**Files:**
- Create: `mod/common/.../mod/world/DirtyTrackerRegistry.java`
- Modify: `mod/common/.../mod/world/ModWorldAdapter.java`
- Modify: `mod/common/.../mod/world/CommitService.java`
- Modify: `mod/common/.../mod/command/ServerCommandRuntime.java`, `MineGitService.java`, `MineGitCommands.java`, `MineGitText.java`
- Test: `mod/common/src/test/.../mod/world/DirtyTrackerRegistryTest.java`, `mod/common/src/test/.../mod/world/ModWorldAdapterDirtyTest.java`

**Context:** The adapter is built per command (`adapterFor(level)`), so the dirty set must live in a long-lived registry keyed by level. `DirtyTrackerRegistry` is a plain map (Minecraft-free, unit-testable). `ModWorldAdapter` gains an optional `DirtyChunkSet` it delegates `drainDirty`/`peekDirty` to. `CommitService` captures `drainDirty()` when primed, else `allChunks()` then primes. `status`/`diff` use `diffWorkingTreeDirty` when primed, else full then prime.

- [ ] **Step 1: Write the failing test for `DirtyTrackerRegistry`**

Create `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/world/DirtyTrackerRegistryTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import org.junit.jupiter.api.Test;

class DirtyTrackerRegistryTest {

    @Test
    void returnsTheSameSetPerLevelKey() {
        DirtyTrackerRegistry registry = new DirtyTrackerRegistry();
        DirtyChunkSet a = registry.tracker("minecraft:overworld");
        DirtyChunkSet b = registry.tracker("minecraft:overworld");
        assertNotNull(a);
        assertSame(a, b, "same level key must return the same dirty set");
    }

    @Test
    void differentLevelsGetDifferentSets() {
        DirtyTrackerRegistry registry = new DirtyTrackerRegistry();
        assertSame(registry.tracker("a"), registry.tracker("a"));
        org.junit.jupiter.api.Assertions.assertNotSame(
                registry.tracker("a"), registry.tracker("b"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :mod:common:test --tests '*DirtyTrackerRegistryTest'`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement `DirtyTrackerRegistry`**

Create `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/world/DirtyTrackerRegistry.java`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One {@link DirtyChunkSet} per level, living for the server's lifetime so the {@code setBlockState}
 * mixin can mark chunks dirty continuously, independent of any single command. Keyed by the same level
 * key string that {@code ServerCommandRuntime} binds repos under.
 */
public final class DirtyTrackerRegistry {

    private final Map<String, DirtyChunkSet> trackers = new ConcurrentHashMap<String, DirtyChunkSet>();

    /** The dirty set for {@code levelKey}, created on first use. */
    public DirtyChunkSet tracker(String levelKey) {
        Objects.requireNonNull(levelKey, "levelKey");
        return trackers.computeIfAbsent(levelKey, k -> new DirtyChunkSet());
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :mod:common:test --tests '*DirtyTrackerRegistryTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Write the failing test for `ModWorldAdapter` dirty delegation**

Create `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/world/ModWorldAdapterDirtyTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class ModWorldAdapterDirtyTest {

    @Test
    void peekAndDrainComeFromTheInjectedDirtySet() {
        DirtyChunkSet tracker = new DirtyChunkSet();
        // FakeLevelAccess is the existing test seam in this package.
        FakeLevelAccess level = new FakeLevelAccess(DimensionId.OVERWORLD);
        ModWorldAdapter adapter = new ModWorldAdapter(level, tracker);

        tracker.markDirty(new ChunkRef(DimensionId.OVERWORLD, new ChunkPos(2, 3)));
        assertEquals(1, adapter.peekDirty().size());
        assertEquals(1, adapter.peekDirty().size()); // peek does not clear
        assertEquals(1, adapter.drainDirty().size());
        assertTrue(adapter.peekDirty().isEmpty()); // drain cleared it
    }
}
```

NOTE: `FakeLevelAccess` already exists in `mod/common/src/test/.../mod/world/`. If its constructor differs, match the existing usage in `ModWorldAdapterTest.java`.

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :mod:common:test --tests '*ModWorldAdapterDirtyTest'`
Expected: FAIL — `ModWorldAdapter(LevelAccess, DirtyChunkSet)` constructor does not exist.

- [ ] **Step 7: Wire `DirtyChunkSet` into `ModWorldAdapter`**

In `ModWorldAdapter.java`: add a nullable `DirtyChunkSet` field and a second constructor; delegate `drainDirty`/`peekDirty` to it when present (falling back to `allChunks()` when null, preserving current behavior).

```java
    private final DirtyChunkSet dirty; // nullable: null => fall back to full (allChunks)

    public ModWorldAdapter(LevelAccess level) {
        this(level, null);
    }

    public ModWorldAdapter(LevelAccess level, DirtyChunkSet dirty) {
        this.level = Objects.requireNonNull(level, "level");
        this.dimension = level.dimension();
        this.dirty = dirty;
    }
```

Replace the existing `drainDirty()` body and the Task-1 placeholder `peekDirty()` with:

```java
    @Override
    public Set<ChunkRef> drainDirty() {
        return dirty != null ? dirty.drainDirty() : allChunks();
    }

    @Override
    public Set<ChunkRef> peekDirty() {
        return dirty != null ? dirty.peekDirty() : allChunks();
    }
```

Add `import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;`.

- [ ] **Step 8: Run both mod world tests to verify pass**

Run: `./gradlew :mod:common:test --tests '*ModWorldAdapterDirtyTest' --tests '*ModWorldAdapterTest'`
Expected: PASS.

- [ ] **Step 9: Make `CommitService` capture dirty-only when primed**

In `mod/common/.../mod/world/CommitService.java`, `snapshotBegin` currently does:
`List<ChunkRef> refs = new ArrayList<ChunkRef>(live.allChunks());`

Change the `commit(...)` entry to accept a `DirtyChunkSet tracker` (nullable) and choose the ref set:

```java
        // primed => only the dirty chunks; first pass this session => full, then prime.
        List<ChunkRef> refs;
        if (tracker != null && tracker.isPrimed()) {
            refs = new ArrayList<ChunkRef>(live.drainDirty());
        } else {
            refs = new ArrayList<ChunkRef>(live.allChunks());
            live.drainDirty();           // consume so the next incremental drain is clean
            if (tracker != null) {
                tracker.prime();
            }
        }
```

Thread the `tracker` parameter through `commit(...)` → `snapshotBegin(...)` (add it to both signatures). Keep everything else (batching, off-thread git) identical.

- [ ] **Step 10: Primed reconciliation + dirty-aware status/diff in `ServerCommandRuntime`**

In `ServerCommandRuntime.java`:
- Add a `DirtyTrackerRegistry` field, constructed in the existing constructor: `private final DirtyTrackerRegistry trackers = new DirtyTrackerRegistry();` and a getter `public DirtyTrackerRegistry trackers() { return trackers; }` (the mixin reaches it via the shared accessor set up in Task 4).
- Change `adapterFor(ServerLevel level)` to inject the tracker:

```java
    private WorldAdapter adapterFor(ServerLevel level) {
        DirtyChunkSet tracker = trackers.tracker(levelKey(level));
        return new ModWorldAdapter(new ServerLevelAccess(level), tracker);
    }
```

- In `commit(...)`: parse an optional `--full` flag (see Task step 11 for the command tree). Before calling the service, if `--full`, call `trackers.tracker(levelKey).unprime();`. Pass `trackers.tracker(levelKey)` into `service.commit(...)`.
- In `status(...)` and `diff(...)` (working-vs-HEAD path only): use the primed-aware service call (next step).

- [ ] **Step 11: `MineGitService` dirty-aware status + `MineGitCommands` `--full`/`rescan`**

In `MineGitService.java`, add a primed-aware status:

```java
    /** Working-vs-HEAD diff, incremental when the tracker is primed (else full, then primes). */
    public static WorldDiff status(Path repoDir, WorldAdapter adapter, Clock clock, DirtyChunkSet tracker) {
        try (MineGitRepo repo = MineGitRepo.open(repoDir, adapter, clock)) {
            if (tracker != null && tracker.isPrimed()) {
                return WorldDiffer.diffWorkingTreeDirty(repo, adapter);
            }
            WorldDiff full = WorldDiffer.diffWorkingTree(repo, adapter);
            if (tracker != null) {
                tracker.prime();
            }
            return full;
        }
    }
```

(Match the exact `MineGitRepo.open(...)` overload used by the existing `status`.) Keep the old `status(repoDir, adapter, clock)` for callers/tests that don't pass a tracker. Have `ServerCommandRuntime.status`/`diff` call the new overload with `trackers.tracker(levelKey)`.

In `MineGitCommands.java`:
- Add a `--full` literal under `commit` (mirror the `-m` branch) and a top-level `rescan` subcommand routing to `runtime::rescan`. Add `int rescan(CommandContext<CommandSourceStack> ctx)` to the `Runtime` interface.
- Implement `ServerCommandRuntime.rescan`: resolve the player's level, `trackers.tracker(levelKey).unprime()`, and send `MineGitText.good("MineGit will do a full rescan on the next commit/status.")` via the source.
- Add `Subcommand.RESCAN` (literal `"rescan"`) and `Subcommand.FULL_FLAG`/`FORCE`-style constant `--full` consistent with the existing `FORCE_FLAG` pattern.

- [ ] **Step 12: Build + run the mod common suite**

Run: `./gradlew :mod:common:test`
Expected: BUILD SUCCESSFUL, all green (new + existing).

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(mod): dirty tracker registry, adapter wiring, primed reconciliation, --full/rescan (Spec E task 3)"
```

---

## Task 4: mod — `setBlockState` mixin (Fabric + NeoForge) + GameTest

**Files:**
- Create: `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/LevelChunkMixin.java`
- Create: `mod/fabric/src/main/resources/minegit.mixins.json`
- Modify: `mod/fabric/build.gradle.kts` (loom mixin config), `mod/fabric/src/main/resources/fabric.mod.json` (mixins entry)
- Create: `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/LevelChunkMixin.java`
- Create: `mod/neoforge/src/main/resources/minegit.mixins.json`
- Modify: `mod/neoforge/build.gradle.kts`, `mod/neoforge/src/main/resources/META-INF/neoforge.mods.toml` (mixin config)
- Create: a shared accessor so the mixin reaches the registry — `mod/common/.../mod/world/DirtyTracking.java`
- Modify: `mod/common/.../mod/MineGitMod.java` (publish the registry), `ServerCommandRuntime` (use the published registry)
- Test: `mod/common/.../mod/gametest/MineGitGameTestLogic.java` (add a one-block→one-chunk assertion) + register it on both loaders

**Context:** Mixins can't live in `common` (NeoForge limitation), so each platform has an identical mixin. Both need to reach the same `DirtyTrackerRegistry`. Use a tiny static holder in `common` that the mod publishes the registry into at init.

- [ ] **Step 1: Add the shared static accessor in common**

Create `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/world/DirtyTracking.java`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;

/**
 * The process-wide bridge from the platform {@code setBlockState} mixins to the active
 * {@link DirtyTrackerRegistry}. The mod publishes the registry here at init; the mixins call
 * {@link #markDirty} on every block change. No-op until published (e.g. early boot), so a mixin firing
 * before init never NPEs.
 */
public final class DirtyTracking {

    private static volatile DirtyTrackerRegistry registry;

    private DirtyTracking() {
    }

    public static void install(DirtyTrackerRegistry r) {
        registry = r;
    }

    /** Marks the chunk owning block (x,z) dirty for {@code levelKey}. Safe before install (no-op). */
    public static void markDirty(String levelKey, int blockX, int blockZ) {
        DirtyTrackerRegistry r = registry;
        if (r == null) {
            return;
        }
        ChunkPos pos = new ChunkPos(blockX >> 4, blockZ >> 4);
        r.tracker(levelKey).markDirty(new ChunkRef(new DimensionId(levelKey), pos));
    }
}
```

IMPORTANT — keep the level-key scheme consistent: `DirtyTracking.markDirty` builds `new DimensionId(levelKey)`, and `ServerCommandRuntime.levelKey(level)` must produce the same string the adapter's `ModWorldAdapter.dimension()` carries. Verify `levelKey(level)` returns the dimension id string (e.g. `"minecraft:overworld"`); if it returns a different key (save-folder name), align `DirtyTracking` to use the dimension id used by `ServerLevelAccess.dimension()` so the `ChunkRef` dimensions match what `drainDirty`/`read` use. (The `ChunkRef.dimension` must equal `adapter.dimension()`.)

- [ ] **Step 2: Publish the registry at mod init**

In `ServerCommandRuntime`, change the registry from a private field to one obtained from `DirtyTracking`, OR install the runtime's registry. Simplest: in `MineGitMod.init()` (or where `sharedRuntime()` is created), after constructing the runtime, call `DirtyTracking.install(sharedRuntime().trackers());`. Ensure `ServerCommandRuntime.trackers()` returns the same registry the adapters use.

- [ ] **Step 3: Create the Fabric mixin**

Create `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/LevelChunkMixin.java`:

```java
package net.rainbowcreation.vocanicz.minegit.mod.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.rainbowcreation.vocanicz.minegit.mod.world.DirtyTracking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Marks a chunk dirty for MineGit whenever any block in it changes — player edits, pistons, fluids,
 * explosions, worldgen, commands, other mods — by hooking {@code LevelChunk#setBlockState}. Only
 * server-side {@link ServerLevel} changes are tracked; client levels are ignored.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void minegit$markDirty(BlockPos pos, BlockState state, boolean moved,
                                   CallbackInfoReturnable<BlockState> cir) {
        Level level = ((LevelChunk) (Object) this).getLevel();
        if (level instanceof ServerLevel) {
            String levelKey = ((ServerLevel) level).dimension().location().toString();
            DirtyTracking.markDirty(levelKey, pos.getX(), pos.getZ());
        }
    }
}
```

NOTE: confirm the `setBlockState` signature for MC 1.21.11 Mojang mappings (it may be `setBlockState(BlockPos, BlockState, boolean)` or `(BlockPos, BlockState, int flags)` / include a 4th arg). Use the mappings (e.g. `javap` on the loom-decompiled `LevelChunk`, or the minecraft-modding skill) to match the exact descriptor; adjust the `@Inject method`/params accordingly. The `dimension().location().toString()` is the canonical level key — ensure `ServerCommandRuntime.levelKey` and `ServerLevelAccess.dimension()` use the SAME string.

- [ ] **Step 4: Create the Fabric mixin config**

Create `mod/fabric/src/main/resources/minegit.mixins.json`:

```json
{
  "required": true,
  "minVersion": "0.8",
  "package": "net.rainbowcreation.vocanicz.minegit.mod.mixin",
  "compatibilityLevel": "JAVA_21",
  "mixins": [],
  "server": ["LevelChunkMixin"],
  "injectors": { "defaultRequire": 1 }
}
```

Add to `mod/fabric/src/main/resources/fabric.mod.json` a top-level `"mixins": ["minegit.mixins.json"]` entry.

- [ ] **Step 5: Enable mixins in `mod/fabric/build.gradle.kts`**

Ensure loom mixin is configured. Add inside the `loom { }` block:

```kotlin
loom {
    mixin {
        defaultRefmapName.set("minegit.refmap.json")
    }
}
```

(Architectury-loom applies the mixin AP automatically; if a separate `annotationProcessor`/`mixin` dependency is required for this toolchain, add it per the minecraft-multiloader skill.)

- [ ] **Step 6: Create the NeoForge mixin (identical class) + config**

Create `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/mixin/LevelChunkMixin.java` with the SAME content as Step 3.

Create `mod/neoforge/src/main/resources/minegit.mixins.json` with the SAME content as Step 4 (package/server list identical).

Register it for NeoForge in `META-INF/neoforge.mods.toml`:

```toml
[[mixins]]
config = "minegit.mixins.json"
```

Add the same `loom { mixin { defaultRefmapName.set("minegit.refmap.json") } }` to `mod/neoforge/build.gradle.kts`.

- [ ] **Step 7: Add the one-block→one-chunk GameTest**

In `mod/common/.../mod/gametest/MineGitGameTestLogic.java`, add a test method that: builds nothing, primes via a first commit, then sets ONE block and asserts that committing changes exactly that area. Since GameTests can't easily count chunk reads, assert behaviorally: after a primed commit, place one block, `commit`, then mutate it, `checkout HEAD~1`, and assert it reverted (proving the dirty path committed the block). Add:

```java
    /** After priming, a single-block change is captured incrementally and reverts on checkout. */
    public static void incrementalSingleBlockReverts(GameTestHelper helper) {
        // Reuse the existing harness pattern: build adapter+repo for this gametest level,
        // commit once (primes), set one block, commit, mutate, checkout HEAD~1, assert reverted.
        // (Mirror placeCommitMutateCheckoutRevert but with the registry-backed adapter so the
        // mixin/dirty path is exercised.)
    }
```

Replace the comment with the concrete steps mirroring the existing `placeCommitMutateCheckoutRevert` (which is already in this file) but driving the dirty-backed adapter. Register the new test in `mod/fabric/.../MineGitFabricGameTest.java` and `mod/neoforge/.../MineGitNeoForgeGameTest.java` alongside the existing two.

- [ ] **Step 8: Build both loaders + run GameTests**

Run:
```
./gradlew :mod:fabric:build :mod:neoforge:build
./gradlew :mod:fabric:runGametest
./gradlew :mod:neoforge:runGameTestServer
```
Expected: both build; GameTests report "All N required tests passed". Confirm the log shows the mixin applied (no "mixin apply failed"). If the `setBlockState` descriptor is wrong, the run fails fast with a mixin error — fix the signature per Step 3's note and re-run.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(mod): setBlockState mixin feeds dirty tracker on both loaders + GameTest (Spec E task 4)"
```

---

## Task 5: plugin — dirty registry, adapter wiring, primed reconciliation, `--full`/`rescan`

**Files:**
- Create: `plugin/.../plugin/world/WorldDirtyRegistry.java`
- Modify: `plugin/.../plugin/world/BukkitWorldAdapter.java`, `plugin/.../plugin/world/CommitService.java`
- Modify: `plugin/.../plugin/MineGitPlugin.java`, `plugin/.../plugin/command/MineGitCommand.java`
- Test: `plugin/src/test/.../plugin/world/BukkitWorldAdapterDirtyTest.java`

**Context:** Mirror Task 3 for Bukkit. `WorldDirtyRegistry` maps world name → `DirtyChunkSet`. `BukkitWorldAdapter` gains a nullable `DirtyChunkSet`; `peekDirty`/`drainDirty` delegate to it (else `allChunks()`). The plugin `CommitService` captures dirty-only when primed. Plugin tests use Mockito (no MockBukkit), so test the adapter delegation with a real `DirtyChunkSet` and a Mockito-mocked `World`/`BlockBridge` (mirror the existing `BukkitWorldAdapterTest` setup).

- [ ] **Step 1: Write the failing adapter-delegation test**

Create `plugin/src/test/java/net/rainbowcreation/vocanicz/minegit/plugin/world/BukkitWorldAdapterDirtyTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.plugin.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import org.junit.jupiter.api.Test;

class BukkitWorldAdapterDirtyTest {

    @Test
    void peekAndDrainComeFromTheInjectedDirtySet() {
        DirtyChunkSet tracker = new DirtyChunkSet();
        // Build the adapter the way BukkitWorldAdapterTest does (mocked World + BlockBridge),
        // then pass the tracker via the new constructor.
        BukkitWorldAdapter adapter = TestAdapters.withTracker(tracker);

        DimensionId dim = adapter.dimension();
        tracker.markDirty(new ChunkRef(dim, new ChunkPos(5, 6)));
        assertEquals(1, adapter.peekDirty().size());
        assertEquals(1, adapter.peekDirty().size());
        assertEquals(1, adapter.drainDirty().size());
        assertTrue(adapter.peekDirty().isEmpty());
    }
}
```

Add a tiny `TestAdapters` helper (or inline the Mockito setup copied from `BukkitWorldAdapterTest`) that builds a `BukkitWorldAdapter` over a mocked `World` (stub `getName`, `getEnvironment`, `getMinHeight`/`getMaxHeight` as that test does) with the tracker. Match `BukkitWorldAdapterTest`'s existing mock stubs exactly.

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plugin:test --tests '*BukkitWorldAdapterDirtyTest'`
Expected: FAIL — constructor with tracker / `TestAdapters` missing.

- [ ] **Step 3: Implement `WorldDirtyRegistry`**

Create `plugin/src/main/java/net/rainbowcreation/vocanicz/minegit/plugin/world/WorldDirtyRegistry.java`:

```java
package net.rainbowcreation.vocanicz.minegit.plugin.world;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link DirtyChunkSet} per Bukkit world, for the server's lifetime (fed by block events). */
public final class WorldDirtyRegistry {

    private final Map<String, DirtyChunkSet> trackers = new ConcurrentHashMap<String, DirtyChunkSet>();

    public DirtyChunkSet tracker(String worldName) {
        Objects.requireNonNull(worldName, "worldName");
        return trackers.computeIfAbsent(worldName, k -> new DirtyChunkSet());
    }
}
```

- [ ] **Step 4: Wire the tracker into `BukkitWorldAdapter`**

Add a nullable `DirtyChunkSet dirty` field + a second constructor (mirror Task 3 Step 7), and replace `drainDirty()`/`peekDirty()`:

```java
    @Override
    public Set<ChunkRef> drainDirty() {
        return dirty != null ? dirty.drainDirty() : allChunks();
    }

    @Override
    public Set<ChunkRef> peekDirty() {
        return dirty != null ? dirty.peekDirty() : allChunks();
    }
```

- [ ] **Step 5: Run the adapter test to verify pass**

Run: `./gradlew :plugin:test --tests '*BukkitWorldAdapterDirtyTest'`
Expected: PASS.

- [ ] **Step 6: Plugin `CommitService` dirty-only when primed**

Mirror Task 3 Step 9 in `plugin/.../plugin/world/CommitService.java`: thread a `DirtyChunkSet tracker` param into the commit entry; choose `live.drainDirty()` when primed else `allChunks()` + prime.

- [ ] **Step 7: Wire registry + reconciliation + `--full`/`rescan` in plugin**

- In `MineGitPlugin.onEnable`: create `WorldDirtyRegistry worldDirty = new WorldDirtyRegistry();`, keep it as a field, and change `adapterFor(World world)` to `new BukkitWorldAdapter(world, blockBridge, worldDirty.tracker(world.getName()))`.
- In `MineGitCommand.doCommit`: support a `--full` arg (unprime the world's tracker before committing) and add a `rescan` subcommand (`case "rescan": return doRescan(sender);`) that unprimes and messages the player. Pass `worldDirty.tracker(worldName)` into the commit + status calls.
- Add `rescan` to the `PERMISSIONS` map (use `minegit.use`).

- [ ] **Step 8: Run the plugin suite**

Run: `./gradlew :plugin:test`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(plugin): dirty registry + adapter wiring + primed reconciliation + --full/rescan (Spec E task 5)"
```

---

## Task 6: plugin — Bukkit block-change listener

**Files:**
- Create: `plugin/.../plugin/listener/BlockChangeListener.java`
- Modify: `plugin/.../plugin/MineGitPlugin.java` (register the listener)
- Test: `plugin/src/test/.../plugin/listener/BlockChangeListenerTest.java`

**Context:** The listener marks the affected chunk dirty for the common mutation events. Test with Mockito-mocked events (mirror existing plugin tests).

- [ ] **Step 1: Write the failing listener test**

Create `plugin/src/test/java/net/rainbowcreation/vocanicz/minegit/plugin/listener/BlockChangeListenerTest.java`:

```java
package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import net.rainbowcreation.vocanicz.minegit.core.adapter.DirtyChunkSet;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.Test;

class BlockChangeListenerTest {

    @Test
    void blockBreakMarksOwningChunkDirty() {
        WorldDirtyRegistry registry = new WorldDirtyRegistry();
        BlockChangeListener listener = new BlockChangeListener(registry);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(world);
        when(block.getX()).thenReturn(33); // chunk x = 2
        when(block.getZ()).thenReturn(50); // chunk z = 3
        BlockBreakEvent event = mock(BlockBreakEvent.class);
        when(event.getBlock()).thenReturn(block);

        listener.onBlockBreak(event);

        DirtyChunkSet tracker = registry.tracker("world");
        assertEquals(1, tracker.peekDirty().size());
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :plugin:test --tests '*BlockChangeListenerTest'`
Expected: FAIL — `BlockChangeListener` does not exist.

- [ ] **Step 3: Implement the listener**

Create `plugin/src/main/java/net/rainbowcreation/vocanicz/minegit/plugin/listener/BlockChangeListener.java`:

```java
package net.rainbowcreation.vocanicz.minegit.plugin.listener;

import net.rainbowcreation.vocanicz.minegit.core.adapter.ChunkRef;
import net.rainbowcreation.vocanicz.minegit.core.model.ChunkPos;
import net.rainbowcreation.vocanicz.minegit.core.model.DimensionId;
import net.rainbowcreation.vocanicz.minegit.plugin.world.WorldDirtyRegistry;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Marks chunks dirty for MineGit on the common block-mutation events (Spec E §4.3). Bukkit cannot
 * observe every mutation source, so {@code /mg rescan} and once-per-session reconciliation cover gaps.
 * Listeners run at {@code MONITOR} priority and ignore cancelled events — we only record changes that
 * actually happen.
 */
public final class BlockChangeListener implements Listener {

    private final WorldDirtyRegistry registry;

    public BlockChangeListener(WorldDirtyRegistry registry) {
        this.registry = registry;
    }

    private void mark(Block block) {
        String world = block.getWorld().getName();
        ChunkPos pos = new ChunkPos(block.getX() >> 4, block.getZ() >> 4);
        registry.tracker(world).markDirty(new ChunkRef(new DimensionId(world), pos));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) { mark(e.getToBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) { mark(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        for (Block b : e.blockList()) {
            mark(b);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        e.getBlocks().forEach(state -> {
            ChunkPos pos = new ChunkPos(state.getX() >> 4, state.getZ() >> 4);
            String world = state.getWorld().getName();
            registry.tracker(world).markDirty(new ChunkRef(new DimensionId(world), pos));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkPopulate(ChunkPopulateEvent e) {
        String world = e.getWorld().getName();
        ChunkPos pos = new ChunkPos(e.getChunk().getX(), e.getChunk().getZ());
        registry.tracker(world).markDirty(new ChunkRef(new DimensionId(world), pos));
    }
}
```

NOTE: the `DimensionId` here must match what `BukkitWorldAdapter.dimension()` reports (so the `ChunkRef` dimensions agree). Check `BukkitWorldAdapter`'s `dimension` value — if it derives from `world.getEnvironment()` or a fixed id rather than `world.getName()`, use that SAME derivation in `mark(...)`. Align both to one helper if needed.

Piston events (`BlockPistonExtendEvent`/`BlockPistonRetractEvent`) can be added the same way (mark each `getBlocks()` block + the piston block); include them if straightforward, otherwise the reconciliation/rescan covers them.

- [ ] **Step 4: Register the listener in `MineGitPlugin.onEnable`**

```java
        getServer().getPluginManager().registerEvents(new BlockChangeListener(worldDirty), this);
```

(Use the same `worldDirty` registry instance created in Task 5 Step 7.)

- [ ] **Step 5: Run the listener test + full plugin suite**

Run: `./gradlew :plugin:test`
Expected: BUILD SUCCESSFUL, all green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(plugin): Bukkit block-change listener feeds dirty tracker (Spec E task 6)"
```

---

## Final verification (after all tasks)

- [ ] `./gradlew clean build` — all subprojects + both loader jars, all unit tests green.
- [ ] `./gradlew :mod:fabric:runGametest` and `./gradlew :mod:neoforge:runGameTestServer` — all GameTests pass (incl. the new incremental one), and the log shows the mixin applied.
- [ ] Manual smoke (optional, needs a client/server): `/mg init`, build, `/mg commit` (primes, full), build more, `/mg commit` (incremental — fast), `/mg status`/`/mg diff` fast, `/mg checkout` reverts, `/mg rescan` then `/mg commit` does a full pass.
- [ ] Dispatch a final code review over the whole branch, then use superpowers:finishing-a-development-branch.

---

## Spec coverage check

- Spec §3.1 DirtyChunkSet → Task 1. §3.2 peekDirty → Task 1. §3.3 liveDirty + diffWorkingTreeDirty → Task 2. §3.4 primed reconciliation → Tasks 3 (mod) + 5 (plugin).
- Spec §4.1 registry → Tasks 3 (mod) + 5 (plugin). §4.2 mod mixin → Task 4. §4.3 plugin events → Task 6. §4.4 commands (`--full`/`rescan`) → Tasks 3 + 5.
- Spec §6 testing → unit tests in Tasks 1/2/3/5/6 + GameTest in Task 4 + final regression.
