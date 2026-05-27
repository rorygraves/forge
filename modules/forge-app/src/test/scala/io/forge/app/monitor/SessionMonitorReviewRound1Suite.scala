package io.forge.app.monitor

import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{BudgetScope, SessionPhase}

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import munit.CatsEffectSuite

/** PR-F review round 1 regression suite.
  *
  *   - **P1 — kill is not cut short by foreground cancellation.** Foreground returns from `result.get` as soon as the
  *     Deferred is complete; the surrounding `Resource.background` then cancels the timer/processor fiber. The
  *     winner-fiber path must run `session.kill()` to completion (which includes the SIGTERM → 5s grace → SIGKILL state
  *     machine on the real `Subprocess.kill`) before publishing the outcome, or `result.get` would unblock and the
  *     cancellation could interrupt a partial kill — leaving a runaway subprocess unreaped.
  *
  *   - **P2 — feature/piece budget breach defers to end of turn.** §12 check 2 says "per-feature/per-piece breach → let
  *     current turn complete, no new spawns". The monitor records a pending breach on the CostUpdate that trips the
  *     cap, keeps consuming events, and only publishes `BudgetBreached` on the next `AgentEvent.Result` (or stream
  *     end). Settle-timeout and turn-budget breach must still win earlier.
  */
class SessionMonitorReviewRound1Suite extends CatsEffectSuite:

  private val limits = SessionLimits(
    settleTimeout = 30.seconds,
    maxTurnCostUsd = BigDecimal("10.00"),
    maxPieceCostUsd = None,
    maxFeatureCostUsd = Some(BigDecimal("5.00"))
  )

  private def cost(usd: String): Cost =
    Cost(provider = "p", model = "m", inputTokens = 0L, outputTokens = 0L, usd = BigDecimal(usd))

  // --- P1 regression: kill runs to completion before result.get unblocks ---------------------------------------

  /** Streaming-session fake whose `kill()` sleeps for `killDuration` and records its completion timestamp into
    * `killFinishedAt`. The P1 test asserts the monitor's `result.get` returns AFTER `killFinishedAt` is set, proving
    * the foreground waited for the kill to complete (instead of unblocking when the Deferred was set and leaving the
    * kill fiber to be cancelled mid-grace).
    */
  private final class SlowKillSession(
      killDuration: FiniteDuration,
      val killStartedAt: Ref[IO, Option[FiniteDuration]],
      val killFinishedAt: Ref[IO, Option[FiniteDuration]]
  ) extends StreamingSession:
    override val sessionId: String = "slow-kill"
    override def events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] =
      for
        nowStart <- IO.monotonic
        _ <- killStartedAt.set(Some(nowStart))
        _ <- IO.sleep(killDuration)
        nowEnd <- IO.monotonic
        _ <- killFinishedAt.set(Some(nowEnd))
      yield ()
    override def send(input: String): IO[Unit] = IO.unit
    override def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

  test("P1: settle timeout — result.get unblocks only AFTER session.kill() completes"):
    val killDuration: FiniteDuration = 5.seconds
    val program =
      for
        killStart <- Ref.of[IO, Option[FiniteDuration]](None)
        killEnd <- Ref.of[IO, Option[FiniteDuration]](None)
        session = new SlowKillSession(killDuration, killStart, killEnd)
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        _ <- monitor.monitor(SessionPhase.Spec, None, session, Stream.never[IO], limits, totals)
        unblockedAt <- IO.monotonic
        end <- killEnd.get
      yield (unblockedAt, end)
    TestControl.executeEmbed(program).map { case (unblockedAt, end) =>
      val killCompletedAt =
        end.getOrElse(fail("kill() never ran to completion"))
      assert(
        unblockedAt >= killCompletedAt,
        s"result.get unblocked at $unblockedAt before kill completed at $killCompletedAt"
      )
    }

  test("P1: turn-budget breach — result.get unblocks only AFTER session.kill() completes"):
    val killDuration: FiniteDuration = 5.seconds
    val program =
      for
        killStart <- Ref.of[IO, Option[FiniteDuration]](None)
        killEnd <- Ref.of[IO, Option[FiniteDuration]](None)
        session = new SlowKillSession(killDuration, killStart, killEnd)
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("11.00")))
        _ <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
        unblockedAt <- IO.monotonic
        end <- killEnd.get
      yield (unblockedAt, end)
    TestControl.executeEmbed(program).map { case (unblockedAt, end) =>
      val killCompletedAt =
        end.getOrElse(fail("kill() never ran to completion"))
      assert(
        unblockedAt >= killCompletedAt,
        s"result.get unblocked at $unblockedAt before kill completed at $killCompletedAt"
      )
    }

  // --- P2 regression: feature/piece breach defers to end of turn -----------------------------------------------

  test("P2: pending feature breach + later events are all consumed; BudgetBreached fires on Result"):
    // Without the round-1 fix, this asserted only on the first CostUpdate and returned BudgetBreached immediately,
    // dropping the rest of the turn on the floor. After the fix, consumption continues until Result.
    val consumed: Ref[IO, Int] = Ref.unsafe[IO, Int](0)
    val events = Stream
      .emits[IO, AgentEvent](
        Vector(
          AgentEvent.CostUpdate(cost("6.00")),
          AgentEvent.AssistantText("still thinking", outputTokens = 3),
          AgentEvent.ToolUse("Read", "src/X.scala"),
          AgentEvent.AssistantText("ok", outputTokens = 1),
          AgentEvent.Result(success = true, durationMs = 42)
        )
      )
      .evalTap(_ => consumed.update(_ + 1))
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limits, totals)
        kills <- session.killCount.get
        nConsumed <- consumed.get
      yield (outcome, kills, nConsumed)
    TestControl.executeEmbed(program).map { case (outcome, kills, nConsumed) =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Feature, _, _) => ()
        case other => fail(s"expected BudgetBreached(Feature, _, _), got $other")
      assertEquals(kills, 0, "feature-scope breach must NOT kill")
      assertEquals(nConsumed, 5, "all 5 events must be consumed before BudgetBreached publishes")
    }

  test("P2: turn-budget breach AFTER a pending feature breach still wins (with kill)"):
    // First CostUpdate trips feature cap but not turn cap; second CostUpdate trips turn cap. Turn breach must
    // win (with kill) even though a feature breach was already pending.
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("6.00")), // feature: 6 > 5; turn: 6 < 10
        AgentEvent.CostUpdate(cost("5.00")) // turn: 11 > 10
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
      assertEquals(
        outcome,
        MonitorOutcome
          .TurnBudgetBreached(SessionPhase.Implement, BigDecimal("11.00"), BigDecimal("10.00")): MonitorOutcome
      )
      assertEquals(kills, 1, "turn-budget breach must kill even after a pending feature breach")
    }

  test("P2: settle timeout AFTER a pending feature breach still wins (with kill)"):
    // Pending feature breach is set, but no Result ever arrives → settle timer fires first.
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("6.00"))) ++ Stream.never[IO]
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
        case MonitorOutcome.SettleTimeout(SessionPhase.Implement, _) => ()
        case other => fail(s"expected SettleTimeout, got $other")
      assertEquals(kills, 1, "settle timeout must kill even with a pending feature breach")
    }

  test("P2: pending feature breach is flushed on stream end without a Result (defensive backstop)"):
    // Anomalous stream that ends without a Result. The pending breach is the only outcome the monitor can publish
    // before the settle timer fires; we ship that as the defensive backstop so the orchestrator at least sees
    // the breach instead of an unrelated SettleTimeout.
    val events = Stream.emit[IO, AgentEvent](AgentEvent.CostUpdate(cost("6.00")))
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
        case MonitorOutcome.BudgetBreached(BudgetScope.Feature, _, _) => ()
        case other => fail(s"expected BudgetBreached on stream end, got $other")
      assertEquals(kills, 0)
    }

  test("P2: first detected breach scope is preserved; subsequent breaches do not overwrite it"):
    // Feature cap = 5; piece cap = 4. First CostUpdate (4.50) trips piece but not feature; second CostUpdate
    // (1.00) bumps feature to 5.50 → also over feature cap. The pending breach should still be the original
    // piece breach (orElse semantics on the pending Ref).
    val limitsPF = limits.copy(maxPieceCostUsd = Some(BigDecimal("4.00")))
    val events = Stream.emits[IO, AgentEvent](
      Vector(
        AgentEvent.CostUpdate(cost("4.50")),
        AgentEvent.CostUpdate(cost("1.00")),
        AgentEvent.Result(success = true, durationMs = 0)
      )
    )
    val program =
      for
        session <- FakeStreamingSession.make
        totals <- Ref.of[IO, CostTotals](CostTotals.zero)
        monitor = new RealSessionMonitor
        outcome <- monitor.monitor(SessionPhase.Implement, None, session, events, limitsPF, totals)
      yield outcome
    TestControl.executeEmbed(program).map { outcome =>
      outcome match
        case MonitorOutcome.BudgetBreached(BudgetScope.Harness, _, cap) =>
          // piece breach with no PieceId supplied → defensive fallback to Harness (see PieceCostSuite)
          assertEquals(cap, BigDecimal("4.00"))
        case other => fail(s"expected first-detected (piece/Harness) breach to win, got $other")
    }
