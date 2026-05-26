package io.forge.git.watcher

import upickle.default.ReadWriter

/** PR-B B1 — baseline cursor captured at PR creation per design-rationale **RL2** / v1.2 §11.4 step 6. Used by
  * [[PrSnapshotDecoder]] to filter "new since baseline" comments, reviews, and (in Slice 4) check-runs.
  *
  * All GitHub identifiers are **numeric `Long` `databaseId` values**, not lexicographic strings: GitHub's `databaseId`
  * is a 64-bit integer that routinely crosses the decimal-digit-count boundary (e.g. lexicographic compare gives `"100"
  * < "99"`). The wire JSON exposes `databaseId` as a JSON number; the decoder reads it via
  * `json("databaseId").num.toLong`. Stored on disk as `Long` via uPickle's default codec.
  *
  * Slice 3 owns the type and the filter; persistence lives in Slice 4 (the orchestrator stores the baseline alongside
  * the manifest piece record).
  *
  * @param lastSeenCommentId
  *   the highest `databaseId` of any `comments[]` entry already routed through the FSM; `None` at PR creation.
  * @param lastSeenReviewId
  *   the highest `databaseId` of any `reviews[]` entry already routed through the FSM; `None` at PR creation.
  * @param lastSeenCheckRunIds
  *   set of CheckRun `databaseId`s already routed. Reserved for Slice-4 check-run deduplication; the Slice-3 decoder
  *   doesn't yet consult it (every observed check ends up under `CheckRollup.observed`).
  */
final case class PollBaseline(
    lastSeenCommentId: Option[Long],
    lastSeenReviewId: Option[Long],
    lastSeenCheckRunIds: Set[Long]
) derives ReadWriter

object PollBaseline:
  /** Initial baseline at PR creation — nothing seen yet. */
  val empty: PollBaseline = PollBaseline(None, None, Set.empty)
