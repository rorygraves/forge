package io.forge.core.state

import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmFixtures, FsmState, SessionPhase}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.Action

import java.time.Instant

/** Slice 1.4b Task 1.4.10 / carry-forward **S4-4** — pins the pure [[RebuildState.inFlightSessions]] projection that
  * the orchestrator's restart recovery consumes.
  *
  * The projection answers a single question against a replayed log + the post-reconcile feature: "is a driver session
  * still in flight at the log tail?" A `<actor>.spawn` / `<actor>.resume` for the current state's driver phase that is
  * NOT followed by a [[RebuildState.MonitorOutcomeKind]] marker (same piece key) is in flight; a spawn whose monitor
  * outcome IS logged (the post-settle crash window — e.g. `Settled(Implement, Clean)` is an FSM no-op until `PrOpened`)
  * is not, and is recovered by the orchestrator's post-settle synthesis instead.
  */
class RebuildStateInFlightSuite extends munit.FunSuite:

  private val ts0 = Instant.parse("2026-05-27T09:00:00Z")
  private def at(n: Int): Instant = ts0.plusSeconds(n.toLong)

  private def feature(state: FsmState): Feature =
    val manifest = FsmFixtures.manifest(Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr))))
    Feature.initial(FeatureA, manifest).copy(state = state)

  private def action(
      seq: Long,
      kind: String,
      piece: Option[PieceId],
      payload: ujson.Value = ujson.Obj()
  ): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = piece,
      actor = Some("claude"),
      role = Some("driver"),
      kind = kind,
      payload = payload
    )

  private def spawn(seq: Long, piece: Option[PieceId], sid: String): Action =
    action(seq, "claude.spawn", piece, ujson.Obj("sessionId" -> ujson.Str(sid), "role" -> ujson.Str("driver")))

  private def resume(seq: Long, piece: Option[PieceId], oldSid: String, newSid: String): Action =
    action(
      seq,
      "claude.resume",
      piece,
      ujson.Obj("oldSessionId" -> ujson.Str(oldSid), "newSessionId" -> ujson.Str(newSid))
    )

  private def monitorOutcome(seq: Long, piece: Option[PieceId]): Action =
    action(seq, RebuildState.MonitorOutcomeKind, piece, ujson.Obj("kind" -> ujson.Str("settled")))

  // --- non-driver states carry no in-flight session ---

  test("non-driver state → empty (no driver runs in Drafting / PieceAwaitingCi / Refining / terminal)"):
    val log = Vector(spawn(1, Some(P1), "sess-1"))
    for state <- Vector[FsmState](
        FsmState.Drafting,
        FsmState.DesignReady,
        FsmState.PieceAwaitingCi(P1, P1Pr),
        FsmState.PieceAwaitingReview(P1, P1Pr),
        FsmState.PieceAwaitingMerge(P1, P1Pr),
        FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
        FsmState.FeatureDone
      )
    do assertEquals(RebuildState.inFlightSessions(log, feature(state)), Vector.empty, s"state=$state")

  // --- driver states: spawn with no settle marker → in flight ---

  test("PieceImplementing + spawn, no monitor outcome → Implement in-flight session"):
    val log = Vector(spawn(5, Some(P1), "impl-sess"))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

  test("PieceFixingUp + spawn, no monitor outcome → Fixup in-flight session"):
    val log = Vector(spawn(5, Some(P1), "fix-sess"))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceFixingUp(P1, P1Pr, attempt = 1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Fixup, "fix-sess", Some(P1)))
    )

  test("InteractiveSpec + spawn(piece=None), no monitor outcome → Spec in-flight session"):
    val log = Vector(spawn(2, None, "spec-sess"))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.InteractiveSpec)),
      Vector(RebuildState.InFlightSession(SessionPhase.Spec, "spec-sess", None))
    )

  test("DesignReviewing + resume(piece=None) → DesignRevision in-flight session uses newSessionId"):
    val log = Vector(spawn(1, None, "spec-sess"), monitorOutcome(2, None), resume(3, None, "spec-sess", "rev-sess"))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.DesignReviewing(round = 2))),
      Vector(RebuildState.InFlightSession(SessionPhase.DesignRevision, "rev-sess", None))
    )

  test("DesignPrFeedback maps to the DesignRevision driver phase"):
    val log = Vector(resume(7, None, "rev-old", "rev-new"))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.DesignPrFeedback(P1Pr, round = 1))),
      Vector(RebuildState.InFlightSession(SessionPhase.DesignRevision, "rev-new", None))
    )

  // --- settle marker present → not in flight (post-settle crash window) ---

  test("PieceImplementing + spawn + monitor outcome → empty (driver settled; post-settle synthesis recovers it)"):
    val log = Vector(spawn(5, Some(P1), "impl-sess"), monitorOutcome(6, Some(P1)))
    assertEquals(RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))), Vector.empty)

  test("monitor outcome for a different piece does not close this piece's spawn"):
    val log = Vector(spawn(5, Some(P1), "impl-sess"), monitorOutcome(6, Some(P2)))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

  test("the last spawn wins — a re-spawn after a settled session is the in-flight one"):
    val log = Vector(
      spawn(5, Some(P1), "impl-sess-1"),
      monitorOutcome(6, Some(P1)),
      spawn(7, Some(P1), "impl-sess-2")
    )
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess-2", Some(P1)))
    )

  test("driver state but no spawn ever logged → empty (state-entry spawn not yet reached)"):
    assertEquals(RebuildState.inFlightSessions(Vector.empty, feature(FsmState.PieceImplementing(P1))), Vector.empty)
