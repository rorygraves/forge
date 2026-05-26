package io.forge.git.watcher

/** PR-B B4 — pure-helper coverage for [[Comments.unseen]] independent of the [[PrSnapshotDecoder]].
  *
  * The point of this file is to guard the **numeric `Long` ordering** invariant: GitHub `databaseId` values are 64-bit
  * integers, and a "let's stringify the id for serialization convenience" regression would silently flip the ordering
  * (`"100" < "99"` lexicographically). Three properties below pin the contract:
  *
  *   1. Numeric ordering at the digit-count boundary (99 vs 100). 2. Empty baseline → every entry is unseen. 3.
  *      Baseline equal to the highest seen id → empty unseen set.
  */
class CommentsSuite extends munit.FunSuite:

  // --- numeric Long ordering at the digit-count boundary ---------------------

  test("baseline = Some(100): id 99 is already seen (excluded)"):
    val unseen = Comments.unseen(Vector((99L, "c99")), baseline = Some(100L))
    assertEquals(unseen, Vector.empty[String])

  test("baseline = Some(99): id 100 is unseen (Long ordering, NOT String)"):
    val unseen = Comments.unseen(Vector((100L, "c100")), baseline = Some(99L))
    assertEquals(unseen, Vector("c100"))

  test("baseline = Some(99): both 99 and 100 evaluated; 100 unseen, 99 excluded"):
    val unseen = Comments.unseen(
      Vector((99L, "c99"), (100L, "c100")),
      baseline = Some(99L)
    )
    assertEquals(unseen, Vector("c100"))

  // --- empty baseline ---------------------------------------------------------

  test("baseline = None: every entry is unseen"):
    val unseen = Comments.unseen(
      Vector((1L, "a"), (2L, "b"), (3L, "c")),
      baseline = None
    )
    assertEquals(unseen, Vector("a", "b", "c"))

  test("baseline = None with empty input: empty output"):
    val unseen = Comments.unseen(Vector.empty[(Long, String)], baseline = None)
    assertEquals(unseen, Vector.empty[String])

  // --- baseline equal to the highest seen id ---------------------------------

  test("baseline = Some(last): empty unseen set (filter is strictly greater)"):
    val unseen = Comments.unseen(
      Vector((10L, "a"), (20L, "b"), (30L, "c")),
      baseline = Some(30L)
    )
    assertEquals(unseen, Vector.empty[String])

  test("baseline strictly above all ids: empty unseen set"):
    val unseen = Comments.unseen(
      Vector((10L, "a"), (20L, "b"), (30L, "c")),
      baseline = Some(100L)
    )
    assertEquals(unseen, Vector.empty[String])

  // --- Long.MaxValue boundary (regression catch for accidental Int truncation) ---

  test("baseline near Long.MaxValue: id Long.MaxValue is excluded by equal baseline"):
    val unseen = Comments.unseen(
      Vector((Long.MaxValue, "max")),
      baseline = Some(Long.MaxValue)
    )
    assertEquals(unseen, Vector.empty[String])

  test("baseline near Long.MaxValue: id Long.MaxValue - 1 is excluded"):
    val unseen = Comments.unseen(
      Vector((Long.MaxValue - 1L, "max-minus-one")),
      baseline = Some(Long.MaxValue)
    )
    assertEquals(unseen, Vector.empty[String])
