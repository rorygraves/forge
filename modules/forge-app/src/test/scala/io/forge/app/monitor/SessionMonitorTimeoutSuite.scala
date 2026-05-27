package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.SessionPhase

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import munit.CatsEffectSuite

/** PR-F F5 — settle-timeout firing semantics. Uses [[cats.effect.testkit.TestControl]] so the timeout fires
  * deterministically once the simulated clock advances past `limits.settleTimeout`, instead of relying on wall-clock
  * sleeps inside the test runner.
  */
class SessionMonitorTimeoutSuite extends CatsEffectSuite:

  private val settleTimeout: FiniteDuration = 30.seconds

  private val limits = SessionLimits(
    settleTimeout = settleTimeout,
    maxTurnCostUsd = BigDecimal(100),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = None
  )

  private def runMonitor(
      phase: SessionPhase,
      events: Stream[IO, AgentEvent]
  ): IO[(MonitorOutcome, Int)] =
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(phase, None, session, events, limits, totals)
        kills <- session.killCount.get
      yield (outcome, kills)
    TestControl.executeEmbed(program)

  test("Stream.never → settle timeout fires, kill() invoked, outcome is SettleTimeout"):
    runMonitor(SessionPhase.Spec, Stream.never[IO]).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.SettleTimeout(phase, reason, killError) =>
          assertEquals(phase, SessionPhase.Spec)
          assert(reason.contains(settleTimeout.toString), s"reason should reference the timeout: $reason")
          assertEquals(killError, None, "no kill failure on the happy path")
        case other => fail(s"expected SettleTimeout, got $other")
      assertEquals(kills, 1)
    }

  test("Stream with no Result emits non-Result events then stalls → SettleTimeout still fires"):
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.Init("session-x"),
        AgentEvent.AssistantText("thinking...", outputTokens = 5)
      )
    ) ++ Stream.never[IO]
    runMonitor(SessionPhase.Implement, events).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.SettleTimeout(SessionPhase.Implement, _, None) => ()
        case other => fail(s"expected SettleTimeout(Implement, _, None), got $other")
      assertEquals(kills, 1)
    }

  test("a clean Result arriving before settleTimeout wins; timer does not fire kill"):
    // Stream emits Result well before the settle timeout — Settled should win the race.
    val events = Stream.sleep[IO](settleTimeout / 4) >>
      Stream.emit[IO, AgentEvent](AgentEvent.Result(success = true, durationMs = 0))
    runMonitor(SessionPhase.Fixup, events).map { case (outcome, kills) =>
      outcome match
        case MonitorOutcome.Settled(SessionPhase.Fixup, _) => ()
        case other => fail(s"expected Settled, got $other")
      assertEquals(kills, 0)
    }
