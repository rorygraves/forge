package io.forge.app.orchestrator

import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, ResumeHint, SessionPhase}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.state.RebuildState.InFlightSession

/** Task 1.4.10 J2 restart recovery — pins the per-phase synthetic `HarnessError` routing through `Fsm.transition`: each
  * in-flight driver session lands the feature in `NeedsHumanIntervention` with the hint the doc's recovery table names,
  * confirming the orchestrator's mapping and `Fsm.hintFromState` agree (so the orchestrator can pass only the typed
  * reason and trust the FSM's default-hint table).
  */
class OrchestratorRestartSuite extends munit.FunSuite:

  private val FeatureA = FeatureId("stripe-webhook")
  private val P1 = PieceId("p1")
  private val Pr = PrNumber(4291)
  private val DesignPr = PrNumber(4290)

  private def manifest(designPr: Option[PrNumber] = None): Manifest =
    Manifest(
      schemaVersion = 1,
      featureId = FeatureA,
      title = "Stripe webhook",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = designPr,
      pieces = Vector(
        Piece(
          id = P1,
          order = 1,
          title = "Piece p1",
          summary = "summary p1",
          specPath = s".forge/specs/${FeatureA.value}/pieces/p1.md",
          acceptanceHash = "sha256:" + ("0" * 64),
          status = PieceStatus.InProgress,
          baseSha = Some(Sha("a" * 40)),
          prNumber = None,
          mergeCommit = None,
          mergedAt = None,
          attempts = 0
        )
      )
    )

  private def feature(state: FsmState, designPr: Option[PrNumber] = None): Feature =
    Feature.initial(FeatureA, manifest(designPr)).copy(state = state)

  private def designSession(): InFlightSession =
    InFlightSession(SessionPhase.DesignRevision, "sess-design", None)

  // ---------------------------------------------------------------------------
  // Each row: in-flight session + FSM state → (expected reason, expected hint the FSM lands on).
  // ---------------------------------------------------------------------------

  private val rows: Vector[(String, InFlightSession, FsmState, String, ResumeHint)] = Vector(
    (
      "Spec / InteractiveSpec",
      InFlightSession(SessionPhase.Spec, "sess-spec", None),
      FsmState.InteractiveSpec,
      "spec session interrupted by process restart",
      ResumeHint.AbortOrAbandon
    ),
    (
      "DesignRevision / DesignReviewing",
      designSession(),
      FsmState.DesignReviewing(2),
      "design revision interrupted by process restart",
      ResumeHint.ReopenDesign(None)
    ),
    (
      "DesignRevision / DesignPrFeedback",
      designSession(),
      FsmState.DesignPrFeedback(DesignPr, 1),
      "design PR feedback session interrupted by process restart",
      ResumeHint.ReopenDesign(Some(DesignPr))
    ),
    (
      "Implement / PieceImplementing",
      InFlightSession(SessionPhase.Implement, "sess-impl", Some(P1)),
      FsmState.PieceImplementing(P1),
      "implementation interrupted by process restart; worktree may have uncommitted changes",
      ResumeHint.ResolveLocalImplementationChanges(P1, BranchName(s"forge/${FeatureA.value}/p1"))
    ),
    (
      "Fixup / PieceFixingUp",
      InFlightSession(SessionPhase.Fixup, "sess-fixup", Some(P1)),
      FsmState.PieceFixingUp(P1, Pr, 1),
      "fix-up interrupted by process restart; worktree may have uncommitted changes",
      ResumeHint.RunAnotherFixup(P1, Pr)
    )
  )

  rows.foreach { case (label, session, state, expectedReason, expectedHint) =>
    test(s"synthetic HarnessError reason — $label"):
      assertEquals(RestartRecovery.syntheticHarnessError(session, state).reason, expectedReason)

    test(s"recover routes through Fsm.transition to NHI($expectedHint) — $label"):
      val (recovered, _) = RestartRecovery.recover(feature(state), Vector(session))
      assertEquals(recovered.state, FsmState.NeedsHumanIntervention(expectedReason, expectedHint))
  }

  // ---------------------------------------------------------------------------
  // recover folds correctly over the edges.
  // ---------------------------------------------------------------------------

  test("recover with no in-flight sessions is the identity"):
    val f = feature(FsmState.PieceImplementing(P1))
    val (recovered, drafts) = RestartRecovery.recover(f, Vector.empty)
    assertEquals(recovered, f)
    assertEquals(drafts, Vector.empty)

  test("recover emits the FSM transition drafts for the synthesized HarnessError"):
    val (_, drafts) = RestartRecovery.recover(
      feature(FsmState.InteractiveSpec),
      Vector(InFlightSession(SessionPhase.Spec, "sess-spec", None))
    )
    assert(drafts.nonEmpty, "expected at least the NHI fsm.transition draft")

  // ---------------------------------------------------------------------------
  // Defensive: an inconsistent (phase, state) pair still yields a HarnessError that recovery routes to NHI,
  // rather than crashing the recovery path.
  // ---------------------------------------------------------------------------

  test("unexpected (phase, state) pair still produces a HarnessError routed to NHI"):
    val session = InFlightSession(SessionPhase.Implement, "sess-impl", Some(P1))
    val err = RestartRecovery.syntheticHarnessError(session, FsmState.InteractiveSpec)
    assert(err.reason.contains("unexpected state"), s"reason was: ${err.reason}")
    val (recovered, _) = RestartRecovery.recover(feature(FsmState.InteractiveSpec), Vector(session))
    assert(
      recovered.state.isInstanceOf[FsmState.NeedsHumanIntervention],
      s"expected NHI, got ${recovered.state}"
    )
