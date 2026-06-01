package io.forge.tui

import termflow.tui.*
import termflow.tui.TuiPrelude.*
import termflow.tui.Tui.tui
import termflow.tui.KeyDecoder.InputKey

import scala.concurrent.Future

/** Slice 2.1 — the Forge TUI, first runnable slice.
  *
  * An Elm-architecture (`termflow.tui.TuiApp`) two-pane dashboard over a [[TuiSnapshot]]: a left **status** pane
  * (feature / FSM state / current piece / last action / budget) and a right **active** pane (streaming / log-tail /
  * question / idle, per `snapshot.activePane`). This slice renders a snapshot supplied at construction and proves the
  * termflow wiring end to end — the Elm loop (`init`/`update`/`view`), the virtual-DOM view, keyboard quit, and a 1s
  * tick — exercised by [[ForgeTuiAppSuite]] through `termflow.testkit.TuiTestDriver` with no real terminal.
  *
  * The snapshot is refreshed by polling: `run(initial, reload)` (Task 2.1.3, what `forge tui` calls) re-folds the
  * committed log + state cache via the `Sub.Every` tick (log-tail-first, see `docs/design-2.1-tui.md`); the
  * `run(snapshot)` entry keeps the construction-time snapshot static. A live `AgentEvent` tap for token-level streaming
  * in the active pane is still deferred (Task 2.1.5).
  */
object ForgeTui:

  /** The TUI model: the rendered snapshot plus liveness bookkeeping (tick count, last key) and the active-pane scroll
    * position.
    *
    * `scrollBack` is the number of lines the *bottom* of the active-pane viewport sits above the newest line — `0`
    * means **follow-tail** (the viewport shows the most recent lines), and a positive value parks the viewport that
    * many lines back in history. Measuring from the tail (rather than from the top) is what makes follow-tail fall out
    * for free: when a poll refresh (Task 2.1.3) appends new lines, a `scrollBack == 0` viewport keeps showing the
    * newest lines, while a parked viewport stays anchored relative to the tail. The value is clamped to the scrollable
    * range at render time, so a snapshot that shrinks can never strand the viewport.
    */
  final case class Model(
      snapshot: TuiSnapshot,
      ticks: Long,
      lastKey: Option[String],
      scrollBack: Int = 0,
      width: Int = MinFrameWidth,
      height: Int = MinFrameHeight,
      showHelp: Boolean = false
  )

  enum Msg:
    /** 1s heartbeat from `Sub.Every` — bumps the liveness counter and kicks off a [[reload]] of the committed data. */
    case Tick

    /** A freshly re-folded snapshot from the [[reload]] poll — replaces the rendered snapshot in place. */
    case Refreshed(snapshot: TuiSnapshot)

    /** A decoded key event from `Sub.InputKey`. */
    case Key(k: InputKey)

    /** Terminal dimensions changed; the next frame reflows the panes. */
    case Resize(width: Int, height: Int)

    /** An input-source error forwarded from `Sub.InputKey` — surfaced, not fatal. */
    case KeyError(t: Throwable)

    /** Quit (from the `quit` / `:q` prompt command). */
    case Quit

  /** Default [[App]] reload — no live data source, so the snapshot supplied at construction never changes. Used by the
    * static `run(snapshot)` entry and the headless app tests; `forge tui` (Task 2.1.3) passes a real log re-fold.
    */
  private val NoReload: () => Future[Option[TuiSnapshot]] = () => Future.successful(None)

  private val MinFrameWidth = 60
  private val MinFrameHeight = 16

  private val Theme0: Theme =
    Theme.dark.copy(
      primary = Color.Cyan,
      secondary = Color.Blue,
      border = Color.BrightBlack,
      chars = BorderChars.rounded
    )

  /** Run the TUI against a static `snapshot` (no live refresh). Blocks until the user quits. */
  def run(snapshot: TuiSnapshot): Unit =
    val _ = TuiRuntime.run(new App(snapshot))

  /** Run the TUI starting from `initial`, re-folding the snapshot from `reload` on each 1s tick. `forge tui` (Task
    * 2.1.3) passes a read-only log re-fold here; `reload` returning `None` leaves the current snapshot in place. Blocks
    * until the user quits.
    */
  def run(initial: TuiSnapshot, reload: () => Future[Option[TuiSnapshot]]): Unit =
    val _ = TuiRuntime.run(new App(initial, reload))

  final class App(initial: TuiSnapshot, reload: () => Future[Option[TuiSnapshot]] = NoReload)
      extends TuiApp[Model, Msg]:

    def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      // Each Sub.* factory already auto-registers with the RuntimeCtx (termflow's `Sub.autoRegisterIfRuntimeCtx` /
      // `ctx.registerSub` inside the factory), so constructing them is enough — wrapping in `ctx.registerSub(...)`
      // would register the same subscription a second time.
      val _ = Sub.Every(1000L, () => Msg.Tick, ctx)
      val _ = Sub.InputKey(k => Msg.Key(k), t => Msg.KeyError(t), ctx)
      val _ = Sub.TerminalResize(250L, Msg.Resize.apply, ctx)
      Model(initial, 0L, None, width = ctx.terminal.width, height = ctx.terminal.height).tui

    def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      msg match
        // The tick bumps the liveness counter and launches a reload; the re-folded snapshot arrives as Msg.Refreshed.
        case Msg.Tick =>
          Tui(model.copy(ticks = model.ticks + 1), Cmd.FCmd(reload(), refreshCmd))
        // Replace the rendered snapshot. `scrollBack` is tail-relative, so it carries across an append-only refresh
        // within the same pane (a follow-tail viewport keeps tracking the newest lines; a parked one stays anchored to
        // the tail). But a *pane change* means a different content stream — preserving scrollBack there would strand the
        // viewport in unrelated history, so reset to follow-tail. (Within a pane the log is append-only, so the
        // tail-relative model holds; the at-render clamp covers any shrink.)
        case Msg.Refreshed(snapshot) =>
          val scrollBack = if snapshot.activePane == model.snapshot.activePane then model.scrollBack else 0
          model.copy(snapshot = snapshot, scrollBack = scrollBack).tui
        case Msg.Quit => Tui(model, Cmd.Exit)
        case Msg.Resize(width, height) => model.copy(width = width, height = height).tui
        case Msg.KeyError(_) => model.tui
        case Msg.Key(k) =>
          k match
            case InputKey.CharKey('q') => Tui(model, Cmd.Exit)
            case InputKey.CharKey('?') => model.copy(showHelp = !model.showHelp).tui
            case InputKey.Escape if model.showHelp => model.copy(showHelp = false).tui
            case InputKey.Ctrl('c') => Tui(model, Cmd.Exit)
            case InputKey.Ctrl('C') => Tui(model, Cmd.Exit)
            case other =>
              // Scroll keys adjust the active-pane viewport; every other key is recorded (last-key indicator) but inert.
              val scrolled = scrollDelta(other, model) match
                case Some(delta) => model.copy(scrollBack = clampScrollBack(model.scrollBack + delta, model))
                case None => model
              scrolled.copy(lastKey = Some(other.toString)).tui

    def view(model: Model): RootNode =
      val s = model.snapshot
      val m = FrameMetrics.forModel(model)
      given Theme = Theme0
      val title =
        TextNode(
          2.x,
          1.y,
          List(
            truncate(s"""Forge — ${s.featureId}  "${s.title}"  [${s.mode}]""", m.width - 3)
              .text(Style(fg = Theme0.primary, bold = true))
          )
        )

      val statusBox = BoxNode(
        1.x,
        1.y,
        width = m.statusWidth,
        height = m.panelHeight,
        children = List(
          TextNode(3.x, 2.y, List("STATUS".text(Style(fg = Theme0.primary, bold = true, underline = true)))),
          statusLine(4, "state:  ", s.stateLabel, m.statusInnerWidth),
          statusLine(5, "piece:  ", s.pieceLabel, m.statusInnerWidth),
          statusLine(6, "last:   ", s.lastAction, m.statusInnerWidth),
          statusLine(8, "budget: ", s.budgetLine, m.statusInnerWidth),
          statusLine(math.max(9, m.panelHeight - 2), "tick:   ", model.ticks.toString, m.statusInnerWidth)
        ),
        style = Style(border = true, fg = Theme0.border),
        chars = Theme0.chars
      )

      val av = activeView(model)
      val activeHeader = truncate(s"ACTIVE — ${paneLabel(s.activePane)}${scrollIndicator(av)}", m.activeInnerWidth)
      val activeBox = BoxNode(
        1.x,
        1.y,
        width = m.activeWidth,
        height = m.panelHeight,
        children = TextNode(3.x, 2.y, List(activeHeader.text(Style(fg = Theme0.primary, bold = true)))) ::
          activeLineNodes(av, m),
        style = Style(border = true, fg = Theme0.border),
        chars = Theme0.chars
      )

      val footer =
        TextNode(
          2.x,
          m.height.y,
          List(truncate("q quit · ↑↓ PgUp/PgDn scroll · ? help · :q command", m.width - 3).text(Style(dim = true)))
        )

      val body = Layout.row(gap = 1)(statusBox, activeBox).resolve(Coord(1.x, 2.y))

      RootNode(
        width = m.width,
        height = m.height,
        children = title :: body ::: List(footer),
        input = None,
        overlays = if model.showHelp then List(helpOverlay()) else Nil
      )

    def toMsg(input: PromptLine): Result[Msg] =
      input.value.trim.toLowerCase match
        case "q" | "quit" | ":q" => Right(Msg.Quit)
        case _ => Left(TermFlowError.CommandError(input.value))

    /** Map a [[reload]] result onto the command bus: a fresh snapshot re-renders via [[Msg.Refreshed]]; `None` (no
      * readable manifest, e.g. the feature was abandoned mid-session) is a no-op that keeps the last good frame.
      */
    private def refreshCmd(loaded: Option[TuiSnapshot]): Cmd[Msg] =
      loaded match
        case Some(snapshot) => Cmd.GCmd(Msg.Refreshed(snapshot))
        case None => Cmd.NoCmd

    private def statusLine(row: Int, label: String, value: String, width: Int): VNode =
      TextNode(3.x, row.y, List(truncate(s"$label$value", width).text))

    private def activeLineNodes(av: ActiveView, m: FrameMetrics): List[VNode] =
      av.lines.toList.zipWithIndex.map { case (text, i) =>
        TextNode(3.x, (m.activeFirstRow + i).y, List(truncate(text, m.activeInnerWidth).text))
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

  /** The slice of active-pane lines currently visible, plus how many are hidden above / below the viewport. */
  private final case class ActiveView(lines: Vector[String], hiddenAbove: Int, hiddenBelow: Int):
    /** True when the lines overflow the viewport — i.e. scrolling can change what's shown. */
    def overflow: Boolean = hiddenAbove > 0 || hiddenBelow > 0

  /** The largest meaningful `scrollBack` for `total` lines: scrolling further would push the first line off the top of
    * the viewport with nothing to reveal, so it is capped at "first line at the top".
    */
  private def maxScrollBack(total: Int, rows: Int): Int = math.max(0, total - rows)

  /** Clamp a candidate `scrollBack` to `[0, maxScrollBack]` for the snapshot's active-line count. */
  private def clampScrollBack(back: Int, model: Model): Int =
    math.max(0, math.min(back, maxScrollBack(model.snapshot.activeLines.size, FrameMetrics.forModel(model).activeRows)))

  /** The per-key viewport delta: `ArrowUp`/`ArrowDown` move one line, `PageUp`/`PageDown` a full viewport. Positive
    * scrolls back into history (older lines); negative scrolls toward the tail. `None` for non-scroll keys.
    */
  private def scrollDelta(k: InputKey, model: Model): Option[Int] = k match
    case InputKey.ArrowUp => Some(1)
    case InputKey.ArrowDown => Some(-1)
    case InputKey.PageUp => Some(FrameMetrics.forModel(model).activeRows)
    case InputKey.PageDown => Some(-FrameMetrics.forModel(model).activeRows)
    case _ => None

  /** Project the model's active lines + scroll position onto the viewport. Falls back to a single placeholder line when
    * there are no lines yet (which never overflows, so scroll keys are inert there).
    */
  private def activeView(model: Model): ActiveView =
    val s = model.snapshot
    val lines = if s.activeLines.nonEmpty then s.activeLines else Vector(placeholderFor(s.activePane))
    val total = lines.size
    val rows = FrameMetrics.forModel(model).activeRows
    val back = clampScrollBack(model.scrollBack, model)
    val bottom = total - back
    val top = math.max(0, bottom - rows)
    ActiveView(lines = lines.slice(top, bottom), hiddenAbove = top, hiddenBelow = back)

  /** A compact scroll position for the active-pane header: nothing when the lines fit, `[↑a ↓b]` otherwise (`b == 0`
    * means following the tail).
    */
  private def scrollIndicator(av: ActiveView): String =
    if av.overflow then s"  [↑${av.hiddenAbove} ↓${av.hiddenBelow}]" else ""

  private final case class FrameMetrics(
      width: Int,
      height: Int,
      panelHeight: Int,
      statusWidth: Int,
      activeWidth: Int,
      activeInnerWidth: Int,
      activeFirstRow: Int,
      activeRows: Int
  ):
    def statusInnerWidth: Int = statusWidth - 4

  private object FrameMetrics:
    def forModel(model: Model): FrameMetrics =
      val width = math.max(MinFrameWidth, model.width)
      val height = math.max(MinFrameHeight, model.height)
      val panelHeight = math.max(10, height - 3)
      val statusWidth = math.max(28, math.min(40, (width * 45) / 100))
      val activeWidth = math.max(20, width - statusWidth - 1)
      val activeFirstRow = 4
      val activeRows = math.max(1, panelHeight - activeFirstRow - 1)
      FrameMetrics(
        width = width,
        height = height,
        panelHeight = panelHeight,
        statusWidth = statusWidth,
        activeWidth = activeWidth,
        activeInnerWidth = activeWidth - 4,
        activeFirstRow = activeFirstRow,
        activeRows = activeRows
      )

  private def helpOverlay()(using theme: Theme): Overlay =
    val rows = Vector(
      "q / Ctrl-C       quit",
      "ArrowUp/Down     scroll one line",
      "PageUp/PageDown  scroll one page",
      "? / Esc          toggle this help",
      ":q               prompt command quit"
    )
    val width = 38
    val height = rows.size + 4
    val box = Theme.box(1.x, 1.y, width, height)
    val title = TextNode(3.x, 1.y, List(" Forge keys ".text(Style(fg = theme.primary, bold = true))))
    val body = rows.zipWithIndex.map { case (line, i) =>
      TextNode(3.x, (3 + i).y, List(line.text))
    }.toList
    Overlay(
      position = OverlayPosition.Centered,
      width = width,
      height = height,
      children = box :: title :: body,
      inputCapture = InputCapture.Modal
    )
