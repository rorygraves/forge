package io.forge.git.watcher

import io.forge.core.pr.*
import io.forge.core.{PrNumber, Sha}
import ujson.Value

import java.time.Instant
import scala.util.Try

/** PR-B B2 / B3 — provider-neutral decode of a `gh pr view --json …` payload into [[DecodedSnapshot]].
  *
  * Pure: no `IO`, no side effects. `BranchManager` / `PRWatcher` own the IO surrounding the call.
  *
  * Wire-field expectations (per v1.2 §9 / `PRWatcher.DefaultFields` — Slice 0 §4.1 pin):
  * {{{
  * state, statusCheckRollup, reviews, reviewDecision, mergeable, mergeStateStatus,
  * comments, commits, mergedAt, mergeCommit, number
  * }}}
  *
  * Key contracts:
  *
  *   - **`mergeStateStatus` trap (design-rationale CI6).** Merge detection lives entirely in `state == "MERGED"` +
  *     non-null `mergedAt`. `mergeStateStatus` is **dropped on the floor** — the field never returns `"MERGED"` even
  *     after the PR has merged, and any "fix this omission" instinct is wrong. The B5 fixture suite asserts the trap
  *     with a `merged-stale-mergestate.json` payload.
  *   - **Comment baseline filter (design-rationale RL2 / S3-7).** Comments and reviews after the [[PollBaseline]]
  *     watermark — or at the watermark with an id not in [[BaselineCursor.seenIds]] (the same-second tie-breaker added
  *     in review round 2) — survive into `unseenComments`. See [[Comments.unseen]] for the predicate and
  *     [[Comments.advance]] for cursor advancement. The decoder also returns the next [[PollBaseline]] the orchestrator
  *     should persist via [[DecodedSnapshot.nextBaseline]].
  *   - **Empty-body filter (review round 1).** Posts with an empty `body` are dropped at decode time. GitHub allows
  *     empty-bodied review submissions (e.g. plain approvals), but the FSM treats `unseenComments.nonEmpty` as a human
  *     override signal — an empty approval would spuriously kick a piece back to `PieceReviewFailed`. Blocking review
  *     state still propagates via `reviewDecision == CHANGES_REQUESTED`.
  *   - **Bot-author filter.** Entries authored by `botLogin` (default `forge-bot`, Slice 4 wires the config value) are
  *     dropped at decode time so the FSM never sees its own historical posts as "new human signal".
  *   - **Required-checks overlay is not computed here.** Every `statusCheckRollup` entry lands under
  *     [[CheckRollup.observed]]; `BranchManager.requiredChecksOverlay` (PR-C C6) promotes the named subset into
  *     `required` using the branch-protection union.
  *
  * @return
  *   `Right(decoded)` on success; `Left(DecodeError.*)` on any wire-shape problem. The decoder fails on the **first**
  *   problem it encounters — the JSON path in the error is enough to locate the offending fixture without re-parsing.
  */
object PrSnapshotDecoder:

  /** Canonical decoder entry point. */
  def decode(
      json: Value,
      baseline: PollBaseline,
      botLogin: String
  ): Either[DecodeError, DecodedSnapshot] =
    json.objOpt match
      case None =>
        Left(DecodeError.MalformedShape("<root>", "object", shapeName(json)))
      case Some(root) =>
        for
          number <- decodeNumber(root)
          state <- decodeState(root)
          mergedAt <- decodeMergedAt(root)
          mergeCommit <- decodeMergeCommit(root)
          mergeable <- decodeMergeable(root)
          reviewDecision <- decodeReviewDecision(root)
          observedChecks <- decodeStatusCheckRollup(root)
          headSha <- decodeHeadSha(root)
          commentEntries <- decodeComments(root)
          reviewEntries <- decodeReviews(root)
        yield
          val newComments = filterUnseen(commentEntries, baseline.commentCursor, botLogin)
          val newReviews = filterUnseen(reviewEntries, baseline.reviewCursor, botLogin)
          // Cursor advancement runs against the FULL observed set (including bot-authored / empty-body posts) so the
          // next poll doesn't re-evaluate the same entries: they're seen from the cursor's perspective even though
          // we don't surface them as `unseenComments`.
          val nextCommentCursor = Comments.advance(
            commentEntries.map(e => (e.at, e.comment.id)),
            baseline.commentCursor
          )
          val nextReviewCursor = Comments.advance(
            reviewEntries.map(e => (e.at, e.comment.id)),
            baseline.reviewCursor
          )
          val snapshot = PrSnapshot(
            number = number,
            state = state,
            mergedAt = mergedAt,
            mergeCommit = mergeCommit,
            requiredChecks = CheckRollup(required = Vector.empty, observed = observedChecks),
            reviewDecision = reviewDecision,
            unseenComments = newComments ++ newReviews,
            mergeable = mergeable
          )
          val nextBaseline = baseline.copy(
            commentCursor = nextCommentCursor,
            reviewCursor = nextReviewCursor
          )
          DecodedSnapshot(snapshot, headSha, nextBaseline)

  // --- known-enum vectors (echoed into UnknownEnumValue.knownValues for diagnosability) ---

  private val PrStateKnown: Vector[String] = Vector("OPEN", "CLOSED", "MERGED")
  private val MergeableKnown: Vector[String] = Vector("MERGEABLE", "CONFLICTING", "UNKNOWN")
  private val ReviewDecisionKnown: Vector[String] = Vector("APPROVED", "CHANGES_REQUESTED", "REVIEW_REQUIRED")
  private val CheckStateKnown: Vector[String] =
    Vector("QUEUED", "IN_PROGRESS", "COMPLETED", "PENDING", "REQUESTED", "WAITING")
  private val CheckConclusionKnown: Vector[String] =
    Vector(
      "SUCCESS",
      "FAILURE",
      "CANCELLED",
      "SKIPPED",
      "TIMED_OUT",
      "ACTION_REQUIRED",
      "NEUTRAL",
      "STALE",
      "STARTUP_FAILURE"
    )

  // --- top-level field decoders ----------------------------------------------

  private def decodeNumber(root: Obj): Either[DecodeError, PrNumber] =
    nonNullField(root, "number") match
      case None => Left(DecodeError.MissingField("number"))
      case Some(v) =>
        v.numOpt match
          case None => Left(DecodeError.MalformedShape("number", "number", shapeName(v)))
          case Some(n) =>
            Try(PrNumber(n.toInt)).toEither.left
              .map(_ => DecodeError.MalformedShape("number", "positive int", n.toString))

  private def decodeState(root: Obj): Either[DecodeError, PrState] =
    nonNullField(root, "state") match
      case None => Left(DecodeError.MissingField("state"))
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape("state", "string", shapeName(v)))
          case Some(s) =>
            PrState.fromString(s).left.map(_ => DecodeError.UnknownEnumValue("state", s, PrStateKnown))

  private def decodeMergedAt(root: Obj): Either[DecodeError, Option[Instant]] =
    nonNullField(root, "mergedAt") match
      case None => Right(None)
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape("mergedAt", "string", shapeName(v)))
          case Some(s) =>
            Try(Instant.parse(s)).toEither.left
              .map(_ => DecodeError.MalformedShape("mergedAt", "ISO instant", s))
              .map(Some(_))

  private def decodeMergeCommit(root: Obj): Either[DecodeError, Option[Sha]] =
    nonNullField(root, "mergeCommit") match
      case None => Right(None)
      case Some(v) =>
        v.objOpt match
          case None => Left(DecodeError.MalformedShape("mergeCommit", "object", shapeName(v)))
          case Some(o) =>
            nonNullField(o, "oid") match
              case None => Left(DecodeError.MissingField("mergeCommit.oid"))
              case Some(oidV) =>
                oidV.strOpt match
                  case None =>
                    Left(DecodeError.MalformedShape("mergeCommit.oid", "string", shapeName(oidV)))
                  case Some(s) =>
                    Sha
                      .fromString(s)
                      .left
                      .map(_ => DecodeError.MalformedShape("mergeCommit.oid", "sha", s))
                      .map(Some(_))

  private def decodeMergeable(root: Obj): Either[DecodeError, Option[Boolean]] =
    nonNullField(root, "mergeable") match
      case None => Left(DecodeError.MissingField("mergeable"))
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape("mergeable", "string", shapeName(v)))
          case Some("MERGEABLE") => Right(Some(true))
          case Some("CONFLICTING") => Right(Some(false))
          case Some("UNKNOWN") => Right(None)
          case Some(other) => Left(DecodeError.UnknownEnumValue("mergeable", other, MergeableKnown))

  /** `gh pr view --json reviewDecision` returns:
    *   - `null` (or omitted) on a PR with no associated review-decision context;
    *   - the empty string `""` on a brand-new PR with no reviews yet (the GraphQL `reviewDecision` field is nullable on
    *     the server but `gh` flattens the `null` to `""` in JSON output — surfaced by the PR-G sacrificial-repo IT
    *     against a freshly-opened PR);
    *   - one of `"APPROVED"` / `"CHANGES_REQUESTED"` / `"REVIEW_REQUIRED"` otherwise.
    *
    * Both `null` and the empty string decode as `None` (no review state). Any other string is an `UnknownEnumValue`, so
    * a future GitHub addition surfaces diagnosably.
    */
  private def decodeReviewDecision(root: Obj): Either[DecodeError, Option[ReviewDecision]] =
    nonNullField(root, "reviewDecision") match
      case None => Right(None)
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape("reviewDecision", "string", shapeName(v)))
          case Some("") => Right(None)
          case Some(s) =>
            ReviewDecision
              .fromString(s)
              .left
              .map(_ => DecodeError.UnknownEnumValue("reviewDecision", s, ReviewDecisionKnown))
              .map(Some(_))

  private def decodeStatusCheckRollup(root: Obj): Either[DecodeError, Vector[CheckResult]] =
    nonNullField(root, "statusCheckRollup") match
      case None => Right(Vector.empty)
      case Some(v) =>
        v.arrOpt match
          case None => Left(DecodeError.MalformedShape("statusCheckRollup", "array", shapeName(v)))
          case Some(arr) =>
            collectEither(arr.toVector.zipWithIndex)((entry, i) => decodeCheckEntry(entry, s"statusCheckRollup[$i]"))

  private def decodeCheckEntry(entry: Value, pathPrefix: String): Either[DecodeError, CheckResult] =
    entry.objOpt match
      case None => Left(DecodeError.MalformedShape(pathPrefix, "object", shapeName(entry)))
      case Some(o) =>
        for
          name <- decodeCheckName(o, pathPrefix)
          state <- decodeCheckState(o, pathPrefix)
          conclusion <- decodeCheckConclusion(o, pathPrefix)
        yield CheckResult(name, state, conclusion)

  /** CheckRun shape carries `name`; StatusContext carries `context`. Accept either. */
  private def decodeCheckName(o: Obj, pathPrefix: String): Either[DecodeError, String] =
    nonNullField(o, "name").orElse(nonNullField(o, "context")) match
      case None => Left(DecodeError.MissingField(s"$pathPrefix.name"))
      case Some(v) =>
        v.strOpt match
          case Some(s) => Right(s)
          case None => Left(DecodeError.MalformedShape(s"$pathPrefix.name", "string", shapeName(v)))

  /** CheckRun shape carries `status`; StatusContext carries `state`. Accept either; expect a wire enum drawn from
    * [[CheckStateKnown]].
    */
  private def decodeCheckState(o: Obj, pathPrefix: String): Either[DecodeError, CheckState] =
    nonNullField(o, "status").orElse(nonNullField(o, "state")) match
      case None => Left(DecodeError.MissingField(s"$pathPrefix.state"))
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape(s"$pathPrefix.state", "string", shapeName(v)))
          case Some(s) =>
            CheckState
              .fromString(s)
              .left
              .map(_ => DecodeError.UnknownEnumValue(s"$pathPrefix.state", s, CheckStateKnown))

  private def decodeCheckConclusion(o: Obj, pathPrefix: String): Either[DecodeError, Option[CheckConclusion]] =
    nonNullField(o, "conclusion") match
      case None => Right(None)
      case Some(v) =>
        v.strOpt match
          case None =>
            Left(DecodeError.MalformedShape(s"$pathPrefix.conclusion", "string", shapeName(v)))
          case Some(s) =>
            CheckConclusion
              .fromString(s)
              .left
              .map(_ => DecodeError.UnknownEnumValue(s"$pathPrefix.conclusion", s, CheckConclusionKnown))
              .map(Some(_))

  private def decodeHeadSha(root: Obj): Either[DecodeError, Sha] =
    nonNullField(root, "commits") match
      case None => Left(DecodeError.MissingField("commits[-1].oid"))
      case Some(v) =>
        v.arrOpt match
          case None => Left(DecodeError.MalformedShape("commits", "array", shapeName(v)))
          case Some(arr) if arr.isEmpty => Left(DecodeError.MissingField("commits[-1].oid"))
          case Some(arr) =>
            val last = arr.last
            last.objOpt match
              case None => Left(DecodeError.MalformedShape("commits[-1]", "object", shapeName(last)))
              case Some(o) =>
                nonNullField(o, "oid") match
                  case None => Left(DecodeError.MissingField("commits[-1].oid"))
                  case Some(oidV) =>
                    oidV.strOpt match
                      case None =>
                        Left(DecodeError.MalformedShape("commits[-1].oid", "string", shapeName(oidV)))
                      case Some(s) =>
                        Sha
                          .fromString(s)
                          .left
                          .map(_ => DecodeError.MalformedShape("commits[-1].oid", "sha", s))

  private def decodeComments(root: Obj): Either[DecodeError, Vector[CommentEntry]] =
    nonNullField(root, "comments") match
      case None => Right(Vector.empty)
      case Some(v) =>
        v.arrOpt match
          case None => Left(DecodeError.MalformedShape("comments", "array", shapeName(v)))
          case Some(arr) =>
            collectEither(arr.toVector.zipWithIndex)((entry, i) =>
              decodePostEntry(entry, s"comments[$i]", timestampField = "createdAt")
            )

  private def decodeReviews(root: Obj): Either[DecodeError, Vector[CommentEntry]] =
    nonNullField(root, "reviews") match
      case None => Right(Vector.empty)
      case Some(v) =>
        v.arrOpt match
          case None => Left(DecodeError.MalformedShape("reviews", "array", shapeName(v)))
          case Some(arr) =>
            collectEither(arr.toVector.zipWithIndex)((entry, i) =>
              decodePostEntry(entry, s"reviews[$i]", timestampField = "submittedAt")
            )

  /** Shared decoder for `comments[]` and `reviews[]` entries. Both shapes carry `id` (a String GraphQL global node id;
    * `gh pr view --json comments,reviews` does **not** expose `databaseId` — see design-rationale S3-7),
    * `author.login`, `body` (possibly empty for reviews — see the FSM-impact comment in the object-level docstring),
    * and a timestamp field — `createdAt` for comments, `submittedAt` for reviews.
    */
  private def decodePostEntry(
      entry: Value,
      path: String,
      timestampField: String
  ): Either[DecodeError, CommentEntry] =
    entry.objOpt match
      case None => Left(DecodeError.MalformedShape(path, "object", shapeName(entry)))
      case Some(o) =>
        for
          id <- decodeId(o, path)
          login <- decodeAuthorLogin(o, path)
          body = nonNullField(o, "body").flatMap(_.strOpt).getOrElse("")
          at <- decodeTimestamp(o, path, timestampField)
        yield
          val comment = PrComment(
            id = id,
            author = login,
            body = body,
            createdAt = at,
            path = None,
            line = None
          )
          CommentEntry(at, login, comment)

  private def decodeId(o: Obj, path: String): Either[DecodeError, String] =
    nonNullField(o, "id") match
      case None => Left(DecodeError.MissingField(s"$path.id"))
      case Some(v) =>
        v.strOpt match
          case Some(s) => Right(s)
          case None => Left(DecodeError.MalformedShape(s"$path.id", "string", shapeName(v)))

  private def decodeAuthorLogin(o: Obj, path: String): Either[DecodeError, String] =
    nonNullField(o, "author") match
      case None => Left(DecodeError.MissingField(s"$path.author"))
      case Some(v) =>
        v.objOpt match
          case None => Left(DecodeError.MalformedShape(s"$path.author", "object", shapeName(v)))
          case Some(authorObj) =>
            nonNullField(authorObj, "login") match
              case None => Left(DecodeError.MissingField(s"$path.author.login"))
              case Some(loginV) =>
                loginV.strOpt match
                  case Some(s) => Right(s)
                  case None =>
                    Left(DecodeError.MalformedShape(s"$path.author.login", "string", shapeName(loginV)))

  private def decodeTimestamp(o: Obj, path: String, field: String): Either[DecodeError, Instant] =
    nonNullField(o, field) match
      case None => Left(DecodeError.MissingField(s"$path.$field"))
      case Some(v) =>
        v.strOpt match
          case None => Left(DecodeError.MalformedShape(s"$path.$field", "string", shapeName(v)))
          case Some(s) =>
            Try(Instant.parse(s)).toEither.left
              .map(_ => DecodeError.MalformedShape(s"$path.$field", "ISO instant", s))

  // --- helpers ---------------------------------------------------------------

  private type Obj = ujson.Obj

  /** Look up `name` in `obj`; treat `null` JSON values as absent so callers needn't branch on `case Null` everywhere.
    */
  private def nonNullField(obj: Obj, name: String): Option[Value] =
    obj.value.get(name).filter(_ != ujson.Null)

  private def shapeName(v: Value): String = v match
    case _: ujson.Obj => "object"
    case _: ujson.Arr => "array"
    case _: ujson.Str => "string"
    case _: ujson.Num => "number"
    case _: ujson.Bool => "boolean"
    case ujson.Null => "null"

  /** Short-circuiting fold: stop at the first `Left`. */
  private def collectEither[A, B](
      items: Vector[(Value, Int)]
  )(fn: (Value, Int) => Either[DecodeError, B]): Either[DecodeError, Vector[B]] =
    items.foldLeft[Either[DecodeError, Vector[B]]](Right(Vector.empty)) {
      case (Left(e), _) => Left(e)
      case (Right(acc), (v, i)) => fn(v, i).map(acc :+ _)
    }

  /** Apply (a) the bot-author filter, (b) the empty-body filter (review round 1 — avoid spurious human-override signal
    * from plain approval submissions), and (c) the [[Comments.unseen]] cursor filter (review round 2 — id tie-breaker
    * for same-second entries) in one step.
    */
  private def filterUnseen(
      entries: Vector[CommentEntry],
      cursor: Option[BaselineCursor],
      botLogin: String
  ): Vector[PrComment] =
    val signal = entries.collect {
      case CommentEntry(at, login, c) if login != botLogin && c.body.nonEmpty => (at, c.id, c)
    }
    Comments.unseen(signal, cursor)

  /** Decoder-local intermediate carrying the entry's timestamp + login alongside the public `PrComment`. The timestamp
    * flows into [[Comments.unseen]] for the baseline filter; the login flows into the bot-author filter.
    */
  private final case class CommentEntry(at: Instant, login: String, comment: PrComment)
