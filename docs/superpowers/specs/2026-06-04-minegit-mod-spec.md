# Spec D — MineGit Architectury Server-Mod (single-player capable)

**Date:** 2026-06-04
**Status:** Approved. Implementation to be fanned out via the Harness fleet (Spec D batch 1).
**Parent:** [MineGit umbrella architecture](2026-06-03-minegit-architecture-design.md)
**Depends on:** `core` + `protocol` (Spec A batch 1 + 2, merged to `master`)
**Sibling:** [Spec B — Spigot plugin](2026-06-04-minegit-plugin-spec.md) (server-only frontend)

The first **single-player-capable** frontend: a Forge/Fabric mod (one shared codebase via Architectury)
that loads into the client's **integrated server**, so a solo player gets `/mg` with no separate server
process. The plugin (Spec B) is server-only; a localhost Paper server is its only solo route. This mod
closes that gap. It reuses the finished `core` + `protocol` engine **unchanged** — it adds only a new
frontend (a modern, reflection-free `WorldAdapter` + Brigadier commands), and ships JGit bundled into the
mod jar. Modern-only (MC 1.21.11): the world is already flattened, so there is **no `LegacyBlockMapper`**.

---

## 1. Scope

### In scope
- A new **Architectury multiloader** mod: subprojects `common` / `fabric` / `neoforge`, building **both** a
  Fabric and a NeoForge jar from one shared codebase. MC **1.21.11**, **Java 21**, Mojang mappings.
- Bundled, **relocated** `core` / `protocol` / JGit (+ transitives) into each loader jar, packaged per
  loader (`include`/jar-in-jar on Fabric, `jarJar` on NeoForge) so it coexists with MC's own log4j/slf4j.
- A modern **`WorldAdapter`** implementing core's interface directly against MC types (no reflection):
  read/apply, loaded chunks, server-thread scheduler.
- Brigadier commands `/minegit` (+ `mg`, `git`): `init`, `status`, `commit`, `log`, `diff` (chat
  Components), `checkout` (live-apply).
- One MineGit repo **per level (dimension save)**, under the world's save folder; a level↔repo binding.
- **GameTests** (Fabric + NeoForge) that headlessly place→commit→mutate→checkout→**assert the world
  reverted** — CI-proving the real block I/O.
- Dev launch via `:fabric:runClient` / `:neoforge:runClient` → open a single-player world → run `/mg`.

### Out of scope (deferred to later Spec D batches / other specs)
- **GitHub push/pull/clone + operator credential** — engine supports it; mod wiring + config come later.
- **`minegit:diff` client overlay** — Spec C (client rendering). Diff is chat text for now.
- **Block entities** (chests/signs NBT) — first slice is **blocks only**.
- **Unloaded-chunk capture** — first slice snapshots **loaded chunks**; event-based dirty-tracking +
  region scanning is the follow-up.
- **Biomes** — deferred; first slice versions block states only.
- **`LegacyBlockMapper` / pre-1.13 support** — this mod is modern-only (1.21.11); not applicable.
- **Polished hot-swap** (bulk relight, explicit chunk resend) — `level.setBlock` flags suffice for now.

---

## 2. Build & Packaging (Architectury)

- Root project with `architectury-loom` + the `architectury-plugin`; `settings.gradle` includes `common`,
  `fabric`, `neoforge`. `gradle.properties` pins (from one released toolchain line):
  `minecraft_version=1.21.11`, `enabled_platforms=fabric,neoforge`, plus aligned `architectury_version`,
  `fabric_loader_version`, `fabric_api_version`, `neoforge_version`, `loom_version`. Java 21 toolchain.
- `common/` compiles against vanilla MC + Architectury only (no `net.neoforged.*` / `net.fabricmc.*`).
  Loader-specific bits go behind **`@ExpectPlatform`** with matching `*Impl` classes in each platform.
- `fabric/` (`ModInitializer` entrypoint, `fabric.mod.json`) and `neoforge/` (`@Mod` entrypoint,
  `neoforge.mods.toml`) each produce a `remapJar`. Shared assets live in `common/src/main/resources`.
- **`core` / `protocol` dependency:** the engine is Java 8 bytecode + JGit 5.13.x; a Java 21 mod loads it
  fine. The mod consumes them as normal `implementation(project(...))` deps.

### Top risk — bundling JGit into a mod jar
MC ships its own log4j / slf4j on the mod classpath, and JGit drags in `slf4j`, `sshd`, `commons-*`,
`javaewah`, etc. Shipping these unrelocated into a Fabric/NeoForge jar risks classpath clashes and
duplicate-provider errors. Mitigation (handled in the scaffold issue, before any feature work):
- **Relocate** `org.eclipse.jgit` and the engine packages into the mod's namespace
  (e.g. `com/minegit/mod/libs/...`), mirroring what the plugin did (`com/minegit/plugin/libs/...`).
- **Package the bundled libs per loader:** Fabric `include` (jar-in-jar) / NeoForge `jarJar`, so they are
  nested as mod-jar dependencies rather than flattened onto the global classpath.
- Provide MC's logging facade so JGit's slf4j calls bind to a present implementation rather than a bundled
  duplicate (relocate or exclude the bundled slf4j binding as appropriate).
- The scaffold issue's acceptance includes both loaders **launching cleanly with the mod present** (analog
  to the plugin's Paper-boot check) — i.e. JGit loads and `/mg init` succeeds in a dev client.

---

## 3. Modern `WorldAdapter` (no reflection)

Compiled directly against MC 1.21.11 types — unlike the plugin's reflection bridge, this adapter calls MC
APIs directly. Implements `core.adapter.WorldAdapter`:

| Operation | Implementation |
|---|---|
| read state | `ServerLevel.getBlockState(BlockPos)` → registry key from `BuiltInRegistries.BLOCK.getKey(state.getBlock())` + `state.getValues()` (property map) → core `BlockState`. **Already flattened — no `LegacyBlockMapper`.** |
| write state | core `BlockState` → block-state string → `BlockStateParser.parseForBlock(...)` → `ServerLevel.setBlock(pos, state, flags)` |
| dimensions | the single `ServerLevel` bound to the repo (one repo per level — `dimensions()` returns that one level) |
| loaded chunks | enumerate currently-loaded chunks from `ServerLevel` (determinism dedupes unchanged ones) |
| scheduler | server-thread executor via `server.execute(Runnable)` (or `level.getServer().execute`) |
| actor | the command's player (name + UUID); console/GameTest actor where no player |

- **One repo per level**, path under the world save folder (e.g. `<saveDir>/minegit/<levelKey>`); a
  level↔repo binding persists the mapping. (Integrated server and dedicated server both expose `ServerLevel`,
  so the same adapter serves single-player and a future server deployment.)
- Reads run on the **server thread** (spread across ticks); apply runs on the **server thread, throttled**.

---

## 4. Commands (Brigadier `/minegit`, aliases `/mg`, `/git`)

Registered via Architectury's command-registration event (or `@ExpectPlatform` shim) so one definition
serves both loaders.

| Command | Behavior |
|---|---|
| `/mg init` | Create a MineGit repo for the player's current level |
| `/mg status` | `+N/−M/~K` summary, live level vs HEAD (loaded chunks) |
| `/mg commit -m "msg"` | Snapshot loaded chunks → commit; author = player; **git off the server thread** |
| `/mg log` | Recent commits (hash, author, message, time) |
| `/mg diff [refA refB]` | Default working-vs-HEAD → chat **Components** (ADD green, REMOVE red, CHANGE yellow), `+N/−M/~K`, **truncated** past a line cap with "…and X more" |
| `/mg checkout <ref> [--force]` | Live-apply `HEAD→ref` block changes on the server thread (throttled); dirty-guard unless `--force` |

- `checkout` gated on **permission level 2** (op); read/commit available to any player.
- Tab-completion for subcommands, refs, and block-ids where useful.
- Output as MC text `Component`s (Brigadier-native — no Adventure dependency).

---

## 5. Threading Model

- **Reads** (`commit` / `status` / `diff` working side): on the **server thread**, spread across ticks to
  avoid a tick spike.
- **Serialize + git** (`commit`, ref reads): **off-thread** (a background executor), then hop back to the
  server thread to message the player.
- **Apply** (`checkout`): **server thread**, throttled to N chunks/tick.
- Core stays synchronous; the mod owns all thread hopping via `server.execute(...)`.

---

## 6. Testing

- **GameTests** (Fabric `fabric-gametest-api-v1` + NeoForge `@GameTest`): headless, in a real server level —
  place a known structure → `init` → `commit` → mutate blocks → `checkout` the commit → **assert every
  block reverted** (and a no-op `checkout` is clean). This is the headless place→commit→checkout→assert
  loop that the plugin could not auto-test; it runs in **CI** on both loaders.
- **JUnit** unit tests for loader-agnostic seams (command parsing/dispatch, diff Component formatting +
  truncation, level↔repo binding, dirty-guard logic) where they don't require a running server.
- Engine logic is already covered by `core` / `protocol` tests; mod tests focus on the MC seam.
- Manual: `:fabric:runClient` / `:neoforge:runClient` → SP world → full `/mg` loop (the human SP test).

---

## 7. Proposed Issue Breakdown (~8, chained)

1. **Mod scaffold** — Architectury root + `common`/`fabric`/`neoforge`; `gradle.properties` pins; entrypoints;
   `@ExpectPlatform` skeleton; bundle + **relocate** `core`/`protocol`/JGit with per-loader packaging
   (`include` / `jarJar`); both loaders **launch cleanly with the mod + JGit present** (`/mg` registers).
2. **BlockState bridge** — MC `BlockState` ↔ core `BlockState` (registry key + property map ↔ block-state
   string via `BlockStateParser`); round-trip unit tests. *(blocked by #1)*
3. **`WorldAdapter`** — read/apply over the bridge, loaded-chunks enumeration, server-thread scheduler,
   level↔repo binding. *(blocked by #2)*
4. **Command framework + `init` / `status` / `log`** — Brigadier registration (both loaders), tab-complete,
   permission-level gating. *(blocked by #3)*
5. **`commit`** — off-thread git, throttled server-thread reads, player author. *(blocked by #4)*
6. **`diff`** — colored chat Components, working-vs-HEAD + ref-vs-ref, truncation. *(blocked by #4)*
7. **`checkout`** — live-apply (throttled), dirty-guard, `--force`. *(blocked by #5)*
8. **GameTests** — Fabric + NeoForge place→commit→mutate→checkout→assert-reverted; wire into CI.
   *(blocked by #7)*

---

## 8. Acceptance for Spec D Batch 1

- `./gradlew build` produces both a Fabric and a NeoForge jar with JGit bundled, relocated, and loadable.
- `:fabric:runClient` and `:neoforge:runClient` → open a **single-player** world → `/mg init` → build
  something → `/mg commit` → `/mg diff` shows colored block changes in chat → `/mg log` lists commits →
  `/mg checkout <prev>` reverts the build live.
- **GameTests green on both loaders in CI** (the place→commit→mutate→checkout→assert-reverted loop).
- Blocks-only, local repo, modern-only (1.21.11). **First single-player MineGit, with auto-tested block I/O.**

After this slice, later Spec D batches add GitHub push/pull wiring, event-based dirty-tracking + block
entities, biomes, and (with Spec C) the `minegit:diff` client overlay.
