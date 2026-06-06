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
> `docker run`↔await-exit↔kill spike goes in front. Tasks 4.3.2–4.3.6 open.

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

- [ ] **Task 4.3.3 — credential isolation broker (O6, the blocker).** The §7
  minimum: **no host home mount** into the container; a **per-repo / per-worker
  scoped `gh` token** (not the host-wide credential — mint/scope a token the worker
  uses for its push/PR flow); a **broker / short-lived-secret injection** model for
  `claude`/`codex` auth (host-side broker over the worker control channel, or mounted
  short-lived tokens) so host-wide credentials never enter the container. This is the
  crux of the G4 safety claim; designed + landed here, not deferred.

- [ ] **Task 4.3.4 — containerised worker spawn end-to-end.** A `ContainerSpawner`
  (forge-app) adapting `OciRuntime` (4.3.1) to the supervisor's spawn path: build the
  `ContainerSpec` (image from 4.3.2; mounts = the isolated clone + the daemon control
  socket so the worker reaches the daemon from inside; env = brokered creds from
  4.3.3, **never** a host home mount), run `forge worker` inside it. The supervisor's
  reconcile (§6.4) keys liveness on the **container id** (durable in `worker.spawned`
  alongside/instead of the host pid) — `docker inspect`/`docker wait` reattach from a
  restarted daemon, the container having survived. Idempotent against a re-issued spawn
  as the host path is.

- [ ] **Task 4.3.5 — B2 budget reservation protocol + `cost.update` fan-in.** Four new
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

---

## 4. Carry-forward / deferred

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
- **Non-Docker OCI runtimes** (Podman / colima, O7) — the seam is abstract and
  Docker-first; a second backend is added only if the host fleet needs it.
- **Local bare/reference mirror clone cache** (O10) — carried from 4.2; still a
  fetch-cost optimisation, deferred unless per-worker full clone proves too slow.
