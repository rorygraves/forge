package io.forge.app.reviewer

import cats.effect.{IO, Ref}
import io.forge.agents.*
import io.forge.core.{FeatureId, PieceId, PrNumber}
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** Task 1.4.10-d2c (S4-5) — the §7.6 process-failure retry decorator. Asserts: a retryable [[ReviewerProcessFailure]]
  * is re-issued up to the budget; a persistent one exhausts it and surfaces; non-process failures and `Timeout` are
  * never retried; `designReview`/`prReview` draw on `reviewRetries` while `refine` draws on its own `refineRetries`.
  */
class RetryingReviewerCallSuite extends CatsEffectSuite:

  private val limits = ReviewerLimits(wallClockTimeout = 30.seconds)

  private val designInput = DesignReviewInput(FeatureId("feat-1"), round = 1, designMarkdown = "# design")
  private val prInput =
    PrReviewInput(FeatureId("feat-1"), PieceId("p1"), PrNumber(42), pieceSpec = "spec", diff = "diff", Vector.empty)
  private val refineInput = RefineInput(FeatureId("feat-1"), PieceId("p1"), designMarkdown = "# design", "{}")

  private val approveDesign =
    ReviewerOutcome.Settled(DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok"))
  private val approvePr = ReviewerOutcome.Settled(PrReview(ReviewVerdict.Approve, Vector.empty, "ok"))
  private val refineNoChange = ReviewerOutcome.Settled(RefineResult(RefineOutcome.NoChange, "", None))

  /** A [[ReviewerCall]] that pops a scripted outcome per call and counts how many times each method was invoked. */
  private final class ScriptedReviewerCall(
      designScript: Ref[IO, List[ReviewerOutcome[DesignReview]]],
      prScript: Ref[IO, List[ReviewerOutcome[PrReview]]],
      refineScript: Ref[IO, List[ReviewerOutcome[RefineResult]]],
      val calls: Ref[IO, Int]
  ) extends ReviewerCall:
    private def pop[A](script: Ref[IO, List[ReviewerOutcome[A]]]): IO[ReviewerOutcome[A]] =
      calls.update(_ + 1) >> script.modify {
        case h :: t => (t, IO.pure(h))
        case Nil => (Nil, IO.raiseError(new IllegalStateException("ScriptedReviewerCall: script exhausted")))
      }.flatten
    override def designReview(input: DesignReviewInput, l: ReviewerLimits): IO[ReviewerOutcome[DesignReview]] =
      pop(designScript)
    override def prReview(input: PrReviewInput, l: ReviewerLimits): IO[ReviewerOutcome[PrReview]] = pop(prScript)
    override def refine(input: RefineInput, l: ReviewerLimits): IO[ReviewerOutcome[RefineResult]] = pop(refineScript)

  private def scripted(
      design: List[ReviewerOutcome[DesignReview]] = Nil,
      pr: List[ReviewerOutcome[PrReview]] = Nil,
      refine: List[ReviewerOutcome[RefineResult]] = Nil
  ): IO[ScriptedReviewerCall] =
    for
      d <- Ref.of[IO, List[ReviewerOutcome[DesignReview]]](design)
      p <- Ref.of[IO, List[ReviewerOutcome[PrReview]]](pr)
      r <- Ref.of[IO, List[ReviewerOutcome[RefineResult]]](refine)
      c <- Ref.of[IO, Int](0)
    yield new ScriptedReviewerCall(d, p, r, c)

  private def procFail[A]: ReviewerOutcome[A] = ReviewerOutcome.AdapterFailure(ReviewerProcessFailure("boom"))

  test("transient ReviewerProcessFailure then Settled → Settled, with one retry consumed"):
    for
      fake <- scripted(design = List(procFail, approveDesign))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 2, refineRetries = 2)
      out <- decorated.designReview(designInput, limits)
      n <- fake.calls.get
    yield
      assertEquals(out, approveDesign)
      assertEquals(n, 2)

  test("persistent ReviewerProcessFailure exhausts the budget and surfaces AdapterFailure"):
    for
      fake <- scripted(design = List.fill(5)(procFail))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 2, refineRetries = 2)
      out <- decorated.designReview(designInput, limits)
      n <- fake.calls.get
    yield
      out match
        case ReviewerOutcome.AdapterFailure(_: ReviewerProcessFailure) => ()
        case other => fail(s"expected AdapterFailure(ReviewerProcessFailure), got $other")
      assertEquals(n, 3) // initial + 2 retries

  test("clean Settled is issued exactly once (no retry)"):
    for
      fake <- scripted(pr = List(approvePr))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 2, refineRetries = 2)
      out <- decorated.prReview(prInput, limits)
      n <- fake.calls.get
    yield
      assertEquals(out, approvePr)
      assertEquals(n, 1)

  test("non-process AdapterFailure (StructuredOutputMissing) is not retried"):
    val missing: ReviewerOutcome[PrReview] =
      ReviewerOutcome.AdapterFailure(StructuredOutputMissing("no structured_output"))
    for
      fake <- scripted(pr = List(missing, approvePr))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 2, refineRetries = 2)
      out <- decorated.prReview(prInput, limits)
      n <- fake.calls.get
    yield
      out match
        case ReviewerOutcome.AdapterFailure(_: StructuredOutputMissing) => ()
        case other => fail(s"expected AdapterFailure(StructuredOutputMissing), got $other")
      assertEquals(n, 1)

  test("Timeout is not retried"):
    for
      fake <- scripted(design = List(ReviewerOutcome.Timeout, approveDesign))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 2, refineRetries = 2)
      out <- decorated.designReview(designInput, limits)
      n <- fake.calls.get
    yield
      assertEquals(out, ReviewerOutcome.Timeout: ReviewerOutcome[DesignReview])
      assertEquals(n, 1)

  test("refine draws on refineRetries, not reviewRetries"):
    for
      fake <- scripted(refine = List(procFail, procFail, refineNoChange))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 0, refineRetries = 2)
      out <- decorated.refine(refineInput, limits)
      n <- fake.calls.get
    yield
      assertEquals(out, refineNoChange)
      assertEquals(n, 3) // initial + 2 refine retries; reviewRetries=0 must not apply here

  test("retry count of 0 is a transparent pass-through (one call, failure surfaced)"):
    for
      fake <- scripted(design = List(procFail))
      decorated = new RetryingReviewerCall(fake, reviewRetries = 0, refineRetries = 0)
      out <- decorated.designReview(designInput, limits)
      n <- fake.calls.get
    yield
      out match
        case ReviewerOutcome.AdapterFailure(_: ReviewerProcessFailure) => ()
        case other => fail(s"expected AdapterFailure(ReviewerProcessFailure), got $other")
      assertEquals(n, 1)
