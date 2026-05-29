package io.forge.app.orchestrator

import io.forge.app.monitor.MonitorOutcome
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{BudgetScope, FsmEvent, FsmState, SessionPhase, SettleOutcome}

/** The §11 side-effect sequence the orchestrator runs between a driver-session settle and the FSM transition that
  * advances past it (Task 1.4.10 J2 sub-phase III). `None` means the settle needs no orchestrator side effects: the
  * synthesized event drives `Fsm.transition` directly.
  *
  * These are *descriptions* — the effectful loop (Task 1.4.10-d) executes them against the real `SpecStore` /
  * `ChangeCollector` / `BranchManager` / git seams. `-c` lands the pure classification so the loop has a single,
  * table-tested decision point (mirroring how `-b` landed the pure `EventSources.select` source table without racing).
  */
enum SettleEffect:
  /** Pass-through — no orchestrator side effects. The converted [[FsmEvent]] drives the FSM directly. */
  case None

  /** §11.1 step 7 — `InteractiveSpec` coherence post-check (verify `design.md`, `manifest.json`, the piece specs and
    * `decomposition.md` cohere; ≤2 corrective rounds on mismatch).
    */
  case CoherencePostCheck

  /** §11.2 steps 9-10 — update `design.md` from the just-settled design-revision session (no PR exists yet). */
  case UpdateDesignAssets

  /** §11.3 steps 3-5 — update design assets, snapshot-tag, force-push-with-lease the design PR branch. */
  case RepushDesignFeedback

  /** §11.4 step 6 — `ChangeCollector` classify; on `Allow`: commit + push + `createPr`. The synthesized event is
    * [[SettleSynthesis.PrOpenedAfterCreate]] because its `PrNumber` is only known after `createPr` returns.
    */
  case ClassifyCommitOpenPr

  /** §11.6 — `ChangeCollector` classify; on `Allow`: commit + push (the PR already exists, so no `createPr`). */
  case ClassifyCommitPush

/** The event the orchestrator synthesizes back into `Fsm.transition` once the plan's side effects succeed. On
  * side-effect failure (e.g. `ChangeCollector` `Deny`, push rejected, `createPr` failed) the loop synthesizes a
  * `HarnessError` instead per the §11 "side effects bracket the FSM transition" contract — that failure path is the
  * loop's concern, not this pure table's.
  */
enum SettleSynthesis:
  /** Deterministic — known without running side effects (every pass-through row, plus the asset-update rows whose
    * success event carries no effect-derived data).
    */
  case Event(event: FsmEvent)

  /** §11.4 step 6 — the piece PR was just created; the loop fills in the `PrNumber` returned by `createPr` to build
    * `FsmEvent.PrOpened(piece, prNumber)`.
    */
  case PrOpenedAfterCreate(piece: io.forge.core.PieceId)

/** What the orchestrator does with a `SessionMonitor` [[MonitorOutcome]] in a given [[FsmState]]: the side effects to
  * run, and the event to synthesize afterwards.
  */
final case class SettlePlan(effect: SettleEffect, synthesis: SettleSynthesis)

/** The pure post-settle synthesis recipe table from Task 1.4.10 J2 (sub-phase III) — the missing glue between a
  * driver-session [[MonitorOutcome]] and the `FsmEvent` that advances the FSM.
  *
  * `plan` is total over `(FsmState, MonitorOutcome)`:
  *   - a `Settled(driverPhase, Clean)` in the state where `driverPhase` is the active driver maps to its §11 recipe row
  *     (the five rows the doc enumerates);
  *   - every other outcome (settle-timeout, turn/feature/piece budget breach, adapter error, hit-question-limit) is a
  *     **pass-through**: no side effects, and the converted `FsmEvent` drives the FSM directly (`Fsm.transition` routes
  *     timeouts/breaches/adapter-errors to NHI on its own);
  *   - a `Settled(driverPhase, Clean)` in a state where `driverPhase` is *not* the active driver is a lifecycle bug and
  *     **raises `IllegalStateException`** (so a future bug never silently runs commit/push side effects in the wrong
  *     state) — mirroring the loud-on-unreachable discipline of `EventSources.select`.
  */
object PostSettleSynthesis:

  def plan(state: FsmState, outcome: MonitorOutcome): SettlePlan =
    outcome match
      case MonitorOutcome.Settled(phase, SettleOutcome.Clean) =>
        driverCleanPlan(state, phase)
      case other =>
        SettlePlan(SettleEffect.None, SettleSynthesis.Event(toFsmEvent(other)))

  /** The five §11 driver-Clean recipe rows. Keyed on `(state, phase)` so a settle for a phase that isn't the state's
    * active driver fails loudly rather than running side effects in the wrong place.
    */
  private def driverCleanPlan(state: FsmState, phase: SessionPhase): SettlePlan =
    (state, phase) match
      case (FsmState.InteractiveSpec, SessionPhase.Spec) =>
        SettlePlan(
          SettleEffect.CoherencePostCheck,
          SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean))
        )
      case (_: FsmState.DesignReviewing, SessionPhase.DesignRevision) =>
        SettlePlan(
          SettleEffect.UpdateDesignAssets,
          SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
        )
      case (_: FsmState.DesignPrFeedback, SessionPhase.DesignRevision) =>
        SettlePlan(
          SettleEffect.RepushDesignFeedback,
          SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
        )
      case (s: FsmState.PieceImplementing, SessionPhase.Implement) =>
        SettlePlan(SettleEffect.ClassifyCommitOpenPr, SettleSynthesis.PrOpenedAfterCreate(s.p))
      case (s: FsmState.PieceFixingUp, SessionPhase.Fixup) =>
        val _ = s
        SettlePlan(
          SettleEffect.ClassifyCommitPush,
          SettleSynthesis.Event(FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean))
        )
      case _ =>
        throw new IllegalStateException(
          s"unreachable post-settle synthesis: clean $phase settle in state $state " +
            s"($phase is not the active driver for this state)"
        )

  /** The universal `MonitorOutcome → FsmEvent` conversion (§7.9 / the `SessionMonitor` source description). `Settled` /
    * `SettleTimeout` map structurally; `TurnBudgetBreached` / `BudgetBreached` flatten their rich cost payload into the
    * FSM-bound `message: String`. A non-`None` `killError` is surfaced into the message so the failed-kill diagnostic
    * isn't lost.
    *
    * Public so the orchestrator loop (Task 1.4.10-d) and tests can convert a pass-through outcome directly; the loop's
    * sub-phase III dispatches on [[plan]] (whose pass-through rows wrap exactly this conversion).
    */
  def toFsmEvent(outcome: MonitorOutcome): FsmEvent =
    outcome match
      case MonitorOutcome.Settled(phase, settle) =>
        FsmEvent.Settled(phase, settle)
      case MonitorOutcome.SettleTimeout(phase, reason, killError) =>
        FsmEvent.SettleTimeout(phase, withKillError(reason, killError))
      case MonitorOutcome.TurnBudgetBreached(phase, turnUsd, capUsd, killError) =>
        FsmEvent.TurnBudgetBreached(phase, withKillError(s"turn cost $$$turnUsd exceeded cap $$$capUsd", killError))
      case MonitorOutcome.BudgetBreached(scope, totals, capUsd) =>
        FsmEvent.BudgetBreached(scope, budgetMessage(scope, totals, capUsd))

  private def withKillError(base: String, killError: Option[String]): String =
    killError.fold(base)(e => s"$base (kill failed: $e)")

  private def budgetMessage(scope: BudgetScope, totals: CostTotals, capUsd: BigDecimal): String =
    scope match
      case BudgetScope.Feature => s"feature cost $$${totals.feature} exceeded cap $$$capUsd"
      case BudgetScope.Piece(p) => s"piece ${p.value} cost $$${totals.piece} exceeded cap $$$capUsd"
      case BudgetScope.Harness => s"harness budget exceeded cap $$$capUsd"
