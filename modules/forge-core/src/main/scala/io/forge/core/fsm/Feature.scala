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
  */
final case class Feature(
    id: FeatureId,
    manifest: Manifest,
    state: FsmState,
    cost: CostTotals,
    designSessionId: Option[String],
    currentPieceSessionId: Option[String],
    branchProtectionCacheEpoch: Long
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
      branchProtectionCacheEpoch = 0L
    )
