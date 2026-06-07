# Forge — design doc v2.0

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v2.0 (2026-06-05) — Phase 4 (Workspace & Workstream platform): instance scope + workstreams + workers + daemon + containerised execution + parallel runs  •  **Target:** personal tool, OSS later

**This is the Phase-4 architecture contract.** It is the design doc the roadmap (§5) names as the prerequisite for any Phase-4 code (*"Treat this phase as needing its own design doc — `forge-design-2.0.md` — before any code lands"*). It is comprehensive in **design** because the roadmap establishes the four sub-pieces unlock each other and must be designed as one phase; **implementation** is sliced separately in the per-slice `design-4.x.md` plans (§11).

**Relationship to v1.16 — the FSM is frozen; v2 adds a *worker boundary* around it.** v2.0 is the first *major*-version revision. It does **not** alter the single-repo feature **lifecycle**: the v1 contract — [`forge-design-1.16.md`](forge-design-1.16.md) and the §-frozen base [`forge-design-1.6.md`](forge-design-1.6.md) — remains authoritative for `Fsm.transition`, the action-log format + replay/restart determinism, branch/PR/CI/merge gates, the connectors + reviewer + Phase-3 senses, the poll loop that drives the FSM, and the per-session budget checks. What v2.0 adds is a **worker boundary** around that engine with a **small, explicit set of seams** (§3.1): path topology, pre-spawn budget authorization, event export, and process topology. The earlier draft's claim that "the whole inner orchestrator is unmodified" was wrong — pathing, budget authorization, event export, and process topology each need a declared boundary. Everything *behind* the boundary stays frozen; the boundary itself is the v2 contract.

**Changed/new in v2.0:** the whole outer layer + the worker boundary. §1 (v2 goals + re-decision of four v1 non-goals), §2 (vocabulary: instance, **worker**, workstream, feature), §3 (layered architecture + the worker-boundary seams), §4 (Forge instance + path topology), §5 (workstream/worker model + cardinality), §6 (daemon + durability/recovery), §7 (containerised execution + credential isolation), §8 (parallel runs + budget authorization/fan-in), §9 (what stays frozen), §10 (decisions — ruled + still-open), §11 (proposed sub-slices), §12 (re-decisions vs v1 §22).

---

## 0. The pivot in one paragraph

v1 is a laptop tool whose lifetime is one TUI session driving **one feature in one repo**. v2.0 promotes Forge to a **long-running workspace platform**: one *instance* per project owns N repos; a developer expresses intent as a **workstream** (a coordination object — a goal that may span several repo-features); the daemon decomposes each workstream into **workers**, where one worker = one repo-feature, runs in its own container against its own isolated checkout, and drives an unmodified v1 feature loop. A *daemon* supervises the worker fleet, owns the instance lock, authorizes spend, aggregates each worker's event feed, and serves the TUI/CLI clients. Phase 3's repo-adaptation layer is the safety prerequisite — a cockpit driving several repos at once is only safe once each repo is *profiled* rather than run blind.

---

## 1. Goals and non-goals (v2.0)

**Goals.**
- **G1 — Instance scope.** A first-class "Forge instance / workspace": one project group, N repos, M workstreams, its own config/prompts/state, addressable by name.
- **G2 — Workstream as the developer's unit, worker as the execution unit.** Replace "one feature at a time" with M concurrent workstreams; each workstream decomposes into one or more workers (one repo-feature each), which are what actually run.
- **G3 — Daemon.** A long-running supervisor owning the instance lock, the worker fleet, spend authorization, and event aggregation; TUI and CLI are clients over a local socket.
- **G4 — Worker isolation.** Each worker runs in its own container — an isolated full-clone checkout, pinned tool versions, host-isolated permissions — so broad-permission agent runs can't cross-contaminate and parallel work can't collide.
- **G5 — Cockpit observability.** A multi-workstream/worker TUI that flags and accepts human input at the right stage of *each* worker (NHI, driver question, merge gate, profile/convention-PR approval), plus per-container log/process/port inspection via the daemon status API.

**Re-decided v1 non-goals (v1.6 §1 / §22 reject these *for v1*; v2.0 revisits them deliberately).**
| v1 stance | v2.0 decision |
|---|---|
| "Multi-repo / monorepo split work" — non-goal | **Adopted** as the instance concept (§4). |
| "Parallel features" — non-goal | **Adopted** at *worker* granularity (§5, §8). Per-session cost-cap enforcement unchanged; aggregate budgets gain a pre-spawn authorization seam + fan-in. |
| "Long-running daemon; lifetime = TUI session" | **Adopted** as daemon mode (§6). CLI remains the scripting interface; both talk to the daemon. |
| "Worktrees rejected; devcontainer-incompatible" | **Worktrees stay rejected.** Workers use isolated full-clone checkouts in containers, not git worktrees (§7). Different mechanism, same "concurrent work without colliding" goal. |

**Non-goals (still rejected).** Webhooks (polling stays the trigger model — v1.6 §22). Worktrees as isolation. Multi-*user* / hosted multi-tenant operation. Reactive PR review, knowledge base, issue-tracker integration — Phase 5 (roadmap §6).

**Exit criterion (roadmap §5 — kept at the roadmap bar).** One instance manages the llm4s family (**≥2 repos**), running **≥2 workstreams** concurrently in containers, with the TUI attaching to / detaching from the daemon cleanly. (Stated in *workstreams*, not workers, so one cross-repo workstream of two workers does not trivially satisfy it — the bar is genuine multi-workstream concurrency.)

---

## 2. Vocabulary

- **Instance (workspace).** One project that may span multiple repos. Owns repos, workstreams, config, prompts, and instance-level local state/log. Addressable by name.
- **Repo.** A git repository the instance knows about — **registry/input, not a shared mutation target.** The registered repo path is where the instance reads config/profile and clones *from*; workers never mutate it directly. Its **committed** per-repo `.forge/specs/` family (design, manifest, decomposition, pieces, `audit/`, `config.json`, `profile.json`, overrides) is the versioned input + review-history home, mutated **inside the worker clone** and flowed back through PRs/merge (§4.3).
- **Workstream.** *The unit a developer thinks about, and a coordination object* — a goal with state and an ordering over the workers that fulfil it. **Not** an execution unit and **not** a checkout. The TUI's top-level grouping.
- **Worker.** *The execution + isolation unit* — exactly **one repo-feature**: one isolated full-clone checkout, in one container, running one unmodified v1 feature loop. The atomic thing the daemon spawns, supervises, authorizes, and observes.
- **Feature.** *The unit Forge implements* — unchanged from v1 (a manifest of pieces driven by `Fsm.transition`). One feature is driven by exactly one worker.
- **Daemon.** The long-running supervisor; owns the instance lock, the worker fleet, spend authorization, event aggregation, and the client socket.

**Cardinality (the corrected model).** workstream **1—N** worker **1—1** feature **1—1** checkout **1—1** container. A single-repo single-feature workstream = 1 worker (the common case). N features in one repo = N workers, *each its own clone + container* (so parallel features never share a checkout — the v1 collision risk Phase 4 exists to remove). Cross-repo work = N workers across repos, sequenced by the workstream.

---

## 3. Architecture — the layered picture

```
┌─ Forge instance (~/.forge/instances/<name>/) ─────────────────────────────┐
│  config · prompts · instance state/log · (Phase 5) knowledge base         │
│                                                                           │
│  ┌─ Daemon (long-running supervisor) ────────────────────────────────────┐│
│  │  • owns instance lock + instance state store (durable, §6.4)          ││
│  │  • spawns/supervises workers; pre-spawn budget authorization (§8)     ││
│  │  • subscribes to each worker's exported event feed → status aggregate ││
│  │  • status + control API over a Unix socket (§6); TUI & CLI are clients││
│  │                                                                       ││
│  │  ┌─ Workstream A (coordination object) ──┐  ┌─ Workstream B ─────────┐││
│  │  │  goal · ordering · {Worker A1, A2}     │  │  goal · {Worker B1}    │││
│  │  └───┬───────────────────┬────────────────┘  └────────┬───────────────┘││
│  │      │                   │                            │                ││
│  │  ┌───▼ Worker A1 ────┐ ┌─▼ Worker A2 ────┐        ┌───▼ Worker B1 ───┐ ││
│  │  │ container         │ │ container        │        │ container        │ ││
│  │  │ isolated clone    │ │ isolated clone   │        │ isolated clone   │ ││
│  │  │ claude/codex/gh + │ │ pinned toolchain │        │ ...              │ ││
│  │  │ pinned toolchain  │ │                  │        │                  │ ││
│  │  │ ┌ v1.16 feature ─┐│ │ ┌ v1.16 feature ┐│        │ ┌ v1.16 feature┐│ ││
│  │  │ │ FSM·log·gates· ││ │ │ FSM·log·gates· ││        │ │ FSM·log·...  ││ ││
│  │  │ │ poll·senses    ││ │ │ poll·senses    ││        │ │              ││ ││
│  │  │ └────────────────┘│ │ └────────────────┘│        │ └──────────────┘│ ││
│  │  └────────┬──────────┘ └────────┬──────────┘        └────────┬─────────┘││
│  └───────────┼─────────────────────┼──────────────────────────┼──────────┘│
│   worker-boundary seams (§3.1): path · budget-auth · event-export · process│
└───────────────────────────────────────────────────────────────────────────┘
```

### 3.1 The worker boundary (the explicit inner seams)

Behind the boundary, the v1 feature loop is frozen (§9). The boundary is exactly four seams — small, declared, and the substance of the v2 contract:

- **B1 — Path topology.** A worker runs against an **isolated checkout** with its **local** runtime (`.forge/log/`, `.forge/state/`, lock) re-rooted to the instance/worker directory; the **committed** `.forge/specs/` family stays anchored at the **worker clone's** repo root (not the registered source checkout — §4.3). `ForgePaths` already centralises this and its docstring already names the re-root as the Phase-4 plan — so this is a constructor change, not a callsite sweep. (§4.3)
- **B2 — Budget authorization.** Before spawning any agent session, the worker makes a **reservation/authorization call** to the daemon, which enforces the *aggregate* (instance + workstream) caps and may refuse the spawn. Per-session caps (v1.6 §12 + Slice-2.2 feature/piece + non-killing per-turn advisory) are unchanged *inside* the worker. This is a genuine inner boundary: passive after-the-fact aggregation cannot refuse a spawn. (§8)
- **B3 — Event export.** The worker's action log stays its own canonical, locally-written source of truth (replay/restart determinism unchanged); the worker additionally **exports** its event stream to the daemon, which is a *subscriber* for status aggregation, never the writer. (§6.3)
- **B4 — Process topology.** A worker is a **daemon-spawned process inside a container**, not an in-process library call. The daemon manages its lifecycle (start/stop/crash/reattach). The worker's own FSM-driving poll loop stays inside the worker (polling drives transitions, CI/merge gates, baseline persistence — it is not mere observation, so it is *not* relocated to the daemon). The daemon multiplexes *supervision and status*, and may throttle/schedule worker poll cadence, but does not own the FSM poll. (§6.2)

**Module placement (proposed, additive).** New `forge-daemon` (supervisor, socket server, worker fleet, budget authority, event aggregator), `forge-instance` (instance/workstream/worker model, repo registry, re-rooted paths, durable state store), and a `forge-tui` cockpit mode (extends the Slice-2.1 read-only TUI to multi-worker + attach/detach). `forge-app`'s orchestrator becomes the per-worker engine the daemon spawns, gaining only the B1–B4 seams. The deferred `Connector` capability-trait split (roadmap §4.2 C1) is picked up when 4.3/4.4 add the daemon/cockpit roles.

---

## 4. Forge instance (roadmap §5.1)

- **Concept.** An instance owns N repos, M workstreams, its own config, prompts, and (Phase 5) knowledge base. Addressable by name.
- **Proposed layout.** `~/.forge/instances/<name>/` with `repos/` (registry → repo paths + per-worker clone roots), `workstreams/<ws>/` (goal, state, worker refs, ordering), `workers/<worker>/` (checkout root, container ref, re-rooted `log/`+`state/`), `config.json`, `prompts/`, `log/` (instance-level), and the durable instance **state store** (§6.4). `forge init-instance <name>` / `add-repo <path>` / `list-repos` / `list-workstreams` / `attach` / `detach`.

### 4.3 Path topology (B1 — corrected committed-vs-local split)

The earlier draft contradicted v1 by calling the action log "committed." Corrected against `ForgePaths`:

**"Repo root" means the worker's clone, not the registered source checkout.** Under isolation each worker operates on its **own clone**; "anchored at repo root" = anchored at *that worker's clone root*. The registered repo path (§2) is registry/input — it is never the shared mutation target. Committed `.forge/specs/` is written **inside the worker clone** and merged back through the normal PR/merge path (v1.6 §10/§11), so two workers on the same repo never write each other's specs tree.

| Class | Paths | v1 home | v2 home |
|---|---|---|---|
| **Committed** (versioned inputs, in git) | `.forge/specs/` (design, `manifest.json`, decomposition, `pieces/`, `audit/`), `config.json`, `profile.json`, `overrides/` | repo root | **the worker clone's repo root** (committed + merged via PR; never written to the registered source checkout) |
| **Local runtime** (gitignored, canonical-but-rebuildable) | `.forge/log/<feature>.jsonl` (canonical action log), `.forge/state/` (state cache, poll-baselines), lock | repo root | **re-rooted** under the worker's instance dir (outside the clone) |

The action log is **local canonical runtime, not committed** (v1.6 §4 invariant; `ForgePaths.featureLog`). Replay/restart determinism is preserved — the log just lives under the worker dir instead of in the repo. Every callsite already speaks to `ForgePaths`, so re-rooting is a constructor change (enforced clean by `ForgePathsSuite`'s sweep).

---

## 5. Workstream + worker model (roadmap §5.2)

**Workstream** = coordination object. Tracks **goal, lifecycle state, ordering over workers, active/next worker(s)**, and (Phase 5) a backing issue. Its lifecycle state is deliberately small and *scalar* — `Planning → Active → Done/Abandoned` — and never reaches into `Fsm.transition`. **"Needs a human" is an aggregate projection, not a lifecycle state:** a workstream stays `Active` while *any* worker is progressing, and a separate derived `attention` flag (with the list of which workers need what — NHI, a driver question, a merge/approval gate) is computed over its workers. One worker awaiting input while another runs ⇒ workstream `Active` with `attention = {that worker}`, surfaced as the cockpit's per-worker flags (G5). It can span:
- **One worker** — single feature/single repo; the common case.
- **N workers, one repo** — parallel features, each its **own clone + container** (no shared checkout).
- **N workers across repos** — coordinated change (e.g. llm4s-core + a matching termflow update), sequenced by the workstream's ordering.

**Worker** = one repo-feature. Owns an isolated checkout, a container, and an unmodified v1 feature loop. The daemon spawns/supervises it (B4), authorizes its spend (B2), and subscribes to its events (B3).

**Cross-repo coordination** is the genuinely new control problem (O3). v2.0 **designs** the data model now (workstream ordering + an instance-local workstream event log, §6.4 / O4) but **implements single-repo workers first** (§11), deferring multi-repo orchestration to a later slice.

---

## 6. Daemon (roadmap §5.3)

- **6.1 Supervisor.** Long-running process; TUI and CLI are clients over a **Unix domain socket**. CLI stays the primary scripting interface. One daemon per instance; it owns the **instance lock** (generalising v1 `ProcessLock`); per-worker locks live below it.
- **6.2 Polling.** Each **worker** runs its own v1 poll loop (it drives FSM transitions — PR feedback, CI gates, merge gates, baseline persistence — so it stays in the worker, B4). The daemon multiplexes **supervision + status aggregation** across workers, and may schedule/throttle poll cadence per workstream. (This refines roadmap §5.3's "daemon multiplexes polling": the *cadence* is daemon-coordinated; the *FSM-driving poll itself* is not relocated, because doing so would be an inner-lifecycle change, not a boundary seam.)
- **6.3 APIs.** A **status API** (snapshot + subscribe to the aggregated per-worker event feed the cockpit renders) and a **control API** (start/pause/abandon a worker or workstream, answer a driver question, approve a gate). Client wire shape: **JSON-RPC 2.0 over the socket** (O2, ruled); the worker↔daemon control/budget/event/credential path is a **dedicated worker control channel**, kept separate from the client JSON-RPC (O9, ruled).
- **6.3.1 Control serialization (single-writer invariant).** The **worker process is the sole writer** of its own feature log + state — exactly v1's invariant. The daemon **never** mutates a worker's log/state directly. A state-changing control command (answer/approve/pause/abandon for a *running* worker) is **delivered to the owning worker** over the worker control channel and **applied by the worker on its own loop** (it becomes an `FsmEvent` the worker feeds through `Fsm.transition`, like a v1 `UserCommandReceived`). So daemon control and the worker's poll never race on the same log/state. The per-worker lock (held by the worker process) guards this; the daemon holds only the instance lock + its own state store. (Commands to a *not-yet-spawned* or *dead* worker are applied by the daemon to the instance state store, since no worker process owns them.)
- **6.4 Durability + recovery (new — required for ratification).** The daemon is durable, not just a process:
  - **Source of truth.** A durable **instance state store** (proposed: an append-only instance action log mirroring v1's per-feature log idiom, plus a rebuildable instance state cache) recording instance/workstream/worker records, container refs, and lifecycle transitions. Schemas for `Instance`, `Workstream`, `Worker` (incl. container ref + checkout root + status) are part of this slice. *Implemented (4.3): `worker.spawned` carries a **topology liveness key** — `pid: Option[Long]` for a host-process worker **xor** `containerId: Option[String]` for a containerised one — and `live = exitCode.isEmpty && (pid.isDefined || containerId.isDefined)`; reconcile re-probes a pid via `ProcessHandle` and a container via `OciRuntime.running`/`attach`, so a daemon restarted in either mode reconciles both topologies it finds in the log (cache schema bumped to v3).*
  - **Crash recovery.** Workers are **separate processes/containers**, so they survive a daemon crash. On restart the daemon **rebuilds its view** from the instance state store + each worker's exported event log (the same replay/restart discipline v1 uses per feature, lifted to the instance), reconciles live container state (which containers are still running), and **reattaches** to running workers' event feeds.
  - **Idempotency.** Control commands carry a client-supplied id; spawn/merge/approve operations are idempotent against the instance log so a retried command after a disconnect doesn't double-act.
  - **Daemon dies mid-turn (with the B2/O6 dependency made explicit).** Workers depend on the daemon for *budget authorization* (B2) and the *credential broker* (O6), so the recovery contract is: **(a)** an **in-flight turn continues** only if it already holds the credentials it needs (the broker injects at spawn; a turn already running does not re-call the broker mid-turn); **(b)** the worker keeps writing its canonical log throughout; **(c)** the **next agent spawn blocks** on the budget-authority / credential-broker call until the daemon reconnects (a budget-hold, not a failure); **(d)** exported-feed **offsets are durable on the worker side**, so on restart the daemon replays from the worker's last-acked offset — no event lost or double-counted. A worker whose container died is detected during reconciliation and surfaced as NHI.
- **6.5 Lifecycle.** `forge daemon start/stop/status`; **explicit start first**, auto-start-on-connect deferred (O5, ruled). Clean attach/detach (a client disconnect must not kill in-flight workers) is part of the exit criterion.

---

## 7. Containerised execution (roadmap §5.4)

Drivers: parallelism, observability, reproducibility, isolation, broad-permission agent runs without cross-contamination → *every worker runs in its own container with an isolated checkout, pinned tooling, host-isolated permissions.*

- **One container per worker** (= per repo-feature). The worker is the unit with a coherent isolated checkout.
- **Isolated full clones, not worktrees** (worktrees stay rejected, v1.6 §1). Parallel workers never share a working tree.
- **Tool pinning (O1, ruled — flipped).** Source of truth is a **normative** `Forgefile` (or a reused devcontainer/`Dockerfile`) declaring the image + pinned tool versions — reproducibility *policy*, committed per repo. `RepoProfile` (Phase-3, observed/adaptive state) **discovers defaults and validates** the pinned set against what it perceives, but is **not** the source of truth (its job is perception, not normative pinning).
- **Runtime (O7, ruled).** An **abstract OCI runtime seam** with a **Docker-first** implementation (host is macOS); Podman/colima swappable behind the seam.
- **Credential isolation (O6, ruled — a 4.3 blocker, not vague hardening).** Minimum ratified requirements: **no host home mount** into the container; **per-repo / per-worker scoped `gh` token** (not the host-wide credential); a **broker / short-lived-secret injection** model for `claude`/`codex` auth (host-side broker over the worker's control channel, or mounted short-lived tokens) so host-wide credentials never enter the container. This is the crux of the G4 safety claim and must be designed in 4.3, not deferred.
- **Observability.** Logs, processes, ports inspectable via the daemon status API and surfaced in the TUI (the CMUX-style layer). CMUX, if integrated, is a *viewer* over the container status feed, not a daemon replacement.
- **Implemented (4.3).** The supervisor runs `forge worker --instance <name> --worker-id <id> --repo <repo> --feature <id> --worker-root <path> --socket <path> --container` **inside** the container. Exactly two bind mounts — the isolated clone *worker root* (RW; the checkout is `<root>/checkout`) and the daemon control socket — and **no host home mount, no secret env in the spec** (`docker inspect`-safe, O6); the worker brokers its credentials over the mounted socket (`broker-credentials`: a per-repo scoped `gh` token + best-effort agent keys, sourced via a `SecretSource` seam, **refusing** rather than falling back to a host login). The image resolves from the clone's committed `Forgefile.image` (O1), else the fallback **`forge-worker:latest`** — a forge-capable base bundling `forge` + the pinned `gh`/`claude`/`codex` tooling (the default image + build wiring live in `docker/forge-worker/`). Containers are spawned `--rm`-free so a restarted daemon can still inspect/await an exited one. The abstract seam is `OciRuntime` (`run`/`running`/`attach`); the Docker-first impl shells `docker run -d`/`wait`/`kill`/`rm -f`. Defence-in-depth non-root + host-UID mapping is a 4.5 carry-forward.
  - **Transport caveat (found in the 4.3.6 dogfood prep, → 4.5).** The worker reaches the daemon over the bind-mounted **Unix** control socket (O9). This works natively on a **Linux** host, but **not** on Docker Desktop for **macOS** (the stated host, §7): a container connecting to a *host-created* Unix socket over a bind mount fails with `Errno 95 Operation not supported` (the VM boundary does not pass host Unix sockets through; only a socket on a *shared Docker volume* between two in-VM containers crosses). So the **containerised** worker↔daemon control channel runs only on Linux today; **re-architecting it to TCP** (so it crosses the macOS VM boundary cleanly) is **deferred to 4.5**, and **dogfood #8 runs on a Linux host**. The host-process (4.2) topology is unaffected (no container boundary).

---

## 8. Parallel workers, budget authorization + fan-in (roadmap §5.5)

- Concurrency unit is the **worker**; drop the v1 "parallel features" non-goal.
- **Per-session** enforcement **unchanged** (the v1.16 monitor + Slice-2.2 caps run inside each worker, untouched).
- **B2 — pre-spawn authorization with *reservation* semantics (active, not passive, and concurrency-safe).** Aggregate caps are **per-instance** and **per-workstream**. A plain "check the live aggregate, grant/refuse" is **not** enough: two concurrent workers can both pass the check and oversubscribe the cap. So authorization is a **reservation protocol**, durable in the instance log:
  - Before each agent spawn the worker calls **reserve(workerId, estimatedSpend)**; the daemon checks `committed + outstanding-reservations + estimate ≤ cap` and emits a durable **`budget.reserve` → `budget.grant{reservationId}`** or **`budget.refuse`**. Outstanding reservations count against the cap, so concurrent reservers can't both win.
  - On the turn's `cost.update` the reservation is **finalized** (`budget.finalize{reservationId, actualSpend}` — committed total += actual, reservation cleared); a granted-but-unspent reservation is **released** on spawn failure, and **expires** via a TTL if a worker dies between grant and spawn (reconciliation reclaims it).
  - Refuse ⇒ the worker **holds** and surfaces a budget-hold (Slice-2.2's "refuse the next spawn, never kill mid-turn", lifted to the aggregate). The estimate may be coarse (e.g. the per-session cap as the reservation amount) — finalization corrects it from the real `cost.update`.
- **Fan-in.** The daemon aggregates each worker's exported `cost.update` stream (B3) into live workstream + instance totals that back both the authorization decision and the cockpit's spend view.
- **Implemented (4.3 — the minimal protocol).** Four `budget.*` instance-log events (`budget.reserve` / `budget.grant` / `budget.refuse` / `budget.finalize`) fold into `InstanceState` (cache schema **v4**): `committedUsd` + `committedByWorkstream` + a `reservations` table keyed by `workerId` (one outstanding per sequential worker; a fresh `grant` *replaces* the prior so a missing finalize cannot leak headroom; `worker.exited` releases a dead worker's reservation). The worker control RPC is **`reserve-budget` `{workerId, estimateUsd}` → `{granted, reservationId?, reason?}`**, authorized **under the single-writer gate** (`committed + outstanding-excluding-self + estimate ≤ cap`, per-workstream and per-instance); a **refuse is a success body** (`granted:false`), *not* a JSON-RPC error — the worker holds (reports a `BudgetHold` status, retries after a backoff), never killed mid-turn. **The `cost.update` fan-in is the sole writer of committed spend** (it adds the per-turn delta to the instance + workstream totals); `budget.finalize` is *implicit* on the worker's exported `cost.update` and only **releases** the estimate (carrying the actual for audit), so spend is never double-counted. Estimate granularity is the coarse O11 option = the per-piece session cap (`maxPieceCostUsd`). The §11 **driver** launches/resumes (the dominant spend) are reserved; the cheaper **reviewer** one-shots are *not* pre-reserved but are still accounted post-hoc via their `actor="reviewer"` `cost.update` (a 4.5 follow-up to pre-authorize them). Status JSON exposes `committedUsd`/`outstandingUsd` (instance + per-workstream) for the cockpit spend view (4.4). The cap policy (`BudgetPolicy`) defaults to **unlimited** in control-only/test paths (a missing cap must never *accidentally* block a worker); `InstanceConfig` persistence of the caps is a carry-forward. TTL/expiry + release-on-failed-spawn are **4.5**.

---

## 9. What stays frozen (behind the worker boundary)

Consumed as a library, unchanged except for the four B1–B4 seams:
- `Fsm.transition`, the full §11 lifecycle, the 20-variant `FsmEvent` ADT.
- The action-log **format**, `foldEvents`, replay, `RebuildState`, restart recovery (determinism). *Location* re-roots (B1); format and semantics do not.
- Branch/PR/CI/merge gates; `BranchManager`, `PRWatcher`, `CiReadiness`, `ChangeCollector`; the **FSM-driving poll loop** (stays in the worker, B4).
- Connectors (Claude/Codex), the reviewer path + `Reviewed[A]` cost-fold (Slice 2.2), the Phase-3 senses.
- Per-session budget enforcement (§12). The aggregate authorization (B2) wraps it; it does not change.

---

## 10. Decisions

**Ruled (this review round).**
- **O1 — Tool pinning:** `Forgefile`/devcontainer is the **normative** source; `RepoProfile` discovers defaults + validates. *(Tool pinning is reproducibility policy, not observed/adaptive state — so it is not `RepoProfile`'s job to own.)*
- **O2 — Wire protocol:** JSON-RPC 2.0 over the Unix socket.
- **O3/O4 — Cross-repo + log location:** design the cross-repo data model now, implement **single-repo workers first**; use an **instance-local workstream event log that references** per-repo feature logs, not replacing them (per-repo feature logs stay canonical, re-rooted per B1).
- **O5 — Daemon start:** explicit `forge daemon start` first; auto-start later.
- **O6 — Credential isolation:** a **Phase-4.3 blocker** with the §7 minimum requirements, not a vague follow-up.
- **O7 — Runtime:** abstract OCI seam, Docker-first.
- **O8 — Instance state store:** **append-only instance action log + rebuildable cache** (§6.4); **not** a single mutable instance manifest as the daemon source of truth (mirrors v1's per-feature log/cache idiom at the instance level).
- **O9 — Worker↔daemon transport:** a **dedicated worker control channel**, kept separate from the client JSON-RPC, carrying worker/daemon control, budget auth (B2), event export (B3), and credential brokering (O6). *Implemented over a Unix socket; for the **containerised** topology this crosses the boundary only on a **Linux** host — Docker Desktop for macOS blocks a host-socket bind mount (see §7 transport caveat), so a **TCP** control channel for the container topology is **deferred to 4.5**.*
- **O10 — Clone strategy:** a local **bare/reference mirror cache** is fine for fetch cost, but **each worker gets a fresh isolated *working* clone**; the cache is **never a mutable working tree**.

**Still open (flag for a later round).**
- **O11 — Reservation estimate granularity.** Per-session-cap as the reservation amount (simple, coarse) vs. a learned/profiled estimate per phase (tighter aggregate packing, more machinery). Finalization corrects either from the real `cost.update`. *Resolved for 4.3: the coarse option (per-piece session cap) ships; finalization corrects it. A learned per-phase estimate stays a 4.5 option, taken only if lived experience shows the coarse estimate wastes too much headroom.*

---

## 11. Proposed sub-slice breakdown (implementation, sliced separately)

Designed as one phase, built incrementally; each slice gets a `design-4.x.md` plan and a runnable spike first (CLAUDE.md "run code earlier").

- **4.0 — Instance scope + path re-root (B1; no daemon/containers).** `init-instance`/`add-repo`/`list-repos`; re-root local log/state/lock via `ForgePaths` to the instance dir; an existing dogfood feature runs unchanged. *Spike: re-root `ForgePaths` and re-run a prior dogfood feature green.*
- **4.1 — Daemon skeleton + JSON-RPC socket + durability core (B3/B4, §6.4).** Supervisor, instance lock, instance state store, one client, status snapshot + event subscribe for one worker; crash-recovery rebuild. *Spike: daemon serves a status snapshot + survives a restart, rebuilding from the instance log.* Proves the riskiest new contract (IPC + durability) early.
- **4.2 — Workstream/worker model + isolated clones + multiplexed supervision.** Workstream coordination object; M concurrent **single-repo workers each on its own isolated full clone** (host processes, *not* shared checkouts — the isolation premise holds pre-container); daemon supervision + cadence. *(Corrected: no shared host checkout.)*
- **4.3 — Containerised execution (O1/O6/O7) + budget authorization (B2).** OCI runtime seam (Docker-first); `Forgefile` tool pinning; **credential-isolation blocker**; the **minimal B2 reservation protocol** (reserve → grant/refuse → finalize-on-`cost.update`) + the `cost.update` fan-in. (Edge-case hardening is 4.5.)
- **4.4 — Cockpit TUI.** Multi-worker pane, attach/detach, per-worker "needs a human" flagging, container log/process inspection. Meets G5 + the exit criterion.
- **4.5 — Aggregate-budget hardening + parallel edge cases.** Hardens the B2 reservation protocol landed in 4.3 — reconciliation, reservation TTL/expiry, release on failed spawn, oversubscription edge cases — and completes per-instance/per-workstream cap wiring.
- **(deferred) 4.6 — Cross-repo coordinated workstreams (O3/O4)** — only if lived experience justifies it.

---

## 12. Re-decisions vs the v1 §22 rejections

v1.6 §22 is correct *for v1*; v2.0 reverses four for the new constraint set and **upholds** the rest:
- **Reversed:** long-running daemon (§6), parallel work via workers (§5/§8), containerised runtime (§7), multi-repo instance scope (§4).
- **Upheld:** webhooks (polling stays the trigger model); git worktrees as isolation (containers + clones instead); API-direct LLM calls (still CLI-only — the engine is frozen). Each reversal is justified by a constraint v1 didn't have (concurrency + isolation + a supervisor worth the container overhead).

---

## 13. Document conventions / status log

- Mirrors v1 spec conventions (v1.6 §23): this is the **design contract**; per-slice `design-4.x.md` files are the implementation plans with checkbox Tasks; roadmap §5 is the phase plan this doc refines.
- Section numbers are **v2.0-local** and do *not* map onto v1.6's §-numbers (v2.0 is a new outer layer). Inner references are written "v1.16 §N" / "v1.6 §N".

**Status log.**
- **v2.0 (2026-06-05)** — ratified. Synthesises roadmap §5 into the Phase-4 architecture contract; settled over three review rounds. Load-bearing decisions: the **worker** unit + cardinality (workstream 1—N worker 1—1 feature 1—1 checkout 1—1 container); the core bet as a **worker boundary** (B1 path · B2 budget-auth · B3 event-export · B4 process) around a frozen v1 FSM; committed `.forge/specs/` written in the **worker clone** and merged via PR (the registered repo is registry/input); the action log as **local canonical runtime** (not committed); the **FSM-driving poll stays in the worker** (daemon supervises, doesn't relocate it); **B2 reservation protocol** (reserve/grant/finalize, concurrency-safe) for aggregate caps; **§6.3.1** worker-as-sole-writer control serialization; **§6.4** daemon durability/recovery incl. the B2/O6 dependency; workstream "needs a human" as an aggregate **`attention` projection**; credential isolation a **4.3 blocker**. Rulings O1–O10 in §10; one open: O11 (reservation estimate granularity). Deferred: 4.6 cross-repo workstreams.
- **Slice 4.3 reconciliation (2026-06-07)** — the implemented O1/O6/O7 + B2 deltas folded back into the design text (the "Implemented (4.3)" annotations in §6.4 / §7 / §8 and the O11 resolution in §10), at the Slice-4.3 close-out per CLAUDE.md "deviation is a flag, reconcile at close". Concrete contract pins now realized: the `worker.spawned` pid-xor-`containerId` topology key + cache schema v3 (§6.4); the `forge worker … --worker-root/--socket/--container` in-container contract, the two-mount no-home-no-secret-env spec, and the `forge-worker:latest` fallback image (§7); the four `budget.*` events + `InstanceState` v4 aggregates/reservation table, the `reserve-budget` `{workerId, estimateUsd}`→`{granted, reservationId?, reason?}` RPC with **refuse-is-a-success**, the implicit `cost.update`-drives-`finalize` fan-in, and the `committedUsd`/`outstandingUsd` status fields (§8); O11 resolved to the coarse per-piece estimate. Plan + audit trail: [`design-4.3.md`](design-4.3.md). No section renumbering (annotations only); the still-open hardening (TTL/expiry, release-on-failed-spawn, reviewer pre-reservation, non-root UID mapping, `InstanceConfig` cap persistence) is 4.5.
