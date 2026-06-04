package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.manifest.PieceStatus

/** design-3.3-trunk / W3 — the trunk-commit (no-PR) integration path.
  *
  * A `TrunkBased` repo integrates a piece by committing straight to the trunk branch with no PR, so the orchestrator
  * emits the neutral `CommittedToTrunk` event (the FSM stays profile-agnostic) and the piece goes `PieceImplementing |
  * PieceBuildFixingUp → Refining(p, None)` with no `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge`
  * tail. The integration shares `handleIntegrated` with the PR `Merged` path, so this suite mirrors
  * `Fsm_11_5_MergedIdempotencySuite`'s shape against the trunk variant.
  */
class Fsm_11_5_TrunkPathSuite extends munit.FunSuite:

  private val TrunkCommit = Sha40Other
  private val CommittedAt = MergedAt

  test("PieceImplementing + CommittedToTrunk → manifest merged (no PR), Refining(p, None), fsm.transition + audit"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.CommittedToTrunk(P1, TrunkCommit, CommittedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.Refining(P1, None, startedAt = ObservedAt))
    val p1 = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1.status, PieceStatus.Merged)
    assertEquals(p1.prNumber, None, "a trunk piece carries no PR number")
    assertEquals(p1.mergeCommit, Some(TrunkCommit))
    assertEquals(p1.mergedAt, Some(CommittedAt))
    assertEquals(drafts.size, 2)
    assertEquals(drafts(0).kind, "fsm.transition")
    assertEquals(drafts(1).kind, "audit.piece_merged")
    // §19: prNumber is null on the trunk path; the piece id is still recorded.
    assertEquals(drafts(1).payload("p").str, P1.value)
    assertEquals(drafts(1).payload("prNumber"), ujson.Null)

  test("PieceBuildFixingUp + CommittedToTrunk → Refining(p, None) (re-gate passed on a TrunkBased repo)"):
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("fix-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.CommittedToTrunk(P1, TrunkCommit, CommittedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.Refining(P1, None, startedAt = ObservedAt))
    assertEquals(out.manifest.pieces.find(_.id == P1).get.status, PieceStatus.Merged)
    assertEquals(drafts.map(_.kind), Vector("fsm.transition", "audit.piece_merged"))

  test("idempotent re-apply: manifest[p] already trunk-merged with matching fields → no mutation, still Refining"):
    val pre = pieceMergedTrunk(P1, 1, TrunkCommit, CommittedAt)
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pre, piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.CommittedToTrunk(P1, TrunkCommit, CommittedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.Refining(P1, None, startedAt = ObservedAt))
    assertEquals(out.manifest.pieces.find(_.id == P1).get, pre, "manifest untouched on idempotent re-apply")
    assertEquals(drafts.size, 2)

  test("trunk-merged with disagreeing mergeCommit → NHI(AbortOrAbandon) + harness.error mismatch"):
    val pre = pieceMergedTrunk(P1, 1, Sha40 /* differs from event TrunkCommit */, CommittedAt)
    val f = featureIn(FsmState.PieceImplementing(P1), pieces = Vector(pre, piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.CommittedToTrunk(P1, TrunkCommit, CommittedAt, ObservedAt)
    )
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    val mismatch = drafts.find(_.kind == "harness.error").getOrElse(fail("expected harness.error draft"))
    assertEquals(mismatch.payload("kind").str, "merged_field_mismatch")
    assertEquals(mismatch.payload("expected").obj("prNumber"), ujson.Null)

  test("CommittedToTrunk for a different piece than the active one → no-op"):
    val f = featureIn(FsmState.PieceImplementing(P1), pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.CommittedToTrunk(P2, TrunkCommit, CommittedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.PieceImplementing(P1))
    assert(drafts.isEmpty)

  test("trunk Refining + NoChange + nextPending → PieceImplementing(next); currentPieceSessionId cleared"):
    val f = featureIn(
      FsmState.Refining(P1, None, ObservedAt),
      pieces = Vector(pieceMergedTrunk(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("refine-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.RefineOutcome(io.forge.core.review.RefineVerdict.NoChange))
    assertEquals(out.state, FsmState.PieceImplementing(P2))
    assertEquals(out.currentPieceSessionId, None)

  test("trunk Refining + NoChange + no pending → FeatureDone"):
    val f = featureIn(
      FsmState.Refining(P1, None, ObservedAt),
      pieces = Vector(pieceMergedTrunk(P1, 1))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.RefineOutcome(io.forge.core.review.RefineVerdict.NoChange))
    assertEquals(out.state, FsmState.FeatureDone)

  test("trunk Refining + refine settle timeout → NHI(AbortOrAbandon) (no PR to re-run a fix-up against)"):
    val f = featureIn(
      FsmState.Refining(P1, None, ObservedAt),
      pieces = Vector(pieceMergedTrunk(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("refine-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Refine, "cap"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.AbortOrAbandon) => ()
      case other => fail(s"expected NHI(AbortOrAbandon), got $other")
    assertEquals(out.currentPieceSessionId, None)
