# design-4.4 — Slice 4.4 implementation plan (Cockpit TUI)

> **Maps to:** [`roadmap.md`](roadmap.md) §5 (Phase 4 — Workspace & Workstream
> platform); the ratified Phase-4 architecture contract
> [`forge-design-2.0.md`](forge-design-2.0.md) §6.3 (status + control APIs), the
> G5 cockpit-observability goal (§1), the workstream `attention` projection (§5),
> the §8 spend view, and §11 sub-slice **4.4**.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but **not** during a review
> round; tick the roadmap §5 sub-slice 4.4 bullet only at slice close after a
> whole-section review.
>
> **Scope note.** Slices 4.0–4.3 built the platform *engine*: an instance owning N
> repos (4.0), a supervising daemon over a TCP JSON-RPC control plane + durable
> instance log/state (4.1), a workstream/worker model with real worker processes on
> isolated clones under multiplexed supervision (4.2), and containerised execution
> with credential isolation + the B2 budget reservation protocol (4.3). What the
> platform still lacks is the **operator's window into it**: today the only views are
> the one-shot CLI `forge workstream list` / `forge worker list` text dumps and the
> Slice-2.1 *single-feature* read-only `forge tui <feature>` (which reads local
> committed files, not the daemon). Slice 4.4 adds the **cockpit** — a multi-worker /
> multi-workstream TUI that **attaches to a running daemon** over the existing client
> RPCs, renders the aggregated fleet (workstreams → workers, per-worker "needs a
> human" flags, live spend totals), updates live off the B3 subscribe feed, lets the
> operator **drill into** a worker (event tail, status, liveness, container) and
> **act** on it (answer a driver question, approve a gate, pause/abandon), and
> **detaches cleanly** (the daemon + workers run on). It meets **G5** and the §1
> exit criterion (≥2 repos, ≥2 workstreams concurrently, attach/detach clean).
>
> **Status:** _open (2026-06-08)._ Task 4.4.1 (the daemon-backed multi-worker render
> spike) landing first per CLAUDE.md "run code earlier": the riskiest *new* contract
> is the **data-source flip** — the Slice-2.1 TUI renders one feature from local
> committed files, whereas the cockpit renders the *whole fleet from the daemon over
> the network* (a `status`/`subscribe` round-trip → a multi-worker model → a termflow
> view), and **attach/detach cleanliness over a TCP feed** is the exit-criterion-
> critical behaviour. The control API (Task 4.4.4) and container inspection (4.4.5)
> are comparatively mechanical *given* a proven render path. So a runnable
> `forge cockpit` that attaches to a real daemon and renders its fleet goes in front.
>
> **Quality gate update (2026-06-13).** A whole-project review found that root
> `sbt test` can fail in aggregate even when the implicated suites pass in isolation
> (currently a supervisor/background-lifecycle cleanup race). Fixing aggregate test
> reliability is a priority before expanding the cockpit/control surface further.

---

## 0. Exit criterion for Slice 4.4

Slice 4.4 is done when **`forge cockpit` attaches to a running daemon and renders the
live multi-workstream / multi-worker fleet** — each workstream with its goal, lifecycle
state, workers, aggregate spend (committed + outstanding), and the derived per-worker
**attention** flags (NHI / driver-question / merge-gate); the operator can **drill into**
a worker (its exported event tail, status, liveness/container, spend) and **act** from
the cockpit (answer a driver question, approve a gate, pause/abandon a worker or
workstream) over the daemon control API; the view **updates live** off the B3 subscribe
feed; and the cockpit **attaches to / detaches from the daemon cleanly** — quitting the
cockpit leaves the daemon and every worker running (it is a *client*, never the writer).

The roadmap-bar exit criterion (contract §1): **one instance manages the llm4s family
(≥2 repos), running ≥2 workstreams concurrently in containers, with the TUI attaching
to / detaching from the daemon cleanly.** This couples to the deferred live container
dogfood #8 (Slice 4.3 closed on code+proofs with the live container run deferred); the
Task 4.4.6 live exercise is where dogfood #8 is run — a real `forge daemon --container`
driving ≥2 workstreams across ≥2 llm4s-family repos, observed + steered through the
cockpit.

Concretely, mirroring the contract's §11 sub-slice line for 4.4 (*"Multi-worker pane,
attach/detach, per-worker 'needs a human' flagging, container log/process inspection.
Meets G5 + the exit criterion"*):

1. **Daemon-backed multi-worker render (Task 4.4.1).** A pure `CockpitSnapshot`
   (workstreams → workers + attention + spend) parsed from the daemon `status` JSON, a
   `ForgeCockpit` termflow app rendering the fleet, and a `forge cockpit [--instance N]`
   command that attaches to a running daemon, polls `status` on a tick, renders, and
   detaches cleanly on quit. Proven live against a real daemon.
2. **Live feed via `subscribe` + clean attach/detach (Task 4.4.2).** The cockpit
   updates off the B3 `subscribe` stream (not just tick-polling), and attach → observe →
   detach (q / Ctrl-C) provably leaves the daemon + workers untouched; reconnect-on-drop.
3. **Worker drill-down + attention flagging (Task 4.4.3, G5).** Keyboard navigation
   across workstreams/workers; a selected-worker detail pane (status, exported event
   tail, spend, liveness/container id); the attention projection rendered as prominent
   per-worker badges.
4. **Control API + cockpit actions (Task 4.4.4).** The §6.3 control RPCs the daemon
   still lacks (`pause-worker` / `abandon-worker` / `abandon-workstream`, and the
   answer-driver-question / approve-gate path) + cockpit key-bindings wired to them. The
   worker-side *receive* path for answer/approve (a daemon→worker push consumed mid-FSM)
   is the hard part — scoped carefully, with the supervisor-side lifecycle actions
   (pause/abandon) landing first and the answer/approve plumbing designed here (some of
   the deep worker-side consumption may carry to 4.5/4.6).
5. **Container log / process inspection (Task 4.4.5, G5).** A daemon inspection RPC
   (`inspect-worker` → shells `docker logs <id>` / `docker inspect` for a containerised
   worker) surfaced in the cockpit detail pane.
6. **Proof + close-out (Task 4.4.6, exit).** The live multi-workstream exercise
   (folds in dogfood #8) + tests. Reconcile spec deltas (§23). Close-out: whole-section
   review, carry-forward walk, flip the roadmap §5 sub-slice 4.4 bullet.

---

## 1. What stays frozen

Everything behind the worker boundary (contract §9) and the entire 4.0–4.3 engine: the
daemon as the **sole writer** of the instance log (§6.3.1 — the cockpit is a *client*,
it never appends an `InstanceEvent` directly, it asks the daemon to), the `DaemonState`
single-writer durability core + the B2 reservation gate, the worker process / container
topology, the credential broker. The cockpit is **additive**: a new `ForgeCockpit`
termflow app + `CockpitSnapshot` model in `forge-tui` (peer to the Slice-2.1
`ForgeTui`/`TuiSnapshot`, *not* a rewrite of them — the single-feature `forge tui`
stays), a new `forge cockpit` client command in `forge-app` (mirroring
`WorkstreamCommands` — instance-scoped, no config/lock, a `DaemonClient` round-trip),
and — for the control API (Task 4.4.4) and inspection (4.4.5) — **new daemon RPC
methods + their `InstanceEvent` variants** added behind the existing `Daemon.handler`
+ single-writer gate (the open-`kind` instance-log record shape already tolerates new
variants, as the budget events did in 4.3). The existing `status` / `subscribe` /
`create-workstream` / `spawn-worker` RPCs and the status JSON shape are reused as-is for
the read path (Tasks 4.4.1–4.4.3); only the *control* path adds RPCs.

---

## 2. Task breakdown

- [x] **Task 4.4.1 — daemon-backed multi-worker render spike (the riskiest new
  contract, landed first and runnable).** The data-source flip from local-file
  (Slice 2.1) to daemon-over-the-network:
  - **`CockpitSnapshot` (forge-tui)** — a pure multi-workstream/worker model
    (`CockpitSnapshot{instanceName, bootCount, committedUsd, outstandingUsd,
    workstreams: Vector[CockpitWorkstream], looseWorkers: Vector[CockpitWorker]}`;
    `CockpitWorkstream{id, goal, status, committedUsd, outstandingUsd, workers,
    attention}`; `CockpitWorker{workerId, repo, feature, status, live, liveness,
    eventCount, attentionReason}`) + `CockpitSnapshot.fromStatusJson(instanceName,
    json)` — a pure parse of the daemon `status` JSON (the §8 `committedUsd`/
    `outstandingUsd` totals, the §5 `attention` projection), tolerant of absent
    optional fields exactly as `WorkstreamCommands.renderWorkstreams` reads it today.
  - **`ForgeCockpit` (forge-tui)** — a termflow Elm app (peer to `ForgeTui`): a
    summary header (instance · boot · committed/outstanding spend), a scrollable
    workstream/worker list (each workstream with its goal/state/spend, then its
    workers with status + liveness + an attention badge), a footer with the key hints.
    Quit on `q`/Ctrl-C; a 1s `Sub.Every` tick drives the reload. Tested headless via
    `termflow.testkit.TuiTestDriver` (the `ForgeTuiAppSuite` idiom).
  - **`forge cockpit [--instance <name>]` (forge-app)** — a new
    `CommandClass.Cockpit` + `CockpitCommand(instance)` + `CliParser.parseCockpit` +
    `Main.runCockpit` → a `CockpitCommands` handler mirroring `WorkstreamCommands`:
    resolve the instance (`InstanceResolver`), `DaemonClient.call(portFile, "status")`
    for the initial snapshot (no daemon ⇒ the same "start one with `forge daemon
    start`" exit-1 as the workstream commands), then `IO.blocking(ForgeCockpit.run(…))`
    with a per-tick reload thunk that re-calls `status` (the `TuiCommand` IO→Future
    bridge). Read-only — never takes the instance lock, never writes.
  - **Tests:** `CockpitSnapshotSuite` (pure `fromStatusJson` parse: multi-workstream,
    attention flags, spend totals, loose/absent fields) + `ForgeCockpitAppSuite`
    (headless render/update/quit via the testkit). **Proven live** per "run code
    earlier": `forge cockpit` against a real `forge daemon` with a couple of
    registered workers (host-process or stub — the live *container* fleet is 4.4.6),
    rendering the fleet + detaching cleanly.

- [x] **Task 4.4.2 — live feed via `subscribe` + clean attach/detach.** Drive cockpit
  updates off the B3 `DaemonClient.subscribe` stream rather than only re-polling
  `status`; prove attach → observe live events → detach (q / Ctrl-C) leaves the daemon
  + every worker running (the exit-criterion attach/detach bar) + a reconnect-on-drop
  policy. Resolve the termflow-`Future` vs fs2-`Stream` bridge (a background fiber
  feeding the queue the reload drains). **Ratified bridge: subscribe-triggered status
  refresh** — each pushed event is a *change signal* that triggers a debounced `status`
  re-fetch into a shared `Ref` the render tick reads, rather than a client-side event
  fold. The cockpit's §5 `attention` projection + §8 spend totals are derived
  canonically in `forge-instance` (which `forge-tui` can't see) and the daemon's
  subscribe *seed* is per-worker only (no pre-attach workstreams/budget), so a literal
  client-side fold would duplicate load-bearing projection logic and lose pre-attach
  state; re-fetching `status` keeps the projection canonical + lossless while staying
  event-driven.

- [ ] **Task 4.4.3 — worker drill-down + per-worker "needs a human" flagging (G5).**
  **Quality precondition:** restore stable aggregate `sbt test` first. The current
  failure mode points at background supervisor/watch fibers racing temp-dir cleanup;
  the fix should leave the root unit suite green under normal aggregate execution,
  not only under targeted `testOnly` reruns.
  Keyboard navigation (select a workstream / worker); a selected-worker **detail pane**
  (exported event tail, FSM status, spend, liveness/container id, attention reason); the
  `attention` projection rendered as prominent badges (NHI / driver-question /
  merge-gate) at the workstream + fleet level so an operator sees at a glance which
  worker needs what.

- [ ] **Task 4.4.4 — control API + cockpit actions.** Add the §6.3 control RPCs the
  daemon lacks — supervisor-backed **`pause-worker` / `abandon-worker` /
  `abandon-workstream`** (lifecycle + supervisor actions, no worker-side plumbing) land
  first; the **answer-driver-question / approve-gate** path (a daemon→worker push the
  worker consumes mid-FSM) is the hard part, designed here with the deep worker-side
  consumption scoped (some may carry to 4.5/4.6). Each new RPC is a `Daemon.handler`
  case + (where it mutates fleet state) an `InstanceEvent` variant under the
  single-writer gate; cockpit key-bindings invoke them via `DaemonClient.call`.

- [ ] **Task 4.4.5 — container log / process inspection (G5).** An `inspect-worker`
  daemon RPC that, for a containerised worker, shells `docker logs <containerId>`
  (tail) / `docker inspect` (process/ports/state) via the existing `OciRuntime` seam,
  surfaced in the cockpit detail pane. A host-process worker degrades to its pid + the
  exported feed.

- [ ] **Task 4.4.6 — proof + close-out (exit criterion).** The live multi-workstream
  exercise — `forge daemon --container` driving **≥2 workstreams across ≥2
  llm4s-family repos concurrently**, observed + steered (answer a question / approve a
  gate / abandon) through `forge cockpit`, attaching/detaching cleanly — **folding in
  the deferred dogfood #8**. Tests + a runbook (`dogfood/4.4-cockpit.md`). Reconcile
  spec deltas (§23). Close-out: whole-section review, carry-forward walk, flip the
  roadmap §5 sub-slice 4.4 bullet.

---

## 3. Status log

- **2026-06-08** — plan opened. Task 4.4.1 (the daemon-backed multi-worker render spike)
  landing first per CLAUDE.md "run code earlier": the riskiest new 4.4 contract is the
  **data-source flip** — the Slice-2.1 `forge tui` renders one feature from local
  committed files, whereas the cockpit renders the whole fleet *from the daemon over the
  network* (`status`/`subscribe` → a multi-worker model → a termflow view), with
  attach/detach cleanliness over a TCP feed the exit-criterion-critical behaviour; the
  control API (4.4.4) + container inspection (4.4.5) are comparatively mechanical given a
  proven render path. Survey confirmed the reuse surface: the daemon `status` JSON
  (`committedUsd`/`outstandingUsd` + per-workstream `attention`), `DaemonClient.call`/
  `subscribe` (port-file form), the termflow Elm API (`ForgeTui`/`TuiSnapshot`), and the
  `WorkstreamCommands` client idiom; and the **gap**: the daemon handler has no
  `pause`/`abandon`/`answer-question`/`approve-gate` control RPCs yet (only
  `create-workstream`/`spawn-worker`), so the control API is real new scope (4.4.4).
  Tasks 4.4.2–4.4.6 open.
- **2026-06-08** — **Task 4.4.1 (daemon-backed multi-worker render spike) landed.** The
  data-source flip is proven: the cockpit renders the whole fleet from the daemon over the
  network, peer to (not a rewrite of) the Slice-2.1 single-feature `forge tui`. (1)
  **`CockpitSnapshot` + `CockpitSnapshot.fromStatusJson` (forge-tui)** — a pure, total
  multi-workstream/worker read-model parsed from the daemon `status` JSON: workers folded
  into their workstream by the `workers` id list, the §5 `attention` projection resolved
  onto the flagged worker, the §8 `committedUsd`/`outstandingUsd` totals, a rendered
  liveness label (pid / truncated containerId / exitCode), and workstreamless workers
  collected as `looseWorkers`; every field read through an `…Opt` accessor so an absent
  optional key degrades rather than throws. (2) **`ForgeCockpit` (forge-tui)** — a termflow
  Elm app (peer to `ForgeTui`): an instance/boot/spend summary header + a scrollable fleet
  list (workstream rows with goal/state/spend, indented worker rows with status + liveness +
  an attention badge, an `(unassigned)` group), 1s tick reload, `q`/Ctrl-C quit, `?` help
  overlay. (3) **`forge cockpit [--instance <name>]` (forge-app)** — a new
  `CommandClass.Cockpit` + `CockpitCommand` + `CliParser.parseCockpit` + `Main.runCockpit`
  → a `CockpitCommands` handler mirroring `WorkstreamCommands`: resolve the instance
  (`InstanceResolver`), seed from one `status` round-trip, run `ForgeCockpit` with a per-tick
  `status` re-fetch (the `TuiCommand` IO→Future bridge), a transport failure mid-session
  folding to `None` (keeps the last frame). Read-only — never takes the instance lock, never
  writes (§6.3.1). **Tests:** `CockpitSnapshotSuite` (6, pure parse), `ForgeCockpitAppSuite`
  (9, headless render/quit/help via the testkit), and — grounding the parser against the
  **real producer** per CLAUDE.md "capture real shapes" — `CockpitStatusWireSuite` (forge-app,
  5: folds a real `InstanceEvent` sequence via `RebuildInstanceState.step`, renders the actual
  `InstanceState.toStatusJson`, asserts the parsed `CockpitSnapshot`; catches `toStatusJson`
  drift a static fixture would miss). **Proven live**: `forge cockpit --instance llm4s` against
  a non-running daemon drives the full new dispatch chain (parse → `CommandClass.Cockpit` →
  `runCockpit` → `CockpitCommands` → real `InstanceResolver` resolving the on-disk `llm4s`
  instance → clean "no daemon … start one with `forge daemon start`" exit 1); the successful-
  attach render rides the same `DaemonClient.call(portFile, "status")` path proven live in 4.2,
  with the interactive live render deferred to the Task 4.4.6 dogfood (it needs a real terminal
  + a live fleet). forge-tui 38→53, forge-app 561→566; full `sbt test` green across all modules,
  `scalafmtCheckAll` clean, ForgePaths smell sweep passes. Tasks 4.4.2–4.4.6 open.
- **2026-06-08** — **Task 4.4.2 (live feed via `subscribe` + clean attach/detach) landed.**
  The cockpit's data source flips from per-tick `status` polling to the B3 `subscribe`
  stream, via the ratified **subscribe-triggered status refresh** bridge. (1) New
  **`CockpitLiveFeed` (forge-app)** — `follow(instance, latest, settle, reconnectDelay)`
  holds a persistent `DaemonClient.subscribe` connection; each pushed event is a *change
  signal* that, after a `settle` debounce coalescing bursts, re-fetches `status` into a
  shared `Ref[Option[CockpitSnapshot]]`; an RPC-error/transport-failure `fetch` leaves the
  ref untouched (keep the last good frame); stream completion/error waits `reconnectDelay`
  and re-subscribes (reconnect-on-drop), with an immediate `fetch` catch-up per (re)connect
  so an empty fleet (no seed events) and a post-reconnect gap both refresh. *Why a re-fetch
  and not a client-side event fold:* the §5 `attention` projection + §8 spend are derived
  canonically in `forge-instance` (`AttentionReason.forStatus`, the reservation table) which
  `forge-tui` can't see, and the daemon's subscribe *seed* replays per-worker events only (no
  pre-attach workstreams/budget) — folding client-side would duplicate load-bearing logic +
  lose pre-attach state. (2) **`CockpitCommands.launch` rewired** — seeds the `Ref` from one
  `status` round-trip, hands `ForgeCockpit` a ref-reading reload thunk (`() =>
  latest.get.unsafeToFuture()`, no per-tick network I/O), and runs
  `CockpitLiveFeed.follow(...).background` around the blocking `ForgeCockpit.run`; quitting
  closes the background scope → cancels the fiber → releases the subscribe socket (clean
  detach, daemon untouched). **`ForgeCockpit` (forge-tui) is unchanged** — its `reload`
  contract is reused verbatim; only the data behind it changed. (3) **Tests:** new real-daemon
  **`CockpitLiveFeedSuite` (forge-app, 3)** mirroring `DaemonWorkerSubscribeSuite`'s
  `instance`/`served` harness (a real `DaemonState` + `Daemon.serveUntilShutdown` over a real
  socket): a recorded fleet mutation refreshes the shared snapshot off the feed; detaching
  (cancelling) leaves the daemon answering `status`; the feed reconnects after a daemon
  restart and surfaces a worker registered against the second daemon. forge-app 566→569; full
  `sbt test` green across all modules, `scalafmtCheckAll` clean. Live interactive render still
  rides the Task 4.4.6 dogfood (needs a real terminal + live fleet). Tasks 4.4.3–4.4.6 open.
- **2026-06-13 — whole-project review follow-up.** The planning docs now treat root
  `sbt test` reliability as a first-class quality gate for the active slice. Observed
  state: `sbt scalafmtCheckAll` passes; targeted reruns of the initially implicated
  suites pass; aggregate `sbt test` can still fail in `SupervisorSuite` with a
  `DirectoryNotEmptyException` during temp-dir cleanup, consistent with a background
  supervisor/watch lifecycle race. Task 4.4.3 carries the precondition to fix this
  before adding more cockpit/control surface.

---

## 4. Carry-forward / deferred

- **Live container fleet exit criterion couples to dogfood #8.** Slice 4.3 closed on
  code + automated proofs with the live `forge daemon --container` run deferred (a scoped
  PAT + agent keys + real spend). Task 4.4.6's live exercise is where dogfood #8 runs (a
  cockpit needs a live fleet to be worth driving), so the 4.4 close inherits the same
  provisioning dependency. Tasks 4.4.1–4.4.5 are proven against host-process / stub
  workers + a real daemon; the *container* fleet is the 4.4.6 dogfood.
- **Answer-driver-question / approve-gate worker-side consumption (Task 4.4.4).** The
  daemon→worker push a worker consumes mid-FSM (so a cockpit answer reaches the frozen
  v1 loop running headless in a container) is the deep part of the control API. The
  supervisor-side lifecycle actions (pause/abandon) need no worker-side plumbing and land
  in 4.4.4; the answer/approve consumption path is designed in 4.4.4 with the deepest
  worker-side wiring potentially carried to 4.5/4.6 if it proves heavier than the slice
  scopes. (The worker control channel is worker→daemon today; this adds a daemon→worker
  direction.)
- **CMUX-style container viewer (§5 observability).** The contract notes CMUX, if
  integrated, is a *viewer* over the container status feed, not a daemon replacement. 4.4
  surfaces logs/process/ports via the `inspect-worker` RPC in the cockpit's own panes; a
  richer external CMUX integration is out of scope.
