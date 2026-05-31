package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.ActionDraft
import io.forge.core.manifest.{ManifestPatch, ManifestPatchOp}

import upickle.default as upickle

/** Slice 2.0 Task 2.0.6 — the `audit.resume_from_nhi` marker written when a feature resumes from
  * `NeedsHumanIntervention`. The marker makes the resume boundary explicit in the committed timeline (`{ hint, from,
  * to, reason }`) so the operator never has to truncate-and-replay the log to retry a transient failure (the MVP-run
  * rewind that corrupted the timing record, design-2.0 §0 #6).
  *
  * This pins the *write* side: a real resume emits the marker beside the `fsm.transition` (both ride the same atomic
  * batch); a no-op resume emits nothing; the pre-NHI drafts are never touched (append-only). `StatsReportSuite` pins
  * the *read* side (the fold count + render note).
  */
class FsmResumeMarkerSuite extends munit.FunSuite:

  private def resumeMarker(drafts: Vector[ActionDraft]): Option[ActionDraft] =
    drafts.find(_.kind == "audit.resume_from_nhi")

  private def hintOf(d: ActionDraft): ResumeHint =
    upickle.read[ResumeHint](d.payload("hint"))
  private def fromOf(d: ActionDraft): FsmState =
    upickle.read[FsmState](d.payload("from"))
  private def toOf(d: ActionDraft): FsmState =
    upickle.read[FsmState](d.payload("to"))

  private def resume(f: Feature, hint: ResumeHint): (Feature, Vector[ActionDraft]) =
    Fsm.transition(f, FsmEvent.UserCommandReceived(UserCommand.Resume(hint)))

  // --- the marker lands beside the transition, with the right payload ---

  test(
    "Resume(RunAnotherFixup) emits an audit.resume_from_nhi marker carrying hint/from/to/reason + the fsm.transition"
  ):
    val hint = ResumeHint.RunAnotherFixup(P1, P1Pr)
    val f = featureIn(
      FsmState.NeedsHumanIntervention("ci exhausted", hint),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2)),
      currentPieceSessionId = Some("stale")
    )
    val (out, drafts) = resume(f, hint)
    val marker = resumeMarker(drafts).getOrElse(fail("expected an audit.resume_from_nhi marker"))
    assertEquals(hintOf(marker), hint)
    assertEquals(fromOf(marker), FsmState.NeedsHumanIntervention("ci exhausted", hint))
    assertEquals(toOf(marker), out.state)
    assertEquals(marker.payload("reason").str, "ci exhausted")
    // the marker carries the piece so per-piece audit queries see it
    assertEquals(marker.piece, Some(P1))
    // the lifecycle transition still lands in the same batch (append-only — both drafts present)
    assert(drafts.exists(_.kind == "fsm.transition"), "the fsm.transition must still be emitted")
    // and the marker precedes the transition (the resume happened, then the state moved)
    assert(drafts.indexWhere(_.kind == "audit.resume_from_nhi") < drafts.indexWhere(_.kind == "fsm.transition"))

  test("Resume(ResolveLocalImplementationChanges) marker carries the piece"):
    val hint = ResumeHint.ResolveLocalImplementationChanges(P1, P1Branch)
    val f = featureIn(
      FsmState.NeedsHumanIntervention("implement settle timeout", hint),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("stale-impl")
    )
    val (_, drafts) = resume(f, hint)
    val marker = resumeMarker(drafts).getOrElse(fail("expected an audit.resume_from_nhi marker"))
    assertEquals(marker.piece, Some(P1))
    assertEquals(hintOf(marker), hint)

  test("Resume(ReopenDesign) marker has no piece (a design-phase hint)"):
    val hint = ResumeHint.ReopenDesign(Some(DesignPr))
    val f = featureIn(
      FsmState.NeedsHumanIntervention("design did not converge", hint),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    val (_, drafts) = resume(f, hint)
    val marker = resumeMarker(drafts).getOrElse(fail("expected an audit.resume_from_nhi marker"))
    assertEquals(marker.piece, None)
    assertEquals(marker.actor, Some("operator"))

  test("Resume(AbortOrAbandon) records the resume boundary into Abandoned"):
    val f = featureIn(
      FsmState.NeedsHumanIntervention("spec settle timeout", ResumeHint.AbortOrAbandon),
      designSessionId = Some("sess-1")
    )
    val (out, drafts) = resume(f, ResumeHint.AbortOrAbandon)
    assert(out.state.isInstanceOf[FsmState.Abandoned])
    val marker =
      resumeMarker(drafts).getOrElse(fail("expected an audit.resume_from_nhi marker even when resuming into abandon"))
    assertEquals(toOf(marker), out.state)

  // --- a no-op resume marks nothing (there is no boundary to record) ---

  test("Resume(ApplyPlanningUpdate) with a failing patch stays in NHI and emits NO marker"):
    val badPatch = ManifestPatch("bogus", Vector(ManifestPatchOp.RemovePiece(PieceId("p99"))))
    val hint = ResumeHint.ApplyPlanningUpdate(badPatch)
    val f = featureIn(
      FsmState.NeedsHumanIntervention("refinery planning update", hint),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, drafts) = resume(f, hint)
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], "patch failed → stays in NHI")
    assertEquals(resumeMarker(drafts), None, "a no-op resume records no boundary")
    assertEquals(drafts, Vector.empty)
