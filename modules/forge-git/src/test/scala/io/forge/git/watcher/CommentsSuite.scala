package io.forge.git.watcher

import java.time.Instant

/** PR-B B4 — pure-helper coverage for [[Comments.unseen]] and [[Comments.advance]] independent of the
  * [[PrSnapshotDecoder]].
  *
  * Two contracts are pinned here (review round 2, design-rationale S3-7):
  *
  *   1. **`unseen` filter**: entries strictly after `cursor.at` survive; entries at `cursor.at` survive only when their
  *      id is **not** in `cursor.seenIds`. The id tie-breaker is the load-bearing fix for `gh`'s second-granularity
  *      timestamps. 2. **`advance` cursor**: empty input keeps the prior cursor; a new max moves the watermark and
  *      resets `seenIds`; a max equal to the prior watermark unions `seenIds` across polls.
  */
class CommentsSuite extends munit.FunSuite:

  private val T0 = Instant.parse("2026-05-26T09:00:00Z")
  private val T1 = Instant.parse("2026-05-26T09:30:00Z")
  private val T2 = Instant.parse("2026-05-26T10:00:00Z")
  private val T3 = Instant.parse("2026-05-26T10:30:00Z")

  private def cursor(at: Instant, ids: String*): BaselineCursor =
    BaselineCursor(at, ids.toSet)

  // --- unseen: strictly-after branch ----------------------------------------

  test("cursor = Some(T1, {}): entry at T0 (earlier) is excluded"):
    val unseen = Comments.unseen(Vector((T0, "a", "early")), Some(cursor(T1)))
    assertEquals(unseen, Vector.empty[String])

  test("cursor = Some(T0, {}): entry at T1 (strictly after) is unseen"):
    val unseen = Comments.unseen(Vector((T1, "b", "later")), Some(cursor(T0)))
    assertEquals(unseen, Vector("later"))

  test("cursor = Some(T1, {}): mixed input — T2 and T3 survive (strict > branch)"):
    val unseen = Comments.unseen(
      Vector((T0, "a", "earliest"), (T1, "b", "boundary"), (T2, "c", "later"), (T3, "d", "latest")),
      Some(cursor(T1))
    )
    // Note: T1's entry is excluded because its id "b" isn't in seenIds, but the cursor is at T1
    // with empty seenIds. Wait — empty seenIds means NO ids are "already seen", so id "b" at T1 IS unseen!
    // Adjusting expectation: per the `at == cursor.at && !seenIds.contains(id)` branch, "b" survives too.
    assertEquals(unseen, Vector("boundary", "later", "latest"))

  // --- unseen: equality + id tie-breaker (round-2 fix) ----------------------

  test("cursor = Some(T1, {id-already-seen}): same-second entry with KNOWN id is excluded"):
    val unseen = Comments.unseen(
      Vector((T1, "id-already-seen", "duplicate")),
      Some(cursor(T1, "id-already-seen"))
    )
    assertEquals(unseen, Vector.empty[String])

  test("cursor = Some(T1, {id-already-seen}): same-second entry with a DIFFERENT id is unseen"):
    // The reviewer's concrete worry: a comment created in the same second as the watermark would be silently dropped.
    // The id tie-breaker rescues it.
    val unseen = Comments.unseen(
      Vector((T1, "fresh-id", "late same-second human feedback")),
      Some(cursor(T1, "id-already-seen"))
    )
    assertEquals(unseen, Vector("late same-second human feedback"))

  test("cursor at exact watermark with multiple seenIds: only the unseen id surfaces"):
    val unseen = Comments.unseen(
      Vector(
        (T1, "a-seen", "seen-1"),
        (T1, "b-seen", "seen-2"),
        (T1, "c-fresh", "fresh signal"),
        (T2, "d-later", "strictly after watermark")
      ),
      Some(cursor(T1, "a-seen", "b-seen"))
    )
    assertEquals(unseen, Vector("fresh signal", "strictly after watermark"))

  // --- unseen: empty cursor + edge cases ------------------------------------

  test("cursor = None: every entry is unseen regardless of id"):
    val unseen = Comments.unseen(
      Vector((T0, "a", "x"), (T1, "b", "y"), (T2, "c", "z")),
      None
    )
    assertEquals(unseen, Vector("x", "y", "z"))

  test("cursor = None with empty input: empty output"):
    val unseen = Comments.unseen(Vector.empty[(Instant, String, String)], None)
    assertEquals(unseen, Vector.empty[String])

  test("output preserves input order"):
    val unseen = Comments.unseen(
      Vector((T3, "d", "third"), (T1, "a", "first"), (T2, "c", "second")),
      None
    )
    assertEquals(unseen, Vector("third", "first", "second"))

  // --- advance: empty input ----------------------------------------------------

  test("advance: empty entries keeps prior cursor unchanged (None → None)"):
    val next = Comments.advance(Vector.empty[(Instant, String)], None)
    assertEquals(next, None)

  test("advance: empty entries keeps prior cursor unchanged (Some(T1) → Some(T1))"):
    val prior = cursor(T1, "id1")
    val next = Comments.advance(Vector.empty[(Instant, String)], Some(prior))
    assertEquals(next, Some(prior))

  // --- advance: fresh cursor / forward watermark -----------------------------

  test("advance: prior=None, single entry at T1 → BaselineCursor(T1, {id1})"):
    val next = Comments.advance(Vector((T1, "id1")), None)
    assertEquals(next, Some(BaselineCursor(T1, Set("id1"))))

  test("advance: max strictly after prior — fresh seenIds at the new watermark"):
    val prior = cursor(T1, "id-old-a", "id-old-b")
    val next = Comments.advance(
      Vector((T1, "id-old-a"), (T2, "id-new")),
      Some(prior)
    )
    // Watermark moved to T2; only T2-timestamped ids enter the new seenIds set.
    assertEquals(next, Some(BaselineCursor(T2, Set("id-new"))))

  test("advance: multiple entries share the new max — all those ids enter seenIds"):
    val next = Comments.advance(
      Vector((T2, "id1"), (T2, "id2"), (T1, "id-old")),
      None
    )
    assertEquals(next, Some(BaselineCursor(T2, Set("id1", "id2"))))

  // --- advance: equal watermark accumulates seenIds across polls ------------

  test("advance: max equals prior.at — union prior.seenIds with ids at the watermark"):
    val prior = cursor(T1, "id-prev")
    val next = Comments.advance(
      Vector((T0, "id-old"), (T1, "id-prev"), (T1, "id-fresh-same-second")),
      Some(prior)
    )
    // Watermark stays at T1; seenIds accumulates the new same-second id alongside the prior one.
    assertEquals(next, Some(BaselineCursor(T1, Set("id-prev", "id-fresh-same-second"))))

  test("advance: max < prior.at — defensive, keep prior cursor"):
    val prior = cursor(T2, "id-current")
    val next = Comments.advance(Vector((T0, "id-stale")), Some(prior))
    // Out-of-order or late-arriving older entries don't move the cursor backwards.
    assertEquals(next, Some(prior))
