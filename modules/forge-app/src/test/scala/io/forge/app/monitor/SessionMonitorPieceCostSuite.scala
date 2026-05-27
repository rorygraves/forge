package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.PieceId
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{BudgetScope, SessionPhase}

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F F5 — piece-scope budget enforcement. Same shape as the feature suite but the breach scope is the
  * caller-supplied [[PieceId]]. The monitor does **not** invoke `session.kill()` (§12 check 2).
  */
class SessionMonitorPieceCostSuite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal("100"),
    maxPieceCostUsd = Some(BigDecimal("2.50")),
    maxFeatureCostUsd = None
  )

  private val pieceId: PieceId = PieceId("p1")

  private def cost(usd: String): Cost =
    Cost(provider = "p", model = "m", inputTokens = 0L, outputTokens = 0L, usd = BigDecimal(usd))

  test("pieceTotal crossing the cap → BudgetBreached(Piece(p), totals, cap) + kill NOT called"):
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("3.00")))
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, Some(pieceId), session, events, limits, totals)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Piece(p), totals, cap) =>
          assertEquals(p, pieceId)
          assertEquals(totals.piece, BigDecimal("3.00"))
          assertEquals(cap, BigDecimal("2.50"))
        case other => fail(s"expected BudgetBreached(Piece(p1), _, _), got $other")
      assertEquals(kills, 0)
    }

  test("missing piece id with a piece-cap configured falls back to BudgetScope.Harness (defensive)"):
    // Phases that genuinely don't have a piece (Spec, DesignRevision) ought never to be paired with
    // `maxPieceCostUsd = Some(_)`. The fallback to Harness is a programmer-error safety net rather than a
    // first-class supported configuration; the scaladoc on SessionMonitor names this.
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("3.00")))
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Spec, None, session, events, limits, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Harness, _, _) => ()
        case other => fail(s"expected BudgetBreached(Harness, _, _) as defensive fallback, got $other")
    }
