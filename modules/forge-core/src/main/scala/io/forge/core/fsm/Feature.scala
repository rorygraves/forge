package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.Json.given
import io.forge.core.cost.CostTotals
import io.forge.core.manifest.Manifest

import upickle.default.ReadWriter

/** §6 — the in-memory aggregate that `Fsm.transition` operates over.
  *
  * `manifest` is loaded from the committed `manifest.json` (the source of truth per §4); the action log records FSM
  * transitions over it. `state`, the session-id projections, and `cost` are all derivable by replaying the action log
  * onto an initial `Feature` (PR-D D4 `Feature.foldEvents`).
  *
  * Session-id projections (§6.1):
  *   - `designSessionId`: populated on the first `InteractiveSpec` spawn / design-revision resume; retained through
  *     every design-phase state (`DesignReviewing`, `DesignNeedsHumanInput`, `DesignAwaitingMerge`,
  *     `DesignPrFeedback`); cleared on entering `DesignReady`.
  *   - `currentPieceSessionId`: populated at `PieceImplementing` or `PieceFixingUp` spawn; retained through
  *     `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceCiFailed`, `PieceReviewFailed`, `PieceFixingUp`,
  *     `PieceAwaitingMerge`, `Refining`; cleared at the moment of advancing past the piece (to next
  *     `PieceImplementing`, `FeatureDone`, `PlanningUpdate`, or `NeedsHumanIntervention`).
  *
  * `branchProtectionCacheEpoch` is bumped on every `forge resume` (§8.1) so cached branch-protection results from a
  * prior orchestrator process are invalidated.
  *
  * `designPrFeedbackRound` is the monotonic counter behind v1.2 §11.3's `DesignPrFeedback(prNumber, round + 1)` — it
  * remembers the last completed `DesignPrFeedback` round number while the FSM is back in `DesignAwaitingMerge`, so the
  * next human-comment / `CHANGES_REQUESTED` event enters `DesignPrFeedback(prNumber, lastRound + 1)` rather than
  * restarting at `round = 1` (which would reuse audit filenames like `design-pr-feedback-r1-answers.md` and snapshot
  * tags). PR-C originally restarted at 1; this projection fixes the §11.3 cross-cycle wording. Reset on
  * `Resume(ReopenDesign)` since that re-opens design from scratch.
  */
final case class Feature(
    id: FeatureId,
    manifest: Manifest,
    state: FsmState,
    cost: CostTotals,
    designSessionId: Option[String],
    currentPieceSessionId: Option[String],
    branchProtectionCacheEpoch: Long,
    designPrFeedbackRound: Int = 0
) derives ReadWriter

object Feature:
  /** Initial seed used by `RebuildState.run` (PR-E E4 pipeline step 2): the manifest is the committed source of truth,
    * the state is `Drafting`, totals are zero, no sessions are open, and the cache epoch is zero. The subsequent
    * `foldEvents` projects the recorded transitions onto this seed.
    */
  def initial(id: FeatureId, manifest: Manifest): Feature =
    Feature(
      id = id,
      manifest = manifest,
      state = FsmState.Drafting,
      cost = CostTotals.zero,
      designSessionId = None,
      currentPieceSessionId = None,
      branchProtectionCacheEpoch = 0L,
      designPrFeedbackRound = 0
    )

  /** PR-D D4 — pure replay of a feature's action log onto an initial `Feature`. Delegates to `io.forge.core.log.Replay`
    * (kept in the `log` package because it owns `ReplayError` / `FoldResult` / `ObservedTransition`); this companion
    * method exists so callers can write `Feature.foldEvents(initial, actions)` matching the design-2.2 §1.4 D4
    * signature exactly.
    */
  def foldEvents(
      initial: Feature,
      actions: Vector[io.forge.core.log.Action]
  ): Either[io.forge.core.log.ReplayError, io.forge.core.log.FoldResult] =
    io.forge.core.log.Replay.foldEvents(initial, actions)
