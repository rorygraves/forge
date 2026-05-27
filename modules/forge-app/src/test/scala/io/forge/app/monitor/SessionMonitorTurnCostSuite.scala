package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.SessionPhase

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F F5 — per-turn budget enforcement. §12 check 3 says the monitor invokes `session.kill()` as soon as
  * `CostTotals.turn` crosses `maxTurnCostUsd`. The outcome is `TurnBudgetBreached(phase, turnUsd, capUsd)` with the
  * post-update `turnUsd` so the FSM-bound message can report the breach precisely.
  */
class SessionMonitorTurnCostSuite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal("1.00"),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = None
  )

  private def cost(usd: String): Cost =
    Cost(provider = "p", model = "m", inputTokens = 0L, outputTokens = 0L, usd = BigDecimal(usd))

  test("single CostUpdate over the cap → TurnBudgetBreached + kill called once"):
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("1.50")))
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
        kills <- session.killCount.get
        finalTotals <- totals.get
      yield (outcome, kills, finalTotals)
    TestControl.executeEmbed(program).map { case (outcome, kills, finalTotals) =>
      assertEquals(
        outcome,
        MonitorOutcome.TurnBudgetBreached(SessionPhase.Implement, BigDecimal("1.50"), BigDecimal("1.00"))
      )
      assertEquals(kills, 1)
      // totals.turn was advanced before the breach was detected — the orchestrator (Slice 4) handles reset.
      assertEquals(finalTotals.turn, BigDecimal("1.50"))
    }

  test("multiple CostUpdates accumulate; breach fires on the increment that crosses the cap"):
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("0.40")),
        AgentEvent.CostUpdate(cost("0.40")),
        // Cumulative turn = 0.40 + 0.40 + 0.30 = 1.10 > 1.00 → breach here.
        AgentEvent.CostUpdate(cost("0.30")),
        // Result should not fire — Deferred already complete.
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Fixup, None, session, events, limits, totals)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program).map { case (outcome, kills) =>
      assertEquals(
        outcome,
        MonitorOutcome.TurnBudgetBreached(SessionPhase.Fixup, BigDecimal("1.10"), BigDecimal("1.00"))
      )
      assertEquals(kills, 1, "kill should be called exactly once on the breaching event")
    }

  test("CostUpdate that lands exactly at the cap does NOT breach (strict greater-than)"):
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("1.00")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Spec, None, session, events, limits, totals)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.Settled(SessionPhase.Spec, _) => ()
        case other => fail(s"expected Settled at exact-cap boundary, got $other")
      assertEquals(kills, 0)
    }
