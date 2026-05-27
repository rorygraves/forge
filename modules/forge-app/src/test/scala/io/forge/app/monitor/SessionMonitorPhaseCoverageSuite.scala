package io.forge.app.monitor

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.AgentEvent
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{SessionPhase, SettleOutcome}

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F F5 — table-driven assertion that every driver phase routes through the same logic, and that the three
  * non-driver phases (`DesignReview`, `CodeReview`, `Refine`) are refused with an `IllegalArgumentException` at
  * `monitor` entry. Per design-rationale S3-5 / S2-8, reviewer + refine wall-clock caps live in the Slice-4
  * reviewer-asset adapter wrappers; SessionMonitor never owns them.
  */
class SessionMonitorPhaseCoverageSuite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal(100),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = None
  )

  private def cleanRun(phase: SessionPhase): IO[MonitorOutcome] =
    for
      session <- FakeStreamingSession.make
      totals <- Ref.of[IO, CostTotals](CostTotals.zero)
      monitor = new RealSessionMonitor
      events = Stream.emit[IO, AgentEvent](AgentEvent.Result(success = true, durationMs = 0))
      outcome <- monitor.monitor(phase, None, session, events, limits, totals)
    yield outcome

  Vector(
    SessionPhase.Spec,
    SessionPhase.DesignRevision,
    SessionPhase.Implement,
    SessionPhase.Fixup
  ).foreach { phase =>
    test(s"driver phase $phase routes through monitor and yields Settled(Clean)"):
      cleanRun(phase).map(o => assertEquals(o, MonitorOutcome.Settled(phase, SettleOutcome.Clean)))
  }

  Vector(SessionPhase.DesignReview, SessionPhase.CodeReview, SessionPhase.Refine).foreach { phase =>
    test(s"non-driver phase $phase is refused with IllegalArgumentException"):
      interceptIO[IllegalArgumentException](cleanRun(phase))
  }
