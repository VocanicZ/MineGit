# MineGit — Spec C: Client Diff-Overlay (design)

**Date:** 2026-06-05
**Status:** Approved (design). Decomposes into a PRD + tracer-bullet issues for the Harness fleet.
**Umbrella:** `docs/superpowers/specs/2026-06-03-minegit-architecture-design.md` (§4.3, §7 flow ②, §9 client row)
**Builds on:** `core` + `protocol` (Spec A b1+b2), server-mod (Spec D b1, `docs/superpowers/specs/2026-06-04-minegit-mod-spec.md`)

---

## 1. What this is

When a player runs `/mg diff`, the server streams the computed block-diff to that player over the
`minegit:diff` channel; the player's client renders it as translucent colored boxes in the world
(**ADD = green, REMOVE = red, CHANGE = yellow**), with a `+N −M ~K` HUD and a keybind that toggles the
overlay. This is the **diff-overlay frontend** the umbrella reserved for "Spec C".

It is a **full vertical slice across three layers** so the overlay is verifiably visible in-world, not a
client stub against a dead channel:

1. a `Frame`⇄bytes wire codec in `protocol`,
2. the **server send** path in the existing Architectury `mod` (`@ExpectPlatform` per loader), and
3. the **client receive + render** path, folded into the same `mod` as client-distribution code.

### Goals
- `/mg diff [refA] [refB]` pushes the diff to the requesting player; the client renders an in-world
  block-level overlay + a `+N −M ~K` HUD; a keybind toggles visibility.
- Works against **any server that speaks the wire protocol** — the Architectury server-mod now, and a
  future Spigot-plugin send later — because the client registers `minegit:diff` as a **raw byte payload**.
- Reuses the finished `DiffPayload` / `Framing` / `Reassembler` codec **unchanged**.

### Non-goals (this batch)
- **Spigot-plugin send** — the plugin's `/mg diff` keeps printing chat; its channel send is a later batch.
- **Client → server request packets** — the keybind only toggles a *received* overlay; it never requests one.
- A **baked/GPU-buffered renderer** — immediate-mode draw only; baking is a later optimization.
- Block-entity / biome diffs, commit-picker UI, per-block tooltips, overlay for unloaded chunks.

---

## 2. Architecture — three layers

```
  /mg diff [refs]                       (server, existing command)
        │  computes WorldDiff (already done for chat)
        ▼
  DiffPayload.encode → Framing.frame(…,30k) → Frame.toBytes()      ← protocol (NEW codec)
        │  one Frame == one minegit:diff custom-payload packet
        ▼  @ExpectPlatform DiffChannel.sendTo(player, bytes)  [canSend-gated]
  ════════════════ minegit:diff (raw byte payload) ════════════════
        ▼  client receiver (NEW, client-dist)
  Reassembler.add(Frame) ─complete→ DiffPayload.decode → WorldDiff
        ▼
  OverlayState  (pure data: per-dim boxes + counts + refs + receivedAt)   ← unit-tested core
        │                              │
   world-render hook              HUD hook                 keybind
   (@ExpectPlatform)         (ClientGuiEvent.RENDER_HUD)  (KeyMappingRegistry)
   translucent boxes,        "+N −M ~K  (+J more)"        toggles visibility
   culled + capped
```

**Key principle (unchanged from umbrella):** the overlay's *logic* is platform- and GPU-agnostic. The
renderer is a thin reader over `OverlayState`; everything that can be wrong in a headless test lives in
`OverlayState`, not in a draw call.

### 2.1 Wire codec — `protocol` (no Minecraft imports)
`Framing`/`Reassembler` already split a payload into `Frame` objects and back, but nothing turns a single
`Frame` into the bytes of one packet. Add that:

- `byte[] Frame.toBytes()` and `Frame Frame.fromBytes(byte[])` (or a `FrameCodec` peer) with layout
  `sessionId (int) | seq (uvarint) | total (uvarint) | data (remaining bytes)`, varint-framed, matching the
  existing `DiffPayload` primitive style.
- Deterministic + lossless round-trip: `fromBytes(toBytes(f)).equals(f)`. JUnit-tested.

### 2.2 Server send — existing `mod` (`common` + `@ExpectPlatform`)
- The `/mg diff` command already computes a `WorldDiff` for chat. After emitting chat, **also**:
  `DiffPayload.encode(diff, fromRef, toRef)` → `Framing.frame(payload, Framing.DEFAULT_MAX_FRAME_BYTES)` →
  for each `Frame`, `Frame.toBytes()` → send a `minegit:diff` packet to the requesting `ServerPlayer`.
- `@ExpectPlatform DiffChannel`:
  - `boolean canSend(ServerPlayer)` — Fabric `ServerPlayNetworking.canSend`, NeoForge channel/connection check.
  - `void sendTo(ServerPlayer, byte[] frameBytes)` — Fabric `ServerPlayNetworking.send`, NeoForge
    `PacketDistributor`/`connection.send` with a raw-bytes custom payload on `minegit:diff`.
- **Gated on `canSend`**: a player without the client mod (vanilla/other) is silently skipped — no error,
  the chat output is unaffected. Sends run off the diff computation path on the server thread without
  blocking (frames are already in memory; the send is a cheap enqueue).
- The custom-payload **type id is `minegit:diff` carrying an opaque `byte[]`** — *not* a typed mod struct —
  so the exact bytes a future Spigot plugin emits decode identically on the client.

### 2.3 Client receive + render — existing `mod`, client-distribution
Folded into the existing `mod` per the chosen module structure: the render/receive code is client-dist
(Fabric `client` entrypoint / NeoForge client init, `@Environment(CLIENT)`-gated), so the **one mod jar**
serves server commands *and* the client overlay. A player on a Spigot server still installs this mod to
get overlays; its server-side command logic simply lies dormant there.

- **Receiver:** register a client handler for the raw `minegit:diff` payload. Each packet → `Frame.fromBytes`
  → `Reassembler.add(frame)`; on a completed payload → `DiffPayload.decode` → build a new `OverlayState`.
- **`OverlayState`** (pure data, lives in `common`, no render imports — the unit-tested core):
  - the decoded diff reduced to, per `DimensionId`, a flat list of `OverlayBox { x, y, z, color }`
    (`color` from `BlockChange.Kind`: ADD→green, REMOVE→red, CHANGE→yellow),
  - the aggregate `added / removed / changed` counts and the `fromRef`/`toRef` labels,
  - a `receivedAt` tick stamp (for auto-expiry),
  - `List<OverlayBox> visibleBoxes(camX, camY, camZ, maxDistance, cap)` → distance-filtered, sorted nearest
    first, truncated to `cap`; returns the kept boxes and exposes how many were dropped (for the HUD).
- **Client overlay holder** (client-side singleton): one current `OverlayState` (replace-on-new), a
  `visible` boolean, and the active dimension id. Toggling/clearing mutate only this holder.
- **World-render hook** (`@ExpectPlatform`): Fabric `WorldRenderEvents.AFTER_TRANSLUCENT`, NeoForge
  `RenderLevelStageEvent` (after translucent blocks). If `visible`, not expired, and the player's current
  dimension matches the overlay's: draw each `visibleBoxes(camera, maxDistance, cap)` box as a translucent
  cube via a `VertexConsumer` against the frame's `PoseStack`, frustum-aware. Immediate-mode; no buffers.
- **HUD hook** (Architectury `ClientGuiEvent.RENDER_HUD`): when an overlay is active, draw
  `+N −M ~K` in the configured corner, plus `(+J more)` when `visibleBoxes` truncated J boxes.
- **Keybind** (Architectury `KeyMappingRegistry`, default e.g. `J`, rebindable): toggles `visible` on the
  held overlay. With no overlay held, it is a no-op (optionally a brief actionbar hint).

---

## 3. Behavior

- **Trigger:** server-push only — `/mg diff [refs]` sends the overlay to that player. The keybind never
  requests a diff; it only shows/hides the last one received.
- **Lifecycle:**
  - a newly received diff **replaces** the held overlay (and resets its expiry),
  - the overlay **auto-clears** on dimension change and on disconnect,
  - a **configurable auto-expire timer** clears it after `autoExpireSeconds` (default **60s**) so a
    forgotten overlay tidies itself up.
- **Color legend:** ADD = green, REMOVE = red, CHANGE = yellow, all translucent.

### 3.1 Client config (small file under the config dir)
| Key | Default | Meaning |
|---|---|---|
| `keybind` | `J` | toggle key (also rebindable via vanilla Controls) |
| `maxRenderDistance` | `64` | blocks; beyond this, boxes are culled |
| `renderCap` | `4096` | max boxes drawn per frame (nearest first); rest counted as `(+J more)` |
| `autoExpireSeconds` | `60` | overlay auto-clear timeout; `0` disables the timer |
| `hudCorner` | `top-left` | HUD anchor |

Keep it minimal — a plain JSON/properties file the client reads on init and falls back to defaults for any
missing key. No config-UI screen this batch.

---

## 4. Module & build notes
- All new server + client code lands in the existing `mod` tree (`common` / `fabric` / `neoforge`); the
  `protocol` codec addition lands in `protocol`. No new gradle subproject.
- The `mod` must now ship **client entrypoints**: Fabric `client` entrypoint in `fabric.mod.json`; NeoForge
  client-dist init. Render/keybind/HUD code is `@Environment(CLIENT)`-gated so the dedicated server never
  classloads client-only MC types.
- World-render and packet send/receive are the loader-specific seams → `@ExpectPlatform`. HUD, keybind, and
  the receive→decode→`OverlayState` flow are loader-agnostic (`common` + Architectury events).
- Reuses the bundled/relocated `core` + `protocol` already in the mod jar; the client path needs
  `core.model` + `protocol` only (no JGit at render time).

---

## 5. Verification (layered)
The actual GPU draw cannot be asserted in CI, so coverage is layered to maximize what *is* automated and
isolate the untestable pixel layer:

- **Unit (JUnit, headless):**
  - `Frame` wire codec round-trip (incl. multi-frame, empty, max-size slices).
  - `OverlayState`: frames→reassemble→decode→state; color mapping per `Kind`; `visibleBoxes` distance cull,
    nearest-first ordering, cap truncation + dropped-count; per-dimension separation; expiry stamping.
- **GameTest (both loaders, in CI):** server send — drive `/mg diff` (or the send entrypoint) and assert
  the **framed bytes hit a captured network sink** (a test `DiffChannel` that records instead of
  transmitting), that frames reassemble + decode back to the source `WorldDiff`, and that a `canSend=false`
  player is skipped.
- **Manual visual-smoke checklist (documented, in the PRD/issue):** `runClient` on each loader → single
  world → `/mg init`, build, `/mg commit`, mutate, `/mg diff` → green/red/yellow boxes appear at the right
  blocks → HUD shows correct `+N −M ~K` → keybind toggles → overlay clears on dimension change and after the
  expire timer.

---

## 6. Decomposition — tracer-bullet issues (DAG)

| # | Issue | Blocked by |
|---|---|---|
| 1 | `protocol`: `Frame`⇄bytes wire codec (`toBytes`/`fromBytes`) + round-trip JUnit | — |
| 2 | `mod`: register `minegit:diff` **raw byte payload** on both loaders (`@ExpectPlatform` send+receive plumbing, no logic yet); both loaders launch with the channel registered | 1 |
| 3 | `mod`: **server send** — on `/mg diff`, encode→frame→`sendTo` the requesting player, `canSend`-gated; **GameTest** with a captured sink (both loaders) | 1, 2 |
| 4 | `mod`: **`OverlayState`** pure model — reassemble→decode→boxes, color map, `visibleBoxes` cull/cap/dropped-count, expiry; **JUnit** | 1 |
| 5 | `mod`: **client receive + render + HUD + keybind + lifecycle** — receiver, world-render `@ExpectPlatform`, `RENDER_HUD`, `KeyMappingRegistry`, replace/dimension/disconnect/expire clearing | 2, 4 |
| 6 | `mod`: **client config** + **manual visual-smoke checklist** doc + CI wiring for the new tests | 5 |

This is the umbrella's `diff` overlay flow ② realized end-to-end. Each row is an independently-reviewable
slice; the engine and the existing commands are reused unchanged.
