package io.forge.core.review

import io.forge.core.{FeatureId, PieceId}

/** Decision D8 — the §19 `review.request_changes` audit action's payload shape. The blocker prose recorded here is the
  * recurring-reviewer-comment signal the `ConventionLearner` (§11.7) mines; the orchestrator's
  * `Orchestrator.observedReviewerComments` reads exactly these fields back, so the keys are a contract. (Replay
  * inertness is proven in `ProfileReplayInvarianceSuite` R1; the end-to-end mining in
  * `OrchestratorConventionLearnerSuite`.)
  */
class ReviewRequestedChangesActionSuite extends munit.FunSuite:

  private val feature = FeatureId("feat")

  test("code-review draft carries the piece tag, a null round, and the blockers"):
    val d = ReviewRequestedChangesAction.draft(
      feature,
      gate = "code",
      round = None,
      piece = Some(PieceId("p1")),
      blockers = Vector("name the lock owner", "missing null check")
    )
    assertEquals(d.kind, "review.request_changes")
    assertEquals(d.piece, Some(PieceId("p1")))
    assertEquals(d.payload("gate").str, "code")
    assertEquals(d.payload("round"), ujson.Null)
    assertEquals(d.payload("blockers").arr.map(_.str).toVector, Vector("name the lock owner", "missing null check"))

  test("design-review draft is piece-less and carries the round"):
    val d = ReviewRequestedChangesAction.draft(
      feature,
      gate = "design",
      round = Some(2),
      piece = None,
      blockers = Vector("decompose the auth slice")
    )
    assertEquals(d.piece, None)
    assertEquals(d.payload("gate").str, "design")
    assertEquals(d.payload("round").num.toInt, 2)
    assertEquals(d.payload("blockers").arr.map(_.str).toVector, Vector("decompose the auth slice"))
