package io.forge.git.watcher

import io.forge.core.Json.given
import upickle.default.ReadWriter

import java.time.Instant

/** PR-B B1 — baseline cursor captured at PR creation per design-rationale **RL2** / v1.2 §11.4 step 6, augmented for
  * second-granularity timestamp tolerance per **S3-7 round 2**.
  *
  * **Why a richer cursor than `Option[Instant]`.** `gh pr view --json comments,reviews` exposes ISO-8601 timestamps
  * with one-second resolution: two posts can share an exact `createdAt` / `submittedAt`. A bare `Option[Instant]`
  * cursor + `isAfter` filter would silently drop any entry created in the same second as the prior high-watermark —
  * including legitimate human feedback (CHANGES_REQUESTED comments, late merge-gate replies) arriving on the next poll.
  * [[BaselineCursor]] adds the `seenIds` tie-breaker so the filter retains `at.isAfter(cursor.at) || (at == cursor.at
  * && !cursor.seenIds.contains(id))`.
  *
  * Slice 3 owns the type and the filter; persistence lives in Slice 4 (the orchestrator stores the baseline alongside
  * the manifest piece record and persists [[DecodedSnapshot.nextBaseline]] after each poll).
  *
  * @param commentCursor
  *   high-watermark for `comments[]` + the set of ids already seen at that timestamp. `None` at PR creation.
  * @param reviewCursor
  *   high-watermark for `reviews[]` + the set of ids already seen at that timestamp. `None` at PR creation.
  * @param lastSeenCheckRunIds
  *   reserved for Slice-4 check-run deduplication. Slice-3 decoder doesn't consult this set (every observed check lands
  *   under `CheckRollup.observed`); the field is stringly-typed because `statusCheckRollup` entries also use GraphQL
  *   global ids, not `databaseId`.
  */
final case class PollBaseline(
    commentCursor: Option[BaselineCursor],
    reviewCursor: Option[BaselineCursor],
    lastSeenCheckRunIds: Set[String]
) derives ReadWriter

object PollBaseline:
  /** Initial baseline at PR creation — nothing seen yet. */
  val empty: PollBaseline = PollBaseline(None, None, Set.empty)

/** A high-watermark cursor for an ordered collection (`comments[]` or `reviews[]`).
  *
  * `at` is the most recent timestamp Forge has observed; `seenIds` is the set of entry ids whose `at` equals `this.at`
  * exactly. The decoder's [[Comments.unseen]] filter excludes only entries that fall strictly before `at` OR sit at
  * `at` AND have an id in `seenIds`. Anything newer than `at`, or sharing `at` with a fresh id, surfaces as new signal.
  *
  * `seenIds` typically holds 1–3 ids — the second-granularity collision window is narrow in practice — so persistence
  * cost is negligible.
  */
final case class BaselineCursor(at: Instant, seenIds: Set[String]) derives ReadWriter
