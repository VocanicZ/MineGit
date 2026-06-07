# MineGit Permission Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/minegit` authorization identical on the plugin and both mod loaders — locked-by-default, every subcommand allowed only for op or a granted `minegit.use`/`minegit.admin` permission node.

**Architecture:** A new loader-agnostic seam `MineGitPermissions` (a settable `Checker`, mirroring the existing `DiffControlChannel` settable-handler pattern) decides each Brigadier `.requires(...)` gate from a permission node + an op fallback level. The default checker is pure vanilla op-level; each loader installs a node-aware checker at init — Fabric via fabric-permissions-api, NeoForge via the built-in `PermissionAPI`. The plugin is already node-based; it only needs its `plugin.yml` default flipped from `true` to `op`.

**Tech Stack:** Java 21 (mod) / Java 8 (plugin), Architectury multiloader, Brigadier, Minecraft 1.21.11 (`net.minecraft.server.permissions.PermissionCheck`), fabric-permissions-api (Fabric), NeoForge `PermissionAPI` (built in), JUnit 5 + Mockito, Gradle/loom.

**Spec:** `docs/superpowers/specs/2026-06-07-minegit-permission-parity-design.md`

---

## File Structure

**Mod — common (`mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/`):**
- `command/Subcommand.java` — *modify*: add `node()`; reinterpret level as op-fallback (all `2`); update javadoc.
- `command/permission/MineGitPermissions.java` — *create*: the `Checker` seam + `require(node, level)` predicate + default op-level checker.
- `command/MineGitCommands.java` — *modify*: `.requires(...)` keys on `MineGitPermissions.require(node, level)`; delete `permissionCheck(int)` and the `PermissionCheck` import.

**Mod — common tests (`mod/common/src/test/java/.../mod/command/`):**
- `SubcommandTest.java` — *modify*: assert the new node mapping + all-level-2 fallback.
- `permission/MineGitPermissionsTest.java` — *create*: the seam delegates to the installed checker.

**Mod — Fabric (`mod/fabric/`):**
- `build.gradle.kts` — *modify*: add fabric-permissions-api dependency (jar-in-jar) + its maven repo.
- `src/main/java/.../mod/command/permission/fabric/MineGitPermissionsImpl.java` — *create*: installs a fabric-permissions-api checker.
- `src/main/java/.../mod/MineGitFabric.java` — *modify*: call the installer in `onInitialize`.

**Mod — NeoForge (`mod/neoforge/`):**
- `src/main/java/.../mod/neoforge/MineGitNeoForgePermissions.java` — *create*: declares the two `PermissionNode`s, registers them on `PermissionGatherEvent.Nodes`, installs a `PermissionAPI` checker.
- `src/main/java/.../mod/neoforge/MineGitNeoForge.java` — *modify*: wire the installer + node-gather listener in the constructor.

**Plugin (`plugin/`):**
- `src/main/resources/plugin.yml` — *modify*: `minegit.use` default `true → op`.
- `src/main/java/.../plugin/command/MineGitCommand.java` — *modify*: update the `PERM_USE` javadoc (no logic change).

---

## Task 1: Subcommand — add permission node, fallback-level all op

**Files:**
- Modify: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/Subcommand.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/SubcommandTest.java`

- [ ] **Step 1: Update the failing test first**

Replace the body of `SubcommandTest` (keep the package, the existing imports, and the `literalsAreThe...` and `byLiteral...` tests unchanged). Replace the two permission tests with node-aware ones:

```java
    @Test
    void everySubcommandFallsBackToVanillaOpLevelTwo() {
        // Locked-by-default model: no command is open to non-ops; the op fallback is vanilla op (2).
        for (Subcommand sub : Subcommand.values()) {
            assertEquals(Subcommand.OP_PERMISSION_LEVEL, sub.permissionLevel(),
                    sub + " should fall back to vanilla op level " + Subcommand.OP_PERMISSION_LEVEL);
        }
    }

    @Test
    void checkoutIsTheOnlyAdminNodeEverythingElseIsUse() {
        for (Subcommand sub : Subcommand.values()) {
            String expected = sub == Subcommand.CHECKOUT ? "minegit.admin" : "minegit.use";
            assertEquals(expected, sub.node(), sub + " should map to " + expected);
        }
    }

    @Test
    void opSeamIsVanillaLevelTwo() {
        assertEquals(2, Subcommand.OP_PERMISSION_LEVEL);
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :mod:common:test --tests 'net.rainbowcreation.vocanicz.minegit.mod.command.SubcommandTest'`
Expected: FAIL — `node()` does not exist (compile error), and `permissionLevel()` returns `0` for several values.

- [ ] **Step 3: Rewrite the enum**

Replace the enum constants block and accessors in `Subcommand.java`. The constructor now takes `(literal, node, fallbackOpLevel)`; every command falls back to op level 2, and `node()` returns the grantable node. Replace lines 19–50 (the `enum ... {` through the `permissionLevel()` accessor) with:

```java
public enum Subcommand {
    INIT("init", "minegit.use", OP_PERMISSION_LEVEL),
    STATUS("status", "minegit.use", OP_PERMISSION_LEVEL),
    COMMIT("commit", "minegit.use", OP_PERMISSION_LEVEL),
    LOG("log", "minegit.use", OP_PERMISSION_LEVEL),
    DIFF("diff", "minegit.use", OP_PERMISSION_LEVEL),
    CHECKOUT("checkout", "minegit.admin", OP_PERMISSION_LEVEL),
    RESCAN("rescan", "minegit.use", OP_PERMISSION_LEVEL);

    /**
     * Vanilla op permission level (2). Every subcommand falls back here when no permission backend
     * grants its node — the locked-by-default model: a non-op with no granted node can run nothing.
     */
    public static final int OP_PERMISSION_LEVEL = 2;

    private final String literal;
    private final String node;
    private final int permissionLevel;

    Subcommand(String literal, String node, int permissionLevel) {
        this.literal = literal;
        this.node = node;
        this.permissionLevel = permissionLevel;
    }

    /** The subcommand literal as typed after {@code /mg} (e.g. {@code "status"}). */
    public String literal() {
        return literal;
    }

    /**
     * The grantable permission node this subcommand checks ({@code minegit.use} for read/setup/commit
     * /rescan, {@code minegit.admin} for the world-mutating {@code checkout}). A permission backend
     * (LuckPerms etc.) grants this; absent a grant, {@link #permissionLevel()} is the op fallback.
     */
    public String node() {
        return node;
    }

    /** The vanilla op level required when no permission backend grants {@link #node()} (always op, 2). */
    public int permissionLevel() {
        return permissionLevel;
    }
```

> Note: enum constants may forward-reference the `static final int OP_PERMISSION_LEVEL` because it is a compile-time constant. Leave the rest of the file (`literals()`, `byLiteral(...)`) unchanged.

- [ ] **Step 4: Update the class javadoc**

Replace the class javadoc paragraph at the top of `Subcommand.java` (the one describing "level 0 ... available to any player") with:

```java
/**
 * The {@code /minegit} subcommand registry (Spec D §4, issue #60; permission parity 2026-06-07): the
 * loader-agnostic catalogue of subcommand literals, the grantable permission {@link #node()} each
 * requires, and the vanilla op level each falls back to. Driving Brigadier registration
 * ({@link MineGitCommands}), tab-completion and the gating seam off one ordered enum keeps them in
 * lock-step and makes the gating decision unit-testable without a live server.
 *
 * <p>Locked-by-default: every subcommand requires op <em>or</em> a granted node. {@code minegit.use}
 * covers {@code init}, {@code status}, {@code commit}, {@code log}, {@code diff} and {@code rescan};
 * the world-mutating {@code checkout} (issue #63) requires {@code minegit.admin}. Both fall back to
 * vanilla op (level 2). Mirrors the plugin's node model exactly.
 */
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :mod:common:test --tests 'net.rainbowcreation.vocanicz.minegit.mod.command.SubcommandTest'`
Expected: PASS (all SubcommandTest cases).

> This step will not fully pass until Task 3 compiles `MineGitCommands` against the new enum (the module must compile to run the test). If `:mod:common:test` fails to compile because `MineGitCommands.permissionCheck` still reads `permissionLevel()` as a 0/2 switch, that is expected — proceed to Task 3 and re-run. To keep tasks independently committable, do Task 1 → Task 2 → Task 3 then run the suite once at Task 3 Step 5.

- [ ] **Step 6: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/Subcommand.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/SubcommandTest.java
git commit -m "feat(mod): Subcommand carries a grantable permission node + op fallback"
git push
```

---

## Task 2: MineGitPermissions seam (common)

**Files:**
- Create: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/MineGitPermissions.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/MineGitPermissionsTest.java`

- [ ] **Step 1: Write the failing test**

```java
package net.rainbowcreation.vocanicz.minegit.mod.command.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The loader-agnostic permission seam: {@code require} delegates to the installed checker. */
class MineGitPermissionsTest {

    @AfterEach
    void reset() {
        MineGitPermissions.resetChecker();
    }

    @Test
    void requireDelegatesNodeAndLevelToTheInstalledCheckerAndReturnsItsVerdict() {
        List<String> seenNodes = new ArrayList<String>();
        List<Integer> seenLevels = new ArrayList<Integer>();
        MineGitPermissions.setChecker((source, node, level) -> {
            seenNodes.add(node);
            seenLevels.add(level);
            return "minegit.use".equals(node);
        });

        // A null source is fine: this fake checker never dereferences it.
        assertTrue(MineGitPermissions.require("minegit.use", 2).test((CommandSourceStack) null));
        assertFalse(MineGitPermissions.require("minegit.admin", 2).test((CommandSourceStack) null));

        assertEquals(java.util.Arrays.asList("minegit.use", "minegit.admin"), seenNodes);
        assertEquals(java.util.Arrays.asList(2, 2), seenLevels);
    }

    @Test
    void resetRestoresADefaultCheckerThatIsNeverNull() {
        MineGitPermissions.setChecker((source, node, level) -> true);
        MineGitPermissions.resetChecker();
        // The default checker must be installed (non-null predicate) so registration never NPEs.
        org.junit.jupiter.api.Assertions.assertNotNull(MineGitPermissions.require("minegit.use", 2));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :mod:common:test --tests 'net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissionsTest'`
Expected: FAIL — `MineGitPermissions` does not exist (compile error).

- [ ] **Step 3: Create the seam**

```java
package net.rainbowcreation.vocanicz.minegit.mod.command.permission;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.PermissionCheck;

/**
 * The loader-agnostic command-authorization seam (permission parity, 2026-06-07). A subcommand's
 * Brigadier {@code .requires(...)} gate is built from a grantable permission node plus a vanilla op
 * fallback level; the actual decision is delegated to an installed {@link Checker} so the gating shape
 * stays unit-testable in {@code mod:common} without a live server — mirroring the settable-handler
 * pattern of {@link net.rainbowcreation.vocanicz.minegit.mod.net.DiffControlChannel}.
 *
 * <p>Until a loader installs its node-aware checker at init, the default is pure vanilla op-level:
 * granted iff the source holds the fallback op level. Each loader replaces it — Fabric via
 * fabric-permissions-api, NeoForge via the built-in {@code PermissionAPI} — so a non-op granted
 * {@code minegit.use}/{@code minegit.admin} by a permissions backend can run the matching commands.
 */
public final class MineGitPermissions {

    /** Decides whether {@code source} may run a command gated on {@code node} (op fallback {@code level}). */
    @FunctionalInterface
    public interface Checker {
        /** True iff a backend grants {@code node} to {@code source}, OR {@code source} holds op {@code level}. */
        boolean allowed(CommandSourceStack source, String node, int level);
    }

    /**
     * The default checker: no permission backend, so authorization is pure vanilla op-level. Reuses the
     * 1.21.11 {@link PermissionCheck} machinery the command tree already gates on.
     */
    private static final Checker OP_LEVEL_ONLY =
            (source, node, level) -> Commands.hasPermission(opLevelCheck(level)).test(source);

    /** The current checker. Replace-on-install from each loader's entrypoint; never null. */
    private static volatile Checker checker = OP_LEVEL_ONLY;

    private MineGitPermissions() {
    }

    /** Installs the loader's node-aware checker. Called once from each loader entrypoint at init. */
    public static void setChecker(Checker installed) {
        checker = Objects.requireNonNull(installed, "checker");
    }

    /** Restores the default op-level-only checker. Used by tests and on teardown. */
    public static void resetChecker() {
        checker = OP_LEVEL_ONLY;
    }

    /**
     * The Brigadier requirement for a subcommand gated on {@code node} with op fallback {@code level}.
     * Evaluated per source at parse/execute time, so a checker installed after registration still applies.
     */
    public static Predicate<CommandSourceStack> require(String node, int level) {
        return source -> checker.allowed(source, node, level);
    }

    /** Maps a MineGit op fallback level to the 1.21.11 {@link PermissionCheck}. Only op (2) is used. */
    private static PermissionCheck opLevelCheck(int level) {
        if (level == 2) {
            return Commands.LEVEL_GAMEMASTERS;
        }
        throw new IllegalArgumentException("unsupported MineGit op fallback level: " + level);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :mod:common:test --tests 'net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissionsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/MineGitPermissions.java \
        mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/MineGitPermissionsTest.java
git commit -m "feat(mod): MineGitPermissions seam — node + op-fallback Brigadier gate"
git push
```

---

## Task 3: Rewire MineGitCommands onto the node seam

**Files:**
- Modify: `mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/MineGitCommands.java`
- Test: `mod/common/src/test/java/net/rainbowcreation/vocanicz/minegit/mod/command/MineGitCommandsTest.java` (existing — must stay green)

- [ ] **Step 1: Swap the import**

In `MineGitCommands.java`, delete the import `import net.minecraft.server.permissions.PermissionCheck;` (line 17) and add:

```java
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;
```

- [ ] **Step 2: Replace each `.requires(...)` call**

There are four explicit subcommand builders plus the generic helper. In each, replace
`.requires(Commands.hasPermission(permissionCheck(Subcommand.X.permissionLevel())))`
with `.requires(MineGitPermissions.require(Subcommand.X.node(), Subcommand.X.permissionLevel()))`.

`checkoutSubcommand` (was line 187):
```java
                .requires(MineGitPermissions.require(
                        Subcommand.CHECKOUT.node(), Subcommand.CHECKOUT.permissionLevel()))
```

`diffSubcommand` (was line 203):
```java
                .requires(MineGitPermissions.require(
                        Subcommand.DIFF.node(), Subcommand.DIFF.permissionLevel()))
```

`commitSubcommand` (was line 231):
```java
                .requires(MineGitPermissions.require(
                        Subcommand.COMMIT.node(), Subcommand.COMMIT.permissionLevel()))
```

`initSubcommand` (was line 254):
```java
                .requires(MineGitPermissions.require(
                        Subcommand.INIT.node(), Subcommand.INIT.permissionLevel()))
```

generic `subcommand(...)` helper (was line 263) — serves STATUS, LOG, RESCAN:
```java
                .requires(MineGitPermissions.require(sub.node(), sub.permissionLevel()))
```

- [ ] **Step 3: Delete the dead mapping helper**

Delete the entire `permissionCheck(int level)` method and its javadoc (was lines 267–282). Nothing else references it.

- [ ] **Step 4: Run the full common suite to verify it passes**

Run: `./gradlew :mod:common:test`
Expected: PASS — including `MineGitCommandsTest.everySubcommandCarriesAPermissionRequirementSeam` (requirements are still non-null predicates), all `SubcommandTest` cases (Task 1), and `MineGitPermissionsTest` (Task 2). The whole `mod:common` module now compiles against the new enum.

- [ ] **Step 5: Commit**

```bash
git add mod/common/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/MineGitCommands.java
git commit -m "refactor(mod): gate /minegit on the MineGitPermissions node seam"
git push
```

---

## Task 4: Fabric — install a fabric-permissions-api checker

**Files:**
- Modify: `mod/fabric/build.gradle.kts`
- Create: `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/fabric/MineGitPermissionsImpl.java`
- Modify: `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/MineGitFabric.java`

- [ ] **Step 1: Add the dependency + repo**

In `mod/fabric/build.gradle.kts`, add to the `repositories { ... }` block (after the Architectury maven):
```kotlin
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid (fabric-permissions-api)" }
```

In the `dependencies { ... }` block, after the `modApi("dev.architectury:architectury-fabric:$architecturyVersion")` line, add (bundled jar-in-jar so it ships in the production jar):
```kotlin
    // Grantable permission nodes on Fabric (permission parity 2026-06-07). Mapping-agnostic — wraps
    // SharedSuggestionProvider — so it is tolerant across MC patch versions. Bundled (include) so the
    // production jar carries it; a perms backend (LuckPerms) supplies actual grants at runtime.
    // 0.4.2-patbox.1 is the release published on the nucleoid maven (verified: package
    // me.lucko.fabric.api.permissions.v0.Permissions, with check(CommandSourceStack, String, int)).
    modImplementation("me.lucko:fabric-permissions-api:0.4.2-patbox.1")
    include("me.lucko:fabric-permissions-api:0.4.2-patbox.1")
```

- [ ] **Step 2: Verify the dependency resolves**

Run: `./gradlew :mod:fabric:dependencies --configuration modImplementation`
Expected: `me.lucko:fabric-permissions-api:0.4.2-patbox.1` appears resolved (no "FAILED"). Resolution needs network (not `--offline`) the first time, to fetch from the nucleoid maven; it is cached afterward. The API surface used below — `Permissions.check(source, node, level)` returning boolean — was verified present in this artifact.

- [ ] **Step 3: Create the installer**

```java
package net.rainbowcreation.vocanicz.minegit.mod.command.permission.fabric;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;

/**
 * Fabric installer for the {@link MineGitPermissions} seam: routes each gate through
 * fabric-permissions-api, whose 3-arg {@code check} returns "granted node OR op level >= fallback".
 * A permissions backend (LuckPerms) supplies grants; with none installed it degrades to op-level,
 * matching the seam's default. Called once from {@code MineGitFabric#onInitialize}.
 */
public final class MineGitPermissionsImpl {

    private MineGitPermissionsImpl() {
    }

    public static void install() {
        MineGitPermissions.setChecker(
                (source, node, level) -> Permissions.check(source, node, level));
    }
}
```

- [ ] **Step 4: Call the installer from the Fabric entrypoint**

Open `mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/MineGitFabric.java`. In `onInitialize()`, add the install call BEFORE `MineGitMod.init()` (so the checker is in place before any command registers), and the import:

```java
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.fabric.MineGitPermissionsImpl;
```
```java
        MineGitPermissionsImpl.install();
```

- [ ] **Step 5: Compile to verify the API binds**

Run: `./gradlew :mod:fabric:compileJava`
Expected: BUILD SUCCESSFUL (pre-existing `EnvType.CLIENT` cosmetic warnings only). If `Permissions.check(...)` does not resolve, confirm the package is `me.lucko.fabric.api.permissions.v0.Permissions` for the resolved version and adjust the import.

- [ ] **Step 6: Run the Fabric GameTests (authorization still passes as op)**

Run: `./gradlew :mod:fabric:runGametest`
Expected: BUILD SUCCESSFUL — every `@GameTest` still runs (the GameTest server source is op, so all commands authorize).

- [ ] **Step 7: Commit**

```bash
git add mod/fabric/build.gradle.kts \
        mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/command/permission/fabric/MineGitPermissionsImpl.java \
        mod/fabric/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/MineGitFabric.java
git commit -m "feat(mod/fabric): install fabric-permissions-api checker for /minegit gates"
git push
```

---

## Task 5: NeoForge — register nodes + install a PermissionAPI checker

**Files:**
- Create: `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/neoforge/MineGitNeoForgePermissions.java`
- Modify: `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/neoforge/MineGitNeoForge.java`

- [ ] **Step 1: Create the node registry + installer**

NeoForge's `PermissionAPI` is built in (no new dependency). Register one BOOL node per MineGit node; its default resolver returns the op-level fallback when a backend hasn't set it. LuckPerms-NeoForge exposes a node `ResourceLocation("minegit","use")` as the permission string `minegit.use`, matching the plugin and Fabric.

```java
package net.rainbowcreation.vocanicz.minegit.mod.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.rainbowcreation.vocanicz.minegit.mod.MineGitInfo;
import net.rainbowcreation.vocanicz.minegit.mod.command.permission.MineGitPermissions;

/**
 * NeoForge installer for the {@link MineGitPermissions} seam (permission parity 2026-06-07). Declares
 * a BOOL {@link PermissionNode} for {@code minegit.use} and {@code minegit.admin}; each node's default
 * resolver returns the vanilla op fallback (op level 2) when no backend has set it. A permissions
 * backend (LuckPerms) overrides per player. The checker routes player sources through
 * {@code PermissionAPI}; console / RCON / command blocks fall back to op-level via the source.
 */
public final class MineGitNeoForgePermissions {

    private static final PermissionNode<Boolean> USE = boolNode("use");
    private static final PermissionNode<Boolean> ADMIN = boolNode("admin");

    private MineGitNeoForgePermissions() {
    }

    private static PermissionNode<Boolean> boolNode(String path) {
        return new PermissionNode<>(
                ResourceLocation.fromNamespaceAndPath(MineGitInfo.MOD_ID, path),
                PermissionTypes.BOOLEAN,
                // Default when unset by a backend: op level 2 (the seam's locked-by-default fallback).
                (player, playerUUID, context) -> player != null && player.hasPermissions(2));
    }

    /** Adds the two nodes to the gather event. Wired to {@code PermissionGatherEvent.Nodes}. */
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(USE, ADMIN);
    }

    /** Installs the PermissionAPI-backed checker over the common seam. Called from the mod constructor. */
    public static void install() {
        MineGitPermissions.setChecker(MineGitNeoForgePermissions::allowed);
    }

    private static boolean allowed(CommandSourceStack source, String node, int level) {
        PermissionNode<Boolean> permNode = "minegit.admin".equals(node) ? ADMIN : USE;
        if (source.getEntity() instanceof ServerPlayer player) {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, permNode));
        }
        // Console / RCON / command block: no player to resolve a node for — fall back to op level.
        return source.hasPermission(level);
    }
}
```

- [ ] **Step 2: Wire it into the NeoForge entrypoint**

In `mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/neoforge/MineGitNeoForge.java`, register the gather listener on the mod event bus and install the checker. The `PermissionGatherEvent` fires on the NeoForge game event bus (`NeoForge.EVENT_BUS`), not the mod bus, so add a listener there. Update the constructor:

```java
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
```
```java
    public MineGitNeoForge(IEventBus modEventBus, Dist dist) {
        // Install the permission checker + register the node-gather listener BEFORE shared init, so the
        // /minegit gates resolve through PermissionAPI from the first command registration (parity 2026-06-07).
        MineGitNeoForgePermissions.install();
        NeoForge.EVENT_BUS.addListener(
                PermissionGatherEvent.Nodes.class, MineGitNeoForgePermissions::onGatherNodes);
        MineGitMod.init();
        // Register the minegit:diff opaque-byte payload (type + S2C handler) on the mod bus (issue #77).
        MineGitNeoForgeNetworking.register(modEventBus);
        // Client-distribution init seam: only on the physical client, so the dedicated server never
        // classloads client-only overlay types (issue #77).
        if (dist.isClient()) {
            MineGitNeoForgeClient.init(modEventBus);
        }
        // GameTest registration (issue #64); gated to the gametest run.
        MineGitNeoForgeGameTest.register(modEventBus);
    }
```

- [ ] **Step 3: Compile to verify the PermissionAPI signatures bind**

Run: `./gradlew :mod:neoforge:compileJava`
Expected: BUILD SUCCESSFUL (pre-existing `EnvType.CLIENT` cosmetic warnings only). If a signature differs on NeoForge 21.11.42 — e.g. `PermissionNode` constructor arity, `PermissionTypes.BOOLEAN` name, `event.addNodes` vs `event.addNode`, or `PermissionAPI.getPermission` varargs context — adjust to the 21.11.x API; the structure (declare BOOL nodes, add on gather, resolve per player, op fallback for non-players) is unchanged.

- [ ] **Step 4: Run the NeoForge GameTests (authorization still passes as op)**

Run: `./gradlew :mod:neoforge:runGameTestServer`
Expected: BUILD SUCCESSFUL — "All 9 required tests passed" (the GameTest server source is op, so all commands authorize; nodes resolve to the op-level default).

- [ ] **Step 5: Commit**

```bash
git add mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/neoforge/MineGitNeoForgePermissions.java \
        mod/neoforge/src/main/java/net/rainbowcreation/vocanicz/minegit/mod/neoforge/MineGitNeoForge.java
git commit -m "feat(mod/neoforge): register minegit permission nodes + PermissionAPI checker"
git push
```

---

## Task 6: Plugin — lock the base node by default

**Files:**
- Modify: `plugin/src/main/resources/plugin.yml`
- Modify: `plugin/src/main/java/net/rainbowcreation/vocanicz/minegit/plugin/command/MineGitCommand.java`

> The plugin already checks `hasPermission(node)` per subcommand and its tests mock the nodes directly, so no Java logic or test changes are needed — only the default and a stale comment.

- [ ] **Step 1: Flip the default in plugin.yml**

In `plugin/src/main/resources/plugin.yml`, change the `minegit.use` permission default from `true` to `op`:

```yaml
permissions:
  minegit.use:
    description: Use read/commit MineGit commands.
    default: op
  minegit.admin:
    description: Use destructive MineGit commands (e.g. checkout).
    default: op
```

- [ ] **Step 2: Update the stale comment**

In `MineGitCommand.java` (line 49), replace the `PERM_USE` javadoc:

```java
    /** Permission for read/setup/commit commands; op-or-granted (locked by default, parity 2026-06-07). */
    public static final String PERM_USE = "minegit.use";
```

- [ ] **Step 3: Verify the plugin still builds and tests pass**

Run: `./gradlew :plugin:build`
Expected: BUILD SUCCESSFUL — `MineGitCommandTest` / `MineGitFormatTest` pass unchanged (they mock `hasPermission(PERM_USE)` directly, independent of the `plugin.yml` default).

- [ ] **Step 4: Commit**

```bash
git add plugin/src/main/resources/plugin.yml \
        plugin/src/main/java/net/rainbowcreation/vocanicz/minegit/plugin/command/MineGitCommand.java
git commit -m "feat(plugin): lock minegit.use by default — op-or-granted parity"
git push
```

---

## Task 7: Full multi-module verification

**Files:** none (verification only)

- [ ] **Step 1: Build every module**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL across `core`, `protocol`, `plugin`, `mod:common`, `mod:fabric`, `mod:neoforge` (cosmetic `EnvType.CLIENT` warnings only).

- [ ] **Step 2: Run both loaders' GameTests once more end-to-end**

Run: `./gradlew :mod:fabric:runGametest :mod:neoforge:runGameTestServer`
Expected: both BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke checklist (record results in the PR/commit body)**

Document for the human tester (cannot be automated — needs a live server + a perms backend):
- Fresh server, **non-op** player: every `/mg` subcommand → "no permission". (Was: `status`/`log`/`diff` allowed — intended change.)
- **op** player: every subcommand allowed.
- LuckPerms grant `minegit.use` to a non-op: `init/status/commit/log/diff/rescan` allowed; `checkout` denied.
- LuckPerms grant `minegit.admin`: `checkout` allowed.
- Run the four cases on **Fabric**, **NeoForge**, and the **plugin** — behavior identical on all three.

- [ ] **Step 4: Final confirmation commit (if any smoke fixes were needed)**

Only if Step 3 surfaced a fix. Otherwise the work is already committed + pushed task-by-task.

---

## Self-Review Notes

- **Spec coverage:** two-node model (Task 1) ✓; locked-by-default (Task 1 levels + Task 6 plugin.yml) ✓; grantable nodes on mod via Permission API + op fallback (Tasks 2/4/5) ✓; rescan=use tier fix (Task 1) ✓; plugin minimal change (Task 6) ✓; common unit test + gametests + manual smoke (Tasks 2/3/4/5/7) ✓.
- **Known verify-at-build items (not placeholders — concrete first-try + adjustment instruction):** fabric-permissions-api version/package (Task 4 Steps 2/5); NeoForge `PermissionAPI`/`PermissionNode`/`PermissionGatherEvent.Nodes` signatures on 21.11.42 (Task 5 Step 3). Both are pinned to a concrete candidate with an explicit fallback if the exact symbol differs in the resolved version.
- **Type consistency:** `Subcommand.node()` / `Subcommand.permissionLevel()` used identically in Tasks 1, 3, 5; `MineGitPermissions.require(node, level)` / `setChecker` / `resetChecker` / `Checker.allowed(source, node, level)` consistent across Tasks 2, 3, 4, 5 and the test.
