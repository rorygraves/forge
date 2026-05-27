package io.forge.git.watcher

import java.time.Instant

/** PR-B B4 — pure helpers for the [[BaselineCursor]] mechanic. Lifted out of [[PrSnapshotDecoder]] so the filter and
  * the cursor-advancement rules can be tested independently of JSON decoding.
  *
  * **Why a tie-breaker at all** (review round 2 — see design-rationale S3-7). `gh pr view --json` timestamps have
  * one-second resolution; the bare-`Instant` cursor (review round 1) dropped any entry sharing a second with the
  * watermark. The `seenIds` set fixes that: at the boundary, only entries whose id is already known are excluded.
  */
object Comments:

  given Ordering[Instant] = Ordering.fromLessThan(_.isBefore(_))

  /** Retain `(at, id, value)` entries that are strictly later than `cursor.at`, OR sit at `cursor.at` but carry an id
    * not in `cursor.seenIds`. With `cursor = None` (the PR-creation baseline), every entry is unseen.
    */
  def unseen[A](entries: Vector[(Instant, String, A)], cursor: Option[BaselineCursor]): Vector[A] =
    entries.collect {
      case (at, id, value) if cursor.forall(c => at.isAfter(c.at) || (at == c.at && !c.seenIds.contains(id))) =>
        value
    }

  /** Compute the next [[BaselineCursor]] from the **full** `(at, id)` set the decoder observed (regardless of any
    * bot-author / empty-body filters Forge applies on top). Cursor advancement obeys three rules:
    *
    *   - `entries.isEmpty` → keep `prior` unchanged (nothing observed).
    *   - max observed `at` strictly after `prior.at` → fresh cursor at the new watermark; `seenIds` reset to the ids
    *     sharing the new watermark.
    *   - max observed `at` equals `prior.at` → keep watermark, union `prior.seenIds` with ids at the watermark so
    *     same-second tie-breakers accumulate across polls.
    *
    * Entries strictly older than `prior.at` cannot move the cursor backwards; they're treated as observed history.
    */
  def advance(
      entries: Vector[(Instant, String)],
      prior: Option[BaselineCursor]
  ): Option[BaselineCursor] =
    entries.iterator.map(_._1).maxOption match
      case None => prior
      case Some(maxAt) =>
        val idsAtMax = entries.collect { case (at, id) if at == maxAt => id }.toSet
        prior match
          case None =>
            Some(BaselineCursor(maxAt, idsAtMax))
          case Some(p) if maxAt.isAfter(p.at) =>
            Some(BaselineCursor(maxAt, idsAtMax))
          case Some(p) if maxAt == p.at =>
            Some(BaselineCursor(p.at, p.seenIds ++ idsAtMax))
          case Some(p) =>
            Some(p)
