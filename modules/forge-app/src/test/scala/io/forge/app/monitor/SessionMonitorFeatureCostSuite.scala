package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{BudgetScope, SessionPhase}

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F F5 — feature-scope budget enforcement. §12 check 2: the monitor emits [[MonitorOutcome.BudgetBreached]] but
  * does **not** invoke `session.kill()` — the current turn is allowed to complete and the Slice-4 orchestrator refuses
  * the next spawn.
  */
class SessionMonitorFeatureCostSuite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal("100"),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = Some(BigDecimal("5.00"))
  )

  private def cost(usd: String): Cost =
    Cost(provider = "p", model = "m", inputTokens = 0L, outputTokens = 0L, usd = BigDecimal(usd))

  test("featureTotal crossing the cap → BudgetBreached(Feature, totals, cap) + kill NOT called"):
    // §12 check 2: the breach is recorded on CostUpdate but only published on Result, so the canonical fixture
    // pairs the breaching CostUpdate with the turn-completing Result.
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("6.00")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Feature, totals, cap) =>
          assertEquals(totals.feature, BigDecimal("6.00"))
          assertEquals(cap, BigDecimal("5.00"))
        case other => fail(s"expected BudgetBreached(Feature, _, _), got $other")
      assertEquals(kills, 0, "feature-scope breach must NOT kill the session (§12 check 2)")
    }

  test("featureTotal accumulating across CostUpdates only breaches once the cap is crossed"):
    // First CostUpdate doesn't breach (feature = 2); second pushes feature to 6 → records pending breach;
    // Result flushes the pending breach into the published outcome.
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("2.00")),
        AgentEvent.CostUpdate(cost("4.00")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.DesignRevision, None, session, events, limits, totals)
        kills <- session.killCount.get
        finalTotals <- totals.get
      yield (outcome, kills, finalTotals)
    TestControl.executeEmbed(program).map { case (outcome, kills, finalTotals) =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Feature, _, cap) =>
          assertEquals(cap, BigDecimal("5.00"))
        case other => fail(s"expected BudgetBreached(Feature, _, _), got $other")
      assertEquals(kills, 0)
      assertEquals(finalTotals.feature, BigDecimal("6.00"))
    }

  test("when no feature cap is set, feature totals never breach"):
    // maxTurnCostUsd remains 100 from the suite limits; keep the per-update cost well below it so the per-turn
    // check doesn't fire either.
    val capless = limits.copy(maxFeatureCostUsd = None)
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("50.00")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, capless, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.Settled(_, _) => ()
        case other => fail(s"expected Settled with no feature cap configured, got $other")
    }
