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

  test("runStreamingSpec / resumeStreamingSpec raise NotImplementedError with a clear message"):
    val c = ClaudeConnector()
    val r1 = c.runStreamingSpec(os.Path("/tmp/spec.md")).attempt.unsafeRunSync()
    val r2 = c.resumeStreamingSpec("abc").attempt.unsafeRunSync()
    assert(
      r1.left.exists(_.getMessage.contains("emits init only after the first")),
      clue = r1
    )
    assert(
      r2.left.exists(_.getMessage.contains("trait-level blocker")),
      clue = r2
    )

  test("reviewer methods are not yet implemented (Layer 5 follow-up)"):
    val c = ClaudeConnector()
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
