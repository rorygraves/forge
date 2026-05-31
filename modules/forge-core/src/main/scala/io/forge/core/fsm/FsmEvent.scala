package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.Json.given
import io.forge.core.manifest.ManifestPatch
import io.forge.core.pr.PrSnapshot
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}

import java.time.Instant
import upickle.default.ReadWriter

/** §6 / §17 / PR-B B4 — input alphabet of `Fsm.transition(feature, event)`.
  *
  * v1.2 §17 names `FsmEvent` but doesn't enumerate it; PR-B B4 settles the variants. The §11.x cross-reference on each
  * variant names the lifecycle step that produces it; PR-C implements the corresponding transition rule.
  *
  * Naming note: B4's sketch named the design-review-result variant `DesignReviewVerdict`, but that clashes with the
  * `io.forge.core.review.DesignReviewVerdict` ADT carried as the `verdict` field. Renamed to `DesignReviewReceived`
  * (matching the past-tense convention of `PrSnapshotUpdated`, `BranchCreated`, `Merged`, etc.). The §4 carry-forward
  * entry S2-2 captures the variant list as the de-facto contract.
  *
  * Provider neutrality: events that carry reviewer verdicts use the core-side summary types in `io.forge.core.review`.
  * `forge-agents` projects its rich `DesignReview` / `PrReview` / `RefineResult` shapes into those summaries at the
  * Slice-4 wiring call site; `forge-core` never sees the raw reviewer payload.
  */
enum FsmEvent derives ReadWriter:
  // --- Driver session lifecycle (§7.1, §11.1, §11.4, §11.6) ---

  /** §11.1 (spec spawn), §11.4 (impl spawn), §11.6 (fix-up spawn). Projects `feature.designSessionId` or
    * `feature.currentPieceSessionId` per §6.1.
    */
  case SessionSpawned(
      actor: String,
      role: String,
      sessionId: String,
      piece: Option[PieceId]
  )

  /** §11.2 step 12 (design revision), §11.3 step 2 (design PR feedback). Under the pinned CLIs (Slice 0 §2),
    * `newSessionId == oldSessionId` is the norm; the two-field shape stays forward-compatible with a future CLI that
    * mints a new id on resume (§6.1).
    */
  case SessionResumed(
      actor: String,
      role: String,
      oldSessionId: String,
      newSessionId: String,
      piece: Option[PieceId]
  )

  /** §11.1 step 6, §11.2, §11.4 etc. — a driver session settled cleanly. The orchestrator names the originating phase
    * so the FSM can dispatch on it.
    */
  case Settled(phase: SessionPhase, outcome: SettleOutcome)

  /** §11 settle bounds, §12. Wall-clock cap expired before settle. */
  case SettleTimeout(phase: SessionPhase, reason: String)

  /** §12. Per-turn cost cap breached mid-turn. */
  case TurnBudgetBreached(phase: SessionPhase, message: String)

  // --- Design phase (§11.2, §11.3) ---

  /** §11.2 steps 11/12/13 — reviewer returned a verdict on design revision round `round`. (B4's sketch called this
    * `DesignReviewVerdict`; renamed to avoid the name clash with the `DesignReviewVerdict` ADT in
    * `io.forge.core.review`.)
    */
  case DesignReviewReceived(round: Int, verdict: DesignReviewVerdict)

  /** §11.2 step 11 — human answered the reviewer's `Blocking` questions; revision round can resume. */
  case DesignReviewClarified(round: Int)

  /** §11.3 — `PRWatcher` produced a fresh snapshot of the design PR. */
  case DesignPrSnapshotUpdated(snapshot: PrSnapshot)

  // --- Implementation phase (§11.4, §11.5) ---

  /** §11.4 step 1 — branch created and `baseSha` recorded; manifest piece moves to `status="in_progress"`. */
  case BranchCreated(piece: PieceId, branchName: BranchName, baseSha: Sha)

  /** §11.4 step 6 — PR opened against the base. Manifest piece gets `prNumber`. */
  case PrOpened(piece: PieceId, prNumber: PrNumber)

  /** §11.5 — `PRWatcher` produced a fresh snapshot of a piece PR. */
  case PrSnapshotUpdated(piece: PieceId, snapshot: PrSnapshot)

  /** §11.5 `PieceAwaitingReview` — reviewer returned a verdict on the piece's PR. (Variant name matches B4's sketch;
    * the `PrReviewVerdict` ADT in `io.forge.core.review` is named differently, so no clash here.)
    */
  case CodeReviewVerdict(piece: PieceId, verdict: PrReviewVerdict)

  /** §8 — CI discovery finished (either branch-protection required set surfaced, or `checkDiscoveryTimeoutSec`
    * expired). Unblocks the §11.5 `PieceAwaitingCi → PieceAwaitingReview` transition under
    * `CiPolicy.BranchProtectionThenObserved`.
    */
  case CheckDiscoveryComplete(piece: PieceId, prNumber: PrNumber)

  /** §8 — the orchestrator's CI-readiness gate determined a piece PR can never go green without a human push: no checks
    * discovered after `checkDiscoveryTimeoutSec`, a required check never appeared, or fewer than
    * `minimumExpectedChecks` were observed (§8 discovery rules 1-3). The event carries the operator-facing `reason`;
    * the FSM is `CiPolicy`-agnostic — it routes `(reason, ResumeAfterHumanPush(p, prNumber))` to
    * `NeedsHumanIntervention` without knowing anything about checks/overlays/timeouts. A dedicated event (rather than
    * `HarnessError`) is needed because §8 mandates the `ResumeAfterHumanPush` hint, whereas a `PieceAwaitingCi`
    * `HarnessError` resolves to `RunAnotherFixup`.
    */
  case CiReadinessBlocked(piece: PieceId, prNumber: PrNumber, reason: String)

  /** §11.5 step 1 — piece merged upstream. Two timestamps because they mean different things:
    *
    *   - `mergedAt`: the historical fact from GitHub (the moment the PR went to MERGED state). Stored durably in the
    *     manifest's piece record for audit.
    *   - `observedAt`: when the orchestrator processed the merge. Used by the FSM to set `Refining.startedAt`, which
    *     §11.7 / §14.1 surface as "Refining piece <p>... (<elapsed>s)" — that elapsed clock starts when Forge enters
    *     refining, not when the PR merged upstream.
    *
    * The FSM is pure, so the orchestrator stamps `observedAt = Clock.now` at event construction. The synthetic recovery
    * branch in `RebuildState.reconcile` (PR-E E4 case (c)) sets `observedAt = mergedAt` because that's the closest
    * historical fact available.
    *
    * **Idempotency**: the `Merged` handler in `Fsm.transition` is idempotent w.r.t. the manifest mutation — see
    * design-2.2.md §1.2 B4 and PR-C C3 (`Fsm_11_5_MergedIdempotencySuite`). Reconcile case (c) relies on this.
    */
  case Merged(
      piece: PieceId,
      prNumber: PrNumber,
      mergeCommit: Sha,
      mergedAt: Instant,
      observedAt: Instant
  )

  // --- Refinery / planning (§11.7, §14.3) ---

  /** §11.7 — refinery returned a verdict on the just-merged piece. */
  case RefineOutcome(verdict: RefineVerdict)

  /** §14.3 — operator approved or rejected a refinery-proposed manifest update. `choice` follows the §11.7 + §14.3
    * prompt: `Accept`, `Reject`, `EditAndAccept`.
    */
  case PlanningDecision(
      reason: String,
      patch: ManifestPatch,
      choice: PlanningChoice
  )

  // --- Cross-cutting (§12, §13, §15) ---

  /** §12. Feature-, piece-, or harness-scope budget breached (not a per-turn breach — that's `TurnBudgetBreached`).
    */
  case BudgetBreached(scope: BudgetScope, message: String)

  /** §6.2 — `requireSessionId(None, ...)` was triggered by an orchestrator caller. Lives as an event so the transition
    * stays pure (the orchestrator constructs the event from its `requireSessionId` callsite).
    */
  case RequiredSessionIdMissing(reason: String, hint: ResumeHint)

  /** §15 — operator command. Drives transitions out of `NeedsHumanIntervention` (`Resume`), into `Abandoned`
    * (`Abandon`), or `InteractiveSpec → DesignReviewing(1)` (`Done`).
    */
  case UserCommandReceived(cmd: UserCommand)

  /** §19 `harness.error`. Generic catch-all for orchestrator-side failures that need to be surfaced as
    * `NeedsHumanIntervention`.
    */
  case HarnessError(reason: String)

/** §11 — phase tag attached to `Settled`, `SettleTimeout`, `TurnBudgetBreached`. The FSM dispatches on this to find the
  * right transition rule.
  */
enum SessionPhase derives ReadWriter:
  case Spec
  case DesignRevision
  case Implement
  case Fixup
  case DesignReview
  case CodeReview
  case Refine

/** §11.1 step 6 etc. — outcome of a settled driver session. Concrete payload is in the action log; this enum is the
  * FSM-visible projection.
  */
enum SettleOutcome derives ReadWriter:
  case Clean
  case HitQuestionLimit
  case AdapterError(message: String)

/** §14.3 — operator's response to a refinery-proposed manifest update. */
enum PlanningChoice derives ReadWriter:
  case Accept
  case Reject
  case EditAndAccept(patch: ManifestPatch)

/** §12 — scope of a budget breach. */
enum BudgetScope derives ReadWriter:
  case Feature
  case Piece(p: PieceId)
  case Harness

/** §15 — operator commands that drive FSM transitions. Mid-feature mode switching is unsupported (§1, §7), so mode is
  * not a parameter of `Resume`. The `Resume` hint is the §11.0 step 5 dispatch key.
  */
enum UserCommand derives ReadWriter:
  /** `forge new <id> --mode <m>` (or interactive equivalent). Drives `Drafting → InteractiveSpec` (§11.1). The
    * orchestrator constructs the `Feature` with `state = Drafting`; this event is the first transition.
    */
  case New(featureId: FeatureId, mode: Mode)

  /** `forge resume --<hint>` (§11.0 step 5). The hint matches one of the six `ResumeHint` variants from §6. */
  case Resume(hint: ResumeHint)

  /** `forge abandon` — only path to `Abandoned(reason)` (§11.0, §21). */
  case Abandon(reason: String)

  /** `/done` in the spec REPL (§11.1). Drives `InteractiveSpec → DesignReviewing(1)`. */
  case Done

  /** `forge refresh-cache <feature>` (§15) — manual branch-protection cache invalidation. Bumps
    * `branchProtectionCacheEpoch` only; the FSM stays in its current state (no lifecycle transition). Mirrors the epoch
    * bump that `Resume` performs in `handleResume`, but without the accompanying state change.
    */
  case RefreshCache
