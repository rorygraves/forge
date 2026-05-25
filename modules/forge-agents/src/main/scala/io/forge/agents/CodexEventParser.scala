package io.forge.agents

import ujson.Value

/** §7.1 — parse one line of `codex exec --json` into zero, one, or more `AgentEvent`s.
  *
  * Wire shape pinned in Slice 0 (`docs/slice-0/transcripts/04-codex-headless.jsonl`):
  *
  *   - `{type:"thread.started", thread_id}` → [[AgentEvent.Init]]
  *   - `{type:"turn.started"}` → skip
  *   - `{type:"item.completed", item:{type:"agent_message", text}}` → [[AgentEvent.AssistantText]] **or**
  *     [[AgentEvent.HaltWithQuestion]] when the text parses as the §7.3 halt envelope
  *   - `{type:"item.completed", item:{type:"<other>", ...}}` → skipped in v1 (Slice 0 didn't pin tool-event shapes)
  *   - `{type:"turn.completed", usage:{...}}` → [[AgentEvent.CostUpdate]] (USD via [[PriceTable]]) then
  *     [[AgentEvent.Result]] with `durationMs = 0` — wall-clock is the connector's job, not the parser's
  *
  * Unlike `ClaudeEventParser`, the Codex parser carries state: the model name (used as the `Cost.model` key) and the
  * [[PriceTable]] needed to convert `turn.completed.usage` tokens into USD. Slice 0 §2.2 documents why: Codex emits
  * token counts only — no `total_cost_usd` — and the price table lives outside the parser by design.
  *
  * Sticky-settings rule (§7.10(c)) is enforced at the connector layer; the parser is read-only on the event stream and
  * doesn't need to know about resume vs fresh spawn.
  */
final class CodexEventParser(priceTable: PriceTable, model: String):

  def parse(line: String): Either[String, Vector[AgentEvent]] =
    val trimmed = line.trim
    if trimmed.isEmpty then Right(Vector.empty)
    else
      val parsed =
        try Right(ujson.read(trimmed))
        catch case e: ujson.ParsingFailedException => Left(s"malformed JSON: ${e.getMessage}")
      parsed.flatMap: v =>
        v.objOpt.flatMap(_.get("type")).flatMap(_.strOpt) match
          case None => Left("missing required field 'type'")
          case Some("thread.started") => parseThreadStarted(v)
          case Some("turn.started") => Right(Vector.empty)
          case Some("item.completed") => parseItemCompleted(v)
          case Some("turn.completed") => parseTurnCompleted(v)
          case Some(_) => Right(Vector.empty)
  end parse

  private def parseThreadStarted(v: Value): Either[String, Vector[AgentEvent]] =
    v.obj.get("thread_id").flatMap(_.strOpt) match
      case Some(id) => Right(Vector(AgentEvent.Init(id)))
      case None => Left("thread.started missing 'thread_id'")

  private def parseItemCompleted(v: Value): Either[String, Vector[AgentEvent]] =
    v.obj.get("item").flatMap(_.objOpt) match
      case None => Left("item.completed missing 'item' object")
      case Some(item) =>
        item.get("type").flatMap(_.strOpt) match
          case Some("agent_message") =>
            item.get("text").flatMap(_.strOpt) match
              case None => Right(Vector.empty) // shape claims agent_message but no text — drop quietly
              case Some(text) =>
                HaltWithQuestion.tryParse(text) match
                  case Some(q) => Right(Vector(AgentEvent.HaltWithQuestion(q)))
                  case None => Right(Vector(AgentEvent.AssistantText(text, outputTokens = 0)))
          case _ => Right(Vector.empty) // other item types deferred (tool calls etc.)

  private def parseTurnCompleted(v: Value): Either[String, Vector[AgentEvent]] =
    val tokens = v.obj.get("usage").flatMap(_.objOpt) match
      case None => CodexTokens(0L, 0L, 0L, 0L)
      case Some(u) =>
        CodexTokens(
          inputTokens = u.get("input_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
          cachedInputTokens = u.get("cached_input_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
          outputTokens = u.get("output_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
          reasoningOutputTokens = u.get("reasoning_output_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
        )
    // `usdFor` returns `None` for missing-model and for malformed shapes (§7.10(b)). On `None` the orchestrator emits
    // `harness.price_missing` once per (feature, model) and proceeds with usd = 0 for budget accounting; mirror that
    // behaviour in the parser by emitting Cost with usd = 0 so the action-log shape stays uniform and downstream
    // analysis can spot the gap by filtering on usd == 0.
    val usd = priceTable.usdFor(model, tokens).getOrElse(BigDecimal(0))
    val cost = AgentEvent.CostUpdate(
      Cost(
        provider = "openai",
        model = model,
        inputTokens = tokens.inputTokens,
        outputTokens = tokens.outputTokens,
        usd = usd
      )
    )
    // Codex `turn.completed` carries no success/duration fields; the connector layer decorates wall-clock if it cares.
    Right(Vector(cost, AgentEvent.Result(success = true, durationMs = 0L)))

object CodexEventParser:
  /** Convenience constructor for tests / call sites that don't have a price table loaded. */
  def empty(model: String): CodexEventParser = new CodexEventParser(PriceTable.empty, model)
