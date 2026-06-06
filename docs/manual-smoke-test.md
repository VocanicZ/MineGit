# MineGit — Manual visual-smoke checklist (live diff overlay + colored HUD)

The diff overlay's GPU draw cannot be asserted in CI, so the pixel layer is verified by hand. Everything
*below* the draw call is automated: the `Frame` wire codec, `OverlayState` (cull / cap / order), the
colored `OverlayHud.segments()` helper, the `minegit:diffsub` control codec, and the server live loop
(registry → throttled non-destructive recompute → dedupe → push) are JUnit-tested headless, and the
subscribe → place-blocks → push → unsubscribe path is a both-loader GameTest with a captured network
sink. This checklist covers only what those cannot — that boxes actually appear, in the right place and
color, that the **live keybind overlay** updates as you build and clears on the right lifecycle events,
and that `/mg init` **freezes by default** vs `--nofreeze`.

Run it on **each loader** before shipping an overlay change:

```bash
./gradlew :mod:fabric:runClient
./gradlew :mod:neoforge:runClient
```

## Steps (Spec C batch 2 §5)

1. **Setup** — launch the client, create/open a single-player world.
2. **Init freeze (default)** — run `/mg init`. Expect:
   - [ ] Chat shows *"Freezing server to snapshot world…"* then the normal commit report.
   - [ ] The snapshot commit is in `HEAD` the instant the command returns (the tick froze until done).
   - [ ] On a small world the freeze is imperceptible; on a large world the tick visibly hitches once.
3. **Init nofreeze** — in a fresh world, run `/mg init --nofreeze`. Expect:
   - [ ] The command returns immediately and the snapshot completes *across* the next several ticks
         (no freeze); HEAD gets the commit a moment later (pumped via `TickPump`).
   - [ ] `--nofreeze` tab-completes after `/mg init`.
4. **Baseline** — build a small structure, `/mg commit -m "base"`.
5. **`/mg diff` is chat-only** — run `/mg diff`. Expect:
   - [ ] It prints the `+N −M ~K` summary **in chat** and draws **no** overlay (overlay push retired, #92).
6. **Keybind toggles the LIVE overlay** — press the toggle key (default **J**). Expect:
   - [ ] Translucent boxes appear at the currently-changed blocks (working-vs-`HEAD`), correctly placed.
   - [ ] **Green** = added, **red** = removed, **yellow** = changed (the legend) — boxes and HUD segments.
   - [ ] The HUD shows colored `+N` (green) `−M` (red) `~K` (yellow) in the configured corner;
         `(+J more)` (gray) appears only when boxes exceed `renderCap`.
7. **Live updates as you build** — with the overlay on, place/break blocks. Expect:
   - [ ] The overlay and HUD counts **update on their own** within ~`liveRefreshTicks` ticks (default 10
         ≈ 0.5 s) — no re-running any command.
   - [ ] Making **no** change produces no flicker (the server dedupes identical working-vs-HEAD).
   - [ ] `/mg commit` then shows an **empty** overlay shortly after (working == HEAD again), and the
         very next `/mg commit` still captures changes — the live poll never drained the dirty tracker.
8. **Distance cull** — walk away past `maxRenderDistance`: far boxes stop drawing; the HUD / `(+J more)`
   counts reflect what's culled.
9. **Lifecycle clearing**:
   - [ ] Pressing the toggle key again (UNSUBSCRIBE) **clears** the overlay and stops live pushes.
   - [ ] Changing dimension (nether portal / `/execute in`) **clears** the overlay; it repopulates for
         the new level on the next live push.
   - [ ] Disconnecting and rejoining starts with **no** overlay (subscription cleared on quit).
10. **`liveRefreshTicks` cadence** — set `liveRefreshTicks=40` in the config (≈2 s), relaunch, toggle the
    overlay on, and build: updates should now lag visibly (~2 s) vs the default. Set `liveRefreshTicks=2`
    for near-immediate updates. (Clamped to `>= 1`.)
11. **Vanilla server safety** — against a server *without* the mod, the toggle key sends nothing the
    server acts on and produces no client-side errors.

## Config

Both client and server read `<configDir>/minegit-overlay.properties` on init and fall back to the
defaults below for any missing or unparseable key. If the file is absent on first run, a commented
template with the defaults is written for you to edit. There is no config-UI screen.

| Key | Default | Meaning |
|---|---|---|
| `keybind` | `J` | toggle key (GLFW name); also rebindable via vanilla Controls |
| `maxRenderDistance` | `64` | blocks; beyond this, boxes are culled |
| `renderCap` | `4096` | max boxes drawn per frame (nearest first); the rest are counted as `(+J more)` |
| `autoExpireSeconds` | `60` | parsed-but-ignored — auto-expire retired (#92); kept for config back-compat |
| `liveRefreshTicks` | `10` | **server** live-overlay push cadence in server ticks; clamped to `>= 1` |
| `hudCorner` | `top-left` | HUD anchor (`top-left` / `top-right` / `bottom-left` / `bottom-right`) |

To smoke-test the config wiring quickly: set `renderCap=1` and `liveRefreshTicks=2`, relaunch, toggle
the overlay on over a multi-block change — only the nearest box should draw with `(+J more)` on the HUD,
and edits should reflect in the overlay within ~0.1 s.
