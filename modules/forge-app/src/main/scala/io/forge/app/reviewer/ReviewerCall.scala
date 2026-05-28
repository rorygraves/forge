package io.forge.app.reviewer

import cats.effect.IO
import io.forge.agents.{DesignReview, DesignReviewInput, PrReview, PrReviewInput, RefineInput, RefineResult}

import scala.concurrent.duration.FiniteDuration

/** §7.1 / §7.9 reviewer-call boundary. Wraps the three one-shot `Connector.review*` methods with a wall-clock cap so
  * the orchestrator's reviewer / refine phases have the same "kill on stall" backstop the streaming driver phases get
  * from [[io.forge.app.monitor.SessionMonitor]] — but the boundary type, [[ReviewerOutcome]], is deliberately distinct.
  * `MonitorOutcome` is `SessionMonitor`'s driver-phase surface; this wrapper has no business reaching across into it
  * (design-1.4 §B3 — the conversion from `ReviewerOutcome.Timeout` to an FSM-visible event happens orchestrator-side at
  * Task 1.4.10 / Task 1.4.12).
  *
  * **What this wrapper cannot see** (design-1.4 §B1 boundary note):
  *
  *   - **No cost.** The underlying [[io.forge.agents.Connector.reviewDesign]] / `reviewPr` / `refine` methods return
  *     only the typed review value; the one-shot reviewer collectors in `forge-agents` don't emit
  *     `AgentEvent.CostUpdate` the way the streaming pipeline does. Per-reviewer-call cost enforcement is therefore out
  *     of scope for v1.4a; closing it requires a connector-boundary widening (design-rationale carry-forward **S4-3**).
  *     Until then, reviewer spend does not contribute to `feature.cost` / `piece.cost` budget caps — the §12 check 1/2
  *     budgets remain a driver-session-only invariant.
  *   - **No observable kill diagnostic.** The connector exposes no subprocess handle; on cap fire this wrapper cancels
  *     the in-flight reviewer fiber and lets the enclosing connector `Resource` finalizer release the underlying
  *     subprocess. Whether that release reliably issues SIGTERM/SIGKILL depends on connector internals and is not
  *     re-exposed here. [[ReviewerOutcome.Timeout]] therefore carries no `killError` field — see **S4-3** for the same
  *     widening that would surface it.
  */
trait ReviewerCall:
  def designReview(input: DesignReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[DesignReview]]
  def prReview(input: PrReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[PrReview]]
  def refine(input: RefineInput, limits: ReviewerLimits): IO[ReviewerOutcome[RefineResult]]

/** §7.9 reviewer / refine wall-clock cap. Per-call only; no per-call cost cap (see [[ReviewerCall]] docstring and
  * carry-forward S4-3). The orchestrator (Task 1.4.10) populates this from `.forge/config.json` §18 reviewer settle
  * defaults.
  */
final case class ReviewerLimits(wallClockTimeout: FiniteDuration)
