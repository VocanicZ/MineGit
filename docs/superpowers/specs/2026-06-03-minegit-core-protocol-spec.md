# Spec A — MineGit `core` + `protocol`

**Date:** 2026-06-03
**Status:** Approved. Implementation fanned out via the Harness fleet (PRD batch 1 = tracer bullet).
**Parent:** [MineGit umbrella architecture](2026-06-03-minegit-architecture-design.md)

This spec covers the first sub-project: the platform-agnostic engine (`core`), the shared wire module
(`protocol`), and a standalone `minegit-cli`. Everything here is **buildable and testable without
Minecraft** by backing the `WorldAdapter` interface with an in-memory fake world.

---

## 1. Scope

### In scope (the engine)
- Normalized block model (version-agnostic).
- Deterministic compact-binary `.mgc` chunk codec.
- MGRF on-disk repository layout.
- JGit integration: `init`, `commit`, `log` (batch 1); `branch`, `checkout`, `fetch`, `push`, `pull`,
  `clone` (batch 2).
- Block-diff engine (live-vs-HEAD and ref-vs-ref).
- `FakeWorldAdapter` + JUnit test harness.
- `minegit-cli` standalone tool.
- `protocol` module skeleton (wire codec lands in batch 2).

### Out of scope (later specs/batches)
- Any Minecraft API code (plugin / server-mod / client) — Specs B/C/D.
- `BlockMapper` legacy↔flattening tables — batch 2 (needed first by the plugin's `v1_8` module).
- MR-JAR modern-JGit override classes — batch 2.
- Deterministic DEFLATE compression of `.mgc` — batch 2 size optimization.
- `DiffPayload` wire codec — batch 2.

---

## 2. Module Layout & Language Baseline

```
minegit/                      (Gradle monorepo, Kotlin DSL)
  core/                       Java 8 baseline, JGit 5.13.x, JUnit 5
    src/main/java/.../model    normalized block model
    src/main/java/.../format   .mgc codec
    src/main/java/.../repo      MGRF layout
    src/main/java/.../git       JGit integration
    src/main/java/.../diff      block-diff engine
    src/main/java/.../adapter   WorldAdapter interface
    src/main/java/.../api       MineGitRepo facade
    src/test/java/.../fake      FakeWorldAdapter + tests
  protocol/                   Java 8; module skeleton now, DiffPayload codec in batch 2
  minegit-cli/                Java 8 'application' subproject wrapping core
```

- **`core` targets Java 8 bytecode** (hard constraint: the Spigot 1.8.8 plugin runs on Java 8). JGit
  **5.13.x** is the last Java-8-compatible line.
- **`core` is synchronous**; the **caller owns threading**. Core never spawns threads or touches a
  Minecraft scheduler. Frontends handle main-thread reads / async git off-thread.
- Build toolchain may be newer JDK, but `sourceCompatibility`/`targetCompatibility = 8`.

---

## 3. Public API — `MineGitRepo` facade (`core.api`)

```java
public final class MineGitRepo {
  public static MineGitRepo init(Path repoDir, WorldAdapter world);   // create repo + first structures
  public static MineGitRepo open(Path repoDir, WorldAdapter world);   // open existing

  public CommitId commit(String message, Author author);  // snapshot live world → commit (batch 1)
  public List<CommitInfo> log();                           // newest-first history    (batch 1)
  public Status   status();                                // summary: live world vs HEAD (batch 1)
  public WorldDiff diff();                                 // default: live world vs HEAD (batch 1)
  public WorldDiff diff(Ref a, Ref b);                     // explicit ref-vs-ref        (batch 1)

  // batch 2:
  // void branch(String name); List<BranchRef> branches();
  // void checkout(Ref target);                 // returns the BlockChange[] applied
  // void fetch(); void push(); void pull();
  // static MineGitRepo clone(String url, Path repoDir, Credential cred, WorldAdapter world);
}
```

- `Author` = in-game player identity `{ name, uuid }`. Committer is always `MineGit <minegit@local>`.
- `CommitId` wraps the git SHA. `Ref` resolves a branch name / commit id / `HEAD`.
- All methods are synchronous and throw `MineGitException` on failure.

---

## 4. Data Model (`core.model`)

Immutable value types with correct `equals`/`hashCode` (required for diff + dedup):

| Type | Fields |
|---|---|
| `BlockState` | `id: String` (e.g. `"minecraft:stone"`), `props: SortedMap<String,String>` |
| `NormalizedSection` | `palette: List<BlockState>` (canonical order), `indices: int[4096]` (Y·256+Z·16+X) |
| `NormalizedChunk` | `cx, cz, minSection, sections: NormalizedSection[]` (nullable = empty), `biomes`, `blockEntities: List<BlockEntity>` |
| `BlockEntity` | `x, y, z, snbt: String` |
| `ChunkPos` | `cx, cz` |
| `DimensionId` | `id: String` (`overworld` / `the_nether` / `the_end` / custom) |
| `BlockChange` | `x, y, z, kind: ADD\|REMOVE\|CHANGE, old: BlockState?, new: BlockState?` |
| `ChunkDiff` | `pos: ChunkPos, changes: List<BlockChange>` |
| `WorldDiff` | `Map<DimensionId, List<ChunkDiff>>`, plus `added/removed/changed` counts |

- `ADD` = air/absent → solid; `REMOVE` = solid → air/absent; `CHANGE` = different non-air state.
- Air is the canonical "empty" baseline so add/remove are well-defined.

---

## 5. `.mgc` Codec (`core.format`) — deterministic compact binary

**Goal:** byte-identical output for equal chunks so git dedupes unchanged chunks for free.

Layout (big-endian):
```
magic "MGC1" (4 bytes) | formatVersion (u8) | minSection (i8) | sectionCount (u8)
per present section:
  sectionIndex (u8) | paletteLen (varint)
  palette[]: idLen(varint) id(utf8) | propCount(varint) [keyLen key valLen val]...   (props sorted by key)
  bitsPerIndex (u8) | packed long[] (ceil(4096*bits/64) longs, little within long)
blockEntityCount (varint)
  [ x(varint) y(varint) z(varint) snbtLen(varint) snbt(utf8) ]...
```

**Determinism rules (tested):**
- Palette sorted by `(id, props-as-canonical-string)`; indices remapped to the sorted palette.
- `props` always serialized in key-sorted order.
- Empty sections omitted; block entities sorted by `(y, z, x)`.
- **Batch 1 = uncompressed.** Deterministic DEFLATE (zeroed timestamp / raw deflate) is a batch-2 add.

Reader reconstructs the exact `NormalizedChunk`. `serialize(deserialize(x)) == x`-bytes is a test invariant.

---

## 6. MGRF Repository Layout (`core.repo`)

```
<repoDir>/
  minegit.json                                       # { formatVersion, mcVersionsSeen[], dimensions[] }
  dimensions/<dim>/region/r.<rx>.<rz>/c.<cx>.<cz>.mgc # rx = cx>>5, rz = cz>>5
  dimensions/<dim>/blockentities/be.<cx>.<cz>.snbt    # (when present)
  level/level.dat.snbt                               # normalized world metadata
```

- `RepoLayout` maps `(DimensionId, ChunkPos) ↔ Path` deterministically.
- One MineGit repo ↔ one logical world (all its dimensions). Separate server worlds → separate repos.

---

## 7. Git Integration (`core.git`) — JGit working-tree model

- `repoDir` is a **normal** git repo (working tree on disk holding `.mgc`/SNBT files), not bare.
- **`commit()`**: for each chunk reported by `adapter.drainDirty()` (first commit: `adapter.allChunks()`),
  `adapter.read()` → `NormalizedChunk` → serialize to its MGRF path in the working tree; `git add` the
  changed paths; create a commit (`author` = player, committer = MineGit, message). Deterministic
  serialization means unchanged chunks produce no git delta.
- **`log()`**: walk commits newest-first → `CommitInfo{ id, author, message, epochSeconds }`.
- HEAD tree reads (for diff) decode `.mgc` blobs directly via JGit `TreeWalk` — no checkout needed.
- Batch 2 adds branch/checkout/fetch/push/pull/clone and the operator-credential transport.

---

## 8. Diff Engine (`core.diff`)

- `ChunkSource` abstraction yields a `NormalizedChunk` for a `(dim, pos)`; two implementations:
  **live** (`adapter.read`) and **tree** (decode HEAD/ref `.mgc` blob).
- `diff(sourceA, sourceB)`: union of chunk positions present in either; per chunk, skip if the two
  `NormalizedChunk`s are equal; else iterate all block positions, emit `BlockChange` where states differ
  (air-aware add/remove/change). Aggregate into `WorldDiff` with summary counts.
- `diff()` = `diff(treeSource(HEAD), liveSource(adapter))`; `diff(a,b)` = `diff(treeSource(a), treeSource(b))`.
- Single hot path; reused by `status`, `diff`, and (batch 2) `checkout` apply + client overlay.

---

## 9. Test Harness (`core.test`)

- **`FakeWorldAdapter`**: in-memory `Map<DimensionId, Map<ChunkPos, NormalizedChunk>>` with `setBlock`,
  dirty tracking, and the full `WorldAdapter` contract — lets every engine path run with no Minecraft.
- Required tests (batch 1):
  - model `equals`/`hashCode`;
  - `.mgc` **round-trip** + **determinism** (serialize twice → identical bytes);
  - MGRF path mapping;
  - diff-engine correctness (add/remove/change, empty chunks, multi-dimension);
  - JGit `init`/`commit`/`log` on a JUnit temp repo via `FakeWorldAdapter`;
  - `minegit-cli` smoke test.

---

## 10. `minegit-cli` (`minegit-cli` subproject)

Standalone tool operating on a world folder via `FakeWorldAdapter`, exercising core end-to-end:

| Command | Action |
|---|---|
| `minegit init` | Create a MineGit repo in the current dir |
| `minegit set <dim> <x> <y> <z> <blockid>` | Mutate the fake world (so there's something to commit) |
| `minegit commit -m "msg" [--author name:uuid]` | Snapshot → commit |
| `minegit log` | Print history |
| `minegit status` | Print `+N/−M/~K` working-vs-HEAD summary |
| `minegit diff [refA refB]` | Print the block diff (text) |

Fake-world state persists in the repo dir (e.g. a `world.json`) so the CLI is usable across invocations
and doubles as the integration harness.

---

## 11. `protocol` Module

Created now as a Java 8 module. **Batch 2** implements `DiffPayload` encode/decode, the `minegit:diff`
channel constant, and chunked framing (the contract from the umbrella doc §4.3). No batch-1 code beyond
the module skeleton and shared `WorldDiff`-adjacent constants.

---

## 12. PRD Batch 1 — Tracer-Bullet Issues

Thinnest end-to-end vertical, local-only, no Minecraft. Each is independently grabbable:

1. **Project scaffold** — Gradle monorepo; `core` (Java 8 + JGit 5.13) + `minegit-cli`; CI (build + test).
2. **Normalized model** — value types (§4) + equality + unit tests.
3. **`.mgc` codec** — deterministic binary writer/reader (§5) + round-trip & determinism tests.
4. **MGRF layout** — chunk↔path mapping, `minegit.json`, `level.dat.snbt` (§6).
5. **`FakeWorldAdapter`** — in-memory world + dirty tracking + the `WorldAdapter` interface (§9).
6. **JGit integration** — `init` / `commit` / `log` on a temp repo (§7).
7. **Diff engine** — live-vs-HEAD `WorldDiff` (§8) + tests.
8. **`minegit-cli`** — `init` / `set` / `commit` / `log` / `status` / `diff` wired end-to-end (§10).

**Acceptance for batch 1:** `minegit-cli` can `init` a repo, `set` blocks, `commit`, show `log`, and
`diff`/`status` reporting correct block-level add/remove/change — all green in CI, no Minecraft involved.

**Deferred to batch 2:** branch/checkout/fetch/push/pull/clone, GitHub transport + operator credential,
`BlockMapper` tables, MR-JAR overrides, deterministic compression, `DiffPayload` wire codec.
