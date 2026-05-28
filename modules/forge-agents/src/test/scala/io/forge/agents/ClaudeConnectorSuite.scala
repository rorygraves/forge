package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId, QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost
import scala.concurrent.duration.*

class ClaudeConnectorSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 30.seconds

  test("connector declares the expected mechanisms and name"):
    val c = ClaudeConnector()
    assertEquals(c.name, "claude")
    assertEquals(c.questionMechanism, QuestionMechanism.Native)
    assertEquals(c.schemaMechanism, SchemaMechanism.Native)

  test("costFrom extracts cost only from CostUpdate events"):
    val c = ClaudeConnector()
    val cost = Cost("anthropic", "claude-opus-4-7", 100, 50, BigDecimal("0.05"))
    assertEquals(c.costFrom(AgentEvent.CostUpdate(cost)), Some(cost))
    assertEquals(c.costFrom(AgentEvent.Result(true, 0L)), None)
    assertEquals(c.costFrom(AgentEvent.Init("sid")), None)

  test("streamingSpecArgv includes -p, isolation, bidirectional stream-json I/O, and system prompt flags"):
    val argv = ClaudeConnector.streamingSpecArgv("claude", os.Path("/tmp/spec.md"))
    assert(argv.startsWith(List("claude")), clue = argv)
    // -p is required: `claude --help` says --input-format / --output-format only work with --print. Without it the
    // CLI enters interactive TUI mode and never emits Init events.
    assert(argv.contains("-p"), clue = argv)
    assert(argv.containsSlice(List("--setting-sources", "project,local")), clue = argv)
    assert(argv.contains("--strict-mcp-config"), clue = argv)
    // Streaming spec mode reads JSON user messages on stdin.
    assert(argv.containsSlice(List("--input-format", "stream-json")), clue = argv)
    assert(argv.containsSlice(List("--output-format", "stream-json")), clue = argv)
    assert(argv.contains("--verbose"), clue = argv)
    assert(argv.containsSlice(List("--system-prompt-file", "/tmp/spec.md")), clue = argv)
    // Negative: never include --bare (would disable OAuth — see Slice 0 §2.1).
    assert(!argv.contains("--bare"), clue = argv)

  test("resumeStreamingSpecArgv includes -p, carries --resume, omits --system-prompt-file"):
    val argv = ClaudeConnector.resumeStreamingSpecArgv("claude", "abc-123")
    assert(argv.contains("-p"), clue = argv)
    assert(argv.containsSlice(List("--resume", "abc-123")), clue = argv)
    assert(!argv.contains("--system-prompt-file"), clue = argv)
    // Same input-format/output-format flags as streamingSpec; same -p requirement.
    assert(argv.containsSlice(List("--input-format", "stream-json")), clue = argv)
    assert(argv.containsSlice(List("--output-format", "stream-json")), clue = argv)

  test("encodeUserMessageJson wraps text in the wire shape `--input-format stream-json` expects"):
    val frame = ClaudeConnector.encodeUserMessageJson("hello")
    val parsed = ujson.read(frame)
    assertEquals(parsed("type").str, "user")
    assertEquals(parsed("message")("role").str, "user")
    assertEquals(parsed("message")("content").str, "hello")

  test("encodeUserMessageJson properly escapes special characters"):
    val frame = ClaudeConnector.encodeUserMessageJson("line 1\nline 2 with \"quotes\"")
    // JSON-parseable.
    val parsed = ujson.read(frame)
    assertEquals(parsed("message")("content").str, "line 1\nline 2 with \"quotes\"")
    // No raw newlines in the frame body — `sendLine` adds a single trailing `\n` to separate frames.
    assert(!frame.contains("\n"), clue = frame)

  test("headlessArgv: prompt as -p positional, output stream-json, NO --input-format stream-json"):
    val argv = ClaudeConnector.headlessArgv("claude", os.Path("/tmp/impl.md"), "do the thing")
    assert(argv.containsSlice(List("-p", "do the thing")), clue = argv)
    assert(argv.containsSlice(List("--system-prompt-file", "/tmp/impl.md")), clue = argv)
    assert(argv.containsSlice(List("--output-format", "stream-json")), clue = argv)
    // CRITICAL: --input-format stream-json must NOT appear in headless argv. With it, the CLI blocks waiting for
    // stdin user messages that never arrive — the original cause of the 30s init timeout in the first forge-it
    // run. Regression-guarded here.
    assert(!argv.containsSlice(List("--input-format", "stream-json")), clue = argv)

  test("binary override is honoured in argv"):
    val argv = ClaudeConnector.streamingSpecArgv("/opt/local/bin/claude", os.Path("/tmp/x.md"))
    assertEquals(argv.head, "/opt/local/bin/claude")

  /** End-to-end smoke against a fake claude: the connector spawns a shell that emits a transcript, and `runStreaming`
    * returns a session with the right session id + drains the full event stream. Proves the trait wiring without
    * needing the real binary.
    *
    * Implementation detail: we override the binary with `/bin/sh` and use the system-prompt-file *argument value* as a
    * pass-through script. The first three argv elements are `/bin/sh`, `--setting-sources`, `project,local` — sh sees
    * `--setting-sources` as an unknown flag and bails. So we can't reuse the real argv builder verbatim. Instead we
    * test by mocking through `StreamingDriver` directly in `StreamingDriverSuite` (already done) and only verify the
    * argv-construction surface here.
    */
  test("constructor honours the binary parameter (used only by argv builders here)"):
    val c = ClaudeConnector(binary = "/opt/foo/claude")
    // Smoke: instantiation doesn't throw and identity holds.
    assertEquals(c.name, "claude")

  test("encodeToolResultJson wraps the answer in the §7.2 tool_result wire shape"):
    val frame = ClaudeConnector.encodeToolResultJson("toolu_01ABC", "yes please")
    val parsed = ujson.read(frame)
    assertEquals(parsed("type").str, "user")
    assertEquals(parsed("message")("role").str, "user")
    val content = parsed("message")("content").arr
    assertEquals(content.length, 1)
    assertEquals(content(0)("type").str, "tool_result")
    assertEquals(content(0)("tool_use_id").str, "toolu_01ABC")
    assertEquals(content(0)("content").str, "yes please")
    // No raw newlines — `sendLine` is responsible for framing.
    assert(!frame.contains("\n"), clue = frame)

  test("encodeAnswer: Some(id) → tool_result JSON; None → MissingToolUseId adapter error"):
    val ok = ClaudeConnector.encodeAnswer(Some("toolu_42"), "answer").unsafeRunSync()
    assertEquals(ok, ClaudeConnector.encodeToolResultJson("toolu_42", "answer"))
    val bad = ClaudeConnector.encodeAnswer(None, "answer").attempt.unsafeRunSync()
    assert(bad.left.exists(_.isInstanceOf[MissingToolUseId]), clue = bad)
    assert(bad.left.exists(_.getMessage.contains("toolUseId=None")), clue = bad)

  /** Build a fake `claude` shell script that reads ONE JSON frame on stdin, then drives a transcript through stdout
    * (init → echo body → result). Used to verify the full streaming pipeline: argv → spawn → initial-message stdin
    * write → init → drain.
    */
  private def fakeStreamingClaude(sessionId: String): os.Path =
    val script =
      s"""#!/bin/sh
         |IFS= read -r line
         |# Echo init keyed on the supplied session id.
         |echo '{"type":"system","subtype":"init","session_id":"$sessionId"}'
         |# Echo the received frame verbatim — lets the test assert the wire shape that reached stdin.
         |# Wrap as an assistant text event so the parser surfaces it.
         |body=$$(printf '%s' "$$line" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g')
         |echo "{\\"type\\":\\"assistant\\",\\"message\\":{\\"content\\":[{\\"type\\":\\"text\\",\\"text\\":\\"$$body\\"}],\\"usage\\":{\\"output_tokens\\":1}}}"
         |echo '{"type":"result","subtype":"success","is_error":false,"duration_ms":1,"session_id":"'"$sessionId"'"}'
         |""".stripMargin
    val f = os.temp(contents = script, prefix = "fake-claude-stream-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(f, "rwx------")
    f

  test("runStreamingSpec: spawns the CLI, writes the initial user message frame, drains init + body + result"):
    val sid = "sid-stream-1"
    val fake = fakeStreamingClaude(sid)
    val systemPrompt = os.temp(contents = "be helpful", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = ClaudeConnector(binary = fake.toString)
    val (returnedSid, events) = connector
      .runStreamingSpec(systemPrompt, "hello there")
      .flatMap: session =>
        for
          drained <- session.events.compile.toVector
          _ <- session.close()
        yield (session.sessionId, drained)
      .unsafeRunSync()
    assertEquals(returnedSid, sid)
    assertEquals(events.headOption, Some(AgentEvent.Init(sid)))
    assert(events.exists(_.isInstanceOf[AgentEvent.Result]), clue = events)
    // The fake CLI echoes back the encoded frame as an AssistantText. Assert that the wire shape that reached stdin is
    // the §7.1 stream-json user-message frame (not raw text).
    val echoed = events.collectFirst { case AgentEvent.AssistantText(t, _) => t }
    val expectedFrame = ClaudeConnector.encodeUserMessageJson("hello there")
    assertEquals(echoed, Some(expectedFrame))
    // Mirror UserMessage event lands after Init.
    assert(events.contains(AgentEvent.UserMessage("hello there")), clue = events)

  test("resumeStreamingSpec: same pipeline, argv carries --resume <sid>, initial message still required"):
    // Smoke-test the argv shape on its own (already covered exhaustively above) — the round-trip via the fake CLI
    // doesn't exercise argv parsing, only the stdin frame contract. Catches future regressions where the resume path
    // silently drops --resume.
    val argv = ClaudeConnector.resumeStreamingSpecArgv("claude", "abc-123")
    assert(argv.containsSlice(List("--resume", "abc-123")), clue = argv)
    // Functional round-trip through the same fake CLI machinery.
    val sid = "sid-resume-1"
    val fake = fakeStreamingClaude(sid)
    val connector = ClaudeConnector(binary = fake.toString)
    val (returnedSid, events) = connector
      .resumeStreamingSpec(sid, "next turn please")
      .flatMap: session =>
        for
          drained <- session.events.compile.toVector
          _ <- session.close()
        yield (session.sessionId, drained)
      .unsafeRunSync()
    assertEquals(returnedSid, sid)
    val echoed = events.collectFirst { case AgentEvent.AssistantText(t, _) => t }
    assertEquals(echoed, Some(ClaudeConnector.encodeUserMessageJson("next turn please")))

  test("answerQuestion(Some(id), text): writes the tool_result JSON frame on stdin"):
    // Fake CLI that emits init then echoes each subsequent stdin line as an assistant text — lets us read back what
    // answerQuestion actually wrote.
    val script =
      """|#!/bin/sh
         |IFS= read -r initial
         |echo '{"type":"system","subtype":"init","session_id":"sid-q"}'
         |# drop the initial frame; just keep streaming subsequent stdin lines back.
         |while IFS= read -r line; do
         |  body=$(printf '%s' "$line" | sed 's/\\/\\\\/g; s/"/\\"/g')
         |  echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"$body\"}],\"usage\":{\"output_tokens\":1}}}"
         |done""".stripMargin
    val fake = os.temp(contents = script, prefix = "fake-claude-q-", suffix = ".sh", deleteOnExit = true)
    os.perms.set(fake, "rwx------")
    val systemPrompt = os.temp(contents = "x", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = ClaudeConnector(binary = fake.toString)
    val events = connector
      .runStreamingSpec(systemPrompt, "hi")
      .flatMap: session =>
        for
          _ <- session.answerQuestion(Some("toolu_99"), "yes")
          _ <- IO.sleep(150.millis) // let the echo land before we drain
          _ <- session.close()
          drained <- session.events.compile.toVector
        yield drained
      .unsafeRunSync()
    // The echo we want to find is the encoded tool_result frame.
    val expectedFrame = ClaudeConnector.encodeToolResultJson("toolu_99", "yes")
    val texts = events.collect { case AgentEvent.AssistantText(t, _) => t }
    assert(texts.contains(expectedFrame), clue = (expectedFrame, texts))
    // Mirror event for the answer uses the [answer] prefix (driver convention).
    assert(
      events.exists { case AgentEvent.UserMessage(s) => s.startsWith("[answer] yes"); case _ => false },
      clue = events
    )

  test("answerQuestion(None, _): raises MissingToolUseId (parser-regression diagnostic), no stdin write"):
    val fake = fakeStreamingClaude("sid-none")
    val systemPrompt = os.temp(contents = "x", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val connector = ClaudeConnector(binary = fake.toString)
    val result = connector
      .runStreamingSpec(systemPrompt, "hi")
      .flatMap: session =>
        session.answerQuestion(None, "answer").attempt.flatTap(_ => session.kill())
      .unsafeRunSync()
    assert(result.left.exists(_.isInstanceOf[MissingToolUseId]), clue = result)
    assert(result.left.exists(_.getMessage.contains("toolUseId=None")), clue = result)

  test("reviewer methods raise ReviewerNotConfigured (non-retryable) when no ReviewerAssets are configured"):
    val c = ClaudeConnector()
    val fid = FeatureId("feat-1")
    val pid = PieceId("p1")
    val r1 = c.reviewDesign(DesignReviewInput(fid, 1, "")).attempt.unsafeRunSync()
    val r2 = c
      .reviewPr(PrReviewInput(fid, pid, io.forge.core.PrNumber(1), "", "", Vector.empty))
      .attempt
      .unsafeRunSync()
    val r3 = c.refine(RefineInput(fid, pid, "", "")).attempt.unsafeRunSync()
    // All three surface as ReviewerNotConfigured — distinct from ReviewerProcessFailure (which §7.6 marks retryable)
    // so the orchestrator's reviewProcessRetries wrapper does not burn its retry budget on a config mistake.
    Seq(r1, r2, r3).foreach: r =>
      assert(r.left.exists(_.isInstanceOf[ReviewerNotConfigured]), clue = r)
      // Must NOT match the retryable case — guards against an accidental re-conflation.
      assert(!r.left.exists(_.isInstanceOf[ReviewerProcessFailure]), clue = r)
      assert(r.left.exists(_.getMessage.contains("no ReviewerAssets configured")), clue = r)

  test("reviewerArgv: -p prompt positional, --output-format json, inline --json-schema, isolation flags"):
    val argv = ClaudeConnector.reviewerArgv(
      binary = "claude",
      systemPromptPath = os.Path("/tmp/dr.md"),
      schemaContent = """{"type":"object"}""",
      prompt = "review this design"
    )
    assert(argv.startsWith(List("claude")), clue = argv)
    // -p with prompt positional.
    assert(argv.containsSlice(List("-p", "review this design")), clue = argv)
    // Isolation per Slice 0 §2.1.
    assert(argv.containsSlice(List("--setting-sources", "project,local")), clue = argv)
    assert(argv.contains("--strict-mcp-config"), clue = argv)
    // Reviewer-shape output flags: single JSON envelope, NOT stream-json. Critical — `--output-format json` is what
    // makes the CLI emit a single result envelope (carrying the schema payload in `structured_output` or `result`).
    assert(argv.containsSlice(List("--output-format", "json")), clue = argv)
    assert(!argv.containsSlice(List("--output-format", "stream-json")), clue = argv)
    // Schema is inline content, not a path.
    assert(argv.containsSlice(List("--json-schema", """{"type":"object"}""")), clue = argv)
    // System prompt is a file path.
    assert(argv.containsSlice(List("--system-prompt-file", "/tmp/dr.md")), clue = argv)
    // Never include --bare or --input-format stream-json.
    assert(!argv.contains("--bare"), clue = argv)
    assert(!argv.contains("--input-format"), clue = argv)

  test("extractStructuredOutput: pulls structured_output field from a Slice 0 §3.1 envelope"):
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,
        |"structured_output":{"verdict":"approve","blockers":[],"summary":"ok"}}""".stripMargin
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("approve"))

  test("extractStructuredOutput: missing field returns Left for adapter-error routing"):
    val envelope = ujson.read("""{"type":"result","subtype":"success","is_error":false}""")
    assert(
      ClaudeConnector.extractStructuredOutput(envelope).left.exists(_.contains("structured_output")),
      clue = "expected Left mentioning the missing field"
    )

  test("extractStructuredOutput: is_error=true returns Left even when stdout was valid JSON"):
    val envelope = ujson.read("""{"type":"result","is_error":true,"result":"sandbox launch failed"}""")
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.left.exists(_.contains("sandbox launch failed")), clue = result)

  test("extractStructuredOutput: Claude 2.1.153 — parses the `result` JSON string when structured_output is absent"):
    // C16: 2.1.153 dropped the dedicated structured_output field; the schema-conformant payload arrives as the
    // `result` string. Real envelope shape captured against claude 2.1.153.
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,
        |"result":"{\"verdict\":\"request_changes\",\"blockers\":[],\"questions\":[],\"summary\":\"needs work\"}"}""".stripMargin
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("request_changes"))
    assertEquals(result.toOption.flatMap(_.obj.get("summary")).flatMap(_.strOpt), Some("needs work"))

  test("extractStructuredOutput: structured_output wins over `result` when both are present (back-compat)"):
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,
        |"structured_output":{"verdict":"approve","blockers":[],"summary":"ok"},
        |"result":"{\"verdict\":\"request_changes\"}"}""".stripMargin
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("approve"))

  test("extractStructuredOutput: no structured_output and a non-JSON `result` string returns Left"):
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,"result":"I cannot comply with that."}"""
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.left.exists(_.contains("not")), clue = result)

  test("extractStructuredOutput: Claude 2.1.156 — salvages a JSON object wrapped in prose (C18)"):
    // 2.1.156's --json-schema validates but does not hard-enforce; the model sometimes prefixes prose ("Based on…")
    // before the object. The salvage path recovers the single balanced object so a conformant payload still decodes.
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,
        |"result":"Based on the design, here is my review: {\"verdict\":\"approve\",\"blockers\":[],\"questions\":[],\"summary\":\"ok\"} Hope that helps!"}""".stripMargin
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("approve"))
    assertEquals(result.toOption.flatMap(_.obj.get("summary")).flatMap(_.strOpt), Some("ok"))

  test("extractStructuredOutput: Claude 2.1.156 — salvages a JSON object inside a ```json fence (C18)"):
    val envelope = ujson.read(
      """{"type":"result","subtype":"success","is_error":false,
        |"result":"```json\n{\"verdict\":\"request_changes\",\"blockers\":[],\"questions\":[],\"summary\":\"needs work\"}\n```"}""".stripMargin
    )
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("request_changes"))

  test("salvageJsonObject: brace matching is string-aware (braces inside string values don't close the object)"):
    // A naive first-{-to-last-} would mis-span on `}` inside a string literal. The depth scanner must ignore them.
    val salvaged = ClaudeConnector.salvageJsonObject("""prefix {"a":"a } not a close","b":2} suffix""")
    assert(salvaged.isDefined, clue = salvaged)
    assertEquals(salvaged.flatMap(_.obj.get("a")).flatMap(_.strOpt), Some("a } not a close"))
    assertEquals(salvaged.flatMap(_.obj.get("b")).flatMap(_.numOpt), Some(2.0))

  test("salvageJsonObject: prose with no JSON object at all returns None"):
    assertEquals(ClaudeConnector.salvageJsonObject("I cannot comply with that."), None)

  test("extractStructuredOutput: Claude 2.1.156 — recovers raw newlines/tabs inside a multi-line summary (C18)"):
    // The dominant pr-review/claude failure: a bare object whose `summary` carries literal newlines/tabs. ujson rejects
    // raw control chars in strings; normalising them inside string literals makes it parse. Build the envelope with
    // ujson.Obj so `result` is a Str holding a *literal* LF/TAB — the exact shape obj("result").str yields at runtime.
    val rawResult =
      "{\"verdict\":\"approve\",\"blockers\":[],\"questions\":[],\"summary\":\"Line one.\nLine two with a tab\there.\"}"
    val envelope: ujson.Value =
      ujson.Obj("type" -> ujson.Str("result"), "is_error" -> ujson.Bool(false), "result" -> ujson.Str(rawResult))
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("approve"))
    assertEquals(
      result.toOption.flatMap(_.obj.get("summary")).flatMap(_.strOpt),
      Some("Line one.\nLine two with a tab\there.")
    )

  test("extractStructuredOutput: Claude 2.1.156 — prose prefix AND in-string newline together (C18)"):
    val rawResult =
      "Here is my review:\n\n{\"verdict\":\"request_changes\",\"blockers\":[],\"questions\":[],\"summary\":\"First.\nSecond.\"}"
    val envelope: ujson.Value = ujson.Obj("is_error" -> ujson.Bool(false), "result" -> ujson.Str(rawResult))
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isRight, clue = result)
    assertEquals(result.toOption.flatMap(_.obj.get("verdict")).flatMap(_.strOpt), Some("request_changes"))

  test("extractStructuredOutput: unescaped quote inside a string is NOT silently recovered; Left names the reason"):
    // The one failure mode the normaliser deliberately cannot repair (ambiguous). It must surface as Left (schema-fail),
    // and the detail must carry the underlying ujson reason + the diagnostic so the batch is self-explaining.
    val rawResult = "{\"verdict\":\"approve\",\"summary\":\"the \"default\" option\"}"
    val envelope: ujson.Value = ujson.Obj("is_error" -> ujson.Bool(false), "result" -> ujson.Str(rawResult))
    val result = ClaudeConnector.extractStructuredOutput(envelope)
    assert(result.isLeft, clue = result)
    assert(result.left.exists(_.contains("underlying:")), clue = result)
    assert(result.left.exists(_.contains("[diag")), clue = result)

  test("normalizeControlCharsInStrings: escapes control chars inside strings, leaves them outside untouched"):
    // Inside a string: literal newline → \n. Outside (between tokens): left as-is so it can't smuggle structure in.
    val in = "prose\nhere {\"a\":\"x\ny\"}"
    val out = ClaudeConnector.normalizeControlCharsInStrings(in)
    assertEquals(out, "prose\nhere {\"a\":\"x\\ny\"}")
    // The object portion is now parseable; the leading prose newline is preserved verbatim.
    assert(ClaudeConnector.salvageJsonObject(out).isDefined)

  test("normalizeControlCharsInStrings: an already-escaped \\n is left intact (no double escaping)"):
    val in = "{\"a\":\"x\\ny\"}"
    assertEquals(ClaudeConnector.normalizeControlCharsInStrings(in), in)

  test("reviewer end-to-end against a fake CLI: schema-conformant envelope decoded to DesignReview"):
    // Fake `claude` = a small shell script that echoes a Slice 0 §3.1 shape envelope and exits 0. Sandboxed from the
    // real binary — verifies the full reviewer plumbing (argv → spawn → collect → extract → decode) works without
    // needing the actual CLI on PATH.
    val envelope =
      """{"type":"result","subtype":"success","is_error":false,
        |"structured_output":{"verdict":"approve","blockers":[],"questions":[],"summary":"All good."}}""".stripMargin
        .replace("\n", " ")
    val fakeClaude = os.temp(
      contents = s"""#!/bin/sh
                    |cat <<'JSON'
                    |$envelope
                    |JSON
                    |""".stripMargin,
      prefix = "fake-claude-",
      suffix = ".sh",
      deleteOnExit = true
    )
    os.perms.set(fakeClaude, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = ClaudeConnector(binary = fakeClaude.toString, reviewerAssets = Some(assets))
    val review = connector
      .reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "design md content"))
      .unsafeRunSync()
    assertEquals(review.verdict, ReviewVerdict.Approve)
    assertEquals(review.summary, "All good.")
    assertEquals(review.blockers, Vector.empty[ReviewBlocker])

  test("reviewer end-to-end against a fake CLI: Claude 2.1.153 `result`-string envelope decoded to DesignReview"):
    // Fake `claude` echoing the *current* (2.1.153) envelope shape: no structured_output field; the schema-conformant
    // JSON is the `result` string. Mirrors the real binary so the full plumbing (collect → extract → decode) is
    // exercised against the shape the live CLI actually emits (C16).
    val envelope =
      """{"type":"result","subtype":"success","is_error":false,""" +
        """"result":"{\"verdict\":\"approve\",\"blockers\":[],\"questions\":[],\"summary\":\"Looks fine.\"}"}"""
    val fakeClaude = os.temp(
      contents = s"""#!/bin/sh
                    |cat <<'JSON'
                    |$envelope
                    |JSON
                    |""".stripMargin,
      prefix = "fake-claude-2153-",
      suffix = ".sh",
      deleteOnExit = true
    )
    os.perms.set(fakeClaude, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = ClaudeConnector(binary = fakeClaude.toString, reviewerAssets = Some(assets))
    val review = connector
      .reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "design md content"))
      .unsafeRunSync()
    assertEquals(review.verdict, ReviewVerdict.Approve)
    assertEquals(review.summary, "Looks fine.")

  test("reviewer end-to-end against a fake CLI: Claude 2.1.156 prose-wrapped `result` decoded to DesignReview (C18)"):
    // Fake `claude` echoing the 2.1.156 prose-leak shape: --json-schema let free-form text through, so the
    // schema-conformant object is embedded in the `result` string after a preamble. The salvage path must recover it
    // so the full plumbing (collect → extract → salvage → decode) still yields a DesignReview.
    val inner = """{\"verdict\":\"approve\",\"blockers\":[],\"questions\":[],\"summary\":\"Salvaged.\"}"""
    val envelope =
      s"""{"type":"result","subtype":"success","is_error":false,""" +
        s""""result":"Based on the design document: $inner"}"""
    val fakeClaude = os.temp(
      contents = s"""#!/bin/sh
                    |cat <<'JSON'
                    |$envelope
                    |JSON
                    |""".stripMargin,
      prefix = "fake-claude-2156-",
      suffix = ".sh",
      deleteOnExit = true
    )
    os.perms.set(fakeClaude, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = ClaudeConnector(binary = fakeClaude.toString, reviewerAssets = Some(assets))
    val review = connector
      .reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "design md content"))
      .unsafeRunSync()
    assertEquals(review.verdict, ReviewVerdict.Approve)
    assertEquals(review.summary, "Salvaged.")

  test("reviewer end-to-end against a fake CLI: non-zero exit surfaces as ReviewerProcessFailure"):
    val fakeClaude = os.temp(
      contents = """#!/bin/sh
                   |echo "boom" >&2
                   |exit 17
                   |""".stripMargin,
      prefix = "fake-claude-fail-",
      suffix = ".sh",
      deleteOnExit = true
    )
    os.perms.set(fakeClaude, "rwx------")
    val schema = os.temp(contents = """{"type":"object"}""", prefix = "schema-", suffix = ".json", deleteOnExit = true)
    val systemPrompt =
      os.temp(contents = "Review the design", prefix = "sys-", suffix = ".md", deleteOnExit = true)
    val assets = ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      prReview = ReviewerAssets.PerMethod(schema, systemPrompt),
      refine = ReviewerAssets.PerMethod(schema, systemPrompt)
    )
    val connector = ClaudeConnector(binary = fakeClaude.toString, reviewerAssets = Some(assets))
    val r = connector.reviewDesign(DesignReviewInput(FeatureId("feat-1"), 1, "x")).attempt.unsafeRunSync()
    assert(r.left.exists(_.isInstanceOf[ReviewerProcessFailure]), clue = r)
    assert(r.left.exists(_.getMessage.contains("exited 17")), clue = r)
