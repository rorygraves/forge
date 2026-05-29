package io.forge.app.orchestrator

import io.forge.agents.AgentSession
import io.forge.core.fsm.SessionPhase

/** The one piece of driver-session state the orchestrator carries that the pure FSM does not model (Task 1.4.10 J2).
  *
  * §11.2 step 12, §11.3 step 2, and the entry rules for `PieceImplementing` / `PieceFixingUp` all spawn driver sessions
  * *inside* a single FSM state (e.g. `DesignReviewing(round)` contains a reviewer sub-phase AND a driver-revision
  * sub-phase). The FSM state alone can't disambiguate "are we running the reviewer one-shot, or watching a driver
  * settle?", so the orchestrator holds the live driver session in a `Ref[IO, Option[ActiveSession]]`. Keeping this
  * orchestrator-local (rather than an extra `Feature` field) keeps the FSM ADT minimal (§22 "no half-states") and
  * matches §11.2's prose-driven sub-phase structure.
  *
  * Typed `AgentSession` (the headless parent trait), not `StreamingSession`: `runHeadlessImplementation` / `runFixup`
  * return `AgentSession`; only `runStreamingSpec` / `resumeStreamingSpec` return `StreamingSession`. The `phase` field
  * carries enough type information to safely downcast where the streaming surface is needed (the `forge spec` REPL
  * matches `case Some(ActiveSession(SessionPhase.Spec, s: StreamingSession))`). `SessionMonitor` consumes only the
  * `AgentSession` surface (`events` + `kill()`), so the headless / streaming distinction is invisible there.
  */
final case class ActiveSession(phase: SessionPhase, session: AgentSession)
