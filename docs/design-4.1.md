# design-4.1 — Slice 4.1 implementation plan (Daemon skeleton + JSON-RPC socket + durability core)

> **Maps to:** [`roadmap.md`](roadmap.md) §5 (Phase 4 — Workspace & Workstream
> platform), §5.3 (daemon); the ratified Phase-4 architecture contract
> [`forge-design-2.0.md`](forge-design-2.0.md) §3.1 **B3** (event export) / **B4**
> (process topology), §6 (daemon), §6.1 (supervisor + instance lock), §6.3 (status
> + control APIs, JSON-RPC 2.0 over a Unix socket — O2/O9), §6.4 (durability +
> recovery — the append-only instance state store, O8), and §11 sub-slice **4.1**.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but **not** during a review
> round; tick the roadmap §5 bullet only at slice close after a whole-section
> review.
>
> **Scope note.** Slice 4.1 lands the **daemon skeleton + its two riskiest new
> contracts**: IPC (JSON-RPC 2.0 over a Unix-domain socket — O2) and durability
> (the append-only instance state store + crash-recovery rebuild — O8 / §6.4). It
> deliberately stops short of **containers** (4.3), the **B2 budget reservation
> protocol** (4.3), the **credential broker** (O6 / 4.3), real **worker
> processes** (4.2), and the **cockpit TUI** (4.4). In 4.1 a "worker" is a
> *record* in the instance store with an exported event feed the daemon
> subscribes to — not yet a daemon-spawned container process. The point is to
> prove the supervisor can hold the instance lock, serve a status snapshot to a
> client over the socket, accept a worker's exported events, and **survive a
> restart by rebuilding its whole view from the instance log** — the §6.4 contract
> every later slice sits on.
>
> **Status:** 🚧 open — Task 4.1.1 (IPC spike) is landing first per CLAUDE.md "run
> code earlier". Tasks 4.1.2–4.1.5 open.

---

## 0. Exit criterion for Slice 4.1

Slice 4.1 is done when **`forge daemon start` runs a supervisor that holds the
instance lock, answers a `status` request from a separate client process over the
instance's Unix-domain socket, accepts a worker's exported event stream into the
durable instance log, and — after being killed and restarted — rebuilds the exact
same status view from the instance log alone** (no state lost, the re-attached
worker's last events still present).

Concretely, mirroring the contract's spike line for 4.1 (*"daemon serves a status
snapshot + survives a restart, rebuilding from the instance log"*):

1. **IPC (Task 4.1.1).** A `forge-daemon` module with a Unix-socket server +
   client speaking **JSON-RPC 2.0**, proven by a round-trip `status` call over a
   tmp socket. This is the riskiest *new transport* contract — landed first,
   runnable, before any daemon lifecycle is built on it.
2. **Durability core (Task 4.1.2).** An append-only **instance action log**
   (`FileInstanceLog`, NDJSON, the `FileActionLog` durability + partial-line
   repair idiom lifted to the instance level) + a **rebuildable instance state
   cache**, with an `InstanceEvent` ADT and a pure `RebuildInstanceState` fold.
   This is the O8 source-of-truth.
3. **Supervisor (Task 4.1.3).** `forge daemon start/stop/status`. The daemon owns
   the instance lock (the generalised `FileProcessLock` on `Instance.lockFile`),
   boots by rebuilding `InstanceState` from the log, and serves the **real**
   `status` snapshot from it; control RPCs append to the log (single-writer:
   §6.3.1 — only the daemon writes the *instance* log).
4. **Status + event-subscribe for one worker (Task 4.1.4).** A worker *record* is
   registered and exports an event feed (B3); the daemon folds it into
   `InstanceState` and a client can `subscribe` to the aggregated feed.
5. **Crash-recovery + close-out (Task 4.1.5, exit).** Kill the daemon mid-life,
   restart, and prove the status view + worker feed rebuild from the log.
   Close-out: whole-section review, carry-forward walk, flip the roadmap §5
   sub-slice 4.1 bullet.

---

## 1. What stays frozen

Everything behind the worker boundary (contract §9): `Fsm.transition`, the
**per-feature** action log format + `foldEvents`/replay/`RebuildState`, the
branch/PR/CI/merge gates, the connectors + reviewer + Phase-3 senses, the
per-session budget checks, and the B1 path re-root landed in Slice 4.0. The
**instance** log introduced here is a *new, separate* durable stream at the
instance level (§6.4 / O8) — it mirrors the per-feature log's idiom but does not
touch it. Slice 4.1 introduces **no** container, **no** B2 budget reservation
protocol, **no** credential broker, and **no** real worker *process* (4.2/4.3) —
a "worker" in 4.1 is an instance-store record + an exported event feed.

---

## 2. Task breakdown

- [x] **Task 4.1.1 — `forge-daemon` module + Unix-socket JSON-RPC `status`
  round-trip (the IPC spike).** New module `forge-daemon` (dependsOn
  `forge-instance`, transitively `forge-core`; libs: `cats-effect`, `fs2-core`,
  `fs2-io`, `upickle`, `os-lib`). A minimal **JSON-RPC 2.0** codec
  (`JsonRpcRequest{jsonrpc,id,method,params}` / `JsonRpcResponse{...,result}` /
  `JsonRpcError{...,error:{code,message}}`, ujson-backed). A
  `DaemonSocketServer` over fs2 `UnixSockets` (`JdkUnixSockets`, JDK-21 native)
  reading newline-delimited JSON-RPC requests and dispatching to a pluggable
  handler; a `DaemonClient` that connects, sends one request, reads one response.
  The socket lives at `instances/<name>/daemon.sock` (`Instance.socketFile`,
  added alongside `lockFile`). The handler answers `status` with a snapshot.
  Proven by a `munit-cats-effect` round-trip test over a tmp-dir socket. *Landed
  first — it is the transport every later daemon task sits on, and it is the
  riskiest new contract.*

- [ ] **Task 4.1.2 — instance action log + rebuildable instance state cache (O8 /
  §6.4 durability core).** An `InstanceEvent` ADT (the minimal 4.1 set —
  `worker.registered`, `worker.status`, `worker.event` for an exported
  per-feature event, plus `daemon.started`), persisted as instance-log records
  with `seq`/`at`/`kind`/`payload` (the `Action` shape, instance-scoped). A
  `FileInstanceLog` at `instances/<name>/log/instance.jsonl` — append-only NDJSON,
  `CREATE|APPEND|SYNC`, partial-trailing-line repair (the `FileActionLog`
  contract, instance-scoped, single daemon writer so no per-feature mutex fan-out
  needed — one log). A pure `RebuildInstanceState.fold(events): InstanceState`
  projecting the worker records + their latest status + exported-feed tails. A
  `FileInstanceStateCache` (atomic temp + `ATOMIC_MOVE` + parent fsync, the
  `FileStateCache` idiom) + `verifyAgainstLog`. Unit tests mirroring
  `FileActionLogSuite` / `FileStateCacheSuite` / `RebuildStateSuite`.

- [ ] **Task 4.1.3 — daemon supervisor: instance lock + state store wiring +
  lifecycle.** A `Daemon` that, on `start`: acquires the instance lock
  (`FileProcessLock(Instance.lockFile, Instance.lockMetadataFile)`, refusing a
  live holder), rebuilds `InstanceState` from the log (Task 4.1.2), binds the
  socket (Task 4.1.1), and serves the real `status` from the rebuilt state. A
  `daemon.started` event is appended on boot. Control RPCs that change instance
  state append to the instance log and update the in-memory `InstanceState`
  (single-writer: the daemon is the sole writer of the instance log, §6.3.1).
  CLI: `forge daemon start` (foreground for 4.1 — backgrounding/`stop` via a
  shutdown RPC), `forge daemon status` (a client `status` call rendered for a
  human), `forge daemon stop` (a `shutdown` RPC). Wired into `Main`'s
  `CommandClass`, resolving the instance the same way the 4.0 instance commands
  do (`--instance` / sole-instance fallback).

- [ ] **Task 4.1.4 — status snapshot + event subscribe for one worker (B3 event
  export subscription).** `register-worker` (a worker record: id, repo, feature,
  status) + a worker→daemon **event-export** RPC (`worker-event`, appending a
  `worker.event` to the instance log and updating the aggregate). A client
  `subscribe` method streaming the aggregated per-worker event feed (fs2 stream
  over the socket; a `Topic[IO, InstanceEvent]` fan-out inside the daemon, seeded
  from the rebuilt tail). For 4.1 the worker is a *driver harness* in the test (no
  container) that pushes a few synthetic events; the real worker process is 4.2.

- [ ] **Task 4.1.5 — crash-recovery proof + close-out (exit criterion).** A test
  (and a live `forge daemon` exercise) that registers a worker, pushes events,
  kills the daemon, restarts it, and asserts the `status` snapshot + the worker's
  event tail rebuild from the instance log alone. Reconcile any spec deltas into
  the live contract per §23. Close-out: whole-section review, carry-forward walk,
  flip the roadmap §5 sub-slice 4.1 bullet.

---

## 3. Status log

- **2026-06-05** — plan opened. Task 4.1.1 (the IPC spike) landing first per
  CLAUDE.md "run code earlier"; the riskiest new contract is JSON-RPC-over-Unix-
  socket transport, so it goes in front of the daemon lifecycle. fs2-io's
  `JdkUnixSockets` (JDK-16+ native; the build runs JDK 21) verified present in the
  resolved `fs2-io_3-3.11.0` jar. Tasks 4.1.2 (durability core), 4.1.3
  (supervisor), 4.1.4 (worker event subscribe), 4.1.5 (crash-recovery + close-out)
  open.
- **2026-06-05** — **Task 4.1.1 (IPC spike) landed.** New `forge-daemon` module
  (`dependsOn forge-instance`; libs cats-effect/fs2-core/fs2-io/upickle/os-lib),
  aggregated into root. `JsonRpc` — the JSON-RPC 2.0 message model + NDJSON line
  codec (`Request{id,method,params}` / `Response.{Success,Failure}` /
  `RpcError{code,message}` with the reserved codes; `params`/`result` left as
  method-agnostic `ujson.Value`s; unparseable line ⇒ `null`-id failure per spec).
  `DaemonSocketServer.serve` binds the instance socket over fs2 `UnixSockets.forIO`
  (`deleteIfExists`/`deleteOnClose`), decodes inbound lines, dispatches a pluggable
  `Handler`, and writes one response line per request (`parJoinUnbounded` over
  connections so a future long-lived `subscribe` can't block a `status`; a raising
  handler is caught into an internal-error response, not a torn-down connection).
  `DaemonClient.call` / `callWithRetry` (bounded connect-retry for the start-up
  race) do one round-trip per connection. `Instance` gained the `socketFile` /
  `instanceLog` / `instanceStateFile` leaves (the 4.1.2 durable-store anchors,
  defined now so the layout doesn't churn; no `.forge` literal — smell sweep still
  passes). `DaemonSocketRoundTripSuite` proves `status` round-trips over a real
  `/tmp`-rooted Unix socket (short `sun_path` for the macOS 104-byte cap), an
  unknown method returns a JSON-RPC `MethodNotFound`, and two requests share one
  connection. `forge-daemon` 3, `forge-instance` 15 (unchanged), full `sbt test`
  green, `scalafmtCheckAll` clean, smell sweep passes. Tasks 4.1.2–4.1.5 open.

---

## 4. Carry-forward / deferred

- **B2 budget reservation protocol** (contract §8 / O11) — reserve → grant/refuse
  → finalize-on-`cost.update`, durable in the instance log — is **4.3**. 4.1's
  instance log is shaped so the `budget.*` records slot in later without a schema
  break (the `InstanceEvent` ADT is `sealed`, the log is versioned).
- **Real worker process + container** (B4 process topology, O7 runtime) — the
  daemon-spawned worker in an OCI container — is **4.2** (host processes on
  isolated clones) / **4.3** (containers). 4.1's worker is a record + an exported
  feed; the spawn/supervise/reattach lifecycle lands when there is a process to
  supervise.
- **Credential broker** (O6) — short-lived-secret injection over the worker
  control channel — is a **4.3** blocker. Not touched in 4.1.
- **Dedicated worker control channel vs client JSON-RPC** (O9). 4.1 carries the
  worker's event-export over the same socket transport for simplicity (one
  server); the contract rules these are *logically* separate channels. If 4.2/4.3
  need a distinct transport (e.g. the credential broker must not be reachable by a
  read-only TUI client), split then. Recorded so the single-socket 4.1 shape is
  understood as a deliberate simplification, not the ratified end state.
- **Multi-worker / workstream aggregation + cadence scheduling** (§6.2) — the
  daemon throttling/scheduling poll cadence across M workers — is **4.2**. 4.1
  proves the single-worker subscribe path.
