package io.forge.app.monitor

import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{BudgetScope, SessionPhase, SettleOutcome}

/** §12 / §7.9 — output ADT produced by [[SessionMonitor]] for the four driver phases (`Spec`, `DesignRevision`,
  * `Implement`, `Fixup`). Each variant maps **structurally** onto a Slice-2 [[io.forge.core.fsm.FsmEvent FsmEvent]]
  * variant — the Slice-4 orchestrator does the conversion at its call site, flattening the richer typed cost payload
  * here into the `message: String` field on [[io.forge.core.fsm.FsmEvent.TurnBudgetBreached]] /
  * [[io.forge.core.fsm.FsmEvent.BudgetBreached]].
  *
  * Keeping the rich cost data on the monitor side means `SessionMonitor`'s own assertions, future Slice-4 audit-log
  * entries (`harness.session_killed` per §19 has `reason: "settle_timeout" | "turn_budget"` plus a free-text message),
  * and unit tests don't need to re-parse the FSM-bound message string.
  *
  * **Downstream consumers** (per the PR-F F6 coherence anchor — see design-2.3 §1.6):
  *
  *   - [[io.forge.agents.StreamingSession.kill]] — the monitor calls `kill()` on settle-timeout and turn-budget
  *     breaches; feature- and piece-scope budget breaches let the current turn complete (§12 check 2). Any change to
  *     the variant set must keep that kill discipline aligned.
  *   - [[io.forge.core.fsm.FsmEvent]] — `MonitorOutcome.Settled` / `SettleTimeout` map verbatim onto the like-named
  *     FsmEvent variants; `TurnBudgetBreached` / `BudgetBreached` lose their cost payload at the conversion point. If a
  *     new MonitorOutcome variant is added, the Slice-4 orchestrator gets a new conversion arm.
  *   - §19 action log — `harness.session_killed` carries `reason: "settle_timeout" | "turn_budget"` (a closed-string
  *     enum). Settle-timeout + turn-budget map 1:1; budget-breach does not invoke `kill()` and therefore does not
  *     produce a `harness.session_killed` entry.
  */
sealed trait MonitorOutcome

object MonitorOutcome:

  /** Driver session settled cleanly. Maps to [[io.forge.core.fsm.FsmEvent.Settled]] verbatim. */
  final case class Settled(phase: SessionPhase, outcome: SettleOutcome) extends MonitorOutcome

  /** Wall-clock cap expired before the driver settled; the monitor invoked `session.kill()` before returning. Maps to
    * [[io.forge.core.fsm.FsmEvent.SettleTimeout]] verbatim.
    */
  final case class SettleTimeout(phase: SessionPhase, reason: String) extends MonitorOutcome

  /** Per-turn USD cap breached mid-turn (§12 check 3). The monitor invoked `session.kill()` before returning. Maps to
    * [[io.forge.core.fsm.FsmEvent.TurnBudgetBreached]]; Slice-4 formats `turnUsd` + `capUsd` into the FsmEvent message
    * string (e.g. `s"turn cost \$$turnUsd exceeded cap \$$capUsd"`).
    */
  final case class TurnBudgetBreached(phase: SessionPhase, turnUsd: BigDecimal, capUsd: BigDecimal)
      extends MonitorOutcome

  /** Feature- or piece-scope USD cap breached (§12 check 2). The monitor does **not** call `session.kill()` — the
    * current turn completes and the orchestrator refuses the next spawn. Maps to
    * [[io.forge.core.fsm.FsmEvent.BudgetBreached]]; Slice-4 formats `totals` + `capUsd` into the FsmEvent message
    * string.
    */
  final case class BudgetBreached(scope: BudgetScope, totals: CostTotals, capUsd: BigDecimal) extends MonitorOutcome
