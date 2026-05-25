package io.forge.agents

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId, QuestionMechanism, SchemaMechanism}
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

  test("runStreamingSpec / resumeStreamingSpec are stubbed pending Task #5 of the Slice 1 trait-shape PR"):
    // Sentinel test — fails as soon as Task #5 lands so we don't forget to remove it. v1.2 §7.1 trait signatures are
    // in place; the spawn paths are not yet wired up.
    val c = ClaudeConnector()
    val r1 = c.runStreamingSpec(os.Path("/tmp/spec.md"), "hi").attempt.unsafeRunSync()
    val r2 = c.resumeStreamingSpec("abc", "next").attempt.unsafeRunSync()
    assert(r1.left.exists(_.getMessage.contains("Task #5")), clue = r1)
    assert(r2.left.exists(_.getMessage.contains("Task #5")), clue = r2)

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
    // makes `structured_output` appear in the envelope (Slice 0 §3.1).
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
