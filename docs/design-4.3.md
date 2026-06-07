# design-4.3 — Slice 4.3 implementation plan (Containerised execution + credential broker + budget authorization)

> **Maps to:** [`roadmap.md`](roadmap.md) §5 (Phase 4 — Workspace & Workstream
> platform); the ratified Phase-4 architecture contract
> [`forge-design-2.0.md`](forge-design-2.0.md) §7 (containerised execution —
> O1 tool pinning / O6 credential isolation / O7 OCI runtime seam), §8 (B2 budget
> authorization with reservation semantics + the `cost.update` fan-in), §10 (the
> O1/O6/O7/O10/O11 rulings), and §11 sub-slice **4.3**.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but **not** during a review
> round; tick the roadmap §5 sub-slice 4.3 bullet only at slice close after a
> whole-section review.
>
> **Scope note.** Slice 4.2 made a worker a **real OS host process** the daemon
> spawns + supervises on its own isolated clone (B4 process boundary,
> `WorkerSpawner`/`WorkerHandle`), running the frozen v1 loop, exporting its feed
> (B3), under the workstream model + multiplexed supervision/reconcile (§6.2/§6.4).
> Slice 4.3 puts that worker **inside an isolated OCI container** (O7: an abstract
> runtime seam, Docker-first), pins its tooling from a committed normative
> `Forgefile` (O1), and isolates its credentials from the host (O6 — the crux of
> the G4 safety claim: no host home mount, a per-worker scoped `gh` token, brokered
> short-lived `claude`/`codex` auth). It also adds the **B2 budget reservation
> protocol** (reserve → grant/refuse → finalize-on-`cost.update`, durable in the
> instance log) + the `cost.update` fan-in backing both the authorization decision
> and the cockpit spend view. It deliberately stops short of the **cockpit TUI**
> (4.4) and the **aggregate-budget hardening / parallel edge cases** (4.5 —
> reservation TTL/expiry, release-on-failed-spawn, oversubscription edges).
>
> **Status:** _open (2026-06-06)._ Task 4.3.1 (the OCI runtime seam spike) landing
> first per CLAUDE.md "run code earlier": the riskiest *new* contract in 4.3 is the
> **container runtime boundary** (O7) — an external OCI runtime the daemon shells
> out to, whose process model (detached, daemon-owned, reattach-by-id), exit-code
> capture, and kill/cleanup semantics differ from the host-process `WorkerSpawner`,
> and on which the credential-isolation (O6) and containerised-spawn (4.3.4) work
> all sit. The `Forgefile` (4.3.2), broker (4.3.3), and B2 protocol (4.3.5) are
> comparatively mechanical (a parser, a control-channel handshake, more instance-log
> events + a reservation table over the existing single-writer gate). So a runnable
> `docker run`↔await-exit↔kill spike goes in front. **Tasks 4.3.1–4.3.5 landed;
> Task 4.3.6 (proof + close-out) open.**

---

## 0. Exit criterion for Slice 4.3

Slice 4.3 is done when **`forge daemon` spawns a worker *inside an isolated OCI
container*** (Docker-first, behind the abstract runtime seam) **on its own
isolated clone, with its tooling pinned from a committed `Forgefile` and its
credentials isolated from the host** (no host home mount; a per-worker scoped
`gh` token; brokered short-lived `claude`/`codex` auth over the worker control
channel) — the worker reaches the daemon over a mounted control socket, runs the
frozen v1 loop, and **before each agent spawn obtains a budget *reservation*** from
the daemon (`reserve → grant/refuse → finalize-on-`cost.update``, durable in the
instance log), the daemon refusing a spawn that would oversubscribe the
per-workstream / per-instance cap and aggregating each worker's exported
`cost.update` into live workstream + instance totals.

Concretely, the runnable pieces, mirroring the contract's §11 sub-slice line for
4.3 (*"OCI runtime seam (Docker-first); `Forgefile` tool pinning;
credential-isolation blocker; the minimal B2 reservation protocol …"*):

1. **OCI runtime seam (Task 4.3.1, O7).** An abstract `OciRuntime` seam (`run` a
   `ContainerSpec` → a `ContainerHandle` = id + await-exit + kill, the container
   analogue of `WorkerHandle`) with a Docker-first `DockerRuntime` shelling
   `docker run -d` / `docker wait` / `docker rm -f`, proven against a **real**
   container (a `busybox sh -c 'exit N'` child) the same way Task 4.2.1 proved the
   host-process spawner against a real `sh` child.
2. **`Forgefile` tool pinning (Task 4.3.2, O1).** A committed normative `Forgefile`
   (image + pinned tool versions) — the source of truth for what image a worker's
   container runs; `RepoProfile` (Phase-3) discovers defaults + validates against
   it but does **not** own it.
3. **Credential isolation broker (Task 4.3.3, O6 — the blocker).** No host home
   mount into the container; a per-repo / per-worker scoped `gh` token (not the
   host-wide credential); a host-side broker injecting short-lived `claude`/`codex`
   auth over the worker control channel so host-wide credentials never enter the
   container.
4. **Containerised worker spawn end-to-end (Task 4.3.4).** A `ContainerSpawner`
   adapting `OciRuntime` to the supervisor's spawn path: mount the isolated clone +
   the daemon control socket into the container, inject brokered creds, run `forge
   worker` inside it; reconcile container-id-keyed liveness on daemon restart
   (§6.4, the container survives the daemon as the host process did).
5. **B2 budget reservation protocol (Task 4.3.5).** `budget.reserve` /
   `budget.grant` / `budget.refuse` / `budget.finalize` instance-log events + a
   concurrency-safe reservation table over the existing single-writer gate; the
   worker calls `reserve(workerId, estimate)` before each agent spawn, the daemon
   grants iff `committed + outstanding + estimate ≤ cap` (per-workstream /
   per-instance), finalizes on the worker's exported `cost.update`, and a refuse ⇒
   the worker holds + surfaces a budget-hold (never kills mid-turn). The
   `cost.update` fan-in feeds live workstream + instance totals.
6. **Proof + close-out (Task 4.3.6, exit).** A live `forge daemon` exercise + tests
   proving a containerised worker with isolated credentials reaches the daemon and
   completes a budget reservation cycle. Reconcile spec deltas (§23). Close-out:
   whole-section review, carry-forward walk, flip the roadmap §5 sub-slice 4.3
   bullet.

---

## 1. What stays frozen

Everything behind the worker boundary (contract §9), consumed by the worker
process **inside the container** as a library unchanged: `Fsm.transition`, the
per-feature action log format + `foldEvents`/replay/`RebuildState`/restart
recovery, the branch/PR/CI/merge gates, the connectors + reviewer + Phase-3
senses, **the per-session budget checks (§12) — the aggregate B2 authorization
wraps them, it does not change them**, the FSM-driving poll loop (stays in the
worker, B4). The **instance** log + `DaemonState` single-writer durability core
(Slice 4.1) + the reservation-gate (`DaemonState.modify`/`exclusive`, the Slice-4.2
F1 fix) are reused as-is; 4.3 only *adds* `budget.*` variants to the `sealed
InstanceEvent` (the open-`kind` record shape already tolerates them — the 4.1
header comment names `budget.*` as the worked example) and a cache `schemaVersion`
bump if the fold shape changes. The host-process `WorkerSpawner`/`WorkerHandle`
(Slice 4.2) stays; the `OciRuntime` seam slots a `ContainerSpawner` **behind the
same supervisor spawn path** (the `WorkerHandle` = pid+await+kill surface is what a
container runtime also exposes), so the supervisor is unchanged in shape.

---

## 2. Task breakdown

- [x] **Task 4.3.1 — OCI runtime seam spike (O7: the daemon runs a real container;
  await its exit; kill + clean it up).** The riskiest *new* 4.3 contract, landed
  first and runnable. The abstract seam + a Docker-first impl, proven with **real**
  container I/O (not mocks):
  - **`OciRuntime` (forge-daemon)** — `ContainerSpec{image, command, workdir, env,
    mounts, network, removeOnExit}` + `ContainerHandle{containerId, awaitExit,
    kill}` + an `OciRuntime` trait whose `run(spec): Resource[IO, ContainerHandle]`
    starts a container and (on `Resource` release) force-removes it. The container
    analogue of Slice 4.2's `WorkerSpawner`/`WorkerHandle`: the daemon-side handle
    surface (id + await-exit + kill) is identical, so a `ContainerSpawner` adapts
    onto the supervisor's spawn path (4.3.4) without changing it.
  - **`DockerRuntime` (forge-daemon)** — the Docker-first implementation, shelling
    `docker run -d …` (detached → prints the container id; the container is owned by
    the Docker daemon, so it **survives the forge daemon** exactly as a host worker
    spawned via `Resource.allocated` does — reattach is by container id, not a host
    pid), `docker wait <id>` (blocks, prints the exit code → `awaitExit`), `docker
    kill <id>` (`kill`, idempotent), `docker rm -f <id>` (the `Resource` finalizer
    cleanup). Mounts/env/workdir translate to `-v src:dst[:ro]` / `-e K=V` / `-w`.
  - The **argv builders** (`DockerRuntime.runArgs(spec)` etc.) are pure functions,
    unit-tested with **no Docker** (always-on, `<60s`): the `-v`/`-e`/`-w`/`--rm`
    flag translation + `--network`/detached shape. The **real-container** lifecycle
    test (a `busybox sh -c 'exit 7'` → id non-empty, `awaitExit == 7`; a sleeper →
    `kill` → it terminates; the finalizer removes a still-running container) is
    **opt-in** via `FORGE_IT_RUN_DOCKER=1` (it needs a Docker daemon + may pull the
    image — the `FORGE_IT_RUN_*` + `<60s` default-on discipline), mirroring
    `WorkerSpawnerSuite`'s real-`sh` tests. Proven **live** in this task per "run
    code earlier" (the full container-worker-reaches-the-daemon tie-together is the
    Task 4.3.6 dogfood).

- [x] **Task 4.3.2 — `Forgefile` tool pinning (O1).** A committed normative
  `Forgefile` model (image + pinned tool versions; reuse a devcontainer/`Dockerfile`
  where present) + a parser, the source of truth for the image a worker's container
  runs. The Phase-3 `RepoProfile` validation hook (discovers defaults + validates
  the pinned set against what it perceives — perception, **not** normative pinning).
  Resolve the image into the `ContainerSpec` the supervisor builds (4.3.4).

- [x] **Task 4.3.3 — credential isolation broker (O6, the blocker).** The §7
  minimum: **no host home mount** into the container; a **per-repo / per-worker
  scoped `gh` token** (not the host-wide credential — mint/scope a token the worker
  uses for its push/PR flow); a **broker / short-lived-secret injection** model for
  `claude`/`codex` auth (host-side broker over the worker control channel, or mounted
  short-lived tokens) so host-wide credentials never enter the container. This is the
  crux of the G4 safety claim; designed + landed here, not deferred.

- [x] **Task 4.3.4 — containerised worker spawn end-to-end.** A `ContainerSpawner`
  (forge-app) adapting `OciRuntime` (4.3.1) to the supervisor's spawn path: build the
  `ContainerSpec` (image from 4.3.2; mounts = the isolated clone + the daemon control
  socket so the worker reaches the daemon from inside; env = brokered creds from
  4.3.3, **never** a host home mount), run `forge worker` inside it. The supervisor's
  reconcile (§6.4) keys liveness on the **container id** (durable in `worker.spawned`
  alongside/instead of the host pid) — `docker inspect`/`docker wait` reattach from a
  restarted daemon, the container having survived. Idempotent against a re-issued spawn
  as the host path is.

- [x] **Task 4.3.5 — B2 budget reservation protocol + `cost.update` fan-in.** Four new
  `InstanceEvent` variants (`budget.reserve` / `budget.grant` / `budget.refuse` /
  `budget.finalize`) folded into `InstanceState` (committed totals + outstanding
  reservations, per-workstream + per-instance); a daemon `reserve` RPC over the worker
  control channel that, **under the single-writer gate** (the F1 `modify` pattern —
  concurrent reservers cannot both win), checks `committed + outstanding + estimate ≤
  cap` and records a durable grant/refuse; the worker's `WorkerReporter` calls
  `reserve(workerId, estimate)` before each agent spawn and **holds** (surfaces a
  budget-hold, never kills mid-turn — Slice-2.2's rule lifted to the aggregate) on a
  refuse; finalization on the worker's exported `cost.update` (`committed += actual`,
  reservation cleared). The **fan-in**: the daemon aggregates each worker's exported
  `cost.update` (already on the B3 feed) into live workstream + instance totals
  backing both the authorization decision and the cockpit spend view. Estimate
  granularity = the per-session cap (O11 coarse option; finalization corrects it);
  the TTL/expiry + release-on-failed-spawn edges are **4.5**.

- [ ] **Task 4.3.6 — proof + close-out (exit criterion).** A test + a live `forge
  daemon` exercise (dogfood #8) that spawns a worker **inside a container** on an
  isolated clone with isolated credentials, has it reach the daemon over the mounted
  socket and run the v1 loop, and completes a budget reservation cycle (reserve →
  grant → finalize on a real `cost.update`; a refuse → hold). Assert the worker's
  credentials never include a host home mount + the gh token is scoped. Reconcile any
  spec deltas into the live contract per §23. Close-out: whole-section review,
  carry-forward walk, flip the roadmap §5 sub-slice 4.3 bullet.

---

## 3. Status log

- **2026-06-06** — plan opened. Task 4.3.1 (the OCI runtime seam spike) landing first
  per CLAUDE.md "run code earlier": the riskiest new 4.3 contract is the container
  runtime boundary (O7) — an external OCI runtime the daemon shells out to, with a
  detached/daemon-owned/reattach-by-id process model that differs from the
  host-process `WorkerSpawner`, and on which the O6 credential isolation + the 4.3.4
  containerised spawn both sit; the `Forgefile` (4.3.2), broker (4.3.3), and B2
  protocol (4.3.5) are comparatively mechanical. So a runnable `docker run`↔await↔kill
  spike goes in front. Tasks 4.3.2 (`Forgefile` pinning), 4.3.3 (credential broker),
  4.3.4 (containerised spawn end-to-end), 4.3.5 (B2 reservation + fan-in), 4.3.6
  (proof + close-out) open.
- **2026-06-06** — **Task 4.3.1 (OCI runtime seam spike) landed.** The container side of the
  B4 boundary, the container analogue of Slice 4.2's `WorkerSpawner`/`WorkerHandle`. (1)
  **`OciRuntime` (forge-daemon)** — the abstract O7 seam: `run(ContainerSpec): Resource[IO,
  ContainerHandle]`, with `ContainerSpec{image, command, workdir, env, mounts, network, name,
  removeOnExit}`, `Mount{source, target, readOnly}`, and `ContainerHandle{containerId,
  awaitExit, kill}` — the handle surface deliberately mirrors `WorkerHandle` (id + await-exit +
  kill) so a `ContainerSpawner` adapts it onto the supervisor's spawn path unchanged (4.3.4). (2)
  **`DockerRuntime`** — the Docker-first impl, shelling `docker run -d` (detached → container
  id; owned by the Docker daemon, so it survives the forge daemon — the §6.4 premise, reattach
  by id) / `docker wait` (→ `awaitExit` exit code) / `docker kill` (idempotent `kill`) / `docker
  rm -f` (the `Resource` finalizer), via the one-shot `os.proc(...).call(...)` idiom (the
  `RealGitClient`/`RealGhClient` shape). `removeOnExit` defaults **false** (a supervised worker
  must stay reattachable + inspectable after it exits; `--rm` races `docker wait`); env keys are
  sorted for a deterministic argv. (3) **`OciRuntimeSuite`** — the pure argv builders
  (`runArgs`/`mountArg`/`waitArgs`/…, flag-order + `-v`/`-e`/`-w` translation + env sort) are
  always-on (no Docker, `<60s`); the real-container lifecycle (a `busybox sh -c 'exit 7'` →
  `awaitExit == 7`; a sleeper → `kill` → terminates; the finalizer force-removes a still-running
  container) is **opt-in** via `FORGE_IT_RUN_DOCKER=1` (needs a Docker daemon, may pull the
  image), mirroring `WorkerSpawnerSuite`'s real-`sh` tests. **Proven live** per "run code
  earlier": all 8 pass with `FORGE_IT_RUN_DOCKER=1` against a real Docker 29.2.1 daemon (exit-7
  capture, kill, finalizer cleanup — no leaked containers). `forge-daemon` 29 → 32 (3 new
  always-on + 3 opt-in-skipped by default), full `sbt test` green, `scalafmtCheckAll` clean,
  ForgePaths smell sweep passes. Tasks 4.3.2–4.3.6 open.
- **2026-06-06** — **Task 4.3.2 (`Forgefile` tool pinning, O1) landed.** The normative,
  committed source of truth for a worker container's image + pinned tool versions, in
  `forge-core` (the spine consumes it, the supervisor resolves the image — 4.3.4). (1)
  **`Forgefile` model + parser (`io.forge.core.forgefile.Forgefile`)** — `Forgefile{image,
  tools: Vector[ToolPin]}` over a minimal `Dockerfile`-flavoured directive grammar (`image
  <ref>` exactly once; `tool <name> <version>` repeatable, duplicate name rejected; `#`/blank
  lines ignored; full-line comments only, no inline). `parse` is fail-fast with a line-numbered
  diagnostic (a committed source of truth that is malformed at all is one thing to fix). (2)
  **The O1 discover-and-validate hook (`Forgefile.validate(forgefile, profile)`)** — perception,
  **not** pinning: it cross-checks the perceived tool set (`RepoProfile.buildTool` + each gate
  command's argv head) against the pinned set and returns non-fatal **warnings** (the Forgefile
  wins; the profiler only flags drift so a container missing a needed tool is surfaced before a
  worker runs blind). (3) **`ForgefileStore` seam** — `trait` + `none` + `FileForgefileStore`
  over the new `ForgePaths.forgefile` (repo-root `Forgefile`, a peer to `Dockerfile`/devcontainer,
  routed through `ForgePaths` so no call site spells the path). **Load-only** (human-authored,
  never written) with the committed-source-of-truth read policy mirrored from `FileProfileStore`:
  absent ⇒ `None`, present-but-malformed ⇒ the IO **fails loudly** (a typo'd pin must not degrade
  silently to the default image). (4) **`ForgefileSuite`** — 15 always-on tests (no Docker,
  `<60s`): parser happy-path + every error branch (line numbers, duplicates, arities, unknown
  directive), `validate` (no-warning / unpinned-build-tool / dedup), and the `FileForgefileStore`
  absent/valid/malformed read policy. `forge-core` 468 → 483, full `sbt test` green across all
  modules, `scalafmtCheckAll` clean, ForgePaths smell sweep passes. Image resolution into the
  `ContainerSpec` is wired by the supervisor in 4.3.4 (`forge-core` cannot depend on
  `forge-daemon`'s `ContainerSpec`, so the resolution is just `forgefile.image` read there);
  devcontainer/`Dockerfile` reuse where present is noted as a discovery refinement (carry-forward).
  Tasks 4.3.3–4.3.6 open.
- **2026-06-06** — **Task 4.3.3 (credential isolation broker, O6 — the blocker) landed.** The host-side broker +
  control-channel RPC that lets a containerised worker obtain short-lived, host-isolated credentials so host-wide creds
  never enter the container — the crux of the G4 safety claim. **Sourcing strategy ratified with the user**: a
  *configured per-repo PAT* for `gh` + *configured API keys* for `claude`/`codex` (keychain-OAuth brokering + API-side
  PAT minting deferred — carry-forward). (1) **`CredentialBroker` seam (forge-daemon)** — the O6 analogue of the
  [[Supervisor]] split: `brokerFor(workerId, repo): IO[Either[BrokerError, BrokeredCredentials]]`, with
  `BrokeredCredentials{env, missing}` (the secret env entries the worker injects into its *own* process env + the
  canonical names of optional agent-auth keys the host had not configured) and the canonical env-key constants
  (`GH_TOKEN`/`ANTHROPIC_API_KEY`/`OPENAI_API_KEY`). The trait lives in forge-daemon so the handler can depend on it;
  resolution lives in forge-app. `CredentialBroker.noop` mirrors `Supervisor.noop` (the pre-container daemon refuses).
  The broker is **not** a `DaemonState` writer — a brokered secret is ephemeral and must never land in the instance log.
  (2) **`broker-credentials` RPC (forge-daemon `Daemon.handler`)** — `{workerId, repo}` → `{env, missing}`, wired the
  same way `spawn-worker` threads the supervisor (default `CredentialBroker.noop`); a refusal is an `InternalError`, a
  malformed param `InvalidParams`. Secrets cross only the (mounted, in-container) control socket — never the container
  spec (`docker inspect`-safe). (3) **`RealCredentialBroker` (forge-app)** — resolves a per-repo `gh` token from the env
  var the `CredentialPolicy` names (`FORGE_GH_TOKEN` default + a per-repo override map, e.g. `→ GITHUB_LLM4S_PAT`) via a
  `SecretSource` seam (default = process env; `SecretSource.fixed` for tests), plus best-effort agent keys. **Refuses
  (`BrokerError.MissingRequiredSecret`) rather than falling back to the host-wide `gh` login** when the scoped token is
  unset/empty — the O6 posture. A set-but-empty value reads as absent. (4) **`WorkerReporter.brokerCredentials(repo)`**
  — the worker-side request over the control channel (real worker order: register, then broker), returning the env map
  (a contract-violating wire body raises). Wired into `DaemonCommands.runForeground` via `RealCredentialBroker.default`.
  (5) **Tests** — `DaemonBrokerCredentialsSuite` (4 daemon-routing tests, mirroring `DaemonSpawnWorkerSuite`) +
  `CredentialBrokerSuite` (11: resolution incl. per-repo override / refuse-don't-fall-back / empty-as-absent,
  `parseCredentials` wire decode, and an end-to-end reporter↔in-process-daemon round-trip). `forge-daemon` +1 suite,
  `forge-app` +1 suite; full `sbt test` green (Total 543 forge-app + all modules), `scalafmtCheckAll` clean, ForgePaths
  smell sweep passes. The **no-host-home-mount** property + the worker *applying* the brokered env (and the actual
  container wiring) land with the `ContainerSpawner` in **4.3.4**; the broker existing (creds over the channel) is what
  *makes* the no-home-mount possible. Tasks 4.3.4–4.3.6 open.
- **2026-06-06** — **Task 4.3.4 (containerised worker spawn end-to-end) landed.** The daemon now spawns a worker
  **inside an isolated OCI container** behind the same supervisor spawn path the host process used, reconciling liveness
  by **container id** across a daemon restart. (1) **Liveness identity generalised (schema v2 → v3).**
  `InstanceEvent.WorkerSpawned.pid` became `Option[Long]` + a new `containerId: Option[String]`; `WorkerRecord` gained
  `containerId`, and `live = exitCode.isEmpty && (pid.isDefined || containerId.isDefined)` — a host worker records a
  `pid`, a containerised worker a `containerId` (the honest "one of two keys, by topology" model; `payloadOf`/`decode`
  emit/read only the key that applies). (2) **`OciRuntime` reattach surface (forge-daemon)** — `attach(id):
  ContainerHandle` (rebuild a handle from a recorded id, no `run`) + `running(id): IO[Boolean]` (`docker inspect -f
  '{{.State.Running}}'`), the container analogue of `ProcessHandle.of(pid)` for the §6.4 restart probe. (3) **A
  `WorkerRuntime` seam (forge-app)** unifying the two topologies behind `launch → LaunchedWorker{key, awaitExit, kill}`
  with `LivenessKey = HostPid | ContainerId`: `HostProcessRuntime` wraps the 4.2 `WorkerSpawner`/`WorkerLauncher`;
  `ContainerRuntime` resolves the worker image from the **clone's** `Forgefile` (O1, falling back to a default image),
  builds the `ContainerSpec` (the worker root + the daemon control socket bind-mounted in at fixed in-container paths,
  **no host home mount** — O6, no env secrets — `docker inspect`-safe), and runs `forge worker … --worker-root --socket
  --container` inside it via the abstract seam. (4) **`RealSupervisor` refactored** onto `WorkerRuntime` + an injected
  `OciRuntime`: the single-writer allocation gate / reservation table / idempotency are untouched; only the spawn
  mechanism + the recorded key differ, and `reconcile`/cadence **dispatch on each record's key** (a host pid via
  `ProcessHandle`, a container via `OciRuntime.running`/`attach`) so a daemon restarted in *either* mode reconciles
  *both* topologies it finds in the log. (5) **Worker container mode** — `WorkerCommand` gained optional
  `--worker-root`/`--socket` + `--container`; in container mode `WorkerCommands` skips the instance-store load (the
  instance dir is not in the container), uses the explicit mounted paths, and `WorkerLoop` brokers its credentials over
  the control channel (4.3.3) and threads them as an env overlay through `OrchestratorBuilder` → `RealGhClient.env` +
  the connectors' `extraEnv` (no forge-agents change; both connectors already take a defaulted `extraEnv`). (6) **`forge
  daemon start --container`** selects `ContainerRuntime` for new spawns; the default stays the frozen host-process path.
  **Tests:** always-on `ContainerRuntimeSuite` (spec builder — mounts, no-home-mount, `forge worker` argv,
  Forgefile-image-or-default) + `SupervisorContainerReconcileSuite` (reconcile dispatch on a stub `OciRuntime`) +
  `WorkerLoopBrokerSuite` (brokers iff container mode); **opt-in `FORGE_IT_RUN_DOCKER=1`** real-container reattach
  (busybox sleeper stand-in survives a simulated daemon crash, a fresh supervisor reconciles by container id from the
  log alone, killing it records the exit) — **proven live** against the host's Docker 29.2.1 (the new `OciRuntime`
  `attach`/`running` lifecycle + the supervisor crash/reconcile, no leaked containers). forge-app 543 → 553 (10 new, 1
  opt-in-skipped by default), forge-daemon +2 opt-in, full `sbt test` green, `scalafmtCheckAll` clean, ForgePaths smell
  sweep passes. **Spec deltas (§23) to fold into the live contract at 4.3.6 close-out:** the `worker.spawned`
  pid→`containerId` generalisation + the `--container`/`--socket`/`--worker-root` worker contract + the
  `forge-worker:latest` default image / forge-capable-image expectation. The full container-worker-reaches-the-daemon +
  real feature loop tie-together (a built forge-capable image) is the Task 4.3.6 dogfood. Tasks 4.3.5–4.3.6 open.
- **2026-06-06** — **Task 4.3.5 (B2 budget reservation protocol + `cost.update` fan-in) landed.** The aggregate budget
  layer *above* the frozen §12 per-session checks: a worker obtains a fleet-wide reservation before each agent spawn,
  the daemon refusing one that would oversubscribe the cap, and each worker's exported `cost.update` fanning into live
  totals. (1) **Four `budget.*` `InstanceEvent` variants (forge-instance)** — `budget.reserve`/`grant`/`refuse`/`finalize`
  (codec + decode + the open-`kind` no-op tolerance the 4.1 header already promised), folded into a new
  `InstanceState` shape (**schema v3 → v4**): `committedUsd` + `committedByWorkstream` (keyed by ws-id string) +
  a `reservations` table (keyed by `workerId` — one outstanding per sequential worker; a fresh `grant` *replaces* the
  prior, so a missing finalize cannot leak headroom). `grant` adds outstanding; `finalize` clears it (reservation-id
  matched; a stale id is a no-op); `worker.exited` releases a dead worker's reservation. (2) **The `cost.update` fan-in
  is the sole writer of committed spend** — `WorkerEvent`'s fold detects an exported §19 `cost.update`
  (`InstanceEvent.costUpdateUsd`) and adds its per-turn `usd` delta to the instance + workstream committed totals, so
  `finalize` only *releases* the estimate (carrying `actualUsd` for audit) and never double-counts. (3) **`BudgetPolicy`
  (forge-daemon)** — the pure per-workstream / per-instance cap decision (`committed + outstanding-excluding-self +
  estimate ≤ cap`), with `default` (generous: $200 ws / $1000 instance) and `unlimited` (the safe control-only / test
  default — a missing budget cap must never *accidentally* block a worker, unlike `CredentialBroker.noop` which refuses).
  Constructed-default posture mirrors the 4.3.3 `CredentialPolicy`; `InstanceConfig` persistence is a carry-forward.
  (4) **`reserve-budget` RPC (`Daemon.handler`)** — `{workerId, estimateUsd}` → authorize **under the single-writer
  gate** (`DaemonState.modify`, so two concurrent reservers cannot both win) → durable `budget.reserve` +
  `grant`/`refuse`, answering `{granted, reservationId?, reason?}`. A **refuse is a success body** (`granted:false`),
  not a JSON-RPC error — the worker holds, never fails. `worker-event` switched to `modify` so a `cost.update` for a
  worker holding a reservation emits a `budget.finalize` atomically with the event. (5) **Worker side (forge-app)** —
  a `BudgetReserver` seam (`noop` for the non-daemon `forge run`, byte-identical to pre-4.3.5) wrapping each
  `RealSideEffects` driver launch/resume (`reserving { … }`, the §8 wrap the frozen FSM never sees); the daemon-backed
  `ReportingBudgetReserver` phones `reserve-budget` and **holds** on a refuse (reports a `BudgetHold` status, retries
  after a backoff — Slice-2.2's never-kill-mid-turn rule lifted to the aggregate); `WorkerReporter.reserveBudget`
  (refuse decodes to `ReservationOutcome.Refused`, only a transport error raises); `WorkerLoop` injects it with the
  coarse O11 estimate = `config.maxPieceCostUsd`; `DaemonCommands` passes `BudgetPolicy.default`. (6) **Status JSON**
  exposes `committedUsd`/`outstandingUsd` (instance + per-workstream) for the cockpit spend view (4.4). **Tests:**
  `BudgetReservationSuite` (codec round-trip + fold: grant/finalize/replace/exit-release + `cost.update` fan-in +
  status JSON), `BudgetPolicySuite` (cap decision incl. exclude-self + no-workstream + unlimited), `DaemonReserveBudgetSuite`
  (RPC grant/refuse-is-a-success/`InvalidParams` + the `cost.update`-finalizes-and-commits round-trip over an in-process
  daemon), `BudgetReserverSuite` (grant proceeds / refuse holds-and-retries / `parseReservation`), + `RealSideEffectsSuite`
  reserve-before-spawn. `forge-instance` 55→63, `forge-daemon` 37→48, `forge-app` 543→558; full `sbt test` green,
  `scalafmtCheckAll` clean, ForgePaths smell sweep passes. **Spec deltas (§23) to fold into the live contract at 4.3.6
  close-out:** the four `budget.*` instance-log events + the `InstanceState` v4 budget aggregates/reservation table; the
  `reserve-budget` RPC wire (`{workerId, estimateUsd}` → `{granted, reservationId?, reason?}`) + the refuse-is-a-success
  convention; the implicit `cost.update`-drives-`finalize` fan-in; the coarse O11 estimate = per-piece cap; the status
  JSON `committedUsd`/`outstandingUsd` fields. The live container-worker budget-cycle tie-together is the Task 4.3.6
  dogfood. Task 4.3.6 open.
- **2026-06-07** — **Task 4.3.6 prerequisites landed (proof-side; the live dogfood + close-out review remain).** The
  build/test/doc deliverables that unblock the exit-criterion exercise: (1) **the default forge-worker image**
  (`docker/forge-worker/Dockerfile` + `forge` launcher + `README`, built by `scripts/build-forge-worker-image.sh`) — a
  `eclipse-temurin:21-jre` base bundling the assembled `forge.jar` + `git`/`gh`/`claude`/`codex` on PATH, the missing
  `forge-worker:latest` fallback. **Built + verified live** against the host Docker 29.2.1 (git 2.53.0, gh 2.93.0, claude
  2.1.150, codex-cli 0.133.0, `forge worker` argv recognized). (2) **`WorkerDaemonHandshakeSuite`** (forge-app,
  always-on) — the worker-side tie-together through the real `WorkerReporter` + `ReportingBudgetReserver` in `WorkerLoop`'s
  order over one served daemon: register → broker host-isolated creds → reserve→grant→proceed → `cost.update`→finalize
  (outstanding cleared, actual committed), plus a tiny-cap **refuse → hold** (never proceeds, reports `BudgetHold`). (3)
  **Spec reconciliation (§23)** of the 4.3.1–4.3.5 deltas into `forge-design-2.0.md` (the "Implemented (4.3)" notes in
  §6.4/§7/§8, O11 resolved, a §13 status-log entry). (4) **Dogfood #8 runbook** ([`dogfood/4.3-container.md`](dogfood/4.3-container.md)).
  **Key finding (→ 4.5, ratified with the user):** a container cannot reach a *host-created* Unix socket over a bind mount
  on Docker Desktop for **macOS** (`Errno 95`; only a *shared-volume* socket between two in-VM containers crosses), so the
  containerised worker↔daemon control channel runs only on a **Linux** host today — re-architecting it to **TCP** is
  deferred to 4.5 and **dogfood #8 runs on Linux**. forge-app +1 always-on suite (2 tests); full `sbt test` green,
  `scalafmtCheckAll` clean, ForgePaths smell sweep passes. **Task 4.3.6 stays open** pending the live Linux dogfood + the
  whole-section close-out review (carry-forward walk + roadmap §5 sub-slice 4.3 flip).

---

## 4. Carry-forward / deferred

- **Container control-channel transport on macOS → TCP (4.5).** Found in the 4.3.6
  dogfood prep: a container **cannot** connect to a *host-created* Unix socket over a
  bind mount on Docker Desktop for macOS (`Errno 95 Operation not supported` — the VM
  boundary; only a socket on a *shared Docker volume* between two in-VM containers
  crosses). The current `ContainerRuntime` bind-mounts the daemon's host Unix socket
  into the worker container, which works on a **Linux** host but not on the macOS dev
  host (contract §7 "host is macOS"). **Ratified with the user (2026-06-07):**
  re-architect the worker control channel to **TCP** for the container topology (so it
  crosses the macOS VM boundary cleanly) — **deferred to 4.5**; **dogfood #8 runs on a
  Linux host** in the meantime. The host-process (4.2) topology is unaffected. See
  [`design-rationale.md`](design-rationale.md) and the [`dogfood/4.3-container.md`](dogfood/4.3-container.md)
  runbook's host-platform note.
- **Cockpit TUI** (§6.3 — multi-worker panes, attach/detach, per-worker "needs a
  human" flags, container log/process inspection) is **4.4**. 4.3 exposes the
  containerised worker's status + the `attention` projection + the aggregate spend
  totals over the existing client RPCs; the TUI renders them later.
- **Aggregate-budget hardening + parallel edge cases** is **4.5**: the B2 reservation
  TTL/expiry, release-on-failed-spawn, reconciliation reclaim of a grant whose worker
  died between grant and spawn, and the oversubscription edge cases. 4.3 lands the
  *minimal* reserve → grant/refuse → finalize protocol; 4.5 hardens it.
- **O11 — reservation estimate granularity.** 4.3 uses the per-session cap as the
  reservation amount (the simple, coarse option; finalization corrects it from the
  real `cost.update`). A learned/profiled per-phase estimate (tighter aggregate
  packing) is deferred unless lived experience shows the coarse estimate wastes too
  much headroom.
- **Worker feed-resumption on daemon restart** (§6.4(d), carried from Slice 4.2) —
  durable worker-side offsets + replay from the last-acked offset — is coupled to this
  slice's B2/O6 recovery handshake; revisit whether it lands here (with the reservation
  reconciliation) or stays a 4.5 hardening item once 4.3.4/4.3.5 land.
- **Devcontainer / `Dockerfile` reuse where present** (O1) — Task 4.3.2 landed the
  normative `Forgefile` (the source of truth for the image) but **not** the "reuse a
  committed devcontainer/`Dockerfile` where present" fallback the §7 ruling allows. That
  fallback means discovering such a file and `docker build`-ing an image from it (a build
  step, not a pin), which is heavier than the parser this task scoped. Deferred: the
  supervisor (4.3.4) resolves `Forgefile.image`, falling back to a default image when no
  `Forgefile` is committed; devcontainer/`Dockerfile` discovery + build is added only if a
  dogfood repo needs it.
- **Credential broker — heavier sourcing strategies (O6, from Task 4.3.3).** 4.3.3
  landed the broker seam + RPC with the user-ratified *pragmatic* sourcing: a
  configured per-repo PAT env var for `gh` and configured API-key env vars for
  `claude`/`codex`. Three follow-ups, none blocking the slice:
  - **Fine-grained PAT minting via the GitHub API** — mint a short-lived, repo-scoped
    token at broker time instead of reading a pre-provisioned PAT env var. Needs a
    stored owner/admin token + API plumbing; the `SecretSource` seam is where it slots.
  - **Keychain-OAuth brokering for `claude`/`codex`** — extract the host's logged-in
    OAuth session (macOS keychain / `~/.claude.json` / `~/.codex`) and vend it as a
    short-lived secret, so a containerised run does not require an API key to be set.
    Host-specific + security-sensitive; deferred until a dogfood needs key-free runs.
  - **`CredentialPolicy` persistence in instance config** — the policy (env-var *names*,
    the per-repo override map — never secret values) is a constructed default today
    (`RealCredentialBroker.default`); persist it in `instances/<name>/config.json` so the
    per-repo PAT mapping is configurable without a code change. A clean §-bump to
    `InstanceConfig` (currently identity-only).
- **No-host-home-mount enforcement (O6) is wired in 4.3.4.** 4.3.3 makes the no-home-mount
  *possible* (creds flow over the control channel, not a mount); the `ContainerSpawner`
  (4.3.4) builds the `ContainerSpec` that omits any host-home mount and has the worker
  *apply* the brokered env in-process, and 4.3.6 asserts it live (no `~`/home mount; gh
  token scoped).
- **Reviewer-spawn reservation (B2, from Task 4.3.5).** 4.3.5 wraps the §11 **driver**
  launches/resumes (the dominant spend) with the `reserve-budget` handshake; the **reviewer**
  one-shots (a separate `ReviewerCall` seam, cheaper) are **not** pre-reserved. Reviewer spend
  is still fully accounted — it fans into the committed totals via its `actor="reviewer"`
  `cost.update` export (contract 1.16) — so the cap is enforced *after* a reviewer turn, not
  *before* it. Pre-authorizing reviewer spawns (and the `budget.refuse`→hold on the reviewer
  path) is a small follow-up, deferred unless a reviewer-heavy run shows the unreserved spend
  matters; the minimal protocol holds the line on the expensive driver sessions.
- **Non-Docker OCI runtimes** (Podman / colima, O7) — the seam is abstract and
  Docker-first; a second backend is added only if the host fleet needs it.
- **Local bare/reference mirror clone cache** (O10) — carried from 4.2; still a
  fetch-cost optimisation, deferred unless per-worker full clone proves too slow.
