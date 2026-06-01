package io.forge.core.state

import io.forge.core.*
import io.forge.core.fsm.{Feature, Fsm, FsmEvent, FsmFixtures, FsmState, SessionPhase}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.Action

/** Slice 1.4b Task 1.4.10 / carry-forward **S4-4** — pins the pure [[RebuildState.inFlightSessions]] /
  * [[RebuildState.settledButUnadvanced]] projections that the orchestrator's restart + post-settle recovery consume.
  *
  * The projections answer a single question against a replayed log + the post-reconcile feature: "what is the driver
  * session at the log tail doing?" A `<actor>.spawn` / `<actor>.resume` for the current state's driver phase that is
  * NOT followed by a [[RebuildState.MonitorOutcomeKind]] marker (same piece key) is **in flight** → NHI; a spawn whose
  * monitor outcome IS logged (the post-settle crash window — e.g. `Settled(Implement, Clean)` is an FSM no-op until
  * `PrOpened`) is **settled-but-unadvanced** → post-settle recovery. The two are mutually exclusive over the same tail
  * spawn.
  *
  * roadmap §3.5 Unit B: the orchestrator now writes `monitor.outcome` for the piece-driver settles (`Implement` /
  * `Fixup`), so the `settledButUnadvanced` cases below are the projection the live post-settle recovery consumes; the
  * design-phase settles do not write the marker, so those tail spawns stay in-flight → NHI by construction.
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

  // --- settle marker present → not in flight, but settled-but-unadvanced (post-settle crash window) ---

  test("PieceImplementing + spawn + monitor outcome → not in-flight, settled-but-unadvanced (Implement)"):
    val log = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)), monitorOutcome(6, Some(P1)))
    val state = FsmState.PieceImplementing(P1)
    assertEquals(RebuildState.inFlightSessions(log, feature(state)), Vector.empty)
    assertEquals(
      RebuildState.settledButUnadvanced(log, feature(state)),
      Vector(RebuildState.SettledSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

  test("PieceFixingUp + spawn + monitor outcome → settled-but-unadvanced (Fixup)"):
    val log = Vector(spawnAction(5, "claude", "fix-sess", Some(P1)), monitorOutcome(6, Some(P1)))
    val state = FsmState.PieceFixingUp(P1, P1Pr, attempt = 1)
    assertEquals(RebuildState.inFlightSessions(log, feature(state)), Vector.empty)
    assertEquals(
      RebuildState.settledButUnadvanced(log, feature(state)),
      Vector(RebuildState.SettledSession(SessionPhase.Fixup, "fix-sess", Some(P1)))
    )

  test("monitor outcome for a different piece does not close this piece's spawn"):
    val log = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)), monitorOutcome(6, Some(P2)))
    val state = FsmState.PieceImplementing(P1)
    assertEquals(
      RebuildState.inFlightSessions(log, feature(state)),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )
    assertEquals(RebuildState.settledButUnadvanced(log, feature(state)), Vector.empty)

  test("the last spawn wins — a re-spawn after a settled session is the in-flight one (not settled)"):
    val log = Vector(
      spawnAction(5, "claude", "impl-sess-1", Some(P1)),
      monitorOutcome(6, Some(P1)),
      spawnAction(7, "claude", "impl-sess-2", Some(P1))
    )
    val state = FsmState.PieceImplementing(P1)
    assertEquals(
      RebuildState.inFlightSessions(log, feature(state)),
      Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-sess-2", Some(P1)))
    )
    assertEquals(RebuildState.settledButUnadvanced(log, feature(state)), Vector.empty)

  test("a re-spawn whose own settle is logged → settled-but-unadvanced on the second session"):
    val log = Vector(
      spawnAction(5, "claude", "impl-sess-1", Some(P1)),
      monitorOutcome(6, Some(P1)),
      spawnAction(7, "claude", "impl-sess-2", Some(P1)),
      monitorOutcome(8, Some(P1))
    )
    val state = FsmState.PieceImplementing(P1)
    assertEquals(RebuildState.inFlightSessions(log, feature(state)), Vector.empty)
    assertEquals(
      RebuildState.settledButUnadvanced(log, feature(state)),
      Vector(RebuildState.SettledSession(SessionPhase.Implement, "impl-sess-2", Some(P1)))
    )

  test("non-driver / no-spawn states carry neither projection"):
    // a settled marker under a non-driver state (the happy path: state advanced past the settle) projects nothing.
    val advanced = Vector(spawnAction(5, "claude", "impl-sess", Some(P1)), monitorOutcome(6, Some(P1)))
    assertEquals(RebuildState.settledButUnadvanced(advanced, feature(FsmState.PieceAwaitingCi(P1, P1Pr))), Vector.empty)
    assertEquals(RebuildState.settledButUnadvanced(Vector.empty, feature(FsmState.PieceImplementing(P1))), Vector.empty)
    assertEquals(RebuildState.inFlightSessions(Vector.empty, feature(FsmState.PieceImplementing(P1))), Vector.empty)

  // roadmap §3.5 Unit B — producer→consumer link. Feed the *actual* orchestrator-emitted spawn draft (kind
  // "driver.spawn") plus the orchestrator's `monitor.outcome` marker shape so a future change to either draft's
  // kind/piece that broke post-settle recovery is caught here, not only by the synthetic-action tests above.
  test("§3.5: an FSM-emitted spawn followed by a monitor.outcome marker → settled-but-unadvanced"):
    val seed = feature(FsmState.PieceImplementing(P1))
    val (_, spawnDrafts) =
      Fsm.transition(seed, FsmEvent.SessionSpawned("driver", "implement", "impl-sess", piece = Some(P1)))
    val marker = io.forge.core.log.ActionDraft(
      feature = FeatureA,
      piece = Some(P1),
      actor = Some("driver"),
      role = Some("implement"),
      kind = RebuildState.MonitorOutcomeKind,
      payload =
        ujson.Obj("kind" -> ujson.Str("settled"), "phase" -> ujson.Str("Implement"), "settle" -> ujson.Str("clean"))
    )
    val log = (spawnDrafts :+ marker).zipWithIndex.map { case (d, i) => d.stamp(seq = i.toLong, at = at(i)) }
    assertEquals(RebuildState.inFlightSessions(log, seed), Vector.empty)
    assertEquals(
      RebuildState.settledButUnadvanced(log, seed),
      Vector(RebuildState.SettledSession(SessionPhase.Implement, "impl-sess", Some(P1)))
    )

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
