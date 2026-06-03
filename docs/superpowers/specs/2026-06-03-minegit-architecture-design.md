# MineGit — System Architecture (Umbrella Design)

**Date:** 2026-06-03
**Status:** Approved (umbrella map). Each component below gets its own brainstorm → spec → plan → build cycle.
**Inspiration:** https://www.youtube.com/watch?v=ZdM-iNpv3nU

---

## 1. What MineGit Is

MineGit is a **version-control system for Minecraft worlds**, exposing a git-like command set in-game
(`/minegit`, aliases `/mg` and `/git`) and storing world history in a **GitHub repository**. It targets
both classic servers (via a Spigot plugin spanning Minecraft 1.8 → latest) and modded servers (a
Forge/Fabric multiloader mod on 1.21.11), plus a client mod that **visualizes block-level diffs** in-world.

### Goals
- `commit`, `log`, `branch`, `checkout`, `diff`, `fetch`, `push`, `pull`, `clone` over a Minecraft world.
- **Block-level diffs** (`add` / `remove` / `change`) — not opaque binary file diffs.
- **Multiversion**: a single normalized representation that makes a 1.8 world and a 1.21 world comparable.
- **GitHub-native**: real git repos pushed to GitHub; access control via native collaborators/teams.

### Non-goals (recorded; may revisit later)
- **Merge** (3-way or block conflict resolution) — deferred; architecture must not preclude it.
- **Cross-protocol live overlay** (a real 1.8 *server* + a modern *client*) — needs ViaVersion; out of scope.
- **Per-player GitHub auth / hosted backend / GitHub App** — explicitly rejected in favor of a single
  server-side operator credential (see §8).
- **Raw `.mca` full-fidelity backup (Git LFS)** — possible later feature, not in the initial model.

---

## 2. Component Topology

Five Gradle modules → four shippable artifacts (two shared libraries + three frontends; `protocol` ships
inside the others).

```
                         ┌────────────────────────────┐
                         │   minegit-core             │  plain Java library
                         │  • JGit wrapper (history,  │  (Multi-Release JAR:
                         │    branch, push/fetch)     │   Java 8 base + JGit 5.13,
                         │  • Normalized chunk model  │   Java 17/21 overrides w/ modern JGit)
                         │  • Block-diff engine       │
                         │  • BlockMapper tables      │
                         │  • WorldAdapter INTERFACE   │
                         │  • MineGitService (ops)    │
                         └───────────┬────────────────┘
                                     │ depends on (shared)
                         ┌───────────┴────────────────┐
                         │   minegit-protocol         │  DiffPayload codec,
                         │   (channel + wire format)  │  minegit:diff channel, framing
                         └───────────┬────────────────┘
            implements WorldAdapter  │  implements WorldAdapter
        ┌────────────────────────┐   │   ┌────────────────────────┐
        │  minegit-plugin        │   │   │  minegit-server-mod    │
        │  Spigot API 1.8.8      │◄──┴──►│  Architectury (Java 21)│
        │  runs 1.8 → latest     │       │  Forge/Fabric 1.21.11  │
        │  version-module adapters│       │  @ExpectPlatform       │
        └───────────┬────────────┘       └───────────┬────────────┘
                    │   minegit:diff (block-delta payload)        │
                    └───────────────────┬─────────────────────────┘
                                        ▼
                         ┌────────────────────────────┐
                         │  minegit-client            │  Architectury 1.21.11
                         │  depends on protocol ONLY  │  renders diff overlay,
                         │  (no git, no core)         │  HUD, toggle keybind
                         └────────────────────────────┘

   GitHub  ◄── push/fetch via server-side operator credential (PAT / SSH / credential helper)
```

**Key principle:** `core` never imports a Minecraft class. It talks to the world only through the
`WorldAdapter` interface, which each frontend implements for its platform/version.

---

## 3. Versioning Strategy — Two Independent Axes

MineGit spans two unrelated version dimensions; conflating them is a common mistake, so they are handled
by different mechanisms.

### Axis A — JVM version → **Multi-Release JAR**
`core`/`protocol` compile to a **Java 8 baseline** (so a 1.8.8 server on Java 8 can load them) using
**JGit 5.13.x** (the last Java-8-compatible JGit line). `META-INF/versions/17/` (and/or `/21/`) carry
**override classes that use modern JGit** when the same jar runs on a newer JVM. This removes the
"permanently stuck on old JGit" risk: we only use JGit 5.13 when actually on Java 8.

### Axis B — Minecraft/Bukkit API version → **runtime-selected version modules**
The Bukkit block representation changed at the **1.13 "flattening"** (legacy numeric id + `MaterialData`
→ modern string-id `BlockData`), and that happened **while Java 8 was still standard** — so Java version
does **not** identify the Minecraft API era. The `plugin` therefore **detects the server version at
runtime** and loads the matching adapter module (`v1_8`, `v1_13`, `v1_21`, …), each implementing
`WorldAdapter` against that era's API and funneling into the same normalized model. This is the classic
NMS-version-module pattern — no reflection spaghetti, one clean adapter per era.

The `server-mod` targets a single version (1.21.11) and instead uses Architectury `@ExpectPlatform` to
split **loader**-specific bits (Fabric vs (Neo)Forge), not version-specific bits.

---

## 4. Shared Data Formats

### 4.1 On-disk repository format (MGRF) — lives in the GitHub repo
```
<repo>/
  minegit.json                                  # format version, source MC versions seen, dimensions
  dimensions/
    overworld/region/r.<rx>.<rz>/c.<cx>.<cz>.mgc    # one normalized chunk per file
    overworld/blockentities/be.<cx>.<cz>.snbt       # tile entities (chests, signs) as SNBT
    the_nether/… the_end/…
  level/level.dat.snbt                          # world metadata, normalized SNBT (human-readable)
  players/<uuid>.snbt                           # optional
```
- **`.mgc`** = per-section (16³ sub-chunk) **palette + bit-packed indices**, deterministic byte ordering so
  an unchanged chunk re-serializes **byte-identical** → git dedupes it for free. Empty sections omitted.
- Only **changed** `.mgc` files are rewritten per commit → commit size is proportional to what changed,
  not to world size.

### 4.2 Normalized block model — the multiversion layer (in `core`)
```
BlockState { id: "minecraft:stone", props: { facing:"north", … } }   # flattened string ids
NormalizedSection { palette: BlockState[], indices: int[4096] }
NormalizedChunk   { cx, cz, sections[], biomes, blockEntities[] }
```
Each `WorldAdapter` converts its version-specific block data into this canonical form via a core-provided
**`BlockMapper`**. **1.8 numeric id + metadata are mapped to modern flattened string ids** using a bundled
mapping table (the 1.13 flattening map), so stone from a 1.8 server and a 1.21 server normalize to the same
`minecraft:stone` and are directly comparable. This mapping table is its own bounded sub-component.

### 4.3 Block-delta wire payload — server → client (in `protocol`)
```
DiffPayload { fromRef, toRef, dimension,
  chunks: [ ChunkDelta { cx, cz,
    changes: [ BlockChange { x,y,z, kind: ADD|REMOVE|CHANGE, old: BlockState?, new: BlockState? } ] } ] }
```
- Sent over the custom payload channel **`minegit:diff`**, compact binary (palette-compressed,
  VarInt-framed), **chunked across multiple packets** because plugin-message payloads are size-capped
  (~32 KB on legacy). Includes sequence numbers + reassembly.

---

## 5. Git Model: Working Tree, Local & Remote Refs

MineGit uses git's full ref model, stored as real refs by JGit:

- **Working tree** = the **live world** (the current, uncommitted "current work").
- **Local branches** = `main`, etc. — local refs.
- **Remote-tracking refs** = `origin/main`, etc. — updated by `fetch`, never by local edits.
- **HEAD** = the local committed state the working tree is compared against.

**`diff` default = working tree (live world) vs local `HEAD`** — "what have I changed since my last local
commit," exactly like `git diff`. The block-diff engine takes the **live world (via `WorldAdapter.read` of
dirty chunks) as a first-class operand**, not only ref-vs-ref. Remote is never the default diff target.

---

## 6. Command Set (`/minegit`, aliases `/mg`, `/git`)

**Tier 1 — MVP core**
| Command | Meaning |
|---|---|
| `/mg init [remote-url]` | Start versioning this world; optionally bind a GitHub remote |
| `/mg status` | Summary of changed chunks/blocks: **working tree vs local HEAD** |
| `/mg commit -m "msg"` | Snapshot the working tree → a commit (changed chunks only); author = in-game player |
| `/mg log` | Commit history (hash, author, message, time) |
| `/mg diff [refA] [refB]` | Default: working tree vs HEAD → client overlay. Explicit refs supported. |
| `/mg fetch` | Update remote-tracking refs (`origin/*`); no world change |
| `/mg push` | Push local commits to GitHub (operator credential) |
| `/mg pull` | `fetch` + live-apply target onto the world |
| `/mg remote add\|set <url>` | Manage the GitHub remote |

**Tier 2 — branching & navigation**
| Command | Meaning |
|---|---|
| `/mg branch [name]` | Create / list **local + remote-tracking** branches |
| `/mg checkout <branch\|commit>` | **Live hot-swap** the world to that state ⚠️ |
| `/mg clone <url>` | Pull a world from GitHub into a new world |
| `/mg revert <commit>` | Undo a commit's block changes |

**Tier 3 — deferred** (`/mg merge`, `/mg reset --hard`, `/mg stash`)

---

## 7. Data Flows

Cross-cutting rule: **Minecraft world reads/writes run on the server main thread (throttled across ticks);
git + network run on an async worker.**

### ① `commit -m "msg"`
1. Adapter `drainDirty()` → chunks edited since last commit (first commit = full scan). *(main)*
2. `WorldAdapter.read()` each dirty chunk → `NormalizedChunk`, spread over ticks. *(main)*
3. Worker: serialize → deterministic `.mgc`, flush block-entities + `level.dat.snbt`. *(async)*
4. JGit stage + commit. **author = in-game player** (name + UUID), committer = MineGit. *(async)*

### ② `diff` (default working-vs-HEAD) → client overlay
1. Read dirty chunks live → normalized; load HEAD's `.mgc` for the same chunks. *(main read, async decode)*
2. Block-diff engine → `BlockChange[]` → `DiffPayload` per dimension (identical chunks skipped by hash).
3. Encode + **chunk-transfer** over `minegit:diff` to the requesting player. *(async → packets)*
4. Client renders overlay: translucent boxes ADD=green / REMOVE=red / CHANGE=yellow, `+N/−M/~K` HUD, toggle.

### ③ `checkout <ref>` / `pull` — live hot-swap apply
1. Diff engine computes `HEAD → targetRef` as `BlockChange[]` (checkout *is* a diff you apply). *(async)*
2. `WorldAdapter.apply()` on the **main thread, throttled to N chunks/tick**: set blocks, restore
   block-entities, **relight**, **resend affected chunks** to online players.
3. Move the branch/HEAD ref; optionally fire ② so players see what changed.

### ④ `fetch` / `push` / `clone` — auth
1. Core builds a JGit transport using the **server-side operator credential** (§8). *(async)*
2. `fetch` updates remote-tracking refs; `push` uploads commits; `pull` = `fetch` + ③; `clone` = fetch into
   a fresh repo then `WorldAdapter.writeChunk` materializes a playable world from the `.mgc` files.

**Reuse note:** the block-diff engine is the single hot path behind `status`, `diff`, the overlay, and
`checkout`/`pull` apply — built once, pointed at different operand pairs.

---

## 8. Authentication — Server-Side Operator Credential (no app, no backend)

There is **no GitHub App and no hosted MineGit service.** The server owner configures **one** credential on
the server, and all pushes/fetches use it:

| Mechanism | Setup | Notes |
|---|---|---|
| **Fine-grained PAT** | Owner creates a token scoped to just the repo, sets it in server config | Smallest blast radius |
| **SSH deploy key** | Owner uses a `git@github.com:…` remote with an existing key | No token handling; JGit speaks SSH |
| **System git / credential helper** | Reuse the machine's existing git auth | Nothing to paste |

- **Commit author** = the in-game player who ran the command (name + UUID); the credential is only the
  transport identity.
- **Access control** is native GitHub: the owner adds that one account as a collaborator / team member.
- The **client mod never holds a token** — it only receives the diff overlay.

---

## 9. `WorldAdapter` Contract & Module Responsibilities

### The seam (defined in `core`, implemented by `plugin` and `server-mod`)
```java
interface WorldAdapter {
  List<DimensionId> dimensions();
  Set<ChunkPos>     drainDirty(DimensionId dim);   // chunks edited since last drain
  Iterable<ChunkPos> allChunks(DimensionId dim);   // first-commit full scan

  NormalizedChunk read(DimensionId dim, ChunkPos pos);                 // main thread
  void apply(DimensionId dim, ChunkPos pos, List<BlockChange> edits);  // main thread, throttled
  void writeChunk(DimensionId dim, NormalizedChunk chunk);             // clone materialize
  void resendChunk(DimensionId dim, ChunkPos pos);                     // relight + push to players

  PlayerRef          actor();        // commit author identity
  MainThreadExecutor scheduler();    // post work back to the tick thread
}
```

| Module | Java | Depends on | Owns |
|---|---|---|---|
| **`core`** | 8 base (MR-JAR 17/21) | JGit 5.13 / modern | JGit wrapper, MGRF (de)serialization, **block-diff engine**, normalized model + `BlockMapper` tables, transport/credential handling, `WorldAdapter` interface, `MineGitService`. **No MC imports.** |
| **`protocol`** | 8 | — | `DiffPayload` codec, `minegit:diff` channel, chunked framing. Shared everywhere. |
| **`plugin`** | 8 | core, protocol, Spigot 1.8.8 API | Commands (`/minegit`,`/mg`,`/git`) + tab-complete, permissions, config (credential + repo path), **runtime-version-module `WorldAdapter`s** (`v1_8`/`v1_13`/`v1_21`/…), channel registration + chunked send, scheduler. Shades+relocates core/protocol/JGit. |
| **`server-mod`** | 21 | core, protocol, Architectury 1.21.11 | Same commands via **Brigadier**, `WorldAdapter` over Mojang-mapped server API, **`@ExpectPlatform`** for Fabric/(Neo)Forge packet/registry/scheduler bits. |
| **`client`** | 21 | **protocol only** | Receive + reassemble `DiffPayload`, **render overlay**, HUD, toggle keybind, optional commit-picker. No git, no core. |

---

## 10. Build & Test Tooling

- **Gradle monorepo, Kotlin DSL.** `core`/`protocol` = `java-library`, Java 8 baseline + **Multi-Release**
  source sets for 17/21. `plugin` builds against **spigot-api 1.8.8-R0.1-SNAPSHOT** (BuildTools/mirror),
  **shades + relocates** core/protocol/JGit into one MR-JAR. `server-mod` + `client` use
  **architectury-loom**, MC 1.21.11, Java 21.
- **Tests:** `core` → JUnit 5 (MGRF round-trip, **diff-engine correctness**, `BlockMapper` legacy↔flatten,
  JGit ops on temp repos); `protocol` → payload encode/decode + chunking round-trip; `plugin` →
  **MockBukkit** for commands; `server-mod` → **GameTests** for adapter read/apply against a live test
  world; `client` → manual/visual. CI: GitHub Actions matrix.

---

## 11. Top Risks

1. **Block-mapping fidelity 1.8→1.21** — large, error-prone legacy↔flattening table. *Mitigate:* table-driven,
   start with common blocks, round-trip tests, expand over time.
2. **First-commit cost on big worlds** — many files / large initial repo. *Mitigate:* incremental after
   first commit; optional dimension/region scoping; warn + cap on huge worlds.
3. **Live hot-swap correctness** — relight/resend across versions with players present. *Mitigate:* throttle
   per tick, force chunk resend, heavy testing.
4. **Chunked payload reliability** — size caps/ordering on plugin messaging. *Mitigate:* sequence +
   reassembly + paging.
5. **MR-JAR + shading complexity** — two JGit lines, relocation, version source sets. *Mitigate:* isolate in
   `core` build, test on both Java 8 and 21 runtimes.
6. **Operator credential handling** — a real secret in server config. *Mitigate:* support SSH/credential
   helper to avoid plaintext tokens; document least-privilege fine-grained PATs.

---

## 12. Decomposition — Sequenced Sub-Project Specs

This document is the **umbrella**. Each piece gets its own brainstorm → spec → plan → build. Recommended
order delivers a usable vertical slice before the modded frontend:

1. **Spec A — `core` + `protocol`** (engine + formats). Highest-risk; verifiable **without Minecraft** via a
   fake in-memory `WorldAdapter` + a small CLI harness. ← **start here**
2. **Spec B — `plugin`** (first real frontend, widest reach; gets MineGit running on a server).
3. **Spec C — `client`** (diff overlay; pairs with B to see diffs in-world).
4. **Spec D — `server-mod`** (Architectury frontend for modded servers).

**Next step:** brainstorm **Spec A (`core` + `protocol`)** as its own focused design.
