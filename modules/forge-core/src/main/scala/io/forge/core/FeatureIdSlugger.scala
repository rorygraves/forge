package io.forge.core

/** §5.2 slug algorithm and collision suffix logic.
  *
  * The slugger is pure; the existence check is injected so the same code
  * powers both `forge new` (against the filesystem) and tests (against an
  * in-memory set). */
object FeatureIdSlugger:

  private val NonAlnum = "[^a-z0-9]+".r

  /** Apply the 5-step §5.2 algorithm. */
  def slugify(title: String): FeatureId =
    val lower      = title.toLowerCase
    val collapsed  = NonAlnum.replaceAllIn(lower, "-")
    val trimmed    = collapsed.stripPrefix("-").stripSuffix("-")
    val truncated  = truncateAtHyphen(trimmed, 40)
    val prefixed   = if truncated.isEmpty || truncated.head.isDigit then s"f-$truncated" else truncated
    FeatureId(prefixed)

  /** §5.2 step 4 — truncate to `limit` at the last hyphen boundary if needed. */
  private def truncateAtHyphen(s: String, limit: Int): String =
    if s.length <= limit then s
    else
      val window     = s.substring(0, limit)
      val lastHyphen = window.lastIndexOf('-')
      if lastHyphen > 0 then s.substring(0, lastHyphen)
      else s.substring(0, limit)

  /** §5.2 collision rule. The first slug that `exists` returns `false` for is
    * the chosen one. Suffix `-2, -3, ...` is appended to the base slug only. */
  def assignUnique(base: FeatureId, exists: FeatureId => Boolean): FeatureId =
    if !exists(base) then base
    else
      Iterator
        .from(2)
        .map(i => FeatureId(s"${base.value}-$i"))
        .find(id => !exists(id))
        .getOrElse(
          // Unreachable: the iterator is infinite. The `getOrElse` exists only
          // to satisfy the `Option` produced by `find`.
          throw IllegalStateException("collision iterator exhausted")
        )

  /** Convenience for `forge new`: slug the title, then pick a non-colliding id. */
  def assign(title: String, exists: FeatureId => Boolean): FeatureId =
    assignUnique(slugify(title), exists)
