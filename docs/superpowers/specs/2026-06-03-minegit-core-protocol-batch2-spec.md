# Spec A, Batch 2 — Finishing MineGit `core` + `protocol`

**Date:** 2026-06-03
**Status:** Approved. Implementation fanned out via the Harness fleet (PRD batch 2).
**Parent:** [MineGit umbrella architecture](2026-06-03-minegit-architecture-design.md)
**Builds on:** [Spec A batch 1](2026-06-03-minegit-core-protocol-spec.md) (already merged to `master`)

Batch 2 completes the platform-agnostic engine: the remote half of git, branching/checkout, the legacy
block-mapper framework, the `DiffPayload` wire codec, and deterministic `.mgc` compression. Like batch 1,
**everything is buildable/testable without Minecraft or a real GitHub** — remote operations are verified
against a **local bare repo** (`git init --bare`, `file://`).

---

## 1. Scope

### In scope
- Remaining `MineGitRepo` API: `branch`, `branches`, `checkout`, `remoteSet`, `fetch`, `push`, `pull`, `clone`.
- `Credential` abstraction + JGit transport (token / ssh / default).
- `BlockMapper` framework + curated common legacy↔flattening table + unknown-id fallback.
- `DiffPayload` wire codec (`protocol`): palette-compressed encode/decode + chunked framing + reassembly.
- Deterministic DEFLATE compression for `.mgc`.
- CLI additions for all of the above.

### Out of scope (deferred)
- **MR-JAR / modern-JGit overrides** — separate hardening pass; batch 2 stays on JGit 5.13.x.
- **Full ~4000-entry mapping table** — incremental data-fill task after the framework lands.
- **`merge`** — still deferred (architecture leaves room).
- **In-game frontends** — Spec B (plugin), Spec C (client), Spec D (server-mod).
- **Live hot-swap chunk resend / relight** — frontend concern; `core.checkout` only computes + applies the
  delta via `WorldAdapter` and returns it.

---

## 2. Remaining `MineGitRepo` API (`core.api` / `core.git`)

```java
BranchRef        branch(String name);                 // create branch at HEAD
List<BranchRef>  branches();                           // local + remote-tracking (origin/*)
WorldDiff        checkout(Ref target);                 // dirty-guard → apply delta → move HEAD; returns applied delta
WorldDiff        checkout(Ref target, boolean force);  // force bypasses the dirty-guard

void             remoteSet(String url);                // configure 'origin'
void             fetch(Credential cred);               // update origin/* refs only; no world change
PushResult       push(Credential cred);                // push current branch to origin
WorldDiff        pull(Credential cred);                // fetch + apply target onto world; returns applied delta
static MineGitRepo clone(String url, Path dir, Credential cred, WorldAdapter world); // fetch + materialize world
```

- **Dirty-guard:** `checkout`/`pull` throw `WorkingTreeDirtyException` if `diff()` (live vs HEAD) is
  non-empty, unless `force`. Mirrors git's refusal to clobber uncommitted work.
- **Apply path:** `checkout`/`pull` compute `HEAD → target` as `BlockChange[]` (reusing `WorldDiffer`),
  call `WorldAdapter.apply(dim, pos, changes)` per chunk, then move the local ref. They **return** the
  `WorldDiff` applied so a future frontend can resend affected chunks.
- `branches()` lists local refs and remote-tracking refs distinctly.
- `PushResult` reports per-ref status (ok / rejected / up-to-date).

---

## 3. Transport & Credential (`core.git`)

```java
interface Credential { /* configures a JGit TransportConfigCallback / CredentialsProvider */ }
final class TokenCredential   implements Credential { /* user "x-access-token", token = PAT */ }
final class SshCredential     implements Credential { /* private-key path (+ optional passphrase) */ }
final class DefaultGitCredential implements Credential { /* rely on git/netrc config */ }
```

- `TokenCredential` → JGit `UsernamePasswordCredentialsProvider("x-access-token", token)` over HTTPS.
- `SshCredential` → JGit ssh session factory bound to the given identity.
- Core only consumes a `Credential`; the **server-side operator credential** is loaded from plugin config
  later (umbrella §8). No secret ever lives in core.
- **CI strategy:** `push`/`fetch`/`clone` tests run against a **local bare repo** (`file://`) — no network,
  no auth — exercising the JGit transport + ref-update logic. Token/SSH provider *construction* is
  unit-tested; a real GitHub round-trip is a manual check.

---

## 4. `BlockMapper` (`core.mapping`)

Core owns the **numeric→flattened** mapping only (pure ints — no Bukkit types):

```java
final class LegacyBlockMapper {
  BlockState map(int blockId, int meta);   // 1.8 numeric id+meta → flattened BlockState
}
```

- **Data-driven:** loaded from a bundled resource table (`legacy-blocks.*`) covering **common blocks**.
- **Unknown id+meta → fallback** `minegit:unknown` carrying props `{legacy_id, legacy_meta}` — flagged and
  not silently lost.
- The future `v1_8` plugin module does Bukkit `MaterialData` → `(id, meta)`, then calls this mapper. Modern
  (1.13+) servers already produce flattened ids, so they need no legacy mapping.
- **Tests:** common blocks map correctly; unknown → fallback; table parses; (where reversible) round-trip.
- Full ~4000-entry coverage is an incremental data-fill follow-up, not part of this batch.

---

## 5. `DiffPayload` Wire Codec (`protocol`)

```java
byte[]     encode(WorldDiff diff, String fromRef, String toRef);   // single buffer
List<Frame> frame(byte[] payload, int maxFrameBytes);              // chunked
WorldDiff  decode(byte[] payload);
Reassembler { add(Frame) -> Optional<byte[]> whenComplete }
```

- **Palette compression:** collect distinct `BlockState`s across the payload into a palette; each
  `BlockChange` references palette indices (compact, avoids repeating id strings).
- **Encoding:** VarInt-framed; per dimension → per chunk (`cx,cz`) → change count → each change
  `{packedLocalPos, kind, oldIdx, newIdx}`.
- **Framing:** split at a configurable cap (`~30 KB`, legacy-safe). Each `Frame` header =
  `{sessionId, seq, total}`; `Reassembler` collects frames and yields the full payload when complete.
- **Channel constant:** `minegit:diff`.
- **Tests (no Minecraft):** `encode`→`decode` round-trip equals the input `WorldDiff`; framing +
  reassembly with a tiny `maxFrameBytes`; palette correctness; out-of-order frame reassembly.

---

## 6. Deterministic `.mgc` Compression (`core.format`)

- Wrap chunk bytes in **raw DEFLATE** (`Deflater` with `nowrap=true` → no gzip header/timestamp → output
  stays byte-deterministic).
- **Bump the `.mgc` format version**; the reader auto-detects and handles both legacy (uncompressed,
  batch 1) and new (compressed) chunks.
- **Invariant preserved:** equal chunks still serialize byte-identical (`serialize(x) == serialize(x)`),
  so git dedup continues to work. Tested explicitly.

---

## 7. CLI Additions (`minegit-cli`)

| Command | Action |
|---|---|
| `minegit branch [name]` | create at HEAD / list local + remote |
| `minegit checkout <ref> [--force]` | revert the fake world to `<ref>` (apply delta) |
| `minegit remote set <url>` | configure origin |
| `minegit fetch` / `push` / `pull` | sync with origin (tested against a `file://` bare repo) |
| `minegit clone <url> <dir>` | materialize a world from a remote repo |

Credential for the CLI defaults to `DefaultGitCredential` (so `file://` bare-repo tests need no auth).

---

## 8. PRD Batch 2 — Issue Breakdown (~9 slices)

Each independently grabbable; dependencies expressed via `## Blocked by`.

1. **Credential + JGit transport** (token/ssh/default) + local-bare-repo test harness.
2. **`branch` + `branches()`** + CLI `branch`.
3. **`checkout`** (delta compute, dirty-guard, apply, move HEAD) + CLI + tests. *(blocked by #2)*
4. **`remoteSet` + `fetch` + `push`** against a local bare repo + CLI + tests. *(blocked by #1)*
5. **`pull` + `clone`** (materialize via `writeChunk`) + CLI + tests. *(blocked by #3, #4)*
6. **`BlockMapper` framework + common table + unknown fallback** + tests.
7. **`DiffPayload` encode/decode** (palette-compressed) + tests.
8. **`DiffPayload` chunked framing + reassembly + `minegit:diff` constant** + tests. *(blocked by #7)*
9. **Deterministic DEFLATE `.mgc` compression** + format-version handling + tests.

## 9. Acceptance for Batch 2

`minegit-cli` can `branch` and `checkout` (revert the world to a prior commit) and
`push`/`fetch`/`pull`/`clone` against a local bare repo; `LegacyBlockMapper` maps common legacy blocks with
the unknown-id fallback; `DiffPayload` round-trips and reassembles from chunks; `.mgc` compression is
deterministic and back-compatible — **all green in CI, zero Minecraft dependencies.**

After batch 2, `core` + `protocol` are feature-complete for the engine, and the next spec is **B — the
Spigot plugin** (first in-game frontend).
