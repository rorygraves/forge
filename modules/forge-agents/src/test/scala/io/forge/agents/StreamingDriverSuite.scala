package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import scala.concurrent.duration.*

class StreamingDriverSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 30.seconds

  /** Spawn a "fake CLI" = `/bin/sh -c "cat <fixture>"`. The shell prints the fixture's lines on stdout exactly the way
    * a real CLI would, then exits. Lets us test the full pipe (Subprocess + parser + Channel + init synchroniser)
    * against the actual transcript shapes Slice 0 captured.
    */
  private def fakeClaude(transcriptPath: os.Path): cats.effect.kernel.Resource[IO, Subprocess] =
    Subprocess.spawn(List("/bin/sh", "-c", s"cat ${transcriptPath.toString.replace("'", "\\'")}"))

  private val claudeHeadless = os.pwd / "docs" / "slice-0" / "transcripts" / "01-claude-headless.jsonl"
  private val codexHeadless = os.pwd / "docs" / "slice-0" / "transcripts" / "04-codex-headless.jsonl"

  test("Claude transcript replay: Init arrives, events flow, session ends cleanly"):
    assume(os.exists(claudeHeadless), "Slice 0 transcript not present")
    val program = StreamingDriver
      .fromSubprocess(fakeClaude(claudeHeadless), ClaudeEventParser.parse)
      .flatMap: session =>
        for
          allEvents <- session.events.compile.toVector
          _ <- session.close()
        yield (session.sessionId, allEvents)
    val (sid, events) = program.unsafeRunSync()
    assertEquals(sid, "742b7777-637f-453b-be6c-65bd1cd9eee1")
    // Init + assistant text + cost + result (hook events skipped, rate_limit skipped).
    assertEquals(events.head, AgentEvent.Init(sid))
    assert(events.exists(_.isInstanceOf[AgentEvent.AssistantText]))
    assert(events.exists(_.isInstanceOf[AgentEvent.CostUpdate]))
    assert(events.last.isInstanceOf[AgentEvent.Result])

  test("Codex transcript replay: Init arrives, events flow, session ends cleanly"):
    assume(os.exists(codexHeadless), "Slice 0 transcript not present")
    // The fixture mixes a stderr line into the front of the file; the parser drops it as a parse error, the driver
    // routes that into the stderr buffer rather than failing the stream — exactly the production pattern.
    val parser = CodexEventParser.empty("gpt-5-codex")
    val program = StreamingDriver
      .fromSubprocess(fakeClaude(codexHeadless), parser.parse)
      .flatMap: session =>
        for
          allEvents <- session.events.compile.toVector
          stderr <- session.stderrSnapshot
          _ <- session.close()
        yield (session.sessionId, allEvents, stderr)
    val (sid, events, stderr) = program.unsafeRunSync()
    assertEquals(sid, "019e5e5a-bb77-7f21-8ed3-82fcbb7f037d")
    assertEquals(events.head, AgentEvent.Init(sid))
    assert(events.exists(_.isInstanceOf[AgentEvent.AssistantText]))
    assert(events.last.isInstanceOf[AgentEvent.Result])
    // Parser-level error for the "Reading additional input from stdin..." line was logged into the stderr buffer.
    assert(stderr.exists(_.contains("parse error")), clue = stderr)

  test("InitTimedOut: subprocess produces no Init within the configured timeout"):
    assume(os.exists(os.Path("/bin/sh")))
    // A process that emits no JSON for longer than the timeout.
    val sp = Subprocess.spawn(List("/bin/sh", "-c", "sleep 10"))
    val program =
      StreamingDriver.fromSubprocess(sp, ClaudeEventParser.parse, initTimeout = 500.millis).attempt
    val result = program.unsafeRunSync()
    assert(result.isLeft, clue = result)
    assert(
      result.left.exists(_.getMessage.contains("no Init event within")),
      clue = result.left
    )

  test("NoInitBeforeExit: subprocess exits before producing any Init"):
    assume(os.exists(os.Path("/bin/sh")))
    val sp = Subprocess.spawn(List("/bin/sh", "-c", "echo 'not json' && exit 0"))
    val program = StreamingDriver.fromSubprocess(sp, ClaudeEventParser.parse).attempt
    val result = program.unsafeRunSync()
    assert(result.isLeft, clue = result)
    assert(
      result.left.exists(_.getMessage.contains("subprocess exited before first Init event")),
      clue = result.left
    )

  test("kill() returns even when the subprocess is still producing output"):
    assume(os.exists(os.Path("/bin/sh")))
    // Emit Init, then keep producing junk forever — exercises the kill path on a live session.
    val script =
      """echo '{"type":"system","subtype":"init","session_id":"abc"}'
        |while true; do echo noise; sleep 0.1; done""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val program = StreamingDriver
      .fromSubprocess(sp, ClaudeEventParser.parse)
      .flatMap: session =>
        for
          _ <- IO.sleep(200.millis) // let some noise flow
          _ <- session.kill()
          exit <- session.exitValue
        yield exit
    val exit = program.unsafeRunSync()
    // SIGTERM on a normal shell → 143 (128 + 15) or, if the subprocess exec'd into something simpler, may be 0.
    assert(exit == 143 || exit == 137 || exit == 0, clue = exit)

  test("parse-error lines do not crash the stream; subsequent valid lines still flow"):
    assume(os.exists(os.Path("/bin/sh")))
    val script =
      """echo 'not json at all'
        |echo '{"type":"system","subtype":"init","session_id":"sid-1"}'
        |echo 'garbage too'
        |echo '{"type":"assistant","message":{"content":[{"type":"text","text":"after garbage"}],"usage":{"output_tokens":1}}}'
        |echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"session_id":"sid-1"}'""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val program = StreamingDriver
      .fromSubprocess(sp, ClaudeEventParser.parse)
      .flatMap: session =>
        for
          events <- session.events.compile.toVector
          stderr <- session.stderrSnapshot
          _ <- session.close()
        yield (events, stderr)
    val (events, stderr) = program.unsafeRunSync()
    assert(events.exists { case AgentEvent.AssistantText(t, _) => t == "after garbage"; case _ => false })
    assert(stderr.exists(_.contains("parse error")), clue = stderr)
