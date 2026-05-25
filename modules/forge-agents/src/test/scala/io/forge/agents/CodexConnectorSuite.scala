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

  test("runStreamingSpec / resumeStreamingSpec raise NotImplementedError with a clear message"):
    val c = newConnector
    val r1 = c.runStreamingSpec(os.Path("/tmp/spec.md")).attempt.unsafeRunSync()
    val r2 = c.resumeStreamingSpec("abc").attempt.unsafeRunSync()
    assert(r1.left.exists(_.getMessage.contains("multi-turn facade")), clue = r1)
    assert(r2.left.exists(_.getMessage.contains("multi-turn facade")), clue = r2)

  test("reviewer methods are not yet implemented (Layer 5 follow-up)"):
    val c = newConnector
    val fid = FeatureId("feat-1")
    val pid = PieceId("p1")
    val r1 = c.reviewDesign(DesignReviewInput(fid, 1, "")).attempt.unsafeRunSync()
    val r2 = c
      .reviewPr(PrReviewInput(fid, pid, io.forge.core.PrNumber(1), "", "", Vector.empty))
      .attempt
      .unsafeRunSync()
    val r3 = c.refine(RefineInput(fid, pid, "", "")).attempt.unsafeRunSync()
    assert(r1.isLeft, clue = r1)
    assert(r2.isLeft, clue = r2)
    assert(r3.isLeft, clue = r3)
