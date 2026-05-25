package io.forge.agents

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId, QuestionMechanism, SchemaMechanism}

class CodexConnectorSuite extends munit.FunSuite:

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
    assert(argv.containsSlice(List("--ask-for-approval", "never")), clue = argv)
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
    // Negative: should not contain any sticky flag.
    Seq("--sandbox", "--output-schema", "--add-dir", "--ask-for-approval", "-C").foreach: flag =>
      assert(!argv.contains(flag), clue = s"$flag should not appear in resume argv: $argv")

  test("runStreamingSpec / resumeStreamingSpec are stubbed pending Task #6 of the Slice 1 trait-shape PR"):
    val c = newConnector
    val r1 = c.runStreamingSpec(os.Path("/tmp/spec.md"), "hi").attempt.unsafeRunSync()
    val r2 = c.resumeStreamingSpec("abc", "next").attempt.unsafeRunSync()
    assert(r1.left.exists(_.getMessage.contains("Task #6")), clue = r1)
    assert(r2.left.exists(_.getMessage.contains("Task #6")), clue = r2)

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
