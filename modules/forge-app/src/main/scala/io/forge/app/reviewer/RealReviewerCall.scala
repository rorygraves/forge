package io.forge.app.reviewer

import cats.effect.IO
import io.forge.agents.{
  Connector,
  DesignReview,
  DesignReviewInput,
  PrReview,
  PrReviewInput,
  RefineInput,
  RefineResult,
  ReviewerError
}

/** §7.9 wall-clock cap implementation. Each of the three methods runs the underlying `connector.review*` IO under
  * `.attempt` so [[ReviewerError]] subclasses become [[ReviewerOutcome.AdapterFailure]] (preserving sub-variant
  * identity per §7.6), then races that against an `IO.sleep(limits.wallClockTimeout)` so the cap fires first if the
  * reviewer stalls (per design-1.4 §B2).
  *
  * **Cancellation discipline.** When the timeout wins the race, `IO.race` cancels the reviewer fiber; the connector's
  * `Resource` finalizer is the path that actually releases any leftover subprocess (no observable kill channel here —
  * see [[ReviewerCall]] / [[ReviewerOutcome.Timeout]] docstrings + carry-forward S4-3). Non-[[ReviewerError]]
  * `Throwable`s raised by the connector are intentionally **not** caught here — they propagate, mirroring how the
  * streaming driver surfaces unexpected errors at the call site.
  *
  * **Lifetime.** `connector` is constructed once per orchestrator run (Task 1.4.10) and shared across reviewer calls;
  * this class holds a reference but does not own the resource.
  */
final class RealReviewerCall(connector: Connector) extends ReviewerCall:

  override def designReview(
      input: DesignReviewInput,
      limits: ReviewerLimits
  ): IO[ReviewerOutcome[DesignReview]] =
    runWithCap(limits, connector.reviewDesign(input))

  override def prReview(
      input: PrReviewInput,
      limits: ReviewerLimits
  ): IO[ReviewerOutcome[PrReview]] =
    runWithCap(limits, connector.reviewPr(input))

  override def refine(
      input: RefineInput,
      limits: ReviewerLimits
  ): IO[ReviewerOutcome[RefineResult]] =
    runWithCap(limits, connector.refine(input))

  private def runWithCap[A](
      limits: ReviewerLimits,
      call: IO[A]
  ): IO[ReviewerOutcome[A]] =
    val attempted: IO[ReviewerOutcome[A]] =
      call.attempt.flatMap {
        case Right(value) => IO.pure(ReviewerOutcome.Settled(value))
        case Left(err: ReviewerError) => IO.pure(ReviewerOutcome.AdapterFailure(err))
        case Left(other) => IO.raiseError(other)
      }
    IO.race(IO.sleep(limits.wallClockTimeout), attempted).map {
      case Left(_) => ReviewerOutcome.Timeout
      case Right(outcome) => outcome
    }
