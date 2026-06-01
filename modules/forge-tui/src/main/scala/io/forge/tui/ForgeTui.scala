package io.forge.tui

import termflow.tui.*
import termflow.tui.TuiPrelude.*
import termflow.tui.Tui.tui
import termflow.tui.KeyDecoder.InputKey

/** Slice 2.1 — the Forge TUI, first runnable slice.
  *
  * An Elm-architecture (`termflow.tui.TuiApp`) two-pane dashboard over a [[TuiSnapshot]]: a left **status** pane
  * (feature / FSM state / current piece / last action / budget) and a right **active** pane (streaming / log-tail /
  * question / idle, per `snapshot.activePane`). This slice renders a snapshot supplied at construction and proves the
  * termflow wiring end to end — the Elm loop (`init`/`update`/`view`), the virtual-DOM view, keyboard quit, and a 1s
  * tick — exercised by [[ForgeTuiAppSuite]] through `termflow.testkit.TuiTestDriver` with no real terminal.
  *
  * Later Slice 2.1 Tasks replace the static snapshot with a log-fold builder polled via `Sub.Every` (log-tail-first,
  * see `docs/design-2.1-tui.md`) and add a live `AgentEvent` tap for token-level streaming in the active pane.
  */
object ForgeTui:

  /** The TUI model: the rendered snapshot plus liveness bookkeeping (tick count, last key). */
  final case class Model(snapshot: TuiSnapshot, ticks: Long, lastKey: Option[String])

  enum Msg:
    /** 1s heartbeat from `Sub.Every` — drives a "still alive" indicator (and later, a log re-poll). */
    case Tick

    /** A decoded key event from `Sub.InputKey`. */
    case Key(k: InputKey)

    /** An input-source error forwarded from `Sub.InputKey` — surfaced, not fatal. */
    case KeyError(t: Throwable)

    /** Quit (from the `quit` / `:q` prompt command). */
    case Quit

  /** Logical drawing surface. The renderer reads the real terminal size; these are the app's own frame bounds (fixed
    * for the first slice; reflow-on-resize is a later Task).
    */
  private val FrameWidth = 80
  private val FrameHeight = 20

  // Status pane: cols 1..36, rows 2..17. Active pane: cols 38..79, rows 2..17.
  private val StatusInnerCol = 3
  private val StatusInnerWidth = 32
  private val ActiveInnerCol = 40
  private val ActiveInnerWidth = 38
  private val ActiveFirstRow = 5
  private val ActiveLastRow = 15

  /** Run the TUI against `snapshot`. Blocks until the user quits. */
  def run(snapshot: TuiSnapshot): Unit =
    val _ = TuiRuntime.run(new App(snapshot))

  final class App(initial: TuiSnapshot) extends TuiApp[Model, Msg]:

    def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      val _ = ctx.registerSub(Sub.Every(1000L, () => Msg.Tick, ctx))
      val _ = ctx.registerSub(Sub.InputKey(k => Msg.Key(k), t => Msg.KeyError(t), ctx))
      Model(initial, 0L, None).tui

    def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      msg match
        case Msg.Tick => model.copy(ticks = model.ticks + 1).tui
        case Msg.Quit => Tui(model, Cmd.Exit)
        case Msg.KeyError(_) => model.tui
        case Msg.Key(k) =>
          k match
            case InputKey.CharKey('q') => Tui(model, Cmd.Exit)
            case InputKey.Ctrl('c') => Tui(model, Cmd.Exit)
            case other => model.copy(lastKey = Some(other.toString)).tui

    def view(model: Model): RootNode =
      val s = model.snapshot
      val title =
        TextNode(2.x, 1.y, List(s"""Forge — ${s.featureId}  "${s.title}"  [${s.mode}]""".text(Style(bold = true))))

      val statusBox = BoxNode(
        1.x,
        2.y,
        width = 36,
        height = 16,
        children = List(
          TextNode(StatusInnerCol.x, 3.y, List("STATUS".text(Style(bold = true, underline = true)))),
          statusLine(5, "state:  ", s.stateLabel),
          statusLine(6, "piece:  ", s.pieceLabel),
          statusLine(7, "last:   ", s.lastAction),
          statusLine(9, "budget: ", s.budgetLine),
          statusLine(15, "tick:   ", model.ticks.toString)
        ),
        style = Style(border = true)
      )

      val activeBox = BoxNode(
        38.x,
        2.y,
        width = 42,
        height = 16,
        children =
          TextNode(ActiveInnerCol.x, 3.y, List(s"ACTIVE — ${paneLabel(s.activePane)}".text(Style(bold = true)))) ::
            activeLineNodes(s),
        style = Style(border = true)
      )

      val footer =
        TextNode(2.x, 19.y, List("q quit · :q command".text(Style(dim = true))))

      RootNode(
        width = FrameWidth,
        height = FrameHeight,
        children = List(title, statusBox, activeBox, footer),
        input = None
      )

    def toMsg(input: PromptLine): Result[Msg] =
      input.value.trim.toLowerCase match
        case "q" | "quit" | ":q" => Right(Msg.Quit)
        case _ => Left(TermFlowError.CommandError(input.value))

    private def statusLine(row: Int, label: String, value: String): VNode =
      TextNode(StatusInnerCol.x, row.y, List(truncate(s"$label$value", StatusInnerWidth).text))

    private def activeLineNodes(s: TuiSnapshot): List[VNode] =
      val lines = if s.activeLines.nonEmpty then s.activeLines else Vector(placeholderFor(s.activePane))
      lines.take(ActiveLastRow - ActiveFirstRow + 1).toList.zipWithIndex.map { case (text, i) =>
        TextNode(ActiveInnerCol.x, (ActiveFirstRow + i).y, List(truncate(text, ActiveInnerWidth).text))
      }

  private def paneLabel(p: ActivePane): String = p match
    case ActivePane.Streaming => "streaming"
    case ActivePane.LogTail => "log tail"
    case ActivePane.Question => "question"
    case ActivePane.Idle => "idle"

  private def placeholderFor(p: ActivePane): String = p match
    case ActivePane.Streaming => "(waiting for driver output…)"
    case ActivePane.LogTail => "(no actions logged yet)"
    case ActivePane.Question => "(no question pending)"
    case ActivePane.Idle => "(idle — nothing running)"

  private def truncate(s: String, n: Int): String =
    if s.length <= n then s else s.take(math.max(0, n - 1)) + "…"
