# design-4.0 — Slice 4.0 implementation plan (Instance scope + path re-root, B1)

> **Maps to:** [`roadmap.md`](roadmap.md) §5 (Phase 4 — Workspace & Workstream
> platform), §5.1 (Forge instance); the ratified Phase-4 architecture contract
> [`forge-design-2.0.md`](forge-design-2.0.md) §3.1 **B1** (path topology), §4
> (Forge instance + path topology), §4.3 (committed-vs-local split), and §11
> sub-slice **4.0**.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but **not** during a review
> round; tick the roadmap §5 bullet only at slice close after a whole-section
> review.
>
> **Scope note.** Slice 4.0 is deliberately the *thinnest* Phase-4 increment:
> instance scope + the B1 path re-root, **no daemon, no containers, no
> workstream/worker model** (those are 4.1–4.4). The point is to land the one
> seam that every later slice sits on (B1) in a way that is *byte-identical to v1
> when no instance is in play*, and to give the developer a named, file-backed
> "instance" to register repos against. Per the contract's spike line for 4.0:
> *"re-root `ForgePaths` and re-run a prior dogfood feature green."*
>
> **Status:** 🚧 open — 2026-06-05. Task 4.0.1 (the B1 spike) landed first per
> CLAUDE.md "run code earlier"; the remaining tasks (instance model, CLI,
> orchestrator wiring, live dogfood re-run) follow.

---

## 0. Exit criterion for Slice 4.0

Slice 4.0 is done when **a prior dogfood feature runs green with its local
runtime (`.forge/log/`, `.forge/state/`, the lock) re-rooted out of the repo
checkout and under an instance directory, while its committed `.forge/specs/`
family stays in the repo and merges via PR exactly as in v1** — and the v1
single-repo path (no instance) is provably unchanged.

Concretely:

1. **B1 re-root (Task 4.0.1).** `ForgePaths` grows an optional `localRoot`
   parameter. The committed family (`specs/`, `config.json`, `profile.json`,
   `overrides/`, `prices.json`) stays anchored at `repoRoot/.forge`; the
   local-runtime family (`log/<feature>.jsonl`, `state/`, the lock + its
   metadata) re-roots under `localRoot/.forge`. Default `localRoot = repoRoot`
   ⇒ **v1 behaviour is byte-identical** (the entire existing suite stays green
   untouched). This is the riskiest cross-cutting contract, proven cheaply and
   first.
2. **Instance model + store (Task 4.0.2).** A `forge-instance` module with the
   on-disk layout `~/.forge/instances/<name>/` (`config.json`, a repo registry,
   `workers/<feature>/` as the local-runtime re-root target) and a small,
   typed reader/writer. (The append-only *instance action log* + rebuildable
   cache — contract §6.4 / O8 — is **4.1**'s durability core; 4.0 uses a plain
   registry file, since there is no daemon yet to crash.)
3. **CLI (Task 4.0.3).** `forge init-instance <name>`, `forge add-repo <path>`,
   `forge list-repos` — wired into `Main`'s command set with the existing
   two-phase boot.
4. **Orchestrator wiring (Task 4.0.4).** When a command runs against a
   registered instance, `Main` constructs `ForgePaths(repoRoot, localRoot =
   <instance>/workers/<feature>)` so the orchestrator's log/state/lock land under
   the instance dir; absent an instance, `localRoot` defaults to `repoRoot` and
   nothing changes.
5. **Live dogfood re-run (Task 4.0.5, exit + close-out).** Re-run a prior
   dogfood feature under an instance, reach the same terminal state, and confirm
   the committed specs landed in the repo while log/state/lock landed under the
   instance dir. Close-out: whole-section review, carry-forward walk, flip the
   roadmap §5 4.0 bullet.

---

## 1. What stays frozen

Everything behind the B1 seam (contract §9). In particular: `Fsm.transition`,
the action-log **format** + `foldEvents`/replay/`RebuildState` (only the log's
*location* moves, not its bytes or semantics), the branch/PR/CI/merge gates, the
connectors + reviewer + Phase-3 senses, and the per-session budget checks. Slice
4.0 introduces **no** daemon, container, workstream, or worker process —
"worker" here is only a *directory* (the local-runtime re-root target), not yet
a daemon-spawned process (that is B4 / 4.1+).

---

## 2. Task breakdown

- [x] **Task 4.0.1 — re-root `ForgePaths` (the B1 spike).** Optional `localRoot`
  parameter (default `repoRoot`). Local-runtime paths (`featureLog`,
  `stateFile`, `pollBaselineFile`, `lockFile`, `lockMetadataFile`) derive from a
  new `localForgeDir = localRoot / ".forge"`; the committed family is untouched
  (still `repoForgeDir`). `ForgePathsSuite`: the "strictly under
  `repoRoot/.forge`" invariant splits into a **committed** group (still under
  `repoRoot/.forge`) and a **local-runtime** group (under `localRoot/.forge`
  when re-rooted), plus a default-`localRoot == repoRoot` equivalence test
  proving the v1 layout is unchanged. `forge-core` green; the `os.walk` smell
  sweep still passes (no new `.forge` literal). *Landed first — it is the
  contract every later task sits on, and it is backward-compatible.*

- [ ] **Task 4.0.2 — `forge-instance` module: instance model + registry store.**
  New module `forge-instance` (dependsOn `forge-core` for `ForgePaths`/ids).
  `Instance(name, dir)`, a `RepoRegistry` (registered repo paths, validated to
  be git repos), and `InstanceStore` reading/writing
  `~/.forge/instances/<name>/config.json` + the registry. On-disk layout:
  `instances/<name>/{config.json, repos.json, workers/}`. Typed errors
  (no-such-instance, repo-not-found, duplicate-repo). Unit tests against a
  tmp-dir `home`.

- [ ] **Task 4.0.3 — CLI: `init-instance` / `add-repo` / `list-repos`.** Three
  new commands in the `forge-app` command layer + `CliParser`. `init-instance`
  creates the instance dir + empty registry; `add-repo` validates the path is a
  git working tree and registers it; `list-repos` prints the registry. These are
  instance-level (not per-feature, not per-repo `.forge`), so they do **not**
  acquire the per-checkout `ProcessLock`; they take an instance-level lock under
  `instances/<name>/` instead (generalising `ProcessLock` minimally — full
  instance-lock ownership is the daemon's in 4.1).

- [ ] **Task 4.0.4 — orchestrator path wiring.** Resolve `localRoot` from the
  instance for state-changing feature commands: when `--instance <name>` (or a
  resolved default instance) is supplied, `Main` builds `ForgePaths(repoRoot,
  localRoot = instanceDir/workers/<feature>)`. The orchestrator and all
  `paths.xxx` consumers are unchanged (B1's promise). Ensure
  `init`/`refresh-cache`/`rebuild-state` etc. resolve the same re-rooted local
  paths.

- [ ] **Task 4.0.5 — live dogfood re-run + close-out (exit criterion).** Drive a
  prior dogfood feature under an instance to its terminal state; confirm the
  committed/local split on disk; whole-section review; carry-forward walk; flip
  the roadmap §5 sub-slice 4.0 bullet.

---

## 3. Status log

- **2026-06-05** — plan opened. **Task 4.0.1 (B1 re-root) landed first** per
  CLAUDE.md "run code earlier": `ForgePaths` gained the `localRoot` parameter,
  local-runtime paths re-root under `localForgeDir`, committed family unchanged;
  `ForgePathsSuite` split into committed-vs-local groups + a default-equivalence
  test; `forge-core` green and the smell sweep still passes. Remaining tasks
  (4.0.2 instance model, 4.0.3 CLI, 4.0.4 wiring, 4.0.5 live re-run) open.

---

## 4. Carry-forward / deferred

- **Instance durability core (contract §6.4 / O8).** 4.0's plain registry file
  is replaced by the append-only instance action log + rebuildable cache in
  **4.1**, when a daemon exists that can crash. Recorded here so the registry's
  mutable-file shape is understood as a *temporary* 4.0 simplification, not the
  ratified instance source of truth.
- **Worker = directory, not process.** 4.0's `workers/<feature>/` is only the
  B1 re-root target. The daemon-spawned worker *process* in a container (B4) is
  4.1+/4.3. The directory name is chosen now so the path layout doesn't churn
  when the process arrives.
- **Instance lock vs per-checkout lock.** 4.0 takes a minimal instance-level
  lock for the registry commands; the full instance-lock ownership (held by the
  long-running daemon, with per-worker locks below it — contract §6.1) is 4.1.
