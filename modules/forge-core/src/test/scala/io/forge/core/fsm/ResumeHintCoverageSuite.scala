package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}

/** §17 slice-2 invariant 3 — every `NeedsHumanIntervention` reachable from `Fsm.transition` carries one of the six
  * spec-mandated `ResumeHint` variants (§6). Table-driven by lifecycle source (§11.0 step 5 + §11.x).
  *
  * The table is the contract: each row names a §11 rule, an input `(state, event)`, and the expected `ResumeHint` shape
  * on the resulting NHI. If a future PR-C tweak rewires one of these, this suite fires.
  */
class ResumeHintCoverageSuite extends munit.FunSuite:

  private def assertNhi(
      label: String,
      f: Feature,
      e: FsmEvent
  )(checkHint: ResumeHint => Boolean): Unit =
    val (out, _) = Fsm.transition(f, e)
    out.state match
      case FsmState.NeedsHumanIntervention(_, hint) =>
        assert(checkHint(hint), s"[$label] unexpected hint $hint")
      case other => fail(s"[$label] expected NHI, got $other")

  test("§11.1 spec settle timeout → NHI(AbortOrAbandon)"):
    assertNhi(
      "spec settle timeout",
      featureIn(FsmState.InteractiveSpec, designSessionId = Some("s")),
      FsmEvent.SettleTimeout(SessionPhase.Spec, "x")
    )(_ == ResumeHint.AbortOrAbandon)

  test("§11.2 missing design session id on RequestChanges → NHI(ReopenDesign(currentDesignPr))"):
    assertNhi(
      "missing design sid",
      featureIn(FsmState.DesignReviewing(1), designSessionId = None, designPr = Some(DesignPr)),
      FsmEvent.DesignReviewReceived(1, DesignReviewVerdict.RequestChanges(Vector("x")))
    ):
      case ResumeHint.ReopenDesign(Some(pr)) => pr == DesignPr
      case _ => false

  test("§11.2 design did not converge (max rounds) → NHI(ReopenDesign(currentDesignPr))"):
    assertNhi(
      "max rounds",
      featureIn(FsmState.DesignReviewing(3), designSessionId = Some("s")),
      FsmEvent.DesignReviewReceived(3, DesignReviewVerdict.RequestChanges(Vector("x")))
    ):
      case ResumeHint.ReopenDesign(_) => true
      case _ => false

  test("§B3(a)/S2-8 design review settle timeout → NHI(ReopenDesign(currentDesignPr))"):
    assertNhi(
      "design review settle timeout",
      featureIn(FsmState.DesignReviewing(1), designSessionId = Some("s"), designPr = Some(DesignPr)),
      FsmEvent.SettleTimeout(SessionPhase.DesignReview, "x")
    ):
      case ResumeHint.ReopenDesign(Some(pr)) => pr == DesignPr
      case _ => false

  test("§11.3 design PR closed without merge → NHI(ReopenDesign(Some(prNumber)))"):
    assertNhi(
      "design pr closed",
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("s"), designPr = Some(DesignPr)),
      FsmEvent.DesignPrSnapshotUpdated(closedSnapshot(DesignPr))
    ):
      case ResumeHint.ReopenDesign(Some(pr)) => pr == DesignPr
      case _ => false

  test("§11.4 implement settle timeout → NHI(ResolveLocalImplementationChanges)"):
    assertNhi(
      "implement settle timeout",
      featureIn(
        FsmState.PieceImplementing(P1),
        pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
        currentPieceSessionId = Some("s")
      ),
      FsmEvent.SettleTimeout(SessionPhase.Implement, "x")
    ):
      case ResumeHint.ResolveLocalImplementationChanges(p, _) => p == P1
      case _ => false

  test("§11.5 CI exhausted → NHI(RunAnotherFixup)"):
    assertNhi(
      "ci exhausted",
      featureIn(
        FsmState.PieceAwaitingCi(P1, P1Pr),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2))
      ),
      FsmEvent.PrSnapshotUpdated(P1, failedCi(P1Pr))
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("§11.5 PR closed without merge → NHI(RunAnotherFixup)"):
    assertNhi(
      "piece pr closed",
      featureIn(
        FsmState.PieceAwaitingMerge(P1, P1Pr),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
      ),
      FsmEvent.PrSnapshotUpdated(P1, closedSnapshot(P1Pr))
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("§B3(a)/S2-8 code review settle timeout → NHI(RunAnotherFixup)"):
    assertNhi(
      "code review settle timeout",
      featureIn(
        FsmState.PieceAwaitingReview(P1, P1Pr),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
      ),
      FsmEvent.SettleTimeout(SessionPhase.CodeReview, "x")
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("§B3(a)/S3-5 refine settle timeout → NHI(RunAnotherFixup)"):
    assertNhi(
      "refine settle timeout",
      featureIn(
        FsmState.Refining(P1, P1Pr, ObservedAt),
        pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)),
        currentPieceSessionId = Some("s")
      ),
      FsmEvent.SettleTimeout(SessionPhase.Refine, "x")
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("§11.6 fix-up settle timeout → NHI(RunAnotherFixup)"):
    assertNhi(
      "fix-up settle timeout",
      featureIn(
        FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2)),
        currentPieceSessionId = Some("s")
      ),
      FsmEvent.SettleTimeout(SessionPhase.Fixup, "x")
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("§11.7 refinery ReopenDesign verdict → NHI(ReopenDesign(None))"):
    assertNhi(
      "refinery reopen",
      featureIn(
        FsmState.Refining(P1, P1Pr, ObservedAt),
        pieces = Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2))
      ),
      FsmEvent.RefineOutcome(RefineVerdict.ReopenDesign("design drift"))
    ):
      case ResumeHint.ReopenDesign(None) => true
      case _ => false

  test("§11.5 Merged field mismatch → NHI(AbortOrAbandon)"):
    val pre = pieceMerged(P1, 1, P2Pr /* wrong */ )
    assertNhi(
      "merge mismatch",
      featureIn(FsmState.PieceAwaitingMerge(P1, P1Pr), pieces = Vector(pre, piecePending(P2, 2))),
      FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt)
    )(_ == ResumeHint.AbortOrAbandon)

  test("cross-cutting: HarnessError from PieceAwaitingReview → NHI(RunAnotherFixup)"):
    assertNhi(
      "harness error",
      featureIn(
        FsmState.PieceAwaitingReview(P1, P1Pr),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
      ),
      FsmEvent.HarnessError("internal")
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false

  test("cross-cutting: RequiredSessionIdMissing forwards reason+hint verbatim"):
    val hint = ResumeHint.ReopenDesign(Some(DesignPr))
    val f = featureIn(FsmState.DesignReviewing(1), designSessionId = None, designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.RequiredSessionIdMissing("missing", hint))
    out.state match
      case FsmState.NeedsHumanIntervention(reason, h) =>
        assertEquals(reason, "missing")
        assertEquals(h, hint)
      case other => fail(s"expected NHI, got $other")

  test("cross-cutting: UserCommandReceived(Abandon) → Abandoned(reason)"):
    val f = featureIn(FsmState.PieceImplementing(P1), pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(f, FsmEvent.UserCommandReceived(UserCommand.Abandon("operator stopped")))
    assertEquals(out.state, FsmState.Abandoned("operator stopped"))
    assert(drafts.exists(_.kind == "user.command"))
    assert(drafts.exists(_.kind == "fsm.transition"))

  test(
    "cross-cutting: UserCommandReceived(Resume(RunAnotherFixup)) from NHI → PieceCiFailed; orchestrator drives PieceFixingUp via SessionSpawned"
  ):
    val f = featureIn(
      FsmState.NeedsHumanIntervention("x", ResumeHint.RunAnotherFixup(P1, P1Pr)),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 2), piecePending(P2, 2)),
      currentPieceSessionId = Some("stale-fixup")
    )
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.UserCommandReceived(UserCommand.Resume(ResumeHint.RunAnotherFixup(P1, P1Pr)))
    )
    assertEquals(
      out.state,
      FsmState.PieceCiFailed(P1, P1Pr, attempt = 2),
      "Resume parks the FSM in a pre-fix-up state so the orchestrator's runFixup → SessionSpawned drives the fresh PieceFixingUp transition with the new session id (§11.6, §6.1)"
    )
    assertEquals(out.currentPieceSessionId, None, "stale fix-up session id cleared on Resume(RunAnotherFixup)")
    assertEquals(out.branchProtectionCacheEpoch, 1L, "Resume bumps branchProtectionCacheEpoch per §8.1")

  // Code-review-side verdict variant: RequestChanges exhausted is already covered indirectly via the suite above; this
  // double-checks the verdict-path hint is also RunAnotherFixup at exhaustion.
  test("§11.5 review RequestChanges exhausted → NHI(RunAnotherFixup)"):
    assertNhi(
      "review exhausted",
      featureIn(
        FsmState.PieceAwaitingReview(P1, P1Pr),
        pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2))
      ),
      FsmEvent.CodeReviewVerdict(P1, PrReviewVerdict.RequestChanges(Vector("x")))
    ):
      case ResumeHint.RunAnotherFixup(p, pr) => p == P1 && pr == P1Pr
      case _ => false
