package io.forge.tui

import io.forge.core.fsm.FsmState

/** The immutable read-model the TUI panes render.
  *
  * Slice 2.1 is **log-tail-first** (see `docs/design-2.1-tui.md`): the snapshot is a pure projection of the canonical
  * action log + state cache — the same sources `forge status` / `forge tail` read — so the panes are a deterministic
  * function of committed data and stay replayable and unit-testable without a live orchestrator. The first runnable
  * slice renders a hand-supplied snapshot; a later Task wires the log-fold builder (mirroring
  * `StatusReport.renderFeature`) that produces it, and a later Task still adds a live `AgentEvent` tap that refreshes
  * the active pane between settle points.
  *
  * Fields mirror the §3.1 status pane (feature / piece / FSM state / budget / last action) plus the active-pane
  * selection and its lines.
  */
final case class TuiSnapshot(
    featureId: String,
    title: String,
    mode: String,
    stateLabel: String,
    pieceLabel: String,
    lastAction: String,
    budgetLine: String,
    activePane: ActivePane,
    activeLines: Vector[String]
)

object TuiSnapshot:

  /** Placeholder shown before the first log read (or for a feature with no state cache yet). Replaced by the log-fold
    * builder in a later Slice 2.1 Task.
    */
  def loading(featureId: String): TuiSnapshot =
    TuiSnapshot(
      featureId = featureId,
      title = "(loading…)",
      mode = "—",
      stateLabel = "loading state…",
      pieceLabel = "—",
      lastAction = "—",
      budgetLine = "—",
      activePane = ActivePane.Idle,
      activeLines = Vector.empty
    )

/** Which view the active (right-hand) pane shows — chosen from the FSM state by the snapshot builder. Mirrors the §3.1
  * component diagram's "Active pane (one of)" list.
  */
enum ActivePane:
  /** A driver session is running — streaming chat / live token tail. */
  case Streaming

  /** Tailing the committed action log. */
  case LogTail

  /** A driver question is awaiting a human answer. */
  case Question

  /** Awaiting CI / merge, or nothing running. */
  case Idle

object ActivePane:

  /** Map an [[FsmState]] to the active pane it drives, per `design-2.1-tui.md` Task 2.1.2:
    *
    *   - a live driver session (`PieceImplementing` / `PieceFixingUp` / `Refining`) → [[Streaming]];
    *   - a human-blocking state (`DesignNeedsHumanInput` / `NeedsHumanIntervention`) → [[Question]];
    *   - parked on an external system (`PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge` /
    *     `DesignAwaitingMerge`) → [[Idle]];
    *   - everything else (spec/design driver phases, CI/review-failed, planning-update, terminal) → [[LogTail]], the
    *     default committed-log view.
    *
    * The spec/design *driver* phases (`InteractiveSpec` / `DesignReviewing` / `DesignPrFeedback`) deliberately fall to
    * [[LogTail]] rather than [[Streaming]]: the log-tail-first v1 has no live token tap for them (that is Task 2.1.5),
    * so the committed log is the truthful view. The [[Streaming]] cases are the ones whose recent log lines best stand
    * in for live driver output until the tap lands.
    */
  def forState(s: FsmState): ActivePane = s match
    case _: FsmState.PieceImplementing => Streaming
    case _: FsmState.PieceFixingUp => Streaming
    case _: FsmState.Refining => Streaming
    case _: FsmState.DesignNeedsHumanInput => Question
    case _: FsmState.NeedsHumanIntervention => Question
    case _: FsmState.PieceAwaitingCi => Idle
    case _: FsmState.PieceAwaitingReview => Idle
    case _: FsmState.PieceAwaitingMerge => Idle
    case _: FsmState.DesignAwaitingMerge => Idle
    case _ => LogTail
