package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*

import scala.concurrent.duration.*

/** Slice 1 §17 / design-2.1 PR-C — real-CLI streaming-spec integration suite for `CodexConnector`'s multi-process
  * facade ([[CodexStreamingSession]]).
  *
  * Exercises:
  *   - C2: `runStreamingSpec(prompt, "first")` + `send("second")`. Assert exactly one `Init` (resume turns' second
  *     `thread.started` is dropped from the session stream by the facade); both turns appear as `UserMessage` mirrors;
  *     the thread id reported by the session matches the underlying CLI's value throughout (sessionId is captured once
  *     and never changes).
  *   - C3: `runStreamingSpec` → close → `resumeStreamingSpec(threadId, msg)`. Assert the resumed session reports the
  *     same thread id (§6.1 — Codex preserves `thread_id` across `codex exec resume`; Slice 0 §2.2).
  *   - C5: `answerQuestion(_, answer)` on a fresh streaming session — routes through the resume path identically to
  *     `send` (HaltWithQuestion has no wire-level tool_use to reference; the facade drops `toolUseId` and treats
  *     `answer` as the next turn's user message). Assert the answer text reaches Codex and a `Result` is produced.
  *   - C6: `kill()` mid-turn. Assert the events stream terminates cleanly (channel closed by `kill()`) and the
  *     underlying subprocess is reaped (verified indirectly via stream termination — `Subprocess.kill` joins on
  *     `waitFor`, so a hung child would block `kill()` itself and the test would time out).
  *
  * C7 gating: PATH probe + `FORGE_IT_SKIP_CODEX=1` escape hatch.
  *
  * Slow by design — each Codex turn is ~5–15s, so the multi-turn test in C2 can take 30s+ on a cold start.
  */
class CodexStreamingSpecSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 300.seconds

  private val codexOnPath: Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / "codex") => p / "codex"
    }
  private val skipFlag = sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")
  private val canRun = codexOnPath.isDefined && !skipFlag

  // FORGE_IT_CODEX_MODEL lets users on a different account tier override the default — see CodexHeadlessSmokeSuite.
  private val model: String =
    sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5-codex")

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try
      val raw = scala.io.Source.fromInputStream(stream)("UTF-8").mkString
      upickle.default.read[PriceTable](raw)
    finally stream.close()

  private val terseSystem =
    "You are a terse assistant. Reply with one word and stop. Do not call any tools."

  private def systemPromptFile(content: String): os.Path =
    os.temp(contents = content, prefix = "forge-it-system-", suffix = ".md", deleteOnExit = true)

  private def newConnector =
    CodexConnector(
      binary = codexOnPath.get.toString,
      model = model,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never")
    )

  // --- C2 ----

  test("C2: runStreamingSpec + send() preserve thread_id across turns; exactly one Init in the session stream"):
    assume(canRun, "skipped — `codex` not on PATH or FORGE_IT_SKIP_CODEX=1")
    val sys = systemPromptFile(terseSystem)
    val connector = newConnector
    val program = connector
      .runStreamingSpec(sys, "Say 'first' and stop.")
      .flatMap: session =>
        for
          // send() blocks until the first turn drains and the second turn finishes (the facade serialises turns
          // under a mutex). The session sessionId is captured once at start and never changes.
          _ <- session.send("Say 'second' and stop.")
          _ <- session.close()
          events <- session.events.compile.toVector
        yield (session.sessionId, events)
    val (threadId, events) = program.unsafeRunSync()

    assert(threadId.nonEmpty, clue = threadId)
    // Exactly one Init carrying the original thread id — resume turn's second thread.started was dropped.
    val inits = events.collect { case e @ AgentEvent.Init(_) => e }
    assertEquals(inits, Vector(AgentEvent.Init(threadId)), clue = events)
    // Two UserMessage mirrors, in send order. The facade emits the first turn's mirror after Init and the second
    // turn's mirror at the start of its resume turn.
    val userMessages = events.collect { case AgentEvent.UserMessage(s) => s }
    assertEquals(userMessages.size, 2, clue = events)
    assert(userMessages(0).contains("first"), clue = userMessages)
    assert(userMessages(1).contains("second"), clue = userMessages)
    // Two Result events — one per turn.
    assertEquals(events.count(_.isInstanceOf[AgentEvent.Result]), 2, clue = events)

  // --- C3 ----

  test("C3: resumeStreamingSpec returns a session with the same thread_id (§6.1)"):
    assume(canRun, "skipped — `codex` not on PATH or FORGE_IT_SKIP_CODEX=1")
    val sys = systemPromptFile(terseSystem)
    val connector = newConnector
    // close() first — for CodexStreamingSession the events channel stays open across turns (more turns may come),
    // so drain-then-close would block forever. close() blocks on the turn mutex (the current turn's stdout drains
    // naturally then the subprocess exits), then closes the channel. Same idiom as CodexConnectorSuite's facade
    // tests.
    val firstSid = connector
      .runStreamingSpec(sys, "Say 'one' and stop.")
      .flatMap: session =>
        session.close().as(session.sessionId)
      .unsafeRunSync()
    assert(firstSid.nonEmpty, clue = firstSid)

    val resumed = connector
      .resumeStreamingSpec(firstSid, "Say 'two' and stop.")
      .flatMap: session =>
        session.close().as(session.sessionId)
      .unsafeRunSync()

    // Pinned-CLI invariant: `codex exec resume <sid>` returns the same thread id; the facade verifies and would have
    // raised on mismatch. Test passing means the equality held.
    assertEquals(resumed, firstSid)

  // --- C5 ----

  test("C5: answerQuestion(_, answer) routes through the resume path; thread_id stable, answer reaches the model"):
    assume(canRun, "skipped — `codex` not on PATH or FORGE_IT_SKIP_CODEX=1")
    // No actual HaltWithQuestion required — the implementation routes answerQuestion identically to send() for Codex
    // (HaltWithQuestion has no wire-level tool_use to reference). This test proves the routing without depending on
    // the model emitting a halt envelope (covered separately by CodexHaltWithQuestionReliabilitySuite, C4).
    val sys = systemPromptFile(terseSystem)
    val connector = newConnector
    val program = connector
      .runStreamingSpec(sys, "Say 'init' and stop.")
      .flatMap: session =>
        val originalSid = session.sessionId
        for
          // toolUseId ignored on Codex — passing None to match what the §7.3 halt envelope path would emit.
          _ <- session.answerQuestion(None, "Say 'answered' and stop.")
          _ <- session.close()
          events <- session.events.compile.toVector
        yield (originalSid, session.sessionId, events)
    val (originalSid, finalSid, events) = program.unsafeRunSync()

    // sessionId stable across the answerQuestion turn — the facade doesn't rebuild the session on resume turns.
    assertEquals(finalSid, originalSid)
    // Two turns, two Results.
    assertEquals(events.count(_.isInstanceOf[AgentEvent.Result]), 2, clue = events)
    // The answer text became the next user message — appears as a UserMessage mirror with no [answer] prefix (Codex's
    // answer IS the next user message; see CodexStreamingSession docs).
    val userMessages = events.collect { case AgentEvent.UserMessage(s) => s }
    assert(userMessages.exists(_.contains("answered")), clue = userMessages)

  // --- C6 ----

  test("C6: kill() mid-turn terminates the session stream cleanly (no zombie subprocess)"):
    assume(canRun, "skipped — `codex` not on PATH or FORGE_IT_SKIP_CODEX=1")
    // Prompt the model with something that will keep it busy long enough for the kill window. Codex generally takes
    // 5–15s end-to-end so a 300ms sleep before kill lands mid-turn comfortably.
    val sys = systemPromptFile(
      "You are an enumeration bot. List items one per line. Take your time and produce complete output."
    )
    val connector = newConnector
    val program = connector
      .runStreamingSpec(sys, "List 200 unique fruit names, one per line.")
      .flatMap: session =>
        for
          _ <- IO.sleep(500.millis)
          _ <- session.kill()
          // Channel must be closed by kill(); compile.toVector would hang otherwise and the suite timeout would fire.
          // Subprocess.kill blocks on the OS reap, so we know the subprocess is gone when this returns.
          events <- session.events.compile.toVector
        yield events
    val events = program.unsafeRunSync()

    assert(events.exists(_.isInstanceOf[AgentEvent.Init]), clue = events)
    // No Result expected — kill() landed before the turn produced one.
    assert(!events.exists(_.isInstanceOf[AgentEvent.Result]), clue = events)
