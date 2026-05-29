package io.forge.app.orchestrator

import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{BudgetScope, FsmEvent, FsmState, SessionPhase, SettleOutcome}

/** Task 1.4.10 J2 (sub-phase III) — pins every post-settle synthesis recipe row: which §11 side-effect sequence the
  * loop runs (the pure analog of "assert the side-effect calls"), and the `FsmEvent` synthesized back into
  * `Fsm.transition`. The effectful execution of the side effects lands in Task 1.4.10-d; this suite locks the pure
  * decision table the loop consults.
  */
class OrchestratorPostSettleSynthesisSuite extends munit.FunSuite:

  private val P1 = PieceId("p1")
  private val Pr = PrNumber(4291)
  private val DesignPr = PrNumber(4290)

  import SettleEffect.*

  // ---------------------------------------------------------------------------
  // The five §11 driver-Clean recipe rows.
  // ---------------------------------------------------------------------------

  test("InteractiveSpec + Settled(Spec, Clean) → coherence post-check, synth Settled(Spec, Clean)"):
    assertEquals(
      PostSettleSynthesis
        .plan(FsmState.InteractiveSpec, MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean)),
      SettlePlan(CoherencePostCheck, SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean)))
    )

  test("DesignReviewing + Settled(DesignRevision, Clean) → update design assets, synth Settled(DesignRevision, Clean)"):
    assertEquals(
      PostSettleSynthesis.plan(
        FsmState.DesignReviewing(2),
        MonitorOutcome.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean)
      ),
      SettlePlan(
        UpdateDesignAssets,
        SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
      )
    )

  test(
    "DesignPrFeedback + Settled(DesignRevision, Clean) → repush design feedback, synth Settled(DesignRevision, Clean)"
  ):
    assertEquals(
      PostSettleSynthesis.plan(
        FsmState.DesignPrFeedback(DesignPr, 1),
        MonitorOutcome.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean)
      ),
      SettlePlan(
        RepushDesignFeedback,
        SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
      )
    )

  test("PieceImplementing + Settled(Implement, Clean) → classify/commit/openPr, synth PrOpenedAfterCreate(p)"):
    assertEquals(
      PostSettleSynthesis.plan(
        FsmState.PieceImplementing(P1),
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
      ),
      SettlePlan(ClassifyCommitOpenPr, SettleSynthesis.PrOpenedAfterCreate(P1))
    )

  test("PieceFixingUp + Settled(Fixup, Clean) → classify/commit/push, synth Settled(Fixup, Clean)"):
    assertEquals(
      PostSettleSynthesis.plan(
        FsmState.PieceFixingUp(P1, Pr, 1),
        MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean)
      ),
      SettlePlan(ClassifyCommitPush, SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean)))
    )

  // ---------------------------------------------------------------------------
  // Pass-through rows: no side effects, the converted event drives the FSM directly.
  // ---------------------------------------------------------------------------

  test("Settled(_, AdapterError) is a pass-through carrying the verbatim outcome"):
    val plan = PostSettleSynthesis.plan(
      FsmState.InteractiveSpec,
      MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.AdapterError("boom"))
    )
    assertEquals(
      plan,
      SettlePlan(None, SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.AdapterError("boom"))))
    )

  test("Settled(_, HitQuestionLimit) is a pass-through carrying the verbatim outcome"):
    val plan = PostSettleSynthesis.plan(
      FsmState.InteractiveSpec,
      MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.HitQuestionLimit)
    )
    assertEquals(
      plan,
      SettlePlan(None, SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.HitQuestionLimit)))
    )

  test("SettleTimeout maps verbatim (reason preserved) with no side effects"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.SettleTimeout(SessionPhase.Implement, "wall clock cap")
    )
    assertEquals(plan.effect, None)
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(FsmEvent.SettleTimeout(SessionPhase.Implement, "wall clock cap"))
    )

  test("SettleTimeout surfaces a non-empty killError into the reason"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.SettleTimeout(SessionPhase.Implement, "wall clock cap", killError = Some("SIGKILL failed"))
    )
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(
        FsmEvent.SettleTimeout(SessionPhase.Implement, "wall clock cap (kill failed: SIGKILL failed)")
      )
    )

  test("TurnBudgetBreached flattens turn/cap into the FSM message"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.TurnBudgetBreached(SessionPhase.Implement, BigDecimal("1.50"), BigDecimal("1.00"))
    )
    assertEquals(plan.effect, None)
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(FsmEvent.TurnBudgetBreached(SessionPhase.Implement, "turn cost $1.50 exceeded cap $1.00"))
    )

  test("TurnBudgetBreached surfaces a non-empty killError into the message"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.TurnBudgetBreached(
        SessionPhase.Implement,
        BigDecimal("1.50"),
        BigDecimal("1.00"),
        killError = Some("no such pid")
      )
    )
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(
        FsmEvent.TurnBudgetBreached(
          SessionPhase.Implement,
          "turn cost $1.50 exceeded cap $1.00 (kill failed: no such pid)"
        )
      )
    )

  test("BudgetBreached(Feature) flattens the feature total into the message"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.BudgetBreached(
        BudgetScope.Feature,
        CostTotals(BigDecimal("9.00"), BigDecimal("3.00"), BigDecimal("0.50")),
        BigDecimal("8.00")
      )
    )
    assertEquals(plan.effect, None)
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(FsmEvent.BudgetBreached(BudgetScope.Feature, "feature cost $9.00 exceeded cap $8.00"))
    )

  test("BudgetBreached(Piece) names the piece and flattens the piece total"):
    val plan = PostSettleSynthesis.plan(
      FsmState.PieceImplementing(P1),
      MonitorOutcome.BudgetBreached(
        BudgetScope.Piece(P1),
        CostTotals(BigDecimal("9.00"), BigDecimal("5.00"), BigDecimal("0.50")),
        BigDecimal("4.00")
      )
    )
    assertEquals(
      plan.synthesis,
      SettleSynthesis.Event(FsmEvent.BudgetBreached(BudgetScope.Piece(P1), "piece p1 cost $5.00 exceeded cap $4.00"))
    )

  // ---------------------------------------------------------------------------
  // Lifecycle guard: a clean driver settle for a phase that isn't the state's active driver is loud.
  // ---------------------------------------------------------------------------

  private val illegalRows: Vector[(String, FsmState, MonitorOutcome)] = Vector(
    (
      "Implement clean in DesignReviewing",
      FsmState.DesignReviewing(1),
      MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
    ),
    (
      "Spec clean in PieceImplementing",
      FsmState.PieceImplementing(P1),
      MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean)
    ),
    (
      "Fixup clean in PieceImplementing",
      FsmState.PieceImplementing(P1),
      MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean)
    ),
    (
      "DesignRevision clean in PieceFixingUp",
      FsmState.PieceFixingUp(P1, Pr, 1),
      MonitorOutcome.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean)
    ),
    (
      "Implement clean in a terminal state",
      FsmState.FeatureDone,
      MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
    )
  )

  illegalRows.foreach { case (label, state, outcome) =>
    test(s"clean settle for the wrong driver raises — $label"):
      intercept[IllegalStateException](PostSettleSynthesis.plan(state, outcome))
  }
