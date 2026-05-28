package io.forge.app.reviewer

import cats.effect.IO
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId, PrNumber}
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** Task 1.4.2 B4 — happy-path round trip and each [[ReviewerError]] subclass routes to
  * [[ReviewerOutcome.AdapterFailure]] with the sub-variant preserved (§7.6 retry switch happens at the orchestrator
  * boundary, so the wrapper must not collapse the variant set).
  */
class ReviewerCallHappySuite extends CatsEffectSuite:

  private val limits = ReviewerLimits(wallClockTimeout = 30.seconds)

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

  test("designReview clean settle → Settled(result)"):
    val review = DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok")
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.pure(review))
        outcome <- new RealReviewerCall(connector).designReview(designInput, limits)
      yield outcome
    program.assertEquals(ReviewerOutcome.Settled(review))

  test("prReview clean settle → Settled(result)"):
    val review = PrReview(ReviewVerdict.RequestChanges, Vector.empty, "needs work")
    val program =
      for
        connector <- FakeReviewerConnector.make(prReviewIO = IO.pure(review))
        outcome <- new RealReviewerCall(connector).prReview(prInput, limits)
      yield outcome
    program.assertEquals(ReviewerOutcome.Settled(review))

  test("refine clean settle → Settled(result)"):
    val result = RefineResult(RefineOutcome.NoChange, "no change", None)
    val program =
      for
        connector <- FakeReviewerConnector.make(refineIO = IO.pure(result))
        outcome <- new RealReviewerCall(connector).refine(refineInput, limits)
      yield outcome
    program.assertEquals(ReviewerOutcome.Settled(result))

  test("ReviewerProcessFailure → AdapterFailure(ReviewerProcessFailure)"):
    val err = ReviewerProcessFailure("boom")
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.raiseError(err))
        outcome <- new RealReviewerCall(connector).designReview(designInput, limits)
      yield outcome
    program.map(outcome =>
      outcome match
        case ReviewerOutcome.AdapterFailure(e: ReviewerProcessFailure) => assertEquals(e, err)
        case other => fail(s"expected AdapterFailure(ReviewerProcessFailure), got $other")
    )

  test("ReviewerNotConfigured → AdapterFailure(ReviewerNotConfigured)"):
    val err = ReviewerNotConfigured("no schemas")
    val program =
      for
        connector <- FakeReviewerConnector.make(prReviewIO = IO.raiseError(err))
        outcome <- new RealReviewerCall(connector).prReview(prInput, limits)
      yield outcome
    program.map(outcome =>
      outcome match
        case ReviewerOutcome.AdapterFailure(e: ReviewerNotConfigured) => assertEquals(e, err)
        case other => fail(s"expected AdapterFailure(ReviewerNotConfigured), got $other")
    )

  test("StructuredOutputMissing → AdapterFailure(StructuredOutputMissing)"):
    val err = StructuredOutputMissing("no structured_output field")
    val program =
      for
        connector <- FakeReviewerConnector.make(refineIO = IO.raiseError(err))
        outcome <- new RealReviewerCall(connector).refine(refineInput, limits)
      yield outcome
    program.map(outcome =>
      outcome match
        case ReviewerOutcome.AdapterFailure(e: StructuredOutputMissing) => assertEquals(e, err)
        case other => fail(s"expected AdapterFailure(StructuredOutputMissing), got $other")
    )

  test("StructuredOutputMalformed → AdapterFailure(StructuredOutputMalformed)"):
    val err = StructuredOutputMalformed("missing required field 'verdict'")
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.raiseError(err))
        outcome <- new RealReviewerCall(connector).designReview(designInput, limits)
      yield outcome
    program.map(outcome =>
      outcome match
        case ReviewerOutcome.AdapterFailure(e: StructuredOutputMalformed) => assertEquals(e, err)
        case other => fail(s"expected AdapterFailure(StructuredOutputMalformed), got $other")
    )

  test("non-ReviewerError Throwable propagates (not caught by the wrapper)"):
    val err = new RuntimeException("not a reviewer error")
    val program =
      for
        connector <- FakeReviewerConnector.make(designReviewIO = IO.raiseError(err))
        outcome <- new RealReviewerCall(connector).designReview(designInput, limits).attempt
      yield outcome
    program.map {
      case Left(t) => assertEquals(t.getMessage, "not a reviewer error")
      case Right(other) => fail(s"expected propagated Throwable, got $other")
    }
