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

  test("send() emits a UserMessage mirror event BEFORE writing to stdin, then encodes via the callback"):
    assume(os.exists(os.Path("/bin/sh")))
    // Fake CLI that emits Init, then echoes each stdin line back as an assistant text event so we can observe both
    // the wire shape of `send` and the order of events. The fake parser turns each echoed line into a flag we can
    // inspect from the test.
    val script =
      """|echo '{"type":"system","subtype":"init","session_id":"sid-x"}'
         |while IFS= read -r line; do
         |  echo "$line"
         |done""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    // Custom encoder: wrap in <<<text>>> so we can assert the encoded form reaches stdin (and thus the echo loop).
    val encoder: String => String = t => s"<<<$t>>>"
    val recorder: String => Either[String, Vector[AgentEvent]] = line =>
      ClaudeEventParser.parse(line) match
        case r @ Right(events) if events.nonEmpty => r
        case _ =>
          // Treat unparseable echoed lines as AssistantText so we can read the wire form back.
          Right(Vector(AgentEvent.AssistantText(line, 0)))
    val program = StreamingDriver
      .fromSubprocess(sp, recorder, encodeUserInput = encoder)
      .flatMap: session =>
        for
          _ <- session.send("hello")
          _ <- session.send("world")
          _ <- session.close()
          events <- session.events.compile.toVector
        yield events
    val events = program.unsafeRunSync()

    // UserMessage mirror events appear in order, ahead of the corresponding echoed AssistantText events.
    val userMessages = events.collect { case AgentEvent.UserMessage(s) => s }
    assertEquals(userMessages, Vector("hello", "world"))

    // Echoed lines (encoded form) appear as AssistantText after each send.
    val texts = events.collect { case AgentEvent.AssistantText(t, _) => t }
    assert(texts.contains("<<<hello>>>"), clue = events)
    assert(texts.contains("<<<world>>>"), clue = events)

    // Ordering: each UserMessage("X") precedes the echoed AssistantText("<<<X>>>").
    val helloUserIdx = events.indexOf(AgentEvent.UserMessage("hello"))
    val helloEchoIdx = events.indexWhere {
      case AgentEvent.AssistantText("<<<hello>>>", _) => true; case _ => false
    }
    assert(helloUserIdx >= 0 && helloEchoIdx >= 0, clue = events)
    assert(helloUserIdx < helloEchoIdx, clue = (helloUserIdx, helloEchoIdx, events))

  test("initialUserInput: written to stdin before Init; mirrored as UserMessage AFTER Init in the event stream"):
    assume(os.exists(os.Path("/bin/sh")))
    // Fake CLI that reads ONE line from stdin, then emits Init, then echoes the line as an assistant text, then a
    // result. Models the Claude `-p --input-format stream-json` contract: init only after the first user-message
    // frame arrives.
    val script =
      """|IFS= read -r line
         |echo '{"type":"system","subtype":"init","session_id":"sid-init"}'
         |echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"got: $line\"}]}}"
         |echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"session_id":"sid-init"}'""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val program = StreamingDriver
      .fromSubprocess(
        sp,
        ClaudeEventParser.parse,
        encodeUserInput = t => s"<<$t>>",
        initialUserInput = Some("hi-there")
      )
      .flatMap: session =>
        for
          events <- session.events.compile.toVector
          _ <- session.close()
        yield (session.sessionId, events)
    val (sid, events) = program.unsafeRunSync()
    assertEquals(sid, "sid-init")
    // Init is first in the stream — channel-order contract preserved even though initial input was written first to
    // stdin.
    assertEquals(events.headOption, Some(AgentEvent.Init("sid-init")))
    // Mirror UserMessage for the initial input appears after Init.
    val userIdx = events.indexOf(AgentEvent.UserMessage("hi-there"))
    assert(userIdx > 0, clue = events)
    // The fake CLI saw the *encoded* form on stdin (so it actually went through encodeUserInput, not raw).
    assert(
      events.exists { case AgentEvent.AssistantText(t, _) => t == "got: <<hi-there>>"; case _ => false },
      clue = events
    )

  test("answerQuestion: when encodeAnswer is Some, encoded frame is written and a mirror UserMessage [answer] lands"):
    assume(os.exists(os.Path("/bin/sh")))
    val script =
      """|echo '{"type":"system","subtype":"init","session_id":"sid-q"}'
         |while IFS= read -r line; do
         |  echo "$line"
         |done""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val recorder: String => Either[String, Vector[AgentEvent]] = line =>
      ClaudeEventParser.parse(line) match
        case r @ Right(events) if events.nonEmpty => r
        case _ => Right(Vector(AgentEvent.AssistantText(line, 0)))
    // Encoder that requires Some(id): returns a fake `tool_result` frame on Some, errors on None — modelling the
    // Claude contract from §7.1.
    val encode: (Option[String], String) => IO[String] = {
      case (Some(id), ans) => IO.pure(s"TR[$id]=$ans")
      case (None, _) => IO.raiseError(RuntimeException("answerQuestion called with None toolUseId on Claude path"))
    }
    val program = StreamingDriver
      .fromSubprocess(sp, recorder, encodeAnswer = Some(encode))
      .flatMap: session =>
        for
          _ <- session.answerQuestion(Some("toolu_42"), "yes please")
          _ <- session.close()
          events <- session.events.compile.toVector
        yield events
    val events = program.unsafeRunSync()
    // Mirror event with the [answer] prefix.
    val answerMirror = events.collectFirst { case AgentEvent.UserMessage(s) if s.startsWith("[answer]") => s }
    assertEquals(answerMirror, Some("[answer] yes please"))
    // Encoded frame was echoed back by the fake CLI.
    assert(
      events.exists { case AgentEvent.AssistantText(t, _) => t == "TR[toolu_42]=yes please"; case _ => false },
      clue = events
    )

  test("answerQuestion: encoder failure surfaces as the IO error (no mirror, no stdin write)"):
    assume(os.exists(os.Path("/bin/sh")))
    val script =
      """|echo '{"type":"system","subtype":"init","session_id":"sid-q"}'
         |sleep 1""".stripMargin
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val encode: (Option[String], String) => IO[String] = (_, _) => IO.raiseError(RuntimeException("encoder rejected"))
    val program = StreamingDriver
      .fromSubprocess(sp, ClaudeEventParser.parse, encodeAnswer = Some(encode))
      .flatMap: session =>
        session.answerQuestion(None, "shouldnt matter").attempt.flatTap(_ => session.kill())
    val result = program.unsafeRunSync()
    assert(result.left.exists(_.getMessage.contains("encoder rejected")), clue = result)

  test("answerQuestion: with encodeAnswer=None (default) raises NotImplementedError"):
    assume(os.exists(os.Path("/bin/sh")))
    val script = """echo '{"type":"system","subtype":"init","session_id":"sid-x"}' && sleep 1"""
    val sp = Subprocess.spawn(List("/bin/sh", "-c", script))
    val program = StreamingDriver
      .fromSubprocess(sp, ClaudeEventParser.parse)
      .flatMap: session =>
        session.answerQuestion(Some("toolu_1"), "ans").attempt.flatTap(_ => session.kill())
    val result = program.unsafeRunSync()
    assert(result.left.exists(_.isInstanceOf[NotImplementedError]), clue = result)
    assert(result.left.exists(_.getMessage.contains("encodeAnswer hook")), clue = result)
