package io.forge.app.monitor

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}
import io.forge.core.PieceId
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.SessionPhase

import scala.concurrent.duration.FiniteDuration

/** §7.9 settle bounds + §12 budget enforcement. `SessionMonitor` watches a driver session's `AgentEvent` stream,
  * accumulates per-turn / per-piece / per-feature USD totals, and fires the first qualifying [[MonitorOutcome]]:
  *
  *   - [[MonitorOutcome.Settled]] — the stream terminated with an `AgentEvent.Result`.
  *   - [[MonitorOutcome.SettleTimeout]] — `limits.settleTimeout` elapsed first; the monitor calls `session.kill()`
  *     before returning.
  *   - [[MonitorOutcome.TurnBudgetBreached]] — per-turn USD cap exceeded mid-turn (§12 check 3); the monitor calls
  *     `session.kill()` before returning.
  *   - [[MonitorOutcome.BudgetBreached]] — feature- or piece-scope USD cap exceeded; the monitor does **not** call
  *     `kill()` (§12 check 2 — current turn completes, orchestrator refuses next spawn).
  *
  * **Phase scope.** Slice 3 covers the four driver phases that v1.2 §11 currently exposes a `Stream[IO, AgentEvent]`
  * for: `Spec`, `DesignRevision`, `Implement`, `Fixup`. Reviewer (`DesignReview`, `CodeReview`) and refinery (`Refine`)
  * are one-shot adapter calls with no streaming session; their wall-clock caps live in the Slice-4 reviewer-asset
  * adapter wrappers. Per design-rationale carry-forward S3-5 (mirroring S2-8 on the FSM side), passing one of those
  * phases is a programmer error — implementations refuse them with an `IllegalArgumentException` so a Slice-4 caller
  * can't silently drop a settle event for the wrong phase.
  *
  * **Cost-totals ownership.** The orchestrator (Slice 4) owns `runningTotals`. The monitor reads it via `modify` to
  * apply each `CostUpdate` delta and check caps, but it does **not** reset `CostTotals.turn` on
  * `AgentEvent.UserMessage` / `AgentEvent.Result` — that reset is the orchestrator's per-turn-boundary responsibility,
  * so the FSM state machine sees a coherent `turnTotalUsd` at every transition. The monitor treats `CostTotals.turn` as
  * authoritative when checking the per-turn cap.
  */
trait SessionMonitor:
  def monitor(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: StreamingSession,
      events: Stream[IO, AgentEvent],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals]
  ): IO[MonitorOutcome]

/** §7.9 + §12 per-session caps. All USD amounts are `BigDecimal` to match the `Cost.usd` / `CostTotals.*` shape in
  * [[io.forge.core.cost.Cost]] (Slice 2 PR-B). The orchestrator parses the JSON `maxTurnCostUsd: 2.00` form via
  * `BigDecimal(...)` directly so the budget path has no `Double`-precision round-trip.
  *
  * `maxFeatureCostUsd` and `maxPieceCostUsd` are optional because v1.2 §12 lets either be unset (the monitor only
  * checks the configured ones). `maxTurnCostUsd` and `settleTimeout` are required — they're the universal "this turn
  * went runaway" / "this session never finished" backstops.
  */
final case class SessionLimits(
    settleTimeout: FiniteDuration,
    maxTurnCostUsd: BigDecimal,
    maxPieceCostUsd: Option[BigDecimal],
    maxFeatureCostUsd: Option[BigDecimal]
)

object SessionMonitor:

  /** The four driver phases SessionMonitor handles in Slice 3. Listed here so a single source of truth governs the F4
    * precondition check, F5's PhaseCoverageSuite, and the FsmEvent dispatch contract.
    */
  val DriverPhases: Set[SessionPhase] =
    Set(SessionPhase.Spec, SessionPhase.DesignRevision, SessionPhase.Implement, SessionPhase.Fixup)
