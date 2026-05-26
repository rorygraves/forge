package io.forge.git.watcher

/** PR-B B4 — pure filter on numeric (`Long`) `databaseId` against an optional baseline.
  *
  * Lifted out of [[PrSnapshotDecoder]] so the ordering invariant — "compare ids as `Long`, **never** as `String`" — can
  * be tested independently of JSON decoding. GitHub `databaseId` values are 64-bit integers and routinely cross
  * decimal-digit-count boundaries (`"100" < "99"` lexicographically); the helper makes the numeric ordering
  * load-bearing.
  */
object Comments:

  /** Retain entries whose id is strictly greater than `baseline`. With `baseline = None` (the PR-creation cursor),
    * every entry is unseen.
    */
  def unseen[A](entries: Vector[(Long, A)], baseline: Option[Long]): Vector[A] =
    entries.collect {
      case (id, value) if baseline.forall(b => id > b) => value
    }
