# SP1 Transport Parity — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A modded client (Fabric/NeoForge) pressing J on a Paper-plugin server subscribes to and renders the diff overlay, with `minegit.use` permission enforced on both server types.

**Architecture:** Plugin speaks the existing `minegit:diff`/`minegit:diffsub` wire bytes over Bukkit plugin-messaging (vanilla custom payloads), reusing `:protocol`/`:core` codec unchanged. Snapshot push model: push working-vs-HEAD on SUBSCRIBE and on commit/checkout completion (HEAD-move); no per-tick loop. Mod server gains a permission gate on SUBSCRIBE for parity.

**Tech Stack:** Java 8 plugin (Paper/Bukkit), Java mod/common (1.21.11, Architectury), `:protocol` (Java-8, zero MC imports), `:core` (`WorldDiff`). JUnit 5 headless tests.

**Spec:** `docs/superpowers/specs/2026-06-07-transport-parity-design.md` — every task below cites the spec section it implements. The spec contains verified file:line for each seam; **line numbers drift — confirm the seam by symbol before editing.**

**AFK scope note:** Task 1 (live transport spike) and the §3 manual integration matrix require a live Paper server + both loaders and are **USER-SIDE — not implemented by this plan.** They are listed as gates. Everything else is headless-codeable and is implemented here. Do not begin SP2 until the user has run the spike.

---

## Files

- **Verify only:** `mod/neoforge/.../neoforge/MineGitNeoForgeNetworking.java` (`.optional()` already present), `mod/fabric/.../MineGitFabric.java` (global registration present).
- **Modify (mod):** `mod/common/.../command/ServerCommandRuntime.java` (`onControl` permission gate + a test seam to inject the live registry).
- **Create (mod test):** extend `mod/common/.../command/permission/` test area or a new `ServerCommandRuntimeOnControlTest`.
- **Create (plugin):** `plugin/.../net/DiffSubscriptions.java` (registry), `plugin/.../net/DiffSubDecision.java` (pure decode+permission decision), `plugin/.../net/DiffPush.java` (encode→frame helper), `plugin/.../listener/DiffSubListener.java` (incoming control), `plugin/.../listener/DiffSubQuitListener.java` (or fold quit into an existing listener).
- **Modify (plugin):** `plugin/.../MineGitPlugin.java` (`onEnable` wiring), `plugin/.../command/MineGitCommand.java` (commit/checkout completion re-push hooks).
- **Create (plugin test):** `plugin/src/test/.../DiffSubscriptionsTest.java`, `DiffSubDecisionTest.java`, `DiffPushRoundTripTest.java`.

---

## Task 0: Verify mod transport (no edit)

**Files:** Read `mod/neoforge/.../neoforge/MineGitNeoForgeNetworking.java`, `mod/fabric/.../MineGitFabric.java`.

- [ ] **Step 1: Confirm NeoForge `.optional()`**

Read `MineGitNeoForgeNetworking.onRegister`. Confirm it calls `event.registrar("1").optional()` (or equivalent) before `playToClient(DiffRawPayload…)` and `playToServer(DiffControlPayload…)`. Expected: present (commit `4c23c64`). If present, **no edit** — record "verified" and move on. If somehow absent, add `.optional()` and note the deviation.

- [ ] **Step 2: Confirm Fabric global registration**

Read `MineGitFabric.onInitialize`. Confirm `PayloadTypeRegistry.playS2C().register(DiffRawPayload…)`, `…playC2S().register(DiffControlPayload…)`, and the `ServerPlayNetworking.registerGlobalReceiver(DiffControlPayload.TYPE, …)` are present. Expected: present, no edit.

- [ ] **Step 3: Record**

No commit (read-only task). Note in the execution log: "Task 0 — mod transport verified, zero delta; live spike (Task 1 in spec) deferred to user."

---

## Task 1: Mod-server permission gate on SUBSCRIBE

Spec §2(c), §3 "Mod-common unit test". Closes the keybind permission bypass on the mod server.

**Files:**
- Modify: `mod/common/.../command/ServerCommandRuntime.java` (`onControl`; add a constructor/seam to inject the `LiveSubscriptionLoop`)
- Test: `mod/common/src/test/.../command/ServerCommandRuntimeOnControlTest.java` (new)

- [ ] **Step 1: Read the seam**

Read `ServerCommandRuntime.onControl(...)` and how it obtains the `LiveSubscriptionLoop` (`live`). Confirm: the `SUBSCRIBE` branch currently calls `live.subscribe(player, player.getUUID(), currentDiffFor(player))` with no auth check; `MineGitPermissions.require(String node, int level)` returns `Predicate<CommandSourceStack>` (`mod/common/.../command/permission/MineGitPermissions.java`); `Subcommand.DIFF.node()` → `"minegit.use"` and `Subcommand.DIFF.permissionLevel()` → `2`; `ServerPlayer.createCommandSourceStack()` exists. Adjust the snippets below to the real signatures.

- [ ] **Step 2: Add an injection seam for the live registry (if absent)**

If `ServerCommandRuntime` constructs its `LiveSubscriptionLoop` internally with no override, add a package-private constructor (or package-private setter `setLiveLoopForTest(LiveSubscriptionLoop)`) so a test can supply a fake that records `subscribe`/`unsubscribe` calls. Keep the production path unchanged. Minimal — do not refactor unrelated code.

- [ ] **Step 3: Write the failing test**

```java
// ServerCommandRuntimeOnControlTest.java (mod/common test source set)
// Fake loop records calls; fake/installed MineGitPermissions checker toggles allow/deny.
@Test
void deniedSubscribe_doesNotSubscribe() {
    RecordingLoop loop = new RecordingLoop();
    ServerCommandRuntime rt = newRuntimeWith(loop);
    installPermissionChecker(/* allow = */ false);
    rt.onControl(fakePlayer(), DiffControl.SUBSCRIBE);
    assertTrue(loop.subscribeCalls.isEmpty());
}

@Test
void allowedSubscribe_subscribes() {
    RecordingLoop loop = new RecordingLoop();
    ServerCommandRuntime rt = newRuntimeWith(loop);
    installPermissionChecker(/* allow = */ true);
    rt.onControl(fakePlayer(), DiffControl.SUBSCRIBE);
    assertEquals(1, loop.subscribeCalls.size());
}

@Test
void unsubscribe_alwaysUnsubscribes_evenWhenDenied() {
    RecordingLoop loop = new RecordingLoop();
    ServerCommandRuntime rt = newRuntimeWith(loop);
    installPermissionChecker(/* allow = */ false);
    rt.onControl(fakePlayer(), DiffControl.UNSUBSCRIBE);
    assertEquals(1, loop.unsubscribeCalls.size());
}
```

Reuse the existing `MineGitPermissions` test installation pattern (see `MineGitPermissionsTest`). If constructing a real `ServerPlayer`/`CommandSourceStack` headlessly is impractical, factor the gate into a pure helper `boolean maySubscribe(CommandSourceStack)` (or accept a `Predicate<ServerPlayer>` seam) so the decision is testable without a live player — mirror how the spec frames the decision as pure. Pick the approach that keeps the test headless; document the choice in the commit.

- [ ] **Step 4: Run test — verify it FAILS**

Run: `./gradlew :mod:common:test --tests '*ServerCommandRuntimeOnControlTest*'`
Expected: FAIL (gate not implemented / seam missing).

- [ ] **Step 5: Implement the gate**

In `onControl`, before `live.subscribe(...)`:

```java
if (control == DiffControl.SUBSCRIBE) {
    if (!MineGitPermissions
            .require(Subcommand.DIFF.node(), Subcommand.DIFF.permissionLevel())
            .test(player.createCommandSourceStack())) {
        return; // unpermitted SUBSCRIBE silently ignored
    }
    live.subscribe(player, player.getUUID(), currentDiffFor(player));
} else { // UNSUBSCRIBE — never gated
    live.unsubscribe(player.getUUID());
}
```

Match the existing control-dispatch shape exactly (the real code may already branch on `control`).

- [ ] **Step 6: Run test — verify it PASSES**

Run: `./gradlew :mod:common:test --tests '*ServerCommandRuntimeOnControlTest*'`
Expected: PASS (all three).

- [ ] **Step 7: Commit + push**

```bash
git add mod/common
git commit -m "feat(mod): gate diffsub SUBSCRIBE on minegit.use — close keybind permission bypass

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push
```

---

## Task 2: Plugin subscription registry

Spec §2(e) state, §3 "Subscription registry". Pure `Set<UUID>` holder, no scheduler.

**Files:**
- Create: `plugin/.../net/DiffSubscriptions.java`
- Test: `plugin/src/test/.../net/DiffSubscriptionsTest.java`

- [ ] **Step 1: Write the failing test**

```java
class DiffSubscriptionsTest {
    @Test void addAndContains() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        assertTrue(s.subscribe(u));        // true = newly added
        assertTrue(s.isSubscribed(u));
    }
    @Test void doubleSubscribeIdempotent() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        s.subscribe(u);
        assertFalse(s.subscribe(u));       // already present
        assertEquals(1, s.snapshot().size());
    }
    @Test void unsubscribeRemoves() {
        DiffSubscriptions s = new DiffSubscriptions();
        UUID u = UUID.randomUUID();
        s.subscribe(u);
        assertTrue(s.unsubscribe(u));
        assertFalse(s.isSubscribed(u));
        assertFalse(s.unsubscribe(u));     // idempotent remove
    }
    @Test void snapshotIsCopy() {
        DiffSubscriptions s = new DiffSubscriptions();
        s.subscribe(UUID.randomUUID());
        Set<UUID> snap = s.snapshot();
        snap.clear();                      // must not affect internal state
        assertEquals(1, s.snapshot().size());
    }
}
```

- [ ] **Step 2: Run — verify FAIL**

Run: `./gradlew :plugin:test --tests '*DiffSubscriptionsTest*'`
Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

```java
public final class DiffSubscriptions {
    private final Set<UUID> subs = ConcurrentHashMap.newKeySet();
    public boolean subscribe(UUID id)   { return subs.add(id); }
    public boolean unsubscribe(UUID id) { return subs.remove(id); }
    public boolean isSubscribed(UUID id){ return subs.contains(id); }
    public Set<UUID> snapshot()         { return new HashSet<>(subs); }
}
```

- [ ] **Step 4: Run — verify PASS**

Run: `./gradlew :plugin:test --tests '*DiffSubscriptionsTest*'` → PASS.

- [ ] **Step 5: Commit + push**

```bash
git add plugin
git commit -m "feat(plugin): diff-overlay subscription registry (no scheduler)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push
```

---

## Task 3: Plugin decode + permission decision (pure)

Spec §2(d), §3 "Decode + permission decision as a pure function". Mirrors the mod's `deliverToServer` malformed→drop contract.

**Files:**
- Create: `plugin/.../net/DiffSubDecision.java`
- Test: `plugin/src/test/.../net/DiffSubDecisionTest.java`

- [ ] **Step 1: Confirm `DiffControl` API**

Read `protocol/.../DiffControl.java`. Confirm `decode(byte[])` return type and the `SUBSCRIBE`/`UNSUBSCRIBE` constants, and exactly which exception malformed bytes throw (the mod's `deliverToServer` catches it — match that catch). Adjust the code below to match.

- [ ] **Step 2: Write the failing test**

```java
class DiffSubDecisionTest {
    private static byte[] sub()   { return DiffControl.SUBSCRIBE.encode(); }
    private static byte[] unsub() { return DiffControl.UNSUBSCRIBE.encode(); }

    @Test void permittedSubscribe_pushes() {
        assertEquals(Decision.SUBSCRIBE_PUSH, DiffSubDecision.decide(sub(), () -> true));
    }
    @Test void unpermittedSubscribe_ignored() {
        assertEquals(Decision.IGNORE, DiffSubDecision.decide(sub(), () -> false));
    }
    @Test void unsubscribe_ungated() {
        assertEquals(Decision.UNSUBSCRIBE, DiffSubDecision.decide(unsub(), () -> false));
    }
    @Test void malformed_dropsNoThrow() {
        assertEquals(Decision.DROP, DiffSubDecision.decide(new byte[]{ (byte)0xFF, 0x01 }, () -> true));
    }
}
```

(If `DiffControl` constants are not directly `.encode()`-able, build the bytes the same way the mod test does — confirm in step 1.)

- [ ] **Step 3: Run — verify FAIL**

Run: `./gradlew :plugin:test --tests '*DiffSubDecisionTest*'` → FAIL.

- [ ] **Step 4: Implement**

```java
public final class DiffSubDecision {
    public enum Decision { SUBSCRIBE_PUSH, IGNORE, UNSUBSCRIBE, DROP }
    private DiffSubDecision() {}

    public static Decision decide(byte[] bytes, BooleanSupplier permitted) {
        final DiffControl ctl;
        try {
            ctl = DiffControl.decode(bytes);
        } catch (RuntimeException malformed) {   // match deliverToServer's catch
            return Decision.DROP;
        }
        if (ctl == DiffControl.UNSUBSCRIBE) return Decision.UNSUBSCRIBE;
        if (ctl == DiffControl.SUBSCRIBE)   return permitted.getAsBoolean() ? Decision.SUBSCRIBE_PUSH : Decision.IGNORE;
        return Decision.DROP;
    }
}
```

- [ ] **Step 5: Run — verify PASS** → `./gradlew :plugin:test --tests '*DiffSubDecisionTest*'`.

- [ ] **Step 6: Commit + push**

```bash
git add plugin
git commit -m "feat(plugin): pure diffsub decode+permission decision (malformed->drop)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push
```

---

## Task 4: Round-trip equality (test-only) + push frame builder

Spec §2(f), §3 "Round-trip equality". Asserts the plugin's *use* of the codec round-trips, and gives the push path a headless-testable seam.

**Files:**
- Create: `plugin/.../net/DiffPush.java` (frame-builder method is pure; the Bukkit send is a thin wrapper)
- Test: `plugin/src/test/.../net/DiffPushRoundTripTest.java`

- [ ] **Step 1: Confirm codec APIs**

Read `protocol/.../DiffPayload.java`, `protocol/.../Framing.java`, `protocol/.../Reassembler.java`, `core` `WorldDiff`. Confirm: `DiffPayload.encode(WorldDiff, String fromRef, String toRef)` → `byte[]`; `DiffPayload.decode(byte[])` → `WorldDiff`; `Framing.frame(byte[], int)` → `List<Frame>`; `Frame.toBytes()` → `byte[]`; `Reassembler` API to feed frame bytes and detect completion; `Framing.DEFAULT_MAX_FRAME_BYTES == 30000`; `WorldDiff.equals` is value-based. Adjust below to match. Find how existing tests build a `WorldDiff` fixture (small + a >30 KB one) and reuse that builder.

- [ ] **Step 2: Write the failing test**

```java
class DiffPushRoundTripTest {
    @Test void smallDiffSingleFrameRoundTrips() { assertRoundTrip(smallWorldDiff()); }
    @Test void largeDiffMultiFrameRoundTrips()  { assertRoundTrip(largeWorldDiff()); } // payload > 30 KB
    @Test void emptyDiffRoundTrips()            { assertRoundTrip(emptyWorldDiff()); }

    private static void assertRoundTrip(WorldDiff diff) {
        List<byte[]> frames = DiffPush.frames(diff);     // encode("HEAD","WORKING") -> frame -> toBytes
        Reassembler r = new Reassembler();
        WorldDiff out = null;
        for (byte[] fb : frames) {
            // feed fb; when reassembly completes, decode (adjust to real Reassembler API)
            byte[] payload = r.accept(fb);               // returns full payload when complete, else null
            if (payload != null) out = DiffPayload.decode(payload);
        }
        assertEquals(diff, out);
    }
}
```

- [ ] **Step 3: Run — verify FAIL** → `./gradlew :plugin:test --tests '*DiffPushRoundTripTest*'`.

- [ ] **Step 4: Implement `DiffPush.frames` (pure) + the send wrapper**

```java
public final class DiffPush {
    public static final String FROM_REF = "HEAD";
    public static final String TO_REF   = "WORKING";

    /** Pure: encode the diff and split into wire frames. Headless-testable. */
    public static List<byte[]> frames(WorldDiff diff) {
        byte[] payload = DiffPayload.encode(diff, FROM_REF, TO_REF);
        List<byte[]> out = new ArrayList<>();
        for (Frame f : Framing.frame(payload, Framing.DEFAULT_MAX_FRAME_BYTES)) {
            out.add(f.toBytes());
        }
        return out;
    }

    /** Thin Bukkit send — covered by the manual matrix, not unit tests. */
    public static void push(Plugin plugin, Player player, WorldDiff diff) {
        for (byte[] frame : frames(diff)) {
            player.sendPluginMessage(plugin, Protocol.DIFF_CHANNEL, frame);
        }
    }
}
```

- [ ] **Step 5: Run — verify PASS** (round-trip tests green).

- [ ] **Step 6: Commit + push**

```bash
git add plugin
git commit -m "feat(plugin): diff push frame-builder + codec round-trip test

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push
```

---

## Task 5: Wire transport + listeners + HEAD-move re-push

Spec §2(d)(e), end-to-end sequence. Bukkit integration glue — the send/receive edges are covered by the manual matrix; add a MockBukkit listener test **only if the plugin test suite already uses MockBukkit** (check `plugin/build.gradle.kts` deps and existing tests first).

**Files:**
- Create: `plugin/.../listener/DiffSubListener.java` (implements `PluginMessageListener`), quit handling (a `Listener` with `@EventHandler PlayerQuitEvent`, or fold into an existing listener).
- Modify: `plugin/.../MineGitPlugin.java` (`onEnable`), `plugin/.../command/MineGitCommand.java` (commit/checkout completion callbacks).

- [ ] **Step 1: Read the seams**

Confirm in `MineGitCommand`: `workingTreeDiff(repo, adapter, worldName)` → `WorldDiff` (used by `/mg diff`); `PERM_USE == "minegit.use"`; the commit completion callback (`commitService.commit(…, Consumer<CommitService.Result> onComplete)`) and checkout completion (`checkoutService.checkout(…, Consumer<Result>)`), both delivered on the main thread, and that the acting player's `World` is in scope in each handler. Confirm `MineGitPlugin.adapterFor(World)` and `getServer().getMessenger()`. Adjust below.

- [ ] **Step 2: Implement `DiffSubListener`**

```java
public final class DiffSubListener implements PluginMessageListener {
    private final MineGitPlugin plugin;
    private final DiffSubscriptions subs;
    public DiffSubListener(MineGitPlugin plugin, DiffSubscriptions subs) { this.plugin = plugin; this.subs = subs; }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!Protocol.DIFF_CONTROL_CHANNEL.equals(channel)) return;
        switch (DiffSubDecision.decide(message, () -> player.hasPermission(MineGitCommand.PERM_USE))) {
            case SUBSCRIBE_PUSH:
                subs.subscribe(player.getUniqueId());
                plugin.pushCurrentDiff(player);   // computes workingTreeDiff for player's world, then DiffPush.push
                break;
            case UNSUBSCRIBE:
                subs.unsubscribe(player.getUniqueId());
                break;
            case IGNORE:
            case DROP:
                break; // silently ignored / logged
        }
    }
}
```

Add `MineGitPlugin.pushCurrentDiff(Player)`: resolve the player's `World` → `adapterFor` → repo → `MineGitCommand.workingTreeDiff(repo, adapter, world.getName())` → `DiffPush.push(this, player, diff)`. Guard: world not a repo → no push. Log+drop a malformed decode is already handled by `DiffSubDecision`.

- [ ] **Step 3: Wire `onEnable`**

```java
this.subscriptions = new DiffSubscriptions();
Messenger m = getServer().getMessenger();
m.registerOutgoingPluginChannel(this, Protocol.DIFF_CHANNEL);
m.registerIncomingPluginChannel(this, Protocol.DIFF_CONTROL_CHANNEL, new DiffSubListener(this, subscriptions));
getServer().getPluginManager().registerEvents(new DiffSubQuitListener(subscriptions), this);
```

`DiffSubQuitListener`: `@EventHandler public void onQuit(PlayerQuitEvent e) { subs.unsubscribe(e.getPlayer().getUniqueId()); }`.

- [ ] **Step 4: HEAD-move re-push hooks**

In the commit completion callback (`MineGitCommand`, where `Consumer<CommitService.Result> onComplete` runs on the main thread), and the checkout completion callback, add on `!result.isError()`:

```java
for (UUID id : subscriptions.snapshot()) {
    Player sub = Bukkit.getPlayer(id);
    if (sub != null && sub.getWorld().getName().equals(world.getName())) {
        plugin.pushCurrentDiff(sub);
    }
}
```

`world` is the acting player's world already in scope in each handler (confirm the variable name). Push unconditionally on non-error completion (idempotent refresh — see spec §2(e) note). Ensure `subscriptions` is reachable from `MineGitCommand` (pass it in, or route via `plugin`).

- [ ] **Step 5: Optional MockBukkit listener test (only if MockBukkit already present)**

If `plugin/build.gradle.kts` already declares MockBukkit, add a test: mock server + player with `minegit.use`, fire `DiffSubListener.onPluginMessageReceived(DIFF_CONTROL_CHANNEL, player, SUBSCRIBE bytes)`, assert the player is in `subscriptions` and a `minegit:diff` plugin message was sent. Unpermitted player → not subscribed, no message. **If MockBukkit is not already a dependency, skip this step** — do not add new test infra in an AFK run; the manual matrix covers it.

- [ ] **Step 6: Build the plugin**

Run: `./gradlew :plugin:build`
Expected: compiles + existing + new headless tests pass.

- [ ] **Step 7: Commit + push**

```bash
git add plugin
git commit -m "feat(plugin): wire diffsub transport — listener, registry, HEAD-move re-push

Plugin now negotiates minegit:diff/diffsub over plugin-messaging, pushes
working-vs-HEAD on subscribe and on commit/checkout completion for the
subscriber's world, and drops subscriptions on UNSUBSCRIBE/quit. Mirrors
the mod server's snapshot semantics. Permission-gated on minegit.use.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
git push
```

---

## Task 6: Full build verification

- [ ] **Step 1: Full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL — both loaders compile, plugin compiles, all headless tests pass, gametests (if part of `build`) pass. Fix any compile/test breakage before proceeding; do not leave the tree red.

- [ ] **Step 2: Commit any fixups + push** (if needed).

- [ ] **Step 3: Stop — hand the live gates back to the user**

The following are **USER-SIDE** and cannot run AFK; report them as the remaining queue:
- **Transport spike** (spec §3 Task 1): live NeoForge↔Paper and Fabric↔Paper, both directions — confirms `.optional()` negotiates and the codec round-trips on the wire; decides if any `DiscardedPayload` fallback is needed (esp. Fabric `canSend` Open Question #1).
- **Manual integration matrix** (spec §3 table): permitted→boxes, unpermitted→nothing, commit/checkout→refresh, J-again→clear, disconnect→drop, mod-server regression.

Do **not** start SP2 until the spike passes (SP2 spec §5 gate).

---

## Self-review notes

- **Spec coverage:** (a)/(b) → Task 0; (c) → Task 1; (e) registry → Task 2; (d) decode → Task 3; (f) push + round-trip → Task 4; (d)/(e) wiring + HEAD-move → Task 5; build → Task 6. Spike + manual matrix explicitly deferred to user. All §2 components and headless §3 tests covered.
- **Type consistency:** `DiffSubscriptions` methods (`subscribe`/`unsubscribe`/`isSubscribed`/`snapshot`) used identically in Tasks 2 and 5; `DiffSubDecision.Decision` enum values used identically in Tasks 3 and 5; `DiffPush.frames`/`push` and `pushCurrentDiff` consistent across Tasks 4 and 5; `PERM_USE`, `workingTreeDiff`, `Protocol.DIFF_CHANNEL`/`DIFF_CONTROL_CHANNEL` referenced as in the spec.
- **Drift guard:** every task's Step 1 re-confirms the real signatures before editing, because the spec's line numbers will drift.
