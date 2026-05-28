# Design: reviewer-call wall-clock cap

## Goal

Give the orchestrator's one-shot reviewer / refine phases the same "kill on
stall" backstop the streaming driver phases get from `SessionMonitor`, without
reaching across into `MonitorOutcome`.

## Pieces

1. **p1 — `ReviewerCall` trait + `ReviewerOutcome` ADT + `RealReviewerCall`.**
   Wrap the three `Connector.review*` methods with a wall-clock cap via
   `IO.race(IO.sleep(timeout), attempted)`. (just merged)
2. **p2 — orchestrator maps `ReviewerOutcome` onto FSM events.** (pending)

## Just-merged piece (p1)

Shipped `ReviewerCall`, `ReviewerOutcome.{Settled, Timeout, AdapterFailure}`,
and `RealReviewerCall`. During implementation it became clear the underlying
`Connector.review*` methods return **only** the typed review value — they do
not surface cost, and the one-shot reviewer collectors never emit
`CostUpdate`. So `ReviewerOutcome.Settled` carries no `cost`, and per-call cost
enforcement is impossible at this boundary.

## Refine question

Merging p1 revealed a gap the original design did not anticipate: reviewer
spend cannot contribute to the §12 budget caps without a connector-boundary
widening. This is not covered by any pending piece. A new piece is needed to
either widen `Connector.review*` to return `IO[(A, Cost)]` or plumb
`CostUpdate` through the one-shot collectors — otherwise the budget invariant
silently excludes reviewer cost. The plan should gain a piece for this.
