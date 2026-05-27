package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.SessionPhase

import scala.concurrent.duration.DurationInt
import munit.CatsEffectSuite

/** PR-F review round 2 regression suite — kill-failure resilience.
  *
  * [[io.forge.agents.StreamingSession.kill]] is not documented as infallible; the real `StreamingDriver` propagates
  * `Subprocess.kill` failures. Before this round, `finishWithKill` ran the kill bare under `IO.uncancelable`: a raising
  * kill would kill the background fiber, leave `winnerClaimed = true`, and the foreground `result.get` would hang
  * forever because every loser fiber became a no-op.
  *
  * The round-2 fix runs the kill under `.attempt`, captures the failure on the outcome's `killError: Option[String]`
  * field, and always completes the Deferred. These tests pin:
  *
  *   - SettleTimeout when the timer's `session.kill()` raises → outcome published with `killError = Some(msg)`;
  *     `result.get` returns instead of hanging.
  *   - TurnBudgetBreached when the breach-path `session.kill()` raises → same shape, on the per-turn cap path.
  */
class SessionMonitorReviewRound2Suite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal("1.00"),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = None
  )

  private def cost(usd: String): Cost =
    Cost(provider = "p", model = "m", inputTokens = 0L, outputTokens = 0L, usd = BigDecimal(usd))

  /** Streaming-session fake whose `kill()` raises a configurable exception. Used to confirm that a raising kill is
    * captured onto the published outcome instead of orphaning the monitor.
    */
  private final class FailingKillSession(killError: Throwable) extends StreamingSession:
    override val sessionId: String = "failing-kill"
    override def events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] = IO.raiseError(killError)
    override def send(input: String): IO[Unit] = IO.unit
    override def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

  test("settle timeout path: kill() raises → SettleTimeout published with killError = Some(msg), no hang"):
    val killException = new RuntimeException("SIGKILL refused")
    val program =
      for
        session <- IO.pure(new FailingKillSession(killException))
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Spec, None, session, Stream.never[IO], limits, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.SettleTimeout(SessionPhase.Spec, reason, Some(msg)) =>
          assert(reason.contains(limits.settleTimeout.toString))
          assertEquals(msg, "SIGKILL refused")
        case other => fail(s"expected SettleTimeout(Spec, _, Some(_)), got $other")
    }

  test("turn-budget path: kill() raises → TurnBudgetBreached published with killError = Some(msg), no hang"):
    val killException = new RuntimeException("subprocess already reaped")
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("1.50")))
    val program =
      for
        session <- IO.pure(new FailingKillSession(killException))
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.TurnBudgetBreached(
              SessionPhase.Implement,
              turnUsd,
              capUsd,
              Some(msg)
            ) =>
          assertEquals(turnUsd, BigDecimal("1.50"))
          assertEquals(capUsd, BigDecimal("1.00"))
          assertEquals(msg, "subprocess already reaped")
        case other => fail(s"expected TurnBudgetBreached(Implement, _, _, Some(_)), got $other")
    }

  test("kill() raises a Throwable with null message → killError falls back to Throwable.toString"):
    // Defensive: `t.getMessage` can return null (e.g. some custom exceptions). The monitor must not crash on the
    // captured side and must still emit *some* diagnostic string on the outcome.
    val nullMsgError = new RuntimeException(null: String)
    val program =
      for
        session <- IO.pure(new FailingKillSession(nullMsgError))
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Spec, None, session, Stream.never[IO], limits, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.SettleTimeout(_, _, Some(msg)) =>
          assert(msg.nonEmpty, s"killError fallback should be non-empty when getMessage is null; got '$msg'")
        case other => fail(s"expected SettleTimeout with Some(killError), got $other")
    }
