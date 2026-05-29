package io.forge.app.orchestrator

import io.forge.core.PrNumber
import io.forge.core.fsm.{FsmState, SessionPhase}

/** Which reviewer one-shot a state's `ReviewerCall` source drives (§7.1). */
enum ReviewerMethod:
  case DesignReview
  case PrReview
  case Refine

/** A source the orchestrator's sub-phase-II race awaits for the current `(FsmState, activeSession)` pair (Task 1.4.10
  * J2). Each carries enough information for the loop to drive it without re-deriving from the state:
  *
  *   - [[Monitor]] — the active driver session's `SessionMonitor` over `AgentSession.events`.
  *   - [[Watcher]] — a `PRWatcher` poll loop on the named PR.
  *   - [[Reviewer]] — an entry-spawned reviewer one-shot (`ReviewerCall.{designReview, prReview, refine}`); its result
  *     is awaited as a race participant, but the call itself is spawned once on state entry, not per iteration.
  *   - [[Repl]] — the `forge spec` bidirectional REPL channel (only reachable from `InteractiveSpec`).
  *   - [[UserQa]] — operator answers (`forge run --answer-file`, or `forge spec` inline REPL) for a blocking-question
  *     or planning state.
  */
enum EventSource:
  case Monitor
  case Watcher(prNumber: PrNumber)
  case Reviewer(method: ReviewerMethod)
  case Repl
  case UserQa

/** The pure source-selection table from Task 1.4.10 J2 — the sub-phase-II "what do we race in this state?" decision.
  *
  * Consulted only while no post-settle synthesis is pending and after sub-phase I (state-entry side effects) has run,
  * so a state whose row expects a driver session (`Some(phase, _)`) is guaranteed to have it by race time. The
  * selection is total over `FsmState`, and **raises `IllegalStateException` on any unreachable `(state, activeSession)`
  * combination** (e.g. `PieceAwaitingCi` with a live session, or `PieceImplementing` with none) so a future lifecycle
  * bug is loud rather than silently racing the wrong set.
  *
  * States whose row is "(none — entry hook …)" or terminal return `Vector.empty`: the loop either re-loops after the
  * entry hook fires, or exits to the caller.
  */
object EventSources:

  def select(state: FsmState, activeSession: Option[ActiveSession]): Vector[EventSource] =
    state match
      // --- Spec phase ---
      case FsmState.Drafting =>
        requireNoSession(state, activeSession)
        Vector.empty // entry hook fires UserCommand.New, re-loop

      case FsmState.InteractiveSpec =>
        requirePhase(state, activeSession, SessionPhase.Spec)
        Vector(EventSource.Monitor, EventSource.Repl) // forge spec only

      // --- Design review ---
      case _: FsmState.DesignReviewing =>
        // Two reachable shapes: no session → entry-spawned reviewer one-shot; an active design-revision driver →
        // monitor its settle (after a request_changes verdict resumed the spec session).
        activeSession.map(_.phase) match
          case None => Vector(EventSource.Reviewer(ReviewerMethod.DesignReview))
          case Some(SessionPhase.DesignRevision) => Vector(EventSource.Monitor)
          case _ => illegal(state, activeSession)

      case _: FsmState.DesignNeedsHumanInput =>
        requireNoSession(state, activeSession)
        Vector(EventSource.UserQa)

      // --- Design PR gate ---
      case s: FsmState.DesignAwaitingMerge =>
        requireNoSession(state, activeSession)
        Vector(EventSource.Watcher(s.prNumber))

      case _: FsmState.DesignPrFeedback =>
        requirePhase(state, activeSession, SessionPhase.DesignRevision) // entry-spawned resumeStreamingSpec
        Vector(EventSource.Monitor)

      case FsmState.DesignReady =>
        requireNoSession(state, activeSession)
        Vector.empty // entry hook advances to first PieceImplementing or FeatureDone

      // --- Implementation phase ---
      case _: FsmState.PieceImplementing =>
        requirePhase(state, activeSession, SessionPhase.Implement)
        Vector(EventSource.Monitor)

      case s: FsmState.PieceAwaitingCi =>
        requireNoSession(state, activeSession)
        Vector(EventSource.Watcher(s.prNumber))

      case s: FsmState.PieceAwaitingReview =>
        requireNoSession(state, activeSession)
        // PRWatcher + the entry-spawned reviewer race; disjoint FsmEvent types, first to transition wins.
        Vector(EventSource.Watcher(s.prNumber), EventSource.Reviewer(ReviewerMethod.PrReview))

      case _: FsmState.PieceCiFailed =>
        requireNoSession(state, activeSession)
        Vector.empty // entry hook spawns runFixup, re-loop

      case _: FsmState.PieceReviewFailed =>
        requireNoSession(state, activeSession)
        Vector.empty // entry hook spawns runFixup, re-loop

      case _: FsmState.PieceFixingUp =>
        requirePhase(state, activeSession, SessionPhase.Fixup)
        Vector(EventSource.Monitor)

      case s: FsmState.PieceAwaitingMerge =>
        requireNoSession(state, activeSession)
        Vector(EventSource.Watcher(s.prNumber))

      // --- Refining / planning ---
      case _: FsmState.Refining =>
        requireNoSession(state, activeSession)
        Vector(EventSource.Reviewer(ReviewerMethod.Refine)) // entry-spawned

      case _: FsmState.PlanningUpdate =>
        requireNoSession(state, activeSession)
        Vector(EventSource.UserQa) // apply / defer / reopen / ignore per §14.3

      // --- Terminal / recovery: loop exits to caller ---
      case _: FsmState.NeedsHumanIntervention =>
        requireNoSession(state, activeSession)
        Vector.empty
      case FsmState.FeatureDone =>
        requireNoSession(state, activeSession)
        Vector.empty
      case _: FsmState.Abandoned =>
        requireNoSession(state, activeSession)
        Vector.empty

  // --- guards ---

  private def requireNoSession(state: FsmState, activeSession: Option[ActiveSession]): Unit =
    if activeSession.nonEmpty then illegal(state, activeSession)

  private def requirePhase(state: FsmState, activeSession: Option[ActiveSession], expected: SessionPhase): Unit =
    if !activeSession.map(_.phase).contains(expected) then illegal(state, activeSession)

  private def illegal(state: FsmState, activeSession: Option[ActiveSession]): Nothing =
    throw new IllegalStateException(
      s"unreachable orchestrator source combination: state=$state, activeSession=${activeSession.map(_.phase)}"
    )
