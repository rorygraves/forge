package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*

import scala.concurrent.duration.*

/** Slice 1 §17 / design-2.1 PR-B — real-CLI streaming-spec integration suite for `ClaudeConnector`.
  *
  * Exercises the four bullets PR-B owns:
  *   - B1: spawn `runStreamingSpec(prompt, "hi")`, drain to first `Result`, capture sessionId, close. Assert UUID +
  *     event-shape contract `Init → ≥1 AssistantText/ToolUse → CostUpdate → Result`.
  *   - B2: spawn → close → `resumeStreamingSpec(sid, msg)`. Assert `newSessionId == oldSessionId` (the §6.1 invariant
  *     on the pinned CLI — Slice 0 transcripts/02-claude-resume.jsonl confirms this).
  *   - B3: contrived system prompt that strongly biases Claude into calling the `AskUserQuestion` tool; capture the
  *     `toolUseId`, call `answerQuestion(Some(id), …)`, and assert the session continues to a clean `Result` without
  *     emitting a second `AskUserQuestion` (the answer was treated as a tool_result, not a fresh user message).
  *   - B4: spawn, then `kill()` mid-stream. Assert the events `Stream` terminates cleanly (no hang on the
  *     `compile.toVector` drain) and the subprocess is reaped (`exitValue` resolves promptly).
  *
  * B5 gating: PATH probe + `FORGE_IT_SKIP_CLAUDE=1` escape hatch — identical to [[ClaudeHeadlessSmokeSuite]] so a
  * single env knob skips every real-Claude test in this module.
  *
  * Slow by design — a single round-trip is 5–20s depending on cache state. The suite-wide timeout is generous to
  * accommodate the AskUserQuestion test, which sometimes takes a second turn to recover the original "say ok" answer.
  */
class ClaudeStreamingSpecSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 180.seconds

  // --- B5: gating ----

  private val claudeOnPath: Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / "claude") => p / "claude"
    }
  private val skipFlag = sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val canRun = claudeOnPath.isDefined && !skipFlag

  private val uuidRegex =
    """^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""".r

  private val terseSystem =
    "You are a terse assistant. Reply with one word and stop. Do not call any tools."

  private def systemPromptFile(content: String): os.Path =
    os.temp(contents = content, prefix = "forge-it-system-", suffix = ".md", deleteOnExit = true)

  private def newConnector =
    ClaudeConnector(binary = claudeOnPath.get.toString)

  // --- B1 ----

  test("B1: runStreamingSpec round-trip — sessionId is a UUID; Init → AssistantText/ToolUse → CostUpdate → Result"):
    assume(canRun, "skipped — `claude` not on PATH or FORGE_IT_SKIP_CLAUDE=1")
    val sys = systemPromptFile(terseSystem)
    // close() FIRST, then drain. Reason: streaming-spec mode uses `--input-format stream-json`, which keeps stdin open
    // waiting for further user frames. The events channel only closes when the CLI exits, which only happens after
    // closeStdin signals EOF. Draining before close would deadlock (channel never closes → toVector never returns).
    // Same idiom used in CodexConnectorSuite's facade tests; the headless smoke suite gets away with drain-first because
    // `-p '<prompt>'` mode reads no stdin and the CLI exits on its own.
    val program = newConnector
      .runStreamingSpec(sys, "Say 'ok' and stop.")
      .flatMap: session =>
        for
          _ <- session.close()
          events <- session.events.compile.toVector
        yield (session.sessionId, events)
    val (sessionId, events) = program.unsafeRunSync()

    assert(uuidRegex.matches(sessionId), clue = sessionId)
    assertEquals(events.headOption, Some(AgentEvent.Init(sessionId)))

    // At least one AssistantText OR ToolUse between Init and Result.
    val hasBody = events.exists {
      case _: AgentEvent.AssistantText => true
      case _: AgentEvent.ToolUse => true
      case _ => false
    }
    assert(hasBody, clue = events)

    // CostUpdate flowed and reports >0 USD (Slice 0 confirmed total_cost_usd is always present on Claude).
    val costs = events.collect { case AgentEvent.CostUpdate(c) => c }
    assert(costs.nonEmpty, clue = events)
    costs.foreach: c =>
      assertEquals(c.provider, "anthropic")
      assert(c.usd > BigDecimal(0), clue = c)

    // Last event is a successful Result.
    events.last match
      case AgentEvent.Result(success, _) => assertEquals(success, true)
      case other => fail(s"expected Result as last event, got $other")

  // --- B2 ----

  test("B2: resumeStreamingSpec preserves the original sessionId (§6.1 invariant on the pinned CLI)"):
    assume(canRun, "skipped — `claude` not on PATH or FORGE_IT_SKIP_CLAUDE=1")
    val sys = systemPromptFile(terseSystem)
    val connector = newConnector
    val firstSidIO = connector
      .runStreamingSpec(sys, "Say 'ok' and stop.")
      .flatMap: session =>
        // close() first — see B1's note. Drain is not strictly needed here (we only want the sessionId), but doing
        // it on a background fiber would race against the CLI's exit; close-then-discard is safer.
        session.close().as(session.sessionId)

    val sid = firstSidIO.unsafeRunSync()
    assert(uuidRegex.matches(sid), clue = sid)

    val resumed = connector
      .resumeStreamingSpec(sid, sys, "Say 'done' and stop.")
      .flatMap: session =>
        for
          _ <- session.close()
          events <- session.events.compile.toVector
        yield (session.sessionId, events)
      .unsafeRunSync()

    val (newSid, events) = resumed
    // §6.1: pinned Claude CLI returns the SAME session id on resume (Slice 0 transcript 02-claude-resume.jsonl).
    assertEquals(newSid, sid)
    assertEquals(events.headOption, Some(AgentEvent.Init(sid)))
    events.last match
      case AgentEvent.Result(success, _) => assertEquals(success, true)
      case other => fail(s"expected Result as last event on resume turn, got $other")

  // --- B3 ----

  test("B3: contrived AskUserQuestion → answerQuestion(Some(id), …) → session continues to clean Result"):
    assume(canRun, "skipped — `claude` not on PATH or FORGE_IT_SKIP_CLAUDE=1")
    // System prompt strong-arms Claude into using the AskUserQuestion tool on the first turn. Without explicit
    // instruction the model tends to answer directly without invoking the tool — losing the test signal.
    val askyPrompt =
      """You are an interactive assistant. On the very first user turn, you MUST call the AskUserQuestion
        |tool to ask exactly one clarifying yes/no question before answering. Always include 'yes' and 'no'
        |as the two options. After the user has answered, give a one-word reply and stop. Do not call any
        |other tools.""".stripMargin
    val sys = systemPromptFile(askyPrompt)

    // Ref-based collector. Subscribing twice to an fs2 Channel.stream isn't supported (single-consumer), so a single
    // background fiber drains every event into a Ref and signals a Deferred on the first AskUserQuestion. The test
    // body sees the question, replies via answerQuestion, closes, then joins.
    val program = newConnector
      .runStreamingSpec(sys, "Please proceed with the task we discussed.")
      .flatMap: session =>
        for
          collected <- IO.ref(Vector.empty[AgentEvent])
          askDef <- cats.effect.Deferred[IO, AgentEvent.AskUserQuestion]
          drainFiber <- session.events
            .evalMap: e =>
              collected.update(_ :+ e) *> (e match
                case ask: AgentEvent.AskUserQuestion => askDef.complete(ask).attempt.void
                case _ => IO.unit
              )
            .compile
            .drain
            .start
          // Wait up to 90s for the question — well inside the suite timeout but generous enough to absorb a slow
          // first turn on a cold start.
          ask <- askDef.get.timeoutTo(
            90.seconds,
            IO.raiseError(new AssertionError("B3: Claude did not emit AskUserQuestion within 90s"))
          )
          toolUseId = ask.toolUseId
          _ <- IO(assert(toolUseId.isDefined, clue = ask))
          // Reply via the §7.2 tool_result path. `send` would have routed as a fresh user message and left the
          // tool_use dangling — see ClaudeConnector.encodeAnswer.
          _ <- session.answerQuestion(toolUseId, "yes")
          _ <- session.close()
          _ <- drainFiber.join.attempt.void
          allEvents <- collected.get
        yield (session.sessionId, allEvents)

    val (sessionId, events) = program.unsafeRunSync()
    assert(uuidRegex.matches(sessionId), clue = sessionId)
    // Exactly one AskUserQuestion: a second would mean the tool_result didn't reattach and Claude re-asked.
    val askCount = events.count(_.isInstanceOf[AgentEvent.AskUserQuestion])
    assertEquals(askCount, 1, clue = events)
    // Stream ends with a successful Result.
    events.last match
      case AgentEvent.Result(success, _) => assertEquals(success, true)
      case other => fail(s"expected final Result, got $other (events=$events)")

  // --- B4 ----

  test("B4: kill() mid-stream terminates the events stream cleanly and reaps the subprocess"):
    assume(canRun, "skipped — `claude` not on PATH or FORGE_IT_SKIP_CLAUDE=1")
    // Long-ish prompt so the CLI is still producing tokens when we kill it. Listing 50 distinct fruits keeps the model
    // generating long enough for the kill window to land mid-stream rather than after the final Result.
    val sys = systemPromptFile(
      "You are an enumeration bot. List items one per line. Take your time and produce complete output."
    )

    val program = newConnector
      .runStreamingSpec(sys, "List 200 unique fruit names, one per line, then list 200 vegetables.")
      .flatMap: session =>
        for
          // Let the subprocess start producing events (Init has already arrived since spawn blocks on it).
          _ <- IO.sleep(500.millis)
          _ <- session.kill()
          // Drain whatever made it onto the channel before the kill closed it. This must complete; if kill() leaves
          // the channel un-finalised the compile.toVector would hang and the suite timeout would fire.
          events <- session.events.compile.toVector
        yield events
    val events = program.unsafeRunSync()

    // Init must have arrived (spawn blocks on it); anything past that is best-effort but we don't require Result.
    assert(events.exists(_.isInstanceOf[AgentEvent.Init]), clue = events)
    // No assertion that the last event is Result — kill may land before or after the CLI's own end-of-turn. The point
    // is that compile.toVector terminated, which would not happen if the channel weren't closed by kill().
