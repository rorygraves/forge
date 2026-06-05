package io.forge.agents

import munit.FunSuite

/** S4-3 — the reviewer/sensor one-shot cost extractors. The connectors decode the verdict from the same envelope that
  * carries the call's cost; these pin that the cost is read off correctly (Claude) and that the Codex `turn.completed`
  * usage line is recovered from the drained JSONL (Codex).
  */
class ReviewerCostExtractionSuite extends FunSuite:

  // --- Claude: extractReviewerCost off the `--output-format json` result envelope -------------------------------

  test("Claude: extractReviewerCost reads total_cost_usd / usage / modelUsage"):
    val envelope = ujson.read(
      """{
        |  "type": "result",
        |  "subtype": "success",
        |  "is_error": false,
        |  "total_cost_usd": 0.188888,
        |  "usage": {
        |    "input_tokens": 1,
        |    "cache_creation_input_tokens": 196,
        |    "cache_read_input_tokens": 27156,
        |    "output_tokens": 173
        |  },
        |  "modelUsage": { "claude-haiku-4-5": { "costUSD": 0.188888 } },
        |  "structured_output": { "verdict": "approve" }
        |}""".stripMargin
    )
    val cost = ClaudeConnector.extractReviewerCost(envelope)
    assert(cost.isDefined, "a result envelope with total_cost_usd must yield a Cost")
    val c = cost.get
    assertEquals(c.provider, "anthropic")
    assertEquals(c.model, "claude-haiku-4-5")
    assertEquals(c.inputTokens, 1L + 196L + 27156L)
    assertEquals(c.outputTokens, 173L)
    assertEquals(c.usd, BigDecimal(0.188888))

  test("Claude: an envelope with no total_cost_usd yields None"):
    val envelope = ujson.read("""{ "type": "result", "is_error": true, "result": "boom" }""")
    assertEquals(ClaudeConnector.extractReviewerCost(envelope), None)

  test("Claude: a non-object envelope yields None"):
    assertEquals(ClaudeConnector.extractReviewerCost(ujson.read("\"oops\"")), None)

  // --- Codex: extractTurnTokens off the drained JSONL stream ----------------------------------------------------

  test("Codex: extractTurnTokens reads the turn.completed usage line"):
    val lines = Vector(
      """{"type":"thread.started","thread_id":"t-1"}""",
      """{"type":"turn.started"}""",
      """{"type":"item.completed","item":{"id":"i0","type":"agent_message","text":"{}"}}""",
      """{"type":"turn.completed","usage":{"input_tokens":25552,"cached_input_tokens":2432,"output_tokens":64,"reasoning_output_tokens":37}}"""
    )
    val tokens = CodexConnector.extractTurnTokens(lines)
    assert(tokens.isDefined)
    val t = tokens.get
    assertEquals(t.inputTokens, 25552L)
    assertEquals(t.cachedInputTokens, 2432L)
    assertEquals(t.outputTokens, 64L)
    assertEquals(t.reasoningOutputTokens, 37L)

  test("Codex: no turn.completed line yields None"):
    val lines = Vector(
      """{"type":"thread.started","thread_id":"t-1"}""",
      """{"type":"item.completed","item":{"type":"agent_message","text":"{}"}}"""
    )
    assertEquals(CodexConnector.extractTurnTokens(lines), None)

  test("Codex: a turn.completed with no usage object defaults all token counts to 0"):
    val lines = Vector("""{"type":"turn.completed"}""")
    assertEquals(CodexConnector.extractTurnTokens(lines), Some(CodexTokens(0L, 0L, 0L, 0L)))
