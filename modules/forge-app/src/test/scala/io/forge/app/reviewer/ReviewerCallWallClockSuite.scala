package io.forge.app.reviewer

import cats.effect.IO
import cats.effect.testkit.TestControl
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId, PrNumber}
import munit.CatsEffectSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Task 1.4.2 B4 — wall-clock cap fires when the underlying reviewer IO stalls. Uses [[TestControl]] so the cap fires
  * deterministically once the simulated clock advances past `limits.wallClockTimeout`. Cancellation is observed via the
  * `FakeReviewerConnector.cancelled` Ref (the connector's `.onCancel` flips it) — there is no observable kill channel
  * on [[ReviewerOutcome.Timeout]] (see [[ReviewerCall]] docstring + carry-forward S4-3).
  */
class ReviewerCallWallClockSuite extends CatsEffectSuite:

  private val cap: FiniteDuration = 30.seconds
  private val limits = ReviewerLimits(wallClockTimeout = cap)

  private val designInput =
    DesignReviewInput(featureId = FeatureId("feat-1"), round = 1, designMarkdown = "# design")

  private val prInput =
    PrReviewInput(
      featureId = FeatureId("feat-1"),
      pieceId = PieceId("p1"),
      prNumber = PrNumber(42),
      pieceSpec = "spec",
      diff = "diff",
      changedFiles = Vector.empty
    )

  private val refineInput =
    RefineInput(
      featureId = FeatureId("feat-1"),
      mergedPieceId = PieceId("p1"),
      designMarkdown = "# design",
      manifestJson = "{}"
    )

  test("designReview: IO.never reviewer call → Timeout, fiber cancelled"):
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.never)
        call = new RealReviewerCall(connector)
        outcome <- call.designReview(designInput, limits)
        cancelled <- connector.cancelled.get
      yield (outcome, cancelled)
    TestControl.executeEmbed(program).map { case (outcome, cancelled) =>
      assertEquals(outcome, ReviewerOutcome.Timeout)
      assert(cancelled, "reviewer fiber must be cancelled when the wall-clock cap fires")
    }

  test("prReview: IO.never reviewer call → Timeout, fiber cancelled"):
    val program =
      for
        connector <- FakeReviewerConnector.make(prReviewIO = IO.never)
        call = new RealReviewerCall(connector)
        outcome <- call.prReview(prInput, limits)
        cancelled <- connector.cancelled.get
      yield (outcome, cancelled)
    TestControl.executeEmbed(program).map { case (outcome, cancelled) =>
      assertEquals(outcome, ReviewerOutcome.Timeout)
      assert(cancelled, "reviewer fiber must be cancelled when the wall-clock cap fires")
    }

  test("refine: IO.never reviewer call → Timeout, fiber cancelled"):
    val program =
      for
        connector <- FakeReviewerConnector.make(refineIO = IO.never)
        call = new RealReviewerCall(connector)
        outcome <- call.refine(refineInput, limits)
        cancelled <- connector.cancelled.get
      yield (outcome, cancelled)
    TestControl.executeEmbed(program).map { case (outcome, cancelled) =>
      assertEquals(outcome, ReviewerOutcome.Timeout)
      assert(cancelled, "reviewer fiber must be cancelled when the wall-clock cap fires")
    }

  test("reviewer returns just before cap → Settled wins the race, no cancellation"):
    val review = DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok")
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.sleep(cap / 4).as(review))
        call = new RealReviewerCall(connector)
        outcome <- call.designReview(designInput, limits)
        cancelled <- connector.cancelled.get
      yield (outcome, cancelled)
    TestControl.executeEmbed(program).map { case (outcome, cancelled) =>
      outcome match
        case ReviewerOutcome.Settled(r) => assertEquals(r, review)
        case other => fail(s"expected Settled, got $other")
      assert(!cancelled, "no cancellation when the reviewer settles before the cap")
    }
