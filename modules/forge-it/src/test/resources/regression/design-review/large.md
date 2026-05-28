# Design: Orchestrator event-source selection + post-settle synthesis

## Context

The headless orchestrator drives a feature by rebuilding `Feature` state from
the action log, then looping: read events from the outside world, feed each
into the pure `Fsm.transition`, and persist the result. The hard part is
deciding **which event sources to race** in each FSM state, and what to do when
a transition is a deliberate no-op.

## The loop

```
start:
  (feature, inFlightSessions) <- RebuildState.run(featureId, ...)
  currentDriverSession <- Ref.of(None)
  for s in inFlightSessions:
    feature <- applyEvent(feature, syntheticHarnessError(s, feature.state))
  if terminal(feature.state): return feature

loop:
  // sub-phase I: idempotent state-entry side effects
  if entered(feature.state): feature <- runEntryHook(feature, currentDriverSession)
  if terminal(feature.state): return feature

  // sub-phase II: race the selected source set
  sources <- eventSources(feature.state, currentDriverSession.get)
  (event, source) <- race(sources)

  (feature', actions) <- Fsm.transition(feature, event, config)
  persistAtomically(feature.manifest, feature'.manifest)   // S2-5 writer side
  log.appendAll(actions); cache.save(feature')

  // sub-phase III: post-settle synthesis (FSM no-op AND source == SessionMonitor)
  if feature'.state == feature.state && source == SessionMonitor:
    currentDriverSession.set(None)
    synthEvent <- runPostSettleSideEffects(feature', event)
    (feature', actions') <- Fsm.transition(feature', synthEvent, config)
    log.appendAll(actions'); cache.save(feature')
  else if source == SessionMonitor:
    currentDriverSession.set(None)

  feature <- feature'; goto loop
```

## Source-selection table

The set of sources raced in each state must be deterministic — racing the wrong
set causes spurious wake-ups or missed events:

| FSM state            | sources raced                              |
|----------------------|--------------------------------------------|
| `DesignReviewing(r)` | ReviewerCall(designReview) only            |
| `DesignAwaitingMerge`| PRWatcher only                             |
| `DesignPrFeedback`   | user commands only (human writes feedback) |
| `PieceImplementing`  | SessionMonitor(driver) only                |
| `PieceAwaitingReview`| ReviewerCall(prReview) only                |
| `PieceAwaitingCi`    | PRWatcher only                             |
| `Refining`           | ReviewerCall(refine) only                  |

The earlier draft raced `SessionMonitor + ReviewerCall` in `PieceAwaitingReview`
and `DesignReviewing`, which is wrong: in those states there is no live driver
session, so `SessionMonitor` would either block forever or fire a stale event.
`currentDriverSession` is `None` in every reviewer-phase state by construction,
so the selection table keys off the state, not off whether a session happens to
be set.

## Restart recovery

A live session object cannot be reconstructed from log entries after a process
restart. `RebuildState.run` therefore returns an `inFlightSession` projection
naming any phase that had an open session when the log was last written. The
orchestrator converts each into a synthetic `HarnessError`, which routes through
`Fsm.transition` to `NeedsHumanIntervention` with a phase-appropriate
`ResumeHint`. This runs **before** any source race, so a restart never silently
resumes a session whose process is gone.

## Session-clear discipline

Session clear is **source-driven**, not payload-driven: `FsmEvent.HarnessError`
carries no `SessionPhase`, so the orchestrator tags each iteration with its
source and clears `currentDriverSession` whenever `source == SessionMonitor`,
regardless of whether the transition changed state.

## Acceptance criteria

- Each FSM state races exactly the sources named in the table; a property test
  asserts no reviewer-phase state includes `SessionMonitor`.
- The manifest is persisted atomically **before** the action-log append and the
  state-cache write (S2-5 writer side), proven on a simulated crash window.
- A restart with a recorded in-flight session surfaces `NeedsHumanIntervention`
  with the correct hint, not a silent resume.
- A post-settle no-op from `SessionMonitor` synthesises the follow-up event
  within the same loop iteration (no extra wake-up).
