package io.forge.app.monitor

import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession}
import io.forge.core.PieceId
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{BudgetScope, SessionPhase, SettleOutcome}

/** §7.9 + §12 implementation. Races a settle-timeout sleeper against an events-processing consumer; the first to claim
  * the shared "winner" slot publishes the outcome, the other fiber is cancelled by the surrounding
  * `Resource.background` scope.
  *
  * **Kill discipline** (mirrors §12 / the F6 coherence anchor):
  *   - Settle timeout → `session.kill()`, then publish. This is the **only** mid-turn interrupt — the wall-clock cap.
  *   - Per-turn USD cap (`maxTurnCostUsd`) → **post-hoc advisory, no kill** (Slice 2.2 A1 / S4-3). Cost is reported
  *     only in the turn-end frame, so a per-turn breach is observed only after the turn (and its spend) is complete;
  *     killing then reclaims nothing and, for the single-turn headless drivers, only strands an already-settled turn.
  *     The overrun stays visible via the `cost.update` / `session.complete` audit.
  *   - Feature- or piece-scope USD breach → record a pending breach and keep consuming events; on the next
  *     `AgentEvent.Result` (or stream end) publish `BudgetBreached` without killing. §12 check 2: "let current turn
  *     complete, no new spawns" — the orchestrator refuses the next spawn. These cumulative caps are the real budget
  *     enforcement.
  *   - Clean settle (`AgentEvent.Result`) → publish `Settled`, no kill.
  *
  * **Cancellation safety (PR-F review round 1 P1).** Foreground returns from `result.get` as soon as the Deferred is
  * complete; the surrounding `Resource.background` then cancels the timer/processor fiber. To stop that cancellation
  * from interrupting `session.kill()` (which has its own SIGTERM → 5s grace → SIGKILL state machine and would orphan a
  * runaway subprocess if cut short), the winner runs its side effects first and **only then** publishes the outcome,
  * all inside `IO.uncancelable`. The atomic claim (`winnerClaimed.modify(_ => (true, !_))`) ensures exactly one fiber
  * goes through that path — losers find the claim already set and become no-ops.
  *
  * **Kill-failure resilience (PR-F review round 2 P2).** [[io.forge.agents.AgentSession.kill]] is not infallible; the
  * real `StreamingDriver` propagates `Subprocess.kill` failures. If the kill raises, the winner must still publish the
  * outcome or `result.get` hangs forever (the loser fibers see `winnerClaimed = true` and become no-ops, so no other
  * path can publish). `finishWithKill` therefore runs the kill under `attempt`, decorates the outcome with the
  * `Throwable.getMessage` on failure, and always completes the Deferred. Outcomes that don't perform a side effect
  * route through `finish` (`sideEffect = IO.unit`, never throws, `killError` always `None`).
  *
  * The monitor reads `runningTotals` via `updateAndGet` but **does not reset** `CostTotals.turn` on turn boundaries —
  * that is the orchestrator's responsibility per the contract docstring on [[SessionMonitor]]. The cumulative
  * feature/piece caps are checked against `CostTotals.feature` / `.piece` after each `CostUpdate` delta is folded in;
  * the per-turn cap no longer gates (it is advisory — see the kill-discipline note above).
  */
final class RealSessionMonitor extends SessionMonitor:

  override def monitor(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: AgentSession,
      events: Stream[IO, AgentEvent],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals]
  ): IO[MonitorReport] =
    if !SessionMonitor.DriverPhases.contains(phase) then
      IO.raiseError(
        new IllegalArgumentException(
          s"SessionMonitor does not handle $phase — reviewer/refine wall-clock caps live in adapter wrappers " +
            "(design-rationale S3-5; FSM-side S2-8)"
        )
      )
    else
      for
        result <- Deferred[IO, MonitorOutcome]
        winnerClaimed <- Ref.of[IO, Boolean](false)
        pendingBreach <- Ref.of[IO, Option[MonitorOutcome.BudgetBreached]](None)
        // Slice 2.0 per-turn observability: accumulate this turn's CostUpdate deltas + capture the Result duration,
        // both local to this one `monitor` call (one call == one turn) so there is no cross-turn reset to forget (D2).
        turnCost <- Ref.of[IO, Option[Cost]](None)
        durationMs <- Ref.of[IO, Option[Long]](None)

        // Claim the right to publish, run the kill under `attempt` (a kill failure must NOT orphan the monitor —
        // see round 2 P2 in the class docstring), then publish the outcome. The outcome is built from the captured
        // kill failure (None on success) so the diagnostic isn't dropped. All uncancelable so the surrounding
        // Resource.background cannot cut a partial kill short (round 1 P1).
        finishWithKill = (mkOutcome: Option[String] => MonitorOutcome, kill: IO[Unit]) =>
          IO.uncancelable { _ =>
            winnerClaimed.modify(c => (true, !c)).flatMap { won =>
              if won then
                kill.attempt.flatMap { res =>
                  val killError = res.fold(t => Some(Option(t.getMessage).getOrElse(t.toString)), _ => None)
                  result.complete(mkOutcome(killError)).void
                }
              else IO.unit
            }
          }

        // Helper for outcomes that don't perform any side effect. `IO.unit` cannot fail, so `killError` is always
        // `None` here; the through-`finishWithKill` plumbing keeps the claim + uncancelable + always-publish
        // discipline in one place.
        finish = (outcome: MonitorOutcome) => finishWithKill(_ => outcome, IO.unit)

        timer = IO.sleep(limits.settleTimeout).flatMap { _ =>
          val reason = s"settle timeout ${limits.settleTimeout} expired"
          finishWithKill(killErr => MonitorOutcome.SettleTimeout(phase, reason, killErr), session.kill())
        }

        processor = events
          .evalMap(
            handleEvent(
              phase,
              piece,
              limits,
              runningTotals,
              pendingBreach,
              turnCost,
              durationMs,
              finish
            )
          )
          .compile
          .drain
          .flatMap { _ =>
            // Stream ended without an explicit Result. If a budget breach was pending, surface it now; otherwise
            // leave the field open for the timer to fire (the stream-without-Result case is itself anomalous and
            // SettleTimeout is the right backstop).
            pendingBreach.get.flatMap {
              case Some(b) => finish(b)
              case None => IO.unit
            }
          }
          .handleErrorWith { err =>
            finish(MonitorOutcome.Settled(phase, SettleOutcome.AdapterError(err.toString)))
          }

        outcome <- (timer.background, processor.background).tupled.use(_ => result.get)
        // Read the per-turn accumulators *after* the outcome settles: on the publishing path the winning fiber's
        // `turnCost`/`durationMs` writes are sequenced before `result.complete`, so they are visible here.
        tc <- turnCost.get
        dur <- durationMs.get
      yield MonitorReport(phase, outcome, tc, dur)

  private def handleEvent(
      phase: SessionPhase,
      piece: Option[PieceId],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals],
      pendingBreach: Ref[IO, Option[MonitorOutcome.BudgetBreached]],
      turnCost: Ref[IO, Option[Cost]],
      durationMs: Ref[IO, Option[Long]],
      finish: MonitorOutcome => IO[Unit]
  )(event: AgentEvent): IO[Unit] =
    event match
      case AgentEvent.CostUpdate(cost) =>
        // Record the per-turn aggregate (Slice 2.0) alongside the shared running totals + cap check.
        turnCost.update(acc => Some(RealSessionMonitor.addTurnCost(acc, cost))) >>
          runningTotals
            .updateAndGet { old =>
              old.copy(
                feature = old.feature + cost.usd,
                piece = old.piece + cost.usd,
                turn = old.turn + cost.usd
              )
            }
            .flatMap(applyCaps(piece, limits, pendingBreach))

      case AgentEvent.Result(success, dur) =>
        // End-of-turn boundary: a pending feature/piece breach beats Settled (§12 check 2 — current turn was
        // allowed to complete, now we surface the breach so the orchestrator refuses the next spawn). Capture the
        // CLI's own turn duration (Slice 2.0 Task 2.0.2) before finishing either way.
        durationMs.set(Some(dur)) >> pendingBreach.get.flatMap {
          case Some(b) => finish(b)
          case None =>
            val outcome = MonitorOutcome.Settled(
              phase,
              if success then SettleOutcome.Clean else SettleOutcome.AdapterError("non-zero result")
            )
            finish(outcome)
        }

      case _ => IO.unit

  /** §12 budget checks on each `CostUpdate`. **The per-turn cost cap (`maxTurnCostUsd`) is a post-hoc advisory, not a
    * mid-turn interrupt, and no longer kills** (S4-3 / Slice 2.2 A1): cost is reported only in the turn-end frame, so
    * by the time a per-turn breach is observed the turn — and its spend — is already complete. Killing then reclaims
    * nothing and, for the single-turn headless drivers (Implement/Fixup), only strands an already-settled, valuable
    * turn (the szork `$9.56`-vs-`$2` finding). The mid-turn interrupt is the **wall-clock settle cap** (the timer race
    * in [[monitor]]); the cumulative **feature/piece caps below remain the real budget enforcement** — they record a
    * non-killing pending breach that refuses the *next* spawn (§12 check 2). The per-turn overrun stays visible via the
    * `cost.update` / `session.complete` audit the orchestrator writes for the settle.
    */
  private def applyCaps(
      piece: Option[PieceId],
      limits: SessionLimits,
      pendingBreach: Ref[IO, Option[MonitorOutcome.BudgetBreached]]
  )(totals: CostTotals): IO[Unit] =
    // Feature/piece breach: record once and let the current turn complete (§12 check 2). Use `orElse` so a
    // subsequent CostUpdate that also breaches doesn't overwrite the first detected breach.
    val breach: Option[MonitorOutcome.BudgetBreached] =
      limits.maxFeatureCostUsd
        .filter(totals.feature > _)
        .map(cap => MonitorOutcome.BudgetBreached(BudgetScope.Feature, totals, cap))
        .orElse {
          limits.maxPieceCostUsd
            .filter(totals.piece > _)
            .map { cap =>
              val scope = piece match
                case Some(p) => BudgetScope.Piece(p)
                case None => BudgetScope.Harness
              MonitorOutcome.BudgetBreached(scope, totals, cap)
            }
        }
    breach match
      case Some(b) => pendingBreach.update(_.orElse(Some(b)))
      case None => IO.unit

object RealSessionMonitor:
  /** Fold one `AgentEvent.CostUpdate` delta into the running per-turn aggregate (Slice 2.0). Tokens and USD sum; the
    * `provider` / `model` take the delta's values (a single turn is one model, so latest-wins is also first-wins). The
    * seed (`None`) is "no cost seen yet", which the orchestrator reads as "nothing to attribute — write no
    * `cost.update`".
    */
  private[monitor] def addTurnCost(acc: Option[Cost], delta: Cost): Cost =
    acc match
      case None => delta
      case Some(c) =>
        Cost(
          provider = delta.provider,
          model = delta.model,
          inputTokens = c.inputTokens + delta.inputTokens,
          outputTokens = c.outputTokens + delta.outputTokens,
          usd = c.usd + delta.usd
        )
