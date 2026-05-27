package io.forge.git.watcher

import io.forge.core.Json.given
import upickle.default.ReadWriter

import java.time.Instant

/** PR-B B1 — baseline cursor captured at PR creation per design-rationale **RL2** / v1.2 §11.4 step 6. Used by
  * [[PrSnapshotDecoder]] to filter "new since baseline" comments and reviews.
  *
  * **Cursor type is `Instant`, not `Long`** (review round 1 — see design-rationale **S3-7**). The original PR-B plan
  * keyed off `databaseId: Long`, but `gh pr view --json comments,reviews` does not expose `databaseId` for either
  * field: each entry carries a String `id` (the GraphQL global node id) plus an ISO-8601 timestamp (`createdAt` for
  * comments, `submittedAt` for reviews). Timestamps are well-typed under `Instant.isAfter` and avoid the
  * `databaseId`-via-`Double` precision concern raised in the review.
  *
  * Slice 3 owns the type and the filter; persistence lives in Slice 4 (the orchestrator stores the baseline alongside
  * the manifest piece record).
  *
  * @param lastSeenCommentAt
  *   the `createdAt` of the most recent `comments[]` entry already routed through the FSM; `None` at PR creation. The
  *   decoder retains comments with `createdAt.isAfter(lastSeenCommentAt)`.
  * @param lastSeenReviewAt
  *   the `submittedAt` of the most recent `reviews[]` entry already routed through the FSM; `None` at PR creation. The
  *   decoder retains reviews with `submittedAt.isAfter(lastSeenReviewAt)`.
  * @param lastSeenCheckRunIds
  *   reserved for Slice-4 check-run deduplication. Slice-3 decoder doesn't yet consult this set (every observed check
  *   lands under `CheckRollup.observed`); the field is stringly-typed because `statusCheckRollup` entries also use
  *   GraphQL global ids, not `databaseId`.
  */
final case class PollBaseline(
    lastSeenCommentAt: Option[Instant],
    lastSeenReviewAt: Option[Instant],
    lastSeenCheckRunIds: Set[String]
) derives ReadWriter

object PollBaseline:
  /** Initial baseline at PR creation — nothing seen yet. */
  val empty: PollBaseline = PollBaseline(None, None, Set.empty)
