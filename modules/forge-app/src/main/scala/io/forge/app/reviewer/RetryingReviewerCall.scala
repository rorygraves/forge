package io.forge.app.reviewer

import cats.effect.IO
import io.forge.agents.{
  DesignReview,
  DesignReviewInput,
  PrReview,
  PrReviewInput,
  RefineInput,
  RefineResult,
  ReviewerProcessFailure
}

/** §7.6 / §11.2 step 9 — the **process-failure retry** layer over a [[ReviewerCall]] (carry-forward **S4-5**).
  *
  * §7.5 splits reviewer failures into *process-level* ([[ReviewerProcessFailure]] — subprocess crash, non-zero exit,
  * network blip) and *adapter-level* (`ReviewerNotConfigured` / `StructuredOutputMissing` / `StructuredOutputMalformed`
  * — a missing schema or malformed output that a retry can't fix). §7.6 makes only the former retryable, and §11.2 step
  * 9 says the reviewer call is "wrapped by `config.<reviewer>.reviewProcessRetries`". This decorator is that wrapper:
  * it re-issues the underlying call while it returns [[ReviewerOutcome.AdapterFailure]] carrying a
  * [[ReviewerProcessFailure]] and the per-method-class budget is not yet exhausted. Everything else — `Settled`,
  * `Timeout`, and the non-process `AdapterFailure`s — short-circuits on the first attempt.
  *
  * **`Timeout` is deliberately not retried here.** A wall-clock cap fire is mapped by the orchestrator to
  * `FsmEvent.SettleTimeout(...)` (Task 1.4.12 option (a)), which is the spec-visible "the reviewer stalled" signal —
  * not a transient blip to paper over. Retrying it would just burn another full `wallClockTimeout` window before
  * surfacing the same thing.
  *
  * **Two budgets.** `reviewRetries` covers `designReview` + `prReview` (`config.<cli>.reviewProcessRetries`);
  * `refineRetries` covers `refine` (`config.<cli>.refineProcessRetries`). The orchestrator (Task 1.4.10 `Main` wiring)
  * picks the `claude` vs `codex` §18 block from the feature's `Mode` and constructs this decorator once per run,
  * wrapped around the [[RealReviewerCall]] it shares with the connector. A retry count of `0` makes this a transparent
  * pass-through (the underlying call is still issued exactly once).
  *
  * **Lifetime.** Holds a reference to `delegate`; like [[RealReviewerCall]] it does not own the connector resource.
  */
final class RetryingReviewerCall(
    delegate: ReviewerCall,
    reviewRetries: Int,
    refineRetries: Int
) extends ReviewerCall:

  override def designReview(input: DesignReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[DesignReview]] =
    retrying(reviewRetries)(delegate.designReview(input, limits))

  override def prReview(input: PrReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[PrReview]] =
    retrying(reviewRetries)(delegate.prReview(input, limits))

  override def refine(input: RefineInput, limits: ReviewerLimits): IO[ReviewerOutcome[RefineResult]] =
    retrying(refineRetries)(delegate.refine(input, limits))

  /** Re-issue `call` while it returns a retryable [[ReviewerProcessFailure]] and `remaining > 0`. `remaining` counts
    * *retries*, so the call is issued at most `remaining + 1` times.
    */
  private def retrying[A](remaining: Int)(call: IO[ReviewerOutcome[A]]): IO[ReviewerOutcome[A]] =
    call.flatMap {
      case ReviewerOutcome.AdapterFailure(_: ReviewerProcessFailure) if remaining > 0 =>
        retrying(remaining - 1)(call)
      case settledOrTerminal => IO.pure(settledOrTerminal)
    }
