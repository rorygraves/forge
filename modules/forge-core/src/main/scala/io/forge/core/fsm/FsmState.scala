package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.Json.given
import io.forge.core.manifest.ManifestPatch

import java.time.Instant
import upickle.default.ReadWriter

/** §6 — recovery hint paired with every `NeedsHumanIntervention`. Each variant maps to a concrete `forge resume
  * --<mode>` command (§15).
  *
  * The variants and field shapes mirror v1.2 §6 exactly:
  *   - `ResumeAfterHumanPush(p, prNumber)` — operator pushed a fix to the piece branch.
  *   - `CommitAndPushHumanFix(p, prNumber)` — operator has uncommitted local changes for the piece.
  *   - `RunAnotherFixup(p, prNumber)` — re-run fix-up driver on the same piece.
  *   - `ResolveLocalImplementationChanges(p, branch)` — pre-PR (no `prNumber` yet); operator resolves dirty WC.
  *   - `ReopenDesign(prNumber)` — fall back to design phase; `prNumber` is `None` when no design PR exists yet (e.g.
  *     missing `designSessionId`, §6.2).
  *   - `ApplyPlanningUpdate(patch)` — refinery proposed a manifest update; operator approves to apply.
  *   - `AbortOrAbandon` — no automated recovery; operator runs `forge abandon`.
  */
enum ResumeHint derives ReadWriter:
  case ResumeAfterHumanPush(p: PieceId, prNumber: PrNumber)
  case CommitAndPushHumanFix(p: PieceId, prNumber: PrNumber)
  case RunAnotherFixup(p: PieceId, prNumber: PrNumber)
  case ResolveLocalImplementationChanges(p: PieceId, branch: BranchName)
  case ReopenDesign(prNumber: Option[PrNumber])
  case ApplyPlanningUpdate(patch: ManifestPatch)
  case AbortOrAbandon

/** §6 — the FSM state of a feature. Transition rules live in §11.x and are implemented in
  * `io.forge.core.fsm.Fsm.transition` (lands in PR-C).
  *
  * There is intentionally no `PieceMerged` state. The merge event flow is: `PieceAwaitingMerge → [atomic manifest
  * mutation] → Refining → next state`. The "piece merged" milestone is recorded in the action log as
  * `audit.piece_merged` (§19); the FSM passes straight through `Refining` to whatever comes next.
  */
enum FsmState derives ReadWriter:
  // --- Spec phase ---

  /** §11.1 — initial state; no driver session yet. */
  case Drafting

  /** §11.1 — spec driver running; sessionId tracked in `Feature.designSessionId` (§6.1). */
  case InteractiveSpec

  /** §11.2 — reviewer revision round in progress (round 1+). `feature.designSessionId` still populated. */
  case DesignReviewing(round: Int)

  /** §11.2 step 11 — reviewer surfaced one or more `Blocking` questions; awaiting human answers. */
  case DesignNeedsHumanInput(round: Int, questions: Vector[Question])

  /** §11.3 — design PR open, polling for merge. */
  case DesignAwaitingMerge(prNumber: PrNumber)

  /** §11.3 — design PR received human `CHANGES_REQUESTED` or a new comment; running another revision round. */
  case DesignPrFeedback(prNumber: PrNumber, round: Int)

  /** §11.3 — design PR merged. `feature.designSessionId` cleared on entry (§6.1). */
  case DesignReady

  // --- Implementation phase ---

  /** §11.4 — piece driver running; sessionId tracked in `Feature.currentPieceSessionId` (§6.1). */
  case PieceImplementing(p: PieceId)

  /** §11.5 — PR open, polling CI for readiness. */
  case PieceAwaitingCi(p: PieceId, prNumber: PrNumber)

  /** §11.5 — CI green; polling for reviewer verdict / human merge. */
  case PieceAwaitingReview(p: PieceId, prNumber: PrNumber)

  /** §11.5 — CI failed; awaiting human or fix-up. `attempt` counts prior fix-up attempts. */
  case PieceCiFailed(p: PieceId, prNumber: PrNumber, attempt: Int)

  /** §11.5 — reviewer requested changes; awaiting fix-up. */
  case PieceReviewFailed(p: PieceId, prNumber: PrNumber, attempt: Int)

  /** §11.6 — fix-up driver running on the piece. */
  case PieceFixingUp(p: PieceId, prNumber: PrNumber, attempt: Int)

  /** §8.3 / §11.4 (1.8) — the local **pre-PR** Build gate failed and routed to a driver fix-up (a `CodeFix`), so the
    * piece never reached `createPr`. Awaiting a fresh fix-up driver spawn — the pre-PR analogue of [[PieceCiFailed]],
    * but **without** a `prNumber` (no PR exists yet) and with `attempt` carried **in the state only**: a pre-PR build
    * fix-up does **not** increment `manifest[p].attempts` (§11.4 — that budget is reserved for PR-side CI fix-ups). The
    * driver re-implements, then the Build gate re-runs (catching a compile error before the costly CI round-trip).
    */
  case PieceBuildFailed(p: PieceId, attempt: Int)

  /** §8.3 / §11.4 (1.8) — a pre-PR Build fix-up driver is running. The pre-PR analogue of [[PieceFixingUp]] (no
    * `prNumber`). On a clean settle the Build gate re-runs via the same `ClassifyCommitOpenPr` post-settle effect as
    * [[PieceImplementing]]: pass ⇒ `PrOpened` → [[PieceAwaitingCi]]; re-fail ⇒ another `LocalBuildFailed` →
    * [[PieceBuildFailed]] (gated by `maxFixupRounds`, exhausting to `NeedsHumanIntervention`).
    */
  case PieceBuildFixingUp(p: PieceId, attempt: Int)

  /** §11.5 — CI + reviewer both green; awaiting human merge. */
  case PieceAwaitingMerge(p: PieceId, prNumber: PrNumber)

  /** §11.7 — refinery running on the just-merged piece. `startedAt` is the orchestrator's observation time, not the
    * upstream `mergedAt` (see PR-B B4 `Merged` note: §14.1 "Refining piece (Xs)" elapsed clock starts here).
    *
    * `prNumber` is `Some` for the PR (GitFlow) merge path and `None` for the trunk-commit path (design-3.3-trunk / W3):
    * a `TrunkBased` repo integrates a piece by committing straight to the trunk branch with no PR, so the refining step
    * that follows carries no PR number. The FSM reaches the trunk variant via the neutral `CommittedToTrunk` event (the
    * orchestrator decides the branch model; the FSM stays profile-agnostic — the §6.1 replay invariant).
    */
  case Refining(p: PieceId, prNumber: Option[PrNumber], startedAt: Instant)

  /** §14.3 — refinery proposed a manifest update; awaiting operator approval. */
  case PlanningUpdate(reason: String, patch: ManifestPatch)

  // --- Recovery / terminal ---

  /** §11.0 step 5 — automated recovery exhausted; operator runs `forge resume --<hint>`. */
  case NeedsHumanIntervention(reason: String, resumeHint: ResumeHint)

  /** §11.7 — all pieces merged, refinery clean. */
  case FeatureDone

  /** §11.0 — terminal abandon (only via `forge abandon`). */
  case Abandoned(reason: String)
