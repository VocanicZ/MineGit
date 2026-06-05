# MineGit — Spec C Batch 2: live keybind overlay, colored HUD, freezing init (design)

**Date:** 2026-06-05
**Status:** Approved (design). Decomposes into a PRD + tracer-bullet issues for the Harness fleet.
**Builds on:** Spec C batch 1 (`docs/superpowers/specs/2026-06-05-minegit-client-overlay-design.md`, PRD #75, merged).
**Umbrella:** `docs/superpowers/specs/2026-06-03-minegit-architecture-design.md`

---

## 1. What this is

Four refinements to the shipped diff-overlay. The headline change **inverts the overlay model**: instead of
`/mg diff` pushing a one-shot snapshot, the **keybind drives a live subscription** to working-vs-HEAD that the
server pushes as the world changes.

1. **`/mg diff` no longer pushes the overlay** — it is chat-only; the in-world overlay is keybind-only.
2. **Colored HUD** — the white `+N −M ~K` line becomes green/red/yellow to match the box legend.
3. **Freezing `/mg init`** — init snapshots synchronously (tick frozen) by default; `--nofreeze` keeps the
   current tick-pumped spread.
4. **Realtime overlay** — while toggled on, the overlay updates live as you build (working-vs-HEAD).

### Non-goals (this batch)
- Ref-vs-ref **overlay** (refs stay chat-only; the live overlay is always working-vs-HEAD).
- Spigot-plugin parity for any of these (mod only).
- A baked/GPU-buffered renderer; per-block tooltips; commit-picker UI.

---

## 2. Live keybind overlay (changes #1 + #4)

The overlay flow inverts from *server-push-on-command* to *client-subscribes / server-pushes-live*.

```
  keybind ON  ──SUBSCRIBE──▶ server: add player to live registry, push current working-vs-HEAD
       │                              │
       │                     every ~liveRefreshTicks (default 10):
       │                       recompute working-vs-HEAD for DIRTY chunks (non-destructive)
       │                       → dedupe vs last pushed (WorldDiff.equals) → DiffOverlaySender.send
       ▼                              ▼
  client renders each pushed payload (replace-on-new) ──── HUD + boxes
  keybind OFF ──UNSUBSCRIBE──▶ server: drop player; client clears overlay
```

### 2.1 Client→server control channel (`minegit:diffsub`)
- New `protocol` constant `Protocol.DIFF_CONTROL_CHANNEL = "minegit:diffsub"`, carrying a **1-byte** control
  message (`0 = UNSUBSCRIBE`, `1 = SUBSCRIBE`). This is the client→server packet Spec C batch 1 deferred.
- Registered per loader (`@ExpectPlatform`, server receive): Fabric `ServerPlayNetworking` receive, NeoForge
  payload handler. Reuses the raw-byte-payload pattern from batch 1's `minegit:diff`.

### 2.2 Client
- **`/mg diff` chat-only:** delete the `DiffOverlaySender.send(...)` push at `ServerCommandRuntime` (currently
  line ~301). The command's chat body is unchanged.
- **Keybind = subscription toggle** (in `OverlayClientHooks.onClientTick`): on toggle the client flips
  `OverlayClientState.visible` *and* sends the matching SUB/UNSUB control message; UNSUB also clears the held
  overlay locally.
- **Receive/render unchanged:** pushed payloads arrive periodically and replace the held `OverlayState`
  (batch-1 `acceptFrame` + replace-on-new already does this); boxes + HUD render as today.
- **Auto-expire retires:** while subscribed, the overlay reflects current live state and must NOT auto-expire.
  Remove the `tickExpiry` clear from the live model; the only clears are toggle-off (UNSUB), disconnect, and
  dimension change. `OverlayConfig.autoExpireSeconds` is parsed-but-ignored for config back-compat.

### 2.3 Server
- **Subscription registry:** a per-`ServerPlayer` set of live subscribers (keyed by UUID), populated by the
  control-channel handler; cleared on UNSUBSCRIBE and on player disconnect.
- **Live loop:** a server-tick hook (a tick counter; reuse the existing `TickPump` cadence machinery) that
  every `liveRefreshTicks` (default 10) does, per subscriber, for the player's current level:
  - recompute working-vs-HEAD via `MineGitService.status(repoPath, adapter, clock, tracker)`,
  - **dedupe**: skip the push if the resulting `WorldDiff` equals the last one pushed to that player,
  - else `DiffOverlaySender.send(player, diff, "HEAD", "WORKING")` (already `canSend`-gated).
- **Dimension-aware:** the recompute targets the player's current level; a dimension change naturally yields a
  different diff and a fresh push (the client also cleared on its side).
- **Hard constraint — non-destructive poll:** the live recompute must NOT drain/consume the dirty tracker that
  `/mg commit` relies on. Working-vs-HEAD for the live overlay is read-only over the current dirty set (and any
  chunks previously shown), leaving the tracker intact for the next commit.
- **Cost containment:** only subscribed players incur work; recompute is dirty-scoped + throttled + deduped;
  the client `renderCap` still bounds draw. A subscriber with no uncommitted changes pushes nothing after the
  initial empty diff.

---

## 3. Colored HUD (change #2)

`OverlayHud` draws one `0xFFFFFFFF` string. Replace with per-segment color:
- Factor a **pure** `List<Segment> OverlayHud.segments(OverlayState state, int dropped)` helper (Segment =
  `{ text, argb }`): `+N` → green, `−M` → red, `~K` → yellow, `(+J more)` → gray, using `OverlayColor.rgb()`
  with opaque alpha (`0xFF000000 |`).
- Render walks the segments left-to-right, advancing `x` by `font.width(seg.text) + space`. Corner anchoring
  unchanged (compute total width from the segment widths for right/bottom corners).
- The `segments()` helper is headless-unit-tested (text + color per count); only pixel placement stays in the
  client-gated render.

---

## 4. Freezing init (change #3)

- `/mg init` **freezes by default:** run the initial snapshot **synchronously** on the server thread — a
  blocking commit (full scan → read all loaded chunks → git write) inside the command invocation, so the tick
  is frozen until it completes, then resumes. User message: "Freezing server to snapshot world…" then the
  normal commit report.
- **`--nofreeze`** (optional Brigadier literal token on `/mg init`, tab-completed): keeps today's behavior —
  the `CommitService` + `TickPump` spread across ticks.
- Implementation: add a **synchronous** commit path (run the same `CommitService` work to completion on the
  calling thread, or a `MineGitService.commitBlocking(...)`), selected by the flag; `--nofreeze` routes to the
  existing pumped `CommitService.commit(...)`.
- **Watchdog note:** a long synchronous snapshot can trip a dedicated-server tick watchdog on very large
  worlds; `--nofreeze` is the documented escape hatch. Single-player (integrated server) is the common case
  and freezes cleanly.

---

## 5. Verification (layered)
- **Unit (JUnit, headless):**
  - `OverlayHud.segments()` — text + color per count, `(+J more)` present/absent.
  - control-packet codec (SUB/UNSUB byte round-trip).
  - server subscription registry + the recompute→dedupe→push step via a recording `DiffOverlaySender.Sink`
    (push on change, no push when unchanged, no push for incapable player).
  - **non-destructive poll** — repeated live recompute leaves the dirty tracker intact for a following commit.
  - init: freeze path completes the snapshot commit **before** the command returns (repo HEAD has it);
    `--nofreeze` returns before completion (pumped).
- **GameTest (both loaders, CI):** subscribe → place blocks → assert a push lands on the captured sink and
  reassembles to the expected working-vs-HEAD → unsubscribe → assert pushes stop. Init freeze vs `--nofreeze`.
- **Manual visual-smoke:** colored HUD; keybind toggles a live overlay that updates as you build and clears on
  toggle-off / dimension change / disconnect.

---

## 6. Decomposition — tracer-bullet issues (DAG)

| # | Issue | Blocked by |
|---|---|---|
| A | `mod`: colored HUD — pure `segments()` helper (green/red/yellow/gray) + unit | — |
| B | `mod`: `/mg init` freeze-by-default + `--nofreeze` (synchronous vs pumped snapshot) + test | — |
| C | `protocol`+`mod`: `minegit:diffsub` client→server control channel (SUB/UNSUB byte) + codec + per-loader server receive | — |
| D | `mod`: `/mg diff` drops overlay push; keybind toggles SUB/UNSUB; client renders live updates; retire auto-expire | C |
| E | `mod`: server live-subscription loop — registry, throttled dirty-scoped recompute + dedupe + non-destructive push, dimension-aware, disconnect cleanup; GameTest both loaders | C |
| F | `mod`: config (`liveRefreshTicks`) + CI wiring + manual smoke checklist for live overlay + colored HUD | D, E |

A and B are independent and dispatch immediately; C unblocks D + E; F closes the batch.
