# MineGit — Manual visual-smoke checklist (diff overlay)

The diff overlay's GPU draw cannot be asserted in CI, so the pixel layer is verified by hand. Everything
*below* the draw call is automated: the `Frame` wire codec and `OverlayState` (cull / cap / order /
expiry) are JUnit-tested headless, and the server-send path is a both-loader GameTest with a captured
network sink. This checklist covers only what those cannot — that boxes actually appear, in the right
place, in the right color, and that the HUD / keybind / lifecycle behave in a live client.

Run it on **each loader** before shipping an overlay change:

```bash
./gradlew :mod:fabric:runClient
./gradlew :mod:neoforge:runClient
```

## Steps (Spec C §5)

1. **Setup** — launch the client, create/open a single-player world.
2. **Init + baseline** — `/mg init`, build a small structure, `/mg commit -m "base"`.
3. **Mutate** — add some blocks, remove some, change some (e.g. stone → dirt).
4. **Diff** — run `/mg diff`. Expect:
   - [ ] Translucent boxes appear **at the changed blocks**, not offset.
   - [ ] **Green** = added, **red** = removed, **yellow** = changed (the legend).
   - [ ] The HUD shows the correct `+N −M ~K` in the configured corner.
   - [ ] When more boxes exist than `renderCap`, the HUD appends `(+J more)`.
5. **Keybind** — press the toggle key (default **J**): the overlay hides; press again: it shows.
6. **Distance cull** — walk away past `maxRenderDistance`: far boxes stop drawing; the `(+J more)`
   / HUD counts reflect what's culled.
7. **Lifecycle clearing**:
   - [ ] A second `/mg diff` **replaces** the overlay (and resets its expire timer).
   - [ ] Changing dimension (nether portal / `/execute in`) **clears** the overlay.
   - [ ] Disconnecting and rejoining starts with **no** overlay.
   - [ ] After `autoExpireSeconds` (default 60) with the overlay shown, it **auto-clears**;
         setting `autoExpireSeconds=0` in the config disables that timer.
8. **Vanilla server safety** — against a server *without* the mod, `/mg diff` still prints chat and
   sends no overlay packet (no errors client-side).

## Client config

The client reads `<configDir>/minegit-overlay.properties` on init and falls back to the defaults below
for any missing or unparseable key. If the file is absent on first run, a commented template with the
defaults is written for you to edit. There is no config-UI screen.

| Key | Default | Meaning |
|---|---|---|
| `keybind` | `J` | toggle key (GLFW name); also rebindable via vanilla Controls |
| `maxRenderDistance` | `64` | blocks; beyond this, boxes are culled |
| `renderCap` | `4096` | max boxes drawn per frame (nearest first); the rest are counted as `(+J more)` |
| `autoExpireSeconds` | `60` | overlay auto-clear timeout; `0` disables the timer |
| `hudCorner` | `top-left` | HUD anchor (`top-left` / `top-right` / `bottom-left` / `bottom-right`) |

To smoke-test the config wiring quickly: set `renderCap=1` and `autoExpireSeconds=3`, relaunch, run
`/mg diff` over a multi-block change — only the nearest box should draw with `(+J more)` on the HUD, and
the whole overlay should vanish ~3 seconds later.
