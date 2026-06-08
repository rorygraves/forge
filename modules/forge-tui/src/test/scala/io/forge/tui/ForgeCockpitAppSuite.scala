package io.forge.tui

import termflow.tui.KeyDecoder.InputKey
import termflow.testkit.{GoldenFrame, TuiTestDriver}

/** Slice 4.4 Task 4.4.1 coverage: drives [[ForgeCockpit.App]] through the headless `TuiTestDriver` (no real terminal)
  * and asserts the rendered fleet frame + the Elm transitions, the `ForgeTuiAppSuite` idiom.
  */
class ForgeCockpitAppSuite extends munit.FunSuite:

  private val sample = CockpitSnapshot(
    instanceName = "llm4s",
    bootCount = 2,
    committedUsd = 12.50,
    outstandingUsd = 30.0,
    workstreams = Vector(
      CockpitWorkstream(
        id = "ws-1",
        goal = "ship the adventure generator",
        status = "Active",
        committedUsd = 12.50,
        outstandingUsd = 30.0,
        workers = Vector(
          CockpitWorker(
            "w-1",
            "/repos/szork",
            "adventure-gen",
            "PieceImplementing",
            live = true,
            "live(pid=123)",
            42,
            None
          ),
          CockpitWorker(
            "w-2",
            "/repos/toast",
            "retry-config",
            "NeedsHumanIntervention",
            live = true,
            "live(container=abcdef012345)",
            7,
            Some("needs-human-intervention")
          )
        )
      )
    ),
    looseWorkers = Vector(
      CockpitWorker("w-9", "/repos/loose", "solo", "FeatureDone", live = false, "exited(0)", 3, None)
    )
  )

  private def driver(snapshot: CockpitSnapshot = sample): TuiTestDriver[ForgeCockpit.Model, ForgeCockpit.Msg] =
    val d = TuiTestDriver(new ForgeCockpit.App(snapshot), width = 100, height = 24)
    d.init()
    d

  private def text(d: TuiTestDriver[ForgeCockpit.Model, ForgeCockpit.Msg]): String =
    GoldenFrame.serialize(d.frame)

  test("renders the instance header and spend summary"):
    val frame = text(driver())
    assert(frame.contains("Forge cockpit — instance 'llm4s'"), frame)
    assert(frame.contains("[boot 2]"), frame)
    assert(frame.contains("1 workstream(s)"), frame)
    assert(frame.contains("3 worker(s) (2 live)"), frame)
    assert(frame.contains("committed $12.50"), frame)

  test("renders a workstream row with its goal and state"):
    val frame = text(driver())
    assert(frame.contains("ws-1"), frame)
    assert(frame.contains("[Active]"), frame)
    assert(frame.contains("ship the adventure generator"), frame)

  test("renders worker rows with status and liveness"):
    val frame = text(driver())
    assert(frame.contains("w-1"), frame)
    assert(frame.contains("PieceImplementing"), frame)
    assert(frame.contains("live(pid=123)"), frame)

  test("flags a worker needing a human"):
    val frame = text(driver())
    assert(frame.contains("w-2"), frame)
    assert(frame.contains("needs-human"), frame)
    assert(frame.contains("need a human"), frame) // the summary aggregate

  test("renders unassigned workers under a group"):
    val frame = text(driver())
    assert(frame.contains("(unassigned)"), frame)
    assert(frame.contains("w-9"), frame)

  test("empty fleet shows a hint"):
    val frame = text(driver(CockpitSnapshot.loading("llm4s")))
    assert(frame.contains("no workstreams or workers"), frame)

  test("q quits the cockpit"):
    val d = driver()
    d.send(ForgeCockpit.Msg.Key(InputKey.CharKey('q')))
    assert(d.exited, "expected the app to exit on 'q'")

  test("? toggles the help overlay"):
    val d = driver()
    d.send(ForgeCockpit.Msg.Key(InputKey.CharKey('?')))
    val frame = text(d)
    assert(frame.contains("Forge cockpit keys"), frame)

  test("frame is the declared 100×24 surface"):
    val f = driver().frame
    assertEquals(f.width, 100)
    assertEquals(f.height, 24)
