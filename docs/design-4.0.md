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

- [x] **Task 4.0.2 — `forge-instance` module: instance model + registry store.**
  New module `forge-instance` (dependsOn `forge-core` for `ForgePaths`/ids).
  `Instance(name, dir)`, a `RepoRegistry` (registered repo paths, validated to
  be git repos), and `InstanceStore` reading/writing
  `~/.forge/instances/<name>/config.json` + the registry. On-disk layout:
  `instances/<name>/{config.json, repos.json, workers/}`. Typed errors
  (no-such-instance, repo-not-found, duplicate-repo). Unit tests against a
  tmp-dir `home`.

- [x] **Task 4.0.3 — CLI: `init-instance` / `add-repo` / `list-repos`.** Three
  new commands in the `forge-app` command layer + `CliParser`. `init-instance`
  creates the instance dir + empty registry; `add-repo` validates the path is a
  git working tree and registers it; `list-repos` prints the registry. These are
  instance-level (not per-feature, not per-repo `.forge`), so they do **not**
  acquire the per-checkout `ProcessLock`; they take an instance-level lock under
  `instances/<name>/` instead (generalising `ProcessLock` minimally — full
  instance-lock ownership is the daemon's in 4.1).

- [x] **Task 4.0.4 — orchestrator path wiring.** Resolve `localRoot` from the
  instance for state-changing feature commands: when `--instance <name>` is
  supplied, `Main` builds `ForgePaths(repoRoot,
  localRoot = instanceDir/workers/<feature>)`. The orchestrator and all
  `paths.xxx` consumers are unchanged (B1's promise). `refresh-cache` (state-
  changing) and the read-only `tail`/`rebuild-state`/`stats`/`tui` resolve the
  same re-rooted local paths. **Re-root is driven only by an explicit
  `--instance`** — the sole-instance auto-default the plan floated ("or a
  resolved default instance") was dropped (user decision 2026-06-05): a no-flag
  feature command never reads the instance registry, keeping the "byte-identical
  to v1 when no instance is in play" exit-criterion guarantee literal and the
  v1 path free of any `~/.forge` coupling.

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
- **2026-06-05** — **Task 4.0.2 (instance model + registry store) landed.** New
  `forge-instance` module (dependsOn `forge-core`): `Instance(name, dir)` runtime
  handle deriving the `config.json` / `repos.json` / `workers/<feature>/` leaves;
  persisted `InstanceConfig` + `RepoRegistry`/`RegisteredRepo` (upickle, schema
  v1); `trait InstanceStore` + `FileInstanceStore(home)` with `create` /
  `load` / `list` / `addRepo` / `listRepos`; typed `InstanceError`
  (`NoSuchInstance` / `DuplicateInstance` / `RepoNotFound` / `NotAGitRepo` /
  `DuplicateRepo` / `Malformed` / `IoFailure`). `InstanceName` opaque id added to
  `forge-core` (Ids + Json codec); the `~/.forge/instances/` anchor lives on a new
  `ForgePaths` companion (`instancesRoot` / `instanceDir`) so the no-`.forge`-literal
  smell sweep still covers it. Registry persisted with plain `os.write.over` (no
  daemon yet — carry-forward §4 records this as a temporary 4.0 simplification).
  12 `FileInstanceStoreSuite` tests over a tmp `home` + `InstanceName` cases in
  `IdsSuite`; full `sbt test` green (forge-instance 12, forge-core 468), smell
  sweep passes. Tasks 4.0.3 (CLI), 4.0.4 (wiring), 4.0.5 (live re-run) open.
- **2026-06-05** — **Task 4.0.3 (CLI: `init-instance` / `add-repo` /
  `list-repos`) landed.** `forge-app` now `dependsOn forge-instance`. New
  `CommandClass.Instance` + `InstanceCommand` ADT (`InitInstance` /
  `AddRepo` / `ListRepos`) parsed by `CliParser.parseInstance` (third parse
  entry point alongside `phase2`; instance commands are CLI-layer-only, so they
  get their own result type rather than being shoehorned into the
  branch-preflight `ForgeCommand`). `Main` routes `CommandClass.Instance` to a
  new `runInstance` that loads **no** config / assets / per-checkout lock — only
  `paths.home` matters. The `io.forge.app.command.InstanceCommands` handlers:
  `init-instance` creates the instance, `add-repo` resolves + registers a git
  working tree, `list-repos` prints the registry; `add-repo` / `list-repos`
  resolve their target via an optional `--instance <name>` flag, falling back to
  the **sole** instance when exactly one exists (zero/many with no flag → a
  guided exit-1). `FileProcessLock`'s primary constructor was generalised to take
  the lock + metadata paths directly (per-checkout `ForgePaths` form kept as a
  convenience constructor), so the same primitive backs the new **instance-level**
  lock at `instances/<name>/.lock` (`Instance.lockFile` / `lockMetadataFile`);
  the mutating commands serialize on it with `acceptStale = true`. To let the
  lock lay down the empty instance dir before the registry files,
  `InstanceStore.create`'s duplicate guard moved from the directory to
  `config.json` (the same marker `load` uses). 7 new `CliParserSuite` cases + 11
  `InstanceCommandsSuite` cases over a tmp `home`; full `sbt test` green
  (forge-app 465, forge-instance 12), `scalafmtCheckAll` clean, smell sweep
  passes. Tasks 4.0.4 (orchestrator path wiring), 4.0.5 (live re-run) open.
- **2026-06-05** — **Task 4.0.4 (orchestrator path wiring) landed.** The
  existing per-command `--instance <name>` extraction (`CliParser`'s
  `extractInstanceFlag`, made public + renamed `extractInstance`) is now reused
  on the **feature** command path: `Main` strips `--instance` from a
  state-changing / read-only command's `rest` (so phase-2 / read-only-handler
  feature parsing sees a clean positional list) and feeds the parsed name to the
  new `io.forge.app.command.InstancePaths.resolve`. That resolver re-roots
  `ForgePaths(repoRoot, localRootOpt = Some(<instance>/workers/<feature>))` when
  (and only when) an explicit `--instance` + a feature are present — the lock is
  taken on the re-rooted paths (per-worker, not per-checkout) and the same
  re-rooted `paths` flows into `StateChangingContext`/`ReadOnlyContext`, so every
  `paths.xxx` consumer moves with it (B1's promise — a constructor swap, no
  callsite sweep). A re-root prints a one-line stderr note (where log/state/lock
  landed); an explicit `--instance` naming a missing instance is `NoSuchInstance`
  → exit 1; a feature-less command (`profile`) or a no-flag command is never
  re-rooted and **never reads the registry** (v1 byte-identical). Per the user
  decision (see Task 4.0.4), the sole-instance auto-default was **not** built.
  `runStateChangingWith` was split (lock bracket extracted to
  `stateChangingUnderLock`). 5 new `InstancePathsSuite` cases over a tmp `home`
  (v1 no-touch, re-root split, NoSuchInstance) + 3 `CliParserSuite`
  `extractInstance` cases + 1 `MainSuite` exit-1 case; full `sbt test` green
  (forge-app 473, forge-instance 12, forge-core 468), `scalafmtCheckAll` clean,
  smell sweep passes. Task 4.0.5 (live dogfood re-run + close-out) is the last
  open 4.0 task.

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
