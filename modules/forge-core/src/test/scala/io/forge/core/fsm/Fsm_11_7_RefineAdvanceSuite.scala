package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.manifest.{ManifestPatch, ManifestPatchOp}
import io.forge.core.review.RefineVerdict

/** §11.7 — Post-merge Refining and advance. NoChange / UpdatePlan / ReopenDesign. */
class Fsm_11_7_RefineAdvanceSuite extends munit.FunSuite:

  test("Refining(p) + NoChange + nextPending defined → PieceImplementing(next); currentPieceSessionId cleared"):
    val f = featureIn(
      FsmState.Refining(P1, P1Pr, ObservedAt),
      pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.RefineOutcome(RefineVerdict.NoChange))
    assertEquals(out.state, FsmState.PieceImplementing(P2))
    assertEquals(out.currentPieceSessionId, None, "currentPieceSessionId cleared on advance (§6.1)")
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "fsm.transition")

  test("Refining(p) + NoChange + no pending pieces → FeatureDone"):
    val f = featureIn(
      FsmState.Refining(P1, P1Pr, ObservedAt),
      pieces = Vector(pieceMerged(P1, 1, P1Pr)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.RefineOutcome(RefineVerdict.NoChange))
    assertEquals(out.state, FsmState.FeatureDone)
    assertEquals(out.currentPieceSessionId, None)

  test("Refining(p) + UpdatePlan(patch) → PlanningUpdate(reason, patch); currentPieceSessionId cleared"):
    val patch = ManifestPatch("reorder", Vector(ManifestPatchOp.ReorderPieces(Vector(P2))))
    val f = featureIn(
      FsmState.Refining(P1, P1Pr, ObservedAt),
      pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.RefineOutcome(RefineVerdict.UpdatePlan(patch)))
    out.state match
      case FsmState.PlanningUpdate(reason, p) =>
        assertEquals(reason, "reorder")
        assertEquals(p, patch)
      case other => fail(s"expected PlanningUpdate, got $other")
    assertEquals(out.currentPieceSessionId, None)

  test("Refining(p) + ReopenDesign(reason) → NHI(ReopenDesign(None)); currentPieceSessionId cleared"):
    val f = featureIn(
      FsmState.Refining(P1, P1Pr, ObservedAt),
      pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1"),
      designPr = Some(DesignPr)
    )
    val (out, _) = Fsm.transition(f, FsmEvent.RefineOutcome(RefineVerdict.ReopenDesign("design drift")))
    out.state match
      case FsmState.NeedsHumanIntervention(reason, ResumeHint.ReopenDesign(None)) =>
        assert(reason.contains("design drift") || reason.contains("flagged"))
      case other => fail(s"expected NHI(ReopenDesign(None)), got $other")
    assertEquals(out.currentPieceSessionId, None)

  test("PlanningUpdate + PlanningDecision(Accept) → applies patch and advances to next piece"):
    val patch = ManifestPatch("reorder", Vector(ManifestPatchOp.ReorderPieces(Vector(P1, P3, P2))))
    val f = featureIn(
      FsmState.PlanningUpdate("reorder", patch),
      pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2), piecePending(P3, 3))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PlanningDecision("reorder", patch, PlanningChoice.Accept))
    // After reorder: P1 (merged) stays at head, then P3, then P2. nextPending = P3.
    assertEquals(out.state, FsmState.PieceImplementing(P3))
    assertEquals(out.manifest.pieces.map(_.id), Vector(P1, P3, P2))

  test("PlanningUpdate + PlanningDecision(Reject) → advance without modifying manifest"):
    val patch = ManifestPatch("reorder", Vector(ManifestPatchOp.ReorderPieces(Vector(P1, P3, P2))))
    val f = featureIn(
      FsmState.PlanningUpdate("reorder", patch),
      pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2), piecePending(P3, 3))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PlanningDecision("reorder", patch, PlanningChoice.Reject))
    assertEquals(out.state, FsmState.PieceImplementing(P2))
    assertEquals(out.manifest.pieces.map(_.id), Vector(P1, P2, P3), "manifest unchanged on reject")
