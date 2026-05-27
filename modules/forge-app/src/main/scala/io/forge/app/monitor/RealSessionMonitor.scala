package io.forge.app.monitor

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}
import io.forge.core.PieceId
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{BudgetScope, SessionPhase, SettleOutcome}

/** §7.9 + §12 implementation. Races a settle-timeout sleeper against an events-processing consumer; the first to
  * complete a shared [[Deferred]] wins, the other fiber is cancelled by the surrounding `Resource.background` scope.
  *
  * **Kill discipline** (mirrors §12 / the F6 coherence anchor):
  *   - Settle timeout → `session.kill()`.
  *   - Per-turn USD breach → `session.kill()`.
  *   - Feature- or piece-scope USD breach → **no kill** (the current turn completes; the orchestrator refuses the next
  *     spawn).
  *   - Clean settle (`AgentEvent.Result`) → no kill.
  *
  * `Deferred.complete` returns `IO[Boolean]` (true on first completion); we gate `session.kill()` on that flag so a
  * timer firing concurrently with a turn-budget breach can't double-kill.
  *
  * The monitor reads `runningTotals` via `modify` but **does not reset** `CostTotals.turn` on turn boundaries — that is
  * the orchestrator's responsibility per the F3 contract docstring on [[SessionMonitor]]. The per-turn cap is checked
  * against `CostTotals.turn` after each `CostUpdate` delta is folded in.
  */
final class RealSessionMonitor extends SessionMonitor:

  override def monitor(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: StreamingSession,
      events: Stream[IO, AgentEvent],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals]
  ): IO[MonitorOutcome] =
    if !SessionMonitor.DriverPhases.contains(phase) then
      IO.raiseError(
        new IllegalArgumentException(
          s"SessionMonitor does not handle $phase — reviewer/refine wall-clock caps live in adapter wrappers " +
            "(design-rationale S3-5; FSM-side S2-8)"
        )
      )
    else
      Deferred[IO, MonitorOutcome].flatMap { result =>
        val timer: IO[Unit] =
          IO.sleep(limits.settleTimeout).flatMap { _ =>
            val outcome =
              MonitorOutcome.SettleTimeout(phase, s"settle timeout ${limits.settleTimeout} expired")
            result.complete(outcome).flatMap { won =>
              if won then session.kill() else IO.unit
            }
          }

        val processor: IO[Unit] =
          events
            .evalMap(handleEvent(phase, piece, session, limits, runningTotals, result))
            .compile
            .drain
            .handleErrorWith { err =>
              // Stream errored before settling. Surface as an AdapterError so the orchestrator can route this
              // through the same NHI path as a non-zero Result.
              result
                .complete(MonitorOutcome.Settled(phase, SettleOutcome.AdapterError(err.toString)))
                .void
            }

        (timer.background, processor.background).tupled.use(_ => result.get)
      }

  private def handleEvent(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: StreamingSession,
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals],
      result: Deferred[IO, MonitorOutcome]
  )(event: AgentEvent): IO[Unit] =
    event match
      case AgentEvent.CostUpdate(cost) =>
        runningTotals
          .updateAndGet { old =>
            old.copy(
              feature = old.feature + cost.usd,
              piece = old.piece + cost.usd,
              turn = old.turn + cost.usd
            )
          }
          .flatMap(applyCapsAndMaybeFire(phase, piece, session, limits, result))

      case AgentEvent.Result(success, _) =>
        val outcome = MonitorOutcome.Settled(
          phase,
          if success then SettleOutcome.Clean else SettleOutcome.AdapterError("non-zero result")
        )
        result.complete(outcome).void

      case _ => IO.unit

  private def applyCapsAndMaybeFire(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: StreamingSession,
      limits: SessionLimits,
      result: Deferred[IO, MonitorOutcome]
  )(totals: CostTotals): IO[Unit] =
    if totals.turn > limits.maxTurnCostUsd then
      val outcome = MonitorOutcome.TurnBudgetBreached(phase, totals.turn, limits.maxTurnCostUsd)
      result.complete(outcome).flatMap(won => if won then session.kill() else IO.unit)
    else
      limits.maxFeatureCostUsd.filter(totals.feature > _) match
        case Some(cap) =>
          result.complete(MonitorOutcome.BudgetBreached(BudgetScope.Feature, totals, cap)).void
        case None =>
          limits.maxPieceCostUsd.filter(totals.piece > _) match
            case Some(cap) =>
              val scope = piece match
                case Some(p) => BudgetScope.Piece(p)
                case None => BudgetScope.Harness
              result.complete(MonitorOutcome.BudgetBreached(scope, totals, cap)).void
            case None => IO.unit
