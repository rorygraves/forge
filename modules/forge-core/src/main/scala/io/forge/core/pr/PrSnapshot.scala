package io.forge.core.pr

import io.forge.core.*
import io.forge.core.Json.given

import java.time.Instant
import upickle.default.{readwriter, ReadWriter}

/** §6 — provider-neutral pull-request snapshot. `forge-git`'s `PRWatcher` produces these from `gh pr view` output; the
  * FSM consumes them. GitLab adapter (§20) parallel-parses into this same shape.
  *
  * Fields mirror v1.2 §6: `number`, `state`, `mergedAt`, `mergeCommit`, `requiredChecks`, `reviewDecision`,
  * `unseenComments`, `mergeable`. PR-B B0 lands the type so PR-B B4 can reference it from `FsmEvent`.
  */
final case class PrSnapshot(
    number: PrNumber,
    state: PrState,
    mergedAt: Option[Instant],
    mergeCommit: Option[Sha],
    requiredChecks: CheckRollup,
    reviewDecision: Option[ReviewDecision],
    unseenComments: Vector[PrComment],
    mergeable: Option[Boolean]
) derives ReadWriter

/** §6 — wire-shape PR state. `gh pr view --json state` returns `OPEN | CLOSED | MERGED` for the upstream PR. */
enum PrState:
  case Open
  case Closed
  case Merged

object PrState:
  /** `gh pr view --json state` wire form. */
  def fromString(s: String): Either[String, PrState] = s match
    case "OPEN" => Right(PrState.Open)
    case "CLOSED" => Right(PrState.Closed)
    case "MERGED" => Right(PrState.Merged)
    case other => Left(s"unknown PR state '$other'")

  extension (s: PrState)
    def asString: String = s match
      case PrState.Open => "OPEN"
      case PrState.Closed => "CLOSED"
      case PrState.Merged => "MERGED"

  given ReadWriter[PrState] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )

/** §6 — `gh pr view --json reviewDecision`. */
enum ReviewDecision:
  case Approved
  case ChangesRequested
  case ReviewRequired

object ReviewDecision:
  def fromString(s: String): Either[String, ReviewDecision] = s match
    case "APPROVED" => Right(ReviewDecision.Approved)
    case "CHANGES_REQUESTED" => Right(ReviewDecision.ChangesRequested)
    case "REVIEW_REQUIRED" => Right(ReviewDecision.ReviewRequired)
    case other => Left(s"unknown reviewDecision '$other'")

  extension (d: ReviewDecision)
    def asString: String = d match
      case ReviewDecision.Approved => "APPROVED"
      case ReviewDecision.ChangesRequested => "CHANGES_REQUESTED"
      case ReviewDecision.ReviewRequired => "REVIEW_REQUIRED"

  given ReadWriter[ReviewDecision] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )

/** §6 — wire form of one `gh pr view --json statusCheckRollup` entry. Variants mirror GitHub's GraphQL
  * `CheckStatusState` enum (https://docs.github.com/en/graphql/reference/enums#checkstatusstate): `COMPLETED`,
  * `IN_PROGRESS`, `PENDING`, `QUEUED`, `REQUESTED`, `WAITING`. We keep all of them so a real-world rollup decodes
  * losslessly into Forge's model even for newer / less-common states (e.g. `WAITING` for required-reviewer queues,
  * `STARTUP_FAILURE` on the conclusion side).
  */
enum CheckState:
  case Queued
  case InProgress
  case Completed
  case Pending
  case Requested
  case Waiting

object CheckState:
  def fromString(s: String): Either[String, CheckState] = s match
    case "QUEUED" => Right(CheckState.Queued)
    case "IN_PROGRESS" => Right(CheckState.InProgress)
    case "COMPLETED" => Right(CheckState.Completed)
    case "PENDING" => Right(CheckState.Pending)
    case "REQUESTED" => Right(CheckState.Requested)
    case "WAITING" => Right(CheckState.Waiting)
    case other => Left(s"unknown check state '$other'")

  extension (s: CheckState)
    def asString: String = s match
      case CheckState.Queued => "QUEUED"
      case CheckState.InProgress => "IN_PROGRESS"
      case CheckState.Completed => "COMPLETED"
      case CheckState.Pending => "PENDING"
      case CheckState.Requested => "REQUESTED"
      case CheckState.Waiting => "WAITING"

  given ReadWriter[CheckState] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )

/** §6 — completion conclusion for a finished check. Variants mirror GitHub's GraphQL `CheckConclusionState` enum
  * (https://docs.github.com/en/graphql/reference/enums#checkconclusionstate): `ACTION_REQUIRED`, `CANCELLED`,
  * `FAILURE`, `NEUTRAL`, `SKIPPED`, `STALE`, `STARTUP_FAILURE`, `SUCCESS`, `TIMED_OUT`. Forge stays a faithful mirror
  * so `PRWatcher` (Slice 3) doesn't have to lossily collapse states.
  */
enum CheckConclusion:
  case Success
  case Failure
  case Cancelled
  case Skipped
  case TimedOut
  case ActionRequired
  case Neutral
  case Stale
  case StartupFailure

object CheckConclusion:
  def fromString(s: String): Either[String, CheckConclusion] = s match
    case "SUCCESS" => Right(CheckConclusion.Success)
    case "FAILURE" => Right(CheckConclusion.Failure)
    case "CANCELLED" => Right(CheckConclusion.Cancelled)
    case "SKIPPED" => Right(CheckConclusion.Skipped)
    case "TIMED_OUT" => Right(CheckConclusion.TimedOut)
    case "ACTION_REQUIRED" => Right(CheckConclusion.ActionRequired)
    case "NEUTRAL" => Right(CheckConclusion.Neutral)
    case "STALE" => Right(CheckConclusion.Stale)
    case "STARTUP_FAILURE" => Right(CheckConclusion.StartupFailure)
    case other => Left(s"unknown check conclusion '$other'")

  extension (c: CheckConclusion)
    def asString: String = c match
      case CheckConclusion.Success => "SUCCESS"
      case CheckConclusion.Failure => "FAILURE"
      case CheckConclusion.Cancelled => "CANCELLED"
      case CheckConclusion.Skipped => "SKIPPED"
      case CheckConclusion.TimedOut => "TIMED_OUT"
      case CheckConclusion.ActionRequired => "ACTION_REQUIRED"
      case CheckConclusion.Neutral => "NEUTRAL"
      case CheckConclusion.Stale => "STALE"
      case CheckConclusion.StartupFailure => "STARTUP_FAILURE"

  given ReadWriter[CheckConclusion] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )

/** One observed CI check on a PR. */
final case class CheckResult(
    name: String,
    state: CheckState,
    conclusion: Option[CheckConclusion]
) derives ReadWriter

/** §6 / §8 — aggregated CI rollup. `required` lists the checks the §8 policy considers required (branch-protection
  * union overlay); `observed` is the rest. The orchestrator's `CiPolicy` decision logic combines them — `forge-core`
  * just carries the data.
  */
final case class CheckRollup(
    required: Vector[CheckResult],
    observed: Vector[CheckResult]
) derives ReadWriter:
  def all: Vector[CheckResult] = required ++ observed

object CheckRollup:
  val empty: CheckRollup = CheckRollup(Vector.empty, Vector.empty)

/** §6 — wire shape for `gh pr view --json comments` plus inline review-thread entries. `path` / `line` are populated
  * for inline review-thread comments; `None` for top-level issue comments on the PR.
  */
final case class PrComment(
    id: String,
    author: String,
    body: String,
    createdAt: Instant,
    path: Option[String],
    line: Option[Int]
) derives ReadWriter
