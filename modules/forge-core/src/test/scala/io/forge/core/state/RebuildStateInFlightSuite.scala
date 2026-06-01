package io.forge.core.state

import io.forge.core.*
import io.forge.core.fsm.{Feature, Fsm, FsmEvent, FsmFixtures, FsmState, SessionPhase}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.Action

/** Slice 1.4b Task 1.4.10 / carry-forward **S4-4** — pins the pure [[RebuildState.inFlightSessions]] projection that
  * the orchestrator's restart recovery consumes.
  *
  * The projection answers a single question against a replayed log + the post-reconcile feature: "is a driver session
  * still in flight at the log tail?" A `<actor>.spawn` / `<actor>.resume` for the current state's driver phase that is
  * NOT followed by a [[RebuildState.MonitorOutcomeKind]] marker (same piece key) is in flight; a spawn whose monitor
  * outcome IS logged (the post-settle crash window — e.g. `Settled(Implement, Clean)` is an FSM no-op until `PrOpened`)
  * is not.
  *
  * NB (roadmap §3.5 / D3): '''no production code currently writes `monitor.outcome`''', so in a real run every logged
  * driver spawn in a live-driver state is treated as in-flight → `NeedsHumanIntervention` (the conservative "no
  * transparent resume" behaviour). The `monitor.outcome`-present cases below exercise the dormant `settledAfter` branch
  * with a '''synthetic''' marker so the D3 post-settle / respawn-avoidance work can wire the writer against a pinned
  * projection — they do not assert a behaviour the orchestrator exhibits today.
  */
class RebuildStateInFlightSuite extends munit.FunSuite:

  // base instant + `at`, and the spawn / resume Action builders, are shared from FsmFixtures (roadmap §3.5).

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

  // spawn / resume use the shared FsmFixtures builders (actor = "claude"):
  //   spawn(seq, piece, sid)            → spawnAction(seq, "claude", sid, piece)
  //   resume(seq, piece, oldSid, newSid) → resumeAction(seq, "claude", oldSid, newSid, piece)

  private def monitorOutcome(seq: Long, piece: Option[PieceId]): Action =
    action(seq, RebuildState.MonitorOutcomeKind, piece, ujson.Obj("kind" -> ujson.Str("settled")))

  // --- non-driver states carry no in-flight session ---

  test("non-driver state → empty (no driver runs in Drafting / PieceAwaitingCi / Refining / terminal)"):
    val log = Vector(spawnAction(1, "claude", "sess-1", Some(P1)))
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
    val log = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

  test("PieceFixingUp + spawn, no monitor outcome → Fixup in-flight session"):
    val log = Vector(spawnAction(5, "claude", "fix-sess", Some(P1)))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceFixingUp(P1, P1Pr, attempt = 1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Fixup, "fix-sess", Some(P1)))
    )

  test("InteractiveSpec + spawn(piece=None), no monitor outcome → Spec in-flight session"):
    val log = Vector(spawnAction(2, "claude", "spec-sess", None))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.InteractiveSpec)),
      Vector(RebuildState.InFlightSession(SessionPhase.Spec, "spec-sess", None))
    )

  test("DesignReviewing + resume(piece=None) → DesignRevision in-flight session uses newSessionId"):
    val log = Vector(
      spawnAction(1, "claude", "spec-sess", None),
      monitorOutcome(2, None),
      resumeAction(3, "claude", "spec-sess", "rev-sess", None)
    )
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.DesignReviewing(round = 2))),
      Vector(RebuildState.InFlightSession(SessionPhase.DesignRevision, "rev-sess", None))
    )

  test("DesignPrFeedback maps to the DesignRevision driver phase"):
    val log = Vector(resumeAction(7, "claude", "rev-old", "rev-new", None))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.DesignPrFeedback(P1Pr, round = 1))),
      Vector(RebuildState.InFlightSession(SessionPhase.DesignRevision, "rev-new", None))
    )

  // --- settle marker present → not in flight (post-settle crash window) ---

  test(
    "PieceImplementing + spawn + monitor outcome → empty (dormant settledAfter branch; synthetic marker — see docstring)"
  ):
    val log = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)), monitorOutcome(6, Some(P1)))
    assertEquals(RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))), Vector.empty)

  test("monitor outcome for a different piece does not close this piece's spawn"):
    val log = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)), monitorOutcome(6, Some(P2)))
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

  test("the last spawn wins — a re-spawn after a settled session is the in-flight one"):
    val log = Vector(
      spawnAction(5, "claude", "impl-sess-1", Some(P1)),
      monitorOutcome(6, Some(P1)),
      spawnAction(7, "claude", "impl-sess-2", Some(P1))
    )
    assertEquals(
      RebuildState.inFlightSessions(log, feature(FsmState.PieceImplementing(P1))),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess-2", Some(P1)))
    )

  test("driver state but no spawn ever logged → empty (state-entry spawn not yet reached)"):
    assertEquals(RebuildState.inFlightSessions(Vector.empty, feature(FsmState.PieceImplementing(P1))), Vector.empty)

  // roadmap §3.5 — producer→consumer link. The FSM now emits the piece `<actor>.spawn` draft (previously absent, so
  // mid-implement crashes silently re-spawned the driver instead of routing to NHI). Feed the *actual* FSM-emitted
  // draft — with the orchestrator's "driver" actor name → kind "driver.spawn" — so a future change to the spawn draft's
  // kind/piece shape that broke restart recovery is caught here, not only by the synthetic-action tests above.
  test("§3.5: an FSM-emitted PieceImplementing spawn draft is detected as an Implement in-flight session"):
    val seed = feature(FsmState.PieceImplementing(P1))
    val (_, drafts) =
      Fsm.transition(seed, FsmEvent.SessionSpawned("driver", "implement", "impl-sess", piece = Some(P1)))
    val log = drafts.zipWithIndex.map { case (d, i) => d.stamp(seq = i.toLong, at = at(i)) }
    assertEquals(
      RebuildState.inFlightSessions(log, seed),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )
