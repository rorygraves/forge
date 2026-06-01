package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.review.DesignReviewVerdict

/** §11.2 — Design review. Verdict approve / request_changes (with max-round exhaustion) / blocking questions. */
class Fsm_11_2_DesignReviewSuite extends munit.FunSuite:

  test(
    "DesignReviewing + Approve verdict → state unchanged (orchestrator opens PR; transition fires on DesignPrSnapshotUpdated)"
  ):
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = Some("sess-1"))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.DesignReviewReceived(round = 1, verdict = DesignReviewVerdict.Approve)
    )
    assertEquals(out.state, FsmState.DesignReviewing(round = 1))
    assertEquals(out.designSessionId, Some("sess-1"))
    assertEquals(drafts, Vector.empty)

  test("DesignReviewing + RequestChanges + session id present → DesignReviewing(round + 1)"):
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.DesignReviewReceived(round = 1, verdict = DesignReviewVerdict.RequestChanges(Vector("missing tests")))
    )
    assertEquals(out.state, FsmState.DesignReviewing(round = 2))

  test("DesignReviewing + RequestChanges + missing session id → NHI(ReopenDesign(currentDesignPr))"):
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = None, designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.DesignReviewReceived(round = 1, verdict = DesignReviewVerdict.RequestChanges(Vector("x")))
    )
    out.state match
      case FsmState.NeedsHumanIntervention(reason, ResumeHint.ReopenDesign(prOpt)) =>
        assert(reason.contains("missing design session id"))
        assertEquals(prOpt, Some(DesignPr))
      case other => fail(s"expected NHI(ReopenDesign(Some(DesignPr))), got $other")

  test(
    "DesignReviewing + RequestChanges at max round → NHI(\"design did not converge\", ReopenDesign(currentDesignPr))"
  ):
    val f = featureIn(FsmState.DesignReviewing(round = 3), designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.DesignReviewReceived(round = 3, verdict = DesignReviewVerdict.RequestChanges(Vector("x")))
    )
    out.state match
      case FsmState.NeedsHumanIntervention(reason, ResumeHint.ReopenDesign(_)) =>
        assert(reason.contains("did not converge"))
      case other => fail(s"expected NHI(did not converge), got $other")

  test("DesignReviewing + BlockingQuestions → DesignNeedsHumanInput(round, qs); designSessionId persists"):
    val qs = Vector(Question("Is X correct?", Vector("yes", "no"), allowFreeText = false, QuestionSeverity.Blocking))
    val f = featureIn(FsmState.DesignReviewing(round = 2), designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.DesignReviewReceived(round = 2, verdict = DesignReviewVerdict.BlockingQuestions(qs))
    )
    assertEquals(out.state, FsmState.DesignNeedsHumanInput(round = 2, questions = qs))
    assertEquals(out.designSessionId, Some("sess-1"))

  test("DesignNeedsHumanInput + DesignReviewClarified → DesignReviewing(round + 1)"):
    val qs = Vector(Question("?", Vector.empty, allowFreeText = true, QuestionSeverity.Blocking))
    val f = featureIn(FsmState.DesignNeedsHumanInput(round = 2, questions = qs), designSessionId = Some("sess-1"))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignReviewClarified(round = 2))
    assertEquals(out.state, FsmState.DesignReviewing(round = 3))

  test("DesignReviewing + DesignPrSnapshotUpdated(Open) → DesignAwaitingMerge, manifest.designPr persisted"):
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = Some("sess-1"))
    val snap = emptySnapshot(DesignPr, io.forge.core.pr.PrState.Open)
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(snap))
    assertEquals(out.state, FsmState.DesignAwaitingMerge(DesignPr))
    assertEquals(out.manifest.designPr, Some(DesignPr))
    assertEquals(out.designSessionId, Some("sess-1"), "designSessionId retained per §6.1")

  test("DesignReviewing + SessionResumed → designSessionId updated, state unchanged"):
    val f = featureIn(FsmState.DesignReviewing(round = 2), designSessionId = Some("old"))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionResumed("claude", "driver", Some("old"), "new", piece = None)
    )
    assertEquals(out.state, FsmState.DesignReviewing(round = 2))
    assertEquals(out.designSessionId, Some("new"))
    // roadmap §3.5: a design resume now emits the §19 `<actor>.resume` durability entry (oldSid non-empty here).
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "claude.resume")
    assertEquals(drafts.head.piece, None)
    assertEquals(drafts.head.payload("oldSessionId").str, "old")
    assertEquals(drafts.head.payload("newSessionId").str, "new")

  test("DesignReviewing + SessionSpawned(None) → designSessionId projected, state unchanged, <actor>.spawn durability"):
    // roadmap §3.5: `forge spec` ReopenDesign re-entry — after Resume(ReopenDesign) lands DesignReviewing(1), the fresh
    // interactive session projects its new designSessionId via SessionSpawned (the correct primitive for a fresh
    // session: replay needs no prior spawn, unlike <actor>.resume). Repopulates a previously-missing id durably.
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = None)
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("claude", "driver", "fresh-sess", piece = None)
    )
    assertEquals(out.state, FsmState.DesignReviewing(round = 1))
    assertEquals(out.designSessionId, Some("fresh-sess"))
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "claude.spawn")
    assertEquals(drafts.head.piece, None)
    assertEquals(drafts.head.payload("sessionId").str, "fresh-sess")

  test("DesignReviewing + SessionResumed with no oldSessionId → projects but emits no durability draft"):
    // roadmap §3.5 guard: the orchestrator passes oldSessionId = None only when it would resume without a known session
    // (cannot happen in practice — RealSideEffects.resumeDesign refuses an empty designSessionId). The FSM must NOT
    // emit a `<actor>.resume` then, since replay would reject an oldSessionId no prior spawn introduced
    // (ResumeWithoutSpawn) and poison a cold rebuild. The in-memory projection still happens.
    val f = featureIn(FsmState.DesignReviewing(round = 2), designSessionId = Some("old"))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionResumed("claude", "driver", None, "new", piece = None)
    )
    assertEquals(out.designSessionId, Some("new"))
    assertEquals(drafts, Vector.empty)
