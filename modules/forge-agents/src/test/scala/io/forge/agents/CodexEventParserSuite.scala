package io.forge.agents

import io.forge.core.QuestionSeverity

class CodexEventParserSuite extends munit.FunSuite:

  private val model = "gpt-5-codex"

  private val priceTable = PriceTable(
    PriceTable.CurrentSchemaVersion,
    Map(
      model -> ModelPrice(
        inputPerMillionUsd = BigDecimal(1.25),
        cachedInputPerMillionUsd = BigDecimal(0.125),
        outputPerMillionUsd = BigDecimal(10.00),
        reasoningOutputPerMillionUsd = BigDecimal(10.00)
      )
    )
  )

  private def parser = new CodexEventParser(priceTable, model)
  private def parserNoPrices = CodexEventParser.empty(model)

  private def parse(p: CodexEventParser, line: String): Vector[AgentEvent] =
    p.parse(line).fold(msg => fail(s"parse failed: $msg"), identity)

  test("empty line yields no events"):
    assertEquals(parser.parse(""), Right(Vector.empty))

  test("malformed JSON returns Left"):
    assert(parser.parse("not json").isLeft)

  test("thread.started produces Init with thread_id"):
    val line = """{"type":"thread.started","thread_id":"019e5e5a-bb77"}"""
    assertEquals(parse(parser, line), Vector(AgentEvent.Init("019e5e5a-bb77")))

  test("thread.started missing thread_id is Left (adapter bug)"):
    assert(parser.parse("""{"type":"thread.started"}""").isLeft)

  test("turn.started is skipped"):
    assertEquals(parse(parser, """{"type":"turn.started"}"""), Vector.empty)

  test("item.completed agent_message text → AssistantText"):
    val line =
      """{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"hello"}}"""
    parse(parser, line) match
      case Vector(AgentEvent.AssistantText(t, _)) => assertEquals(t, "hello")
      case other => fail(s"expected AssistantText, got $other")

  test("item.completed agent_message whose text is a halt envelope → AskUserQuestion(_, None)"):
    // v1.2 §7.1: Codex's HaltWithQuestion path emits the same AgentEvent.AskUserQuestion as Claude's Native path,
    // distinguished only by `toolUseId = None` (no wire-level tool use exists for the §7.3 envelope).
    val envelope =
      """{\"status\":\"needs_human\",\"question\":\"q?\",\"options\":[\"A\",\"B\"],\"allowFreeText\":true,\"severity\":\"blocking\"}"""
    val line =
      s"""{"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"$envelope"}}"""
    parse(parser, line) match
      case Vector(AgentEvent.AskUserQuestion(q, toolUseId)) =>
        assertEquals(q.text, "q?")
        assertEquals(q.options, Vector("A", "B"))
        assertEquals(q.allowFreeText, true)
        assertEquals(q.severity, QuestionSeverity.Blocking)
        assertEquals(toolUseId, None)
      case other => fail(s"expected AskUserQuestion(_, None), got $other")

  test("item.completed of unrecognised item type is skipped"):
    val line =
      """{"type":"item.completed","item":{"id":"x","type":"tool_call","name":"shell"}}"""
    assertEquals(parse(parser, line), Vector.empty)

  test("turn.completed emits CostUpdate (via PriceTable) then Result"):
    val line =
      """{"type":"turn.completed","usage":{
        |"input_tokens":1000,"cached_input_tokens":200,"output_tokens":500,"reasoning_output_tokens":100}}""".stripMargin
    val events = parse(parser, line)
    assertEquals(events.size, 2)
    events(0) match
      case AgentEvent.CostUpdate(c) =>
        assertEquals(c.provider, "openai")
        assertEquals(c.model, model)
        assertEquals(c.inputTokens, 1000L)
        assertEquals(c.outputTokens, 500L)
        // Expected USD per §7.10(b) formula:
        //   uncachedInput  = 1000 - 200 = 800  @ 1.25/M  = 0.001
        //   cachedInput    = 200            @ 0.125/M = 0.000025
        //   nonReasoning   = 500 - 100 = 400  @ 10/M    = 0.004
        //   reasoning      = 100            @ 10/M    = 0.001
        //   total                                         0.006025
        assertEquals(c.usd, BigDecimal("0.006025"))
      case other => fail(s"expected CostUpdate, got $other")
    assertEquals(events(1), AgentEvent.Result(success = true, durationMs = 0L))

  test("turn.completed with empty PriceTable produces CostUpdate with usd = 0"):
    val line =
      """{"type":"turn.completed","usage":{
        |"input_tokens":100,"cached_input_tokens":0,"output_tokens":50,"reasoning_output_tokens":0}}""".stripMargin
    parse(parserNoPrices, line)(0) match
      case AgentEvent.CostUpdate(c) =>
        assertEquals(c.usd, BigDecimal(0))
        assertEquals(c.inputTokens, 100L)
      case other => fail(s"expected CostUpdate, got $other")

  test("turn.failed produces an AssistantText carrying the error message and Result(success=false)"):
    // Real-CLI shape observed against codex 0.133 when the model is rejected by the account tier:
    //   {"type":"error","message":"..."}
    //   {"type":"turn.failed","error":{"message":"..."}}
    // The parser produces a terminal Result(success=false) so the "stream terminates with Result" contract holds; the
    // AssistantText is the diagnostic carrier (no TurnFailed variant on AgentEvent — keeps the surface narrow).
    val line =
      """{"type":"turn.failed","error":{"message":"The 'gpt-5-codex' model is not supported when using Codex with a ChatGPT account."}}"""
    val events = parse(parser, line)
    assertEquals(events.size, 2, clue = events)
    events(0) match
      case AgentEvent.AssistantText(text, _) =>
        assert(text.startsWith("[codex turn.failed] "), clue = text)
        assert(text.contains("not supported when using Codex with a ChatGPT account"), clue = text)
      case other => fail(s"expected AssistantText carrying the error, got $other")
    assertEquals(events(1), AgentEvent.Result(success = false, durationMs = 0L))

  test("turn.failed with no error.message falls back to a generic diagnostic"):
    val line = """{"type":"turn.failed"}"""
    val events = parse(parser, line)
    assertEquals(events.size, 2)
    events(0) match
      case AgentEvent.AssistantText(text, _) =>
        assert(text.contains("no error.message in wire frame"), clue = text)
      case other => fail(s"expected fallback AssistantText, got $other")
    assertEquals(events(1), AgentEvent.Result(success = false, durationMs = 0L))

  test("error event is skipped (the terminal turn.failed carries the same diagnostic)"):
    // Codex emits a standalone `error` line before `turn.failed`. Surfacing both would double-emit the message in the
    // action log.
    val line =
      """{"type":"error","message":"{\"type\":\"error\",\"status\":400,\"error\":{\"type\":\"invalid_request_error\"}}"}"""
    assertEquals(parse(parser, line), Vector.empty)

  test("turn.completed with no usage falls back to zero tokens / zero usd"):
    val line = """{"type":"turn.completed"}"""
    parse(parser, line)(0) match
      case AgentEvent.CostUpdate(c) =>
        assertEquals(c.inputTokens, 0L)
        assertEquals(c.outputTokens, 0L)
        assertEquals(c.usd, BigDecimal(0))
      case other => fail(s"expected CostUpdate, got $other")

  // The fixture transcripts mix in a stray stderr line — see Slice 0 report §2.2 ("Stderr emits 'Reading additional
  // input from stdin...' even when a prompt is provided as an arg — Forge must separate stderr from stdout when
  // consuming `--json` output"). The connector handles the stderr/stdout split; the parser only sees stdout. The
  // transcript fixtures were captured before that split, so we filter to JSON-looking lines here.
  private def jsonLinesOf(path: os.Path): Vector[String] =
    os.read.lines(path).filter(l => l.trim.startsWith("{")).toVector

  test("Slice 0 headless transcript replays end-to-end"):
    val path = os.pwd / "docs" / "slice-0" / "transcripts" / "04-codex-headless.jsonl"
    assume(os.exists(path), "Slice 0 transcript not present in working directory")
    val all = jsonLinesOf(path).flatMap(l => parse(parser, l))
    val ids = all.collect { case AgentEvent.Init(id) => id }
    assertEquals(ids, Vector("019e5e5a-bb77-7f21-8ed3-82fcbb7f037d"))
    val texts = all.collect { case AgentEvent.AssistantText(t, _) => t }
    assertEquals(texts, Vector("hello"))
    val results = all.collect { case r: AgentEvent.Result => r }
    assertEquals(results.size, 1)
    val costs = all.collect { case AgentEvent.CostUpdate(c) => c }
    assertEquals(costs.size, 1)
    assertEquals(costs.head.inputTokens, 25505L)
    assertEquals(costs.head.outputTokens, 5L)
    // gpt-5-codex price entry above → usd > 0
    assert(costs.head.usd > BigDecimal(0), clue = costs.head.usd)

  test("Slice 0 schema transcript: schema output text passes through as AssistantText"):
    val path = os.pwd / "docs" / "slice-0" / "transcripts" / "06-codex-schema.jsonl"
    assume(os.exists(path), "Slice 0 schema transcript not present in working directory")
    val all = jsonLinesOf(path).flatMap(l => parse(parser, l))
    val texts = all.collect { case AgentEvent.AssistantText(t, _) => t }
    assertEquals(texts.size, 1)
    // schema output is the agent_message text — not a halt envelope, so it stays as AssistantText
    assert(texts.head.contains("\"verdict\""), clue = texts.head)

  test("parser is stateless across lines (per-line, no implicit ordering)"):
    val turn =
      """{"type":"turn.completed","usage":{"input_tokens":10,"cached_input_tokens":0,"output_tokens":5,"reasoning_output_tokens":0}}"""
    val first = parse(parser, turn)
    val second = parse(parser, turn)
    assertEquals(first, second)
