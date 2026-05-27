package io.forge.git.watcher

import java.time.Instant

/** PR-B B4 — pure filter on an `Instant` cursor against an optional baseline. Lifted out of [[PrSnapshotDecoder]] so
  * the ordering rule — "compare timestamps with [[Instant.isAfter]], strictly greater than the cursor" — can be tested
  * independently of JSON decoding.
  *
  * Cursor type is `Instant` (review round 1, design-rationale **S3-7**): `gh pr view --json comments,reviews` doesn't
  * expose `databaseId`, so timestamps replace the original `Long` plan. `Instant.isAfter` is well-typed and avoids the
  * lexicographic-vs-numeric trap that prompted the original `Long`-based helper.
  */
object Comments:

  /** Retain entries whose timestamp is strictly after `baseline`. With `baseline = None` (the PR-creation cursor),
    * every entry is unseen.
    */
  def unseen[A](entries: Vector[(Instant, A)], baseline: Option[Instant]): Vector[A] =
    entries.collect {
      case (at, value) if baseline.forall(b => at.isAfter(b)) => value
    }
