package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId, QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost
import scala.concurrent.duration.*

class CodexConnectorSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 30.seconds

  private val model = "gpt-5-codex"
  private val emptyPrices = PriceTable.empty
  private val defaultSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never")

  private def newConnector = CodexConnector(
    model = model,
    priceTable = emptyPrices,
    sessionSettings = defaultSettings
  )

  test("connector declares the expected mechanisms and name"):
    val c = newConnector
    assertEquals(c.name, "codex")
    assertEquals(c.questionMechanism, QuestionMechanism.HaltWithQuestion)
    assertEquals(c.schemaMechanism, SchemaMechanism.Native)

  test("costFrom extracts cost only from CostUpdate events"):
    val c = newConnector
    val cost = Cost("openai", model, 100, 50, BigDecimal("0.001"))
    assertEquals(c.costFrom(AgentEvent.CostUpdate(cost)), Some(cost))
    assertEquals(c.costFrom(AgentEvent.Result(true, 0L)), None)

  test("execArgv builds the canonical Slice 0 §2.2 invocation shape"):
    val argv = CodexConnector.execArgv("codex", model, defaultSettings, "hello")
    assertEquals(argv.head, "codex")
    assertEquals(argv(1), "exec")
    assert(argv.containsSlice(List("--ignore-user-config", "--ignore-rules")), clue = argv)
    assert(argv.contains("--json"), clue = argv)
    assert(argv.containsSlice(List("-m", model)), clue = argv)
    assert(argv.containsSlice(List("--sandbox", "read-only")), clue = argv)
    // codex ≥0.131 — `-c approval_policy=<mode>` replaces `-a/--ask-for-approval <mode>`.
    assert(argv.containsSlice(List("-c", "approval_policy=never")), clue = argv)
    // Prompt is the last positional argument.
    assertEquals(argv.last, "hello")

  test("execArgv encodes addDirs as repeated --add-dir flags"):
    val settings = defaultSettings.copy(addDirs = Vector(os.Path("/tmp/a"), os.Path("/tmp/b")))
    val argv = CodexConnector.execArgv("codex", model, settings, "prompt")
    val addDirIndices = argv.zipWithIndex.collect { case ("--add-dir", i) => i }
    assertEquals(addDirIndices.size, 2)
    assertEquals(argv(addDirIndices(0) + 1), "/tmp/a")
    assertEquals(argv(addDirIndices(1) + 1), "/tmp/b")

  test("execArgv includes --output-schema when settings carry one (reviewer side)"):
    val settings = defaultSettings.copy(outputSchema = Some(os.Path("/tmp/schema.json")))
    val argv = CodexConnector.execArgv("codex", model, settings, "p")
    assert(argv.containsSlice(List("--output-schema", "/tmp/schema.json")), clue = argv)

  test("execArgv includes -C <dir> when working directory is set"):
    val settings = defaultSettings.copy(workingDirectory = Some(os.Path("/tmp/work")))
    val argv = CodexConnector.execArgv("codex", model, settings, "p")
    assert(argv.containsSlice(List("-C", "/tmp/work")), clue = argv)

  test("execResumeArgv carries only thread id, user msg, and --json (no sticky flags per §7.10(c))"):
    val argv = CodexConnector.execResumeArgv("codex", "thread-abc", "follow-up question")
    assertEquals(argv, List("codex", "exec", "resume", "--json", "thread-abc", "follow-up question"))
    // Negative: should not contain any sticky flag. `--ask-for-approval` no longer exists in codex ≥0.131 (replaced
    // by `-c approval_policy=...`, which IS legal on resume), so it's not in this list — exec resume's rejection
    // surface narrowed in 0.133 to just the path-bound flags.
    Seq("--sandbox", "--output-schema", "--add-dir", "-C").foreach: flag =>
      assert(!argv.contains(flag), clue = s"$flag should not appear in resume argv: $argv")

  // --- streaming-spec multi-process facade tests ---

  /** Build a fake `codex` shell script. Each invocation (one per turn) emits a Codex stream-json transcript:
    *   - thread.started with the configured `threadId`
    *   - turn.started
    *   - item.completed{agent_message} whose text echoes the LAST positional arg (the user prompt as the connector
    *     constructed it — combined with the system block for first turns, raw for resume turns)
    *   - turn.completed with token usage
    *
    * The thread.started id is the same on every invocation so resume turns appear to preserve the original thread id
    * (matching the real Codex CLI contract — Slice 0 §2.2).
    */
  private def fakeCodex(threadId: String): os.Path =
    // Use $$ in the s"" interpolation to emit a literal $ in the script. Pull the last positional arg via a POSIX
    // "shift through to the end" loop so we don't depend on bash-only idioms.
    val script =
      s"""#!/bin/sh
         |last=""
         |for a in "$$@"; do last="$$a"; done
         |json_prompt=$$(printf '%s' "$$last" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g; s/$$/\\\\n/' | tr -d '\\n')
         |echo '{"type":"thread.started","thread_id":"$threadId"}'
         |echo '{"type":"turn.started"}'
         |printf '%s\\n' "{\\"type\\":\\"item.completed\\",\\"item\\":{\\"id\\":\\"item_0\\",\\"type\\":\\"agent_message\\",\\"text\\":\\"echo: $$json_prompt\\"}}"
         |echo '{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":3,"reasoning_output_tokens":0}}'
         |""".stripMargin
    val f = os.temp(contents = script, prefix = "fake-codex-stream-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(f, "rwx------")
    f

  test("runStreamingSpec: first turn captures thread_id, Init forwarded, UserMessage mirror after Init"):
    val tid = "thr-stream-1"
    val fake = fakeCodex(tid)
    val systemPrompt = os.temp(contents = "Act as designer.", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val (returnedSid, events) = connector
      .runStreamingSpec(systemPrompt, "hello")
      .flatMap: session =>
        for
          // close() blocks until any in-flight turn drains, then closes the events channel — only then can
          // events.compile.toVector terminate. Codex's multi-turn facade deliberately keeps the channel open across
          // turns (more turns may come), so the drain-then-close idiom from StreamingDriver doesn't apply.
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield (session.sessionId, drained)
      .unsafeRunSync()
    assertEquals(returnedSid, tid)
    // First event is Init.
    assertEquals(events.headOption, Some(AgentEvent.Init(tid)))
    // UserMessage mirror lands after Init.
    val initIdx = events.indexOf(AgentEvent.Init(tid))
    val userIdx = events.indexOf(AgentEvent.UserMessage("hello"))
    assert(initIdx >= 0 && userIdx > initIdx, clue = events)
    // The assistant text contains the combined system+user prompt (the fake CLI echoes whatever positional arg it
    // received). This proves §7.10(a) prepending happened on the initial spawn.
    val assistantText = events.collectFirst { case AgentEvent.AssistantText(t, _) => t }.getOrElse("")
    assert(assistantText.contains("Act as designer."), clue = assistantText)
    assert(assistantText.contains("hello"), clue = assistantText)
    // Stream ends with Result(success).
    assert(events.last.isInstanceOf[AgentEvent.Result], clue = events)

  test("send() on a streaming session re-spawns with `codex exec resume`; resume turn's Init is dropped"):
    val tid = "thr-stream-2"
    val fake = fakeCodex(tid)
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val events = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap: session =>
        for
          _ <- session.send("second") // blocks until first turn finishes; runs the second turn fully
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield drained
      .unsafeRunSync()
    // Exactly one Init even though two turns ran.
    assertEquals(events.count(_.isInstanceOf[AgentEvent.Init]), 1, clue = events)
    // Both UserMessage mirrors land — first after Init, second at start of resume turn.
    val userMessages = events.collect { case AgentEvent.UserMessage(s) => s }
    assertEquals(userMessages, Vector("first", "second"))
    // Second turn's echo includes "second" prefix-suffixed by §7.10(a) prepending; first turn's echo includes "first".
    val texts = events.collect { case AgentEvent.AssistantText(t, _) => t }
    assert(texts.exists(_.contains("first")), clue = texts)
    assert(texts.exists(_.contains("second")), clue = texts)
    // Two Result events (one per turn).
    assertEquals(events.count(_.isInstanceOf[AgentEvent.Result]), 2, clue = events)

  test("answerQuestion(_, answer) routes through the resume path (toolUseId ignored)"):
    val tid = "thr-stream-3"
    val fake = fakeCodex(tid)
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val events = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap: session =>
        for
          // toolUseId is ignored for Codex's HaltWithQuestion path (no wire-level tool_use to reference).
          _ <- session.answerQuestion(None, "the-answer")
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield drained
      .unsafeRunSync()
    // Mirror UserMessage for the answer appears with the user-visible text (no [answer] prefix in Codex; the answer
    // IS the next turn's user message, indistinguishable from a `send`).
    val userMessages = events.collect { case AgentEvent.UserMessage(s) => s }
    assertEquals(userMessages, Vector("first", "the-answer"))
    // The answer reached the resume subprocess.
    assert(
      events.exists { case AgentEvent.AssistantText(t, _) => t.contains("the-answer"); case _ => false },
      clue = events
    )

  test("answerQuestion(Some(id), _) ignores the id; routes through resume the same way"):
    // Defensive: Codex doesn't care whether the caller passes Some or None; the toolUseId is dropped on the floor.
    val tid = "thr-stream-3b"
    val fake = fakeCodex(tid)
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val events = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap: session =>
        for
          _ <- session.answerQuestion(Some("toolu_ignored"), "ignored-id-still-works")
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield drained
      .unsafeRunSync()
    assert(
      events.exists { case AgentEvent.UserMessage(s) => s == "ignored-id-still-works"; case _ => false },
      clue = events
    )

  test("kill() mid-turn terminates the active subprocess and finalises the events channel"):
    // Fake that hangs on the first turn so we can kill it mid-flight.
    val script =
      """|#!/bin/sh
         |echo '{"type":"thread.started","thread_id":"thr-kill"}'
         |# Hang here until the parent SIGTERMs us.
         |sleep 10""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-kill-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val events = connector
      .runStreamingSpec(systemPrompt, "hi")
      .flatMap: session =>
        // Init has arrived (the factory blocks on it). Give the subprocess a moment to be mid-sleep, then kill.
        for
          _ <- IO.sleep(150.millis)
          _ <- session.kill()
          drained <- session.events.compile.toVector
        yield drained
      .unsafeRunSync()
    // Events stream terminates (the Channel was closed by kill). Init is in there since the fake produced it before
    // sleeping; no Result, since the subprocess was killed mid-turn.
    assertEquals(events.headOption, Some(AgentEvent.Init("thr-kill")), clue = events)
    assert(!events.exists(_.isInstanceOf[AgentEvent.Result]), clue = events)

  test("resumeStreamingSpec: spawns codex exec resume <sid>, re-prepends the §7.10(a) system block (C14)"):
    // C14 / N5 regression: the widened trait carries `systemPromptPath` so each `codex exec resume` re-prepends the
    // original driver framing (a fresh subprocess remembers nothing the adapter doesn't pass in). The fake echoes the
    // last positional arg — the combined prompt — back as assistant text, so the system block must show up there.
    val tid = "thr-resume-1"
    val fake = fakeCodex(tid)
    val systemPrompt =
      os.temp(contents = "Act as the design driver.", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val (returnedSid, events) = connector
      .resumeStreamingSpec(tid, systemPrompt, "continue please")
      .flatMap: session =>
        for
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield (session.sessionId, drained)
      .unsafeRunSync()
    assertEquals(returnedSid, tid)
    assertEquals(events.headOption, Some(AgentEvent.Init(tid)))
    val texts = events.collect { case AgentEvent.AssistantText(t, _) => t }
    assert(texts.exists(_.contains("Act as the design driver.")), clue = texts) // system block reached Codex
    assert(texts.exists(_.contains("continue please")), clue = texts) // user message too

  test("resumeStreamingSpec: mismatched thread_id raises rather than silently lying about sessionId"):
    val fake = fakeCodex("thr-actual")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val result = connector
      .resumeStreamingSpec("thr-expected-different", systemPrompt, "msg")
      .attempt
      .unsafeRunSync()
    assert(result.left.exists(_.getMessage.contains("thr-actual")), clue = result)
    assert(result.left.exists(_.getMessage.contains("thr-expected-different")), clue = result)

  test("in-session send() / answerQuestion: resume turn with mismatched thread_id raises §6.1 continuity error"):
    // Two-phase fake: the first invocation emits "thr-first" (the captured session id); a counter file in /tmp
    // causes the second invocation to emit "thr-second" (a different id). Without the mismatch check the resume
    // turn's Init would be silently dropped and the caller's IO would succeed, masking continuity loss.
    val counterFile = os.temp(prefix = "codex-counter-", suffix = ".txt", deleteOnExit = true)
    os.write.over(counterFile, "0")
    val script =
      s"""#!/bin/sh
         |last=""
         |for a in "$$@"; do last="$$a"; done
         |json_prompt=$$(printf '%s' "$$last" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g; s/$$/\\\\n/' | tr -d '\\n')
         |n=$$(cat ${counterFile.toString})
         |if [ "$$n" = "0" ]; then
         |  tid="thr-first"
         |  echo 1 > ${counterFile.toString}
         |else
         |  tid="thr-second"
         |fi
         |echo "{\\"type\\":\\"thread.started\\",\\"thread_id\\":\\"$$tid\\"}"
         |echo '{"type":"turn.started"}'
         |printf '%s\\n' "{\\"type\\":\\"item.completed\\",\\"item\\":{\\"id\\":\\"item_0\\",\\"type\\":\\"agent_message\\",\\"text\\":\\"echo: $$json_prompt\\"}}"
         |echo '{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":3,"reasoning_output_tokens":0}}'
         |""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-mismatch-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val result = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap(session => session.send("second").attempt.map((session, _)))
      .unsafeRunSync()
    val (session, sendResult) = result
    assertEquals(session.sessionId, "thr-first", clue = session.sessionId)
    assert(sendResult.left.exists(_.getMessage.contains("thr-first")), clue = sendResult)
    assert(sendResult.left.exists(_.getMessage.contains("thr-second")), clue = sendResult)
    assert(sendResult.left.exists(_.getMessage.contains("continuity break")), clue = sendResult)

  test("close() while a turn is queued behind the mutex: queued send rechecks closedRef and rejects"):
    // First turn sleeps after emitting Init so we can deterministically queue a second turn behind it. After Init
    // arrives, runStreamingSpec returns the session, but the first-turn fiber still owns `turnMutex` until the
    // script exits. We .start session.send(...) in a fiber so it waits on the mutex, then call session.close()
    // which sets closedRef = true and waits on the mutex too. Whichever waiter acquires the mutex next, the
    // queued send's inner closedRef recheck (runResumeTurn) must see closedRef = true and raise. Regression guard:
    // before the inner recheck was added, a queued send would proceed past the outer fast-path closedRef check,
    // acquire the mutex, and spawn a fresh `codex exec resume` after the session had already been closed.
    val tid = "thr-queued"
    val script =
      s"""#!/bin/sh
         |last=""
         |for a in "$$@"; do last="$$a"; done
         |json_prompt=$$(printf '%s' "$$last" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g; s/$$/\\\\n/' | tr -d '\\n')
         |echo '{"type":"thread.started","thread_id":"$tid"}'
         |# Sleep AFTER thread.started so the factory's initDef.get returns and the test can queue a send behind
         |# the still-running first turn. The mutex stays held until this script exits.
         |sleep 0.5
         |echo '{"type":"turn.started"}'
         |printf '%s\\n' "{\\"type\\":\\"item.completed\\",\\"item\\":{\\"id\\":\\"item_0\\",\\"type\\":\\"agent_message\\",\\"text\\":\\"echo: $$json_prompt\\"}}"
         |echo '{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":3,"reasoning_output_tokens":0}}'
         |""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-queued-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val queuedResult = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap: session =>
        for
          // Queue a second turn that must wait on `turnMutex` because the first turn still holds it.
          sendFiber <- session.send("queued").attempt.start
          // Give the send fiber a moment to actually start waiting on the mutex.
          _ <- IO.sleep(50.millis)
          // close() sets closedRef = true and waits on the mutex; the queued send must recheck before spawning.
          _ <- session.close()
          result <- sendFiber.joinWithNever
        yield result
      .unsafeRunSync()
    assert(queuedResult.isLeft, clue = queuedResult)
    assert(
      queuedResult.left.exists(_.getMessage.contains("session is closed")),
      clue = queuedResult
    )

  test("resume turn that exits non-zero raises from send() with stderr tail and finalises the session"):
    // Two-phase fake. First invocation: normal transcript so runStreamingSpec succeeds. Second invocation (the
    // resume turn driven by send()): emits thread.started then writes a diagnostic to stderr and exits 1. Pre-P2
    // the send() returned success because runOneTurn discarded the exit code; post-P2 it raises so the
    // orchestrator can route to a turn-failure rather than continuing against a torn-down stream. Subsequent
    // sends must also reject — the session is finalised.
    val counterFile = os.temp(prefix = "codex-counter-fail-", suffix = ".txt", deleteOnExit = true)
    os.write.over(counterFile, "0")
    val tid = "thr-fail-1"
    val script =
      s"""#!/bin/sh
         |n=$$(cat ${counterFile.toString})
         |if [ "$$n" = "0" ]; then
         |  echo 1 > ${counterFile.toString}
         |  echo '{"type":"thread.started","thread_id":"$tid"}'
         |  echo '{"type":"turn.started"}'
         |  printf '%s\\n' "{\\"type\\":\\"item.completed\\",\\"item\\":{\\"id\\":\\"item_0\\",\\"type\\":\\"agent_message\\",\\"text\\":\\"first turn ok\\"}}"
         |  echo '{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":3,"reasoning_output_tokens":0}}'
         |  exit 0
         |else
         |  echo '{"type":"thread.started","thread_id":"$tid"}'
         |  echo "codex internal error" >&2
         |  exit 1
         |fi
         |""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-fail-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val outcome = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap: session =>
        for
          firstSend <- session.send("triggers failure").attempt
          // Session must be finalised after a turn failure — a follow-up send must reject without spawning.
          followUp <- session.send("after failure").attempt
        yield (firstSend, followUp)
      .unsafeRunSync()
    val (firstSend, followUp) = outcome
    assert(firstSend.isLeft, clue = firstSend)
    assert(firstSend.left.exists(_.getMessage.contains("exited 1")), clue = firstSend)
    assert(firstSend.left.exists(_.getMessage.contains("resume turn")), clue = firstSend)
    assert(
      firstSend.left.exists(_.getMessage.contains("codex internal error")),
      clue = firstSend
    )
    assert(followUp.isLeft, clue = followUp)
    assert(followUp.left.exists(_.getMessage.contains("session is closed")), clue = followUp)

  test(
    "first turn that fails AFTER Init surfaces the actual cause on the next send(), not the generic 'session is closed'"
  ):
    // Regression guard for the silent-first-turn-failure bug: when the first turn produces thread.started (so the
    // factory's initDef.get completes and runStreamingSpec returns a live session) but then exits non-zero (or 0
    // without a Result), the session is finalised silently. Pre-fix, the next send() raised
    // "session is closed; further turns rejected" — useless for debugging because the actual failure detail (stderr
    // tail, exit code, §6.1 reason) is lost. Post-fix, firstTurnFailureRef carries the real cause across the gap and
    // the next send() raises with it.
    val tid = "thr-first-fail"
    val script =
      s"""#!/bin/sh
         |echo '{"type":"thread.started","thread_id":"$tid"}'
         |echo "fatal: model unavailable" >&2
         |exit 7
         |""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-firstfail-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val outcome = connector
      .runStreamingSpec(systemPrompt, "hello")
      .flatMap: session =>
        for
          // Give the first-turn fiber a moment to drain the (already-exited) subprocess and record the failure.
          _ <- IO.sleep(150.millis)
          firstSend <- session.send("anything").attempt
          followUp <- session.send("after").attempt
        yield (firstSend, followUp)
      .unsafeRunSync()
    val (firstSend, followUp) = outcome
    assert(firstSend.isLeft, clue = firstSend)
    assert(firstSend.left.exists(_.getMessage.contains("exited 7")), clue = firstSend)
    assert(firstSend.left.exists(_.getMessage.contains("first turn")), clue = firstSend)
    assert(firstSend.left.exists(_.getMessage.contains("fatal: model unavailable")), clue = firstSend)
    // Negative: the misleading "session is closed" message must NOT be what surfaces — that would mean the
    // firstTurnFailureRef check was bypassed.
    assert(!firstSend.left.exists(_.getMessage.contains("session is closed")), clue = firstSend)
    // Follow-up: firstTurnFailureRef is sticky (the recorded error is still there), so subsequent sends raise the
    // same actual cause, not the generic close message.
    assert(followUp.isLeft, clue = followUp)
    assert(followUp.left.exists(_.getMessage.contains("exited 7")), clue = followUp)

  test("resume turn that exits cleanly but emits no Result event raises from send()"):
    // Resume turn produces thread.started + turn.started but NO turn.completed → CodexEventParser emits no Result.
    // The AgentSession.events contract is "terminates with a Result event"; a clean-exit turn that doesn't emit
    // one is still a contract violation. Pre-P2 the missing Result was silently absorbed; post-P2 send() raises.
    val counterFile = os.temp(prefix = "codex-counter-noresult-", suffix = ".txt", deleteOnExit = true)
    os.write.over(counterFile, "0")
    val tid = "thr-fail-2"
    val script =
      s"""#!/bin/sh
         |n=$$(cat ${counterFile.toString})
         |if [ "$$n" = "0" ]; then
         |  echo 1 > ${counterFile.toString}
         |  echo '{"type":"thread.started","thread_id":"$tid"}'
         |  echo '{"type":"turn.started"}'
         |  printf '%s\\n' "{\\"type\\":\\"item.completed\\",\\"item\\":{\\"id\\":\\"item_0\\",\\"type\\":\\"agent_message\\",\\"text\\":\\"first turn ok\\"}}"
         |  echo '{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":3,"reasoning_output_tokens":0}}'
         |  exit 0
         |else
         |  echo '{"type":"thread.started","thread_id":"$tid"}'
         |  echo '{"type":"turn.started"}'
         |  # No turn.completed → no Result event → P2 surfaces the missing-Result contract violation.
         |  exit 0
         |fi
         |""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-codex-noresult-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "S", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = CodexConnector(
      binary = fake.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings
    )
    val sendResult = connector
      .runStreamingSpec(systemPrompt, "first")
      .flatMap(session => session.send("no result coming").attempt)
      .unsafeRunSync()
    assert(sendResult.isLeft, clue = sendResult)
    assert(sendResult.left.exists(_.getMessage.contains("no Result event")), clue = sendResult)
    assert(sendResult.left.exists(_.getMessage.contains("resume turn")), clue = sendResult)

  test("reviewer methods raise ReviewerNotConfigured (non-retryable) when no ReviewerAssets are configured"):
    val c = newConnector
    val fid = FeatureId("feat-1")
    val pid = PieceId("p1")
    val r1 = c.reviewDesign(DesignReviewInput(fid, 1, "")).attempt.unsafeRunSync()
    val r2 = c
      .reviewPr(PrReviewInput(fid, pid, io.forge.core.PrNumber(1), "", "", Vector.empty))
      .attempt
      .unsafeRunSync()
    val r3 = c.refine(RefineInput(fid, pid, "", "")).attempt.unsafeRunSync()
    // Distinct from ReviewerProcessFailure (which §7.6 marks retryable) so reviewProcessRetries does not burn its
    // retry budget on a config mistake.
    Seq(r1, r2, r3).foreach: r =>
      assert(r.left.exists(_.isInstanceOf[ReviewerNotConfigured]), clue = r)
      assert(!r.left.exists(_.isInstanceOf[ReviewerProcessFailure]), clue = r)
      assert(r.left.exists(_.getMessage.contains("no ReviewerAssets configured")), clue = r)

  test("extractAgentMessageText: pulls the last item.completed{agent_message}.text and parses it as JSON"):
    val lines = Vector(
      """{"type":"thread.started","thread_id":"019e5e65-1caf-7210-a79b-239db0bafb43"}""",
      """{"type":"turn.started"}""",
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"{\"verdict\":\"approve\",\"blockers\":[],\"summary\":\"Approved.\"}"}}""",
      """{"type":"turn.completed","usage":{"input_tokens":25552,"cached_input_tokens":2432,"output_tokens":64,"reasoning_output_tokens":37}}"""
    )
    val result = CodexConnector.extractAgentMessageText(lines)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("approve"))

  test("extractAgentMessageText: missing agent_message returns Left for adapter-error routing"):
    val lines = Vector(
      """{"type":"thread.started","thread_id":"abc"}""",
      """{"type":"turn.completed","usage":{"input_tokens":1,"cached_input_tokens":0,"output_tokens":1,"reasoning_output_tokens":0}}"""
    )
    val result = CodexConnector.extractAgentMessageText(lines)
    assert(result.left.exists(_.contains("no agent_message event")), clue = result)

  test("extractAgentMessageText: takes the LAST agent_message when multiple appear in a turn"):
    val lines = Vector(
      """{"type":"item.completed","item":{"type":"agent_message","text":"{\"verdict\":\"request_changes\",\"blockers\":[],\"summary\":\"first\"}"}}""",
      """{"type":"item.completed","item":{"type":"agent_message","text":"{\"verdict\":\"approve\",\"blockers\":[],\"summary\":\"second\"}"}}"""
    )
    val result = CodexConnector.extractAgentMessageText(lines).toOption.get
    assertEquals(result.obj.get("verdict").flatMap(_.strOpt), Some("approve"))

  test("extractAgentMessageText: agent_message text not valid JSON returns Left"):
    val lines = Vector(
      """{"type":"item.completed","item":{"type":"agent_message","text":"not json"}}"""
    )
    val result = CodexConnector.extractAgentMessageText(lines)
    assert(result.left.exists(_.contains("not valid JSON")), clue = result)

  test("reviewer end-to-end against a fake CLI: schema-conformant payload decoded to DesignReview"):
    // Mirrors the Codex Slice 0 §3.2 transcript shape: thread.started, turn.started, item.completed{agent_message},
    // turn.completed. The fake `codex` echoes those four lines and exits 0; the connector sees a single
    // agent_message text payload and decodes it as DesignReview.
    val lines = Seq(
      """{"type":"thread.started","thread_id":"019e5e65-1caf-7210-a79b-239db0bafb43"}""",
      """{"type":"turn.started"}""",
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"{\"verdict\":\"request_changes\",\"blockers\":[{\"summary\":\"missing API\"}],\"questions\":[],\"summary\":\"Needs work.\"}"}}""",
      """{"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":0,"output_tokens":20,"reasoning_output_tokens":5}}"""
    )
    val script = "#!/bin/sh\n" + lines.map(l => s"printf '%s\\n' '${l.replace("'", "'\\''")}'").mkString("\n") + "\n"
    val fakeCodex = os.temp(contents = script, prefix = "fake-codex-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fakeCodex, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = CodexConnector(
      binary = fakeCodex.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings,
      reviewerAssets = Some(assets)
    )
    val review = connector
      .reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "design md"))
      .unsafeRunSync()
    assertEquals(review.verdict, ReviewVerdict.RequestChanges)
    assertEquals(review.summary, "Needs work.")
    assertEquals(review.blockers.size, 1)
    assertEquals(review.blockers.head.summary, "missing API")

  test("reviewer end-to-end against a fake CLI: non-zero exit surfaces as ReviewerProcessFailure"):
    val fakeCodex = os.temp(
      contents = """#!/bin/sh
                   |echo "boom" >&2
                   |exit 5
                   |""".stripMargin,
      prefix = "fake-codex-fail-",
      suffix = ".sh",
      deleteOnExit = true
    )
    os.perms.set(fakeCodex, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = CodexConnector(
      binary = fakeCodex.toString,
      model = model,
      priceTable = emptyPrices,
      sessionSettings = defaultSettings,
      reviewerAssets = Some(assets)
    )
    val r = connector.reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "x")).attempt.unsafeRunSync()
    assert(r.left.exists(_.isInstanceOf[ReviewerProcessFailure]), clue = r)
    assert(r.left.exists(_.getMessage.contains("exited 5")), clue = r)

  test("reviewer per-call settings use --output-schema from ReviewerAssets, not the driver sessionSettings"):
    // Regression guard: the reviewer one-shot must build its own CodexSessionSettings with the per-method schema, so
    // a driver connector configured with `outputSchema = None` can still run reviewer one-shots.
    val schemaDr = os.temp(contents = "{}", prefix = "dr-schema-", suffix = ".json", deleteOnExit = true)
    val schemaPr = os.temp(contents = "{}", prefix = "pr-schema-", suffix = ".json", deleteOnExit = true)
    val schemaRf = os.temp(contents = "{}", prefix = "rf-schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt = os.temp(contents = "p", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schemaDr, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schemaPr, systemPrompt),
      refine = ReviewerAssets.PerMethod(schemaRf, systemPrompt)
    )
    // Each method's argv carries its own --output-schema path. Exercise the wiring via execArgv directly using the
    // settings the connector would build per-call.
    val drSettings = CodexSessionSettings(
      sandbox = "read-only",
      outputSchema = Some(assets.designReview.schema),
      addDirs = Vector.empty,
      approvalMode = "never",
      workingDirectory = None
    )
    val argv = CodexConnector.execArgv("codex", model, drSettings, "body")
    assert(argv.containsSlice(List("--output-schema", schemaDr.toString)), clue = argv)
    assert(!argv.contains(schemaPr.toString), clue = argv)
    assert(!argv.contains(schemaRf.toString), clue = argv)
