package io.forge.tui

import termflow.tui.*
import termflow.tui.TuiPrelude.*
import termflow.tui.Tui.tui
import termflow.tui.KeyDecoder.InputKey

import scala.concurrent.Future

/** Slice 4.4 Task 4.4.1 — the Forge **cockpit**: a multi-workstream / multi-worker dashboard that attaches to a running
  * daemon and renders its whole fleet (peer to the Slice-2.1 single-feature [[ForgeTui]], not a replacement).
  *
  * An Elm-architecture (`termflow.tui.TuiApp`) app over a [[CockpitSnapshot]]: a summary header (instance · boot ·
  * fleet counts · committed/outstanding spend) and a scrollable fleet list (each workstream with its goal/state/spend,
  * then its workers with status + liveness + an attention badge; unassigned workers under a trailing group). The
  * snapshot is refreshed by polling: `run(initial, reload)` (what `forge cockpit` calls) re-calls the daemon `status`
  * RPC on each 1s `Sub.Every` tick. The live B3 `subscribe` feed is Task 4.4.2; per-worker drill-down + control actions
  * are Tasks 4.4.3/4.4.4.
  *
  * The cockpit is a **client** (§6.3.1): it never writes the instance log — quitting it leaves the daemon + every
  * worker running. Tested headless via `termflow.testkit.TuiTestDriver` ([[ForgeCockpitAppSuite]]).
  */
object ForgeCockpit:

  /** The cockpit model: the rendered fleet snapshot plus the tick counter, the top-anchored scroll offset, and the
    * terminal dimensions. `scrollTop` is the index of the first fleet line shown (0 = top); a list, unlike the log-tail
    * pane, reads best anchored at the top, and the offset is clamped to the scrollable range at render time.
    */
  final case class Model(
      snapshot: CockpitSnapshot,
      ticks: Long,
      scrollTop: Int = 0,
      width: Int = MinFrameWidth,
      height: Int = MinFrameHeight,
      showHelp: Boolean = false
  )

  enum Msg:
    /** 1s heartbeat from `Sub.Every` — bumps the liveness counter and kicks off a [[reload]] of the daemon status. */
    case Tick

    /** A freshly fetched fleet snapshot from the [[reload]] poll — replaces the rendered snapshot in place. */
    case Refreshed(snapshot: CockpitSnapshot)

    /** A decoded key event from `Sub.InputKey`. */
    case Key(k: InputKey)

    /** Terminal dimensions changed; the next frame reflows. */
    case Resize(width: Int, height: Int)

    /** An input-source error forwarded from `Sub.InputKey` — surfaced, not fatal. */
    case KeyError(t: Throwable)

    /** Quit (from the `quit` / `:q` prompt command). */
    case Quit

  /** Default reload — no live data source, so the snapshot supplied at construction never changes (the static
    * `run(snapshot)` entry + the headless app tests). `forge cockpit` passes a real daemon `status` re-fetch.
    */
  private val NoReload: () => Future[Option[CockpitSnapshot]] = () => Future.successful(None)

  private val MinFrameWidth = 64
  private val MinFrameHeight = 16

  private val Theme0: Theme =
    Theme.dark.copy(
      primary = Color.Cyan,
      secondary = Color.Blue,
      border = Color.BrightBlack,
      chars = BorderChars.rounded
    )

  /** Run the cockpit against a static `snapshot` (no live refresh). Blocks until the user quits. */
  def run(snapshot: CockpitSnapshot): Unit =
    val _ = TuiRuntime.run(new App(snapshot))

  /** Run the cockpit starting from `initial`, re-fetching the fleet from `reload` on each 1s tick. `forge cockpit`
    * passes a daemon `status` re-fetch here; `reload` returning `None` leaves the current snapshot in place. Blocks
    * until the user quits.
    */
  def run(initial: CockpitSnapshot, reload: () => Future[Option[CockpitSnapshot]]): Unit =
    val _ = TuiRuntime.run(new App(initial, reload))

  final class App(initial: CockpitSnapshot, reload: () => Future[Option[CockpitSnapshot]] = NoReload)
      extends TuiApp[Model, Msg]:

    def init(ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      // Each Sub.* factory auto-registers with the RuntimeCtx, so constructing them is enough (mirrors ForgeTui.init).
      val _ = Sub.Every(1000L, () => Msg.Tick, ctx)
      val _ = Sub.InputKey(k => Msg.Key(k), t => Msg.KeyError(t), ctx)
      val _ = Sub.TerminalResize(250L, Msg.Resize.apply, ctx)
      Model(initial, 0L, width = ctx.terminal.width, height = ctx.terminal.height).tui

    def update(model: Model, msg: Msg, ctx: RuntimeCtx[Msg]): Tui[Model, Msg] =
      msg match
        case Msg.Tick =>
          Tui(model.copy(ticks = model.ticks + 1), Cmd.FCmd(reload(), refreshCmd))
        case Msg.Refreshed(snapshot) =>
          model.copy(snapshot = snapshot, scrollTop = clampScrollTop(model.scrollTop, snapshot, model)).tui
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
              scrollDelta(other, model) match
                case Some(delta) =>
                  model.copy(scrollTop = clampScrollTop(model.scrollTop + delta, model.snapshot, model)).tui
                case None => model.tui

    def view(model: Model): RootNode =
      val s = model.snapshot
      val m = FrameMetrics.forModel(model)
      given Theme = Theme0

      val title =
        TextNode(
          2.x,
          1.y,
          List(
            truncate(s"Forge cockpit — instance '${s.instanceName}'  [boot ${s.bootCount}]", m.width - 3)
              .text(Style(fg = Theme0.primary, bold = true))
          )
        )

      val summary =
        TextNode(
          2.x,
          2.y,
          List(truncate(summaryLine(s), m.width - 3).text(Style(fg = Theme0.secondary)))
        )

      val lines = fleetLines(s)
      val shown = sliceForViewport(lines, model, m)
      val header = truncate(s"FLEET${scrollIndicator(lines.size, model, m)}", m.innerWidth)
      val box = BoxNode(
        1.x,
        1.y,
        width = m.width,
        height = m.boxHeight,
        children = TextNode(3.x, 2.y, List(header.text(Style(fg = Theme0.primary, bold = true)))) ::
          fleetLineNodes(shown, m),
        style = Style(border = true, fg = Theme0.border),
        chars = Theme0.chars
      )
      val body = Layout.row(gap = 0)(box).resolve(Coord(1.x, 3.y))

      val footer =
        TextNode(
          2.x,
          m.height.y,
          List(truncate("q quit · ↑↓ PgUp/PgDn scroll · ? help · :q command", m.width - 3).text(Style(dim = true)))
        )

      RootNode(
        width = m.width,
        height = m.height,
        children = title :: summary :: body ::: List(footer),
        input = None,
        overlays = if model.showHelp then List(helpOverlay()) else Nil
      )

    def toMsg(input: PromptLine): Result[Msg] =
      input.value.trim.toLowerCase match
        case "q" | "quit" | ":q" => Right(Msg.Quit)
        case _ => Left(TermFlowError.CommandError(input.value))

    private def refreshCmd(loaded: Option[CockpitSnapshot]): Cmd[Msg] =
      loaded match
        case Some(snapshot) => Cmd.GCmd(Msg.Refreshed(snapshot))
        case None => Cmd.NoCmd

    private def fleetLineNodes(lines: Vector[String], m: FrameMetrics): List[VNode] =
      lines.toList.zipWithIndex.map { case (text, i) =>
        TextNode(3.x, (m.firstLine + i).y, List(truncate(text, m.innerWidth).text))
      }

  // --- pure rendering helpers (shared, testable without a terminal) ------------

  /** The fleet flattened to render lines: a row per workstream, then an indented row per worker (with an attention
    * marker), and a trailing "(unassigned)" group for workstreamless workers. The single-list shape keeps scroll +
    * golden tests simple; per-worker selection/drill-down is Task 4.4.3.
    */
  private[tui] def fleetLines(s: CockpitSnapshot): Vector[String] =
    val wsLines = s.workstreams.flatMap { ws =>
      val head =
        f"▸ ${ws.id}  [${ws.status}]  $$${ws.committedUsd}%.2f / $$${ws.outstandingUsd}%.2f  ${quoted(ws.goal)}"
      head +: ws.workers.map(workerLine)
    }
    val loose =
      if s.looseWorkers.isEmpty then Vector.empty
      else "▸ (unassigned)" +: s.looseWorkers.map(workerLine)
    val all = wsLines ++ loose
    if all.isEmpty then Vector("(no workstreams or workers — spawn one with `forge workstream spawn`)") else all

  private def workerLine(w: CockpitWorker): String =
    val marker = w.attentionReason match
      case Some(_) => "⚠"
      case None => if w.live then "●" else "○"
    val flag = w.attentionReason.map(r => s"  ← $r").getOrElse("")
    s"    $marker ${w.workerId}  ${w.feature}  ${w.status}  ${w.liveness}$flag"

  private def summaryLine(s: CockpitSnapshot): String =
    val attention = if s.attentionCount > 0 then s" · ⚠ ${s.attentionCount} need a human" else ""
    f"${s.workstreams.size} workstream(s) · ${s.workerCount} worker(s) (${s.liveCount} live)$attention · " +
      f"committed $$${s.committedUsd}%.2f · outstanding $$${s.outstandingUsd}%.2f"

  private def quoted(goal: String): String = if goal.isEmpty then "" else s"\"$goal\""

  private def truncate(s: String, n: Int): String =
    if s.length <= n then s else s.take(math.max(0, n - 1)) + "…"

  private def sliceForViewport(lines: Vector[String], model: Model, m: FrameMetrics): Vector[String] =
    val top = clampScrollTop(model.scrollTop, model.snapshot, model)
    lines.slice(top, top + m.rows)

  private def maxScrollTop(total: Int, rows: Int): Int = math.max(0, total - rows)

  private def clampScrollTop(top: Int, snapshot: CockpitSnapshot, model: Model): Int =
    val m = FrameMetrics.forModel(model)
    math.max(0, math.min(top, maxScrollTop(fleetLines(snapshot).size, m.rows)))

  private def scrollDelta(k: InputKey, model: Model): Option[Int] = k match
    case InputKey.ArrowDown => Some(1)
    case InputKey.ArrowUp => Some(-1)
    case InputKey.PageDown => Some(FrameMetrics.forModel(model).rows)
    case InputKey.PageUp => Some(-FrameMetrics.forModel(model).rows)
    case _ => None

  private def scrollIndicator(total: Int, model: Model, m: FrameMetrics): String =
    val top = clampScrollTop(model.scrollTop, model.snapshot, model)
    val below = math.max(0, total - (top + m.rows))
    if top > 0 || below > 0 then s"  [↑$top ↓$below]" else ""

  private final case class FrameMetrics(
      width: Int,
      height: Int,
      boxHeight: Int,
      innerWidth: Int,
      firstLine: Int,
      rows: Int
  )

  private object FrameMetrics:
    def forModel(model: Model): FrameMetrics =
      val width = math.max(MinFrameWidth, model.width)
      val height = math.max(MinFrameHeight, model.height)
      // Title (row 1) + summary (row 2) sit above the box, which starts at row 3 and leaves the footer row free.
      val boxHeight = math.max(8, height - 3)
      val firstLine = 4
      val rows = math.max(1, boxHeight - firstLine - 1)
      FrameMetrics(
        width = width,
        height = height,
        boxHeight = boxHeight,
        innerWidth = width - 4,
        firstLine = firstLine,
        rows = rows
      )

  private def helpOverlay()(using theme: Theme): Overlay =
    val rows = Vector(
      "q / Ctrl-C       quit (detach — daemon keeps running)",
      "ArrowUp/Down     scroll one line",
      "PageUp/PageDown  scroll one page",
      "? / Esc          toggle this help",
      ":q               prompt command quit"
    )
    val width = 52
    val height = rows.size + 4
    val box = Theme.box(1.x, 1.y, width, height)
    val title = TextNode(3.x, 1.y, List(" Forge cockpit keys ".text(Style(fg = theme.primary, bold = true))))
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
