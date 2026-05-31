package io.forge.app.orchestrator

import cats.effect.IO
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession}
import io.forge.core.*
import io.forge.core.fsm.{FsmState, ResumeHint, SessionPhase}
import io.forge.core.manifest.ManifestPatch

import java.time.Instant

/** Task 1.4.10 J2 — table-driven assertion that every documented `(FsmState, activeSession)` row selects exactly the
  * sources the source-selection table names, and that unreachable combinations raise `IllegalStateException` (so a
  * future lifecycle bug is loud rather than silently racing the wrong set).
  */
class OrchestratorSourceSelectionSuite extends munit.FunSuite:

  /** A no-op [[AgentSession]] — `select` only inspects `activeSession.map(_.phase)`, never the session itself. */
  private object StubSession extends AgentSession:
    override def sessionId: String = "stub"
    override def events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] = IO.unit

  private def session(phase: SessionPhase): Option[ActiveSession] = Some(ActiveSession(phase, StubSession))

  private val P1 = PieceId("p1")
  private val Pr = PrNumber(4291)
  private val DesignPr = PrNumber(4290)
  private val Q = Question("which?", Vector("a", "b"), allowFreeText = false, QuestionSeverity.Blocking)
  private val Patch = ManifestPatch("reason", Vector.empty)
  private val Started = Instant.parse("2026-05-27T09:00:00Z")

  import EventSource.*

  test(
    "gap #13: PieceAwaitingReview watcher emits only on a human override (else None, so the reviewer wins the race)"
  ):
    val noop = OrchestratorTestKit.openSnapshot(Pr) // OPEN, no reviewDecision, no unseen comments
    assertEquals(Orchestrator.pieceReviewWatcherEvent(P1, noop), None, "a no-op poll must not win the reviewer race")
    val changesRequested = noop.copy(reviewDecision = Some(io.forge.core.pr.ReviewDecision.ChangesRequested))
    assert(
      Orchestrator.pieceReviewWatcherEvent(P1, changesRequested).isDefined,
      "CHANGES_REQUESTED is a human override"
    )
    val withComment =
      noop.copy(unseenComments = Vector(io.forge.core.pr.PrComment("c1", "alice", "please fix", Started, None, None)))
    assert(Orchestrator.pieceReviewWatcherEvent(P1, withComment).isDefined, "an unseen human comment is an override")

  // Each row: (label, state, activeSession, expected sources).
  private val rows: Vector[(String, FsmState, Option[ActiveSession], Vector[EventSource])] = Vector(
    ("Drafting", FsmState.Drafting, None, Vector.empty),
    ("InteractiveSpec", FsmState.InteractiveSpec, session(SessionPhase.Spec), Vector(Monitor, Repl)),
    (
      "DesignReviewing (no session → reviewer)",
      FsmState.DesignReviewing(1),
      None,
      Vector(Reviewer(ReviewerMethod.DesignReview))
    ),
    (
      "DesignReviewing (revision driver → monitor)",
      FsmState.DesignReviewing(2),
      session(SessionPhase.DesignRevision),
      Vector(Monitor)
    ),
    ("DesignNeedsHumanInput", FsmState.DesignNeedsHumanInput(1, Vector(Q)), None, Vector(UserQa)),
    ("DesignAwaitingMerge", FsmState.DesignAwaitingMerge(DesignPr), None, Vector(Watcher(DesignPr))),
    ("DesignPrFeedback", FsmState.DesignPrFeedback(DesignPr, 1), session(SessionPhase.DesignRevision), Vector(Monitor)),
    ("DesignReady", FsmState.DesignReady, None, Vector.empty),
    ("PieceImplementing", FsmState.PieceImplementing(P1), session(SessionPhase.Implement), Vector(Monitor)),
    ("PieceAwaitingCi", FsmState.PieceAwaitingCi(P1, Pr), None, Vector(Watcher(Pr))),
    (
      "PieceAwaitingReview (watcher + reviewer race)",
      FsmState.PieceAwaitingReview(P1, Pr),
      None,
      Vector(Watcher(Pr), Reviewer(ReviewerMethod.PrReview))
    ),
    ("PieceCiFailed", FsmState.PieceCiFailed(P1, Pr, 1), None, Vector.empty),
    ("PieceReviewFailed", FsmState.PieceReviewFailed(P1, Pr, 1), None, Vector.empty),
    ("PieceFixingUp", FsmState.PieceFixingUp(P1, Pr, 1), session(SessionPhase.Fixup), Vector(Monitor)),
    ("PieceAwaitingMerge", FsmState.PieceAwaitingMerge(P1, Pr), None, Vector(Watcher(Pr))),
    ("Refining", FsmState.Refining(P1, Pr, Started), None, Vector(Reviewer(ReviewerMethod.Refine))),
    ("PlanningUpdate", FsmState.PlanningUpdate("reason", Patch), None, Vector(UserQa)),
    ("NeedsHumanIntervention", FsmState.NeedsHumanIntervention("stuck", ResumeHint.AbortOrAbandon), None, Vector.empty),
    ("FeatureDone", FsmState.FeatureDone, None, Vector.empty),
    ("Abandoned", FsmState.Abandoned("gave up"), None, Vector.empty)
  )

  rows.foreach { case (label, state, active, expected) =>
    test(s"source selection — $label"):
      assertEquals(EventSources.select(state, active), expected)
  }

  // Representative unreachable combinations — each is a lifecycle bug the loop must never produce.
  private val illegalRows: Vector[(String, FsmState, Option[ActiveSession])] = Vector(
    ("Drafting with a session", FsmState.Drafting, session(SessionPhase.Spec)),
    ("InteractiveSpec with no session", FsmState.InteractiveSpec, None),
    ("InteractiveSpec with wrong phase", FsmState.InteractiveSpec, session(SessionPhase.Implement)),
    ("DesignReviewing with an Implement session", FsmState.DesignReviewing(1), session(SessionPhase.Implement)),
    ("DesignPrFeedback with no session", FsmState.DesignPrFeedback(DesignPr, 1), None),
    ("PieceImplementing with no session", FsmState.PieceImplementing(P1), None),
    ("PieceAwaitingCi with a live session", FsmState.PieceAwaitingCi(P1, Pr), session(SessionPhase.Implement)),
    ("PieceFixingUp with the wrong phase", FsmState.PieceFixingUp(P1, Pr, 1), session(SessionPhase.Implement)),
    ("PieceAwaitingMerge with a live session", FsmState.PieceAwaitingMerge(P1, Pr), session(SessionPhase.Implement))
  )

  illegalRows.foreach { case (label, state, active) =>
    test(s"unreachable combination raises — $label"):
      intercept[IllegalStateException](EventSources.select(state, active))
  }
