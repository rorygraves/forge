package io.forge.git.watcher

import java.time.Instant

/** PR-B B4 — pure-helper coverage for [[Comments.unseen]] independent of the [[PrSnapshotDecoder]].
  *
  * The cursor is `Instant` (review round 1, design-rationale S3-7): `gh pr view --json comments,reviews` doesn't expose
  * `databaseId`, so the original `Long` cursor plan was unviable. The contract pinned here is:
  *
  *   1. Strictly-greater comparison via [[Instant.isAfter]] — entries with `at == baseline` are excluded. 2. Empty
  *      baseline → every entry is unseen. 3. Baseline equal to the most recent entry's timestamp → empty unseen set.
  */
class CommentsSuite extends munit.FunSuite:

  private val T0 = Instant.parse("2026-05-26T09:00:00Z")
  private val T1 = Instant.parse("2026-05-26T09:30:00Z")
  private val T2 = Instant.parse("2026-05-26T10:00:00Z")
  private val T3 = Instant.parse("2026-05-26T10:30:00Z")

  // --- strictly-greater ordering ---------------------------------------------

  test("baseline = Some(T1): entry at T0 (earlier) is excluded"):
    val unseen = Comments.unseen(Vector((T0, "early")), baseline = Some(T1))
    assertEquals(unseen, Vector.empty[String])

  test("baseline = Some(T0): entry at T1 (strictly after) is unseen"):
    val unseen = Comments.unseen(Vector((T1, "later")), baseline = Some(T0))
    assertEquals(unseen, Vector("later"))

  test("baseline = Some(T1): mixed input — only T2/T3 survive (strict > filter)"):
    val unseen = Comments.unseen(
      Vector((T0, "a"), (T1, "b"), (T2, "c"), (T3, "d")),
      baseline = Some(T1)
    )
    assertEquals(unseen, Vector("c", "d"))

  test("baseline = Some(t): equality is excluded (isAfter is strict)"):
    val unseen = Comments.unseen(Vector((T1, "boundary")), baseline = Some(T1))
    assertEquals(unseen, Vector.empty[String])

  // --- empty baseline ---------------------------------------------------------

  test("baseline = None: every entry is unseen"):
    val unseen = Comments.unseen(
      Vector((T0, "a"), (T1, "b"), (T2, "c")),
      baseline = None
    )
    assertEquals(unseen, Vector("a", "b", "c"))

  test("baseline = None with empty input: empty output"):
    val unseen = Comments.unseen(Vector.empty[(Instant, String)], baseline = None)
    assertEquals(unseen, Vector.empty[String])

  // --- baseline equal to the most recent entry's timestamp ------------------

  test("baseline = Some(latestEntry.at): empty unseen set"):
    val unseen = Comments.unseen(
      Vector((T0, "a"), (T1, "b"), (T2, "c")),
      baseline = Some(T2)
    )
    assertEquals(unseen, Vector.empty[String])

  test("baseline strictly above every entry's timestamp: empty unseen set"):
    val unseen = Comments.unseen(
      Vector((T0, "a"), (T1, "b"), (T2, "c")),
      baseline = Some(T3)
    )
    assertEquals(unseen, Vector.empty[String])

  // --- ordering preservation -------------------------------------------------

  test("output preserves input order; no reordering"):
    val unseen = Comments.unseen(
      Vector((T3, "third"), (T1, "first"), (T2, "second")),
      baseline = None
    )
    assertEquals(unseen, Vector("third", "first", "second"))
