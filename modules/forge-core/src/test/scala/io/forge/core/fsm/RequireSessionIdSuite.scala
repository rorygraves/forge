package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** §17 slice-2 invariant 8 — `requireSessionId(None, ...)` always returns `Left(FsmTransition(NHI))` and never throws.
  */
class RequireSessionIdSuite extends munit.FunSuite:

  test("Some(id) → Right(id)"):
    val out = Fsm.requireSessionId(Some("abc"), "irrelevant", ResumeHint.AbortOrAbandon)
    assertEquals(out, Right("abc"))

  test("None → Left(FsmTransition(NHI(reason, hint))) for ReopenDesign(None)"):
    val out = Fsm.requireSessionId(None, "missing design session id", ResumeHint.ReopenDesign(None))
    out match
      case Left(FsmTransition(FsmState.NeedsHumanIntervention(reason, ResumeHint.ReopenDesign(None)))) =>
        assertEquals(reason, "missing design session id")
      case other => fail(s"expected Left(FsmTransition(NHI(_, ReopenDesign(None)))), got $other")

  test("None → Left(FsmTransition(NHI(reason, hint))) for ReopenDesign(Some(pr))"):
    val out = Fsm.requireSessionId(None, "missing design session id", ResumeHint.ReopenDesign(Some(DesignPr)))
    out match
      case Left(FsmTransition(FsmState.NeedsHumanIntervention(_, ResumeHint.ReopenDesign(Some(pr))))) =>
        assertEquals(pr, DesignPr)
      case other => fail(s"unexpected: $other")

  test("None never throws — exercised across every ResumeHint variant"):
    val patch = io.forge.core.manifest.ManifestPatch("noop", Vector.empty)
    val hints = Vector(
      ResumeHint.ResumeAfterHumanPush(P1, P1Pr),
      ResumeHint.CommitAndPushHumanFix(P1, P1Pr),
      ResumeHint.RunAnotherFixup(P1, P1Pr),
      ResumeHint.ResolveLocalImplementationChanges(P1, P1Branch),
      ResumeHint.ReopenDesign(None),
      ResumeHint.ReopenDesign(Some(DesignPr)),
      ResumeHint.ApplyPlanningUpdate(patch),
      ResumeHint.AbortOrAbandon
    )
    hints.foreach { hint =>
      val out = Fsm.requireSessionId(None, "missing", hint)
      assert(out.isLeft, s"expected Left for hint $hint")
    }
