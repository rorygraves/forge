package io.forge.tui

import termflow.tui.Cmd
import termflow.tui.KeyDecoder.InputKey
import termflow.tui.TuiPrelude.PromptLine
import termflow.testkit.{GoldenFrame, TuiTestDriver}

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
