# design-4.2 — Slice 4.2 implementation plan (Workstream/worker model + isolated clones + multiplexed supervision)

> **Maps to:** [`roadmap.md`](roadmap.md) §5 (Phase 4 — Workspace & Workstream
> platform); the ratified Phase-4 architecture contract
> [`forge-design-2.0.md`](forge-design-2.0.md) §5 (workstream + worker model + the
> `attention` projection), §6.1/§6.2 (supervisor + cadence), §6.3.1 (single-writer
> control serialization), §6.4 (durability/recovery — reconcile live processes on
> restart, reattach to feeds), §7 (containerised execution — the **process**
> topology B4, *minus* containers, which stay in 4.3), §10 **O10** (clone
> strategy: a fresh isolated working clone per worker), and §11 sub-slice **4.2**.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but **not** during a review
> round; tick the roadmap §5 sub-slice 4.2 bullet only at slice close after a
> whole-section review.
>
> **Scope note.** Slice 4.1 proved the daemon can hold the instance lock, serve a
> status snapshot, accept a worker's exported events, and rebuild its whole view
> from the instance log after a crash — but a "worker" there was a *record + an
> exported event feed* (driven by a test harness / an external Python client over
> the socket). Slice 4.2 makes the worker a **real OS process the daemon spawns
> and supervises**, each on its **own isolated full clone** of a registered repo
> (host processes — **not** containers, which are 4.3; **not** shared checkouts —
> the isolation premise holds pre-container), running the frozen v1 feature loop
> and exporting its events back to the daemon (B3) over the socket. It adds the
> **workstream** coordination object (§5) and **multiplexed supervision +
> cadence** across M workers (§6.2), and proves the §6.4 **process reconciliation**
> on restart (the daemon's children survive its crash; on restart it re-discovers
> which are still alive and reattaches). It deliberately stops short of
> **containers** (4.3), the **B2 budget reservation protocol** (4.3), the
> **credential broker** (O6 / 4.3), and the **cockpit TUI** (4.4).
>
> **Status:** _open (2026-06-06)._ Task 4.2.1 (the worker-process spike) landing
> first per CLAUDE.md "run code earlier": the riskiest *new* contract in 4.2 is
> the **worker-as-real-OS-process boundary** (B4) — supervision, crash-survival,
> and reattachment all sit on it, where the clone and the v1 loop are mechanical —
> so a runnable spawn↔phone-home spike goes in front of the workstream model and
> the supervisor. Tasks 4.2.2–4.2.6 open.

---

## 0. Exit criterion for Slice 4.2

Slice 4.2 is done when **`forge daemon` spawns a real worker *process* on its own
isolated clone of a registered repo, supervises it (tracks liveness + status +
exit), aggregates its exported event feed (B3), schedules its poll cadence as
part of a workstream, and — after the daemon is killed and restarted —
reconciles which worker processes are still running and reattaches to their feeds
(§6.4), without the worker having been interrupted.**

Concretely, mirroring the contract's spike line for 4.2 (*"M concurrent
single-repo workers each on its own isolated full clone (host processes), daemon
supervision + cadence"*):

1. **Worker process boundary (Task 4.2.1).** The daemon can spawn a real OS child
   process (a `WorkerSpawner` over `os.proc`), and a `forge worker` process can
   connect back to the instance socket, register itself, and export its
   lifecycle — the two halves of the B4 boundary, each runnable, landed first.
2. **Isolated clone provisioning (Task 4.2.2, O10).** A fresh isolated *working*
   clone per worker under `workers/<worker>/checkout/`, with the v1 `ForgePaths`
   re-rooted so the worker's log/state/lock live under `workers/<worker>/`
   (outside the clone) while committed `.forge/specs/` stay in the clone (§4.3 /
   B1). A local bare/reference mirror cache is permitted for fetch cost but is
   **never** a mutable working tree (O10).
3. **Worker runs the frozen v1 loop (Task 4.2.3).** The `forge worker` entrypoint
   builds + runs the real `Orchestrator` over the clone's `ForgePaths`, exporting
   each appended action as a `worker.event` and its FSM-state name as
   `worker.status` (B3), via the worker→daemon path.
4. **Workstream model (Task 4.2.4).** The `Workstream` coordination object (goal,
   `Planning → Active → Done/Abandoned`, ordering over workers, the derived
   `attention` projection — §5), the new instance-log events (`workstream.*`,
   `worker.spawned`/`worker.exited`), and the `forge workstream` / `forge worker
   list` CLI surface.
5. **Multiplexed supervision + cadence (Task 4.2.5).** The daemon spawns workers
   on workstream activation, detects exit, surfaces status, and schedules/throttles
   poll cadence per workstream (§6.2); on restart it reconciles live child
   processes and reattaches.
6. **Reconciliation proof + close-out (Task 4.2.6, exit).** A test + a live
   `forge daemon` exercise that spawns a worker process on an isolated clone,
   kills + restarts the daemon, and asserts the worker survived and was
   reattached. Reconcile spec deltas (§23). Close-out: whole-section review,
   carry-forward walk, flip the roadmap §5 sub-slice 4.2 bullet.

---

## 1. What stays frozen

Everything behind the worker boundary (contract §9), consumed by the worker
process as a library unchanged: `Fsm.transition`, the per-feature action log
format + `foldEvents`/replay/`RebuildState`/restart recovery, the branch/PR/CI/
merge gates, the connectors + reviewer + Phase-3 senses, the per-session budget
checks, **the FSM-driving poll loop (stays in the worker, B4)**. The **instance**
log + `DaemonState` single-writer durability core (Slice 4.1) is reused as-is;
4.2 only *adds* event variants to the `sealed InstanceEvent` (the open-`kind`
record shape already tolerates them). The B1 path re-root landed in Slice 4.0
(instance-level) and is extended per-worker here — a constructor change at the
`ForgePaths` callsite, enforced clean by `ForgePathsSuite`'s sweep. Slice 4.2
introduces **no** container, **no** B2 budget reservation protocol, and **no**
credential broker (all 4.3).

---

## 2. Task breakdown

- [x] **Task 4.2.1 — worker-process boundary spike (B4: the daemon spawns a real
  OS process; a `forge worker` process phones home).** The riskiest *new*
  contract, landed first and runnable. Two halves, each proven with **real** I/O
  (not mocks):
  - **`WorkerSpawner` (forge-daemon)** — `WorkerSpec{command, cwd, env}` +
    `WorkerHandle{pid, awaitExit, kill}` + a `WorkerSpawner` trait whose
    `RealWorkerSpawner` spawns an OS child via `os.proc(...).spawn()` wrapped in a
    `Resource[IO, WorkerHandle]` (destroy-on-release; `awaitExit` an
    interruptible blocking `waitFor`). This is the generic spawn *mechanism*; the
    forge-specific `WorkerSpec` (resolving the `forge`/`java -jar` launcher) is
    the supervisor's, built in 4.2.5. Unit test spawns a **real** trivial child
    (`sh -c 'exit N'` → pid > 0, `awaitExit == N`; a long sleeper → `kill` → it
    terminates) — a real OS-process lifecycle, fast, no classpath fragility.
  - **`forge worker` entrypoint (forge-app)** — a new hidden `worker` command
    (`CommandClass.Worker`, parsed into `WorkerCommand{instance, workerId, repo,
    feature}`) that connects to the instance socket via `DaemonClient`, calls
    `register-worker`, emits a `worker-status` + a couple of synthetic
    `worker-event`s, then exits 0 (the **real** `Orchestrator` body is 4.2.3).
    Unit test boots an in-process `DaemonState` + serves the socket, runs the
    entrypoint pointed at it, and asserts the daemon `status` reflects the
    registration + status + `eventCount` (a real socket, in-process daemon — the
    `DaemonWorkerSubscribeSuite` idiom).

  The full tie-together (the daemon spawns the **real** `forge worker` child over
  a real socket) is proven **live** in the Task 4.2.6 close-out dogfood — the same
  unit-the-halves + live-prove-the-real-process discipline Slice 4.1 used (its
  Python-client stand-in becomes a real `forge worker` child here).

- [ ] **Task 4.2.2 — isolated clone provisioning (O10) + per-worker `ForgePaths`
  re-root (B1).** A `WorkerCheckout` / clone provisioner: given a `RegisteredRepo`
  source path + a worker dir, produce a fresh isolated **working** clone at
  `Instance.workerCheckout(worker)` (`workers/<worker>/checkout/`) and a
  `ForgePaths(repoRoot = checkout, localRootOpt = Some(workers/<worker>/))` so the
  worker's log/state/lock land under the worker dir (outside the clone) while
  committed specs stay in the clone (§4.3). Reuse `GitClient` for the clone
  (`git clone <source> <checkout>`); an optional local bare/reference mirror is a
  fetch-cost optimisation, **never** a mutable working tree (O10) — deferred /
  minimal for 4.2. Proven against a throwaway local git repo (no network); smell
  sweep stays clean.

- [ ] **Task 4.2.3 — worker runs the frozen v1 feature loop + exports its feed
  (B3).** Wire the `forge worker` entrypoint to `OrchestratorBuilder.build` over
  the 4.2.2 clone `ForgePaths` and `orch.run(featureId)`, with a feed exporter
  that tails the worker's own per-feature action log and pushes each new `Action`
  to the daemon as a `worker.event`, plus a `worker.status` on each FSM-state
  change. (The worker is the **sole writer** of its feature log, §6.3.1; the
  export is a read-side tail, not a second writer.) The daemon assigns the worker
  its clone + feature at spawn (4.2.5).

- [ ] **Task 4.2.4 — workstream model + instance-log events + CLI.** The
  `Workstream` coordination object (§5): `id`, `goal`, scalar lifecycle
  `Planning → Active → Done/Abandoned`, an **ordering** over its workers, and a
  derived `attention` projection (which workers need a human + why — NHI / a
  driver question / a merge gate), computed over the workers, **never** a
  lifecycle state. New `InstanceEvent` variants (`workstream.created`,
  `workstream.status`, `worker.spawned`, `worker.exited`) folded into
  `InstanceState`; `WorkerRecord` gains `workstreamId` + `checkoutRoot` + a
  liveness/pid field. CLI: `forge workstream new|list`, `forge worker list`
  (instance-scoped, client RPCs).

- [ ] **Task 4.2.5 — multiplexed supervision + cadence + restart reconciliation.**
  The daemon supervisor: on workstream activation, build each worker's
  `WorkerSpec` (resolving the `forge worker` launcher — the forge-specific half of
  4.2.1) + provision its clone (4.2.2) + spawn it (4.2.1) + record
  `worker.spawned`; on child exit, record `worker.exited` and surface it;
  schedule/throttle poll cadence per workstream (§6.2 — the *cadence* is
  daemon-coordinated, the FSM-driving poll stays in the worker). On boot,
  **reconcile**: for each `worker.spawned` without a matching `worker.exited`,
  probe the recorded pid's liveness (`ProcessHandle.of(pid)`); a still-alive
  worker is reattached (its socket feed resumes), a dead one is surfaced (NHI /
  exited). Idempotent against the instance log (a retried spawn doesn't
  double-spawn — §6.4).

- [ ] **Task 4.2.6 — reconciliation proof + close-out (exit criterion).** A test
  (and a live `forge daemon` exercise, dogfood #7) that spawns a real worker
  process on an isolated clone, drives a small feature far enough to export
  events, `kill -9`s the daemon, restarts it, and asserts the worker survived
  (still the same pid, still progressing) and the daemon reattached to its feed +
  rebuilt the workstream/worker view from the instance log alone. Reconcile any
  spec deltas into the live contract per §23. Close-out: whole-section review,
  carry-forward walk, flip the roadmap §5 sub-slice 4.2 bullet.

---

## 3. Status log

- **2026-06-06** — plan opened. Task 4.2.1 (the worker-process boundary spike)
  landing first per CLAUDE.md "run code earlier": the riskiest new 4.2 contract is
  the worker-as-real-OS-process boundary (B4) — the clone (4.2.2) and the v1 loop
  (4.2.3) are mechanical re-use, but supervision/crash-survival/reattachment all
  depend on the process boundary, so a runnable spawn↔phone-home spike goes in
  front. Two halves, each proven with real I/O (a real `sh` child for the spawner;
  a real socket round-trip against an in-process daemon for the `forge worker`
  entrypoint), with the real-`forge worker`-child-spawned-by-the-daemon
  tie-together as the Task 4.2.6 live dogfood — mirroring Slice 4.1's
  unit-the-halves + live-prove-the-real-process discipline. Tasks 4.2.2 (isolated
  clone + per-worker re-root), 4.2.3 (worker runs the v1 loop + feed export), 4.2.4
  (workstream model + CLI), 4.2.5 (supervision + cadence + reconciliation), 4.2.6
  (reconciliation proof + close-out) open.
- **2026-06-06** — **Task 4.2.1 (worker-process boundary spike) landed.** Both
  halves of the B4 boundary, each proven with real I/O. (1) **`WorkerSpawner`
  (forge-daemon):** `WorkerSpec{command, cwd, env}` + `WorkerHandle{pid, awaitExit,
  kill}` + `RealWorkerSpawner`, spawning an OS child via `java.lang.ProcessBuilder`
  (the `forge-agents` `Subprocess` idiom — `Process.onExit()` for an async,
  cancellable wait; `awaitExit` survives the daemon losing interest, §6.4) as a
  `Resource` that force-kills **and reaps** on release. `WorkerSpawnerSuite` spawns
  real `sh` children: an `exit 7` child (pid > 0, `awaitExit == 7`), a `sleep 60`
  killed (idempotent `kill`, reaped), and the finalizer reaping a still-alive child
  on release. (2) **`forge worker` entrypoint (forge-app):** a hidden
  `CommandClass.Worker` (`parseWorker` → `WorkerCommand{instance, workerId, repo,
  feature}`, every field a required flag) that resolves the instance and **phones
  home** to its socket via `DaemonClient` — `register-worker` (with connect-retry),
  `worker-status`, two `worker-event`s — then exits; a connect failure / absent
  instance degrades to exit 1, never a crash. `WorkerCommandSuite` drives it
  against an in-process served daemon and asserts the worker + status + 2 events
  rebuild from the instance log alone (plus the absent-instance / no-daemon / parse
  cases). The real-`forge worker`-child-spawned-by-the-daemon tie-together is the
  Task 4.2.6 live dogfood. `forge-daemon` 11 → 14, `forge-app` +1 suite, full `sbt
  test` green, `scalafmtCheckAll` clean, smell sweep passes. Tasks 4.2.2–4.2.6 open.

---

## 4. Carry-forward / deferred

- **Containerised execution** (B4 container topology, O1/O6/O7 — `Forgefile` tool
  pinning, the OCI runtime seam, credential isolation) is **4.3**. 4.2 runs the
  worker as a **host process on an isolated clone**; the container wraps that same
  worker process later. The `WorkerSpawner` seam is shaped so a `ContainerSpawner`
  slots in behind it without changing the supervisor.
- **B2 budget reservation protocol** (contract §8 / O11) — reserve → grant/refuse
  → finalize-on-`cost.update`, durable in the instance log — is **4.3**. 4.2's
  aggregate fan-in of each worker's exported `cost.update` (via the B3 feed) backs
  the cockpit spend view; the *authorization* gate is 4.3.
- **Credential broker** (O6) — short-lived-secret injection over the worker
  control channel — is a **4.3** blocker. Not touched in 4.2 (a host-process
  worker uses the ambient host credentials, as today's single-repo `forge run`
  does).
- **Cockpit TUI** (§6.3 status subscribe → multi-worker panes, attach/detach,
  per-worker "needs a human" flags) is **4.4**. 4.2 exposes the workstream/worker
  status + `attention` projection over the existing client RPCs; the TUI renders
  them later.
- **Cross-repo coordinated workstreams** (O3/O4) — N workers across repos,
  sequenced by the workstream ordering — stay **deferred (4.6)**; 4.2 implements
  single-repo workers (the ordering field exists in the model but multi-repo
  sequencing is not driven).
- **Dedicated worker control channel vs client JSON-RPC** (O9). As in 4.1, 4.2
  carries the worker's event export over the same socket transport for
  simplicity. The contract rules these are *logically* separate channels; the
  split lands when 4.3's credential broker must not be reachable by a read-only
  TUI client.
- **Local bare/reference mirror clone cache** (O10) — a fetch-cost optimisation
  over re-cloning per worker — is permitted but **deferred** unless 4.2's
  per-worker full clone proves too slow; the **working** clone per worker is the
  invariant, the mirror is never a mutable tree.
