# MineGit — Spec 2 / SP1: Diff-Overlay Transport Parity (design)

**Date:** 2026-06-07
**Status:** Design, pending review. SP1 of the two-part diff-visualization-parity split (SP2 = client-side live diff, authored separately).
**Umbrella:** `docs/superpowers/specs/2026-06-03-minegit-architecture-design.md` (§4.3, §7 flow ②, §9 client row)
**Builds on:**
- Diff-overlay frontend — `docs/superpowers/specs/2026-06-05-minegit-client-overlay-design.md` (`minegit:diff` S2C, `minegit:diffsub` C2S, `Frame`/`Framing`/`Reassembler`/`DiffPayload` codec).
- Command permission parity — `docs/superpowers/specs/2026-06-07-minegit-permission-parity-design.md` (the `minegit.use`/`minegit.admin` model and the `MineGitPermissions` checker seam this spec's gate reuses).
- Shipped crash-guard `132dd76` — the `@ExpectPlatform DiffControlChannel.canSendToServer()` capability gate on the keybind.

---

## 1. Approach & rationale

### The problem this closes

A **mod server** computes a working-vs-HEAD diff and pushes `minegit:diff` overlay frames to a
subscribed modded client, which renders translucent boxes. A **Paper plugin server** today sends
*nothing* on that channel: the protocol identifiers are shadowed in `:protocol`
(`Protocol.DIFF_CHANNEL = "minegit:diff"`, `Protocol.DIFF_CONTROL_CHANNEL = "minegit:diffsub"` —
`protocol/.../Protocol.java:13,21`) but no plugin code speaks them. A `minegit.use`-permitted player
pressing J on a plugin server therefore gets, at best, chat text — and before `132dd76`, a hard
client crash. The shipped crash-guard converted that into graceful degradation (an actionbar note,
no overlay); SP1 makes the overlay actually *work* on a plugin server.

### The empirical transport constraint (the crux)

The constraint that shapes the entire design was discovered from a live crash log on 2026-06-07
(recorded in `resume-active-thread.md`): **NeoForge's registered-payload networking refuses
unnegotiated payloads in both directions.** Client→server, `NetworkRegistry.checkPacket` throws
`UnsupportedOperationException` on a channel the server never negotiated; server→client, an
unnegotiated payload is dropped with `No registration for payload <id>; refusing to decode`. A Paper
server negotiates nothing through NeoForge's network registry, so a NeoForge client can neither send
`minegit:diffsub` to, nor decode `minegit:diff` from, a Paper server via the loader's *registered*
payload API — regardless of what the plugin does on its side.

The lever that makes a registered payload negotiate over the **vanilla** handshake is NeoForge's
`PayloadRegistrar.optional()`: `NetworkRegistry.getInitialListeningChannels(PacketFlow)` announces
optional channels over the vanilla `minecraft:register` handshake, and `ICommonPacketListener.hasChannel(Type)`
reads the *same* negotiated-channel map that `checkPacket` throws on — so `hasChannel == true ⟺
send won't throw` (this is exactly what `DiffControlChannelImpl.canSendToServer()` relies on, NeoForge
side, `mod/neoforge/.../net/neoforge/DiffControlChannelImpl.java`). Marking a payload `.optional()`
makes it survivable on a vanilla connection; it does **not** make a *plugin* speak it. The plugin's
half of the wire is Bukkit plugin-messaging on the same string channel ids.

### Approach A — optional-registered payloads + Bukkit plugin-messaging, reusing the existing codec

The plugin sends and receives the **identical wire bytes** the mod already produces, over Bukkit's
plugin-messaging transport (which is vanilla `minecraft:register`-negotiated custom payloads under the
hood), reusing `DiffPayload.encode` / `Framing.frame` / `Reassembler` / `DiffControl.decode`
unchanged. The mod client is unaware it is talking to a plugin rather than a mod server: the frames on
`minegit:diff` and the control bytes on `minegit:diffsub` are byte-for-byte what a mod server emits.
Why this shape:

- **Vanilla-compatible transport is forced**, not chosen — see the constraint above. Bukkit
  plugin-messaging is the plugin-side spelling of vanilla custom payloads.
- **Reusing the framing/codec** keeps a single source of truth for the wire. `:protocol` is
  Java-8-safe with zero Minecraft imports, and the plugin already depends on it
  (`plugin/build.gradle.kts:29` — `implementation(project(":protocol"))`) plus `:core`
  (`plugin/build.gradle.kts:28`) for the `WorldDiff` model `DiffPayload.encode` consumes. The
  client's existing receive+reassemble+render path (Spec C) needs no change because the bytes are
  identical.
- **`Framing.DEFAULT_MAX_FRAME_BYTES = 30_000`** (`protocol/.../Framing.java:20`) is under the
  vanilla serverbound custom-payload cap (32767) and far under the clientbound cap (~1 MB), so a
  single frame is safe over plugin-messaging in both directions.

### Spike-first risk reduction

Task 1 is a **throwaway transport spike**, not production code: prove both directions on a live
NeoForge client against a live Paper server before any plugin machinery is built. Only if the spike
shows a direction *failing* do we fall back to a raw `DiscardedPayload` for that one direction. The
spike de-risks the one claim the whole design rests on — that the existing registered payloads,
already `.optional()`, negotiate against Paper and round-trip the reused codec.

### Correction to the handoff premise (important)

The SP1 handoff and the brainstorm both state that the NeoForge diff payloads are "currently
registered WITHOUT `.optional()`" and that adding it is "the central change." **This is stale.**
`.optional()` is already present: `MineGitNeoForgeNetworking.onRegister` calls
`event.registrar("1").optional()` before registering both `playToClient(DiffRawPayload…)` and
`playToServer(DiffControlPayload…)` (`mod/neoforge/.../neoforge/MineGitNeoForgeNetworking.java:37–49`),
added in commit `4c23c64` (2026-06-05, #84). The transport-mechanism reasoning is unchanged and
correct; the net code delta on the NeoForge side is therefore **zero** — component (a) below is a
verification step, not an edit. The real, substantive work of SP1 is entirely plugin-side
(transport, registry, push) plus the mod-server permission gate. This shifts SP1's risk profile: the
mod transport is believed already-correct, so the spike's job is to *confirm* it and surface any
Paper-specific negotiation gap, not to land a change.

---

## 2. Components

The work splits into six components. (a)/(b) are verification; (c) is a mod-common edit; (d)–(f) are
new plugin code. File paths below are the seams each touches.

### (a) NeoForge optional payloads — VERIFY (no edit expected)

`MineGitNeoForgeNetworking.onRegister` already registers both diff payloads through an `.optional()`
registrar (`mod/neoforge/.../neoforge/MineGitNeoForgeNetworking.java:37`). The spike (task 1) confirms
that this is sufficient for negotiation against Paper in both directions. **Expected delta: none.**
If — and only if — the spike shows a direction failing despite `.optional()`, the fallback is to send
that direction as a raw `DiscardedPayload` over the vanilla custom-payload packet; this is scoped as a
spike contingency, not baseline work.

### (b) Fabric transport — VERIFY (no edit expected)

Fabric registers channels globally (`MineGitFabric.onInitialize`:
`PayloadTypeRegistry.playS2C().register(DiffRawPayload…)` and `…playC2S().register(DiffControlPayload…)`
plus `ServerPlayNetworking.registerGlobalReceiver(DiffControlPayload.TYPE, …)` —
`mod/fabric/.../MineGitFabric.java:21–28`) and announces them via `minecraft:register` automatically.
The handoff predicts no Fabric transport change, and the shipped `canSendToServer()` already uses
`ClientPlayNetworking.canSend(TYPE)` as the Fabric capability probe
(`mod/fabric/.../net/fabric/DiffControlChannelImpl.java`). **Expected delta: none.** The spike must
nonetheless exercise Fabric→Paper explicitly, because Fabric's incoming-channel negotiation surfacing
through `canSend` is the one place behavior could differ from NeoForge (see Open Questions).

### (c) Mod-server permission gate on SUBSCRIBE

**Touch:** `mod/common/.../command/ServerCommandRuntime.java` (`onControl`, lines 136–145).

Today `onControl` honors any received `SUBSCRIBE` — it calls `live.subscribe(player, player.getUUID(),
currentDiffFor(player))` with no authorization check. The keybind (`132dd76`) gates only on
*transport capability*, never on permission, so today a client that can reach the channel can
subscribe regardless of `minegit.use`. SP1 inserts the permission check before `live.subscribe`,
reusing the `MineGitPermissions` checker seam:

```java
if (control == DiffControl.SUBSCRIBE) {
    if (!MineGitPermissions
            .require(Subcommand.DIFF.node(), Subcommand.DIFF.permissionLevel())
            .test(player.createCommandSourceStack())) {
        return; // unpermitted SUBSCRIBE silently ignored — no subscribe, no push
    }
    live.subscribe(player, player.getUUID(), currentDiffFor(player));
}
```

- `MineGitPermissions.require(node, level)` returns a `Predicate<CommandSourceStack>` evaluated by the
  installed checker (`mod/common/.../command/permission/MineGitPermissions.java:57–59`); each loader
  installs its node-aware checker at init, so this honors a LuckPerms/`fabric-permissions-api` grant
  *or* op level.
- `Subcommand.DIFF.node()` → `"minegit.use"`, `permissionLevel()` → `2` (op fallback)
  (`mod/common/.../command/Subcommand.java:25`, `:55`, `:60`), so overlay subscription is gated at the
  same `minegit.use` tier as `/mg diff` — the natural parity choice.
- `ServerPlayer.createCommandSourceStack()` is a confirmed 1.21.11 mapped method (verified against the
  loom merged jar), so the player→source bridge needed by `require(...)` exists.
- **UNSUBSCRIBE is never gated** — a client must always be able to drop its subscription.
- Unpermitted SUBSCRIBE is **silently ignored** (no push, no error frame), matching the keybind's
  quiet degradation and closing today's permission bypass.

This makes the mod server enforce the same rule the plugin will (component (d)), so a permission
denial is identical across server types.

### (d) Plugin transport — register channels + decode incoming control

**Touch:** `plugin/.../MineGitPlugin.java` (`onEnable` wiring) + a new listener class under
`plugin/.../listener/` (e.g. `DiffSubListener`).

No plugin-messaging exists yet (confirmed: no `Messenger`/`PluginMessageListener` anywhere in
`plugin/`). In `onEnable`:

```java
Messenger m = getServer().getMessenger();
m.registerOutgoingPluginChannel(this, Protocol.DIFF_CHANNEL);            // "minegit:diff"  S2C push
m.registerIncomingPluginChannel(this, Protocol.DIFF_CONTROL_CHANNEL,    // "minegit:diffsub" C2S control
        new DiffSubListener(subscriptions, this));
```

`DiffSubListener.onPluginMessageReceived(channel, player, bytes)`:

1. **Decode** the control via `DiffControl.decode(bytes)`, mirroring the mod's `deliverToServer`:
   catch the `IllegalArgumentException`/`NullPointerException` a malformed packet throws, **log and
   drop** — never propagate (`mod/common/.../net/DiffControlChannel.java:71–80` is the reference
   pattern: "one bad packet cannot crash the server or confuse the subscription registry").
2. **Permission-gate** a `SUBSCRIBE`: `if (!player.hasPermission(MineGitCommand.PERM_USE)) return;`
   (`PERM_USE = "minegit.use"`, `plugin/.../command/MineGitCommand.java:50`). Op or a perms-plugin
   grant passes; unpermitted SUBSCRIBE is silently ignored. This is the plugin mirror of component (c).
3. **Drive the registry** (component (e)): `SUBSCRIBE` → add + push current snapshot; `UNSUBSCRIBE` →
   remove.

Decode + permission decision is a **pure function of `(bytes, permission predicate)`** — no Bukkit
needed beyond the boolean check — so it is directly unit-testable (component §3).

### (e) Plugin subscription registry

**New:** a small `DiffSubscriptions` holder owned by `MineGitPlugin`; **touch:** the commit/checkout
completion callbacks in `MineGitCommand` to re-push on HEAD move, and a `PlayerQuitEvent` listener.

- State: `Set<UUID>` of subscribed players (mirrors the mod's `LiveSubscriptionLoop.subscribers`,
  `mod/common/.../net/LiveSubscriptionLoop.java:61`). **No per-tick loop** — this is the snapshot
  model: the plugin has no scheduler today and SP1 adds none.
- **Push-on-subscribe:** on `SUBSCRIBE`, add the UUID and immediately push the player's current
  working-vs-HEAD (component (f)).
- **Re-push on HEAD move:** the working-vs-HEAD a subscriber sees must refresh when HEAD moves for
  their world. The hook points are the existing main-thread completion callbacks:
  - commit — `commitService.commit(…, onComplete)` invoked at `MineGitCommand.java:265`, completion is
    `Consumer<CommitService.Result>` delivered on the main thread (`CommitService.java:103–118,182`).
  - checkout — `checkoutService.checkout(…, onComplete)` at `MineGitCommand.java:418`, same main-thread
    `Consumer<Result>` shape (`CheckoutService.java:106–118`).
  At each completion, when `!result.isError()` and the world has subscribers, recompute the world's
  working-vs-HEAD and push to every subscriber currently in that world. The acting player's `World`
  is in scope in both handlers (`MineGitCommand.java:248` commit, `:407` checkout), and online
  subscribers are filtered by `Bukkit.getPlayer(uuid).getWorld().getName()`.
  - *Note on "for that world":* a commit only changes the **nothing-to-commit vs new-commit** state
    and a checkout changes HEAD; in both cases the post-operation working-vs-HEAD for that world is
    the correct overlay for every subscriber standing in it. A commit that reports `commit == null`
    (nothing changed, `CommitService.Result.commit()` null) still leaves the diff equal to its prior
    value, so a re-push is a harmless idempotent refresh; SP1 may push unconditionally on non-error
    completion for simplicity.
- **Remove on UNSUBSCRIBE/quit:** `UNSUBSCRIBE` removes the UUID; a `PlayerQuitEvent` listener removes
  it too (parity with the mod's `disconnect` == `unsubscribe`, `LiveSubscriptionLoop.java:108–111`).

### (f) Plugin push — encode → frame → per-frame plugin message

**New:** a `DiffPush` helper (or method on the plugin) invoked by (d)/(e).

```java
WorldDiff diff = workingTreeDiff(repo, adapter, world.getName());   // existing core path
byte[] payload = DiffPayload.encode(diff, "HEAD", "WORKING");        // same tags the mod uses
for (Frame f : Framing.frame(payload, Framing.DEFAULT_MAX_FRAME_BYTES)) {
    player.sendPluginMessage(this, Protocol.DIFF_CHANNEL, f.toBytes());
}
```

- The diff is the **same working-vs-HEAD** `/mg diff`/`/mg status` already compute:
  `MineGitCommand.workingTreeDiff(repo, adapter, worldName)` (`plugin/.../command/MineGitCommand.java:229`),
  which dispatches to `WorldDiffer.diffWorkingTreeDirty` (primed) or `diffWorkingTree` (full) and
  yields a core `WorldDiff` — exactly what `DiffPayload.encode` consumes.
- `"HEAD"`→`"WORKING"` ref tags match the mod's push (`LiveSubscriptionLoop.pushIfChanged` tags
  `"HEAD","WORKING"`, `LiveSubscriptionLoop.java:168`), so the client renders identically.
- A world-name → `DimensionId` mapping is available via `BukkitWorldAdapter.dimensionOf(World)` /
  `MineGitPlugin.adapterFor(World)` for the diff computation; the wire frame is dimension-agnostic
  (the diff carries its own dimension keys).
- All sends are on the main thread (push-on-subscribe runs in the listener; re-push runs in the
  main-thread completion callback), so `sendPluginMessage` is called safely.

### End-to-end sequence (client J on a plugin server)

1. Client presses **J**. `OverlayClientHooks` checks `DiffControlChannel.canSendToServer()` — true,
   because the plugin negotiated `minegit:diffsub` via plugin-messaging registration and Bukkit
   announces it over `minecraft:register`. The keybind sends `DiffControl.SUBSCRIBE` as a
   `minegit:diffsub` payload.
2. Bukkit delivers the payload to the plugin's incoming `minegit:diffsub` listener (component (d)).
3. Listener `DiffControl.decode(bytes)` → `SUBSCRIBE` (malformed → log+drop).
4. Permission gate: `player.hasPermission("minegit.use")`. **Unpermitted → return; nothing pushed.**
5. Permitted → registry adds the UUID (component (e)); the plugin computes the player's world's
   working-vs-HEAD and pushes it (component (f)) as `minegit:diff` frames.
6. Client reassembles the frames (`Reassembler`) and renders boxes — the *unchanged* Spec C receive
   path.
7. Later, the player runs `/mg commit` or `/mg checkout` (or another player does, in that world). On
   non-error completion the registry re-pushes the fresh working-vs-HEAD to every subscriber in that
   world; the overlay refreshes.
8. The player presses **J** again → `UNSUBSCRIBE` → registry removes the UUID → no further pushes.
   (A disconnect does the same via `PlayerQuitEvent`.)

---

## 3. Testing

### Task 1 — transport spike (first deliverable, throwaway)

Before any production code: prove both directions on a **live NeoForge client ↔ live Paper server**,
and separately **Fabric client ↔ Paper**. Minimal throwaway plugin that registers the two channels,
echoes a hand-built `DiffPayload` frame on receiving any `minegit:diffsub` byte. Success criterion: a
modded client subscribes and renders at least one box; no `refusing to decode` in the client log; no
`UnsupportedOperationException` on send. Records, for each loader+direction, pass/fail — the one
input that decides whether any `DiscardedPayload` fallback is needed.

### Headless plugin JUnit (no server boot)

- **Subscription registry:** add on SUBSCRIBE, remove on UNSUBSCRIBE, remove on quit; idempotent
  double-subscribe; query-by-world filtering.
- **Decode + permission decision as a pure function:** given control bytes and a permission predicate,
  assert SUBSCRIBE-permitted → "subscribe+push", SUBSCRIBE-unpermitted → "ignore", UNSUBSCRIBE →
  "remove" (ungated), malformed bytes → "drop" (no throw escapes). Mirrors the mod's `deliverToServer`
  contract.
- **Round-trip equality:** `DiffPayload.encode(diff,"HEAD","WORKING")` → `Framing.frame(payload,
  DEFAULT_MAX_FRAME_BYTES)` → `Reassembler.add(each)` → `DiffPayload.decode(reassembled).equals(diff)`,
  including a multi-frame diff (payload > 30 KB) and an empty diff (one empty frame). These classes are
  `:protocol`/`:core` and already covered by their own tests; this asserts the plugin's *use* of them
  is a faithful round trip.

### Mod-common unit test — `onControl` permission gate

Using the `MineGitPermissions` checker seam (the existing test pattern,
`mod/common/.../command/permission/MineGitPermissionsTest.java`) with a fake/recording live registry:

- Install a checker that **denies** → `onControl(player, SUBSCRIBE)` does **not** call
  `live.subscribe` (assert no subscriber added, no push).
- Install a checker that **allows** → `onControl(player, SUBSCRIBE)` **does** subscribe.
- `onControl(player, UNSUBSCRIBE)` always unsubscribes regardless of checker.

This requires a seam to observe/inject the `LiveSubscriptionLoop` in `ServerCommandRuntime` (a
package-private accessor or constructor injection of a fake loop). The decision is exercised without a
live server because both `MineGitPermissions.require` and `LiveSubscriptionLoop` are headless; the
`ServerPlayer.createCommandSourceStack()` bridge is the only Minecraft-touching line and is covered by
the manual matrix below rather than unit tests.

### Manual integration matrix (user-side — live Paper + both loaders)

Requires a running Paper server with the plugin and a perms backend; not automatable.

| scenario | expected |
|---|---|
| permitted client (`minegit.use`/op) presses J | overlay boxes appear |
| unpermitted client presses J | nothing — no boxes, no error spam |
| permitted client subscribed, then `/mg commit` (this world) | overlay refreshes to new working-vs-HEAD |
| permitted client subscribed, then `/mg checkout <ref>` | overlay refreshes after HEAD move |
| subscribed client presses J again | overlay clears, no further pushes |
| subscribed client disconnects | registry drops it (verify via re-join shows no stale push) |
| same flow on the mod server | unchanged from today (regression check) |

---

## 4. Sequencing / dependencies

- **SP1 ships before SP2.** SP1 establishes the plugin transport + snapshot push and the mod-server
  permission gate; SP2 (client-side live diff) builds on that transport at implementation time.
- **Accepted, temporary divergence:** in SP1 the mod server keeps its existing per-tick
  `LiveSubscriptionLoop` recompute (untouched except the (c) permission gate), while the plugin uses
  the event-driven snapshot model (push-on-subscribe + push-on-HEAD-move). The two therefore differ in
  *liveness cadence* (mod re-pushes every `DEFAULT_REFRESH_TICKS = 10` ticks; plugin re-pushes only on
  HEAD move). This is known and accepted: **SP2 unifies them** by retiring the mod's per-tick recompute
  in favor of the same event-driven push.
- **Files SP2 will also touch** (call out so ordering is explicit and SP2's spec notes the dependency):
  - `mod/common/.../net/LiveSubscriptionLoop.java` — SP2 drops the per-tick recompute + dedupe, keeps
    the subscription registry + push primitive.
  - `mod/common/.../command/ServerCommandRuntime.java` — SP2 changes `onControl`/`tick` to event-driven
    push-on-HEAD-move; SP1 has already edited `onControl` (the permission gate), so SP2 rebases on it.
  - `mod/neoforge/.../neoforge/MineGitNeoForgeNetworking.java` — already `.optional()`; SP2 only
    revisits if the spike's fallback added a raw-payload path.
  Because SP1 lands first, SP2 takes the (c) gate as a given and does not re-introduce it.

---

## Open questions / risks (the spike must resolve)

1. **Fabric incoming-channel negotiation via `canSend`.** NeoForge's `hasChannel`/`checkPacket`
   coupling is verified; Fabric's `ClientPlayNetworking.canSend(DiffControlPayload.TYPE)` must flip
   `true` against a Paper server that registered the `minegit:diffsub` incoming channel. If it does
   not surface (Fabric announces *its own* registered channels, not necessarily the server's
   server-bound declarations), the keybind's `canSendToServer()` gate would suppress the subscribe on
   Fabric→Paper even though the wire would work. The spike must confirm `canSend` is `true` on
   Fabric→Paper; if not, SP1 needs a Fabric-side capability adjustment (out of the currently-scoped
   "no change," would be a small follow-up).
2. **Clientbound frame-size headroom.** `DEFAULT_MAX_FRAME_BYTES = 30_000` is safe per-frame, but a
   large world's working-vs-HEAD can span *many* frames. The spike should push a deliberately large
   diff to confirm Paper does not coalesce/throttle a burst of `minegit:diff` plugin messages in a way
   the client's `Reassembler` mishandles (it shouldn't — frames carry seq/total — but verify under
   load).
3. **HEAD-move hook completeness in CommitService/CheckoutService.** The chosen hook is the existing
   `onComplete` callback. Confirm there is no *other* path that moves HEAD without going through those
   callbacks (e.g. a future direct repo op). For SP1 the only HEAD-movers are `/mg commit` and
   `/mg checkout`, both of which route through the services, so the hook is complete today.
4. **`DiscardedPayload` fallback scope.** If the spike shows a direction failing despite `.optional()`,
   the fallback raw-payload path is mod-side only (the plugin already speaks raw vanilla payloads via
   plugin-messaging). Scope and loader-specificity of that fallback are deferred until the spike
   result is known — it is explicitly *not* baseline work.
5. **Per-world subscriber resolution on re-push.** A subscriber may change dimension/world while
   subscribed. SP1 resolves "subscribers in that world" at push time from the live player's current
   world, so a player who walked into another world simply isn't pushed for the committing world — the
   correct behavior. Confirm the mod's per-level resolution (`ServerCommandRuntime.pollSubscriber` /
   `currentDiffFor`, dimension-aware, `ServerCommandRuntime.java:160–192`) and the plugin's
   world-name resolution agree semantically.
