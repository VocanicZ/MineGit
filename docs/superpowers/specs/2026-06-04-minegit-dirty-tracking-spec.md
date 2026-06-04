# Spec E — MineGit Incremental Commits via Event-Based Dirty Tracking

**Date:** 2026-06-04
**Status:** Approved. Implementation via subagent-driven development.
**Parent:** [MineGit umbrella architecture](2026-06-03-minegit-architecture-design.md)
**Depends on:** `core` + `protocol` + `plugin` + `mod` (Specs A/B/D, merged to `master`)

`commit`, `status`, and `diff` are slow because they read the **entire loaded world** every time — even
for a one-block change. With a normal view distance that is hundreds of chunks × ~98k blocks each, read
on the server thread and throttled across ticks → multiple seconds. This batch makes them **incremental**:
track which chunks actually changed (from world events), and read only those.

The engine was built for this: `WorldAdapter.drainDirty()` already exists and `MineGitRepo.commit` already
uses it (full only on the first commit). The work is (1) populate a real dirty set from world events, and
(2) make `status`/`diff` dirty-aware too. The repository's working-tree-of-`.mgc` model already makes git
incremental at the blob level — unchanged chunks' `.mgc` files persist between commits — so once we stop
re-reading unchanged chunks, commits become genuinely incremental.

This batch is about **speed**, not new captured data: still **blocks only**, loaded chunks only.

---

## 1. Scope

### In scope
- **`core`**: a Minecraft-free `DirtyChunkSet`; a non-clearing `peekDirty()` on `WorldAdapter`; a
  dirty-aware working diff (`ChunkSources.liveDirty` + `WorldDiffer.diffWorkingTreeDirty`) for
  `status`/`diff`; a **primed** flag for once-per-session reconciliation.
- **`mod`** (Fabric + NeoForge): a per-server `DirtyTrackerRegistry`; a **mixin** on the chunk
  `setBlockState` path that marks the touched chunk dirty for that level; the per-command `ModWorldAdapter`
  reads its level's tracker; `/mg commit --full` / `/mg rescan` forces a full pass.
- **`plugin`** (Bukkit): the same registry + adapter wiring, fed by a Bukkit event listener covering the
  common block-mutation events; the same `--full`/`rescan` escape hatch.
- Tests: `core` unit tests; a `mod` GameTest proving a one-block change reads exactly one chunk; a `plugin`
  MockBukkit test that each event marks the right chunk; both loaders' existing GameTests stay green.

### Out of scope (unchanged from prior specs)
- **No new captured data** — still blocks only (no block entities, no biomes).
- **No unloaded-chunk capture** — dirty tracking covers loaded chunks; the once-per-session reconciliation
  re-reads currently-loaded chunks, not region files on disk.
- **GitHub push/pull**, the **`minegit:diff` client overlay**, and **`merge`** remain deferred.

---

## 2. Root cause (measured in code)

- `MineGitRepo.commit`: `refs = firstChunkCommitDone ? adapter.drainDirty() : adapter.allChunks()`. The
  incremental branch exists, but frontends' `drainDirty()` returns `allChunks()`, and the **adapter/repo
  are constructed per command**, so `firstChunkCommitDone` resets each invocation → every commit reads all
  loaded chunks anyway.
- `ChunkSources.live(adapter).chunks(dim)` enumerates `adapter.allChunks()` → `status`/`diff` read every
  loaded chunk against HEAD.

The fix therefore needs a **long-lived dirty set** (events feed it continuously, independent of any single
command) and a **dirty-aware diff path**.

---

## 3. Core changes (Minecraft-free, unit-tested)

### 3.1 `DirtyChunkSet`
A thread-safe set of dirty `ChunkRef`, one per world/level. Events may call it from any thread, commands
drain/peek it on the server thread.

```java
final class DirtyChunkSet {
    void          markDirty(ChunkRef ref);   // idempotent; any-thread
    Set<ChunkRef> peekDirty();               // snapshot, does NOT clear (status/diff)
    Set<ChunkRef> drainDirty();              // snapshot AND clear (commit)
    boolean       isPrimed();                // false until the session reconciliation pass runs
    void          prime();                   // mark the once-per-session full pass done
}
```

`markDirty` is cheap and may over-include (a block set to its own value still marks the chunk); the engine
dedupes by content (re-serialized identical `.mgc` yields no git delta), so **over-marking is safe;
under-marking is the risk** the mixin/event coverage must avoid.

### 3.2 `WorldAdapter.peekDirty()`
Add `Set<ChunkRef> peekDirty()` to the interface — the non-clearing read for `status`/`diff`. Existing
`drainDirty()` (clearing) is unchanged and remains the commit path. The in-memory `FakeWorldAdapter` and
`SnapshotWorldAdapter` implement both.

### 3.3 Dirty-aware diff
- `ChunkSources.liveDirty(adapter)`: a `ChunkSource` whose `chunks(dim)` returns the chunk positions from
  `adapter.peekDirty()` for that dimension; `read` delegates to `adapter.read`.
- `WorldDiffer.diffWorkingTreeDirty(repo, adapter)`: diffs `liveDirty(adapter)` against
  `ChunkSources.tree(repo, "HEAD")`, but **only over the dirty positions** (a chunk absent from the dirty
  set is HEAD by construction and contributes nothing). New chunks (worldgen) are in the dirty set, so
  ADDs still appear.

### 3.4 Reconciliation (the `primed` flag)
The in-memory dirty set is empty after a server restart, so changes made before the restart but after the
last commit would be missed. To stay correct:
- The **first** `commit`/`status`/`diff` of a server session, when `!isPrimed()`, runs the **full** path
  (`allChunks` for commit; `WorldDiffer.diffWorkingTree` for status/diff), then calls `prime()`.
- Once primed, all three use the incremental path (`drainDirty`/`peekDirty` + `diffWorkingTreeDirty`).
- `/mg commit --full` / `/mg rescan` resets to unprimed (forces one full pass) for safety.

---

## 4. Frontend wiring (shared shape, per-platform capture)

### 4.1 `DirtyTrackerRegistry`
One per server, created at init, keyed by level/world identity. `tracker(levelKey)` returns (creating on
first use) that world's `DirtyChunkSet`. The capture layer writes to it; the per-command adapter reads
from it. Long-lived — survives across commands within a session.

### 4.2 Mod capture — mixin
A mixin in **each platform subproject** (Fabric + NeoForge; common holds no mixins) on the chunk
`setBlockState` path (`LevelChunk#setBlockState`). On a real change it resolves the owning `ServerLevel`
and chunk position and calls `registry.tracker(levelKey).markDirty(ref)`. Client-side and non-`ServerLevel`
calls are ignored. `ModWorldAdapter` is given its level's `DirtyChunkSet` so `drainDirty`/`peekDirty`
return tracked changes instead of all loaded chunks.

### 4.3 Plugin capture — Bukkit events
A listener marks the affected chunk dirty for: `BlockPlaceEvent`, `BlockBreakEvent`, `BlockBurnEvent`,
`BlockFadeEvent`, `BlockFormEvent`, `BlockSpreadEvent`, `BlockFromToEvent` (fluids),
`LeavesDecayEvent`, `EntityExplodeEvent` (each block), `BlockPistonExtendEvent`/`BlockPistonRetractEvent`
(moved blocks + destination), `StructureGrowEvent`, and `ChunkPopulateEvent` (worldgen). `BukkitWorldAdapter`
reads its world's `DirtyChunkSet`. Because Bukkit cannot observe every mutation source (e.g. NMS/WorldEdit),
the `/mg rescan` fallback and once-per-session reconciliation guarantee correctness.

### 4.4 Command surface
- `/mg commit [--full] [-m "msg"]` — `--full` forces a full snapshot (unprimes, then commits).
- `/mg rescan` — unprimes the tracker so the next operation does a full reconciliation pass; reports done.
- `status` and `diff` use the dirty-aware path automatically (full once per session, then incremental).

---

## 5. Threading

- `markDirty` is thread-safe and may be called off the server thread (mixin/event context). The set uses a
  concurrent structure; no Minecraft access happens inside it.
- `drainDirty`/`peekDirty` are read by commands on the server thread, consistent with the existing model.
- No change to the commit's read-on-server-thread / git-off-thread / apply-throttled dance.

---

## 6. Testing

- **core**: `DirtyChunkSet` (mark/peek/drain/prime semantics, idempotency, concurrency-safe);
  `liveDirty` position enumeration; `diffWorkingTreeDirty` equals the full working diff when every changed
  chunk is marked, and is empty when nothing is marked; `peekDirty` does not clear, `drainDirty` does.
- **mod GameTest**: place one block → exactly one chunk dirty → `commit` reads only that chunk and the
  commit reverts/round-trips; the full place→commit→mutate→checkout→assert-reverted loop still passes.
- **plugin MockBukkit**: each registered event marks the correct chunk; `rescan` unprimes; adapter reads
  the tracker.
- **regression**: both loaders' existing GameTests and the full unit suites stay green.

---

## 7. Proposed Issue / Task Breakdown (chained)

1. **`core`: `DirtyChunkSet` + `peekDirty()`** on `WorldAdapter` (+ fakes) + unit tests.
2. **`core`: dirty-aware diff** — `ChunkSources.liveDirty` + `WorldDiffer.diffWorkingTreeDirty` + the
   `primed` reconciliation contract + unit tests. *(blocked by 1)*
3. **`mod`: `DirtyTrackerRegistry` + adapter wiring** — `ModWorldAdapter` reads a `DirtyChunkSet`;
   per-session primed reconciliation in `ServerCommandRuntime`; `--full`/`rescan`. *(blocked by 2)*
4. **`mod`: `setBlockState` mixin** (Fabric + NeoForge) feeding the registry + GameTest for one-block→
   one-chunk. *(blocked by 3)*
5. **`plugin`: registry + adapter wiring** — `BukkitWorldAdapter` reads a `DirtyChunkSet`; primed
   reconciliation; `--full`/`rescan`. *(blocked by 2)*
6. **`plugin`: Bukkit event listener** feeding the registry + MockBukkit tests. *(blocked by 5)*

---

## 8. Acceptance

- After the first (reconciliation) commit of a session, building a small structure and running
  `/mg commit` reads **only the changed chunks** (verified by the mod GameTest counting reads), and
  `/mg status` / `/mg diff` are correspondingly fast.
- A one-block change marks exactly one chunk dirty on the mod (mixin) and on the plugin (event).
- `/mg commit --full` and `/mg rescan` force a full pass; correctness after a restart is preserved by the
  once-per-session reconciliation.
- Blocks-only, local repo. Both loaders' GameTests green; full unit suites green.
