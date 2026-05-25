package io.forge.agents

import io.forge.core.{Question, QuestionSeverity}
import upickle.default.{read, ReadWriter}

/** §7.3 — the prompt-engineered halt envelope a `HaltWithQuestion`-mechanism driver (Codex in v1) is asked to print as
  * its final output when blocked on human judgement:
  *
  * {{{
  *   { "status": "needs_human",
  *     "question": "...",
  *     "options": ["..."],
  *     "allowFreeText": true,
  *     "severity": "blocking" | "clarifying" | "optional" }
  * }}}
  *
  * The Codex event parser calls [[tryParse]] on every `item.completed` agent_message text. A `Some` result demotes the
  * event from `AssistantText` to `HaltWithQuestion`; on `None` the text flows through as ordinary assistant output. The
  * parse is deliberately conservative — anything that isn't a single top-level JSON object with `status ==
  * "needs_human"` stays as plain text — so a model that mentions the literal string `needs_human` in prose can't
  * spuriously trip the halt path.
  */
object HaltWithQuestion:

  /** Wire shape of a §7.3 halt envelope. Kept separate from `Question` so the wire schema is decoded once and
    * validated, and so future Forge revisions can evolve `Question` without churn here.
    */
  private final case class Envelope(
      status: String,
      question: String,
      options: Vector[String],
      allowFreeText: Boolean,
      severity: String
  ) derives ReadWriter

  /** `Right(Some)` = matches `status:"needs_human"` and the rest of the schema parsed cleanly. `Right(None)` = not a
    * halt envelope (text isn't JSON, isn't an object, or doesn't claim `needs_human`). `Left(msg)` = claimed to be a
    * halt envelope but the schema was malformed (adapter bug worth logging).
    */
  def detect(text: String): Either[String, Option[Question]] =
    val trimmed = text.trim
    if !trimmed.startsWith("{") then Right(None)
    else
      val parsed =
        try Right(ujson.read(trimmed))
        catch case e: ujson.ParsingFailedException => Left(e.getMessage)
      parsed.flatMap: value =>
        val statusOpt = value.objOpt.flatMap(_.get("status")).flatMap(_.strOpt)
        if !statusOpt.contains("needs_human") then Right(None)
        else
          try
            val env = read[Envelope](value)
            QuestionSeverity.fromString(env.severity) match
              case Left(err) => Left(err)
              case Right(severity) =>
                Right(Some(Question(env.question, env.options, env.allowFreeText, severity)))
          catch case e: upickle.core.Abort => Left(e.getMessage)

  /** Soft form used by the streaming parser: collapses malformed envelopes to `None` so a single misbehaving model
    * response can't kill the event stream. Adapter-bug detection happens via [[detect]] in tests + diagnostics.
    */
  def tryParse(text: String): Option[Question] =
    detect(text).toOption.flatten
