# MineGit — Spec 2 / SP2: Client-Side Live Diff (design)

**Date:** 2026-06-07
**Status:** Design, pending review. SP2 of the two-part diff-visualization-parity split (SP1 = transport parity, authored separately).
**Umbrella:** `docs/superpowers/specs/2026-06-03-minegit-architecture-design.md` (§4.3, §7 flow ②, §9 client row)
**Builds on:**
- Diff-overlay frontend — `docs/superpowers/specs/2026-06-05-minegit-client-overlay-design.md` (`minegit:diff` S2C, `minegit:diffsub` C2S, `Frame`/`Framing`/`Reassembler`/`DiffPayload` codec; `OverlayClientState`, `OverlayClientHooks`, the J keybind).
- Transport parity — `docs/superpowers/specs/2026-06-07-transport-parity-design.md` (SP1: the plugin's snapshot-push model and the mod-server permission gate this spec extends to "push on subscribe + HEAD-move").
- Shipped crash-guard `132dd76` — `DiffControlChannel.canSendToServer()`; the keybind already refuses to subscribe where the channel cannot negotiate.

**Sequencing:** SP1 ships first. SP2 then edits `LiveSubscriptionLoop`, `ServerCommandRuntime`, and `MineGitNeoForgeNetworking` — files SP1 also touches — plus the plugin push hooks. Treat SP1 as a hard predecessor (see §5).

---

## 1. Approach & rationale

### The problem this closes

After SP1, a plugin server pushes a working-vs-HEAD diff to a subscribed client **on subscribe and
on HEAD-move**, and the mod server still recomputes the diff **every tick** via `LiveSubscriptionLoop`
(`mod/common/.../net/LiveSubscriptionLoop.java`, `DEFAULT_REFRESH_TICKS = 10`). Two problems remain:

1. **The plugin overlay is not live.** Between HEAD-moves the plugin pushes nothing, so a player
   editing the world sees a frozen snapshot — the boxes do not follow their edits.
2. **The mod server pays a per-tick recompute** (#93/#94/#100) for liveness, while the plugin cannot
   afford the same loop (no per-tick scheduler today; SP1 deliberately avoided one).

The unifying insight is that liveness does not require *server* work at all. The server already sends
the one thing the client cannot derive on its own — the **HEAD baseline** of every changed block,
carried as the `oldState` of each entry in the working-vs-HEAD diff. Given that baseline, the client
can reconstruct true git-HEAD locally and live-diff its own `ClientLevel` against it every tick, on
every connection type, with no further server traffic until HEAD actually moves.

### Baseline reconstruction

For any position, true HEAD is:

```
HEAD[pos] = diff.contains(pos) ? diff.oldState(pos) : clientWorld[pos]   (captured at push time)
```

Dirty positions take their HEAD from the server diff's `oldState`. Clean positions are — by
definition of "clean vs HEAD" at the moment the diff was computed — equal to the current world, so
the live world *at push time* is their HEAD. The client freezes this reconstruction; it must never be
recomputed from the live world afterward, because once the player edits a previously-clean block the
live world no longer reflects HEAD there (see §3, freeze semantics).

### The uniform model (decided)

Both the mod server and the plugin become **snapshot pushers**: they send the working-vs-HEAD diff
only **on subscribe** and **on HEAD-move** (commit/checkout completion). The **client is the sole
live-diff engine** on every connection. This yields a single render path everywhere, retires the
mod server's per-tick recompute and dedupe (closing #93/#94/#100), and keeps the existing
subscription registry and the `DiffOverlaySender` push primitive. An incoming server diff is treated
as **baseline seed/reset data only** — it is never rendered directly. Because the locally-computed
diff at the push instant is identical to the server diff (same baseline, same world), there is no
visual gap in handing rendering over to local computation.

Alternatives considered and rejected: a **dual path** (keep the mod-server loop, client-compute only
on plugin connections) keeps the per-tick cost, forces two render paths and a connection-type branch,
and does not close the loop issues; a **throttled-timer push** with no client compute neither retires
the loop nor solves plugin liveness, since the plugin still cannot recompute per-tick.

---

## 2. Components

All new compute units live in `mod/common` and are designed to be pure and headless-testable — they
take a diff, a block-readback function, and a current-block function, and return overlay state. The
client wiring around them stays thin.

### (a) `HeadBaselineCache` — the frozen HEAD reconstruction

A new mod/common class holding, per loaded-and-seen chunk, the reconstructed HEAD block states for
positions that matter to the overlay. Keyed by `(DimensionId, ChunkPos)`; values are a compact
position→`BlockState` map (section-keyed internally so the dirty tracker and differ can address a
single `(ChunkPos, sectionY)`).

- **Seed a chunk once:** `diff.oldState(pos)` for every dirty position in the chunk, the captured
  `clientWorld[pos]` for the clean positions the differ will compare. Seeding is the only point the
  live world is read into the baseline.
- **Frozen thereafter:** never recomputed from the live world. This is the correctness crux — see §3.
- **Bounded:** an LRU cap over chunk entries bounds memory for a long subscribe-while-roaming
  session. On eviction the chunk is *dropped from the overlay* (its boxes disappear) rather than
  rebuilt from the live world, which would read post-edit state as HEAD. Documented degradation: a
  chunk evicted and later revisited loses live-diff boxes for client edits made to its
  *clean* positions; dirty positions are recoverable from the server diff on re-seed. The cap is set
  generously because overlay subscriptions are expected to be short (toggle J to inspect, not leave on
  while roaming for hours).

### (b) `DirtySectionTracker` — change detection

A new client-side tracker that marks `(ChunkPos, sectionY)` dirty in response to client block-update
notifications. Per-block updates mark the containing section; bulk updates (multi-block change,
section replacement, relight, chunk apply) mark whole sections so no change is missed. This is the
hybrid event-dirty half of the chosen change-detection strategy — events decide *what* to look at,
the rescan decides *what changed*.

### (c) `LiveDiffer` — the budgeted rescan

A new pure function driving the rescan half. Each client tick it pops a budget of dirty sections from
the tracker, and for each compares the current `ClientLevel` block against `HeadBaselineCache` for the
section's positions, producing the overlay box set. The budget bounds per-tick cost the same way the
repo's `TickPump` spreads server-thread world I/O across ticks (see `tick-pump-throttle` memory). With
no dirty sections the differ does nothing. Being a pure compare over two block-readback functions, it
is fully headless-testable.

### (d) `OverlayClientState` — store → compute

Today `OverlayClientState` stores the server-pushed `OverlayState` and the renderer reads it. It flips
to *compute* that state: it owns the `HeadBaselineCache`, the `DirtySectionTracker`, and the
`LiveDiffer`, and the renderer reads the locally-computed box set. Incoming diff frames
(`DiffChannel.setClientHandler` → `acceptFrame`) no longer feed the renderer directly; they feed the
**baseline seed/reset** path (§3). The keybind, `OverlayClientHooks` wiring, and `onClientTick` entry
point are unchanged except that `onClientTick` now also drives the `LiveDiffer` budget.

### (e) Server push side — retire the loop, keep the registry

- **Mod server** (`ServerCommandRuntime`, `LiveSubscriptionLoop`, `DiffOverlaySender`): drop the
  per-tick recompute and the `Map<UUID, WorldDiff>` dedupe. Keep the subscription `Set<UUID>` and the
  `DiffOverlaySender.send(...)` push primitive. Push on **subscribe** (already happens) and add a
  push on **HEAD-move** — wired into the same commit/checkout completion path SP1 uses on the plugin,
  so both servers share the trigger semantics.
- **Plugin** (SP1): unchanged by SP2 — SP1 already pushes on subscribe + HEAD-move. SP2 only relies
  on that contract.

---

## 3. Data flow, lifecycle, and edge cases

**Subscribe and seed ordering.** On SUBSCRIBE the server pushes the full working-vs-HEAD diff, framed;
the client reassembles it (`Reassembler`). Baselines can only be seeded **once the first full diff has
arrived** — freezing a chunk before the diff lands would mis-record a dirty position's HEAD as the
live (already-edited) world. Therefore:

- Chunks already loaded when the diff arrives are seeded on arrival.
- Chunks loaded *after* the diff are seeded on chunk-load, against the held diff.
- The renderer shows nothing until the first diff seeds; this is the only "drop frames until first
  diff" window and it is brief.

(Considered: skipping explicit seed ordering and simply rebuilding lazily. Rejected — without the
"wait for first diff" rule a chunk loaded mid-handshake silently records wrong HEAD for its dirty
positions, which is exactly the class of bug freeze semantics exist to prevent.)

**Steady state.** A block changes → `DirtySectionTracker` marks its section → next tick the
`LiveDiffer` budget re-diffs that section against the frozen baseline → renderer shows the updated
boxes. No server traffic.

**Freeze semantics (the correctness guard).** Once a chunk is seeded, its baseline is immutable until
a reset event. A player editing a previously-clean block makes the live world diverge from HEAD at
that position; because the baseline still holds the pre-edit (true HEAD) state, the differ correctly
raises a new box. Rebuilding the baseline from the live world here would read the post-edit state as
HEAD and the box would never appear — the rejected "rebuild-on-load" model. This invariant gets a
direct headless test (§4).

**HEAD-move (commit / checkout).** The server pushes a fresh diff. The client **drops all baselines
for that dimension and re-seeds** from the new diff plus the then-current world. Boxes that were
working changes now folded into HEAD disappear naturally; on checkout, the server also streams the
checked-out block changes to the client world, and the re-seeded baseline reflects the new HEAD. The
trigger is the same commit/checkout completion hook SP1 introduced — reused, not re-invented.

**Dimension change.** Baselines are keyed per-dimension. Entering a dimension uses that dimension's
pushed diff to seed; leaving does not discard the prior dimension's baselines unless evicted by the
LRU cap or a HEAD-move.

**Disconnect / unsubscribe.** Drop the cache, the tracker, and the overlay entirely.

**Memory.** Bounded by the LRU cap in (a). The expected short-lived-subscription usage keeps the
working set small; the cap is the backstop for the roam-while-subscribed tail, with the documented
clean-position degradation on evict-then-revisit.

---

## 4. Testing

**Headless (mod/common, pure — the bulk of coverage):**

- **Baseline seed correctness:** given a diff and a stub world, a seeded chunk returns
  `diff.oldState(pos)` for dirty positions and the captured world state for clean positions.
- **Freeze-survives-edit (the §3 guard):** seed a chunk, mutate the stub world at a clean position,
  re-run the differ → a new box appears; assert the baseline value did **not** change. This is the
  regression test for the rejected rebuild-on-load bug.
- **LiveDiffer compare:** pure diff of current-vs-baseline over a section yields exactly the changed
  positions; an unchanged section yields no boxes; a reverted edit clears its box.
- **Seed ordering:** a chunk "loaded" before the diff arrives is seeded correctly on diff arrival
  (its dirty positions read `oldState`, not the pre-diff live world).
- **HEAD-move reset:** pushing a second diff drops prior baselines and re-seeds; stale boxes clear and
  the new diff's boxes appear.

**Mod-common unit (existing seams):**

- The retired-loop path: subscribing pushes once and does **not** schedule a per-tick recompute (guard
  against accidentally keeping the loop).

**Manual integration (user-side, both loaders + a Paper plugin server, post-SP1):**

- Edit a block → box appears live (no commit needed).
- Commit → the committed boxes clear.
- Checkout → boxes swap to the new HEAD's working set.
- Walk away from an edited chunk and return (within the cache cap) → its box persists.
- Confirm parity: the same sequence behaves identically against a mod server and a plugin server.

---

## 5. Sequencing & dependencies

SP1 is a hard predecessor: it establishes the plugin transport and the "push on subscribe + HEAD-move"
contract that SP2's uniform model assumes on both servers. SP2 then modifies files SP1 also touches —
`LiveSubscriptionLoop` and `ServerCommandRuntime` (retire the per-tick recompute, add the HEAD-move
push) and `MineGitNeoForgeNetworking` (no functional change expected; listed because both specs name
it). Land SP1 first; do not begin SP2 implementation until SP1's transport spike has confirmed the
wire works against Paper.

## 6. Open risks

- **LRU cap tuning** — the cap value and the accepted clean-position degradation on evict-then-revisit
  should be confirmed against a realistic render distance during implementation; if subscriptions turn
  out to be long-lived in practice, revisit whether persistence needs a smarter eviction than LRU.
- **Bulk-update coverage** — the `DirtySectionTracker` must catch every client-side path that mutates
  blocks without a per-block event (section replacement, relight, chunk (re)apply). Enumerate these
  against the 1.21.11 client during the spike; a missed path shows as a stale box.
- **Checkout world stream vs re-seed ordering** — on checkout the client receives both a fresh diff
  and the checked-out block updates; confirm the re-seed observes the post-checkout world (seed after
  the world updates land, or the baseline records pre-checkout state).
