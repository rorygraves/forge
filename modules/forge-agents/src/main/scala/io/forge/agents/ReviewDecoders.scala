package io.forge.agents

import io.forge.core.{Question, QuestionSeverity}
import io.forge.core.profile.{
  BranchModel,
  Classification,
  ClaudeMdProposal,
  CommandKind,
  CommitIdentity,
  ConventionDeltas,
  Determinism,
  FailureKind,
  MergeStrategy,
  RepoCommand,
  RepoProfile,
  WorkflowProfile
}
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

  /** `repo-profile.json` (§7.11 `RepoProfiler`):
    * {{{
    *   {
    *     "buildTool": string,
    *     "commands": [{ "kind", "argv": [string], "determinism", "required": bool, "autofix": bool }],
    *     "commitIdentity": { "name", "email" },
    *     "workflow": { "reviewRequired": bool, "ciRequiredChecks": [string], "branchModel", "mergeStrategy" }
    *   }
    * }}}
    *
    * `schemaVersion` is **not** in the schema — it is a Forge-internal versioning concern, not an LLM judgment, so the
    * decoder stamps [[RepoProfile.CurrentSchemaVersion]]. This is why feeding a committed `.forge/profile.json` fixture
    * (which carries `schemaVersion`) back through this decoder round-trips: the field is ignored on the way in and
    * re-stamped from the current constant. The enum parses reuse the `forge-core` `fromString` helpers so the accepted
    * wire strings can never drift from the model.
    */
  def repoProfile(v: Value): Either[String, RepoProfile] =
    for
      obj <- objectOrLeft(v, "repo-profile root")
      buildTool <- stringOrLeft(obj.get("buildTool"), "buildTool")
      commands <- repoCommands(obj.get("commands"))
      identity <- commitIdentity(obj.get("commitIdentity"))
      workflow <- workflowProfile(obj.get("workflow"))
    yield RepoProfile(RepoProfile.CurrentSchemaVersion, buildTool, commands, identity, workflow)

  /** `failure-classifier.json` (§7.11 `FailureClassifier`, Task 3.1.4):
    * {{{
    *   {
    *     "kind": "deterministic_fix" | "code_fix" | "flaky" | "env" | "rate_limit" | "unknown",
    *     "confidence": number,            // 0..1
    *     "suggested": "format" | "lint" | "build" | "test" | "typecheck" | null,
    *     "evidence": string               // the log fragment the classifier keyed on
    *   }
    * }}}
    *
    * `suggested` is optional (absent or `null` ⇒ `None`); it names the remediating command for a `deterministic_fix`.
    * The enum parses reuse the `forge-core` `fromString` helpers so the accepted wire strings can never drift from the
    * model the spine routes on.
    */
  def failureClassification(v: Value): Either[String, Classification] =
    for
      obj <- objectOrLeft(v, "failure-classifier root")
      kindStr <- stringOrLeft(obj.get("kind"), "kind")
      kind <- FailureKind.fromString(kindStr).left.map(e => s"kind: $e")
      confidence <- obj.get("confidence").flatMap(_.numOpt).toRight("missing or non-number required field 'confidence'")
      suggested <- suggestedCommandKind(obj.get("suggested"))
      evidence <- stringOrLeft(obj.get("evidence"), "evidence")
    yield Classification(kind, confidence, suggested, evidence)

  /** `convention-deltas.json` (§7.11 `ConventionLearner`, Task 3.2):
    * {{{
    *   {
    *     "addCommands": [{ "kind", "argv": [string], "determinism", "required": bool, "autofix": bool }],
    *     "claudeMdProposal": { "rationale": string, "suggestedAddition": string } | null,
    *     "summary": string
    *   }
    * }}}
    *
    * `addCommands` reuses the same per-command decode as `repo-profile.json` (so the accepted enum wire strings can't
    * drift); a missing/empty array ⇒ no command deltas. `claudeMdProposal` is optional (absent or `null` ⇒ `None`) —
    * the learner proposes a doc edit only when it has one. The orchestrator dedups `addCommands` against the live
    * profile before saving ([[ConventionDeltas.freshCommands]]).
    */
  def conventionDeltas(v: Value): Either[String, ConventionDeltas] =
    for
      obj <- objectOrLeft(v, "convention-deltas root")
      addCommands <- repoCommands(obj.get("addCommands"))
      proposal <- claudeMdProposal(obj.get("claudeMdProposal"))
      summary <- stringOrLeft(obj.get("summary"), "summary")
    yield ConventionDeltas(addCommands, proposal, summary)

  private def claudeMdProposal(v: Option[Value]): Either[String, Option[ClaudeMdProposal]] =
    v match
      case None | Some(ujson.Null) => Right(None)
      case Some(value) =>
        objectOrLeft(value, "claudeMdProposal").flatMap { obj =>
          for
            rationale <- stringOrLeft(obj.get("rationale"), "claudeMdProposal.rationale")
            addition <- stringOrLeft(obj.get("suggestedAddition"), "claudeMdProposal.suggestedAddition")
          yield Some(ClaudeMdProposal(rationale, addition))
        }

  private def suggestedCommandKind(v: Option[Value]): Either[String, Option[CommandKind]] =
    v match
      case None | Some(ujson.Null) => Right(None)
      case Some(value) =>
        value.strOpt
          .toRight(s"'suggested' expected string or null, got ${shapeName(value)}")
          .flatMap(s => CommandKind.fromString(s).left.map(e => s"suggested: $e"))
          .map(Some(_))

  private def repoCommands(v: Option[Value]): Either[String, Vector[RepoCommand]] =
    v match
      case None => Right(Vector.empty)
      case Some(value) =>
        value.arrOpt.toRight(s"'commands' expected JSON array, got ${shapeName(value)}").flatMap { arr =>
          arr.iterator.zipWithIndex
            .foldLeft[Either[String, Vector[RepoCommand]]](Right(Vector.empty)) {
              case (Left(e), _) => Left(e)
              case (Right(acc), (item, idx)) => repoCommand(item, idx).map(acc :+ _)
            }
        }

  private def repoCommand(v: Value, idx: Int): Either[String, RepoCommand] =
    objectOrLeft(v, s"commands[$idx]").flatMap { obj =>
      for
        kindStr <- stringOrLeft(obj.get("kind"), s"commands[$idx].kind")
        kind <- CommandKind.fromString(kindStr).left.map(e => s"commands[$idx].kind: $e")
        argv <- stringArray(obj.get("argv"), s"commands[$idx].argv")
        detStr <- stringOrLeft(obj.get("determinism"), s"commands[$idx].determinism")
        determinism <- Determinism.fromString(detStr).left.map(e => s"commands[$idx].determinism: $e")
        required <- boolOrLeft(obj.get("required"), s"commands[$idx].required")
        autofix <- boolOrLeft(obj.get("autofix"), s"commands[$idx].autofix")
      yield RepoCommand(kind, argv, determinism, required, autofix)
    }

  private def commitIdentity(v: Option[Value]): Either[String, CommitIdentity] =
    v.toRight("missing required field 'commitIdentity'").flatMap { value =>
      objectOrLeft(value, "commitIdentity").flatMap { obj =>
        for
          name <- stringOrLeft(obj.get("name"), "commitIdentity.name")
          email <- stringOrLeft(obj.get("email"), "commitIdentity.email")
        yield CommitIdentity(name, email)
      }
    }

  private def workflowProfile(v: Option[Value]): Either[String, WorkflowProfile] =
    v.toRight("missing required field 'workflow'").flatMap { value =>
      objectOrLeft(value, "workflow").flatMap { obj =>
        for
          reviewRequired <- boolOrLeft(obj.get("reviewRequired"), "workflow.reviewRequired")
          ciRequiredChecks <- stringArray(obj.get("ciRequiredChecks"), "workflow.ciRequiredChecks")
          branchStr <- stringOrLeft(obj.get("branchModel"), "workflow.branchModel")
          branchModel <- BranchModel.fromString(branchStr).left.map(e => s"workflow.branchModel: $e")
          mergeStr <- stringOrLeft(obj.get("mergeStrategy"), "workflow.mergeStrategy")
          mergeStrategy <- MergeStrategy.fromString(mergeStr).left.map(e => s"workflow.mergeStrategy: $e")
        yield WorkflowProfile(reviewRequired, ciRequiredChecks, branchModel, mergeStrategy)
      }
    }

  // --- field helpers ----

  private def boolOrLeft(v: Option[Value], field: String): Either[String, Boolean] =
    v.flatMap(_.boolOpt).toRight(s"missing or non-boolean required field '$field'")

  /** A required JSON array of strings. A missing array defaults to empty (additive-schema tolerance), but a present
    * non-array — or one whose elements aren't strings — is an error.
    */
  private def stringArray(v: Option[Value], field: String): Either[String, Vector[String]] =
    v match
      case None => Right(Vector.empty)
      case Some(value) =>
        value.arrOpt.toRight(s"'$field' expected JSON array, got ${shapeName(value)}").flatMap { arr =>
          arr.iterator.zipWithIndex
            .foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) {
              case (Left(e), _) => Left(e)
              case (Right(acc), (item, idx)) =>
                item.strOpt.toRight(s"$field[$idx]: expected string, got ${shapeName(item)}").map(acc :+ _)
            }
        }

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
