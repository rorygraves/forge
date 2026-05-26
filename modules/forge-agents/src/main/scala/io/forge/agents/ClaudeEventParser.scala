package io.forge.agents

import io.forge.core.{Question, QuestionSeverity}
import io.forge.core.cost.Cost
import ujson.Value

/** §7.1 — parse one line of `claude -p --output-format stream-json --verbose` into zero, one, or more `AgentEvent`s.
  *
  * Wire shape pinned in Slice 0 (`docs/slice-0/transcripts/01-claude-headless.jsonl`):
  *
  *   - `{type:"system", subtype:"init", session_id, ...}` → [[AgentEvent.Init]]
  *   - `{type:"system", subtype:"hook_*", ...}` → skip
  *   - `{type:"assistant", message:{content:[...]}, ...}` → one event per content block (text/tool_use)
  *   - `{type:"rate_limit_event", ...}` → skip in v1 (§22 backoff lives outside the parser)
  *   - `{type:"result", subtype, total_cost_usd, duration_ms, is_error, ...}` → [[AgentEvent.CostUpdate]] then
  *     [[AgentEvent.Result]] (the consumer can rely on `Result` being the last event of the turn)
  *
  * One wire line can produce multiple events (assistant message with several content blocks; result with cost), so the
  * return type is `Vector[AgentEvent]` rather than `Option[AgentEvent]`. `Left(msg)` is reserved for malformed JSON or
  * a recognised shape with a missing required field — both are adapter bugs the connector layer logs and skips.
  */
object ClaudeEventParser:

  /** Parse one stream-json line. */
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
          case Some("system") => parseSystem(v)
          case Some("assistant") => parseAssistant(v)
          case Some("rate_limit_event") => Right(Vector.empty)
          case Some("result") => parseResult(v)
          case Some("user") => Right(Vector.empty) // user-message echo; we mirror via StreamingSession.send
          case Some(_) => Right(Vector.empty) // unknown but harmless
  end parse

  private def parseSystem(v: Value): Either[String, Vector[AgentEvent]] =
    v.obj.get("subtype").flatMap(_.strOpt) match
      case Some("init") =>
        v.obj.get("session_id").flatMap(_.strOpt) match
          case Some(id) => Right(Vector(AgentEvent.Init(id)))
          case None => Left("system/init missing 'session_id'")
      case Some(_) => Right(Vector.empty)
      case None => Left("system event missing 'subtype'")

  private def parseAssistant(v: Value): Either[String, Vector[AgentEvent]] =
    v.obj.get("message").flatMap(_.objOpt) match
      case None => Left("assistant event missing 'message' object")
      case Some(message) =>
        val outputTokens =
          message
            .get("usage")
            .flatMap(_.objOpt)
            .flatMap(_.get("output_tokens"))
            .flatMap(_.numOpt)
            .map(_.toInt)
            .getOrElse(0)
        val contentArr = message.get("content").flatMap(_.arrOpt).getOrElse(Vector.empty)
        val events = contentArr.iterator.flatMap(contentBlockEvent(_, outputTokens)).toVector
        Right(events)

  private def contentBlockEvent(block: Value, outputTokens: Int): Option[AgentEvent] =
    block.objOpt.flatMap(_.get("type")).flatMap(_.strOpt) match
      case Some("text") =>
        block.obj.get("text").flatMap(_.strOpt).map(AgentEvent.AssistantText(_, outputTokens))
      case Some("tool_use") =>
        val name = block.obj.get("name").flatMap(_.strOpt).getOrElse("?")
        if name == "AskUserQuestion" then
          // The block-level `id` is the `tool_use_id` the orchestrator must echo back in the §7.2 `tool_result` frame.
          // Drop the event entirely if it's missing — emitting one with `toolUseId = None` would falsely look like the
          // HaltWithQuestion path and mis-route the answer.
          for
            input <- block.obj.get("input").flatMap(_.objOpt)
            question <- askUserQuestionInputToQuestion(input)
            id <- block.obj.get("id").flatMap(_.strOpt)
          yield AgentEvent.AskUserQuestion(question, Some(id))
        else Some(AgentEvent.ToolUse(name, toolSummary(name, block)))
      case _ => None

  /** Best-effort extraction of a [[Question]] from the `input` of an `AskUserQuestion` tool_use. Claude's tool schema
    * permits N questions per call; Forge models one Q&A interaction at a time, so we take the first question and
    * discard the rest (`questions.tail` is preserved in the raw action log via the connector's debug stream). If the
    * input shape doesn't match (no `questions` array, empty array, missing `question` field), we return `None` and the
    * caller skips the event rather than crash the stream.
    */
  private def askUserQuestionInputToQuestion(input: collection.mutable.Map[String, Value]): Option[Question] =
    for
      questions <- input.get("questions").flatMap(_.arrOpt)
      first <- questions.headOption
      firstObj <- first.objOpt
      text <- firstObj.get("question").flatMap(_.strOpt)
    yield
      val options = firstObj
        .get("options")
        .flatMap(_.arrOpt)
        .map(_.iterator.flatMap(_.objOpt.flatMap(_.get("label")).flatMap(_.strOpt)).toVector)
        .getOrElse(Vector.empty)
      Question(text, options, allowFreeText = true, QuestionSeverity.Clarifying)

  private def toolSummary(name: String, block: Value): String =
    // One-line summary for the action log. Avoid dumping arbitrary tool input here — Bash commands and Edit payloads
    // can be huge and contain secrets; the connector layer logs the full payload elsewhere if needed.
    block.obj.get("input").flatMap(_.objOpt) match
      case None => name
      case Some(input) =>
        // Highlight common-tool key fields without spilling full payloads.
        val hint = name match
          case "Bash" => input.get("description").orElse(input.get("command")).flatMap(_.strOpt)
          case "Read" | "Write" | "Edit" => input.get("file_path").flatMap(_.strOpt)
          case "Grep" | "Glob" => input.get("pattern").flatMap(_.strOpt)
          case _ => None
        hint.fold(name)(h => s"$name($h)".take(200))

  private def parseResult(v: Value): Either[String, Vector[AgentEvent]] =
    val obj = v.obj
    val isError = obj.get("is_error").flatMap(_.boolOpt).getOrElse(false)
    val durationMs = obj.get("duration_ms").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
    val result = AgentEvent.Result(success = !isError, durationMs = durationMs)
    val costEvent =
      for
        usd <- obj.get("total_cost_usd").flatMap(_.numOpt).map(d => BigDecimal(d))
        usage = obj.get("usage").flatMap(_.objOpt)
        inputTokens = usage
          .map: u =>
            val base = u.get("input_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
            val cacheRead = u.get("cache_read_input_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
            val cacheWrite = u.get("cache_creation_input_tokens").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
            base + cacheRead + cacheWrite
          .getOrElse(0L)
        outputTokens = usage.flatMap(_.get("output_tokens")).flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
        // `modelUsage` is keyed by versioned model name; v1 takes the first key. If empty, fall back to the
        // assistant model carried in earlier events — but we don't see those here, so emit "unknown" as a marker.
        model = obj
          .get("modelUsage")
          .flatMap(_.objOpt)
          .flatMap(_.headOption.map(_._1))
          .getOrElse("unknown")
      yield AgentEvent.CostUpdate(Cost("anthropic", model, inputTokens, outputTokens, usd))
    Right(costEvent.toVector :+ result)
