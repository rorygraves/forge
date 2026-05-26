# design-2.2 — Slice 2 implementation plan

> **Maps to:** [`roadmap.md`](roadmap.md) §2.2 (Phase 1 / Slice 2 — FSM,
> Feature, ActionLog, StateCache) and
> [`forge-design-1.2.md`](forge-design-1.2.md) §17 slice 2 deliverables.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. The roadmap stays terse — direction and exit criteria — and
> this file owns the per-task breakdown. Items get ticked off here as they
> land; the roadmap section gets ticked off only after a code review on
> the section as a whole.
>
> **Status:** active — opened 2026-05-26 on the close of Slice 1
> (`design-2.1.md`). No code in this slice has landed yet.

## 0. Exit criterion for Slice 2

Roadmap §2.2: `forge-core` ships the FSM as a pure function over
`(Feature, FsmEvent)`, an append-only `ActionLog`, an atomically-written
`StateCache`, a `ForgePaths` helper that owns every `.forge/*` literal,
and the property-test suite covering the §17 slice-2 invariants.
Concretely, this slice is done when:

1. `forge-core` exposes:
   - `FsmState` per v1.2 §6, `FsmEvent` ADT (designed in PR-B), pure
     `Fsm.transition(feature, event): (Feature, Vector[ActionDraft])`,
     `Feature` aggregate with the §6.1 projections,
     `requireSessionId` per §6.2. The transition takes the whole
     `Feature` (not just `FsmState`) because §11 transitions both
     read manifest state (e.g. `nextPending`, baseSha invariants in
     §5.1) and produce manifest mutations (e.g. §11.4 step 1 atomic
     `status="in_progress"` write, §11.5 step 1 atomic
     `status="merged"` write). The transition is pure: it has no
     clock and no log-sequence allocator, so it emits
     `Vector[ActionDraft]` (everything per §19 except `seq` and
     `at`) and `ActionLog.append` stamps them on the way to disk —
     see PR-B B3 and PR-D D1. The orchestrator atomically persists
     the returned `Feature.manifest` before writing the action log
     and state cache.
   - `ActionLog` (NDJSON append-only per §19) with monotonic `seq`.
   - `Feature.foldEvents(initial: Feature, actions: Vector[Action]):
     Either[ReplayError, FoldResult]` replayer that takes the
     initial `Feature` (built from `manifest.json` loaded via
     `ManifestStore` — see PR-E E4 — plus an initial `Drafting`
     `FsmState`) and folds the action log onto it. Returns a
     `FoldResult(feature, observedTransitions, observedPieceMerges)`
     so downstream reconciliation (PR-E E4) has the history it
     needs (see PR-D D4 for the shape). Manifest content is **not** derivable from
     the action log alone — `manifest.json` is the committed source
     of truth (§4) and the log records FSM transitions and
     projections on top of it.
   - `StateCache` (atomic temp-file + `os.move`) with a
     verify-against-log path per §11.0 step 4.
   - `ForgePaths(repoRoot)` owning every `.forge/` location — no
     other call site hardcodes a `.forge/...` literal.
2. The §17 slice-2 property-test list passes. Each invariant has a
   dedicated test (or property check) with a one-line cross-reference
   to the spec section it implements.
3. `forge rebuild-state <feature>` works as a `forge-core` entry point
   `RebuildState.run(featureId, paths, manifestStore, log, cache):
   IO[Either[RebuildError, Feature]]` callable from tests and from
   the Slice-4 CLI. The `manifestStore` dependency is what lets
   replay seed the initial `Feature`; the `Either` channel surfaces
   internally-inconsistent recovery cases (e.g. `manifest.json`
   claims a piece is merged but the log's last fold-state is
   structurally incompatible with the §11.5-step-1 crash window —
   see PR-E E4 reconciliation rule). (The CLI wiring itself is
   Slice 4.)
4. A code review on the section confirms (1) (2) (3) and that the §4
   carry-forward list (including Slice-1 carry-forward **C14**) is
   durably handed off (PR-G G5); the `[~]` bullets in `roadmap.md` §2.2
   flip to `[x]`.

## 1. Sub-PR breakdown

Seven numbered sub-PRs. The dependency graph is strictly linear
(`A → B → C → D → E → F → G`) — see §2 for why `D` and `E` can't be
parallelised.

### 1.1 PR A — Manifest relocation + `ForgePaths` skeleton

`forge-core` needs both `Feature` (which holds a `Manifest`) and
`FsmState.PlanningUpdate(reason, patch: ManifestPatch)`. Today,
`Manifest` / `ManifestPatch` / `Piece` / `PieceStatus` live in
`forge-specs`, which already depends on `forge-core`. The cleanest
resolution — flagged as a real design call here, not a silent move —
is to relocate the manifest data types into `forge-core` and leave
`forge-specs` to own the persistence and rendering wrappers
(`SpecStore`, `DocSync`, `ChangeCollector`) that land in this slice
and in Slice 4. v1.2 §3.2 names "Manifest" inside `forge-specs`; v1.3
needs a spec correction to match. The §4 carry-forward records this.

- [ ] **A1.** Move `Manifest.scala` / `ManifestPatch.scala` /
  `Piece.scala` / `PieceStatus.scala` (and their suites) from
  `modules/forge-specs/src/main/scala/io/forge/specs/` to
  `modules/forge-core/src/main/scala/io/forge/core/manifest/`. Package
  becomes `io.forge.core.manifest`; downstream imports updated.
  Spec: v1.2 §3.2 (deviation tracked in §4 carry-forward S2-1 below).
- [ ] **A2.** File **S2-1** as a `design-rationale.md` entry as part
  of PR-A (not at PR-G close-out): "Manifest data types live in
  `forge-core`, not `forge-specs` (Slice-2 PR-A relocation)". The
  rationale + rejected alternatives below (§4 S2-1) become the body;
  v1.2 §3.2 is the cross-reference. Filing it in PR-A means
  reviewers seeing the move can find the "why" without waiting for
  PR-G. Also re-export the moved types from `io.forge.specs` as
  package aliases **only if** any external Slice-1 caller imports
  them under `io.forge.specs.*` (grep first). If grep is clean, skip
  the aliases and update the imports directly — no compatibility
  shim, per AGENTS.md "Things to not do".
- [ ] **A3.** Add `ForgePaths(repoRoot: os.Path)` in
  `io.forge.core.paths`. Exposed methods (all returning `os.Path`,
  never a raw `String`):
  - `featureSpecDir(FeatureId)` → `.forge/specs/<feature>/`
  - `design(FeatureId)` → `.forge/specs/<feature>/design.md`
  - `manifest(FeatureId)` → `.forge/specs/<feature>/manifest.json`
  - `decomposition(FeatureId)` → `.forge/specs/<feature>/decomposition.md`
  - `pieceSpec(FeatureId, PieceId)`
  - `auditDir(FeatureId)`, `audit(FeatureId, name)`
  - `featureLog(FeatureId)` → `.forge/log/<feature>.jsonl`
  - `stateFile(FeatureId)` → `.forge/state/<feature>.json`
  - `lockFile`, `lockMetadataFile`
  - `pricesUser`, `pricesRepo` (per §18 — `~/.forge/prices.json` /
    `.forge/prices.json`)
  Spec: v1.2 §4 path table + §17 slice 2 paths-helper seam.
- [ ] **A4.** `ForgePathsSuite` — golden-path test that every method
  returns a path strictly under `repoRoot / ".forge"` (or under `~`
  for the `pricesUser` case). Smell-test enforcement: a CI-style
  scalafmt-adjacent script (or a `sbt` task) that fails the build if
  `grep -RE '"\.forge/' modules/` finds matches outside
  `ForgePaths.scala`. The exact mechanism (script vs. sbt task vs. a
  unit test using `os.walk`) is settled inside PR-A; AGENTS.md
  §"Architectural seams to preserve" currently calls it a smell test,
  PR-A graduates it to enforcement.
- [ ] **A5.** Wire `design-2.2.md` into the parent docs:
  - `AGENTS.md` §"Active design-`<section>`.md files" — replace
    *(none currently open)* with the design-2.2.md pointer.
  - `CLAUDE.md` TL;DR "Active implementation plan" — replace
    *(none currently open)* with the design-2.2.md pointer.
  - `roadmap.md` §2.2 — link the active design doc inline (mirroring
    the §2.1 "Detailed history of how it got there" pattern, but
    open-section flavoured).
- [ ] **A6.** PR-A landing checklist:
  - `sbt clean compile` clean under `-Xfatal-warnings`.
  - `sbt test` green across the build (no regression from the
    Slice-1 baseline of 177 unit tests).
  - `sbt scalafmtCheckAll` clean.
  - `sbt "project forge-it" test` clean (no `forge-it` source
    touched here, so this is a smoke check).
  - `grep -RE '"\.forge/' modules/ --exclude-dir=target` returns
    matches only inside `ForgePaths.scala` and any explicit
    test-only fixture files.
  - This file's PR-A header flipped to "✅ landed" and a §3 status-log
    entry added.

### 1.2 PR B — Domain model (FsmState, FsmEvent, Feature, ResumeHint, Action)

PR-B is the *types-only* PR. Pure values + ReadWriter codecs. No
behaviour, no I/O.

- [ ] **B0.** Core-side, provider-neutral types that `FsmEvent` and
  `Feature` will reference. v1.2 §3.2 names "PrSnapshot ADT" inside
  `forge-core`; AGENTS.md currently places it under `forge-git` — the
  spec is right because `forge-git` depends on `forge-core` and the
  FSM has to consume `PrSnapshot` events. AGENTS.md gets corrected by
  PR-G G3 (carry-forward S2-4). Lands in PR-B:
  - `PrSnapshot` per v1.2 §6 (`number`, `state`, `mergedAt`,
    `mergeCommit`, `requiredChecks`, `reviewDecision`,
    `unseenComments`, `mergeable`) plus the `PrState`,
    `CheckRollup`, `ReviewDecision`, `PrComment` supporting
    enums/records.
  - Core-side reviewer-verdict summaries — the FSM consumes the
    *decision*, not the full reviewer payload. `forge-agents` already
    owns the rich `DesignReview` / `PrReview` / `RefineResult`
    shapes; `forge-core` exposes the projection:
    - `DesignReviewVerdict = Approve | RequestChanges(Vector[String])
      | BlockingQuestions(Vector[Question])`
    - `PrReviewVerdict = Approve | RequestChanges(Vector[String])`
    - `RefineVerdict = NoChange | UpdatePlan(ManifestPatch) |
      ReopenDesign(reason)`
    `forge-agents` is responsible for converting its rich types to
    these summaries at the call site (lands in Slice 4 wiring); the
    FSM never sees the raw reviewer JSON. This breaks the
    forge-core → forge-agents dependency that would otherwise be
    needed.
- [ ] **B1.** `FsmState` enum in `io.forge.core.fsm`, exactly per
  v1.2 §6. Includes `Drafting`, `InteractiveSpec`,
  `DesignReviewing(round)`, `DesignNeedsHumanInput(round, questions)`,
  `DesignAwaitingMerge(prNumber)`, `DesignPrFeedback(prNumber, round)`,
  `DesignReady`, `PieceImplementing(p)`, `PieceAwaitingCi(p, pr)`,
  `PieceAwaitingReview(p, pr)`, `PieceCiFailed(p, pr, attempt)`,
  `PieceReviewFailed(p, pr, attempt)`, `PieceFixingUp(p, pr, attempt)`,
  `PieceAwaitingMerge(p, pr)`, `Refining(p, pr, startedAt)`,
  `PlanningUpdate(reason, patch)`, `NeedsHumanIntervention(reason, hint)`,
  `FeatureDone`, `Abandoned(reason)`. Sealed-trait-style ReadWriter
  (uPickle's `derives ReadWriter` on enums tags by case name).
- [ ] **B2.** `ResumeHint` enum per v1.2 §6 — `ResumeAfterHumanPush`,
  `CommitAndPushHumanFix`, `RunAnotherFixup`,
  `ResolveLocalImplementationChanges`, `ReopenDesign`,
  `ApplyPlanningUpdate(patch)`, `AbortOrAbandon`. ReadWriter derives.
- [ ] **B3.** Two case classes, splitting the §6 `Action` shape
  along the pure/effectful seam introduced by C2:
  - `ActionDraft` — `feature: FeatureId, piece: Option[PieceId],
    actor: Option[String], role: Option[String], kind: String,
    payload: ujson.Value`. Emitted by `Fsm.transition` and any
    other pure producer. No `seq`, no `at` — those are allocated
    by `ActionLog.append`.
  - `Action` — `seq: Long, at: Instant` plus every `ActionDraft`
    field. Represents an on-disk log entry as written by
    `ActionLog.append` and read back by `ActionLog.replay`. A
    helper `ActionDraft.stamp(seq, at): Action` (or
    `Action.from(draft, seq, at)`) is the only path from one to
    the other.
  ReadWriter via `Json.given` for both. **Wire-name mismatch:**
  v1.2 §6 case class uses `at: Instant`; v1.2 §19 wire example uses
  `"ts"`. Resolve in PR-B with a custom encoder that renames `at`
  ↔ `ts` on the wire (uPickle `key` annotation or a hand-rolled
  `ReadWriter`), so the in-memory model stays `at` and the on-disk
  NDJSON stays `ts` per §19. Suite asserts the wire string matches
  §19's example. (`ActionDraft` has no on-disk shape — it never
  serialises directly; it always goes through `stamp`.)
- [ ] **B4.** `FsmEvent` ADT. **This is the main design judgement of
  PR-B.** v1.2 §17 says only "FSM as `(FsmState, FsmEvent) =>
  (FsmState, List[ActionLogEntry])`" without spelling the ADT out, so
  PR-B settles it. Sketch (cross-referenced to the §11 lifecycle
  steps that produce each variant; final list is what lands):
  - `SessionSpawned(actor, role, sessionId, piece: Option[PieceId])`
    — §11.1, §11.4, §11.6.
  - `SessionResumed(actor, role, oldSessionId, newSessionId, piece)`
    — §11.2 step 12, §11.3 step 2.
  - `Settled(phase, outcome)` — §11.1 step 6, etc.
  - `SettleTimeout(phase, reason)` and `TurnBudgetBreached(phase)` —
    §11 settle bounds + §12.
  - `DesignReviewVerdict(round, verdict, blockers)` — §11.2 step 11,
    12, 13.
  - `DesignReviewClarified(round)` — §11.2 step 11.
  - `DesignPrSnapshotUpdated(snapshot)` — §11.3.
  - `BranchCreated(p, branchName, baseSha)` — §11.4 step 1.
  - `PrOpened(p, prNumber)` — §11.4 step 6.
  - `PrSnapshotUpdated(p, snapshot)` — §11.5.
  - `CodeReviewVerdict(p, verdict, blockers)` — §11.5
    `PieceAwaitingReview`.
  - `Merged(p, prNumber, mergeCommit, mergedAt, observedAt)` —
    §11.5 step 1 **input** event. Two timestamps because they mean
    different things: `mergedAt` is the historical fact from
    GitHub (the moment the PR went to MERGED state — stored
    durably in the manifest's piece record for audit); `observedAt`
    is when the orchestrator processed the merge (used by the
    FSM to set `Refining.startedAt`, which §11.7 / §14.1 surface
    as "Refining piece <p>... (<elapsed>s)" — that elapsed clock
    starts when Forge enters refining, not when the PR merged
    upstream). The FSM is pure, so the orchestrator stamps
    `observedAt = Clock.now` at event construction; the synthetic
    recovery branch in `RebuildState.reconcile` (E4) sets
    `observedAt = mergedAt` because that's the closest historical
    fact available and the alternative (use rebuild-time `now`)
    would lie about elapsed refining time even more aggressively.
    Consistent with C2's pure-transition model: the FSM consumes
    `Merged` and *returns* a `Feature` whose manifest reflects
    `pieces[i].status = "merged"`, `prNumber`, `mergeCommit`,
    `mergedAt` plus `state = Refining(p, prNumber, observedAt)`.
    The orchestrator (Slice 4) atomically persists the returned
    `Feature.manifest` to `manifest.json` (temp + `os.move`) —
    that's the §11.5 step 1 atomic write — then appends the
    `fsm.transition: PieceAwaitingMerge → Refining` and
    `audit.piece_merged` drafts to the log, then writes the state
    cache. Crash between the manifest write and the log/cache
    writes is the F13 / `RebuildState.reconcile` case. `Merged` is
    therefore an input from the PR-watcher side, not an output
    from a persistence layer.

    **Idempotency rule.** The `Merged` handler in `Fsm.transition`
    is idempotent with respect to the manifest mutation: if the
    target piece in `feature.manifest` already has `status =
    Merged` *and* matching `prNumber` / `mergeCommit` /
    `mergedAt`, the manifest fields are left untouched (the
    mutation is a no-op) while the FSM still produces
    `state = Refining(p, prNumber, observedAt)` and emits the
    `fsm.transition` + `audit.piece_merged` drafts. If the
    manifest's existing merged record disagrees on any of those
    fields (`prNumber`, `mergeCommit`, `mergedAt`), the handler
    transitions to `NeedsHumanIntervention("manifest merged
    record disagrees with Merged event for piece <p>",
    AbortOrAbandon)` and emits a `harness.error { kind:
    "merged_field_mismatch", piece, expected, observed }` draft
    — following the C1 pattern of encoding structural failures
    as `NeedsHumanIntervention` transitions rather than as a
    separate error channel on `Fsm.transition`. This rule is
    what makes `RebuildState.reconcile`'s case (c) safe:
    reconcile seeds the initial `Feature` from `manifest.json`
    (where `p` is already `status = Merged`), then applies the
    synthetic `Merged` event; without idempotency the handler
    would either reject the input or double-mutate. C3 includes
    a dedicated suite case (`Fsm_11_5_MergedIdempotencySuite`)
    covering the no-op-mutation path and the
    `merged_field_mismatch` `NeedsHumanIntervention` path.
  - `RefineOutcome(verdict)` — §11.7.
  - `PlanningDecision(reason, patch, choice)` — §14.3.
  - `BudgetBreached(scope, message)` — §12.
  - `RequiredSessionIdMissing(reason, hint)` — produced by
    `requireSessionId` callers; lives as an event so the transition
    stays pure.
  - `UserCommand(cmd)` — `forge new`, `forge resume --<mode>`,
    `forge abandon`, `/done` in the spec REPL. Each variant maps to
    the §15 command table.
  - `HarnessError(reason)`.
  ReadWriter derives for all variants.
- [ ] **B5.** `Feature` case class in `io.forge.core.fsm` per v1.2 §6:
  `id, manifest, state, cost, designSessionId,
  currentPieceSessionId, branchProtectionCacheEpoch`. ReadWriter
  derives via `Json.given` + the moved `Manifest` ReadWriter.
- [ ] **B6.** `Cost` / `CostTotals` lift the existing
  `io.forge.agents.Cost` into the domain layer **iff** the type is
  trivially-movable (no cats-effect / fs2 deps). Otherwise leave the
  agent-side type alone and define a `CostTotals` in `forge-core`
  that the orchestrator projects into. Decided in PR-B from the
  actual `Cost.scala` shape; expected to be a simple move.
- [ ] **B7.** Codec round-trip suites for every new type — encode →
  decode → `assertEquals(original, roundtripped)`. One suite per
  type family (`FsmStateSuite`, `FsmEventSuite`, `FeatureSuite`,
  `ActionSuite`). No transition tests yet (those are PR-C).

### 1.3 PR C — FSM transition function + `requireSessionId`

The pure heart of Slice 2. No I/O. Big test surface.

- [ ] **C1.** `requireSessionId[A](sessionId, reason, hint):
  Either[FsmTransition, String]` per v1.2 §6.2, in
  `io.forge.core.fsm`. Plus `FsmTransition` wrapper or direct
  `Either[NeedsHumanIntervention, String]` — settled inside PR-C
  from the call-site shape needed by the transition function.
- [ ] **C2.** `Fsm.transition: (Feature, FsmEvent) =>
  (Feature, Vector[ActionDraft])` — pure. Operates on the whole
  `Feature` (not just `FsmState`) because §11 transitions both read
  manifest state (next-pending selection in §11.7, baseSha
  invariants in §5.1, attempts counters in §11.5) and produce
  manifest mutations (§11.4 step 1 atomic
  `status="in_progress"` write, §11.5 step 1 atomic
  `status="merged"` write). The transition emits `ActionDraft`
  (PR-B B3) — no `seq`, no `at` — so it can stay clock-free and
  log-free; `ActionLog.append` (PR-D D1) stamps them on the way to
  disk. The returned `Feature` is the new domain state; the
  orchestrator is responsible for atomically persisting
  `Feature.manifest` (temp + `os.move`) before the state-cache
  write, matching §11.5 step 1 wording. Each case mirrors a §11
  lifecycle rule with a one-line spec cross-reference.
- [ ] **C3.** Per-state unit suites, one per §11.x section:
  - `Fsm_11_1_SpecPhaseSuite` — `Drafting → InteractiveSpec`,
    settle → `DesignReviewing(1)`, settle-timeout/turn-budget →
    `NeedsHumanIntervention`.
  - `Fsm_11_2_DesignReviewSuite` — verdict approve / request_changes
    / blocking questions, max-round exhaustion.
  - `Fsm_11_3_DesignPrGateSuite` — merged / new-comment /
    closed-without-merge / force-push rejected.
  - `Fsm_11_4_ImplementationPhaseSuite` — branch creation / ChangeCollector
    Deny pre-PR / commit-push-PR-open.
  - `Fsm_11_5_CiReviewPollingSuite` — CI ready / CI failed +
    attempts gate / reviewer verdicts / human comment override / merge.
  - `Fsm_11_5_MergedIdempotencySuite` — exercises the B4 `Merged`
    idempotency rule: (a) applying `Merged` to a feature whose
    manifest has `p` as `InProgress` produces the normal mutation
    + `Refining` transition + drafts; (b) applying `Merged` again
    (or with the manifest already showing `p` merged with
    matching fields) leaves the manifest untouched but still
    produces `Refining` + drafts; (c) applying `Merged` with a
    mismatched `prNumber` / `mergeCommit` / `mergedAt`
    transitions to `NeedsHumanIntervention(..., AbortOrAbandon)`
    and emits a `harness.error merged_field_mismatch` draft.
    Case (b) is exactly the state `RebuildState.reconcile`'s
    case (c) hands to the transition.
  - `Fsm_11_6_FixupSuite` — `runFixup` spawn → settle → back to CI.
  - `Fsm_11_7_RefineAdvanceSuite` — `no_change` / `update_plan` /
    `reopen_design` / refinery failure.
- [ ] **C4.** Cross-cutting suites:
  - `RequireSessionIdSuite` — `None` always returns
    `Left(NeedsHumanIntervention(...))`, never throws; matches
    invariant 8 of §17 slice 2.
  - `ResumeHintCoverageSuite` — every `NeedsHumanIntervention` case
    constructible from the FSM carries the spec-mandated
    `ResumeHint`. Table-driven from §11.0 step 5 + §11.x.
- [ ] **C5.** **C14 awareness comment.** Where the FSM produces a
  `SessionResumed` precursor for the design-revision path (§11.2
  step 12) and the design-PR-feedback path (§11.3 step 2), a
  source-level comment cross-references design-rationale **C14**:
  for Codex the `message` parameter must re-issue role framing the
  orchestrator-side because `CodexConnector.resumeStreamingSpec`
  drops `systemPromptPath`. The FSM **does not** branch on
  `Mode` — that's the orchestrator's job. The comment exists so the
  Slice-4 orchestrator wiring can't quietly skip the re-framing.

### 1.4 PR D — ActionLog (file I/O)

- [ ] **D0.** Add `cats-effect` to `forge-core`'s `libraryDependencies`
  in `build.sbt`. Current `forge-core` declares only `upickle` and
  `os-lib`; PR-D/E introduce `IO[_]`-returning trait methods for
  `ActionLog` / `StateCache` (matching the `forge-agents` style), so
  the dep has to land here. The alternative — keep the algebras pure
  (`Either[Error, A]`) and put file impls in `forge-agents` /
  `forge-app` — fragments the §3.2 module ownership (FSM and its
  persistence belong together) and is rejected. PR-D's checklist also
  bumps `fs2-core` if any trait method ends up returning a `Stream`;
  expectation today is no.
- [ ] **D1.** `ActionLog` trait in `io.forge.core.log`:
  - `append(draft: ActionDraft): IO[Action]` — stamps `seq`
    (allocated under the in-process `nextSeq` counter) and `at`
    (`IO.realTime` → `Instant`), writes the NDJSON line
    (file-locked, fsync-on-write), and returns the stamped
    `Action` so the caller can correlate. The single-writer
    invariant is the orchestrator's process lock (§13); the
    `nextSeq` counter is therefore in-process-monotonic without
    needing a cross-process strategy.
  - `appendAll(drafts: Vector[ActionDraft]): IO[Vector[Action]]` —
    the FSM-driven batch from `Fsm.transition`'s return value.
    **Single rendered-batch write**: allocate the next N `seq`
    values, capture `at` once for the whole batch (matches the
    "conceptually atomic transition" semantics), render every
    draft to its NDJSON line, concatenate into one byte buffer,
    then issue a **single `Files.write(... APPEND | SYNC)` call**.
    The single-write strategy is *not* a crash-atomicity guarantee
    — POSIX makes no such guarantee for regular files at any size
    (PIPE_BUF applies only to pipes/FIFOs, and ext4 / APFS / NTFS
    behaviour varies). What single-write batching buys is a
    smaller recoverable crash window: one OS-level write call
    means fewer points at which a partial flush can interleave
    with a crash than would N sequential `append` calls. The
    durable correctness guarantee comes from two sources working
    together: (1) `FileActionLog.replay` physically truncates a
    partially-flushed trailing line at the last `\n` on disk
    (PR-D D5), so subsequent appends extend a valid NDJSON file
    and never write past surviving garbage, and (2)
    `RebuildState.reconcile` (E4) repairs any missing companion
    entries — specifically case (b),
    "fsm.transition present, audit.piece_merged missing", is
    exactly the partial-batch crash window this approach leaves
    behind. The reconcile fallback is what makes the design
    correct on any POSIX filesystem; the single-write strategy
    just reduces how often that fallback fires.
  - `replay(featureId): IO[Vector[Action]]` — reads the entire
    file and returns the durable post-repair view (PR-D D5
    spells out the truncate-then-append-recovery contract).
    Note: `replay` may have a one-shot write side-effect on
    first encountering a partial trailing line; the contract
    surfaces this through D5 rather than splitting the API.
  - `nextSeq(featureId): IO[Long]` (monotonic; warm-up reads
    existing log to find max seq, then in-memory increments).
    Warm-up calls `replay`; `replay` therefore cannot call
    `nextSeq` or public `append` (recursion). The file impl
    has a `private[log] unsafeAppendStamped(action: Action):
    IO[Unit]` helper that takes a fully-stamped `Action` and
    writes one NDJSON line via `Files.write(... APPEND | SYNC)`
    — `replay` uses this helper for its recovery entry, and the
    `append` / `appendAll` paths delegate to it after `seq`/`at`
    stamping. The helper is not part of the public `ActionLog`
    trait.
- [ ] **D2.** File impl `FileActionLog(paths: ForgePaths)`. Writes
  NDJSON exactly per v1.2 §19 wire shape: `seq`, `ts`, `feature`,
  `piece`, `actor`, `role`, `kind`, `payload`. One line per action.
  Append-only — no rewrites, no rotation (v1 §19 explicitly).
  Writes via `Files.write(... APPEND | SYNC)`; crash recovery is
  handled by D5 (`replay`'s truncate-and-recover) and E4
  (`RebuildState.reconcile`'s missing-companion repair), not by
  any per-line atomicity claim at this layer. If `SYNC` proves to
  be a perf cliff during Slice 4 testing, lower to a per-batch
  `force()` and flag in §4 carry-forward.
- [ ] **D3.** `Action.payload` codec — `ujson.Value` round-trips
  through uPickle natively; the suite asserts that every `kind`
  from §19 (`fsm.transition`, `<actor>.spawn` / `.resume` /
  `.user_message` / `.assistant_text` / `.tool_use` /
  `.ask_user_question` / `.halt_respawn` / `.schema_invalid` /
  `.process_retry`, `audit.piece_merged`, `gh.poll` / `.action`,
  `review.*`, `user.command`, `harness.*`, `cost.update`)
  round-trips. The `kind` strings are a closed enum but stored as
  raw `String` — easier evolution than a sealed enum.
- [ ] **D4.** `Feature.foldEvents(initial: Feature, actions:
  Vector[Action]): Either[ReplayError, FoldResult]` where:
  ```
  final case class FoldResult(
    feature: Feature,
    observedTransitions: Vector[ObservedTransition],
    observedPieceMerges: Set[PieceId]
  )
  final case class ObservedTransition(from: FsmState, to: FsmState,
                                       piece: Option[PieceId], at: Instant)
  ```
  `FoldResult` exists because `Feature` is the projected state and
  doesn't carry history; the post-pass in `RebuildState.reconcile`
  (E4) needs to know *which* transitions and audit entries the log
  actually contained, not just where they landed. `feature` is the
  current state; `observedTransitions` is the in-order list pulled
  from `fsm.transition` payloads; `observedPieceMerges` is the set
  of `PieceId`s that had an `audit.piece_merged` entry in the log.
  Manifest content is **not** derivable from the log alone (v1.2
  §4: `manifest.json` is the committed source of truth); the
  caller — `RebuildState.run` in PR-E E4, or a property test —
  builds the initial `Feature` from a manifest loaded via
  `ManifestStore` (PR-E E4), plus the implicit starting state
  (`FsmState.Drafting`, empty cost totals, no session ids). The
  replayer walks `fsm.transition` payloads (`from`, `to`) to
  recover state and append to `observedTransitions`; walks
  `<actor>.spawn` / `.resume` to project `designSessionId` and
  `currentPieceSessionId` per §6.1 lifecycle rules; walks
  `cost.update` to project `CostTotals`; collects
  `audit.piece_merged` entries into `observedPieceMerges`.
  `harness.error { kind: "log_truncated" }` entries (written by
  `FileActionLog.replay`'s repair path — PR-D D5) are a no-op
  projection: they don't affect `FsmState`, the session-id
  projections, `CostTotals`, `observedTransitions`, or
  `observedPieceMerges`. They exist as forensic markers for the
  human reading the log, not as domain events. Returns
  `Left(ReplayError)` if the log is internally inconsistent —
  example cases:
  - `NonMonotonicSeq(at)` — `seq` not strictly increasing.
  - `ResumeWithoutSpawn(actor, sessionId)` — `<actor>.resume`
    references a session id that no prior `<actor>.spawn` event
    introduced.
  - `TransitionFromMismatch(from, observedState)` —
    `fsm.transition` whose `from` doesn't equal the current
    state at that point in the fold.
  - `AuditPrNumberMismatch(piece, logPrNumber, manifestPrNumber)`
    — `audit.piece_merged` payload's `prNumber` doesn't match
    the manifest's record for the same piece. The reconcile rule
    (E4) treats `observedPieceMerges` as authoritative for
    "audit was logged"; `foldEvents` is the layer that
    cross-checks `prNumber` so reconcile doesn't have to. **`ReplayError` is a sealed trait local to the
  replay layer** (`io.forge.core.log`) — it does *not* extend
  `RebuildError`. The rebuild layer (PR-E E4) lifts it explicitly
  via `RebuildError.ReplayInconsistent(cause: ReplayError)` so
  each layer keeps its own error vocabulary; the replay layer
  doesn't depend on `forge-core.state` types and the rebuild
  layer's error channel is a tagged superset rather than a leaky
  one. F1 exercises this end-to-end. `foldEvents` does **not**
  synthesize recovery transitions or repair missing companion
  entries — that's `RebuildState.reconcile`'s job in PR-E E4 and
  is the only place in Slice 2 that writes recovery actions back
  to the log.
- [ ] **D5.** `FileActionLogSuite` — append+replay round-trip,
  monotonic seq across appends, replay tolerant to a
  partially-flushed last line.

  **Replay's repair contract.** `FileActionLog.replay`:
  1. Parses the on-disk file line-by-line until either EOF or a
     non-newline-terminated trailing fragment.
  2. If a trailing fragment is present, **physically truncates
     the file at the last `\n` boundary on disk** (not just in
     memory). The truncation runs before the recovery entry is
     written, so the file is a valid NDJSON prefix before the
     append happens.
  3. Computes `nextSeq = survivors.lastOption.map(_.seq + 1)
     .getOrElse(0)` directly from the survivors it just parsed
     — *not* by calling `ActionLog.nextSeq`, which warms by
     calling `replay` and would recurse.
  4. Renders a `harness.error { kind: "log_truncated",
     droppedBytes }` action with `seq = nextSeq` and `at =
     IO.realTime` and writes it via the **lower-level no-replay
     append path** (the private file-impl helper that
     `appendAll` ultimately calls; this helper takes
     pre-stamped `Action`s rather than `ActionDraft`s and does
     not consult `nextSeq`). Public `append` is off-limits here
     for the same recursion reason.
  5. Returns `Vector[Action]` containing **the survivors + the
     recovery entry**, i.e. the durable post-repair log view.
     Subsequent consumers (`foldEvents`, the warm-up that seeds
     `nextSeq` on first public `append`) see exactly what's
     on disk.

  D4's `foldEvents` treats `harness.error { kind: "log_truncated"
  }` as a no-op projection (does not affect `FsmState`, the
  session-id projections, `CostTotals`, or
  `observedTransitions` / `observedPieceMerges`) — it's a
  recovery marker, not a domain event.

  This is load-bearing: if replay only returned the truncated
  view in memory while leaving the trailing garbage in place,
  the next `append` would write a new line starting *after* the
  garbage — leaving an invalid NDJSON fragment in the middle of
  the file that no future replay can recover from cleanly.

  D5 includes a fixture test that injects a partial line, calls
  `replay`, asserts (a) the returned `Vector[Action]` excludes
  the garbage *and includes* the synthesized `harness.error
  log_truncated` entry, (b) `os.read(paths.featureLog(...))`
  shows the file has been truncated to a clean NDJSON prefix
  with the recovery entry appended, (c) a subsequent
  `appendAll` of new drafts produces a valid file from
  beginning to end with seq monotonically continuing past the
  recovery entry's seq.
- [ ] **D6.** Cross-reference docstring on `ActionLog` linking the
  trait to v1.2 §4 invariant ("local runtime log is canonical") and
  §19 (event shapes).

### 1.5 PR E — StateCache (file I/O)

- [ ] **E1.** `StateCache` trait in `io.forge.core.state`:
  - `load(featureId): IO[Option[Feature]]`
  - `save(featureId, feature): IO[Unit]` (atomic: temp file →
    fsync → `os.move` over the target; per v1.2 §4 and §11.5
    "Write via temp file + os.move").
- [ ] **E2.** File impl `FileStateCache(paths: ForgePaths)`. Reads
  `.forge/state/<feature>.json`; writes via `paths.stateFile(...)`.
  ReadWriter via `Json.given`.
- [ ] **E3.** `StateCache.verifyAgainstLog(featureId,
  manifestStore: ManifestStore, log: ActionLog):
  IO[Either[RebuildError, VerifyResult]]` where `VerifyResult` is
  `Consistent(Feature) | Rewritten(Feature)`. Per v1.2 §11.0 step
  4: state cache verified against log replay; rewritten if
  divergent. The rewrite path runs the same `RebuildState.run`
  pipeline (manifest seed → `foldEvents` → `reconcile` → `save`),
  because the initial-Feature build needs the manifest store —
  `foldEvents` cannot construct a `Feature` on its own (D4) and
  the reconcile post-pass needs the `FoldResult` history (D4). Any
  `RebuildError` raised by the rewrite path propagates through
  this method's `Either` channel rather than being swallowed; the
  §11.0 step-4 caller in Slice 4 will surface it as a
  `NeedsHumanIntervention` ("state cache unrecoverable"). Logs a
  `harness.cache_invalidated` action on rewrite. `verifyAgainstLog`
  therefore takes `manifestStore` as a parameter (mirroring the
  `RebuildState.run` signature in E4).
- [ ] **E4.** `RebuildState.run(featureId, paths, manifestStore,
  log, cache): IO[Either[RebuildError, Feature]]` — the
  `forge-core` entry point that the Slice-4 `forge rebuild-state`
  CLI delegates to.

  **Error model.** `RebuildError` is a sealed trait in
  `io.forge.core.state` with cases:
  - `ManifestLoadFailed(featureId, cause: Throwable)` — manifest
    file unreadable or fails `Manifest.validate`.
  - `ReplayInconsistent(cause: ReplayError)` — wraps a
    `ReplayError` returned by `Feature.foldEvents` (D4). The
    wrap is explicit so the rebuild layer owns its own error
    vocabulary; `ReplayError` itself is local to
    `io.forge.core.log` and doesn't extend `RebuildError`.
    Callers pattern-match on `ReplayInconsistent(cause)` and
    descend into `cause` if they want the underlying detail.
  - `InconsistentRecovery(reason)` — manifest claims a piece
    merged but the log's fold-state is structurally incompatible
    with the §11.5 crash window; operator intervention required.
  Every step that can fail returns `Either[RebuildError, …]`; the
  `IO` only carries genuine I/O exceptions.

  **Pipeline:**
  1. **Load manifest.** `manifestStore.load(featureId)`. On any
     I/O or validation failure: `Left(ManifestLoadFailed)`.
  2. **Seed initial Feature.** `initial = Feature(id = featureId,
     manifest = manifest, state = FsmState.Drafting, cost =
     CostTotals.zero, designSessionId = None,
     currentPieceSessionId = None, branchProtectionCacheEpoch =
     0)`. (Manifest is the committed source of truth per §4; the
     action log records FSM transitions over it, not the manifest
     itself.)
  3. **Fold the log.** `foldResult =
     Feature.foldEvents(initial, log.replay(featureId))` — pure
     log-fold; returns `FoldResult` (feature + observed
     transitions + observed piece-merges). On
     `Left(replayError)` (a `ReplayError`), lift to
     `Left(RebuildError.ReplayInconsistent(replayError))` before
     returning.
  4. **Reconcile with manifest.** `RebuildState.reconcile(
     foldResult, manifest): Either[RebuildError, ReconcileResult]`
     where `ReconcileResult(feature, draftsToAppend:
     Vector[ActionDraft])` — **pure**. Computes the recovered
     `Feature` and the repair drafts needed to make the on-disk
     log self-consistent. The log dependency lives in `run`, not
     `reconcile`, so the rule itself stays a pure function over
     `(FoldResult, Manifest)`.
  5. **Append repair drafts.** If
     `result.draftsToAppend.nonEmpty`, `log.appendAll(
     result.draftsToAppend)`. (Empty in the no-repair-needed
     case (a), so the log is untouched on a clean rebuild.)
  6. **Write cache.** `cache.save(featureId, result.feature)`.
     Returns `Right(result.feature)`.

  **Reconciliation rule** (explicit design for F13's reader-side
  behaviour; the rule takes `foldResult: FoldResult` and
  `manifest: Manifest`, so `observedTransitions` and
  `observedPieceMerges` are available without re-scanning the
  log):

  Per piece `p` in `manifest.pieces.filter(_.status ==
  PieceStatus.Merged)` (in manifest order), classify the on-disk
  state:

  - `transitionLogged(p) =
    foldResult.observedTransitions.exists(t => t.piece ==
    Some(p.id) && t.from == PieceAwaitingMerge(p.id,
    p.prNumber.get) && t.to.isInstanceOf[Refining] && t.to.p ==
    p.id && t.to.prNumber == p.prNumber.get)` — anchored on
    **both** the piece id *and* `p.prNumber` (the manifest's
    recorded PR number for the merged piece). Anchoring only on
    piece id would accept a hand-edited or corrupted log that
    transitioned the same `PieceId` through a different PR,
    silently treating an unrelated merge event as the recovery
    target. `p.prNumber` is guaranteed non-None for merged
    pieces by `Manifest.validate` (§5.1, B1), so the `.get` is
    safe at this call site.
  - `auditLogged(p) = foldResult.observedPieceMerges.contains(p.id)`
    — piece-id-only is sufficient here because the
    `audit.piece_merged` payload itself carries `prNumber`,
    `mergeCommit`, `mergedAt`, and D4's `foldEvents` rejects an
    `audit.piece_merged` whose `prNumber` doesn't match the
    manifest as a `ReplayError.AuditPrNumberMismatch` (added to
    D4's `ReplayError` cases when this rule lands).

  Four sub-cases per piece, but only three are reachable under
  §11.5's write order (manifest → fsm.transition →
  audit.piece_merged → state cache):

  The rule accumulates `(feature, draftsToAppend)` across pieces.
  `feature` starts as `foldResult.feature` and is updated only by
  case (c). `draftsToAppend` starts empty.

  - **(a) `transitionLogged && auditLogged`** — fully recovered;
    no contribution.
  - **(b) `transitionLogged && !auditLogged`** — partial-batch
    crash between appending the `fsm.transition` line and the
    `audit.piece_merged` line for `p` (see D1's framing — the
    rendered-batch write reduces but doesn't eliminate this
    window). The `fsm.transition` for `p` is already on disk, so
    the FSM transition already occurred during the fold — *no
    synthetic FSM event is needed*. The fold-state at the time
    of repair is whatever later transitions landed at, which may
    be `Refining(p, _, _)` or any forward state (e.g.
    `PieceImplementing(next)` if the log captured `p`'s
    `Refining → PieceImplementing` advance but not `p`'s
    `audit.piece_merged`). The repair just synthesizes the
    missing audit entry sourced from `p`'s manifest fields
    (`p.prNumber.get`, `p.mergeCommit.get`, `p.mergedAt.get` —
    safe because `Manifest.validate` (§5.1, B1) guarantees these
    are non-None for any piece with `status == Merged`, which is
    the filter at the top of the rule) and a paired
    `harness.crash_recovered` draft naming the repair. Both
    drafts append to `draftsToAppend`; `feature` is unchanged.
  - **(c) `!transitionLogged && !auditLogged`** — the §11.5
    crash window proper (the F13a case). (Same `Manifest.validate`
    non-None guarantee as in case (b) for `p.prNumber`,
    `p.baseSha`, `p.mergeCommit`, `p.mergedAt`.) Verify
    `feature.state == PieceAwaitingMerge(p.id, p.prNumber.get)`
    — anything else means the log's fold-state is structurally
    incompatible (e.g. log says we're still in
    `PieceAwaitingReview`); return
    `Left(InconsistentRecovery(...))` (F13c). Synthesize
    `FsmEvent.Merged(p.id, p.prNumber.get, p.mergeCommit.get,
    p.mergedAt.get, observedAt = p.mergedAt.get)` —
    `observedAt = mergedAt` per the B4 note (closest historical
    fact available; using rebuild-time `now` would distort
    refining elapsed time further). Apply
    `Fsm.transition(feature, syntheticMerged)` → `(feature',
    drafts)`. The B4 `Merged`-handler idempotency rule is what
    makes this safe: `feature.manifest`'s piece `p` is already
    `status = Merged` with matching `prNumber` / `mergeCommit` /
    `mergedAt` (the manifest is the seed for `feature`), so the
    manifest mutation is a no-op while the state transition and
    drafts still fire. `drafts` contains the `fsm.transition:
    PieceAwaitingMerge → Refining` and `audit.piece_merged`
    entries; the rule sets `feature = feature'` and appends
    `drafts ++ Vector(harnessCrashRecoveredDraft)` to
    `draftsToAppend`.
  - **(d) `!transitionLogged && auditLogged`** — structurally
    impossible (audit comes after transition in the batch); if
    encountered, the log was hand-edited or corrupted. Return
    `Left(InconsistentRecovery(...))`.

  **Multi-piece divergence.** If two or more pieces fall into
  case (c), return `Left(InconsistentRecovery("multi-piece
  partial merge — operator intervention required"))`. A second
  piece can only have entered `PieceAwaitingMerge` after its
  predecessor's `Refining → PieceImplementing` transition was
  logged, which presupposes the predecessor's `fsm.transition`
  for `Refining` was logged; so genuine multi-piece (c) is
  unreachable under §11.5 ordering and we refuse to invent
  transitions for it. Cases (a) and (b) compose freely across
  pieces — case (b)'s repair is a per-piece audit-entry
  synthesis that touches neither the FSM state nor any other
  piece's status, so any number of case-(b) pieces is fine.

  This rule keeps `foldEvents` pure (no clock, no synthesis) and
  isolates all recovery synthesis to `RebuildState.reconcile`,
  which is the only function in Slice 2 that authors recovery
  drafts. `reconcile` itself does **not** touch the log — the
  `Either[RebuildError, ReconcileResult]` shape means the log
  write is `run`'s responsibility (pipeline step 5), so reconcile
  can be tested by handing it fixture `FoldResult` + `Manifest`
  values and asserting on the returned drafts without any I/O.

  - `ManifestStore` is the small trait `forge-core` defines for
    "load `manifest.json` for a feature id" — concretely
    `trait ManifestStore { def load(id: FeatureId):
    IO[Either[ManifestLoadFailed, Manifest]] }` in
    `io.forge.core.manifest`. Default file impl
    (`FileManifestStore(paths)`) reads `paths.manifest(id)` via
    `os.read` + `Manifest.fromJson` + `Manifest.validate`. Lives
    in `forge-core` because PR-A relocated the `Manifest` data
    type there; the I/O wrapper has no `forge-specs` dependency.
    `forge-specs`'s richer `SpecStore` (writes, DocSync, audit
    rendering) lands in Slice 4 and *uses* `ManifestStore` under
    the hood.
- [ ] **E5.** `FileStateCacheSuite` — write/read round-trip, atomic
  write (verify the temp file doesn't exist after `save`),
  divergence detection (rewrite a `Feature` with stale state cache
  and confirm `verifyAgainstLog` returns `Rewritten`).

### 1.6 PR F — Property tests (§17 slice-2 invariant list)

Property tests use `munit-scalacheck` (add `org.scalameta::munit-scalacheck`
to `forge-core/test`). One suite per invariant; each suite begins with
a one-line cross-reference to the v1.2 § that motivates it.

- [ ] **F0.** Add `munit-scalacheck` dependency in `build.sbt`,
  shared `commonSettings` test scope. Pin to a version compatible
  with munit 1.0.4. Add a tiny generator library
  (`forge-core/test/.../gen/`) for `FeatureId`, `PieceId`, `Manifest`,
  `Feature`, `FsmState`, `FsmEvent`. The generators bias toward
  legal manifests (merged-prefix invariant respected); illegal
  inputs are tested separately in named negative-path suites.
- [ ] **F1. Log → state replay round-trip.** For an arbitrary legal
  sequence of `FsmEvent`s applied via `Fsm.transition`, the
  resulting `Vector[Action]` replayed via `Feature.foldEvents`
  reproduces the final `Feature`, including the §6.1 session-id
  projections. Maps to §17 slice 2 invariant 1.
- [ ] **F2. `NeedsHumanIntervention` legality.** For every reachable
  `NeedsHumanIntervention` produced by `Fsm.transition`, the
  carried `ResumeHint` is one of the six legal §6 variants AND a
  follow-up `UserCommand(resume(hint))` event produces a legal
  next state (not `NeedsHumanIntervention` again with the same
  reason). Maps to invariants 2 + 3.
- [ ] **F3. Design-before-implement.** No path from `Drafting`
  reaches any `PieceImplementing(_)` state without first passing
  `DesignReady`. Maps to invariant 4.
- [ ] **F4. Merged piece never re-selected.** After any sequence
  ending in `manifest.pieces[i].status = "merged"`, the next
  `RefineOutcome(NoChange)` event causes the FSM to pick a piece
  whose id is **not** `i`. Maps to invariant 5.
- [ ] **F5. `currentPieceSessionId` lifecycle.** Populated exactly
  at `PieceImplementing` / `PieceFixingUp` spawn; retained through
  `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceCiFailed`,
  `PieceReviewFailed`, `PieceFixingUp`, `PieceAwaitingMerge`,
  `Refining`; cleared at advance. Maps to invariant 6.
- [ ] **F6. `designSessionId` lifecycle.** Populated at first
  `InteractiveSpec` spawn / design-revision resume; retained
  through every design-phase state; cleared on entering
  `DesignReady`. `<actor>.resume` updates with `newSessionId ==
  oldSessionId` are idempotent. Maps to invariant 7.
- [ ] **F7. `requireSessionId` returns `Left`.** Never throws on
  `None`; the returned `NeedsHumanIntervention` carries
  `ReopenDesign(currentDesignPr)` for design-phase missing-id and
  no other reason in v1 (per §11.0 step 5 "no other resume calls in
  v1; this rule covers all current cases"). Maps to invariant 8.
- [ ] **F8. Human feedback returns to revision/fixup.** From any
  pre-merge state, an event signalling human `CHANGES_REQUESTED`
  / new human comment yields a transition to `DesignPrFeedback`
  (design phase) or `PieceFixingUp` (implementation phase), never
  to a forward state. Maps to invariant 9.
- [ ] **F9. CI-readiness ordering.** `PieceAwaitingCi → PieceAwaitingReview`
  cannot fire before a `CheckDiscoveryComplete` event (or
  equivalent — the precise event name lands in PR-B B4) under
  `CiPolicy.BranchProtectionThenObserved`. Under `CiPolicy.None`,
  the transition is permitted immediately. Maps to invariant 10.
- [ ] **F10. Already-merged piece IDs immutable.**
  `ManifestPatch.RemovePiece(mergedId)` returns
  `Left(op[i] RemovePiece: cannot remove merged piece ...)`. Already
  partly covered in `ManifestPatchSuite`; F10 lifts the existing
  unit case to a property by generating arbitrary patches over a
  manifest with a non-empty merged prefix. Maps to invariant 11.
- [ ] **F11. Reorder invariant under arbitrary patches.** §5.5
  merged-prefix invariant holds for any sequence of
  `ManifestPatch` validated by `applyTo`. Existing
  `ManifestPatchSuite` has the targeted cases; F11 wraps in a
  ScalaCheck property over generated patch sequences. Maps to
  invariant 12.
- [ ] **F12. `status != "pending" → baseSha non-null`.** Property
  check that no FSM-driven manifest mutation leaves a piece with
  `status` in `{in_progress, merged}` and `baseSha = None`. Maps
  to invariant 13.
- [ ] **F13. Atomic merge mutation persists across crash.** The
  invariant is "manifest mutation must commit before the FSM
  transition to `Refining`" (§11.5 step 1). Two sides — the
  *ordering* (writer-side, the orchestrator's atomic-write
  sequence) and the *recovery* (reader-side, `RebuildState.run`
  observing the post-crash on-disk state). Slice 2 owns the
  reader side because that's where `RebuildState` lives; the
  writer-side test lands with the orchestrator wiring in Slice 4
  (carry-forward S2-5).

  Slice-2 F13 covers the reader and exercises the four
  classification sub-cases (a)–(d) defined in PR-E E4. Five
  fixture-driven tests:
  - **F13a — case (c) happy crash window.** Fixture:
    `manifest.json` with one piece marked `status="merged"` (plus
    `mergeCommit`, `mergedAt`, `prNumber`); action log whose last
    fsm.transition is `… → PieceAwaitingMerge(p, prNumber)` and
    which has neither the `PieceAwaitingMerge → Refining` entry
    nor `audit.piece_merged`. Run
    `RebuildState.run(featureId, paths, fileManifestStore, log,
    cache)`. Assertions: (1) `Right(feature)` with
    `feature.state == Refining(p, prNumber, observedAt =
    mergedAt)` (synthetic `Merged` applied with `observedAt =
    mergedAt`), (2) the action log has gained
    `fsm.transition: PieceAwaitingMerge → Refining`,
    `audit.piece_merged`, and `harness.crash_recovered` entries,
    (3) state cache rewritten to match.
  - **F13b — case (b) partial batch repair.** Two fixtures, both
    triggering case (b) but at different fold-states, to confirm
    the rule doesn't bake in a "state is Refining" assumption:
    - **F13b₁ — repair at `Refining`.** Manifest says `p` is
      merged; the log contains `fsm.transition: PieceAwaitingMerge
      → Refining(p)` but lacks `audit.piece_merged` and stops
      there. Run `RebuildState.run`. Assertions: (1)
      `Right(feature)` with `feature.state` equal to the fold's
      pre-repair state (which is `Refining(p, _, _)` here),
      (2) the log has gained a synthesized `audit.piece_merged`
      plus `harness.crash_recovered`, (3) no synthetic
      `fsm.transition` was added.
    - **F13b₂ — repair at a forward state.** Manifest says `p`
      is merged; the log contains `fsm.transition:
      PieceAwaitingMerge → Refining(p)` AND `fsm.transition:
      Refining(p) → PieceImplementing(next)` but lacks
      `audit.piece_merged` for `p`. (The crash happened after the
      transition lines for the *next* piece were written but
      before the missing-audit was noticed; in practice, this is
      a degenerate replay state — possible only if the writer
      somehow re-ordered. The rule must still handle it
      gracefully.) Run `RebuildState.run`. Assertions: (1)
      `Right(feature)` with `feature.state ==
      PieceImplementing(next)` (untouched by the repair —
      reconcile case (b) does not synthesize FSM transitions),
      (2) the log has gained a synthesized `audit.piece_merged`
      for `p` plus `harness.crash_recovered`.
  - **F13c — case (c) inconsistent fold-state.** Fixture:
    manifest says piece `p` is merged but the log's fold-state is
    `PieceAwaitingReview(p, _)` (not `PieceAwaitingMerge`). Run
    `RebuildState.run` and assert
    `Left(InconsistentRecovery(...))`.
  - **F13d — case (d) audit-only orphan.** Fixture: manifest says
    `p` is merged; log lacks the fsm.transition but contains an
    `audit.piece_merged` for `p`. Assert
    `Left(InconsistentRecovery(...))` — case (d) is structurally
    impossible under §11.5 ordering so we refuse.
  - **F13e — multi-piece divergence.** Fixture: manifest claims
    two pieces merged; log's last fsm.transition only reached
    `PieceAwaitingMerge(p2)` with no log-level evidence of `p1`'s
    `Refining` transition. Assert
    `Left(InconsistentRecovery("multi-piece partial merge..."))`.

  F13 thus exercises (a) the reader's authority to lift the FSM
  into `Refining` from a manifest-recorded merge that the log
  missed, (b) the rule's repair of partial-batch crash windows
  via synthetic `audit.piece_merged`, (c)/(d) the rule's refusal
  to invent transitions outside the legal §11.5 crash windows,
  and (e) the multi-piece refusal — matching invariant 14.

### 1.7 PR G — Close-out: code review + carry-forward walk

- [ ] **G1.** Code review on PR-A through PR-F as a section. Review
  comments folded back into PR-A–F or into PR-G as a final fixups
  commit.
- [ ] **G2.** `roadmap.md` §2.2 bullets flipped from any `[~]` markers
  to `[x]`. Status header line in `roadmap.md` updated to reflect
  Slice 2 closure.
- [ ] **G3.** `AGENTS.md` "Current state" Slice 2 paragraph
  rewritten (mirror the Slice 1 wording in `AGENTS.md` today).
  "Active design-`<section>`.md files" list returns to *(none
  currently open)*. Module-layout table updated:
  - `forge-core` now owns `Manifest` / `ManifestPatch` / `Piece` /
    `PieceStatus` (consequence of PR-A A1).
  - `forge-core` ownership row gains `PrSnapshot` (carry-forward
    S2-4 — `AGENTS.md`'s current row places it under `forge-git`;
    the spec §3.2 is right and the doc was wrong).
  - `forge-specs` row narrows to `SpecStore`, `DocSync`,
    `ChangeCollector` (which mostly land in Slice 4 — note that
    inline). `forge-git` row narrows to `BranchManager`,
    `PRWatcher` only.
- [ ] **G4.** `CLAUDE.md` TL;DR "Active implementation plan" + "Current
  state" rewritten. The `ForgePaths` seam rule in the TL;DR
  graduates from "smell test" to "enforced by build" (per PR-A A4
  mechanism).
- [ ] **G5.** §4 carry-forward walked. Inherited from Slice 1:
  - **C14** (Codex resume can't reapply system prompt). PR-C C5
    embedded the FSM-side comment; the orchestrator-side wiring is
    Slice 4 (which inherits the carry-forward). G5 confirms the
    FSM comment is in place and that design-2.2 §4 still names C14
    as the open item. No code resolution lands here; the spec gap
    is genuinely a v1.3 question.
  - **C15** (PR-D reviewer regression suite deferred to Slice 4
    reviewer-asset PR). Unchanged; design-2.2 §4 re-names it.
  - New Slice-2 carry-forwards (`S2-1` etc., named in §4 below)
    each get a durable home in `design-rationale.md` or
    `roadmap.md` §7.2 before G2 flips the roadmap bullet.
- [ ] **G6.** This file flipped from "active" to "audit trail".
  `design-2.3.md` opens when Slice 3 starts.

## 2. Order of work

`A → B → C → D → E → F → G` — strictly linear.

`E` depends on `D` (PR-E `verifyAgainstLog` needs `ActionLog`;
`RebuildState.run` needs `Feature.foldEvents` which lands in D4),
so they can't be parallelised as the first cut of this doc claimed.
The earlier "D ∥ E" framing is incorrect and stayed only briefly in
the draft.

`F` is a single PR (not split per-invariant) because the property
suites share generator infrastructure (F0) and the marginal cost of
landing them together is small.

## 3. Status log

Update this section as items land. The roadmap section ticks off only
after PR-G lands.

- 2026-05-26 — design-2.2.md created on the close of Slice 1
  (`design-2.1.md` closed earlier same day). No PR-A code yet.

## 4. Carry-forward to v1.3

Items the section closure (PR-G) **must not silently bury** when it
flips the §2.2 roadmap bullet. Each one needs a durable home — a
roadmap §3 v1.3 bullet, a tracking issue, or an explicit
deferred-decision entry in `design-rationale.md` — before PR-G G2
ticks the section.

### Inherited from Slice 1

- **C14 — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a)
  system-prompt prepending** (design-rationale C14). The FSM-side
  exposure: PR-C C5 places a source-level comment on the
  design-revision and design-PR-feedback resume sites, so the
  Slice-4 orchestrator wiring inherits the awareness without
  re-deriving it. **No code resolution in Slice 2** — the trait
  shape is the spec gap and v1.3 closes it.
- **C15 — Native schema regression suite (PR-D) deferred from
  Slice 1 to the reviewer-asset PR** (design-rationale C15). Slice 2
  doesn't touch this; the bullet rolls forward unchanged. Lands on
  the Slice-4 reviewer-asset PR.

### New in Slice 2

- **S2-1 — Manifest data types live in `forge-core`, not
  `forge-specs`** (PR-A A1). v1.2 §3.2 names "Manifest" under
  `forge-specs`. The actual code lands under
  `io.forge.core.manifest` to break the cyclic-dep concern
  (`forge-core.Feature` references `Manifest`; `Feature` belongs in
  `forge-core` per §3.2). **Status:** **filed as a
  `design-rationale.md` entry in PR-A A2** (not held until PR-G) so
  reviewers seeing the move have a durable "why". PR-A A1
  implements the move; PR-G G3 updates the `AGENTS.md`
  module-layout table; v1.3 needs to correct §3.2.
- **S2-2 — `FsmEvent` ADT shape not in v1.2 spec** (PR-B B4). v1.2
  §17 names `FsmEvent` but doesn't enumerate it; PR-B settles the
  list. The chosen variants become the de-facto contract for
  Slice 3 (`BranchManager` / `PRWatcher` / `SessionMonitor`
  produce these events) and Slice 4 (orchestrator loop). **Status:**
  PR-B B4 names the variants here; this entry captures the shape so
  v1.3 can lift it into §6 or §11 as appropriate.
- **S2-3 — `ActionLog` write durability vs. throughput** (PR-D D2).
  v1.2 §19 says "append-only", doesn't specify fsync. PR-D defaults
  to `APPEND + SYNC`; if Slice 4 surfaces a perf cliff, the fallback
  is per-batch `force()` with the trade-off documented as a
  carry-forward. **Status:** decided in PR-D; only opens as a
  carry-forward if Slice 4 actually trips the cliff.
- **S2-4 — `PrSnapshot` ownership mismatch between v1.2 §3.2 and
  `AGENTS.md`** (PR-B B0). v1.2 §3.2 places "PrSnapshot ADT" in
  `forge-core`; `AGENTS.md`'s module-layout table places it in
  `forge-git`. The spec is right (the FSM has to consume
  `PrSnapshot` events; `forge-git` already depends on `forge-core`,
  not the other way around). **Status:** PR-B B0 implements it in
  `forge-core`; PR-G G3 corrects the `AGENTS.md` row. No v1.3 spec
  change needed because §3.2 is already correct; this is an
  `AGENTS.md` doc bug.
- **S2-5 — Writer-side atomic-merge ordering test deferred to
  Slice 4** (PR-F F13). Slice-2 F13 covers the *reader* side of
  §11.5 step 1 (replay correctly recovers from a crash between
  manifest write and FSM transition). The *writer* side (assert the
  orchestrator atomically persists `manifest.json` before the
  state-cache write) needs an orchestrator under test, which lands
  with Slice 4's headless feature loop. **Status:** PR-G G5 walks
  this forward; the Slice-4 `design-2.4.md` carry-forward list
  picks it up as a gating test on the relevant sub-PR.
- *(more added as PR-A–F land if review surfaces them)*

## 5. Cross-references

- v1.2 spec for FSM, Feature, ActionLog, StateCache: §4, §6, §6.1,
  §6.2, §11.0–§11.7, §19.
- v1.2 spec for Manifest invariants exercised in F10–F12: §5.1,
  §5.5.
- v1.2 spec for the paths-helper seam (PR-A A3–A4): §4, §17 Slice 2.
- v1.2 spec for budget and locking events surfaced as `FsmEvent`s
  (PR-B B4): §12, §13.
- Slice 0 wire-shape findings consumed by `FsmEvent` design:
  `slice-0/slice-0-report.md` §2 (resume preserves session id).
- Decisions backing the FSM trait shape: design-rationale C9, C10,
  C11, C12, C14.
- Phase context + seam discipline: `roadmap.md` §2.2, §2.6, §7.1
  (the `.forge/state` re-rooting question that PR-A's `ForgePaths`
  pre-resolves).
- Predecessor: `design-2.1.md` (Slice 1 audit trail) — closed
  2026-05-26.
