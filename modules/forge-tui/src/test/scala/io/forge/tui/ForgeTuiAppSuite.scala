package io.forge.tui

import termflow.tui.Cmd
import termflow.tui.KeyDecoder.InputKey
import termflow.tui.TuiPrelude.PromptLine
import termflow.testkit.{GoldenFrame, TuiTestDriver}

import scala.concurrent.Future

/** Slice 2.1 first-runnable-slice coverage: drives [[ForgeTui.App]] through the headless `TuiTestDriver` (no real
  * terminal) and asserts the rendered frame and the Elm transitions.
  *
  * The driver renders `app.view(model)` to a cell matrix; `GoldenFrame.serialize` flattens it to character rows we
  * substring-match against — robust to cosmetic style churn (style is intentionally dropped by the serializer) while
  * still proving the panes paint the snapshot.
  */
class ForgeTuiAppSuite extends munit.FunSuite:

  private val sample = TuiSnapshot(
    featureId = "image-creds-dedup",
    title = "Dedup image credential lookup",
    mode = "auto",
    stateLabel = "implementing piece p3",
    pieceLabel = "p3 — extract credential resolver",
    lastAction = "session.complete @ 2026-06-01T10:00:00Z",
    budgetLine = "feature $4.20 / $20.00 · piece $1.10 / $5.00",
    activePane = ActivePane.Streaming,
    activeLines = Vector("> resolving credentials…", "edited CredentialResolver.scala")
  )

  private def driver(snapshot: TuiSnapshot = sample): TuiTestDriver[ForgeTui.Model, ForgeTui.Msg] =
    val d = TuiTestDriver(new ForgeTui.App(snapshot), width = 80, height = 20)
    d.init()
    d

  private def text(d: TuiTestDriver[ForgeTui.Model, ForgeTui.Msg]): String =
    GoldenFrame.serialize(d.frame)

  test("renders the status pane from the snapshot"):
    val frame = text(driver())
    assert(frame.contains("Forge — image-creds-dedup"), frame)
    assert(frame.contains("STATUS"), frame)
    assert(frame.contains("state:  implementing piece p3"), frame)
    assert(frame.contains("budget: feature $4.20"), frame)

  test("renders the active pane label and lines"):
    val frame = text(driver())
    assert(frame.contains("ACTIVE — streaming"), frame)
    assert(frame.contains("resolving credentials"), frame)

  test("idle active pane shows a placeholder when there are no lines"):
    val frame = text(driver(sample.copy(activePane = ActivePane.Idle, activeLines = Vector.empty)))
    assert(frame.contains("ACTIVE — idle"), frame)
    assert(frame.contains("idle — nothing running"), frame)

  test("frame is the declared 80×20 surface"):
    val f = driver().frame
    assertEquals(f.width, 80)
    assertEquals(f.height, 20)

  test("Tick increments the liveness counter without exiting"):
    val d = driver()
    d.send(ForgeTui.Msg.Tick)
    d.send(ForgeTui.Msg.Tick)
    assertEquals(d.model.ticks, 2L)
    assert(!d.exited)

  test("'q' key exits"):
    val d = driver()
    d.send(ForgeTui.Msg.Key(InputKey.CharKey('q')))
    assert(d.exited)
    assert(d.cmds.contains(Cmd.Exit))

  test("Ctrl-C exits"):
    val d = driver()
    d.send(ForgeTui.Msg.Key(InputKey.Ctrl('c')))
    assert(d.exited)

  test("a non-quit key is recorded, not fatal"):
    val d = driver()
    d.send(ForgeTui.Msg.Key(InputKey.CharKey('x')))
    assert(!d.exited)
    assertEquals(d.model.lastKey, Some(InputKey.CharKey('x').toString))

  test("toMsg parses the quit command and rejects unknown input"):
    val app = new ForgeTui.App(sample)
    assertEquals(app.toMsg(PromptLine("quit")), Right(ForgeTui.Msg.Quit))
    assertEquals(app.toMsg(PromptLine(":q")), Right(ForgeTui.Msg.Quit))
    assert(app.toMsg(PromptLine("nonsense")).isLeft)

  // ── Task 2.1.4 — scrollable action-log pane ───────────────────────────────────────────────────────────────────────
  //
  // 20 log lines ("line 01".."line 20") over a 12-row active viewport at the default 80×20 test size. The labels are
  // zero-padded so no label is a substring of another (e.g. "line 01" ⊄ "line 10").

  private val scrollable = sample.copy(
    activePane = ActivePane.LogTail,
    activeLines = (1 to 20).map(i => f"line $i%02d").toVector
  )

  test("active pane follows the tail by default — newest lines shown, oldest hidden"):
    val frame = text(driver(scrollable))
    assert(frame.contains("line 20"), frame) // last line visible
    assert(frame.contains("line 09"), frame) // top of the 12-row tail window
    assert(!frame.contains("line 08"), frame) // line 08 is scrolled off the top
    assert(!frame.contains("line 01"), frame)
    assert(frame.contains("[↑8 ↓0]"), frame) // 8 hidden above, 0 below = following the tail

  test("ArrowUp scrolls one line back into history"):
    val d = driver(scrollable)
    d.send(ForgeTui.Msg.Key(InputKey.ArrowUp))
    assertEquals(d.model.scrollBack, 1)
    val frame = text(d)
    assert(frame.contains("line 08"), frame) // revealed at the top
    assert(!frame.contains("line 20"), frame) // newest line scrolled off the bottom
    assert(frame.contains("[↑7 ↓1]"), frame)

  test("ArrowDown does not scroll below the tail"):
    val d = driver(scrollable)
    d.send(ForgeTui.Msg.Key(InputKey.ArrowDown))
    assertEquals(d.model.scrollBack, 0)
    assert(text(d).contains("line 20"), text(d))

  test("PageUp scrolls a full viewport and clamps at the top"):
    val d = driver(scrollable)
    d.send(ForgeTui.Msg.Key(InputKey.PageUp))
    assertEquals(d.model.scrollBack, 8) // clamped to maxScrollBack = 20 - 12
    val frame = text(d)
    assert(frame.contains("line 01"), frame) // oldest line now at the top
    assert(!frame.contains("line 13"), frame)
    assert(frame.contains("[↑0 ↓8]"), frame)
    // A further ArrowUp at the top is a no-op.
    d.send(ForgeTui.Msg.Key(InputKey.ArrowUp))
    assertEquals(d.model.scrollBack, 8)

  test("PageDown from the top returns to following the tail"):
    val d = driver(scrollable)
    d.send(ForgeTui.Msg.Key(InputKey.PageUp))
    d.send(ForgeTui.Msg.Key(InputKey.PageDown))
    assertEquals(d.model.scrollBack, 0)
    assert(text(d).contains("line 20"), text(d))

  test("no scroll indicator when the lines fit the viewport"):
    val frame = text(driver(scrollable.copy(activeLines = Vector("only one line"))))
    assert(frame.contains("ACTIVE — log tail"), frame)
    assert(!frame.contains("[↑"), frame)

  test("scroll keys are recorded as the last key (non-fatal)"):
    val d = driver(scrollable)
    d.send(ForgeTui.Msg.Key(InputKey.PageUp))
    assert(!d.exited)
    assertEquals(d.model.lastKey, Some(InputKey.PageUp.toString))

  // ── Task 2.1.3 — periodic snapshot reload ─────────────────────────────────────────────────────────────────────────
  //
  // `forge tui` re-folds the committed log on each tick via a `reload` thunk. The TuiTestDriver requires the FCmd's
  // Future to be already completed, so the tests feed `Future.successful(...)`.

  private def reloadingDriver(
      initial: TuiSnapshot,
      reload: () => Future[Option[TuiSnapshot]]
  ): TuiTestDriver[ForgeTui.Model, ForgeTui.Msg] =
    val d = TuiTestDriver(new ForgeTui.App(initial, reload), width = 80, height = 20)
    d.init()
    d

  test("Tick re-folds the snapshot via the reload thunk"):
    val refreshed = sample.copy(stateLabel = "implementing piece p4", activeLines = Vector("> next piece…"))
    val d = reloadingDriver(sample, () => Future.successful(Some(refreshed)))
    assert(text(d).contains("state:  implementing piece p3"), text(d))
    d.send(ForgeTui.Msg.Tick)
    assertEquals(d.model.snapshot, refreshed)
    assertEquals(d.model.ticks, 1L)
    assert(text(d).contains("state:  implementing piece p4"), text(d))

  test("a reload returning None leaves the current snapshot in place"):
    val d = reloadingDriver(sample, () => Future.successful(None))
    d.send(ForgeTui.Msg.Tick)
    assertEquals(d.model.snapshot, sample)
    assertEquals(d.model.ticks, 1L)
    assert(!d.exited)

  test("a reload preserves the scroll position across an append-only refresh in the same pane"):
    // Park the viewport one line back, then a refresh that appends a line (same LogTail pane) keeps the same scrollBack.
    val grown = scrollable.copy(activeLines = scrollable.activeLines :+ "line 21")
    val d = reloadingDriver(scrollable, () => Future.successful(Some(grown)))
    d.send(ForgeTui.Msg.Key(InputKey.ArrowUp))
    assertEquals(d.model.scrollBack, 1)
    d.send(ForgeTui.Msg.Tick)
    assertEquals(d.model.snapshot, grown)
    assertEquals(d.model.scrollBack, 1)

  test("a reload that changes the active pane resets scroll back to follow-tail"):
    // Parked deep in an old LogTail; the feature then moves to a Question pane (a different content stream) — the
    // viewport must not stay stranded in the old history, so scrollBack resets to 0.
    val question = scrollable.copy(
      activePane = ActivePane.Question,
      activeLines = Vector("needs human intervention:", "  ci failed", "display-only; answer via: forge resume")
    )
    val d = reloadingDriver(scrollable, () => Future.successful(Some(question)))
    d.send(ForgeTui.Msg.Key(InputKey.PageUp))
    assert(d.model.scrollBack > 0)
    d.send(ForgeTui.Msg.Tick)
    assertEquals(d.model.snapshot.activePane, ActivePane.Question)
    assertEquals(d.model.scrollBack, 0)

  // ── Task 2.1.7 — resize + help overlay ───────────────────────────────────────────────────────────────────────────

  test("Resize changes the rendered frame size and keeps panes visible"):
    val d = driver()
    d.send(ForgeTui.Msg.Resize(100, 24))
    val f = d.frame
    val frame = text(d)
    assertEquals(f.width, 100)
    assertEquals(f.height, 24)
    assert(frame.contains("STATUS"), frame)
    assert(frame.contains("ACTIVE — streaming"), frame)

  test("? toggles the key-help overlay and Escape closes it"):
    val d = driver()
    d.send(ForgeTui.Msg.Key(InputKey.CharKey('?')))
    assert(d.model.showHelp)
    assert(text(d).contains("Forge keys"), text(d))
    assert(text(d).contains("PageUp/PageDown"), text(d))
    d.send(ForgeTui.Msg.Key(InputKey.Escape))
    assert(!d.model.showHelp)
    assert(!text(d).contains("Forge keys"), text(d))
