# Spec B — MineGit Spigot Plugin (First Slice)

**Date:** 2026-06-04
**Status:** Approved. Implementation fanned out via the Harness fleet (Spec B batch 1).
**Parent:** [MineGit umbrella architecture](2026-06-03-minegit-architecture-design.md)
**Depends on:** `core` + `protocol` (Spec A batch 1 + 2, merged to `master`)

The first **in-game** frontend: a Spigot plugin that wraps the finished engine so you can version-control a
live world from inside Minecraft. Honors the umbrella's "one jar, 1.8 → latest" goal from day one (Spigot
1.8.8 API + runtime version modules), with a deliberately tight feature scope (core loop + local checkout).

---

## 1. Scope

### In scope
- `plugin` Gradle subproject: compiled against **spigot-api 1.8.8-R0.1-SNAPSHOT**, Java 8, shaded +
  relocated `core`/`protocol`/JGit into one jar that runs **1.8 → latest**.
- **`BlockBridge`** cross-version block I/O (Legacy direct + Modern reflection), selected by runtime version.
- Bukkit **`WorldAdapter`** implementing core's interface (read/apply, loaded-chunks, scheduler).
- Commands `/minegit` (+ `mg`, `git`): `init`, `status`, `commit`, `log`, `diff` (colored chat text),
  `checkout` (live-apply).
- One MineGit repo **per Bukkit world**, under `plugins/MineGit/repos/<world>`.
- `config.yml`, permissions, and a `runServer` (modern Paper) dev task.

### Out of scope (deferred to later Spec B batches / other specs)
- **GitHub push/pull/clone + operator credential** — engine supports it; the plugin wiring + config come next.
- **`minegit:diff` client overlay** — Spec C (client mod). Diff is chat text for now.
- **Block entities** (chests/signs NBT) — needs per-version NMS; first slice is **blocks only**.
- **Unloaded-chunk capture** — first slice snapshots **loaded chunks**; event-based dirty-tracking +
  region scanning is the immediate follow-up.
- **Biomes** — deferred; first slice versions block states only.
- **Polished hot-swap** (bulk relight + explicit chunk resend) — Bukkit's per-block update suffices for now.

---

## 2. Build & Packaging

- New subproject `plugin`; `compileOnly("...spigot-api:1.8.8-R0.1-SNAPSHOT")`; Java 8 toolchain
  (`source/targetCompatibility = 8`).
- `implementation(project(":core"))`, `project(":protocol")`; **shadowJar relocates** `core`, `protocol`,
  and `org.eclipse.jgit` to avoid clashes.
- `plugin.yml`: `main`, `api-version` omitted or `1.13`-compatible (must load on 1.8 too — keep it minimal),
  commands + permissions. `processResources` expands `${version}`.
- A `runServer` task (jpenilla run-task) on a **modern Paper** build for manual in-game testing — a
  1.8.8-API plugin loads fine on modern Paper, exercising the Modern `BlockBridge` reflection path.

---

## 3. Cross-Version Block I/O — `BlockBridge`

The 1.13 "flattening" changed block representation, and **`BlockData` is absent from the 1.8.8 compile
classpath**, so the modern path uses **reflection**.

```java
interface BlockBridge {
  BlockState read(Block block);            // Bukkit Block -> normalized BlockState
  void       write(Block block, BlockState state);
}
```

| | Read | Write |
|---|---|---|
| `LegacyBlockBridge` (1.8–1.12) | `Material.getId()` + `Block.getData()` → `LegacyBlockMapper.map(id, meta)` | `Block.setTypeIdAndData(id, data, false)` |
| `ModernBlockBridge` (1.13+) | reflection: `Block.getBlockData().getAsString()` → parse to `BlockState` | reflection: `Block.setBlockData(Server.createBlockData(String))` |

- **Selection:** detect server version once at enable (parse `Bukkit.getBukkitVersion()` /
  `Bukkit.getServer().getClass().getPackage()`), pick the bridge, cache reflected `Method` handles.
- Modern servers produce flattened ids directly, so `ModernBlockBridge` bypasses `LegacyBlockMapper`.
- **Tests:** version-string → bridge selection; legacy id/meta mapping; parse/format of flattened id
  strings (the reflection calls themselves are validated in-game / via a modern test server).

---

## 4. Bukkit `WorldAdapter`

Implements `core.adapter.WorldAdapter` over Bukkit:
- `dimensions()` → the Bukkit world's environments (overworld/nether/end) for the bound world.
- `allChunks()` / `drainDirty()` → **currently loaded chunks** (`World.getLoadedChunks()`); determinism
  dedupes unchanged ones. (Event-based dirty set is the follow-up.)
- `read(dim, pos)` → iterate the chunk's blocks via `BlockBridge.read` into `NormalizedSection`s →
  `NormalizedChunk` (blocks only; no block entities / biomes this slice). **Main thread.**
- `apply(dim, pos, changes)` → `BlockBridge.write` each `BlockChange` (Bukkit auto-notifies clients).
  **Main thread, throttled.**
- `writeChunk` → full materialize (used by clone; not exercised in this local-only slice but implemented).
- `actor()` → the command's player (name + UUID). `scheduler()` → `BukkitScheduler` main-thread executor.

**One repo per world**, path `plugins/MineGit/repos/<worldName>`; a world↔repo registry persists the binding.

---

## 5. Commands (`/minegit`, aliases `/mg`, `/git`)

| Command | Behavior |
|---|---|
| `/mg init` | Create a MineGit repo for the player's current world |
| `/mg status` | `+N/−M/~K` summary, live world vs HEAD (loaded chunks) |
| `/mg commit -m "msg"` | Snapshot loaded chunks → commit; author = player; **git on async thread** |
| `/mg log` | Recent commits (hash, author, message, time) |
| `/mg diff [refA refB]` | Default working-vs-HEAD → **colored chat text** (ADD green, REMOVE red, CHANGE yellow), `+N/−M/~K`, **truncated** past a line cap with "…and X more" |
| `/mg checkout <ref> [--force]` | Live-apply `HEAD→ref` block changes on the main thread (throttled); dirty-guard unless `--force` |

- Tab-completion for subcommands, refs, block-ids where useful.
- Permissions: `minegit.use` (default true) for read/commit; `minegit.admin` (op) for `checkout`.
- Adventure components if present on the server; fall back to legacy `ChatColor` on 1.8-era servers
  (version-aware messaging helper).

---

## 6. Threading Model

- **Reads** (`commit`/`status`/`diff` working side): `WorldAdapter.read` on the **main thread**, spread
  across ticks to avoid a spike.
- **Serialize + git** (`commit`, ref reads): **async** via `runTaskAsynchronously`.
- **Apply** (`checkout`): **main thread**, throttled to N chunks/tick.
- Core stays synchronous; the plugin owns all thread hopping. Long ops message the player on completion.

---

## 7. Testing

- **MockBukkit** unit tests: command parsing/dispatch, world↔repo binding, `BlockBridge` selection, diff
  chat formatting/truncation, dirty-guard on `checkout`.
- **Manual / `runServer`** (modern Paper): full in-game loop — the Modern reflection bridge and live apply.
- Engine logic is already covered by `core`/`protocol` tests; the plugin tests focus on the Bukkit seam.

---

## 8. Proposed Issue Breakdown (~7)

1. **Plugin scaffold** — `plugin` subproject (1.8.8 API, shadow+relocate), `plugin.yml`, main class,
   `config.yml`, `runServer` task, version detection at enable.
2. **`BlockBridge`** — `LegacyBlockBridge` + `ModernBlockBridge` (reflection) + runtime selection + tests.
   *(blocked by #1)*
3. **Bukkit `WorldAdapter`** — read/apply over `BlockBridge`, loaded-chunks, scheduler, world↔repo binding.
   *(blocked by #2)*
4. **Command framework + `init` / `status` / `log`** + tab-complete + permissions. *(blocked by #3)*
5. **`commit`** — async git, throttled main-thread reads, player author. *(blocked by #4)*
6. **`diff`** — colored chat text, working-vs-HEAD + ref-vs-ref, truncation. *(blocked by #4)*
7. **`checkout`** — live-apply (throttled), dirty-guard, `--force`. *(blocked by #5)*

## 9. Acceptance for Spec B Batch 1

On a `runServer` Paper instance: `/mg init` → build something → `/mg commit` → `/mg diff` shows the
colored block changes in chat → `/mg log` lists commits → `/mg checkout <prev>` reverts the build live.
Blocks-only, local repo, MockBukkit tests green in CI. **First in-game-testable MineGit.**

After this slice, the next Spec B batches add GitHub push/pull wiring, event-based dirty-tracking + block
entities, and (with Spec C) the `minegit:diff` client overlay.
