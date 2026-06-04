# MineGit

**Git for your Minecraft world.** MineGit snapshots a live world into a real git repository, with
block-level diffs (add / remove / change), commits, branches, and checkout — all driven from in-game
`/mg` commands.

Build something, `/mg commit` it, keep building, then `/mg diff` to see exactly which blocks changed or
`/mg checkout <ref>` to roll the world back — live, no restart.

## Frontends

MineGit ships the same engine behind two frontends:

| Frontend | Runs on | Minecraft | Use it for |
|----------|---------|-----------|------------|
| **Plugin** (`plugin`) | Paper / Spigot / Bukkit servers | 1.8 → latest (one jar) | Server worlds |
| **Mod** (`mod`) | Fabric **and** NeoForge | 1.21.11 | **Single-player** (loads into the integrated server) — and dedicated servers |

The plugin is server-only. The mod loads into the client's integrated server, so solo players get `/mg`
with no separate server process.

## Commands

`/minegit` — aliases `/mg` and `/git`:

| Command | What it does |
|---------|--------------|
| `/mg init` | Create a MineGit repo for your current world / level |
| `/mg status` | Summary of changes (`+added / −removed / ~changed`) vs the last commit |
| `/mg commit -m "msg"` | Snapshot the loaded chunks as a commit (author = you) |
| `/mg log` | Recent commits (hash, author, message, time) |
| `/mg diff [refA refB]` | Colored block-change diff in chat (default: working vs `HEAD`) |
| `/mg checkout <ref> [--force]` | Apply a commit's blocks to the live world (reverting/rolling forward) |

`checkout` is op-gated (permission level 2 on the mod / `minegit.admin` on the plugin).

## Current scope

Blocks-only, local repository per world/level. Deferred for later batches: GitHub push/pull, block
entities (chests/signs NBT), biomes, unloaded-chunk capture, and the client-side diff overlay.

## Building

Requires a JDK 21 (the engine compiles to Java 8 bytecode; the mod targets Java 21).

```bash
./gradlew build          # builds every subproject + both mod loader jars

# Run the headless block-I/O proof (place → commit → mutate → checkout → assert reverted):
./gradlew :mod:fabric:runGametest
./gradlew :mod:neoforge:runGameTestServer

# Dev clients (open a single-player world and run /mg):
./gradlew :mod:fabric:runClient
./gradlew :mod:neoforge:runClient
```

Artifacts:

- Plugin jar — `plugin/build/libs/`
- Fabric mod — `mod/fabric/build/libs/minegit-fabric-*.jar`
- NeoForge mod — `mod/neoforge/build/libs/minegit-neoforge-*.jar`

## Project layout

| Module | Purpose |
|--------|---------|
| `core` | Engine: normalized world model, `.mgc` codec, JGit operations, block-diff engine |
| `protocol` | Wire codec (`DiffPayload`) for diff transport |
| `minegit-cli` | Standalone command-line client over `core` |
| `plugin` | Paper/Spigot/Bukkit frontend (`BlockBridge` cross-version block I/O) |
| `mod` | Architectury multiloader frontend (`common` / `fabric` / `neoforge`) |

Base package: `net.rainbowcreation.vocanicz.minegit`

## Authors

RainBowCreation · VocanicZ · NathanielSong

## License

MIT
