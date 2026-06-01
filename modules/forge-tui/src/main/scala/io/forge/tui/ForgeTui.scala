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
  final case class Model(snapshot: TuiSnapshot, ticks: Long, lastKey: Option[String], scrollBack: Int = 0)

  enum Msg:
    /** 1s heartbeat from `Sub.Every` — bumps the liveness counter and kicks off a [[reload]] of the committed data. */
    case Tick

    /** A freshly re-folded snapshot from the [[reload]] poll — replaces the rendered snapshot in place. */
    case Refreshed(snapshot: TuiSnapshot)

    /** A decoded key event from `Sub.InputKey`. */
    case Key(k: InputKey)

    /** An input-source error forwarded from `Sub.InputKey` — surfaced, not fatal. */
    case KeyError(t: Throwable)

    /** Quit (from the `quit` / `:q` prompt command). */
    case Quit

  /** Default [[App]] reload — no live data source, so the snapshot supplied at construction never changes. Used by the
    * static `run(snapshot)` entry and the headless app tests; `forge tui` (Task 2.1.3) passes a real log re-fold.
    */
  private val NoReload: () => Future[Option[TuiSnapshot]] = () => Future.successful(None)

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

  /** Height (in lines) of the active-pane viewport — the scroll page size for `PageUp` / `PageDown`. */
  private val ActiveRows = ActiveLastRow - ActiveFirstRow + 1

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
      val _ = ctx.registerSub(Sub.Every(1000L, () => Msg.Tick, ctx))
      val _ = ctx.registerSub(Sub.InputKey(k => Msg.Key(k), t => Msg.KeyError(t), ctx))
      Model(initial, 0L, None).tui

    def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      msg match
        // The tick bumps the liveness counter and launches a reload; the re-folded snapshot arrives as Msg.Refreshed.
        case Msg.Tick =>
          Tui(model.copy(ticks = model.ticks + 1), Cmd.FCmd(reload(), refreshCmd))
        // Replace the rendered snapshot; scrollBack is left untouched (re-clamped at render), so a follow-tail viewport
        // keeps tracking the newest lines while a parked one stays anchored relative to the tail.
        case Msg.Refreshed(snapshot) => model.copy(snapshot = snapshot).tui
        case Msg.Quit => Tui(model, Cmd.Exit)
        case Msg.KeyError(_) => model.tui
        case Msg.Key(k) =>
          k match
            case InputKey.CharKey('q') => Tui(model, Cmd.Exit)
            case InputKey.Ctrl('c') => Tui(model, Cmd.Exit)
            case other =>
              // Scroll keys adjust the active-pane viewport; every other key is recorded (last-key indicator) but inert.
              val scrolled = scrollDelta(other) match
                case Some(delta) => model.copy(scrollBack = clampScrollBack(model.scrollBack + delta, model.snapshot))
                case None => model
              scrolled.copy(lastKey = Some(other.toString)).tui

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

      val av = activeView(model)
      val activeHeader = truncate(s"ACTIVE — ${paneLabel(s.activePane)}${scrollIndicator(av)}", ActiveInnerWidth)
      val activeBox = BoxNode(
        38.x,
        2.y,
        width = 42,
        height = 16,
        children = TextNode(ActiveInnerCol.x, 3.y, List(activeHeader.text(Style(bold = true)))) ::
          activeLineNodes(av),
        style = Style(border = true)
      )

      val footer =
        TextNode(2.x, 19.y, List("q quit · ↑↓ PgUp/PgDn scroll · :q command".text(Style(dim = true))))

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

    /** Map a [[reload]] result onto the command bus: a fresh snapshot re-renders via [[Msg.Refreshed]]; `None` (no
      * readable manifest, e.g. the feature was abandoned mid-session) is a no-op that keeps the last good frame.
      */
    private def refreshCmd(loaded: Option[TuiSnapshot]): Cmd[Msg] =
      loaded match
        case Some(snapshot) => Cmd.GCmd(Msg.Refreshed(snapshot))
        case None => Cmd.NoCmd

    private def statusLine(row: Int, label: String, value: String): VNode =
      TextNode(StatusInnerCol.x, row.y, List(truncate(s"$label$value", StatusInnerWidth).text))

    private def activeLineNodes(av: ActiveView): List[VNode] =
      av.lines.toList.zipWithIndex.map { case (text, i) =>
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

  /** The slice of active-pane lines currently visible, plus how many are hidden above / below the viewport. */
  private final case class ActiveView(lines: Vector[String], hiddenAbove: Int, hiddenBelow: Int):
    /** True when the lines overflow the viewport — i.e. scrolling can change what's shown. */
    def overflow: Boolean = hiddenAbove > 0 || hiddenBelow > 0

  /** The largest meaningful `scrollBack` for `total` lines: scrolling further would push the first line off the top of
    * the viewport with nothing to reveal, so it is capped at "first line at the top".
    */
  private def maxScrollBack(total: Int): Int = math.max(0, total - ActiveRows)

  /** Clamp a candidate `scrollBack` to `[0, maxScrollBack]` for the snapshot's active-line count. */
  private def clampScrollBack(back: Int, snapshot: TuiSnapshot): Int =
    math.max(0, math.min(back, maxScrollBack(snapshot.activeLines.size)))

  /** The per-key viewport delta: `ArrowUp`/`ArrowDown` move one line, `PageUp`/`PageDown` a full viewport. Positive
    * scrolls back into history (older lines); negative scrolls toward the tail. `None` for non-scroll keys.
    */
  private def scrollDelta(k: InputKey): Option[Int] = k match
    case InputKey.ArrowUp => Some(1)
    case InputKey.ArrowDown => Some(-1)
    case InputKey.PageUp => Some(ActiveRows)
    case InputKey.PageDown => Some(-ActiveRows)
    case _ => None

  /** Project the model's active lines + scroll position onto the viewport. Falls back to a single placeholder line when
    * there are no lines yet (which never overflows, so scroll keys are inert there).
    */
  private def activeView(model: Model): ActiveView =
    val s = model.snapshot
    val lines = if s.activeLines.nonEmpty then s.activeLines else Vector(placeholderFor(s.activePane))
    val total = lines.size
    val back = clampScrollBack(model.scrollBack, s)
    val bottom = total - back
    val top = math.max(0, bottom - ActiveRows)
    ActiveView(lines = lines.slice(top, bottom), hiddenAbove = top, hiddenBelow = back)

  /** A compact scroll position for the active-pane header: nothing when the lines fit, `[↑a ↓b]` otherwise (`b == 0`
    * means following the tail).
    */
  private def scrollIndicator(av: ActiveView): String =
    if av.overflow then s"  [↑${av.hiddenAbove} ↓${av.hiddenBelow}]" else ""
