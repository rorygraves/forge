package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.SessionPhase

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** §12 per-turn cost cap — **post-hoc advisory, non-killing** (Slice 2.2 A1 / S4-3). Cost is reported only at turn-end,
  * so a per-turn breach is observed only *after* the turn (and its spend) is complete; killing then reclaims nothing
  * and — for the single-turn headless drivers — would only strand an already-settled turn (the szork $9.56-vs-$2
  * finding). So a turn whose cost exceeds `maxTurnCostUsd` is **not** killed and settles on its own terms; the running
  * totals still advance (so the overrun is visible in the `cost.update` / `session.complete` audit). The preventive
  * caps are the cumulative feature/piece budgets (see [[SessionMonitorFeatureCostSuite]] /
  * [[SessionMonitorPieceCostSuite]]); the mid-turn interrupt is the wall-clock settle cap (see
  * [[SessionMonitorTimeoutSuite]]).
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

  test("a single turn whose cost exceeds the cap is NOT killed and settles clean"):
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("1.50")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals).map(_.outcome)
        kills <- session.killCount.get
        finalTotals <- totals.get
      yield (outcome, kills, finalTotals)
    TestControl.executeEmbed(program).map { case (outcome, kills, finalTotals) =>
      outcome match
        case MonitorOutcome.Settled(SessionPhase.Implement, _) => ()
        case other => fail(s"expected a clean Settled (per-turn cap no longer kills), got $other")
      assertEquals(kills, 0, "the per-turn cost cap is post-hoc advisory — it must not kill")
      // The spend is still recorded so the overrun stays visible in the audit / `forge stats`.
      assertEquals(finalTotals.turn, BigDecimal("1.50"))
    }

  test("cumulative over-cap turn still settles clean with no kill"):
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("0.40")),
        AgentEvent.CostUpdate(cost("0.40")),
        // Cumulative turn = 1.10 > 1.00 — under the old semantics this killed; now it is advisory only.
        AgentEvent.CostUpdate(cost("0.30")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Fixup, None, session, events, limits, totals).map(_.outcome)
        kills <- session.killCount.get
        finalTotals <- totals.get
      yield (outcome, kills, finalTotals)
    TestControl.executeEmbed(program).map { case (outcome, kills, finalTotals) =>
      outcome match
        case MonitorOutcome.Settled(SessionPhase.Fixup, _) => ()
        case other => fail(s"expected a clean Settled, got $other")
      assertEquals(kills, 0)
      assertEquals(finalTotals.turn, BigDecimal("1.10"))
    }

  test("CostUpdate at exactly the cap settles clean (parity with under-cap)"):
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
        outcome <- monitor.monitor(SessionPhase.Spec, None, session, events, limits, totals).map(_.outcome)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.Settled(SessionPhase.Spec, _) => ()
        case other => fail(s"expected Settled at exact-cap boundary, got $other")
      assertEquals(kills, 0)
    }
