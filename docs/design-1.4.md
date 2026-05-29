# design-1.4 — Slice 1.4 implementation plan

> **Maps to:** [`roadmap.md`](roadmap.md) §2.4 (Phase 1 / Slice 1.4 —
> Reviewer assets + `forge-specs` (1.4a) → headless orchestrator + REPL
> (1.4b)) and [`forge-design-1.2.md`](forge-design-1.2.md) §17 slice 4
> deliverables.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. The roadmap stays terse — direction and exit criteria — and
> this file owns the per-task breakdown. Items get ticked off here as they
> land; the roadmap section gets ticked off only after a code review on
> the section as a whole.
>
> **Status:** 🟢 open — 2026-05-27. Slice 1.4 is the largest of Phase 1
> and the MVP-gate that closes Phase 1. Split dependency-shaped into
> **1.4a** (reviewer assets, `forge-specs` repopulation, Task 1.4.7
> regression gate — writable foundation) and **1.4b** (orchestrator
> loop, CLI, self-hosting gate — built on top of 1.4a). 1.4a's Tasks
> are **Task 1.4.1 → Task 1.4.8**; 1.4b's are **Task 1.4.9 → Task 1.4.17**. Inherited
> carry-forwards from Slices 1–3 (**C14**, **C15**, **S2-1**
> through **S2-10**, **S3-1** through **S3-8**) are walked in §4
> against the Task each one resolves in.

## 0. Exit criterion for Slice 1.4

Roadmap §2.4 splits this slice in two: 1.4a ships reviewer assets,
`forge-specs` (re)population, and the Task 1.4.7 regression gate; 1.4b
ships the orchestrator loop, line-mode REPL, and drives a small
real feature end-to-end through Forge — the **MVP gate** that
closes Phase 1.

### 0.1 1.4a exit criterion

1.4a is done when:

1. Reviewer assets ship at the v1.2 §17 / §10.2 / §14.3 paths:
   - `~/.forge/schemas/{design-review,code-review,refine}.json` —
     the per-method JSON Schemas Claude binds via `--json-schema
     '<json>'` and Codex binds via `--output-schema <path>` (per
     `forge-agents/ReviewerAssets.scala`).
   - `~/.forge/prompts/<role>-<method>.<reviewer>.md` — per-CLI
     reviewer system-prompt files (one per reviewer × method).
     Claude reads via `--system-prompt-file <path>`; Codex
     prepends via `CodexPrompt.withSystemBlock` (§7.10(a)).
   - `~/.forge/templates/{pr-body,decomposition,*-answers}.md.hbs`
     — PR body / decomposition / Q&A answer templates per §11.4 /
     §7.7. The bot rewrites these as a feature progresses.
2. `forge-specs` exposes:
   - `SpecStore` — `manifest.json`, `design.md`, `decomposition.md`
     persistence + atomic write (§4 source-of-truth invariant).
     Wraps the relocated `Manifest` / `ManifestPatch` types in
     `forge-core` (**S2-1**) — `forge-specs` owns persistence,
     `forge-core` owns the domain.
   - `DocSync` — rewrites `decomposition.md` from `manifest.json`
     per **M1** / §5.3. Idempotent; no in-place Markdown parsing.
   - `ChangeCollector` — Allow / Deny / Ask classification per
     §10.1; deny-pattern globbing against `config.staging.*` from
     §18; Slice 1.1's `Connector`-internal answer routing
     (§10.4) bridges the Ask path.
3. `forge-app` exposes:
   - Reviewer-call wrappers — `ReviewerCall.designReview /
     prReview / refine` in `io.forge.app.reviewer`, each owning
     the one-shot wall-clock cap that closes the SessionMonitor
     reviewer/refine carve-out (**S2-8** / **S3-5**). Wraps the
     Slice 1.1 `forge-agents.Reviews.*` adapter methods; surfaces
     `ReviewerOutcome.Timeout` on cap breach (no
     `MonitorOutcome` boundary — that belongs to Slice 1.3's
     driver-phase `SessionMonitor`). The orchestrator (Slice 1.4b
     Task 1.4.10) maps `ReviewerOutcome.Timeout` to either
     `FsmEvent.SettleTimeout(SessionPhase.{DesignReview,
     CodeReview, Refine}, _)` (Task 1.4.12 option a) or
     `FsmEvent.HarnessError(...)` (option b).
4. **Task 1.4.7 regression suite (C15)** lands as a gating check on
   1.4a close — ≥19/20 native schema bar on `reviewDesign /
   reviewPr / refine` against each connector, against shipped
   reviewer assets. Suite lives in `forge-it`; default-off
   opt-in via `FORGE_IT_RUN_REGRESSION=1` per the
   default-on-`<60s` runtime budget (mirrors the reliability-suite
   pattern in Slice 1.1). Failure → schema/prompt
   tightening inside 1.4a, not deferred further.
5. A 1.4a close-out review (Task 1.4.8) walks the §4 carry-forward
   bucket for "resolved-in-1.4a" items (**C15**, the asset-shape
   half of **C14**, the partial **S2-8** / **S3-5** carve-out
   if 1.4a's wall-clock wrappers fully close it). Items that
   remain carry-forward (**S2-5**, the orchestrator half of
   **C14**, **S2-8** if reviewer wrappers don't fully close it)
   stay open for 1.4b.

### 0.2 1.4b exit criterion (Phase 1 exit gate)

1.4b is done when:

1. `forge-app` exposes:
   - `forge-app/main` — actual `IOApp` entry point. Routes the
     full §17 / §15 command set:
     `forge new | spec | run | status | resume | reconcile |
     refresh-cache | abandon | rebuild-state | unlock --force
     | tail`. (`forge tail` is added as a §2.5 polish item —
     see **Task 1.4.9 I3** below for the `ForgeCommand.ReadOnlyKind`
     extension.) CLI parsing in `io.forge.app.cli`;
     per-command handler in `io.forge.app.command`.
   - `Orchestrator` — the headless feature loop. Reads `Feature`
     via `RebuildState.run`, drives `Fsm.transition` against
     `FsmEvent`s produced by `BranchManager`, `PRWatcher`,
     `SessionMonitor`, the reviewer-call wrappers, and user
     commands. Persists each transition via `FileActionLog.append`
     + `FileStateCache.save` per the §11.0 step 4 pipeline.
     Atomically persists `manifest.json` mutations per §11.4
     step 1 / §11.5 step 1 (closes **S2-5**).
   - Line-mode REPL (no TUI) for `forge spec <feature>` —
     bidirectional channel into a `runStreamingSpec` session,
     forwarding `AskUserQuestion` events to the human and
     answers back via `session.answerQuestion(toolUseId,
     answer)`.
2. Every `NeedsHumanIntervention` reason renders human-readably
   with its `ResumeHint` and the exact `forge resume --<hint>`
   the operator should run (six paths per §2.5 polish list).
3. `forge status [<feature>]` prints current state, active
   piece, last action, budget remaining at a glance.
4. `forge tail <feature>` tails `.forge/log/<feature>.jsonl`.
5. `forge rebuild-state <feature>` is proven on a deliberately
   corrupted cache (test or hand-verified).
6. C14 closure — the orchestrator's Codex resume code path
   either re-issues role framing in the resume message or
   calls a widened trait signature (whichever v1.3 chose).
7. S2-5 writer-side atomic-merge test asserts the orchestrator
   atomically persists `manifest.json` before the FSM transition
   action and the state-cache write (§11.5 step 1 writer side).
8. S2-8 / S3-5 reviewer-phase settle path: either explicit
   `Fsm.transition` handlers for `SessionPhase.{DesignReview,
   CodeReview, Refine}` (with `ResumeHintCoverageSuite` rows) or
   documented orchestrator-side conversion to `HarnessError`
   events. 1.4a's reviewer wrappers may have already partially
   closed this; 1.4b verifies.
9. **MVP gate — drive one small, concrete, low-variance feature
   through Forge end-to-end on this repo.** Pick a contained
   target (a small `forge-git` helper, a narrow `forge-app`
   reporting/replay feature, or one of the v1.3 spec-text
   corrections with tests). The artefact is one merged feature
   PR series through Forge, with the action log preserved as
   evidence in `docs/slice-4/`. **Do not** pick "have Forge
   build its own Slice 5 (TUI)" — TUI is a subjective UX loop,
   the wrong shape for a first self-hosted run.
10. A code review on the section confirms (1)–(9) and that the
    §4 carry-forward list is durably handed off; the `[~]`
    bullet in `roadmap.md` §2.4 flips to `[x]`. Phase 1
    closes; Phase 2 (MLP / TUI) opens.

## 1. Task breakdown

Seventeen numbered Tasks across the two halves. **1.4a: Task 1.4.1 →
Task 1.4.8. 1.4b: Task 1.4.9 → Task 1.4.17.** The dependency graph is mostly linear
within each half (`A → B → … → H` for 1.4a; `I → … → Q` for 1.4b)
with the 1.4a → 1.4b handoff at Task 1.4.8. §2 names the safe
parallelisation points but follows Slice 1.3's discipline of
landing serially to keep review surface manageable.

The plan below pins the first-pass checklist; Tasks may be
combined or split during execution as PRs land and the
landscape becomes clearer. The carry-forward in §4 names which
Task is the durable home for each inherited item.

---

### Slice 1.4a — Reviewer assets, `forge-specs` repopulation, regression gate

### Task 1.4.1 — Reviewer schemas + system prompts under `~/.forge/`  ✅ landed 2026-05-28

The reviewer-asset shipping foundation. Slice 1.1 wired
`ReviewerAssets(PerMethod(schema, systemPrompt))` through both
connectors and the `Reviews.scala` ADT; the actual files don't
yet exist on disk anywhere. Task 1.4.1 lands them as a versioned set
under `assets/reviewer/` in-tree, with an installer wired into
`forge-app`'s bootstrap so first-run populates
`~/.forge/{schemas,prompts}/` from the in-tree copies.

- [x] **A1.** In-tree asset layout under
  `assets/reviewer/`:
  - `schemas/design-review.json`, `code-review.json`,
    `refine.json` — JSON Schemas matching the
    `DesignReview` / `PrReview` / `RefineResult` Scala ADTs in
    `forge-agents/Reviews.scala`. Per-field types, required
    fields, enum values for `verdict` / `outcome`. The schemas
    should be tight (each method's bar is ≥19/20 native
    enforcement — the schema is the contract the CLI's JSON
    output must satisfy). Cross-reference Slice 0 transcripts
    under `docs/slice-0/transcripts/` for the exact wire
    shapes Claude and Codex emit.
  - `prompts/design-review.claude.md`,
    `design-review.codex.md`, `code-review.claude.md`,
    `code-review.codex.md`, `refine.claude.md`,
    `refine.codex.md` — six per-method × per-reviewer system
    prompts. v1 placeholders; iterate post-MVP per §3.2
    (Phase 2 prompt iteration).
  - Templates: `assets/templates/pr-body.md.hbs`,
    `decomposition.md.hbs`, `design-pr-feedback-r<n>-answers.md.hbs`,
    `spec-answers.md.hbs`, `impl-answers.md.hbs`,
    `fixup-r<attempt>-answers.md.hbs` per §11.4 / §7.7 / §14.3.
- [x] **A2.** `io.forge.app.bootstrap.AssetInstaller` —
  `installIfMissing(home: os.Path, paths: ForgePaths): IO[Vector[InstalledAsset]]`.
  Copies in-tree assets into `~/.forge/schemas/`,
  `~/.forge/prompts/`, `~/.forge/templates/` on first run; no-op
  if target file exists (operator may have customised).
  Per-asset `InstalledAsset(source, dest, action: Installed |
  Skipped)` so the bootstrap can log what it did. Lives in
  `forge-app` (where the entry point will live in Task 1.4.9).
- [x] **A3.** Unit suite under
  `modules/forge-app/src/test/scala/io/forge/app/bootstrap/`:
  - `AssetInstallerSuite` — first-run installs every asset;
    re-run skips existing files; permission-error surfaces as
    typed error; partial install of a subset (some skipped,
    some installed) round-trips correctly.
  - Schema-shape sanity: `DesignReviewSchemaShapeSuite` /
    `PrReviewSchemaShapeSuite` / `RefineSchemaShapeSuite`
    parse each shipped schema, walk its keys, and assert each
    field listed in the Scala ADT appears in the schema's
    `required` array. Catches drift where someone adds a Scala
    field but forgets to update the JSON Schema.
- [x] **A4.** Wire `design-1.4.md` into the parent docs:
  - `AGENTS.md` §"Active design-`<section>`.md files" — replace
    *(none currently open)* with the design-1.4.md pointer.
  - `CLAUDE.md` TL;DR "Active implementation plan" — replace
    *(none currently open)* with the design-1.4.md pointer.
    Bump "Current state" to mark Slice 1.4 active.
  - `roadmap.md` §2.4 — add a 🟢 "Slice 1.4 open — 2026-05-27"
    status block pointing at `design-1.4.md` with Task 1.4.1 as the
    entry point. Mirror Slice 1.3's open-section pattern.
- [x] **A5.** Task 1.4.1 landing checklist:
  - `sbt clean compile` clean under `-Xfatal-warnings`.
  - `sbt test` green; baseline test counts from Slice 1.3 close
    (`forge-core` 358 / `forge-agents` 181 / `forge-git` 168
    / `forge-app` 46) preserved or grown.
  - `sbt scalafmtCheckAll` clean.
  - `ForgePathsSuite`'s `os.walk` sweep still green (Task 1.4.1
    only adds in-tree assets and a `forge-app` bootstrap
    helper — no new `.forge` literals outside `ForgePaths`).
  - This file's Task 1.4.1 header flipped to "✅ landed" and a §3
    status-log entry added.

### Task 1.4.2 — Reviewer-call wrappers + wall-clock cap (closes S2-8 / S3-5 partially)  ✅ landed 2026-05-28

Slice 1.3's `SessionMonitor` deliberately excluded the
reviewer/refine phases (§17 slice 3 — only the four driver
phases have a `Stream[IO, AgentEvent]` to monitor; reviewer
calls are one-shot blocking IO). 1.4a's reviewer wrappers
colocate the wall-clock cap with the one-shot adapter call.

**Boundary note — what the wrapper cannot see.** The current
`Connector` surface
(`modules/forge-agents/src/main/scala/io/forge/agents/Connector.scala`)
exposes reviewer methods as `reviewDesign(input):
IO[DesignReview]` / `reviewPr(input): IO[PrReview]` /
`refine(input): IO[RefineResult]`. No cost is returned, no
subprocess handle is exposed, and failures surface as
subclasses of `ReviewerError` (a `RuntimeException`-flavoured
sealed trait — `ReviewerProcessFailure`,
`ReviewerNotConfigured`, `StructuredOutputMissing`,
`StructuredOutputMalformed`) raised into the IO, not in an
`Either` channel. **Slice 1.4a keeps that boundary as-is** —
widening `Connector.reviewDesign` / `reviewPr` / `refine` to
return cost + a kill handle would ripple through every
existing connector suite plus the Task 1.4.7 regression suite, for
no measurable gain on a one-shot blocking adapter call. The
wrapper therefore wraps what's there:

- [x] **B1.** `io.forge.app.reviewer.ReviewerCall` trait:
  ```scala
  trait ReviewerCall:
    def designReview(input: DesignReviewInput,
                     limits: ReviewerLimits): IO[ReviewerOutcome[DesignReview]]
    def prReview(input: PrReviewInput,
                 limits: ReviewerLimits): IO[ReviewerOutcome[PrReview]]
    def refine(input: RefineInput,
               limits: ReviewerLimits): IO[ReviewerOutcome[RefineResult]]
  ```
  `ReviewerLimits(wallClockTimeout: FiniteDuration)` — per-call
  wall-clock cap only. **No `maxCostUsd` field** in the limit
  type: the underlying `Connector.review*` methods return only
  the typed review value, do not surface cost on the reviewer
  path, and the one-shot reviewer collectors in `forge-agents`
  (`extractStructuredOutput` / `extractAgentMessageText` paths
  used by `reviewDesign` / `reviewPr` / `refine`) **do not
  emit `AgentEvent.CostUpdate`** the way the streaming driver
  pipeline does. Slice 1.4a therefore makes per-reviewer-call
  cost enforcement **explicitly out of scope** and files a
  new carry-forward **S4-3** in `design-rationale.md` and §4
  below. Closing S4-3 requires either widening
  `Connector.review*` to return `IO[(A, Cost)]` (or
  `IO[ReviewerCall.Settled[A]]`) **or** plumbing
  `AgentEvent.CostUpdate` through the one-shot reviewer
  collectors so the orchestrator's `Feature.cost` projection
  picks them up; either is a real connector-boundary change
  that 1.4a deliberately defers. **Until S4-3 closes, reviewer
  spend does not contribute to `feature.cost` / `piece.cost`
  budget caps** — the §12 check 1/2 budgets remain a
  driver-session-only invariant for v1, and the four reviewer
  / refine calls per piece are assumed bounded by the
  wall-clock cap Task 1.4.2 does enforce.

  `ReviewerOutcome[A]` is a sealed trait:
  - `Settled(result: A)` — clean return. No `cost: Cost`
    field for the reasons above.
  - `Timeout` — the wall-clock cap fired before the
    underlying `connector.review*` IO completed. **No
    `killError: Option[String]` field.** `Connector.review*`
    exposes no subprocess handle; the wrapper only has fiber
    cancellation. On cap fire, the wrapper cancels the
    in-flight reviewer IO and lets its enclosing connector
    `Resource` finalizer release the underlying subprocess.
    Whether that release reliably issues SIGTERM/SIGKILL
    against the CLI subprocess depends on connector
    internals (Slice 1.1's `Subprocess` honours fiber
    cancellation, but the reviewer collector path doesn't
    re-expose that as an observable kill signal in the
    return value). **Task 1.4.2 does not claim observable kill
    success or failure** — it returns `Timeout`, and any
    leftover subprocess is the connector resource finalizer's
    responsibility. If reliable kill-with-diagnostics matters
    for v1, that lands with S4-3 (the same connector-boundary
    widening that would surface cost).
  - `AdapterFailure(err: ReviewerError)` — `ReviewerError`
    raised by the connector caught via `.attempt` and
    surfaced in the outcome channel. Each sub-variant
    (`ReviewerProcessFailure`, `ReviewerNotConfigured`,
    `StructuredOutputMissing`, `StructuredOutputMalformed`)
    is passed through unchanged; the orchestrator (Slice 1.4b
    Task 1.4.10) routes each per §7.5 / §7.6 (process failures
    retryable, others not).
- [x] **B2.** `RealReviewerCall(connector: Connector)` — wraps
  the three `connector.review*` methods. The wall-clock cap is
  enforced via `IO.race(IO.sleep(limits.wallClockTimeout),
  attempted)`; `connector.review*`'s IO is run under `.attempt`
  to catch `ReviewerError` subclasses and turn them into the
  `AdapterFailure` channel. No explicit `Clock[IO]` parameter:
  `IO.sleep` already routes through the cats-effect runtime's
  clock, which `TestControl.executeEmbed` substitutes
  deterministically in the unit suite (B4). Connector resource
  lifetime ownership: connector is constructed once per
  orchestrator run (Slice 1.4b Task 1.4.10), not per reviewer call.
- [x] **B3.** **`ReviewerCall` boundary is `ReviewerOutcome`.**
  Task 1.4.2 does **not** emit `MonitorOutcome.SettleTimeout`
  directly — `MonitorOutcome` is Slice 1.3 `SessionMonitor`'s
  driver-phase surface and the reviewer wrapper has no
  business reaching across into it. The conversion from
  `ReviewerOutcome.Timeout` to an FSM-visible event happens
  in the orchestrator (Slice 1.4b Task 1.4.10), and the choice of
  target event closes **S2-8** / **S3-5** in Task 1.4.12:
  - **(a)** Orchestrator maps `Timeout` →
    `FsmEvent.SettleTimeout(SessionPhase.{DesignReview,
    CodeReview, Refine}, reason)`; Task 1.4.12 adds explicit FSM
    handlers per **S2-8** option (i).
  - **(b)** Orchestrator maps `Timeout` →
    `FsmEvent.HarnessError("reviewer settle timeout — ...")`;
    Task 1.4.12 documents the orchestrator-side conversion per
    **S2-8** option (ii) and the FSM gains no new handlers.
  Recommend **(a)** for symmetry with the driver-phase
  SessionMonitor / FSM surface; Task 1.4.12 lands the matching
  FSM handlers and the `ResumeHintCoverageSuite` rows. File
  the decision against **S2-8** in `design-rationale.md` once
  taken.
- [x] **B4.** Unit suite under
  `modules/forge-app/src/test/scala/io/forge/app/reviewer/`:
  - `ReviewerCallWallClockSuite` — `TestControl`-driven; cap
    elapses → `Timeout` returned. Fake connector exposes an
    `IO.never`-shaped reviewer call so the cap fires; the
    test asserts the in-flight fiber is cancelled (via a
    `Ref[IO, Boolean]` flipped from an
    `.onCancel`-instrumented fake), not that an observable
    SIGTERM landed — there is no observable kill channel
    on the `ReviewerOutcome.Timeout` surface (see B1).
  - `ReviewerCallHappySuite` — fake connector returning
    `IO.pure(review)` → `Settled`, and raising each
    `ReviewerError` subclass via `IO.raiseError` →
    `AdapterFailure(err)` with the variant preserved.

### Task 1.4.3 — `forge-specs` skeleton + `SpecStore`  ✅ landed 2026-05-28

`forge-specs` lost its sources in Slice 1.2 Task 1.2.1 (manifest types
relocated to `forge-core` per **S2-1**). Task 1.4.3 re-populates the
module with the persistence wrappers it actually owns.

- [x] **C1.** Module skeleton under
  `modules/forge-specs/src/main/scala/io/forge/specs/`.
  `build.sbt` already declares the module (verify dependency:
  `forge-specs` depends on `forge-core` for `Manifest` /
  `ManifestPatch` / `FeatureId` / `PieceId`).
- [x] **C2.** `io.forge.specs.SpecStore` trait — the §4
  source-of-truth boundary for `manifest.json` / `design.md` /
  `decomposition.md` / `pieces/<p>.md`:
  ```scala
  trait SpecStore:
    def loadManifest(feature: FeatureId): IO[Either[SpecStoreError, Manifest]]
    def saveManifest(feature: FeatureId, m: Manifest): IO[Either[SpecStoreError, Unit]]
    def loadDesign(feature: FeatureId): IO[Either[SpecStoreError, String]]
    def saveDesign(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]]
    def loadDecomposition(feature: FeatureId): IO[Either[SpecStoreError, String]]
    def saveDecomposition(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]]
    def loadPieceSpec(feature: FeatureId, piece: PieceId): IO[Either[SpecStoreError, String]]
    def savePieceSpec(feature: FeatureId, piece: PieceId, body: String): IO[Either[SpecStoreError, Unit]]
  ```
  The decomposition methods exist explicitly because **M1** /
  §5.3 makes `decomposition.md` a render target that
  `forge reconcile` (Task 1.4.13 / **M6**) also reads back via the
  editable-region markers — both directions need a typed
  path. `ForgePaths.decomposition(featureId)` is the
  underlying location. Atomic write semantics: temp file +
  `os.move(replaceExisting = true)` + parent fsync, mirroring
  `FileStateCache.save` in `forge-core`. **This is the §4 /
  §11.5 step 1 invariant: the manifest is the committed
  source of truth, so its writes must be atomic.** The
  atomic-write helper itself can be lifted from
  `FileStateCache` and shared, or duplicated thinly — settle
  in Task 1.4.3.
- [x] **C3.** `FileSpecStore(paths: ForgePaths)` — uses
  `ForgePaths.manifest / design / decomposition / pieceSpec`
  exclusively (one accessor per `SpecStore` method pair in
  C2). No `.forge/...` literals in this module either (Task 1.4.1
  wires `ForgePathsSuite`'s `os.walk` sweep over the new
  `forge-specs` sources).
- [x] **C4.** `SpecStoreError` sealed trait:
  - `NotFound(path: os.Path)` — feature / piece doesn't exist.
  - `Malformed(path: os.Path, cause: Throwable)` — JSON parse
    or markdown shape errors.
  - `IoFailure(path: os.Path, cause: Throwable)`.
- [x] **C5.** Unit suite — `FileSpecStoreSuite` covers
  round-trip for each method, atomic-write crash-window
  recovery (write fails partway → original survives), and the
  three error variants. Cross-reference Slice 1.2 Task 1.2.6 F13
  reader-side atomic-merge invariant — the writer side here is
  **S2-5**'s anchor (closure happens at Slice 1.4b Task 1.4.11).

### Task 1.4.4 — `DocSync` (decomposition.md re-render)  ✅ landed 2026-05-28

Per **M1** / §5.3 — `manifest.json` is the machine source of
truth; `decomposition.md` is a rendered view. `forge reconcile`
inverse path is via the HTML-comment editable regions (Slice 1.1
hasn't shipped that direction yet — it's a Slice 1.4b / Task 1.4.15
polish item).

- [x] **D1.** `io.forge.specs.DocSync` trait:
  ```scala
  trait DocSync:
    def renderDecomposition(feature: FeatureId): IO[Either[DocSyncError, String]]
    def writeDecomposition(feature: FeatureId): IO[Either[DocSyncError, Unit]]
  ```
  Reads `manifest.json` via `SpecStore.loadManifest`, applies
  the `~/.forge/templates/decomposition.md.hbs` template,
  writes via Task 1.4.3's `SpecStore.saveDecomposition` (atomic
  temp+rename+fsync; same shape as `saveManifest`).
- [x] **D2.** Template engine choice — Handlebars-Scala (the
  `decomposition.md.hbs` extension implies Handlebars) or a
  hand-rolled mustache-shaped renderer. Decision recorded in
  Task 1.4.4 against `design-rationale.md` as a v1 module-layout
  call (no spec implication). Lightweight is fine; the
  template doesn't have conditionals beyond piece-status
  badges.
- [x] **D3.** `DocSyncError` sealed trait —
  `TemplateMissing(path)`, `TemplateMalformed(path, cause)`,
  `RenderFailure(cause)`, `SpecStoreFailure(spec: SpecStoreError)`.
- [x] **D4.** Unit suite — `DocSyncSuite` against fixture
  manifests in `modules/forge-specs/src/test/resources/`:
  - Round-trip: render → write → re-read → byte-identical
    second render (idempotence).
  - Each `PieceStatus` (`pending | in_progress | merged`)
    renders the correct badge / checkbox.
  - Missing template surfaces `TemplateMissing` not a generic
    `IoFailure`.

### Task 1.4.5 — `ChangeCollector` (Allow / Deny / Ask)  ✅ landed 2026-05-28

Per §10.1. The decision-rich piece: deny-pattern globbing,
allow-pattern overlay, the "Ask" path that bridges into the
driver's `AskUserQuestion` mechanism.

- [x] **E1.** `io.forge.specs.ChangeCollector` trait:
  ```scala
  trait ChangeCollector:
    def classify(repoRoot: os.Path,
                 changes: Vector[FileChange],
                 config: StagingConfig): IO[Classification]

  enum Classification:
    case Allow(included: Vector[FileChange])
    case Deny(denied: Vector[(FileChange, String)])      // (change, matched pattern)
    case Ask(questions: Vector[Question],
             included: Vector[FileChange])
  ```
  `FileChange(path: os.Path, kind: Added | Modified |
  Deleted | Renamed(from))` — emitted by `git status
  --porcelain`-equivalent (`GitClient.isWorktreeClean`
  returns Boolean only; Task 1.4.5 extends `GitClient` if needed
  with a `changedFiles` method, or reads the porcelain
  output directly via `os.proc`).
  **As landed:** `classify` *consumes* `Vector[FileChange]`, so
  the porcelain → `FileChange` production stays with the
  orchestrator (Task 1.4.10) — `GitClient` was **not** extended.
  `FileChange` gained a `gitIgnored: Boolean = false` field
  (deviation from the E1 sketch) because the pure, git-less
  classifier needs it to apply rule 4; the orchestrator
  populates it from `git status --porcelain --ignored`. See
  design-rationale **CC4**.
- [x] **E2.** Pattern matching — adopt one of (a)
  `java.nio.file.PathMatcher` glob syntax (built-in;
  `glob:**/.env` matches v1.2 §18 patterns), (b)
  `os-lib`'s native `**` matching, or (c) a hand-rolled
  matcher. v1.2 §18 patterns use `**` for any-depth and
  `*` for single-segment — picking (a) keeps zero new
  deps. Decision recorded in Task 1.4.5.
  **As landed:** chose (a) plus a `**/`-prefix workaround —
  Java's glob `**/.env` does **not** match a repo-root `.env`
  (empirically verified), so each `**/`-prefixed pattern also
  compiles the prefix-stripped variant. Filed as **CC4**.
- [x] **E3.** `StagingConfig` shape per v1.2 §18:
  ```scala
  final case class StagingConfig(
    requireExplicitAllow: Boolean,        // default false
    denyPatterns: Vector[String],
    allowPatterns: Vector[String]
  ) derives ReadWriter
  ```
  Loaded by the orchestrator from `.forge/config.json` (Task 1.4.9);
  Task 1.4.5 just consumes it. **As landed:** defined in
  `io.forge.specs.StagingConfig` with default args (so a partial
  `staging` JSON merges the §18 defaults) and a
  `StagingConfig.DefaultDenyPatterns` companion val as the single
  source of truth for the §18 deny list (Task 1.4.9's `ForgeConfig`
  default draws from it).
- [x] **E4.** F7 phase-aware denial — the **classification**
  produced by ChangeCollector is phase-agnostic; the
  orchestrator (Slice 1.4b Task 1.4.10) wires the result into
  `ResumeHint.ResolveLocalImplementationChanges` (pre-PR) vs
  `RunAnotherFixup` (post-PR) per §11.4 step 6 / §11.6.
  **As landed:** `Classification` carries no phase; the
  orchestrator owns the `ResumeHint` mapping (no code in Task 1.4.5).
- [x] **E5.** Decision rules per v1.2 §10.1 (re-stated so the
  implementation tracks the spec verbatim):
  1. Path matches `staging.denyPatterns` → `Deny`.
  2. Path is outside the repo root → `Deny`.
  3. Path is under `.git/` → `Deny`.
  4. Path is ignored by `.gitignore` → `Deny` unless under
     `.forge/specs/...`.
  5. Otherwise → `Allow`, **unless** `requireExplicitAllow:
     true`, in which case the default flips: `Allow` only if
     path matches `staging.allowPatterns`; else `Ask`.
  **Strict mode does not silently filter unmatched paths**
  — the spec is explicit that unmatched-in-strict goes to
  `Ask`, surfacing every borderline path through the
  driver's question mechanism rather than silently dropping
  it. The Slice 1.4 review caught an earlier draft that
  conflated "filter" with the spec contract; this rule list
  is the authoritative version.
  **As landed:** rules evaluate in spec order with two
  implementation notes: (i) the outside-repo check (rule 2)
  runs first as a guard so deny-pattern globbing never sees a
  `../`-escaped path (all of rules 1–4 yield `Deny`, so the
  reorder changes only the reported *reason*, never the
  outcome); (ii) batch aggregation is `Deny` > `Ask` > `Allow`
  — a single denied path blocks the whole stage, and any
  unresolved `Ask` holds it. The rule-4 carve-out routes
  through `ForgePaths.specsRoot` (new accessor) to keep the
  `.forge` path seam intact.
- [x] **E6.** Unit suite under
  `modules/forge-specs/src/test/scala/io/forge/specs/`:
  - `ChangeCollectorAllowSuite` — clean changes pass through
    (rule 5 lenient default).
  - `ChangeCollectorDenySuite` — each v1.2 §18 default deny
    pattern triggers exactly when expected (`.env`, `*.pem`,
    `target/**`, `node_modules/**`, etc.); plus rules 2–4
    (outside repo, `.git/`, `.gitignore` not under
    `.forge/specs/`).
  - `ChangeCollectorStrictAllowSuite` —
    `requireExplicitAllow: true` + `allowPatterns` matches →
    `Allow`.
  - `ChangeCollectorStrictAskSuite` —
    `requireExplicitAllow: true` + `allowPatterns` does
    **not** match → `Ask` (this is the rule-5-strict
    variant the spec calls out). Asserts the resulting
    `Question` is structurally well-formed and routes
    through the driver's `AskUserQuestion` per §10.4.
  - `ChangeCollectorDenyPrecedesAllowSuite` — a path
    matching both `denyPatterns` and `allowPatterns` falls
    to `Deny` (rule 1 dominates rule 5-strict).

### Task 1.4.6 — Templates landing under `~/.forge/templates/`  ✅ landed 2026-05-28

Land the v1 PR-body / decomposition / answer-template files
the orchestrator depends on. Task 1.4.1 landed the in-tree
`assets/templates/` copies; Task 1.4.6 lands the actual content.

- [x] **F1.** `pr-body.md.hbs` — Per §11.4 step 6 template
  shape. Renders feature title, piece title, piece spec,
  acceptance criteria, and the audit summary. Variables
  available: feature manifest entry, piece spec body, prior
  pieces merged so far.
  **As landed:** Task 1.4.1's `pr-body.md.hbs` already met the
  F1 content bar — feature/piece title, `{{piece.spec}}` (the
  acceptance criteria live in the piece spec body; the
  manifest `Piece` carries only an `acceptanceHash`, no
  separate criteria field), the `{{#if mergedPieces.length}}`
  merged-pieces section, and the audit summary. F1 is
  therefore verification-only; the golden (F4) pins its exact
  output.
- [x] **F2.** `decomposition.md.hbs` — Already touched by
  Task 1.4.4's renderer. Task 1.4.6 ships the actual v1 template content
  (status badges, piece headers, editable-region HTML
  comments per **M2** / §5.3). **Gap from Task 1.4.4:** the
  Task 1.4.1 placeholder ships coarse
  `forge:decomposition:begin/end` markers, **not** §5.3's
  reconcile marker set (`forge:order-start`/`-end`, per-piece
  `forge:piece`, `forge:editable-summary`, `forge:status`).
  F2 must replace them with the §5.3 markers **and** tighten
  `DocSyncSuite` (which today only checks comment-marker
  passthrough, with a scope note) to assert the §5.3 region
  shape `forge reconcile` (Task 1.4.15) parses.
  **As landed:** the placeholder markers are replaced with the
  full §5.3 set — `forge:order-start`/`-end` wrapping a
  numbered piece list, and per-piece `forge:piece <pid>`,
  `forge:editable-summary <pid>`, `forge:status <pid>`. Each
  piece renders `N. <!-- forge:piece pid -->**pid: title**…`
  with the summary inside the editable-summary region and the
  `statusBadge` inside the status region; the merged-piece
  PR / merge-commit lines render as non-editable extras inside
  the order region. `DocSyncSuite` gained a §5.3 region-shape
  test (order region appears once, wraps every `forge:piece`
  marker in manifest order, editable-summary carries the
  summary text) and the stale placeholder-marker scope note is
  removed; the byte-identical re-render test already proves the
  idempotence `forge reconcile` depends on.
- [x] **F3.** Per-phase answer templates:
  - `spec-answers.md.hbs` — for §11.1 step 5.
  - `design-review-r<n>-answers.md.hbs` — for §11.2.
  - `design-pr-feedback-r<n>-answers.md.hbs` — for §11.3 step 2.
  - `impl-answers.md.hbs` — for §11.4 step 4.
  - `fixup-r<attempt>-answers.md.hbs` — for §11.6.
  **As landed:** Task 1.4.1 shipped four of the five answer
  templates; `design-review-r1-answers.md.hbs` (§7.7 /
  §11.2 — the pre-PR cross-model review loop, distinct from
  the post-PR `design-pr-feedback` loop) was missing from
  both `assets/templates/` and `AssetInstaller.TemplateLeaves`.
  F3 adds it (round-1 instance; orchestrator parameterises
  `round`) and registers it in `AssetInstaller.TemplateLeaves`,
  `AssetInstallerSuite.allExpectedDestinations`,
  `ShippedTemplateRenderSuite`, and the F4 suite. The other
  four already met the bar.
- [x] **F4.** Unit suite — `TemplateRenderSuite` in `forge-specs`
  test scope, rendering each template against a fixture
  context and asserting key fields appear. Lightweight golden
  files (one rendered fixture per template).
  **As landed:** `TemplateRenderSuite` renders all seven
  shipped templates against one fixed fixture context and
  compares byte-for-byte to a checked-in golden under
  `src/test/resources/golden/<template>.golden`. Regeneration
  is opt-in via `FORGE_UPDATE_GOLDEN=1` and is the only path
  that writes a golden; without the env var a **missing**
  golden is a failure, not a silent write, so adding or
  renaming a template in `Templates` without committing its
  golden fails CI rather than passing while mutating the
  working tree (review-round-1 finding). This complements
  `ShippedTemplateRenderSuite` (which proves the renderer
  *can* handle every construct) by pinning the exact rendered
  *output* — most importantly the §5.3 marker shape.

### Task 1.4.7 — Reviewer regression suite (hosts the C15 gate; closes C15 once the bar is met)  ✅ landed 2026-05-29

The ≥19/20 native-schema regression suite Slice 1.1 deferred per
**C15**. Now that reviewer assets exist (Task 1.4.1) and reviewer
wrappers exist (Task 1.4.2), Task 1.4.7 runs the real CLIs against
shipped schemas and prompts.

- [x] **G1.** `ReviewerRegressionSuite` in `forge-it`. Opt-in
  via `FORGE_IT_RUN_REGRESSION=1` per the default-on `<60s`
  budget (matches `FORGE_IT_RUN_RELIABILITY` / `_PROCLOCK`
  pattern).
  **As landed:** six tests (one per method × connector); each
  builds the connector with `reviewerAssets` installed into a
  throwaway `~/.forge` via the real `AssetInstaller` path, then
  drives `RealReviewerCall` (Task 1.4.2) — so the suite measures
  the same install→bind→decode path the orchestrator uses.
  Per-connector escape hatches `FORGE_IT_SKIP_CLAUDE` /
  `FORGE_IT_SKIP_CODEX` mirror the smoke suites. A separate,
  far-cheaper single-call wiring smoke (`FORGE_IT_RUN_REGRESSION_SMOKE=1`)
  proves the plumbing end-to-end without the full batch.
- [x] **G2.** Per-method × per-connector fixture set under
  `modules/forge-it/src/test/resources/regression/`.
  **Deviation from "20 inputs each" (taken with the maintainer):**
  the §16 bar is a *reliability* measurement of the model's
  structured-output formatting, which the established idiom
  (`CodexHaltWithQuestionReliabilitySuite`) samples with **one
  input × 20 runs**. We follow that idiom with a **small real
  fixture set** — 3 representative inputs per method, drawn from
  this repo's own design docs and PR diffs — **cycled to 20
  samples**. The variance under test is the model's, not the input
  set's; this avoids authoring 60 large fixtures while staying
  faithful to what the bar measures. PR-review diffs are captured
  verbatim from real commits (`adb3791`, `f873169`, `705796f`);
  refine manifests are built inline from a real `Manifest` value
  (type-checked, no fixture drift — same idiom as `DocSyncSuite`).
- [x] **G3.** Pass bar: ≥19/20 per method per connector. A
  schema-validation failure (per the Slice 1.1 `Native` schema
  contract) counts as a fail. Adapter errors that are
  retryable per §7.6 don't count against the bar (retried
  per `reviewProcessRetries`).
  **Met (2026-05-29).** Full live 6×20 batch with the v1 reviewer
  config **(claude reviewer on `haiku`, codex on `gpt-5.3-codex`),
  3-min per-call cap → all six method × connector pairs ≥19/20.**
  Scoring: `Settled` = pass, `StructuredOutputMissing/Malformed`
  = schema-fail (counts), `ReviewerProcessFailure` / wall-clock
  `Timeout` retried once then transient-don't-count per §7.6
  (the suite still counts a *persistent* timeout — see the model
  choice below). Three CLI drifts were found and fixed *inside*
  1.4a along the way (G4): **C16** (Claude 2.1.153 moved the payload
  from `structured_output` to the `result` string), **C17** (Codex
  `--output-schema` strict-mode subset), and **C18** (Claude 2.1.156
  `--json-schema` validates but no longer hard-enforces → tolerant
  parse: prose/fence salvage + in-string control-char normalisation).
  Across ~115 real Claude calls under C18 the only residual schema
  fail is a single unescaped-`"`-in-summary (documented-unrepairable,
  within the 1/20 tolerance). The per-call wall-clock cap is proven
  to bound real time by `ReviewerCallSubprocessTimeoutSuite`.
- [x] **G4.** Failure mode: if a method × connector pair
  fails the bar in Task 1.4.7, schema/prompt tightening happens
  inside 1.4a (not deferred further).
  **Done.** Every failing pair was tightened inside 1.4a rather than
  deferred: C16/C17 unblocked the connector paths; C18 (tolerant
  parse + prompt tightening) cleared the Claude prose/control-char
  failures. **Model/cap finding:** `sonnet` reviews are higher
  quality but **latency-prohibitive** for pr-review — at both 3-min
  and 5-min caps it stalled at 16/20 (p50 114s, p90 218s, ~15% of
  large-diff calls exceed 300s; a smooth heavy tail, not rate-limit
  spikes), so `sonnet` would need a ~7-8 min cap and still carries
  the unescaped-quote tail. The v1 reviewer is therefore **haiku at a
  3-min cap** (fast ~46s/call, cheap, schema-clean 6/6). Production
  tuning of the reviewer **(model, wall-clock cap, timeout-retry
  policy)** — including whether a heavier model is worth a much larger
  cap for review *quality* — is a `ForgeConfig` decision deferred to
  Task 1.4.9 / **S4-3** (recorded in §4 + `design-rationale.md`).
- [x] **G5.** File **C15** resolution in
  `design-rationale.md` — the entry's "Action required"
  flips from "defer to reviewer-asset PR" to "closed in
  Slice 1.4a Task 1.4.7; bar met for all 6 method × connector
  pairs". Roadmap §7.2.2 entry updated.
  **Done** — C15 marked closed in `design-rationale.md` (bar met for
  all six pairs with the haiku/codex v1 config) and the roadmap §7.2
  C15 bullet updated.

### Task 1.4.8 — 1.4a close-out + handoff to 1.4b  ✅ landed 2026-05-29

Mirror of Slice 1.3 Task 1.3.8 structure: section-level coherence
review, roadmap status update, carry-forward walk for 1.4a's
half.

- [x] **H1.** Code review on Task 1.4.1 through Task 1.4.7 as a section.
  Checklist:
  - Does every §7.1 reviewer-method × per-CLI pair have a
    shipped schema + prompt under Task 1.4.1?
  - Does the Task 1.4.7 regression suite cover every method ×
    connector pair?
  - Does `ReviewerCall.Timeout` (Task 1.4.2) document the
    fiber-cancellation-only contract (no observable
    `killError` channel) and is the connector resource
    finalizer responsible for any leftover subprocess
    cleanup? **S4-3** (cost + kill diagnostics deferred)
    filed in `design-rationale.md`.
  - Does Task 1.4.4's `DocSync` round-trip preserve byte
    identity against the `decomposition.md.hbs` fixture?
  - Does `ChangeCollector` enforce every v1.2 §18 default
    deny pattern at the unit level?
  - Section-level coherence pass per the AGENTS.md
    "Testing & review discipline" rule "Review comments on
    design docs are signals, not the patch list": re-walk the
    contract end-to-end, not just patch named findings.
  **As reviewed (2026-05-29):** all six checklist items confirmed
  green. (i) Three schemas (`design-review`/`code-review`/`refine`)
  + six prompts (× `claude`/`codex`) ship under `assets/reviewer/`,
  installed into `~/.forge/` by `AssetInstaller`; the three
  schema-shape suites (`DesignReviewSchemaShapeSuite` /
  `PrReviewSchemaShapeSuite` / `RefineSchemaShapeSuite`) + the
  networknt-backed `RefineSchemaValidationSuite` assert ADT↔schema
  field correspondence so drift fails the build. (ii)
  `ReviewerRegressionSuite` has six bar tests — `{design-review,
  pr-review, refine}` × `{claude, codex}` — plus two wiring-smoke
  tests. (iii) `ReviewerOutcome.Timeout` + `RealReviewerCall`
  docstrings document the fiber-cancellation-only contract (no
  `killError`, no `cost`), pointing at the connector `Resource`
  finalizer and **S4-3**. (iv) `DocSyncSuite`'s "render → write →
  re-read → re-render is byte-identical" test pins the idempotence
  `forge reconcile` (Task 1.4.15) depends on. (v)
  `ChangeCollectorDenySuite` parametrically exercises all 16
  `StagingConfig.DefaultDenyPatterns` entries (root + nested path
  each) plus rules 2–4, and `StagingConfig.DefaultDenyPatterns` is
  the single source of truth shared with the orchestrator's config
  defaulting. (vi) Coherence pass: the `ReviewerCall` →
  `ReviewerOutcome` → orchestrator-mapping contract is internally
  consistent across the docstrings, design-rationale B3/S2-8/S3-5,
  and §4. Build green at close: `forge-core` 358, `forge-agents`
  196, `forge-git` 168, `forge-specs` 132, `forge-app` 96;
  `sbt scalafmtCheckAll` + `sbt clean compile test` clean; `forge-it`
  compiles. **One watch item (non-blocking):**
  `ChangeCollectorDenySuite.denyCases` is a hand-maintained mirror
  of `DefaultDenyPatterns` with no drift-guard assertion — all 16
  patterns are currently covered, but a future addition to
  `DefaultDenyPatterns` would not be auto-tested. Cheap hardening
  (assert `denyCases.map(_._1).toSet == DefaultDenyPatterns.toSet`)
  is left to whoever next touches the deny list rather than
  scope-expanding this close-out.
- [x] **H2.** §4 carry-forward walked for 1.4a items:
  - **C15** — closed in Task 1.4.7 (assuming bar met for all 6
    pairs).
  - **S2-8 / S3-5** — Task 1.4.2's reviewer wrappers ship the
    wall-clock side as `ReviewerOutcome.Timeout`; 1.4b Task 1.4.12
    decides whether the orchestrator maps `Timeout` to
    explicit `FsmEvent.SettleTimeout` handlers (option a)
    or to `FsmEvent.HarnessError` (option b). Task 1.4.2 does
    **not** itself emit `MonitorOutcome.SettleTimeout`
    (that boundary belongs to driver-phase
    `SessionMonitor` — Slice 1.3); the FSM-event mapping
    is purely orchestrator-side.
  - **C14** — orchestrator half still open; 1.4b Task 1.4.14
    closes it.
  - **S4-3** — reviewer cost + observable kill diagnostics
    deferred (new at Task 1.4.2; see §4). Stays open into 1.4b as
    a watch item only; budgets remain driver-session-only
    for v1.
  **As walked (2026-05-29):** every 1.4a carry-forward has a
  durable home. **C15** is marked CLOSED in `design-rationale.md`
  (bar met for all six pairs with the v1 `haiku`/`gpt-5.3-codex`
  config) and the roadmap §7.2 bullet is updated. **C16**, **C17**,
  **C18** — the three real-CLI drifts found en route — each have a
  full `design-rationale.md` entry. **S2-8** / **S3-5** carry into
  1.4b Task 1.4.12; Task 1.4.2's B3 decision (orchestrator maps
  `Timeout` → `FsmEvent.SettleTimeout(...)`, option (a)) is filed
  against **S2-8** in `design-rationale.md`. **C14** carries into
  1.4b Task 1.4.14. **S4-3** stays open as a 1.4b watch item (filed
  at Task 1.4.2 close; budgets remain driver-session-only for v1).
  **S4-5** (production reviewer model/cap/retry — surfaced by the
  now-landed Task 1.4.7) is recorded in §4 + folded into the C15
  residual note; it anchors to Task 1.4.9 `ForgeConfig`. The
  1.4b-anchored new items **S4-1** (poll-baseline file), **S4-2**
  (`forge replay` cut), **S4-4** (`RebuildState.run` widening) file
  to `design-rationale.md` at their landing Tasks (1.4.9 / 1.4.10),
  per §4 — none is buried.
- [x] **H3.** `roadmap.md` §2.4 status block updated —
  "🟢 Slice 1.4a complete, 1.4b open — Task 1.4.9 is the entry
  point". Don't flip the §2.4 bullet from `[~]` to
  `[x]` yet; that's Task 1.4.17 after 1.4b closes.
  **Done** — §2.4 status block now reads "Slice 1.4a complete,
  1.4b open — 2026-05-29" with Task 1.4.9 named as the 1.4b entry
  point and a one-line 1.4a recap; the `[~]` bullets stay `[~]`.
- [x] **H4.** `AGENTS.md` "Current state" gets a Slice 1.4a
  paragraph (mirror Slice 1.3's structure / length). Active
  design-`<section>`.md list keeps `design-1.4.md` pointer.
  **Done** — added a Slice 1.4a "✅ complete 2026-05-29" bullet
  mirroring Slice 1.3's length (assets + `AssetInstaller`,
  `io.forge.app.reviewer` boundary, repopulated `forge-specs`,
  C15-closing Task 1.4.7, grown test counts, 1.4b carry-forward); the
  "Active design-`<slice-id>`.md files" pointer now names Task 1.4.9
  as the 1.4b entry point.
- [x] **H5.** `CLAUDE.md` TL;DR mirrors §3 / §4
  carry-forward changes.
  **Done** — "Active implementation plan" + "Current state" bullets
  refreshed: Slice 1.4a closed / 1.4b open with Task 1.4.9 entry,
  updated test scope (`forge-specs` 132 new, `forge-app` 96,
  `forge-agents` 196), and the 1.4b carry-forward list
  (C14/S2-5/S2-8/S3-5/S4-3/S4-5). The stale "refresh lands at
  Task 1.4.8 H5" placeholder note is removed.

---

### Slice 1.4b — Orchestrator loop, CLI, self-hosting gate

### Task 1.4.9 — `forge-app` entry skeleton + config loader  ✅ landed 2026-05-29

`forge-app` so far has `lock/`, `monitor/`, `bootstrap/` (1.4a
Task 1.4.1), `reviewer/` (1.4a Task 1.4.2). Task 1.4.9 lands the `main` entry
point and the configuration loader that every command depends
on.

- [x] **I1.** `io.forge.app.config.ForgeConfig` — case class
  mirroring v1.2 §18 `.forge/config.json` shape. Loaded by
  `ForgeConfigLoader.load(paths: ForgePaths): IO[Either[ConfigError,
  ForgeConfig]]`. Defaults from §18; per-key override from
  `.forge/overrides/<key>.json` if present.
  **As landed:** nested §18 blocks (`Ci`/`Claude`/`Codex`/`Settle`/
  `Github`/`BaseFreshness`) each with default args; `staging` reuses
  `forge-specs` `StagingConfig`. Loader merges defaults →
  `config.json` → per-key overrides at the `ujson` layer (override
  *replaces* the named top-level key; partial nested objects still
  default their own sub-keys), decodes once. A missing `config.json`
  is `ForgeConfig.Default`, not a `ConfigError`. New `ForgePaths`
  accessors `configFile` / `overridesDir` / `overrideFile(key)`.
  Reviewer-tuning knobs (S4-5) deliberately **not** added — a §18
  extension belongs in `forge-design-1.3.md`.
- [x] **I2.** `io.forge.app.Main extends IOApp.Simple` — entry
  point. **Two-phase boot:** parse the command class up front
  so each subsequent resource (config, reviewer assets,
  connector, process lock) is acquired only by commands that
  actually need it. The earlier draft loaded config + assets
  before parsing argv, which made `forge unlock --force`
  fail any time `.forge/config.json` was missing or the
  reviewer-asset bootstrap raised, and parsed `--repo-root`
  before consulting argv. The boot sequence is:

  1. **Argv parse (phase 1 — global flags + command class
     only).** Parse `--repo-root <path>` and identify the
     command name (`new | spec | run | status | resume |
     reconcile | refresh-cache | abandon | rebuild-state |
     unlock | tail`). Produces a *partial* `ForgeCommand`
     shape — enough to know the command class
     (state-changing | read-only | unlock-force) before any
     resource setup. Per-feature args (`<feature>`,
     `--<resume-hint>`, etc.) parse in phase 2 inside the
     handler.
  2. **Resolve `repoRoot`** from the parsed `--repo-root` or
     `os.pwd`; build `ForgePaths(repoRoot)`. No file I/O
     beyond directory existence check.
  3. **`unlock --force` short-circuit.** If the command is
     `forge unlock --force`, skip every subsequent step:
     open `FileProcessLock(paths)`, call `forceRelease`,
     print the `ForceReleaseResult`, exit. **Does not load
     config, does not install reviewer assets, does not
     construct a connector, does not acquire the lock** —
     the whole point of this command is recovery, and
     adding required setup to its path would make recovery
     impossible exactly when recovery is needed (e.g. when
     `.forge/config.json` is malformed by a prior crash).
  4. **Load `ForgeConfig`** for every other command.
     `forge unlock --force` is the only command that skips
     this; read-only commands still need config for §18
     keys like `branchPrefix` and `baseBranch` (used by
     `forge status` to render piece-PR URLs and by
     `forge tail` to find the log path under `branchPrefix`-
     conventional locations).
  5. **Install reviewer assets** only for commands that may
     invoke driver or reviewer connectors: `new`, `spec`,
     `run`, `resume *`, `reconcile` (which may surface
     `ApplyPlanningUpdate` requiring driver follow-up).
     Read-only commands (`status`, `tail`, `rebuild-state`),
     plus `refresh-cache` and `abandon`, skip the install
     step entirely — they don't run a CLI subprocess.
  6. **Construct the connector** (Claude or Codex per
     `feature.mode` from the loaded manifest, or per
     config default for `forge new`) **only for commands
     that need it** — same set as step 5. Wraps the
     connector in a `Resource` scope so step 9 closes it
     cleanly.
  7. **Acquire `ProcessLock`** only for state-changing
     commands per §15:
     - `new`, `spec`, `run`, `resume *`, `reconcile`,
       `refresh-cache`, `abandon` → acquire; failure
       surfaces holder info and exits 2.
     - `status`, `tail`, `rebuild-state` → **do not
       acquire** (§15 row "Read-only / —"); locking them
       would block diagnostics during an active
       `forge run`.
     - `unlock --force` already exited at step 3.
  8. **Argv parse (phase 2)** — finish parsing per-command
     args (`<feature>`, resume hints, etc.) inside the
     handler. Errors surface as exit 64 (`EX_USAGE`).
  9. Dispatch via `io.forge.app.command.CommandRouter` to
     the per-command handler.
  10. **On exit:** the `Resource` scope releases the lock if
      acquired, closes the connector if constructed, and
      flushes the action log. The order matters — log
      flush before connector close, before lock release —
      and the bracket is held in `Main`'s `IO` chain so
      cancellation paths still hit the finalizers.

  Bracketing detail: lock + connector + log live inside a
  single `Resource` scope owned by `Main`, conditionally
  built per command class so a read-only invocation
  doesn't materialise an `Option[ProcessLock]` /
  `Option[Connector]` threading. The handler signature is
  `Handler.run(ctx: HandlerContext, args: HandlerArgs):
  IO[ExitCode]` where `HandlerContext` carries only the
  resources the handler's command-class actually requires
  (separate context types per class — `ReadOnlyContext`,
  `StateChangingContext`, `UnlockForceContext` — so a
  read-only handler can't accidentally call into a
  connector that wasn't constructed).
  **As landed:** `Main extends IOApp` (not `IOApp.Simple` — the boot
  needs the argv list *and* a non-zero `ExitCode`, neither of which
  `Simple` exposes; framework-mechanics correction, no spec
  implication). Boot steps 1–4, 7–10 (lock half) are wired: phase-1
  parse → repoRoot resolve → `unlock --force` short-circuit (real
  `FileProcessLock.forceRelease`) → config load → **phase-2 parse run
  before lock acquire** (so a usage error never grabs the lock and the
  parsed feature id populates the lock metadata — a benign reorder of
  steps 7/8) → lock acquire for state-changing → dispatch. **Steps 5–6
  (reviewer-asset install + connector construction) and the action-log
  half of step 10 are deferred to Task 1.4.10** (I5 — see below): the
  shell handlers don't touch a connector yet, and the connector +
  log join the lock's single `Resource` bracket when the orchestrator
  handlers need them. `Invocation.needsConnector` (phase 1) already
  classifies the step-5/6 set. Exit codes: `0` ok / `2` lock held or
  stale / `64` usage / `66` bad `--repo-root` / `70` not-implemented
  shell / `78` config error. Per-class contexts are independent case
  classes (`ReadOnlyContext` / `StateChangingContext` /
  `UnlockForceContext`); `HandlerArgs` is the phase-1 `rest` carried on
  each context for the handler's own phase-2 feature parse.
- [x] **I3.** `CommandRouter` — thin dispatch table from
  `ForgeCommand` variant to `command.Handler.run(ctx,
  command): IO[ExitCode]`. Handlers in
  `io.forge.app.command.{new_, spec, run, status, resume,
  reconcile, refreshCache, abandon, rebuildState, unlock,
  tail}`. Task 1.4.9 lands the empty handler shells; Task 1.4.10 / Task 1.4.13
  wire the actual logic.
  - **`ForgeCommand` ADT extension.** Slice 1.3 Task 1.3.3 landed
    `io.forge.git.branch.ForgeCommand` with a
    `ReadOnly(kind: ReadOnlyKind)` variant covering
    `Status | Replay | RebuildState`. **`Replay` is
    cut**: Slice 1.4 ships `forge tail` (live log tail, what
    the §2.5 polish list actually names) instead of
    `forge replay` (batch render-from-log, which was a
    placeholder the Slice 1.3 sketch carried forward without
    a §17 / §15 anchor). Task 1.4.9 extends
    `ReadOnlyKind` to `Status | Tail | RebuildState` and
    updates `BranchManagerPreflightSuite`'s read-only row
    to match. The cut is filed as a new Slice 1.4
    carry-forward **S4-2** in `design-rationale.md` (no
    v1.3 spec edit — §15 names "`replay`" only in the
    `ForgeCommand` table comment, not in the command-line
    surface).
  **As landed:** `ReadOnlyKind` is now `Status | Tail | RebuildState`;
  S4-2 filed. `RealBranchManager.preflight` matches `ReadOnly(_)`
  (kind-agnostic) so the preflight contract is unchanged and
  `BranchManagerPreflightSuite`'s read-only row (`ReadOnlyKind.Status`)
  still compiles untouched. `CommandRouter` has two class-keyed entry
  points (`stateChanging` / `readOnly`); the three `resume` variants
  share the `resume` handler; `unlock --force` dispatches from `Main`.
  Handler objects exist for all eleven commands (`unlock` fully wired,
  the other ten are `70`-exiting shells for Task 1.4.10 / Task 1.4.13).
- [x] **I4.** Unit suite — `ForgeConfigLoaderSuite` covers
  defaults, partial config (missing keys default in),
  malformed JSON surfaces typed error, per-key override
  resolution.
  **As landed:** `ForgeConfigLoaderSuite` (11 cases incl. non-object
  root, bad-enum, scalar override, override-without-base, malformed
  override) plus `CliParserSuite` (phase-1 class/`--repo-root`/unknown
  + phase-2 feature/resume/unlock construction) and `MainSuite`
  (boot wiring end-to-end: exit codes, lock acquire→release around a
  state-changing shell, config-skip on `unlock --force`).
- [x] **I5.** **AGENTS.md "ask before scope-expanding" anchor**
  — if Task 1.4.9 needs new `forge-core` / `forge-git` /
  `forge-agents` surface (likely: connector construction
  factory, since `Main` constructs the connector once per
  run), file an `AskUserQuestion` before silently expanding.
  **As resolved:** the connector-construction factory (the flagged
  expansion) was **not** built — connector construction is deferred to
  Task 1.4.10 where the orchestrator handlers actually need it, so no
  new `forge-agents` surface was added and no ask was required. The
  only cross-module additions are routine path-seam accessors on
  `ForgePaths` (`configFile` / `overridesDir` / `overrideFile`, same
  shape as the `pollBaselineFile` accessor Task 1.4.10 itself plans)
  and the I3-mandated `ForgeCommand.ReadOnlyKind` change — neither a
  behavioural surface expansion.

### Task 1.4.10 — `Orchestrator` loop (the headless feature engine)

The heart of Slice 1.4b. Wires Slices 1–3 together through
`Fsm.transition`.

- [ ] **J1.** `io.forge.app.orchestrator.Orchestrator` —
  the headless feature loop. Pseudocode (the three
  sub-phases from J2 are explicit; `currentDriverSession`
  is the `Ref[IO, Option[ActiveSession]]` defined in J2):
  ```
  start:
    RebuildResult(feature, inFlightSessions) ←
      rebuildState.run(featureId, paths, specStore, log, cache)
    currentDriverSession ← Ref.of(None)
    for each s in inFlightSessions:
      // synthetic HarnessError per phase × FSM state; routes to NHI before any source race
      feature ← applyEvent(feature, syntheticHarnessError(s, feature.state))
    if terminal(feature.state):
      return feature

  loop:
    // sub-phase I: state-entry side effects (idempotent — only runs
    //              once per state since last transition)
    if entered(feature.state):
      feature ← runEntryHook(feature, currentDriverSession)
      // entry hooks: driver spawn (Implement / Fixup / DesignRevision entry);
      // reviewer-call entry-spawn (DesignReviewing-no-session / PieceAwaitingReview / Refining);
      // PR-baseline capture (PieceAwaitingCi / PieceAwaitingReview / PieceAwaitingMerge / DesignAwaitingMerge);
      // feedback-bundle write (DesignPrFeedback)
    if terminal(feature.state):
      return feature

    // sub-phase II: race the source set named in J2's selection table
    activeSession ← currentDriverSession.get
    sources ← eventSources(feature.state, activeSession)
    (event, source) ← race(sources)

    // FSM transition
    (feature', actions) ← Fsm.transition(feature, event, config)
    persistAtomically(feature.manifest, feature'.manifest)
    log.appendAll(actions)
    cache.save(feature')

    // sub-phase III: post-settle synthesis (only if FSM no-op AND source was SessionMonitor)
    if feature'.state == feature.state && source == SessionMonitor:
      currentDriverSession.set(None)   // source-driven clear (J2 lifecycle rule)
      synthEvent ← runPostSettleSideEffects(feature', event)
      // synthesized event feeds back into Fsm.transition WITHIN the same iteration
      (feature', actions') ← Fsm.transition(feature', synthEvent, config)
      persistAtomically(feature'.manifest, /* still */ feature'.manifest)
      log.appendAll(actions')
      cache.save(feature')
    else if source == SessionMonitor:
      // state changed; session cleared by source-driven rule
      currentDriverSession.set(None)

    feature ← feature'
    goto loop
  ```
  The atomic-manifest-persist before the action log + cache
  is the **S2-5** writer-side invariant. The §11.5 step 1
  crash window (manifest written, log not yet) is recovered
  by `RebuildState.reconcile` (already in `forge-core`).
  The `inFlightSessions` projection is a Slice 1.4 widening
  of `RebuildState.run`'s return shape (carry-forward
  **S4-4**); see J2 for the per-phase × FSM-state
  synthetic-`HarnessError` mapping.
- [ ] **J2.** Event sources, session lifecycle, post-settle
  synthesis, and restart recovery. `Orchestrator`'s loop has
  **three internal sub-phases** that interleave the pure
  `Fsm.transition` reactive core with the §11 side-effect
  contract:

  - **(I) State-entry side effects.** On entering a new FSM
    state, the orchestrator runs entry-only side effects
    (spawn driver session if the state requires one;
    state-entry-spawn a `ReviewerCall` for `DesignReviewing`
    no-session / `Refining` / `PieceAwaitingReview`; capture
    initial PR baseline; write the feedback bundle for
    `DesignPrFeedback`) before racing any sources.
  - **(II) Source race.** While the FSM state is stable and
    no post-settle synthesis is pending, the orchestrator
    races a fixed source set for the current
    `(FsmState, activeSession)` pair. Whichever source's
    event arrives first is fed into `Fsm.transition`.
  - **(III) Post-settle synthesis.** When a `SessionMonitor`
    outcome arrives and the resulting `Fsm.transition` is a
    no-op (because §11 requires orchestrator side effects
    between settle and the next state — most prominently
    `PieceImplementing`'s commit + push + createPr →
    `PrOpened` sequence; see "Why the Implement case is
    structured this way" below), the orchestrator runs the
    side effects synchronously and synthesizes the next
    `FsmEvent` for `Fsm.transition` to consume **inside the
    same loop iteration**. No external sources are raced
    during this window.

  **Active-session shape.** The orchestrator carries one
  piece of local state the FSM doesn't model:
  ```scala
  // io.forge.app.orchestrator
  final case class ActiveSession(phase: SessionPhase,
                                 session: AgentSession)

  currentDriverSession: Ref[IO, Option[ActiveSession]]
  ```
  Typed `Option[ActiveSession]`, not
  `Option[StreamingSession]`. `runHeadlessImplementation` and
  `runFixup` return the headless `AgentSession` parent trait,
  not `StreamingSession` (see
  `modules/forge-agents/src/main/scala/io/forge/agents/Connector.scala`
  and `AgentSession.scala` — only Spec / DesignRevision
  return `StreamingSession`). The `phase` field carries
  enough type information to safely downcast where the
  streaming surface is needed: `forge spec` REPL (Task 1.4.13 M2)
  pattern-matches
  `case Some(ActiveSession(SessionPhase.Spec, s:
  StreamingSession)) => …` and raises
  `IllegalStateException` on a non-streaming session for a
  streaming phase (defensive guard against a future
  refactor changing Connector return types). `SessionMonitor`
  consumes only the `AgentSession` surface (`events` +
  `kill()`), so the headless / streaming distinction is
  invisible there.

  **Why orchestrator-local, not a `Feature` projection.**
  §11.2 step 12, §11.3 step 2, and the entry rules for
  `PieceImplementing` / `PieceFixingUp` all spawn driver
  sessions inside a single FSM state (e.g.
  `DesignReviewing(round)` contains a reviewer sub-phase
  AND a driver-revision sub-phase). The FSM state alone
  doesn't disambiguate "are we currently running the
  reviewer one-shot, or watching a driver settle?"
  Modelling that as orchestrator-local state instead of an
  extra FSM field keeps the FSM ADT minimal (§22 "no
  half-states") and matches §11.2's prose-driven sub-phase
  structure.

  **`currentDriverSession` lifecycle is source-driven, not
  event-payload-driven** (this is the corrected rule —
  `FsmEvent.HarnessError(reason: String)` carries no
  `SessionPhase`, so payload-keyed clearing is impossible).
  The orchestrator's race-and-dispatch step records which
  source produced the event in a loop-local tag:
  ```
  source = SessionMonitor    → MonitorOutcome → FsmEvent;
                                ALWAYS clear currentDriverSession
                                (the underlying subprocess is gone
                                whether outcome is Settled, SettleTimeout,
                                TurnBudgetBreached, BudgetBreached, or
                                a kill-while-running HarnessError)
  source = PRWatcher         → never touches currentDriverSession
                                (no session is live when PRWatcher is the
                                selected source per the table below)
  source = ReviewerCall      → never touches currentDriverSession
                                (reviewer wrappers don't share a session
                                with the driver phases)
  source = user (REPL/Q&A)   → never touches currentDriverSession on its
                                own; downstream FSM transitions may add a
                                fresh session via the state-entry hook
  source = synthetic (post-settle, in-flight-restart)
                              → clears or sets per the synthesis recipe
                                (see post-settle table below); never reads
                                from a payload phase field
  ```

  **(II) Source-selection table.** Consulted only while no
  post-settle synthesis is pending. `activeSession` is
  pattern-matched on the `Option[ActiveSession]`; when None
  the phase column shows `—`, when Some the phase is the
  exact `SessionPhase` and the session type is enforced at
  state-entry:

  ```
  state                                activeSession                        sources this iteration
  -----                                -------------                        ----------------------
  Drafting                             None                                 (none — entry hook fires UserCommand.New, re-loop)
  InteractiveSpec                      Some(Spec, _: StreamingSession)      SessionMonitor + REPL (forge spec only)
  DesignReviewing(round)               None                                 ReviewerCall.designReview (entry-spawned)
  DesignReviewing(round)               Some(DesignRevision, _: Streaming)   SessionMonitor (revision settle after request_changes)
  DesignNeedsHumanInput(round, _)      None                                 user Q&A (forge run: --answer-file; forge spec: REPL inline)
  DesignAwaitingMerge(prNumber)        None                                 PRWatcher(prNumber)
  DesignPrFeedback(prNumber, round)    Some(DesignRevision, _: Streaming)   SessionMonitor (entry-spawned resumeStreamingSpec)
  DesignReady                          None                                 (none — entry hook advances to first PieceImplementing or FeatureDone)
  PieceImplementing(p)                 Some(Implement, _: AgentSession)     SessionMonitor
  PieceAwaitingCi(p, prNumber)         None                                 PRWatcher(prNumber)
  PieceAwaitingReview(p, prNumber)     None                                 PRWatcher(prNumber) + ReviewerCall.prReview (entry-spawned race)
  PieceCiFailed / PieceReviewFailed    None                                 (none — entry hook spawns runFixup, re-loop)
  PieceFixingUp(p, prNumber, attempt)  Some(Fixup, _: AgentSession)         SessionMonitor
  PieceAwaitingMerge(p, prNumber)      None                                 PRWatcher(prNumber)
  Refining(p, prNumber, _)             None                                 ReviewerCall.refine (entry-spawned)
  PlanningUpdate(_, _)                 None                                 user Q&A (apply/defer/reopen/ignore per §14.3)
  NeedsHumanIntervention/FeatureDone/Abandoned  None                        (none — loop exits to caller)
  ```

  Notes on specific rows:
  - **`PieceAwaitingReview` race.** PRWatcher and
    ReviewerCall produce disjoint `FsmEvent` types
    (`PrSnapshotUpdated` / `CodeReviewVerdict`); whichever
    transitions the FSM first wins, and the other source's
    in-flight fiber is cancelled on state change. The
    baseline cursor only advances on `Snapshot` arrival
    (§S3-7 round-2 contract). The reviewer fiber is
    state-entry-spawned, not race-spawned per iteration —
    each entry to `PieceAwaitingReview` issues one
    `ReviewerCall.prReview` call.
  - **`InteractiveSpec` reachability.** Only `forge spec`
    enters `InteractiveSpec`; `forge run` invoked on a
    feature whose state is `InteractiveSpec` refuses at
    handler-level (§15 / Task 1.4.13 M3) and prints a hint to
    run `forge spec` instead.

  **(III) Post-settle synthesis recipes** (the missing
  glue between `MonitorOutcome` and the FSM's
  state-transition events for the four driver phases —
  drawn directly from §11):

  ```
  state                  monitor outcome              orchestrator side effects                          synthesized FsmEvent
  -----                  ---------------              -------------------------                          --------------------
  InteractiveSpec        Settled(Spec, Clean)         §11.1 step 7 post-check: verify design.md /         Settled(Spec, Clean)
                                                       manifest.json / pieces/*.md / decomposition.md     (FSM transitions to
                                                       coherence; ≤2 corrective rounds on mismatch        DesignReviewing(1))
  InteractiveSpec        Settled(Spec, AdapterError)  none                                                Settled(Spec, AdapterError) — pass through
  DesignReviewing(_)     Settled(DesignRevision,      update design.md per §11.2 step 9-10                Settled(DesignRevision, Clean)
   (post-revision)        Clean)                       (no PR exists at this point)                       (FSM loops to step 9 inside DesignReviewing)
  DesignPrFeedback(_,_)  Settled(DesignRevision,      update design assets; snapshot tag; force-push-     Settled(DesignRevision, Clean)
                          Clean)                       with-lease per §11.3 steps 3-5                     (FSM returns to DesignAwaitingMerge)
  PieceImplementing(p)   Settled(Implement, Clean)    ChangeCollector classify (§10.1); on Allow:         PrOpened(p, prNumber)
                                                       commit + push + createPr per §11.4 step 6;
                                                       on Deny: emit HarnessError → NHI
                                                       (ResolveLocalImplementationChanges)
  PieceFixingUp(_,_,_)   Settled(Fixup, Clean)        ChangeCollector classify; on Allow: commit +        Settled(Fixup, Clean)
                                                       push per §11.6; on Deny: emit HarnessError → NHI   (FSM transitions to PieceAwaitingCi)
                                                       (RunAnotherFixup)
  {any driver phase}     SettleTimeout                none — pass through                                 SettleTimeout(phase, _)
  {any driver phase}     TurnBudgetBreached           none                                                TurnBudgetBreached(phase, _)
  {any driver phase}     BudgetBreached               none                                                BudgetBreached(scope, _)
  {any driver phase}     Settled(_, AdapterError)     none                                                Settled(phase, AdapterError(_))
  ```

  **Why the Implement case is structured this way.**
  `Fsm.transition` deliberately leaves `Settled(Implement,
  Clean)` in `PieceImplementing(p)` as a `_ => noop(feature)`
  catch-all (see
  `modules/forge-core/src/main/scala/io/forge/core/fsm/Fsm.scala:421`).
  The state-transition trigger from `PieceImplementing(p)` →
  `PieceAwaitingCi(p, prNumber)` is `PrOpened(piece,
  prNumber)` (Fsm.scala:400), not the settle event itself.
  This is the spec's atomicity guarantee: if Forge crashes
  between `Settled(Implement, Clean)` and `PrOpened`, the
  next `forge resume` reads the FSM state as still
  `PieceImplementing(p)`, sees no live session in the action
  log, infers "post-settle work was in flight", and replays
  the post-settle synthesis (ChangeCollector + commit +
  push + createPr — `createPr` is idempotent via
  `gh pr list --head <branch>` check before re-creating).
  Fixup uses the inverse approach (FSM transitions on
  `Settled(Fixup, Clean)` directly; commit+push happens
  *before* the orchestrator submits the `Settled` event to
  the FSM) because the PR already exists and there's no
  `createPr` step to crash through. Both are valid §11
  encodings of "side effects bracket the FSM transition";
  Task 1.4.10 documents the asymmetry in `Orchestrator`'s scaladoc.

  The synthesized event from sub-phase III feeds back into
  `Fsm.transition` within the same loop iteration without
  re-entering source-racing. If side effects raise (commit
  fails, push rejected, createPr fails, ChangeCollector
  Deny on Implement / Fixup), the orchestrator synthesizes
  `HarnessError(reason)` with the failure context; the FSM
  routes via `defaultResumeHintForState` (Fsm.scala:971) so
  every failure mode lands in `NeedsHumanIntervention` with
  a phase-appropriate hint. The state-vs-phase ambiguity
  carried by `HarnessError(reason: String)` doesn't matter
  here because the source-driven session-clear rule already
  ran when `MonitorOutcome.Settled` arrived from
  `SessionMonitor`.

  **Source descriptions:**
  - `SessionMonitor(session, phase)` — produces a
    `MonitorOutcome` over the active `AgentSession.events`
    stream. The orchestrator converts every `MonitorOutcome`
    into the corresponding `FsmEvent` per the post-settle
    recipe table above and clears `currentDriverSession`
    in the source-driven lifecycle step. `MonitorOutcome`
    arrival is the **only** trigger for session-clear; no
    other source touches `currentDriverSession`.
  - `PRWatcher(prNumber)` — produces
    `DesignPrSnapshotUpdated(snapshot)` for the design PR,
    `PrSnapshotUpdated(piece, snapshot) /
    Merged(piece, _) / CheckDiscoveryComplete(piece,
    prNumber)` for piece PRs. **Baseline persistence
    location (§S3-7 round-2 carry-forward).** `Manifest`
    / `Piece` in
    `modules/forge-core/src/main/scala/io/forge/core/manifest/`
    carry no baseline fields, so the orchestrator persists
    the `PollBaseline` to a sibling state file
    `.forge/state/<feature>.poll-baselines.json`
    (`Map[PrNumber, PollBaseline]` keyed across the design
    PR + every piece PR). Add a
    `ForgePaths.pollBaselineFile(featureId)` accessor in
    Task 1.4.10 (one-line addition; matches the Slice 1.2
    `stateFile` shape). Flagged as carry-forward **S4-1**
    in §4 / `design-rationale.md`. Alternative considered
    + rejected: widen `Manifest.designPr` and `Piece` to
    carry the baseline inline — rejected because the
    manifest is the committed source of truth (§4) and
    `PollBaseline` is local-only state that mutates on
    every poll; committing it would generate noise on
    every gh round-trip.
  - `ReviewerCall.designReview / prReview / refine` —
    one-shot blocking IO. Maps orchestrator-side:
    `ReviewerOutcome.Settled(review)` →
    `FsmEvent.DesignReviewReceived / CodeReviewVerdict /
    RefineOutcome`; `Timeout` →
    `FsmEvent.SettleTimeout(SessionPhase.{DesignReview,
    CodeReview, Refine}, _)` per **S2-8** option (a) (Task 1.4.12)
    or `FsmEvent.HarnessError(...)` per option (b);
    `AdapterFailure(err)` → either retried (if
    `ReviewerProcessFailure` and retry budget remains) or
    routed to `FsmEvent.HarnessError(...)` per §7.5 / §7.6.
    The reviewer wrapper does **not** synthesize `FsmEvent`s
    — that conversion lives in the orchestrator.
  - User commands — `UserCommandReceived(cmd: UserCommand)`
    fired from `Main`'s top-level dispatch and the
    `forge spec` REPL.

  **Restart recovery (process-crash handling).**
  `currentDriverSession` starts at `None` on every process
  start — a prior subprocess does not survive Forge process
  death, regardless of whether Claude / Codex preserve the
  session id under `--resume`. **Task 1.4.10 extends
  `RebuildState.run` with an additional projection:**
  ```scala
  final case class InFlightSession(
    phase: SessionPhase,        // Spec | DesignRevision | Implement | Fixup
    sessionId: String,          // the spawn/resume id from the log
    piece: Option[PieceId]      // None for design phases; Some(p) for implement/fixup
  )

  // Extended return shape (Slice 1.2 returned only Feature; Task 1.4.10 widens to:)
  final case class RebuildResult(
    feature: Feature,
    inFlightSessions: Vector[InFlightSession]
  )
  ```
  An `InFlightSession` is detected when the action log
  contains a `<actor>.spawn` or `<actor>.resume` for phase
  `P` (and piece `p` for driver phases) without a
  subsequent `Settled` / `SettleTimeout` /
  `TurnBudgetBreached` / `BudgetBreached` action for the
  same `(phase, piece)` key. This projection is computed by
  walking the log tail; it does NOT change the canonical
  `Feature` shape (so the Slice 1.2 invariants stay
  unchanged).

  Before entering the main loop, the orchestrator
  processes each `InFlightSession` as a synthetic
  `HarnessError` event and routes it through
  `Fsm.transition` so the FSM lands in
  `NeedsHumanIntervention` with a phase-appropriate hint
  before any source-racing begins:

  ```
  inFlight phase  + FSM state                              synthetic HarnessError →
  --------------  ----------                              ------------------------
  Spec            InteractiveSpec                          NHI("spec session interrupted by process restart", AbortOrAbandon)
  DesignRevision  DesignReviewing(round)                   NHI("design revision interrupted by process restart", ReopenDesign(None))
  DesignRevision  DesignPrFeedback(prNumber, round)        NHI("design PR feedback session interrupted", ReopenDesign(Some(prNumber)))
  Implement       PieceImplementing(p)                     NHI("implementation interrupted; worktree may have uncommitted changes",
                                                                ResolveLocalImplementationChanges(p, branch))
  Fixup           PieceFixingUp(p, prNumber, attempt)      NHI("fix-up interrupted; worktree may have uncommitted changes",
                                                                RunAnotherFixup(p, prNumber))
  ```

  Each lands via `Fsm.transition`'s `HarnessError`
  catch-all (Fsm.scala:101) and uses
  `defaultResumeHintForState` (Fsm.scala:971) to pick the
  hint — the orchestrator's mapping table above mirrors
  what `defaultResumeHintForState` already produces, so
  the contract is: the orchestrator passes the typed
  `HarnessError(reason)` and trusts the FSM's default-hint
  table. No transparent resume is attempted because:
  - Streaming sessions: Forge doesn't have the original
    in-flight message — `resumeStreamingSpec` needs a
    message to spawn with, and re-issuing a stale message
    risks confusing the model. The user runs
    `forge resume --<hint>` (typically `ReopenDesign` →
    re-spawns from scratch) to recover.
  - Headless sessions: the worktree may carry partial
    uncommitted changes — re-spawning would lose them.
    `ResolveLocalImplementationChanges` /
    `RunAnotherFixup` lets the human decide between
    committing the partial work or discarding it.

  **Post-transition session lifecycle (the bracket
  logic).** After each `Fsm.transition`, the orchestrator
  runs a small post-step:
  1. **If the source was `SessionMonitor`** — clear
     `currentDriverSession` unconditionally (the
     underlying subprocess is gone). Source-driven, not
     event-payload-driven, because
     `FsmEvent.HarnessError(reason: String)` carries no
     `SessionPhase`.
  2. **If the new state requires a fresh driver session
     by §11** (`DesignReviewing(round)` after a
     `request_changes` verdict; `DesignPrFeedback(prNumber,
     round)` entry; `PieceImplementing(p)` entry from
     `DesignReady` or from a prior piece advance;
     `PieceFixingUp(...)` entry from `PieceCiFailed` /
     `PieceReviewFailed`) — spawn via the appropriate
     `connector.*` call, store the new `ActiveSession`,
     log `<actor>.spawn` (or `<actor>.resume` for the
     design-revision case).
  3. **If the consumed event triggered post-settle
     synthesis** (sub-phase III above) — run the side
     effects, synthesize the next `FsmEvent`, re-apply
     `Fsm.transition` immediately. The session was already
     cleared by step 1; the synthesized event may itself
     trigger step 2's spawn on the new state.
  4. **Otherwise** — leave `currentDriverSession`
     unchanged and re-enter the source race.

  Task 1.4.10's unit test surface includes:
  - `OrchestratorSourceSelectionSuite` — table-driven
    assertion that every `(FsmState, activeSession)` row
    in the table above selects the documented sources;
    raises `IllegalStateException` on unreachable
    combinations (e.g. `PieceAwaitingCi` with
    `Some(Implement, _)`) so a future lifecycle bug is
    loud.
  - `OrchestratorPostSettleSynthesisSuite` — pins each
    post-settle recipe row: feed a `MonitorOutcome`,
    assert the side-effect calls (against a fake
    `SpecStore` + fake `BranchManager`), assert the
    synthesized `FsmEvent`.
  - `OrchestratorRestartSuite` — pins `InFlightSession`
    detection from a synthetic log + `Feature` pair, and
    the per-phase synthetic `HarnessError` routing
    through `Fsm.transition`.

  **`RebuildState.run` signature change carried forward.**
  Widening to return `RebuildResult(feature,
  inFlightSessions)` is the Slice 1.4 closure of a gap that
  was implicit in Slice 1.2 (`Feature.foldEvents` returns
  `observedTransitions` and `observedPieceMerges` but no
  in-flight bookkeeping). Filed as new carry-forward
  **S4-4** in §4.
- [ ] **J3.** Connector lifetime — `Connector` is
  constructed once at orchestrator start per `Mode`
  (Claude or Codex). `runStreamingSpec /
  runHeadlessImplementation / runFixup /
  resumeStreamingSpec` calls share the connector
  resource. **C14 watch:** the Codex resume call needs
  to handle role framing per Task 1.4.14's resolution.
- [ ] **J4.** Atomic manifest persistence — `SpecStore.saveManifest`
  (1.4a Task 1.4.3) is called **before** the action log append +
  state cache write. Per §11.5 step 1, if the orchestrator
  crashes between manifest write and FSM transition action,
  the next `forge resume` reads the merged status from
  manifest and `RebuildState.reconcile` synthesises the
  missing `audit.piece_merged` action. This is the writer
  side of the invariant that **S2-5** flagged.
- [ ] **J5.** Unit suite — orchestrator loop happy paths via
  fake event sources (fake `SessionMonitor`, fake
  `PRWatcher`, fake `ReviewerCall`). Cover at minimum:
  - Spec phase end-to-end (`Drafting → InteractiveSpec →
    DesignReviewing(1) → DesignReady`) via a scripted
    event sequence.
  - One piece end-to-end (`PieceImplementing →
    PieceAwaitingCi → PieceAwaitingReview →
    PieceAwaitingMerge → Refining → next or
    FeatureDone`).
  - Crash + recovery — orchestrator killed between
    manifest-merged write and `audit.piece_merged` log
    append; restart reads manifest, reconcile synthesises
    the missing action, FSM advances normally.

### Task 1.4.11 — **S2-5** writer-side atomic-merge test

A focused PR that pins the writer-side invariant Slice 1.2 Task 1.2.6
deferred. Task exists as its own anchor so a future reviewer
walking carry-forwards can find the test directly.

- [ ] **K1.** `OrchestratorAtomicMergeSuite` in `forge-app`
  test scope. Uses a fault-injection `SpecStore` /
  `FileActionLog` / `FileStateCache` triple:
  - Inject crash between manifest write and action-log
    append in `PieceAwaitingMerge → Refining` transition.
    Assert: manifest reflects `status="merged"`, log is
    missing the `audit.piece_merged` entry, state cache
    holds pre-transition state.
  - Restart via `RebuildState.run`. Assert reconciliation
    synthesises the missing `audit.piece_merged` and FSM
    advances to `Refining` correctly.
- [ ] **K2.** Close **S2-5** in `design-rationale.md` —
  "Action required" flips from "deferred to Slice 1.4" to
  "closed in Slice 1.4b Task 1.4.11". Roadmap §7.2.2 entry
  updated.

### Task 1.4.12 — **S2-8** / **S3-5** reviewer/refine SettleTimeout closure

Whichever option B3 (in Task 1.4.2) chose — explicit FSM handlers
(a) or documented orchestrator-side conversion (b).

- [ ] **L1.** **If (a):** Add `Fsm.transition` handlers for
  `SettleTimeout(SessionPhase.DesignReview, _)` from
  `DesignReviewing(round)`, `SettleTimeout(CodeReview, _)`
  from `PieceAwaitingReview`, `SettleTimeout(Refine, _)`
  from `Refining`. Each routes to `NeedsHumanIntervention`
  with a phase-appropriate `ResumeHint`. Add
  `ResumeHintCoverageSuite` rows.
- [ ] **L2.** **If (b):** `Orchestrator.handleReviewerOutcome`
  converts `Timeout(_)` to
  `FsmEvent.HarnessError("<phase> settle timeout")`.
  Document the conversion in `Fsm.scala`'s scaladoc and in
  `Orchestrator.handleReviewerOutcome`'s scaladoc; the FSM
  doesn't gain new handlers. Add an
  `OrchestratorReviewerTimeoutSuite` row pinning the
  conversion.
- [ ] **L3.** Close **S2-8** and **S3-5** in
  `design-rationale.md`. Roadmap §7.2.2 entry updated.

### Task 1.4.13 — CLI surface (every §17 / §15 command)

The user-facing surface. Per-command handlers wire into the
orchestrator (Task 1.4.10) or are read-only / preflight-only.

- [ ] **M1.** `forge new "<title>" [--mode <mode>] [--id <slug>]`
  — slugs per §5.2; creates `<branchPrefix>/<feature>/design`;
  initializes manifest with empty pieces; emits
  `UserCommandReceived(UserCommand.New)`; loops in
  Orchestrator until `InteractiveSpec`.
- [ ] **M2.** `forge spec <feature>` — **line-mode REPL.** The
  one stateful interactive command. Wires
  `runStreamingSpec(specifyPrompt, firstMessage)` directly to
  stdin/stdout: assistant-text events stream to stdout;
  `AskUserQuestion` events prompt the human; answers route via
  `session.answerQuestion(toolUseId, answer)`. `/done`
  triggers `UserCommandReceived(UserCommand.Done)`.
- [ ] **M3.** `forge run <feature>` — fully headless. Loops
  Orchestrator from current state through to `FeatureDone` or
  `NeedsHumanIntervention`. Prints state transitions to
  stdout; full detail in `.forge/log/<feature>.jsonl`.
- [ ] **M4.** `forge status [<feature>]` — Per §2.5 polish:
  current state, current piece, last action, budget remaining.
  Read-only; doesn't acquire the lock (per §15: read-only).
- [ ] **M5.** `forge resume <feature> --<hint>` — variants
  per §15 / §6 `ResumeHint`:
  - `--after-human-push` → `ResumeAfterHumanPush(p, prNumber)`.
  - `--commit-human-fix` → `CommitAndPushHumanFix(p, prNumber)`.
  - `--run-fixup` → `RunAnotherFixup(p, prNumber)`.
  - Other hints (`ResolveLocalImplementationChanges`,
    `ReopenDesign`, `ApplyPlanningUpdate`, `AbortOrAbandon`)
    surface as explicit flags or are inferred from current
    `NeedsHumanIntervention` state.
  Bumps `branchProtectionCacheEpoch` per §8.1.
- [ ] **M6.** `forge reconcile <feature>` — runs **M2** /
  §5.4 manifest reconcile against `decomposition.md`'s
  HTML-comment editable regions. Re-renders if no edits
  detected; surfaces `ManifestPatch` if edits valid; refuses
  if edits outside editable regions.
- [ ] **M7.** `forge refresh-cache <feature>` — bumps
  `branchProtectionCacheEpoch` only; no state mutation
  beyond that (per §15).
- [ ] **M8.** `forge abandon <feature>` — emits
  `UserCommandReceived(UserCommand.Abandon(reason))`;
  Orchestrator transitions to `Abandoned(reason)`.
- [ ] **M9.** `forge rebuild-state <feature>` — calls
  `RebuildState.run` directly; reports any
  `RebuildError`. Per §2.5 polish, prove this on a
  deliberately corrupted cache fixture (one test that
  hand-mutates `.forge/state/<feature>.json`).
- [ ] **M10.** `forge unlock --force` — calls
  `ProcessLock.forceRelease` (Slice 1.3). Refuses with
  exit 2 on `LiveHolderRefused`.
- [ ] **M11.** `forge tail <feature>` — §2.5 polish; tails
  `.forge/log/<feature>.jsonl`. Read-only; doesn't acquire
  the lock. Routes through Task 1.4.9's
  `ForgeCommand.ReadOnly(ReadOnlyKind.Tail)`.
- [ ] **M12.** Unit + IT coverage — per-command handler
  unit tests in `modules/forge-app/src/test/`; the
  MVP-gate end-to-end run (Task 1.4.16) exercises the integration
  surface.

### Task 1.4.14 — **C14** Codex resume role-framing closure

The orchestrator-side half of **C14**. v1.3 (not yet
written) chooses between (i) drop the §7.10(a) "applies to
resume" claim, or (ii) widen the trait to carry
`systemPromptPath`. Task 1.4.14 implements whichever v1.3 picks.

- [ ] **N1.** v1.3 spec decision filed in
  `docs/forge-design-1.3.md` (new file per the §23
  standalone-revisions rule). Task 1.4.14 adopts whichever
  direction v1.3 specifies.
- [ ] **N2.** **If (i):** Orchestrator's `resumeStreamingSpec`
  call sites (§11.2 step 12, §11.3 step 2) embed role
  framing in the `revisionMessage` / `feedbackMessage`
  themselves — the message starts with the system-prompt
  block before the actual feedback content. Document the
  pattern in `Orchestrator`'s scaladoc at each call site
  and centralise the framing construction in a new
  `ReviewerPrompts.codexResumeFraming` helper.
- [ ] **N3.** **If (ii):** Widen the `Connector.resumeStreamingSpec`
  trait to carry `systemPromptPath`; update
  `ClaudeConnector` (no-op — Claude restores via
  `--resume`) and `CodexConnector` (reads the path and
  prepends per §7.10(a)). Update every call site in
  `forge-app`.
- [ ] **N4.** Close **C14** in `design-rationale.md` —
  "Action required" flips to "closed in Slice 1.4b Task 1.4.14
  per v1.3 §7.10(a) revision (option <i|ii>)". Roadmap
  §7.2.1 / §7.2.2 entries updated.
- [ ] **N5.** Regression test against the resume path —
  asserts the system-prompt content actually reaches
  Codex on a resume call. Fake `Subprocess` is fine
  (assert argv shape); the Task 1.4.7 regression suite is the
  real-CLI bar.

### Task 1.4.15 — Targeted polish (§2.5)

The MVP-gate enablers — Forge is unusable without these.

- [ ] **O1.** Human-readable `NeedsHumanIntervention`
  rendering — every reason × every `ResumeHint` pair has a
  one-paragraph human-readable description naming exactly
  the `forge resume --<hint>` (or `forge abandon`) the
  operator runs next. Six paths per §6.
- [ ] **O2.** `forge status` formatting per **M4** —
  golden-file test against fixture features in each
  state.
- [ ] **O3.** `forge tail` smoke test —
  open + first-line read on a synthetic
  `.forge/log/<feature>.jsonl`.
- [ ] **O4.** Corrupted-cache `forge rebuild-state` proof —
  one test in `forge-app` deliberately mutates
  `<feature>.json`'s bytes mid-key, asserts
  `RebuildError.CacheCorrupt`, calls `rebuild-state`,
  asserts recovery to log-canonical Feature.
- [ ] **O5.** Conditional watch items walked:
  - **S2-3** (ActionLog write durability) — measure under
    orchestrator load; if no perf cliff, document the
    `APPEND + SYNC` default as v1.3 baseline. If cliff
    fires, switch to per-batch `force()` after non-sync
    write.
  - **S2-9** (`verifyAgainstLog` cache write skip) —
    measure under orchestrator load; if no perf cliff,
    keep unconditional `cache.save`. If fires hot,
    implement byte-compare-and-skip.
  - **S3-2** (`BranchProtectionCache` persistence) — only
    fix if the per-resume `gh api` round-trip is
    perceptibly slow.
  - **S3-4** (`PRWatcher` rate-limit cliff) — only adjust
    threshold if 3-consecutive feels wrong under lived
    `forge run` cadence.

### Task 1.4.16 — MVP-gate run

The Phase-1 exit gate. Drive one small, contained,
low-variance feature through Forge from `forge new` to
merged-`FeatureDone`.

- [ ] **P1.** Pick the MVP target. Roadmap §2.4 explicitly
  excludes "have Forge build its own Slice 5 (TUI)".
  Candidates per §2.4 closing:
  - A small `forge-git` helper (e.g. a `branchExists`
    abstraction for shared use across BranchManager
    callers).
  - A narrow `forge-app` reporting/replay feature
    (e.g. `forge replay <feature>` reading the action
    log and printing a human summary).
  - One of the v1.3 spec-text corrections (S2-6, S2-7,
    S2-10, S3-6, S3-7, S3-8) with the matching code
    test.
  Recommend the v1.3 spec-text correction route — it
  exercises every reviewer + driver phase against
  small, well-bounded scope (text + one test). File
  the choice in this checklist before Task 1.4.16 begins.
- [ ] **P2.** Drive end-to-end:
  - `forge new "<title>"`.
  - `forge spec <feature>` — interactive REPL until
    `/done`.
  - `forge run <feature>` — headless from
    `DesignReady` through to `FeatureDone` (manually
    merging design PR + each piece PR via `gh pr
    merge` between phases per v1 rule "piece PRs
    merged by the human").
- [ ] **P3.** Capture the run as evidence. Per AGENTS.md's
  "commit completed work before review" memory, the
  `.forge/log/<feature>.jsonl` for this run is committed
  under `docs/slice-4/mvp-run/<feature>/` (sanitised —
  no API keys, no `pwd` leakage). Include a short
  prose summary of what worked, what didn't, what
  surfaced as friction.
- [ ] **P4.** **Friction items surface as Phase 2 input,
  not Slice 1.4 follow-ups.** Phase 2 is where prompt
  iteration + TUI live; Slice 1.4 is "Forge ships its own
  next slice", not "Forge ships its own slice
  beautifully". Document friction in
  `docs/slice-4/mvp-friction.md` for Phase 2 to
  triage.

### Task 1.4.17 — Slice 1.4 close-out + Phase 1 exit

Mirror of Slice 1.3 Task 1.3.8 but for the whole §2.4 bullet
(both 1.4a and 1.4b). Flips the roadmap `[~]` → `[x]` and
opens Phase 2.

- [ ] **Q1.** Section-level code review on Task 1.4.1 through
  Task 1.4.16:
  - Every §17 slice-4 deliverable shipped (orchestrator
    loop, line-mode REPL, every command in §15)?
  - Every §11 lifecycle path exercised end-to-end at
    least once (spec / design review / design PR gate /
    implementation / fix-up / CI & review / refining /
    next piece / FeatureDone)?
  - Every `NeedsHumanIntervention` reason × `ResumeHint`
    has a human-readable render and a `forge resume`
    flag per §2.5?
  - Section-level coherence pass per the AGENTS.md
    "Testing & review discipline" rule "Review comments on
    design docs are signals, not the patch list".
- [ ] **Q2.** `roadmap.md` §2.4 bullet flips `[~]` →
  `[x]`. Status block updated to ✅. Top status line
  bumps draft revision.
- [ ] **Q3.** `AGENTS.md` "Current state" — Slice 1.4
  paragraph (mirror Slice 1.3's structure / length).
  Active design-`<section>`.md list returns to
  *(none currently open — Phase 2 opens its own
  doc when work starts)*. Recently-closed audit
  trails list grows to include `design-1.4.md`.
- [ ] **Q4.** `CLAUDE.md` TL;DR mirrors the same
  updates; "Current state" rewritten covering
  Slices 1–4 + the MVP-gate landing.
- [ ] **Q5.** §4 carry-forward final walk — every
  Slice 1.1/2/3 carry-forward either closed in Slice 1.4
  or explicitly carried to v1.3 / Phase 2 with a
  durable home in `design-rationale.md`.
- [ ] **Q6.** This file flipped from "active" to
  "audit trail" (status header at the top of §0
  reflects ✅ closed). Phase 2's first design doc
  opens when work starts.

## 2. Order of work

`A → B → C → D → E → F → G → H` for 1.4a; `I → J → K →
L → M → N → O → P → Q` for 1.4b. Strictly linear is the
canonical order, matching Slices 2/3.

The dependency-strict edges:

- 1.4a: `A` is the foundation (shipped reviewer assets +
  in-tree templates); `B` consumes `A`'s shipped paths
  via `ReviewerAssets.PerMethod`; `C, D, E, F` only
  depend on `forge-core` (and on `ForgePaths` from
  Slice 1.2). `G` (regression suite) needs `A` + `B`
  shipped; `H` is always last.
- 1.4b: `I` is the entry-point + config; `J` consumes
  everything from 1.4a + Slice 1.3 + Slice 1.1 connectors.
  `K, L, N, O` are focused PRs that can land in any
  order after `J` lands. `M` (CLI) depends on `J`. `P`
  needs everything else; `Q` is always last.

Safe parallelisation points (not required to use):

- 1.4a: `C, D, E, F` can all land in parallel after `A`
  if reviewer attention allows; they touch disjoint
  parts of `forge-specs` / `forge-app`.
- 1.4b: `K, L, N, O` after `J` lands; again, disjoint.

In practice, Slice 1.2/3 experience says review attention
is the bottleneck — landing serially keeps per-PR
review surface manageable.

## 3. Status log

Update this section as items land. The roadmap section
ticks off only after Task 1.4.17 lands.

- 2026-05-27 — design-1.4.md created on the close of
  Slice 1.3 (`design-2.3.md` closed earlier same day). No
  Task 1.4.1 code yet. Carry-forward inheritance from Slices
  1–3: **C14**, **C15**, **S2-1** through **S2-10**,
  **S3-1** through **S3-8** — each routed to the
  Task named in §4 below.
- 2026-05-28 — plan-review rounds 1 + 2 + 3 landed
  before Task 1.4.1. Round 1 (5 findings) tightened the
  reviewer-call boundary, added explicit decomposition
  APIs on `SpecStore`, fixed `ChangeCollector`'s strict-mode
  semantics, redirected `PollBaseline` persistence to a
  sibling state file, and aligned the `forge tail` CLI
  surface with `ForgeCommand.ReadOnlyKind`. Round 2 (5
  findings) made lock acquisition conditional after
  argv-parse, moved reviewer cost + observable kill
  diagnostics out of v1 scope (carry-forward **S4-3**),
  pinned `ReviewerOutcome` (not `MonitorOutcome`) as
  Task 1.4.2's boundary, and added `decomposition` to
  `FileSpecStore`'s accessor list. Round 3 (3 findings)
  introduced an orchestrator-local
  `currentDriverSession` Ref + a deterministic
  source-selection table in Task 1.4.10 J2 so
  `DesignReviewing(round)` / `DesignPrFeedback` /
  `PieceAwaitingReview` no longer ambiguously race
  SessionMonitor + ReviewerCall, restructured `Main`'s
  boot sequence to parse argv before loading config /
  assets / lock (with `unlock --force` short-circuiting
  at step 3), and named the targets for two dangling
  `Orchestrator's <noun>` phrases. New carry-forwards
  filed: **S4-1** (poll-baseline file), **S4-2**
  (`replay` cut), **S4-3** (reviewer cost / kill
  diagnostics deferred).
- 2026-05-28 — plan-review round 4 landed before Task 1.4.1.
  Four findings against Task 1.4.10's orchestrator model. (a)
  `(PieceImplementing, hasDriver=false)` wasn't an
  illegal combination — it's the transient post-settle
  window between `Settled(Implement, Clean)` (which
  `Fsm.transition` deliberately leaves as a no-op per
  Fsm.scala:421) and the orchestrator-synthesized
  `PrOpened`. Introduced a third orchestrator sub-phase
  ("post-settle synthesis") with explicit per-phase
  recipes covering Spec post-check, DesignRevision
  asset-update / force-push-with-lease, Implement
  ChangeCollector + commit + push + createPr →
  `PrOpened`, and Fixup ChangeCollector + commit +
  push. (b) Typed `currentDriverSession` as
  `Option[ActiveSession]` carrying `(phase: SessionPhase,
  session: AgentSession)` — base trait, not
  `StreamingSession` — because
  `runHeadlessImplementation` / `runFixup` return the
  headless trait; REPL code downcasts via pattern match.
  (c) Restart recovery: the live session object cannot be
  reconstructed from log entries on process restart;
  added an explicit `InFlightSession` projection on
  `RebuildState.run` and a synthetic-`HarnessError`
  routing rule per in-flight phase × FSM state, surfacing
  every restart-with-active-session as
  `NeedsHumanIntervention` with a phase-appropriate hint.
  Filed `RebuildState.run` signature widening as new
  carry-forward **S4-4**. (d) Session-clear is now
  source-driven, not event-payload-driven (the
  `FsmEvent.HarnessError(reason: String)` payload carries
  no `SessionPhase`); the orchestrator tags each iteration
  with its source and clears `currentDriverSession`
  unconditionally when source = `SessionMonitor`. Task 1.4.10's
  test surface grew by two suites
  (`OrchestratorPostSettleSynthesisSuite`,
  `OrchestratorRestartSuite`).
- 2026-05-28 — Task 1.4.1 landed. Shipped in-tree reviewer assets
  under `assets/reviewer/{schemas,prompts}/` (3 JSON Schemas,
  6 reviewer system prompts) and `assets/templates/` (6
  templates per §11.4 / §7.7 / §14.3). `forge-app`'s sbt
  build now adds the repo-root `assets/` as an unmanaged
  resources directory so the leaves appear on the forge-app
  classpath at the same relative paths. `ForgePaths` grew
  three accessors — `userSchemasDir` / `userPromptsDir` /
  `userTemplatesDir` — so the install destinations route
  through the seam helper (no new `".forge` literals outside
  `ForgePaths.scala`).
  `io.forge.app.bootstrap.AssetInstaller.installIfMissing(paths)`
  copies each shipped resource into the matching
  `~/.forge/{schemas,prompts,templates}/` destination on
  first run, returning a per-asset `InstalledAsset(source,
  dest, Installed | Skipped)` record; existing destination
  files are preserved (operator customisation respected) and
  the first filesystem failure short-circuits with a typed
  `WriteFailed(dest, cause)`. Test coverage:
  `AssetInstallerSuite` (4 cases — first-run installs,
  idempotent re-run, partial pre-population, write failure →
  typed error) plus three schema-shape suites
  (`DesignReviewSchemaShapeSuite` /
  `PrReviewSchemaShapeSuite` / `RefineSchemaShapeSuite`, 13
  cases total) asserting every Scala ADT field maps to a
  schema property and required field where applicable,
  including the conditional `patch`-required branch on
  `refine.json` when `outcome == update_plan`. `forge-app`
  test count grew from 46 → 63; baselines for `forge-core`
  (358), `forge-agents` (181), `forge-git` (168) preserved.
  `sbt scalafmtCheckAll` clean; `sbt clean compile` clean
  under `-Xfatal-warnings`; `forge-it` still compiles.
- 2026-05-28 — Task 1.4.1 review round 1 landed (2 findings). **P2**
  (`refine.json` `patch` field wasn't constrained to
  `ManifestPatch` shape, and stray `patch` on `no_change` /
  `reopen_design` was silently permitted): tightened the
  schema to model the real `ManifestPatch(reason, ops)` wire
  shape with `oneOf` branches per `ManifestPatchOp` variant
  using the uPickle `$type` discriminator, then added a
  second `allOf` branch that forbids `patch` when
  `outcome ∈ {no_change, reopen_design}`. Backed the
  tightening with a new `RefineSchemaValidationSuite` (14
  cases) that uses networknt's JSON Schema validator against
  payloads built from real `ManifestPatch` /
  `ManifestPatchOp` values via uPickle — so the schema and
  the wire shape can no longer drift silently. **P3**
  (`AssetInstaller` silently treated any existing dest as
  `Skipped`, including a directory): tightened the
  existing-dest check to require a regular file and added
  `InvalidExistingDestination(dest, kind)` to the error
  channel, with a new `AssetInstallerSuite` case where a
  directory at the destination surfaces the typed error
  rather than a misleading success. `forge-app` test count
  46 → 82 (Task 1.4.1 landing was 46 → 63; review round 1 added
  +19 cases). All baselines preserved.
- 2026-05-28 — Task 1.4.2 landed. Shipped the
  `io.forge.app.reviewer` boundary that wraps the three
  one-shot `Connector.reviewDesign` / `reviewPr` / `refine`
  calls in a wall-clock cap. New surface: `ReviewerCall`
  trait + `ReviewerLimits(wallClockTimeout)` +
  `ReviewerOutcome[+A] = Settled(A) | Timeout |
  AdapterFailure(ReviewerError)`, plus `RealReviewerCall`
  that runs each connector method under `.attempt` (so
  `ReviewerError` sub-variants surface in the
  `AdapterFailure` channel with identity preserved per
  §7.6) and races it against `IO.sleep(cap)` —
  `Timeout` wins on a stall, cancelling the in-flight
  reviewer fiber, with subprocess cleanup left to the
  enclosing connector `Resource` finalizer (no observable
  kill channel surfaced — see new carry-forward **S4-3**).
  Non-`ReviewerError` `Throwable`s propagate unchanged.
  **B3 decision (orchestrator mapping of `Timeout`)** —
  filed option (a) against **S2-8** in
  `design-rationale.md`: orchestrator (Task 1.4.10) will map
  `ReviewerOutcome.Timeout` to
  `FsmEvent.SettleTimeout(SessionPhase.{DesignReview,
  CodeReview, Refine}, reason)`, and Task 1.4.12 lands the
  matching `Fsm.transition` handlers + `ResumeHintCoverageSuite`
  rows. `MonitorOutcome` deliberately untouched — that's
  Slice 1.3 `SessionMonitor`'s driver-phase surface.
  Test coverage: `ReviewerCallWallClockSuite` (4 cases,
  `TestControl`-driven — `IO.never` for each of the three
  reviewer methods → `Timeout` with the reviewer fiber's
  `onCancel` Ref flipped, plus a "settles before cap"
  race-loser case) and `ReviewerCallHappySuite` (8 cases
  — `Settled` for each method, each of the four
  `ReviewerError` sub-variants → `AdapterFailure` with
  variant identity preserved, plus a non-`ReviewerError`
  propagation case). `forge-app` test count 82 → 94.
  Baselines preserved (`forge-core` 358, `forge-agents`
  181, `forge-git` 168); `sbt clean compile test` and
  `sbt scalafmtCheckAll` clean; `forge-it` still compiles.
- 2026-05-28 — Task 1.4.3 landed. Re-populated the
  `forge-specs` module (lost its sources in Slice 1.2 Task 1.2.1
  when manifest types relocated to `forge-core` per **S2-1**)
  with the §4 source-of-truth boundary the orchestrator
  (Task 1.4.10) and `DocSync` (Task 1.4.4) will sit on top of. New
  surface in `io.forge.specs`: `SpecStore` trait (eight
  `IO[Either[SpecStoreError, _]]` methods covering load + save
  for `manifest.json`, `design.md`, `decomposition.md`, and
  `pieces/<p>.md`), `FileSpecStore(paths: ForgePaths)` impl,
  and `SpecStoreError = NotFound | Malformed | IoFailure`.
  `loadManifest` validates against `Manifest.validate` so a
  JSON document that parses but violates §5.1 invariants
  surfaces as `Malformed` rather than propagating a broken
  manifest forward. Atomic-write semantics mirror
  `FileStateCache.save`: sibling temp file with `SYNC`,
  `Files.move(ATOMIC_MOVE)`, parent-directory fsync (best
  effort), with stray-temp cleanup on failure — the helper is
  thinly duplicated rather than lifted to a shared module
  since both call sites are short and `forge-specs` is the
  only other consumer in v1. Routes exclusively through
  `ForgePaths.{manifest, design, decomposition, pieceSpec}`;
  no `".forge` literals in the new sources (`ForgePathsSuite`
  `os.walk` sweep clean). The §11.5 step 1 atomic-merge
  *ordering* invariant (manifest written before action log +
  state cache) is still **S2-5** carry-forward — its writer
  side closes at Slice 1.4b Task 1.4.11 against the orchestrator
  loop. Test coverage: `FileSpecStoreSuite` (17 cases —
  round-trip for every method pair, overwrite, atomic-write
  hygiene (no sibling temp leftover), on-demand parent-dir
  creation for both `specs/<feature>/` and `pieces/`,
  `NotFound` for every load method, `Malformed` on truncated
  JSON + on §5.1-invariant-violating manifest, `IoFailure` on
  parent-path collision and on read-against-directory). New
  module test count `forge-specs` 0 → 17; baselines preserved
  (`forge-core` 358, `forge-agents` 181, `forge-git` 168,
  `forge-app` 94). `sbt clean compile test` and
  `sbt scalafmtCheckAll` clean under `-Xfatal-warnings`.
- 2026-05-28 — Task 1.4.3 review round 1 landed (2 findings,
  both consistency gaps against the sibling `FileManifestStore`
  that the pre-declaration consistency sweep should have
  caught). **M1** (`loadManifest` only ran `Manifest.validate`,
  not the `schemaVersion`-supported and embedded-`featureId`-
  matches-requested-id guards `FileManifestStore.load`
  applies — so a hand-edit / stale-file swap at
  `.forge/specs/<id>/manifest.json` could load a foreign-id or
  future-schema manifest). **M2** (`saveManifest` wrote any
  `Manifest` unvalidated — since `SpecStore` is the committed
  write boundary, this could atomically persist a manifest the
  next `loadManifest` rejects as `Malformed`, bricking the
  feature). Extracted a pure `checkManifest(file, id, m)`
  guard mirroring `FileManifestStore` (schemaVersion →
  featureId identity → `Manifest.validate`, first failure
  wins, surfaced as `Malformed`); both `loadManifest` and
  `saveManifest` run it, and `saveManifest` runs it **before**
  `atomicWriteBytes` so a rejected save never touches the
  existing file. Added 5 regression rows (mirroring
  `ManifestStoreSuite`'s identity + schema-version cases on
  the load side, plus save-side variants asserting the prior
  committed manifest survives intact on a rejected overwrite).
  `forge-specs` test count 17 → 22. Baselines preserved;
  `sbt compile test` and `sbt scalafmtCheckAll` clean.
- 2026-05-28 — Task 1.4.4 landed. Shipped the manifest →
  `decomposition.md` render path per **M1** / §5.3. New
  surface in `io.forge.specs`: `DocSync` trait
  (`renderDecomposition` / `writeDecomposition`, both
  `IO[Either[DocSyncError, _]]`), `FileDocSync(paths, store)`
  (reads the template from `ForgePaths.userTemplatesDir`, the
  manifest via the injected `SpecStore`, writes the render
  back through `SpecStore.saveDecomposition` so the
  atomic-write invariant is shared), `DocSyncError =
  TemplateMissing | TemplateMalformed | RenderFailure |
  SpecStoreFailure`, and the template engine itself,
  `HandlebarsLite`. **D2 decision** (filed as
  `design-rationale.md` §"PR body & templates" **T2**): a
  hand-rolled Handlebars-subset renderer rather than a
  third-party engine, keeping `forge-specs` at its `osLib` +
  `upickle` floor. `HandlebarsLite` supports `{{path}}`
  (dotted), `{{#if}}`, `{{#each}}` (rebinding `this`), named
  helpers (`statusBadge`), `{{!-- --}}` comments, and
  mustache standalone-line trimming; failures surface as
  `RenderError.{Parse, Eval}`, which `FileDocSync` maps to
  `TemplateMalformed` / `RenderFailure`. `renderDecomposition`
  is pure over the manifest, so the byte-identical re-render
  property `forge reconcile` (Task 1.4.15) depends on holds by
  construction. Test coverage: `HandlebarsLiteSuite` (27
  cases — each construct, each failure mode, standalone
  trimming) and `DocSyncSuite` (9 cases — idempotent
  render→write→re-read→re-render, per-`PieceStatus` badge,
  the `feature.designPr` `{{#if}}` branch, and all four
  error channels). `DocSyncSuite` renders the **shipped**
  `assets/templates/decomposition.md.hbs`, wired onto the
  `forge-specs` test classpath via a one-line
  `Test / unmanagedResourceDirectories += .../assets` in
  build.sbt (mirrors `forge-app`'s `Compile` wiring), so the
  renderer can't drift from the real template. **Deviation
  from D4 wording:** manifests are built inline via the same
  helper shape as `FileSpecStoreSuite` (the sibling idiom)
  rather than JSON fixtures under `test/resources/` — the
  manifest contract stays type-checked and there's no fixture
  to drift. `forge-specs` test count 22 → 58; baselines
  preserved (`forge-core` 358, `forge-agents` 181,
  `forge-git` 168, `forge-app` 94). `sbt clean compile test`
  and `sbt scalafmtCheckAll` clean under `-Xfatal-warnings`;
  `forge-it` still compiles.
- 2026-05-28 — Task 1.4.4 review round 1 landed (3 findings,
  all from the "re-walk the contract, don't patch the cited
  line" discipline — the consistency sweep over *all* shipped
  templates found a second unsupported construct the review
  hadn't named). **P2 (markers):** `DocSyncSuite` /
  status-log over-claimed §5.3-completeness for what is still
  Task 1.4.1's placeholder template (coarse
  `forge:decomposition:begin/end`, not §5.3's
  `forge:order-start`/`piece`/`editable-summary`/`status`
  reconcile markers). Reworded the suite's scope note + the
  marker assertion to verify comment-passthrough only, and
  filed the gap explicitly on Task 1.4.6 **F2** (replace
  markers + tighten the suite to the §5.3 region shape).
  Template content untouched (F2 owns it). **P2 (renderer):**
  the T2 rationale claimed `HandlebarsLite` renders the other
  shipped templates, but `pr-body.md.hbs` uses
  `{{#if mergedPieces.length}}` and **all four**
  `*-answers.md.hbs` use `{{@index}}` — both rendered
  silently-wrong (not errors). Per the round-decision
  (`AskUserQuestion`: extend vs defer → **extend**), added
  `Value.Num`, `array.length` (numeric, `0` falsy), and
  0-based `{{@index}}` (threaded as a `data` frame through
  the evaluator and bound per `#each` iteration); templates
  left untouched. New `ShippedTemplateRenderSuite` renders
  every shipped template through the renderer so the
  supported set can't silently drift again. **P3:** empty
  block args (`{{#if}}` / `{{#each}}`) and empty tags
  (`{{}}` / `{{ }}`) now surface as `RenderError.Parse`
  (→ `TemplateMalformed`) rather than resolving to the
  current scope and rendering — restores the malformed
  channel for operator-customised templates. Test coverage:
  `HandlebarsLiteSuite` +9 (`.length`, `@index`, four
  empty-arg/empty-tag Parse rows), new
  `ShippedTemplateRenderSuite` (9 — every template renders
  + the `.length` guard both ways + `@index` numbering).
  `forge-specs` test count 58 → 76; baselines preserved.
  `sbt clean compile test` and `sbt scalafmtCheckAll` clean;
  `forge-it` compiles.
- 2026-05-28 — Task 1.4.4 review round 1 follow-up (indexing
  UX). Kept `{{@index}}` 0-based in the renderer (matches
  Handlebars; no surprise) but stopped shipping `Q0` in the
  human-facing answer files: added a `questionNumber` helper
  (0-based `@index` → 1-based display) and moved the helper
  registry into a shared `io.forge.specs.TemplateHelpers`
  (`statusBadge` + `questionNumber`) that `FileDocSync` and
  the future orchestrator answer-renderer both draw from. The
  four `*-answers.md.hbs` templates now use
  `## Q{{questionNumber @index}}.`; `ShippedTemplateRenderSuite`
  asserts `Q1`/`Q2` (was `Q0`/`Q1`), and `HandlebarsLiteSuite`
  gains a helper-applied-to-`@index` row. Deliberately did
  **not** add `{{@index + 1}}` expression support — that
  expands the template language for no real gain.
  `forge-specs` test count 76 → 77. Clean compile / test /
  scalafmt; `forge-it` compiles.
- 2026-05-28 — Task 1.4.5 landed. Shipped `io.forge.specs.ChangeCollector`
  — the §10.1 Allow / Deny / Ask classifier — plus `StagingConfig`
  and `FileChange` / `FileChangeKind`. `ChangeCollector.classify`
  is **pure** over `(repoRoot, Vector[FileChange], StagingConfig)`
  (no filesystem / git access); `DefaultChangeCollector`
  implements the five §10.1 rules with two notes: (i) the
  outside-repo guard (rule 2) runs first so deny-pattern globbing
  never sees a `../`-escaped path — all of rules 1–4 yield `Deny`
  so the reorder only changes the reported reason, not the
  outcome; (ii) batch aggregation is `Deny` > `Ask` > `Allow`.
  **E2 decision (filed as design-rationale CC4):** glob matching
  via `java.nio.file.PathMatcher` (zero new deps) with a
  `**/`-prefix workaround — Java's glob `**/.env` does **not**
  match a repo-root `.env` (empirically verified; a security hole
  since `**/.env` is a §18 default deny), so each `**/`-prefixed
  pattern also compiles the prefix-stripped variant. **Deviation
  from the E1 sketch:** `FileChange` gained a
  `gitIgnored: Boolean = false` field because the git-less
  classifier can't compute rule 4 itself — the orchestrator
  (Task 1.4.10), which owns the git seam, populates it from
  `git status --porcelain --ignored`. The rule-4 carve-out
  ("unless under `.forge/specs/...`") routes through a new
  `ForgePaths.specsRoot` accessor so no `.forge` literal escapes
  the path seam (`ForgePathsSuite`'s `os.walk` sweep stays green);
  `featureSpecDir` now derives from `specsRoot`. `StagingConfig`
  uses default args (a partial `staging` JSON merges the §18
  defaults) and exposes `StagingConfig.DefaultDenyPatterns` as the
  single source of truth for the §18 deny list (Task 1.4.9's
  `ForgeConfig` default will draw from it). Test coverage: the
  five E6 suites — `ChangeCollectorAllowSuite` (4),
  `ChangeCollectorDenySuite` (36 — every §18 deny pattern at root
  *and* nested depth, rules 2–4, multi-offender listing),
  `ChangeCollectorStrictAllowSuite` (2),
  `ChangeCollectorStrictAskSuite` (2 — well-formed `Question`,
  mixed allow/ask set), `ChangeCollectorDenyPrecedesAllowSuite`
  (2). `forge-specs` test count 77 → 123; baselines preserved
  (`forge-core` 358, `forge-agents` 181, `forge-git` 168,
  `forge-app` 94). `sbt clean compile test` and
  `sbt scalafmtCheckAll` clean under `-Xfatal-warnings`;
  `forge-it` compiles.
- 2026-05-28 — Task 1.4.5 review round 1 landed (2 findings, both
  on the `Ask` surface the orchestrator (Task 1.4.10) will consume).
  **F1 (default-Deny was unstructured):** the `Ask` `Question`
  emitted `options = Vector("Allow", "Deny")` but nothing encoded
  the §10.1 "default option Deny" — a Q&A pane had no structured
  way to know the safe answer. Per `AskUserQuestion` (chose
  structured field over option-ordering convention), added a
  trailing `defaultOption: Option[String] = None` to `forge-core`
  `Question` (driver-originated questions stay `None` — the wire
  shape has no default; the trailing default means **zero** changes
  to the `ReviewDecoders` / `HaltWithQuestion` call sites) and set
  `Some("Deny")` on the ChangeCollector `Ask`. **F2 (`Ask` dropped
  the file↔question association):** `Ask(questions: Vector[Question],
  included)` forced the orchestrator to reconstruct which file each
  question was about from input ordering or by parsing the path out
  of the question text. Changed to
  `Ask(asked: Vector[(FileChange, Question)], included)` so the
  answer maps straight back to the file it stages and into the stage
  plan. `ChangeCollectorStrictAskSuite` now asserts the
  file-pairing, ordering, and `defaultOption == Some("Deny")`.
  `forge-specs` 123 (count unchanged); all baselines preserved;
  `sbt clean compile test`, `sbt scalafmtCheckAll`, `forge-it`
  compile all clean.
- 2026-05-28 — Task 1.4.6 landed. Shipped the v1 content for the
  PR-body / decomposition / answer templates the orchestrator
  (Task 1.4.10) renders. **F2 (decomposition §5.3 markers):**
  replaced Task 1.4.1's coarse `forge:decomposition:begin/end`
  placeholder with the §5.3 reconcile marker set —
  `forge:order-start`/`-end` wrapping a numbered piece list,
  and per-piece `forge:piece <pid>`, `forge:editable-summary
  <pid>`, `forge:status <pid>`. `DocSyncSuite`'s stale
  placeholder-marker scope note is removed and a §5.3
  region-shape test added (order region appears exactly once
  and wraps every `forge:piece` marker in manifest order;
  editable-summary carries the summary text between its
  markers; the old placeholder markers are asserted gone). The
  existing byte-identical re-render test already proves the
  idempotence `forge reconcile` (Task 1.4.15) depends on.
  **F3 (answer templates):** Task 1.4.1 shipped four of the five
  §7.7 answer templates; the missing
  `design-review-r1-answers.md.hbs` (§11.2 pre-PR review loop,
  distinct from the post-PR `design-pr-feedback` loop) was
  added and registered in `AssetInstaller.TemplateLeaves`,
  `AssetInstallerSuite.allExpectedDestinations` (now 16
  destinations), `ShippedTemplateRenderSuite`, and the new F4
  suite. **F1 (pr-body):** verification-only — Task 1.4.1's
  template already rendered feature/piece title, `{{piece.spec}}`
  (acceptance criteria live in the spec body — the manifest
  `Piece` has only `acceptanceHash`, no separate field), the
  merged-pieces section, and the audit summary. **F4
  (golden suite):** new `TemplateRenderSuite` renders all
  seven templates against one fixed context and compares
  byte-for-byte to checked-in goldens under
  `src/test/resources/golden/`; `FORGE_UPDATE_GOLDEN=1`
  regenerates and is the only write path — a missing golden
  fails rather than silently writing. Complements
  `ShippedTemplateRenderSuite` (renderer can-handle guard) by
  pinning exact output, most importantly the §5.3 marker shape.
  `forge-specs` test count 123 → 132 (+7 golden, +1 DocSync
  region-shape, +1 ShippedTemplateRenderSuite row); `forge-app`
  unchanged at 94 (same test methods, AssetInstaller now
  installs 16 assets). Baselines preserved (`forge-core` 358,
  `forge-agents` 181, `forge-git` 168). `sbt clean compile
  test` and `sbt scalafmtCheckAll` clean under
  `-Xfatal-warnings`; `forge-it` compiles.
- 2026-05-28 — Task 1.4.7 partial landing (G1, G2 done; G3 scoring
  done, full bar run + G4 / G5 / C15 still open). Shipped
  `ReviewerRegressionSuite` in `forge-it` — six method × connector
  tests, opt-in via `FORGE_IT_RUN_REGRESSION=1`, each building the
  connector with `reviewerAssets` installed into a throwaway
  `~/.forge` through the real `AssetInstaller` path and driving
  `RealReviewerCall` (Task 1.4.2), so it exercises the same
  install→bind→decode path the orchestrator will. **G2 deviation
  (taken with the maintainer):** the §16 bar measures the model's
  structured-output *reliability*, which the established idiom
  (`CodexHaltWithQuestionReliabilitySuite`) samples with one input ×
  20 runs; we follow that with a **small real fixture set** (3 inputs
  per method, cycled to 20 samples) rather than 60 hand-authored
  files. Fixtures under
  `modules/forge-it/src/test/resources/regression/`: 3 design-review
  markdowns, 3 PR-review diffs captured verbatim from real commits
  (`adb3791` / `f873169` / `705796f`) + piece specs + changed-file
  lists, 3 refine designs (manifest built inline from a real
  `Manifest` value, type-checked, no fixture drift — `DocSyncSuite`
  idiom). **G3 scoring:** `Settled` = pass, `StructuredOutputMissing` /
  `Malformed` = schema-fail (counts against the bar), transient
  `ReviewerProcessFailure` / wall-clock `Timeout` retried before
  scoring (don't count, §7.6), assert `passes ≥ 19/20` with a
  per-sample breakdown clue. A separate cheap single-call wiring
  smoke gate (`FORGE_IT_RUN_REGRESSION_SMOKE=1`) proves the plumbing
  without the full batch. **Blocker found + fixed (bundled per
  maintainer decision):** the wiring smoke immediately caught that
  **Claude CLI 2.1.153 dropped the `structured_output` envelope
  field** — the schema-conformant payload now arrives as the `result`
  string (the 2.1.150 Slice 0 transcript had `structured_output`), so
  `ClaudeConnector.extractStructuredOutput` was failing 100% of
  reviewer calls. Fixed back-compatibly (prefer `structured_output`,
  else parse `result` as JSON — the same shape the Codex path reads
  from `agent_message.text`); filed as design-rationale **C16**.
  Added 4 `ClaudeConnectorSuite` tests (3 `extractStructuredOutput`
  shape cases + 1 fake-CLI end-to-end on the 2.1.153 shape, the
  fake-mirrors-real discipline). The live smoke then returned
  `Settled` against the real 2.1.153 CLI (clean schema decode) —
  wiring + fix confirmed. **Watch-item:** that single live call took
  ~90 min wall-clock despite a 3-min per-call cap; most likely Claude
  rate-limiting/backoff on the day, but the per-call wall-clock cap's
  effectiveness under sustained CLI-internal backoff (the **S4-3**
  cancellation caveat) needs verifying before the full 6×20 batch is
  kicked off. **Still open:** the live ≥19/20 measurement across all
  six pairs (G3 bar / G4 / G5) is an opt-in run (~tens of minutes to
  hours, real CLI spend) **not yet executed**; **C15 stays open** and
  its `design-rationale.md` "Action required" is unchanged until that
  run confirms the bar. `forge-agents` test count 181 → 185 (+4);
  baselines preserved (`forge-core` 358, `forge-git` 168,
  `forge-app` 94, `forge-specs` 132). `sbt test` green;
  `sbt scalafmtCheckAll` clean under `-Xfatal-warnings`; `forge-it`
  compiles.
- 2026-05-28 — Task 1.4.7 review round 1 (3 findings). **Finding 1
  (High — cap safety):** the ~90-min live-smoke duration was flagged
  as a possible wall-clock-cap defect that would make the full batch
  unsafe. Wrote the requested subprocess-backed test —
  `ReviewerCallSubprocessTimeoutSuite` (forge-app) drives
  `RealReviewerCall` against a real fake-CLI subprocess (silent-hang
  *and* chatty-hang variants) with a 2-s cap; both return `Timeout`
  in ~2 s real wall-clock. So the cap **does** bound real time
  (unlike the existing `IO.never`/`TestControl` test, which can't
  prove that); the 90-min figure is a measurement artifact (OS sleep
  during the long run). S4-3 watch-item updated from "verify before
  batch" to "verified bounded". **Finding 2 (Medium — wording):**
  "closes C15" overclaimed while G3/G4/G5 are open; reworded the
  suite docstring and the Task 1.4.7 header to "hosts the C15 gate;
  closes once the bar is met". **Finding 3 (Low — Codex smoke):**
  the cheap `FORGE_IT_RUN_REGRESSION_SMOKE=1` wiring smoke covered
  only Claude; added a symmetric Codex single-call smoke (shared
  `assertWiresEndToEnd` helper). `forge-app` test count 94 → 96 (+2
  subprocess-timeout cases); other baselines preserved. `sbt test`
  green; `sbt scalafmtCheckAll` clean; `forge-it` compiles. C15
  remains open (full live bar run still pending).
- 2026-05-28 — Task 1.4.7 review round 1 follow-up: **Codex reviewer
  path fixed (second connector bug the smoke caught).** The new
  Codex wiring smoke (Finding 3) 400'd: Codex's `--output-schema`
  enforces **OpenAI strict mode**, a more restrictive JSON Schema
  subset than Claude's lenient `--json-schema`. Live `codex exec`
  surfaced three rules the shipped schemas broke (one at a time):
  every property must be in `required`; `oneOf`/`allOf`/`if`/`then`
  forbidden (`anyOf` allowed); a `const`-only property needs a
  `type`. Reauthored all three shipped schemas in the strict subset —
  which Claude also accepts, so the §17 "schemas are shared" design is
  preserved (rejected per-connector schemas; see design-rationale
  **C17**). Changes: `design-review.json` / `code-review.json` —
  optional `ReviewBlocker`/`Question` fields added to `required`
  (already nullable), dropped `minimum`; `refine.json` — `oneOf` →
  `anyOf`, `$type` const gains `"type":"string"`, and the
  `allOf`/`if`/`then` "patch iff update_plan" conditional dropped in
  favour of always-required-nullable `patch`. That §14.3 invariant is
  unchanged — it was already enforced by `ReviewDecoders.refineResult`
  (`ReviewDecodersSuite` covers it); the schema conditional was
  redundant. Verified: manual `codex exec --output-schema` accepts all
  three (agent_message parses as schema-conformant JSON) + the live
  Codex wiring smoke. `SchemaShapeSuites` (blocker/question/refine
  `required` + `anyOf` + forbidden-keyword guard) and
  `RefineSchemaValidationSuite` (schema-vs-decoder split) updated to
  the new shape. All baselines preserved (`forge-app` 96,
  `forge-agents` 185, `forge-core` 358, `forge-git` 168, `forge-specs`
  132); `sbt test` green; `sbt scalafmtCheckAll` clean; `forge-it`
  compiles. **C15 now unblocked on both connector paths** (Claude
  envelope fix **C16** + Codex schema fix **C17**); the full live
  ≥19/20 batch across all six pairs is the remaining opt-in run.
- 2026-05-28 — Task 1.4.7 cheap-smoke re-run (per "cheap smoke first"
  before committing to the full batch) on the upgraded **Claude CLI
  2.1.156** (was 2.1.153 at the C16 fix). **Codex pair clean
  (`Settled`); Claude design-review pair failed `StructuredOutputMissing`**
  ("`result` not valid JSON: got \"B\" at index 0"). Root-caused with a
  direct envelope capture using the exact `reviewerArgv` flags: the
  envelope shape is unchanged (still the `result` string per C16), but
  `--json-schema` on 2.1.156 **validates without hard-enforcing** —
  `claude --help` now describes it as "structured output *validation*",
  and the model returned a conformant object **wrapped in prose** on a
  larger fixture (bare JSON on a trivial one). The orchestrator (1.4b)
  will hit the same prose leakage, so this is a connector-robustness gap,
  not a test artifact. **Fix (filed as design-rationale C18):**
  `extractStructuredOutput` gains a salvage step
  (`parseResultPayload` → `salvageJsonObject`) — when a clean
  `ujson.read(result)` fails, recover the first balanced JSON object via a
  string/escape-aware brace-depth scan and accept it only if it parses;
  a prose-only `result` still surfaces `Left`. Belt-and-braces, the three
  `*.claude.md` reviewer prompts were tightened ("first char `{`, last
  char `}`, no preamble/fences" + a strict "Output format" footer) to
  lower the leak rate at the source. `ClaudeConnectorSuite` 26 → 31 (+5:
  two `extractStructuredOutput` salvage cases, two `salvageJsonObject`
  unit cases, one fake-CLI end-to-end on the 2.1.156 prose shape).
  `forge-agents` 185 → 190; other baselines preserved (`forge-core` 358,
  `forge-git` 168, `forge-app` 96, `forge-specs` 132). **C15 still
  open** — the validate-vs-enforce shift means the salvage + prompt
  tightening must be confirmed by the full live 6×20 batch (G3/G4/G5)
  before C15 closes; if Claude pairs still miss the bar, G4 prompt
  tightening is the next lever (don't loosen the decoder).
- 2026-05-29 — Task 1.4.7 first real N=20 measurement + rational fix
  (C18 follow-up). Ran the pairs incrementally rather than the full
  6×20 at once (maintainer steer). design-review/claude, refine/claude
  and all codex pairs cleared at small N; **pr-review/claude measured
  18/20** — one short of the bar, two `StructuredOutputMissing` that
  salvage couldn't recover (bare `{`, not prose-wrapped). Diagnosed
  without guessing (maintainer steer: "verify truncation, don't
  band-aid"): added an opt-in raw-envelope dump
  (`FORGE_REVIEWER_RAW_DUMP_DIR`) + a `resultDiagnostic`
  (len/stop_reason/output_tokens/brace-balance/raw-control-chars) on
  the failure `Left`. Offline analysis of a captured batch **ruled out
  truncation** (every envelope parsed whole → not our draining; every
  `stop_reason=end_turn` → model never cut off; brace-balanced; ~11.8k
  output tokens with no cap), and a local `ujson` probe confirmed
  `ujson` rejects raw control chars inside strings. Root cause:
  **literal newlines inside a multi-line `summary`**. Fix:
  `parseResultPayload` now escapes control chars *inside string
  literals* (`normalizeControlCharsInStrings`) before re-parsing and
  salvaging — tolerant of the model's formatting **without** the
  rejected band-aid of forcing a one-line summary; an unescaped `"`
  remains an unrepairable (rare) schema-fail surfaced with its reason.
  `ClaudeConnectorSuite` 31 → 36. Remaining: re-measure
  pr-review/claude at N=20 under the fix; **C15 stays open until all
  six pairs clear ≥19/20.**
- 2026-05-29 — Task 1.4.7: pinned the Claude reviewer model + first
  **full 6×20 batch (Claude on haiku, Codex on gpt-5.3-codex) — all
  six pairs ≥19/20** (64 min, 120 calls). Found the reviewer was
  silently inheriting the CLI default model (Opus 4.8, ~$0.40/call);
  added `reviewerModel` to `ClaudeConnector` + `FORGE_IT_CLAUDE_MODEL`
  to the suite (default = inherit) so batch cost/bar are reproducible.
  Offline analysis of the 60 dumped Claude envelopes: **haiku wraps
  every response in a ```json fence + pretty-prints it** — so the C18
  **salvage** path carried 60/60; haiku's newlines are structural
  (handled once the fence is stripped), so it did **not** exercise the
  **normalize** (in-string control char) path that fixed the Opus
  18/20 case — that remains unit-proven, not yet live-proven. Per the
  staged plan a **sonnet** full batch follows (production-realistic
  reviewer); an Opus live re-run is deprioritised on cost (the unit
  test pins the exact Opus failure shape). C15 stays open pending the
  production-model bar decision; mechanics validated on the messiest
  model.
- 2026-05-29 — Task 1.4.7: full **sonnet** 6×20 batch. **5/6 pairs
  ≥19/20; pr-review/claude 16/20 — but `schemaFail=0`.** The parse
  fix is fully validated: sonnet emits **bare** objects (not haiku's
  fences), 5 of them multi-line, so it **live-exercised the normalize
  (in-string control char) path** with zero schema failures — the
  live confirmation the haiku run couldn't give. The 4 misses are all
  **Timeout**: sonnet pr-review (largest input) runs a median ~77s but
  tails past the suite's **3-min wall-clock cap** (slowest completed
  162s; 4 exceeded 180s, even after their one retry). Genuine latency,
  not a hang or rate-limit (durations spread 3–162s). So the bar miss
  is a **cap-too-tight-for-sonnet** issue, not correctness. Added
  `FORGE_IT_REGRESSION_CAP=<seconds>` so the bar can be re-measured
  under a production-realistic cap (connector default is 5 min). Open
  decision (ties to S4-3 / Task 1.4.9 config): the production reviewer
  **(model, wall-clock cap)** pair — haiku@3min cleared all six;
  sonnet needs a >3min cap on large PRs. C15 stays open pending that
  decision + a clean full batch under it.
- 2026-05-29 — Task 1.4.7: sonnet pr-review/claude re-run at **5-min
  cap → still 16/20** (3 Timeout + 1 SchemaFail). Two clean findings,
  both from the now-complete diagnostics: (1) the **first
  fully-diagnosed schema fail** is the documented-**unrepairable**
  case — `braces=balanced rawControlChars=0 underlying: "expected , or
  } got w"` = an **unescaped `"`** inside the summary (`Some("Deny")`);
  1/20 is within the bar's tolerance, so this is not the blocker. (2)
  The blocker is **latency**: completed pr-review calls run p50 114s /
  p90 218s / max 257s, with ~15% (3/20) exceeding even 300s — a smooth
  heavy tail (genuine slowness on large diffs, not rate-limit spikes).
  **Sonnet is latency-prohibitive for pr-review under any reasonable
  wall-clock cap** (would need ~7-8 min). **haiku@3min remains the
  only clean full-batch config (6/6).** Recommendation pending
  maintainer: adopt **haiku@3min** as the v1 reviewer default and
  close C15 against it; sonnet's latency tail + residual
  unescaped-quote schema tail are recorded as the rationale and feed
  the production (model, cap, timeout-retry) config decision (Task
  1.4.9 / S4-3). The unescaped-quote mode is a known residual the
  normaliser deliberately can't repair (ambiguous); within 1/20
  tolerance, possible future hardening.
- 2026-05-29 — **Task 1.4.7 closed; C15 resolved.** Maintainer adopted
  **haiku @ 3-min cap** as the v1 reviewer config. The C15 ≥19/20 bar
  is **met for all six method × connector pairs** in the full live
  batch (claude reviewer on haiku, codex on gpt-5.3-codex). G3/G4/G5
  ticked; Task 1.4.7 header flipped to ✅. C15 marked closed in
  `design-rationale.md`; roadmap §7.2 C15 bullet updated. The reviewer
  **(model, wall-clock cap, timeout-retry)** production tuning — and
  whether a heavier model (sonnet/opus) is worth a much larger cap for
  review quality — is deferred to Task 1.4.9 / **S4-3** (sonnet's
  latency tail + the residual unescaped-quote schema mode are the
  recorded inputs to that decision). Remaining 1.4a work is Task 1.4.8
  (1.4a close-out).
- 2026-05-29 — **Task 1.4.8 landed — Slice 1.4a closed, handed off to
  1.4b.** H1 section review on Task 1.4.1–1.4.7: all six checklist
  items confirmed green (reviewer schema/prompt pairs + shape suites;
  six-pair regression coverage; `ReviewerOutcome.Timeout`
  fiber-cancellation-only docstring + S4-3; `DocSync` byte-identical
  re-render; all 16 §18 deny patterns enforced via
  `StagingConfig.DefaultDenyPatterns`; end-to-end `ReviewerCall` →
  `ReviewerOutcome` → orchestrator-mapping coherence). Build green at
  close — `forge-core` 358, `forge-agents` 196, `forge-git` 168,
  `forge-specs` 132, `forge-app` 96; `sbt scalafmtCheckAll` +
  `sbt clean compile test` clean; `forge-it` compiles. One
  non-blocking watch item logged under H1 (the
  `ChangeCollectorDenySuite.denyCases` mirror of `DefaultDenyPatterns`
  has no drift-guard assertion — all 16 currently covered). H2
  carry-forward walk: C15 closed; C16/C17/C18 filed; S2-8/S3-5 (B3
  option a) → Task 1.4.12; C14 → Task 1.4.14; S4-3 watch item; S4-5 →
  Task 1.4.9; S4-1/S4-2/S4-4 file at their 1.4b landing Tasks. H3/H4/H5
  doc updates landed in `roadmap.md` §2.4, `AGENTS.md` "Current state"
  + active-design pointer, and `CLAUDE.md` TL;DR (stale "refresh lands
  at H5" note removed). The §2.4 roadmap `[~]` bullets stay `[~]`
  until Task 1.4.17. **Slice 1.4b opens at Task 1.4.9** (`forge-app`
  entry skeleton + config loader).
- 2026-05-29 — **Task 1.4.9 landed — `forge-app` entry skeleton +
  config loader.** I1: `io.forge.app.config.ForgeConfig` mirrors §18
  field-for-field (nested `Ci`/`Claude`/`Codex`/`Settle`/`Github`/
  `BaseFreshness` blocks; `staging` reuses `forge-specs`
  `StagingConfig` so the deny list can't drift); `ForgeConfigLoader`
  merges §18 defaults → `.forge/config.json` → per-key
  `.forge/overrides/<key>.json` at the `ujson` layer and decodes once
  (lenient default-arg merge), with `ConfigError.{Malformed,IoFailure}`
  and a *missing* config treated as `ForgeConfig.Default` (not an
  error). New `ForgePaths` accessors `configFile` / `overridesDir` /
  `overrideFile(key)` keep the `.forge` seam intact. I3:
  `ForgeCommand.ReadOnlyKind` cuts `Replay` for `Tail`
  (`Status | Tail | RebuildState`); filed as **S4-2** in
  `design-rationale.md` (`RealBranchManager` matches `ReadOnly(_)`, so
  preflight is unchanged). I2: `io.forge.app.Main extends IOApp` (not
  `IOApp.Simple` — the boot needs argv + a non-zero `ExitCode`, which
  `Simple` exposes neither of; minor design-sketch correction, no spec
  implication) with the two-phase boot — phase-1 class parse → repoRoot
  resolve → `unlock --force` short-circuit (fully wired via
  `FileProcessLock.forceRelease`, loads no config) → config load →
  phase-2 parse → lock acquire for state-changing commands → dispatch.
  `io.forge.app.cli` (phase-1/2 parser, `CommandClass`, `CliError`),
  `io.forge.app.command` (`CommandRouter`, per-class `*Context` types,
  eleven handler shells exiting `70` = not-implemented; `unlock` is
  real). **Deferred to Task 1.4.10 (I5 — no scope expansion):** reviewer-
  asset install + connector construction (boot steps 5–6) + the action
  log join the lock's `Resource` bracket when the orchestrator handlers
  need them; `Invocation.needsConnector` already classifies the set.
  I4: `ForgeConfigLoaderSuite` (defaults / partial / malformed /
  non-object root / bad enum / per-key + scalar override / override
  without base / malformed override) + `CliParserSuite` +
  `MainSuite` (boot wiring, exit codes, lock acquire/release,
  config-skip on `unlock --force`). Build green — `forge-app` **130**
  (was 96), all other modules unchanged (`forge-core` 358,
  `forge-agents` 196, `forge-git` 168, `forge-specs` 132);
  `sbt clean compile test` + `sbt scalafmtCheckAll` clean; `forge-it`
  compiles. S4-5 (reviewer model/cap/retry tuning) deliberately **not**
  added as config keys — that's a §18 extension for `forge-design-1.3.md`,
  not a silent field; reviewer model/cap stay at the Task 1.4.7 v1
  values in the reviewer-call wiring until then.

## 4. Carry-forward (inherited + new)

Items the section closure (Task 1.4.17) **must not silently
bury** when it flips the §2.4 roadmap bullet. Each one
either resolves in a named Task or carries forward
explicitly to v1.3 / Phase 2 with a durable home in
`design-rationale.md`.

### Inherited from Slices 1–3, resolved in Slice 1.4

- **C14** — Codex resume system-prompt prepending. Two
  halves: the spec-text decision (v1.3 §7.10(a)) and
  the orchestrator implementation. **Resolves in Task 1.4.14.**
- **C15** — Native schema regression suite (originally
  Slice 1.1's PR-D in design-2.1.md, deferred per C15;
  now resolved as **Task 1.4.7 here**).
  Resolves once all 6 method × connector pairs meet the
  ≥19/20 bar.
- **S2-5** — Writer-side atomic-merge ordering test.
  **Resolves in Task 1.4.11** with a focused fault-injection
  test against the orchestrator's manifest-write
  ordering.
- **S2-8** — `Fsm.transition` doesn't handle
  `SettleTimeout` for reviewer/refine phases. **Resolves
  in Task 1.4.12** via whichever path Task 1.4.2's B3 chose
  (explicit FSM handlers vs orchestrator-side
  conversion).
- **S3-5** — `SessionMonitor` carve-out matches **S2-8**
  on the SessionMonitor side. Closes alongside
  **S2-8** in Task 1.4.12 (1.4a Task 1.4.2's reviewer wrappers ship
  the wall-clock emission path).

### Inherited from Slices 1–3, conditional / watch items

These resolve only if Slice 1.4 surfaces a measurable cost
cliff. If they stay quiet under MVP-run load, they roll
into v1.3 as documented defaults. **Walked in Task 1.4.15.**

- **S2-3** — `ActionLog` write durability vs throughput
  (per-batch `force()` fallback if perf cliff fires).
- **S2-9** — `verifyAgainstLog` always writes the cache
  (byte-compare-and-skip if hot).
- **S3-2** — `BranchProtectionCache` is process-local
  in-memory (on-disk persistence if `gh api` round-trip
  is slow per resume).
- **S3-4** — `PRWatcher` rate-limit cliff threshold
  (config knob if 3-consecutive feels wrong).

### Inherited from Slices 1–3, no Slice 1.4 code work

These need v1.3 spec-text edits, not Slice 1.4 code.
Task 1.4.17's close-out checklist verifies each is filed in
`design-rationale.md` and surfaced in `roadmap.md`
§7.2.1.

- **S2-1** — Manifest data types relocated; v1.3 §3.2
  re-attribution. Already filed; Task 1.4.17 verifies.
- **S2-2** — `FsmEvent` ADT shape needs spec text.
  Already filed; Task 1.4.17 verifies. Slice 1.4's orchestrator
  is the first consumer of the de-facto ADT — if any
  event variant turns out under-specified during Task 1.4.10,
  Task 1.4.17 notes it under S2-2 for v1.3 to address.
- **S2-4** — `PrSnapshot` ownership doc fixed in
  Slice 1.2 Task 1.2.7. Closed; Task 1.4.17 verifies.
- **S2-6** — `Feature.designPrFeedbackRound: Int` v1.3
  §6 addition. Code already correct; v1.3 text
  follows.
- **S2-7** — `fsm.transition` payload encoding v1.3
  §19 worked-example lift. Code already correct.
- **S2-10** — `audit.piece_merged` payload key
  tightened to `"p"`. v1.3 §19 pin. Code already
  correct.
- **S3-1** — `forge-git` subprocess utility ownership
  (no spec change needed). Filed; Task 1.4.17 verifies.
- **S3-3** — `GhClient` / `GitClient` trait
  abstractions (testability seam). Filed; Task 1.4.17 verifies.
- **S3-6** — `gh pr create` URL parse contract; v1.3
  BM8 correction. Code already correct.
- **S3-7** — `PollBaseline` cursor shape; v1.3 RL2 +
  §6 / §9 update. Code already correct.
- **S3-8** — `reviewDecision: ""` flattening; v1.3
  §9 quirk note. Code already correct.

### New in Slice 1.4 (to be reconciled at Task 1.4.17)

Items surfaced during plan-review and during landing of
the Tasks. Each gets a `S4-N` identifier and a Task
anchor; Task 1.4.17's H1-equivalent walk reconciles
expected-vs-actual.

- **S4-1 — Poll-baseline persistence location.** Surfaced
  at plan-review pre-Task 1.4.1. `Manifest` / `Piece`
  (`modules/forge-core/src/main/scala/io/forge/core/manifest/`)
  carry no baseline fields, so Task 1.4.10 persists
  `PollBaseline` to a sibling file
  `.forge/state/<feature>.poll-baselines.json`
  via a new `ForgePaths.pollBaselineFile(featureId)`
  accessor. v1.3 impact: §6 `Feature` may want a typed
  projection `pollBaselines: Map[PrNumber, PollBaseline]`
  (rebuilt from the file via `RebuildState.run`); §11.0
  precondition list grows by one file. Filed at Task 1.4.10
  close in `design-rationale.md`.
- **S4-2 — `forge replay` cut in favour of `forge tail`.**
  Surfaced at plan-review pre-Task 1.4.1. Slice 1.3 Task 1.3.3's
  `ForgeCommand.ReadOnlyKind` carried `Replay` as a
  placeholder without a §17 / §15 anchor; Slice 1.4
  ships `forge tail` (live tail of
  `.forge/log/<feature>.jsonl`, the §2.5 polish item
  the roadmap actually names) and drops `Replay`. v1.3
  impact: none (§15 references "replay" only in the
  table; not in the command surface). **Filed in
  `design-rationale.md` (S4-2) at Task 1.4.9 close
  2026-05-29; `ReadOnlyKind` is now
  `Status | Tail | RebuildState`.**
- **S4-3 — Reviewer call cost + observable kill diagnostics
  are out of scope in 1.4a.** Surfaced at plan-review pre-Task 1.4.2.
  `Connector.reviewDesign / reviewPr / refine` return
  `IO[A]` only — no cost projection, no subprocess handle.
  The one-shot reviewer collectors in `forge-agents`
  (`extractStructuredOutput` / `extractAgentMessageText`)
  do not emit `AgentEvent.CostUpdate` either, so reviewer
  spend cannot flow into `Feature.cost` under the current
  boundary. Slice 1.4a's `ReviewerCall` therefore (a) omits
  `maxCostUsd` from `ReviewerLimits`; (b) omits
  `cost: Cost` from `ReviewerOutcome.Settled`; and (c)
  omits `killError: Option[String]` from
  `ReviewerOutcome.Timeout` — the wrapper relies on fiber
  cancellation + connector `Resource` finalizer to clean up
  any leftover subprocess. **Until S4-3 closes, the §12
  budget caps (`maxFeatureCostUsd` / `maxPieceCostUsd` /
  `maxTurnCostUsd`) are driver-session-only invariants for
  v1.** Closure path: widen the connector reviewer methods
  to return `(A, Cost)` (or `ReviewerCall.Settled[A]`) **or**
  plumb `AgentEvent.CostUpdate` through the one-shot
  collectors so the orchestrator's `Feature.cost` projection
  picks them up. v1.3 impact: §7.1 reviewer-method
  signatures or §12 budget-scope wording. Filed at Task 1.4.2
  close in `design-rationale.md`. Stays open as a watch
  item into Slice 1.4b; Task 1.4.17 evaluates whether MVP-run cost
  data warrants closure inside Phase 1 or whether it rolls
  into v1.3.
  **Task 1.4.7 latency observation + resolution (2026-05-28, review
  round 1):** the single live reviewer wiring smoke returned
  `Settled` but reported ~90 min wall-clock despite a 3-min
  per-call `ReviewerLimits` cap. Review round 1 (Finding 1) flagged
  this as a potential cap defect that would make the full batch
  unsafe. **Empirically refuted:** `ReviewerCallSubprocessTimeoutSuite`
  (forge-app) drives `RealReviewerCall` against a **real** fake-CLI
  subprocess — both a silent-hang (`sleep 30`, no output) and a
  chatty-hang (streams output forever) — with a 2-s cap, and both
  return `ReviewerOutcome.Timeout` in ~2 s of real wall-clock (the
  subprocess is killed; not just an `IO.never` under `TestControl`).
  So the cap **does** bound real wall-time; the ~90-min figure is a
  measurement artifact (most likely OS/laptop sleep during the long
  opt-in run — cats-effect's monotonic `IO.sleep` and the subprocess
  both pause on suspend, while munit's wall-clock keeps counting).
  The full 6×20 batch is therefore time-bounded per call (still
  expensive in aggregate, but not unbounded). S4-3's observable-kill
  widening remains a *diagnostics* nice-to-have, not a correctness
  blocker for Task 1.4.7.
- **S4-4 — `RebuildState.run` widened to return
  `RebuildResult(feature, inFlightSessions)` for restart
  recovery.** Surfaced at plan-review pre-Task 1.4.10. The
  Slice 1.2 `RebuildState.run` returns just `Feature`; Slice
  1.4b's orchestrator needs an `inFlightSessions:
  Vector[InFlightSession]` projection (sessions whose
  `<actor>.spawn` / `<actor>.resume` action has no
  subsequent `Settled` / `SettleTimeout` /
  `TurnBudgetBreached` for the same `(phase, piece)` key)
  so it can synthesize `HarnessError` per phase and route
  the FSM to `NeedsHumanIntervention` before any
  source-racing begins on a fresh process start. Without
  this, a Forge process crash mid-session leaves the FSM
  state pointing at e.g. `PieceImplementing(p)` with
  `currentDriverSession = None` on restart — a combination
  the source-selection table treats as transitory
  post-settle synthesis, but with no settle outcome to
  process. v1.3 impact: §11.0 step 4 (cache-vs-log
  verification) gains an in-flight scan step;
  `Feature.foldEvents` either grows a sibling projection
  or `RebuildState.run` adds it post-fold. Filed at Task 1.4.10
  close in `design-rationale.md`; the rebuild signature
  change is contained to `forge-core` and `forge-app` /
  `forge-app/main` (no Slice 1.2 caller change because
  Slice 1.2 had no orchestrator caller). Stays open through
  Slice 1.4b; closure at Task 1.4.10 landing.
- **S4-5 — Production reviewer (model, wall-clock cap,
  timeout-retry) is a `ForgeConfig` decision.** Surfaced by
  Task 1.4.7's live bar measurement. The §16 schema bar is **met**
  (C15 closed) with the v1 config **claude reviewer = `haiku`,
  codex = `gpt-5.3-codex`, 3-min cap**, chosen because haiku is
  fast (~46s/call) and schema-clean (6/6). The open question is
  *quality vs cost vs latency*: `sonnet` (and likely `opus`)
  review better but are **latency-prohibitive** for pr-review on
  large diffs — `sonnet` stalled at 16/20 at both 3-min and 5-min
  caps (p50 114s, p90 218s, ~15% > 300s; smooth heavy tail, not
  rate-limit spikes) and would need a ~7-8 min cap plus a more
  tolerant timeout-retry policy. Task 1.4.9 (`ForgeConfig`) should
  expose reviewer `model` + `wallClockCap` + reviewer
  `timeout-retry count` per §18, and Task 1.4.9/1.4.10 wire the
  orchestrator to construct the reviewer connector with them
  (the `ClaudeConnector.reviewerModel` seam + the
  `FORGE_IT_CLAUDE_MODEL` / `FORGE_IT_REGRESSION_CAP` test knobs
  landed in Task 1.4.7 are the hooks). Also records the residual
  unescaped-`"`-in-summary schema mode (rare, normaliser cannot
  repair, within 1/20 tolerance — possible future hardening).
  Watch item through 1.4b; no v1.2 spec edit beyond §18 reviewer
  keys.

## 5. Cross-references

- v1.2 spec for Slice 1.4 scope: §17 slice 4 (Headless
  feature loop with line-mode REPL), §11 (lifecycle —
  every state Slice 1.4 drives), §15 (command-aware
  preflight table — every command Slice 1.4 ships), §18
  (configuration — `.forge/config.json` the
  orchestrator loads).
- v1.2 spec for reviewer assets: §10.2 (reviewer
  posting), §14.3 (refine verdicts), §7.1 (reviewer
  methods), §7.4 (Native schema), §16 (≥19/20 bar).
- v1.2 spec for `forge-specs` surface: §4 (source of
  truth), §5.3 (`decomposition.md` rendering), §10.1
  (ChangeCollector).
- v1.2 spec for orchestrator wiring: §11.0
  (preconditions — every state-changing command), §12
  (budget enforcement), §13 (locking).
- Decisions backing the Slice 1.4 contracts:
  design-rationale **M1** (manifest is source of
  truth), **M2** (HTML-comment editable regions for
  reconcile), **F11** (`requireSessionId` not
  `.get`), **F7** (ChangeCollector denial path is
  phase-aware), **B1** (canonical action log is
  gitignored).
- Slice 0 wire-shape findings consumed by reviewer
  assets: `slice-0/slice-0-report.md` §2 (native
  schema on both CLIs), §3 (system-prompt-file flag
  on Claude / CodexPrompt prepending on Codex).
- Phase context + seam discipline: `roadmap.md` §2.4
  (this slice), §2.5 (targeted polish — folded into
  Task 1.4.15), §2.6 (seams to leave open — `.forge/state/.lock`
  scope, paths via `ForgePaths`, no global
  singletons), §3 (Phase 2 receives MVP-run friction
  output).
- Predecessors: `design-2.1.md` (Slice 1.1 audit trail,
  closed 2026-05-26), `design-2.2.md` (Slice 1.2 audit
  trail, closed 2026-05-26), `design-2.3.md` (Slice 1.3
  audit trail, closed 2026-05-27).
