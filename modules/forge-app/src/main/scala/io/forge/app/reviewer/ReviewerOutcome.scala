package io.forge.app.reviewer

import io.forge.agents.ReviewerError
import io.forge.core.cost.Cost

/** §7.9 + §7.5 / §7.6 — output ADT produced by [[ReviewerCall]] for the three one-shot reviewer / refine phases
  * (`DesignReview`, `CodeReview`, `Refine`). Distinct from [[io.forge.app.monitor.MonitorOutcome]] on purpose:
  * `MonitorOutcome` belongs to the driver-phase [[io.forge.app.monitor.SessionMonitor]] surface; this is the reviewer
  * side. The orchestrator (Task 1.4.10) maps each variant onto an FSM-visible event:
  *
  *   - [[Settled]] → either an `Fsm.transition` `Settled(SessionPhase.{DesignReview, CodeReview, Refine}, ...)` event
  *     when the orchestrator has finished its post-settle bookkeeping (Task 1.4.10 J2 post-settle synthesis), or feeds
  *     the typed result directly into the next FSM transition (e.g. `DesignReview.verdict` chooses `RequestChanges` vs
  *     `Approve`).
  *   - [[Timeout]] → `FsmEvent.SettleTimeout(SessionPhase.{DesignReview, CodeReview, Refine}, reason)` per Task 1.4.12
  *     option (a) — the recommended path (design-1.4 §B3). Closes carry-forwards **S2-8** / **S3-5**.
  *   - [[AdapterFailure]] → routed per §7.5 / §7.6 — [[io.forge.agents.ReviewerProcessFailure]] is retryable via
  *     `reviewProcessRetries` / `refineProcessRetries`; the other variants are surfaced as `NeedsHumanIntervention`.
  *
  * **`cost` on `Settled` (S4-3 closed); `killError` on `Timeout` still deferred.** The reviewer-call cost is now
  * surfaced (the connector reads it off the same envelope it decodes the verdict from) so reviewer spend folds into
  * `Feature.cost` — see [[Settled]]. The observable-kill diagnostic on [[Timeout]] stays deferred (the connector
  * exposes no subprocess handle to the wrapper).
  */
sealed trait ReviewerOutcome[+A]

object ReviewerOutcome:

  /** Reviewer / refine call completed cleanly within the wall-clock cap. `cost` is the spend the CLI envelope reported
    * for the call (S4-3), `None` when the envelope carried none — the orchestrator folds it into `Feature.cost`.
    */
  final case class Settled[A](result: A, cost: Option[Cost]) extends ReviewerOutcome[A]

  /** Wall-clock cap fired before the underlying `connector.review*` IO completed. The wrapper cancels the in-flight
    * reviewer fiber via `IO.race`; subprocess cleanup is the enclosing connector resource finalizer's responsibility
    * and is not observable here (see [[ReviewerCall]] docstring + carry-forward **S4-3**).
    */
  case object Timeout extends ReviewerOutcome[Nothing]

  /** Reviewer raised a [[io.forge.agents.ReviewerError]] subclass into the IO; caught via `.attempt` and surfaced in
    * the outcome channel so the orchestrator sees process-level vs adapter-level failures uniformly. Sub-variant
    * identity is preserved — the orchestrator's retry wrapper switches on the trait at the call boundary per §7.6.
    */
  final case class AdapterFailure(err: ReviewerError) extends ReviewerOutcome[Nothing]
