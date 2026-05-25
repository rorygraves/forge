package io.forge.agents

import io.forge.core.{Question, QuestionSeverity}
import ujson.Value

/** Decode the structured JSON the reviewer CLIs return into Forge's Scala domain types. The shape mirrors
  * `~/.forge/schemas/{design-review,code-review,refine}.json` — both reviewers' outputs are validated against the same
  * schemas (§17 "Schemas are shared"), so a single decoder per method handles both connectors.
  *
  * Failure path: `Left(detail)` — the connector wraps as a [[StructuredOutputMalformed]] adapter error (§7.5, not
  * retried by `reviewProcessRetries`). On success the decoder ignores unknown fields so a schema-additive change in a
  * future revision doesn't break v1 connectors.
  */
object ReviewDecoders:

  /** `design-review.json`:
    * {{{
    *   {
    *     "verdict": "approve" | "request_changes",
    *     "blockers": [{ "summary", "path"?, "line"?, "anchorText"? }],
    *     "questions": [{ "text", "options"?, "allowFreeText"?, "severity" }],
    *     "summary": string
    *   }
    * }}}
    */
  def designReview(v: Value): Either[String, DesignReview] =
    for
      obj <- objectOrLeft(v, "design-review root")
      verdict <- verdict(obj.get("verdict"))
      blockers <- blockers(obj.get("blockers"))
      questions <- questions(obj.get("questions"))
      summary <- stringOrLeft(obj.get("summary"), "summary")
    yield DesignReview(verdict, blockers, questions, summary)

  /** `code-review.json`:
    * {{{
    *   {
    *     "verdict": "approve" | "request_changes",
    *     "blockers": [{ "summary", "path"?, "line"?, "anchorText"? }],
    *     "summary": string
    *   }
    * }}}
    */
  def prReview(v: Value): Either[String, PrReview] =
    for
      obj <- objectOrLeft(v, "code-review root")
      verdict <- verdict(obj.get("verdict"))
      blockers <- blockers(obj.get("blockers"))
      summary <- stringOrLeft(obj.get("summary"), "summary")
    yield PrReview(verdict, blockers, summary)

  /** `refine.json`:
    * {{{
    *   {
    *     "outcome": "no_change" | "update_plan" | "reopen_design",
    *     "reason": string,
    *     "patch": <inline JSON object>?    // required iff outcome == "update_plan"
    *   }
    * }}}
    *
    * `patch` is preserved verbatim as a JSON string so the orchestrator can validate it against the live manifest
    * separately (§14.3 — "Build `ManifestPatch` from refine output, validate against current manifest").
    *
    * Invariant per §14.3: `update_plan` MUST carry a `patch` (the manifest patch the orchestrator builds the
    * `ManifestPatch` from). Missing-patch on `update_plan` is a [[StructuredOutputMalformed]] situation, not a silent
    * `None`. Conversely, `no_change` / `reopen_design` ignore any patch field even if the model emits one.
    */
  def refineResult(v: Value): Either[String, RefineResult] =
    for
      obj <- objectOrLeft(v, "refine root")
      outcome <- outcome(obj.get("outcome"))
      reason <- stringOrLeft(obj.get("reason"), "reason")
      patchJson <-
        val raw = obj.get("patch").map(ujson.write(_))
        outcome match
          case RefineOutcome.UpdatePlan if raw.isEmpty =>
            Left("outcome 'update_plan' requires a 'patch' object — §14.3 needs it to build a ManifestPatch")
          case RefineOutcome.UpdatePlan => Right(raw)
          case _ => Right(None) // drop any stray patch on non-update outcomes
    yield RefineResult(outcome, reason, patchJson)

  // --- field helpers ----

  private def objectOrLeft(v: Value, ctx: String): Either[String, collection.mutable.Map[String, Value]] =
    v.objOpt.toRight(s"$ctx: expected JSON object, got ${shapeName(v)}")

  private def stringOrLeft(v: Option[Value], field: String): Either[String, String] =
    v.flatMap(_.strOpt).toRight(s"missing or non-string required field '$field'")

  private def verdict(v: Option[Value]): Either[String, ReviewVerdict] =
    stringOrLeft(v, "verdict").flatMap {
      case "approve" => Right(ReviewVerdict.Approve)
      case "request_changes" => Right(ReviewVerdict.RequestChanges)
      case other => Left(s"unknown verdict '$other' (expected 'approve' | 'request_changes')")
    }

  private def outcome(v: Option[Value]): Either[String, RefineOutcome] =
    stringOrLeft(v, "outcome").flatMap {
      case "no_change" => Right(RefineOutcome.NoChange)
      case "update_plan" => Right(RefineOutcome.UpdatePlan)
      case "reopen_design" => Right(RefineOutcome.ReopenDesign)
      case other =>
        Left(s"unknown outcome '$other' (expected 'no_change' | 'update_plan' | 'reopen_design')")
    }

  private def blockers(v: Option[Value]): Either[String, Vector[ReviewBlocker]] =
    v match
      case None => Right(Vector.empty)
      case Some(value) =>
        value.arrOpt.toRight(s"'blockers' expected JSON array, got ${shapeName(value)}").flatMap { arr =>
          arr.iterator.zipWithIndex
            .foldLeft[Either[String, Vector[ReviewBlocker]]](Right(Vector.empty)) {
              case (Left(e), _) => Left(e)
              case (Right(acc), (item, idx)) => blocker(item, idx).map(acc :+ _)
            }
        }

  private def blocker(v: Value, idx: Int): Either[String, ReviewBlocker] =
    objectOrLeft(v, s"blockers[$idx]").flatMap { obj =>
      stringOrLeft(obj.get("summary"), s"blockers[$idx].summary").map { summary =>
        ReviewBlocker(
          summary = summary,
          path = obj.get("path").flatMap(_.strOpt),
          line = obj.get("line").flatMap(_.numOpt).map(_.toInt),
          anchorText = obj.get("anchorText").flatMap(_.strOpt)
        )
      }
    }

  private def questions(v: Option[Value]): Either[String, Vector[Question]] =
    v match
      case None => Right(Vector.empty)
      case Some(value) =>
        value.arrOpt.toRight(s"'questions' expected JSON array, got ${shapeName(value)}").flatMap { arr =>
          arr.iterator.zipWithIndex
            .foldLeft[Either[String, Vector[Question]]](Right(Vector.empty)) {
              case (Left(e), _) => Left(e)
              case (Right(acc), (item, idx)) => question(item, idx).map(acc :+ _)
            }
        }

  private def question(v: Value, idx: Int): Either[String, Question] =
    objectOrLeft(v, s"questions[$idx]").flatMap { obj =>
      for
        text <- stringOrLeft(obj.get("text"), s"questions[$idx].text")
        severityStr <- stringOrLeft(obj.get("severity"), s"questions[$idx].severity")
        severity <- QuestionSeverity.fromString(severityStr).left.map(e => s"questions[$idx].severity: $e")
      yield
        val options = obj
          .get("options")
          .flatMap(_.arrOpt)
          .map(_.iterator.flatMap(_.strOpt).toVector)
          .getOrElse(Vector.empty)
        val allowFreeText = obj.get("allowFreeText").flatMap(_.boolOpt).getOrElse(true)
        Question(text, options, allowFreeText, severity)
    }

  private def shapeName(v: Value): String = v match
    case _: ujson.Obj => "object"
    case _: ujson.Arr => "array"
    case _: ujson.Str => "string"
    case _: ujson.Num => "number"
    case _: ujson.Bool => "boolean"
    case ujson.Null => "null"
