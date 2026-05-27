package io.forge.app.monitor

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{SessionPhase, SettleOutcome}

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F F5 — settle outcomes when the stream completes cleanly. A trailing `AgentEvent.Result(success, _)` is the
  * canonical end-of-turn marker; `SessionMonitor.Settled` should mirror its `success` flag onto `SettleOutcome.Clean`
  * vs `SettleOutcome.AdapterError`.
  */
class SessionMonitorSettleSuite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 5.seconds,
    maxTurnCostUsd = BigDecimal(100),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = None
  )

  test("Result(success = true) → Settled(phase, Clean) and session.kill was NOT called"):
    val monitor = new RealSessionMonitor
    for
      session <- FakeStreamingSession.make
      totals <- Ref.of[IO, CostTotals](CostTotals.zero)
      events = Stream.emit[IO, AgentEvent](AgentEvent.Result(success = true, durationMs = 0))
      outcome <- monitor.monitor(SessionPhase.Spec, None, session, events, limits, totals)
      kills <- session.killCount.get
    yield
      assertEquals(outcome, MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean))
      assertEquals(kills, 0)

  test("Result(success = false) → Settled(phase, AdapterError(\"non-zero result\"))"):
    val monitor = new RealSessionMonitor
    for
      session <- FakeStreamingSession.make
      totals <- Ref.of[IO, CostTotals](CostTotals.zero)
      events = Stream.emit[IO, AgentEvent](AgentEvent.Result(success = false, durationMs = 0))
      outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
      kills <- session.killCount.get
    yield
      assertEquals(
        outcome,
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.AdapterError("non-zero result"))
      )
      assertEquals(kills, 0)

  test("non-Result events before Result are consumed without firing the outcome early"):
    val monitor = new RealSessionMonitor
    for
      session <- FakeStreamingSession.make
      totals <- Ref.of[IO, CostTotals](CostTotals.zero)
      events = Stream.emits[IO, AgentEvent](
        Vector(
          AgentEvent.Init("session-1"),
          AgentEvent.UserMessage("hello"),
          AgentEvent.AssistantText("hi", outputTokens = 1),
          AgentEvent.ToolUse("Read", "README.md"),
          AgentEvent.Result(success = true, durationMs = 42)
        )
      )
      outcome <- monitor.monitor(SessionPhase.DesignRevision, None, session, events, limits, totals)
    yield assertEquals(outcome, MonitorOutcome.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))

  test("the first Result wins; subsequent events on the stream do not override the outcome"):
    val monitor = new RealSessionMonitor
    for
      session <- FakeStreamingSession.make
      totals <- Ref.of[IO, CostTotals](CostTotals.zero)
      // Two trailing Results — second one would be AdapterError if it overrode the first.
      events = Stream.emits[IO, AgentEvent](
        Vector(
          AgentEvent.Result(success = true, durationMs = 1),
          AgentEvent.Result(success = false, durationMs = 2)
        )
      )
      outcome <- monitor.monitor(SessionPhase.Fixup, None, session, events, limits, totals)
    yield assertEquals(outcome, MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean))
