package io.forge.tui

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
