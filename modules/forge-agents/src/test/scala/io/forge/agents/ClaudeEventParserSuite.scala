package io.forge.agents

import io.forge.core.QuestionSeverity

class ClaudeEventParserSuite extends munit.FunSuite:

  private def parse(line: String): Vector[AgentEvent] =
    ClaudeEventParser.parse(line).fold(msg => fail(s"parse failed: $msg"), identity)

  test("empty line yields no events"):
    assertEquals(ClaudeEventParser.parse(""), Right(Vector.empty))
    assertEquals(ClaudeEventParser.parse("   "), Right(Vector.empty))

  test("malformed JSON returns Left"):
    assert(ClaudeEventParser.parse("not json").isLeft)
    assert(ClaudeEventParser.parse("{").isLeft)

  test("system/init produces Init event with session id"):
    val line =
      """{"type":"system","subtype":"init","cwd":"/tmp","session_id":"abc-123","model":"claude-opus-4-7"}"""
    assertEquals(parse(line), Vector(AgentEvent.Init("abc-123")))

  test("system/init missing session_id returns Left (adapter bug)"):
    val line = """{"type":"system","subtype":"init","cwd":"/tmp"}"""
    assert(ClaudeEventParser.parse(line).isLeft)

  test("system hook events are skipped"):
    val started = """{"type":"system","subtype":"hook_started","hook_name":"x","session_id":"s"}"""
    val response = """{"type":"system","subtype":"hook_response","hook_name":"x","session_id":"s"}"""
    assertEquals(parse(started), Vector.empty)
    assertEquals(parse(response), Vector.empty)

  test("rate_limit_event is skipped"):
    val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"},"session_id":"s"}"""
    assertEquals(parse(line), Vector.empty)

  test("assistant text content produces AssistantText with usage.output_tokens"):
    val line =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"hello"}],"usage":{"output_tokens":3}},"session_id":"s"}"""
    assertEquals(parse(line), Vector(AgentEvent.AssistantText("hello", 3)))

  test("assistant text without usage falls back to 0 outputTokens"):
    val line =
      """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]},"session_id":"s"}"""
    assertEquals(parse(line), Vector(AgentEvent.AssistantText("hi", 0)))

  test("assistant message with multiple content blocks emits multiple events"):
    val line =
      """{"type":"assistant","message":{"content":[
        |  {"type":"text","text":"running tests"},
        |  {"type":"tool_use","name":"Bash","input":{"command":"sbt test","description":"run tests"}}
        |],"usage":{"output_tokens":12}},"session_id":"s"}""".stripMargin
    val events = parse(line)
    assertEquals(events.size, 2)
    assertEquals(events(0), AgentEvent.AssistantText("running tests", 12))
    events(1) match
      case AgentEvent.ToolUse(name, summary) =>
        assertEquals(name, "Bash")
        assert(summary.contains("run tests"), clue = summary)
      case other => fail(s"expected ToolUse, got $other")

  test("tool_use Read/Write/Edit summary includes file_path"):
    val line =
      """{"type":"assistant","message":{"content":[
        |  {"type":"tool_use","name":"Read","input":{"file_path":"/tmp/foo.scala"}}
        |]},"session_id":"s"}""".stripMargin
    parse(line) match
      case Vector(AgentEvent.ToolUse(name, summary)) =>
        assertEquals(name, "Read")
        assert(summary.contains("/tmp/foo.scala"), clue = summary)
      case other => fail(s"expected single ToolUse, got $other")

  test("AskUserQuestion tool_use maps to AgentEvent.AskUserQuestion with Some(toolUseId)"):
    val line =
      """{"type":"assistant","message":{"content":[
        |  {"type":"tool_use","id":"toolu_01ABC","name":"AskUserQuestion","input":{
        |    "questions":[{
        |      "question":"Which database?",
        |      "options":[
        |        {"label":"Postgres","description":"row store"},
        |        {"label":"Redis","description":"in-memory"}
        |      ],
        |      "header":"DB","multiSelect":false
        |    }]
        |  }}
        |]},"session_id":"s"}""".stripMargin
    parse(line) match
      case Vector(AgentEvent.AskUserQuestion(q, toolUseId)) =>
        assertEquals(q.text, "Which database?")
        assertEquals(q.options, Vector("Postgres", "Redis"))
        assertEquals(q.allowFreeText, true)
        assertEquals(q.severity, QuestionSeverity.Clarifying)
        // Native always carries an id — captured from the tool_use block for the §7.2 tool_result reply path.
        assertEquals(toolUseId, Some("toolu_01ABC"))
      case other => fail(s"expected single AskUserQuestion, got $other")

  test("AskUserQuestion with malformed input is dropped (not raised)"):
    val line =
      """{"type":"assistant","message":{"content":[
        |  {"type":"tool_use","id":"toolu_01XYZ","name":"AskUserQuestion","input":{}}
        |]},"session_id":"s"}""".stripMargin
    assertEquals(parse(line), Vector.empty)

  test("AskUserQuestion missing the block-level `id` is dropped (would mis-route as HaltWithQuestion path)"):
    // Defensive: a Native AskUserQuestion event MUST have a tool_use id. Emitting AskUserQuestion(_, None) would
    // look like the HaltWithQuestion path to the orchestrator and silently route the answer through the wrong
    // mechanism. Drop instead.
    val line =
      """{"type":"assistant","message":{"content":[
        |  {"type":"tool_use","name":"AskUserQuestion","input":{
        |    "questions":[{"question":"q?","options":[],"header":"x","multiSelect":false}]
        |  }}
        |]},"session_id":"s"}""".stripMargin
    assertEquals(parse(line), Vector.empty)

  test("result event emits CostUpdate then Result"):
    val line =
      """{"type":"result","subtype":"success","is_error":false,"duration_ms":1500,"session_id":"s",
        |"total_cost_usd":0.0629,"usage":{"input_tokens":6,"cache_creation_input_tokens":8565,
        |"cache_read_input_tokens":18383,"output_tokens":6},
        |"modelUsage":{"claude-opus-4-7[1m]":{"inputTokens":6,"outputTokens":6}}}""".stripMargin
    val events = parse(line)
    assertEquals(events.size, 2)
    events(0) match
      case AgentEvent.CostUpdate(c) =>
        assertEquals(c.provider, "anthropic")
        assertEquals(c.model, "claude-opus-4-7[1m]")
        assertEquals(c.inputTokens, 6L + 8565L + 18383L)
        assertEquals(c.outputTokens, 6L)
        assertEquals(c.usd, BigDecimal(0.0629))
      case other => fail(s"expected CostUpdate first, got $other")
    assertEquals(events(1), AgentEvent.Result(success = true, durationMs = 1500L))

  test("result with is_error:true produces success=false"):
    val line =
      """{"type":"result","subtype":"error_max_turns","is_error":true,"duration_ms":42,"session_id":"s"}"""
    parse(line).last match
      case AgentEvent.Result(success, durationMs) =>
        assertEquals(success, false)
        assertEquals(durationMs, 42L)
      case other => fail(s"expected Result, got $other")

  test("Slice 0 headless transcript replays end-to-end"):
    val path = os.pwd / "docs" / "slice-0" / "transcripts" / "01-claude-headless.jsonl"
    assume(os.exists(path), "Slice 0 transcript not present in working directory")
    val lines = os.read.lines(path).filter(_.nonEmpty)
    val all = lines.toVector.flatMap(l => parse(l))
    val sessionIds = all.collect { case AgentEvent.Init(id) => id }
    assertEquals(sessionIds, Vector("742b7777-637f-453b-be6c-65bd1cd9eee1"))
    val texts = all.collect { case AgentEvent.AssistantText(t, _) => t }
    assertEquals(texts, Vector("hello"))
    val results = all.collect { case r: AgentEvent.Result => r }
    assertEquals(results.size, 1)
    assertEquals(results.head.success, true)
    val costs = all.collect { case AgentEvent.CostUpdate(c) => c }
    assertEquals(costs.size, 1)
    assertEquals(costs.head.provider, "anthropic")
    assertEquals(costs.head.usd, BigDecimal(0.06290275))

  test("Slice 0 schema transcript carries CostUpdate + Result (structured_output is read elsewhere)"):
    val path = os.pwd / "docs" / "slice-0" / "transcripts" / "07-claude-schema.json"
    assume(os.exists(path), "Slice 0 schema transcript not present in working directory")
    val events = parse(os.read(path).trim)
    assert(events.exists(_.isInstanceOf[AgentEvent.CostUpdate]))
    assert(events.exists(_.isInstanceOf[AgentEvent.Result]))
