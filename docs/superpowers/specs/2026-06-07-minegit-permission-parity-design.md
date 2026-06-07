# MineGit Permission Parity — Design

**Date:** 2026-06-07
**Status:** Approved (design), pending implementation plan
**Scope:** Spec 1 of 2 split out of "mod and plugin not aligned." This spec = command authorization parity. Spec 2 (diff-visualization parity across server types) is tracked separately and NOT in scope here.

## Problem

The mod (`mod/common` + Fabric/NeoForge) and the server plugin (`plugin/`) expose the same `/minegit` (`/mg`, `/git`) command set but authorize it differently, and neither matches the intended rule: **every command requires op OR an explicitly-granted permission.**

Current state:

| subcommand | plugin | mod |
|---|---|---|
| init, status, commit, log, diff | `minegit.use` — `plugin.yml` default **`true`** (every player) | permission level **0** (`Commands.LEVEL_ALL`, every player) |
| rescan | `minegit.use` (every player) | permission level **2** (op) ← tier mismatch |
| checkout | `minegit.admin` — default `op` | permission level **2** (op) |

Two divergences:

1. **Default openness.** Both sides hand the read/commit set to every player by default. The intended model is locked-by-default: nothing runs without op or a granted node.
2. **`rescan` tier mismatch.** Plugin treats `rescan` as `minegit.use`; the mod gates it at op level 2.
3. **No grantable node on the mod.** The plugin authorizes via Bukkit permission nodes a perms plugin (e.g. LuckPerms) can grant. The mod gates on raw vanilla op-level only, so a non-op can *never* be granted access mod-side — it cannot honor "or player with permission" at all.

## Goal

One authorization model, identical semantics on plugin and both mod loaders:

- Two grantable permission nodes:
  - `minegit.use` → `init`, `status`, `commit`, `log`, `diff`, `rescan`
  - `minegit.admin` → `checkout`
- Both nodes fall back to **vanilla op level 2** when no permission backend grants them.
- A source is allowed for a subcommand iff: a permission backend grants the node, **OR** the source has op level ≥ the node's fallback level (2). Console, RCON, and command blocks resolve through op-level and therefore pass.
- Locked-by-default: with no perms backend and no op status, a player can run nothing.

### Intended behavior change (flagged)

On a fresh server today, a non-op can run `status`/`log`/`diff`. After this change they cannot, unless granted `minegit.use`. This is the deliberate "lock everything" outcome.

## Design

### Plugin (small)

The plugin is already node-based and correctly tiered (`rescan` = `minegit.use`, `checkout` = `minegit.admin`; per-subcommand check at `MineGitCommand.java:110` off the `PERMISSIONS` map at `MineGitCommand.java:60-70`).

Only change: in `plugin/src/main/resources/plugin.yml`, flip `minegit.use` default `true → op`. `minegit.admin` already defaults `op`. No Java change.

Result: nothing runs without op or a perms-plugin grant of the node — exactly the target model.

### Mod (the work)

The mod authorizes in `mod/common` `MineGitCommands` via `.requires(Commands.hasPermission(permissionCheck(level)))`, where `permissionCheck(int)` maps level `0 → Commands.LEVEL_ALL` and `2 → Commands.LEVEL_GAMEMASTERS` (`MineGitCommands.java:273-282`). We replace the level mapping with a node-aware seam.

**New common seam — `MineGitPermissions`** (mirrors the existing `DiffChannel` / `DiffControlChannel` settable-handler pattern so it is unit-testable in `mod/common` without a live server):

```java
public final class MineGitPermissions {
    @FunctionalInterface
    public interface Checker {
        // true iff the source is granted `node`, OR holds op level >= fallbackLevel
        boolean allowed(CommandSourceStack source, String node, int fallbackLevel);
    }

    // Default checker = pure vanilla op-level fallback (no backend). Platform init overrides it.
    private static Checker checker = (src, node, level) -> src.hasPermission(level);

    public static void setChecker(Checker c) { checker = c; }

    public static Predicate<CommandSourceStack> require(String node, int fallbackLevel) {
        return src -> checker.allowed(src, node, fallbackLevel);
    }
}
```

**`Subcommand` enum** gains a node string alongside the existing op-level (which becomes the *fallback* level):

```java
INIT    ("init",     "minegit.use",   2),
STATUS  ("status",   "minegit.use",   2),
COMMIT  ("commit",   "minegit.use",   2),
LOG     ("log",      "minegit.use",   2),
DIFF    ("diff",     "minegit.use",   2),
CHECKOUT("checkout", "minegit.admin", 2),
RESCAN  ("rescan",   "minegit.use",   2);
```

Add `node()` accessor; `permissionLevel()` is reinterpreted as the op fallback level (all `2` now). Update the class javadoc: the previous "read/commit available to any player (level 0)" note is replaced by the locked-by-default model. `rescan` moves from the op-only tier to `minegit.use`, resolving the plugin/mod tier mismatch in the plugin's favor.

**`MineGitCommands`** — each subcommand's `.requires(...)` switches from `permissionCheck(level)` to `MineGitPermissions.require(sub.node(), sub.permissionLevel())`. Tab-completion (`MineGitCommands.java` completion filter, currently keyed on the level) keys on the same predicate so the menu stays in lock-step with executable commands. `permissionCheck(int)` is deleted.

**Platform seam wiring** (`@ExpectPlatform`-style impl per loader, registered during mod init alongside the existing `DiffControlChannel.setServerHandler(...)` hookup in `MineGitMod`):

- **Fabric** — `setChecker` delegates to fabric-permissions-api:
  `(src, node, level) -> Permissions.check(src, node, level)` (the 3-arg form already encodes "granted node OR op-level ≥ level"). No node pre-registration required.
  Build: add the dependency and bundle it jar-in-jar so it ships in the production jar — `include(modImplementation("<fabric-permissions-api coords>"))` — plus its maven repository. Exact coordinate/version verified at implementation time (the API is mapping-agnostic — it wraps `CommandSourceStack` — so it is tolerant across MC patch versions).

- **NeoForge** — built-in `PermissionAPI`; no external dependency.
  Register a `PermissionNode` for each of `minegit.use` and `minegit.admin` (type BOOL, default resolver = granted iff the player has op level ≥ 2) via `PermissionGatherEvent.Nodes`. Hold the node references. `setChecker`:
  `(src, node, level) -> src.getEntity() instanceof ServerPlayer p ? PermissionAPI.getPermission(p, nodeFor(node)) : src.hasPermission(level)` — players resolve through `PermissionAPI`, console/RCON/command-block fall back to op-level. Exact `PermissionGatherEvent.Nodes` / default-resolver signature verified against the NeoForge version in use (21.11.x).

### Data flow

`/mg <sub>` → Brigadier `.requires(predicate)` → `MineGitPermissions.checker.allowed(source, node, fallbackLevel)` → loader impl (fabric-permissions-api / NeoForge PermissionAPI / pure op-level default) → allow/deny. No new network traffic, no protocol change, no wire format.

### Testing

- **Common unit test** (`mod/common`, no live server): assert `Subcommand` → node mapping is exactly the table above; assert `MineGitPermissions.require(node, level)` calls the injected `Checker` with the right `(node, level)` and returns its verdict; with the default checker, assert pure op-level fallback (op passes, non-op denied) using a fake `CommandSourceStack` permission level.
- **Existing GameTests** unaffected — they run as op, so every command still authorizes and passes.
- **Manual smoke (both loaders + plugin):**
  - non-op: every subcommand denied;
  - op: every subcommand allowed;
  - LuckPerms grant `minegit.use` to a non-op: use-tier allowed, `checkout` still denied;
  - grant `minegit.admin`: `checkout` allowed;
  - plugin `minegit.use` default is now `op` (a fresh non-op cannot run `status`).

## Risks

- **fabric-permissions-api availability/version for MC 1.21.11.** Mitigation: verify coordinate at build; API wraps `CommandSourceStack` and is version-tolerant. Fallback if unavailable: ship the pure op-level default checker on Fabric (degrades "or player with permission" to op-only on Fabric, still locked-by-default) — but only as a last resort, since it reintroduces a (smaller) divergence.
- **NeoForge `PermissionGatherEvent.Nodes` signature drift on 21.11.x.** Mitigation: verify against the exact NeoForge version; the default resolver is the only subtle part.
- **Behavior change is user-visible** (non-ops lose read access). Intended and flagged; mention in the changelog/PR body.

## Out of scope

- Diff-visualization parity across modded vs plugin servers (spec 2).
- Per-world or per-region permissions.
- A config-file UUID→node map mod-side (LuckPerms/perms-plugin is the grant mechanism).
