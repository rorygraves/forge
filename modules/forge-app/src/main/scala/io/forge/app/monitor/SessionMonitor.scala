package io.forge.app.monitor

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession}
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
  *   - [[MonitorOutcome.TurnBudgetBreached]] — per-turn USD cap exceeded. **Dormant since Slice 2.2 (S4-3 / A1):**
  *     [[RealSessionMonitor]] no longer produces this — the per-turn cap is now a post-hoc, non-killing advisory (cost
  *     is reported only at turn-end, so a breach is observed after the turn and its spend are already complete; killing
  *     then reclaims nothing). The variant + its `FsmEvent.TurnBudgetBreached` handling are retained for the FSM
  *     contract and direct test injection. The cumulative feature/piece caps below are the real budget enforcement; the
  *     wall-clock settle timeout is the only mid-turn interrupt.
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
  * apply each `CostUpdate` delta and check the cumulative feature/piece caps, but it does **not** reset
  * `CostTotals.turn` on `AgentEvent.UserMessage` / `AgentEvent.Result` — that reset is the orchestrator's
  * per-turn-boundary responsibility, so the FSM state machine sees a coherent `turnTotalUsd` at every transition. (The
  * monitor no longer gates on `CostTotals.turn` since Slice 2.2 made the per-turn cap a non-killing advisory — see the
  * `TurnBudgetBreached` note above; `turn` is still accumulated for the §19 `cost.update` audit.)
  *
  * **Per-turn observability (Slice 2.0).** `monitor` returns a [[MonitorReport]] — the settle outcome plus this turn's
  * aggregated [[io.forge.core.cost.Cost Cost]] and CLI-reported `durationMs`. The orchestrator records those as the §19
  * `cost.update` / `session.complete` actions (Tasks 2.0.1 / 2.0.2). The aggregate is accumulated locally per call, so
  * it carries no cross-turn-reset hazard (contrast the shared `runningTotals` above).
  */
trait SessionMonitor:
  def monitor(
      phase: SessionPhase,
      piece: Option[PieceId],
      session: AgentSession,
      events: Stream[IO, AgentEvent],
      limits: SessionLimits,
      runningTotals: Ref[IO, CostTotals]
  ): IO[MonitorReport]

/** §7.9 + §12 per-session caps. All USD amounts are `BigDecimal` to match the `Cost.usd` / `CostTotals.*` shape in
  * [[io.forge.core.cost.Cost]] (Slice 2 PR-B). The orchestrator parses the JSON `maxTurnCostUsd: 15.00` form via
  * `BigDecimal(...)` directly so the budget path has no `Double`-precision round-trip.
  *
  * `maxFeatureCostUsd` and `maxPieceCostUsd` are optional because v1.2 §12 lets either be unset (the monitor only
  * checks the configured ones); they are the preventive "this turn went runaway" backstops. `maxTurnCostUsd` and
  * `settleTimeout` are required, but `maxTurnCostUsd` is a post-hoc, non-killing advisory since Slice 2.2 (see the
  * `TurnBudgetBreached` note above); `settleTimeout` is the universal "this session never finished" mid-turn interrupt.
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
