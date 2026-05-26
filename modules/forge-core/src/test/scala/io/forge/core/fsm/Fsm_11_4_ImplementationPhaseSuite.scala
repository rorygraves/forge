package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.manifest.PieceStatus

/** §11.4 — Implementation phase. Branch creation (manifest mutation), driver spawn (sessionId projection), PR open. */
class Fsm_11_4_ImplementationPhaseSuite extends munit.FunSuite:

  test("DesignReady + BranchCreated(p, _, baseSha) → PieceImplementing(p), manifest[p] = {InProgress, baseSha}"):
    val f = featureIn(FsmState.DesignReady, designPr = Some(DesignPr))
    val (out, drafts) = Fsm.transition(f, FsmEvent.BranchCreated(P1, P1Branch, Sha40))
    assertEquals(out.state, FsmState.PieceImplementing(P1))
    val p1 = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1.status, PieceStatus.InProgress)
    assertEquals(p1.baseSha, Some(Sha40))
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "fsm.transition")
    assertEquals(drafts.head.piece, Some(P1))

  test("PieceImplementing(p) + SessionSpawned(piece=Some(p)) → currentPieceSessionId set; no state change"):
    val f = featureIn(FsmState.PieceImplementing(P1), pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("claude", "driver", "impl-1", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceImplementing(P1))
    assertEquals(out.currentPieceSessionId, Some("impl-1"))
    assertEquals(drafts, Vector.empty)

  test("PieceImplementing(p) + PrOpened(p, prNumber) → PieceAwaitingCi, manifest[p].prNumber set"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.PrOpened(P1, P1Pr))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr))
    val p1 = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1.prNumber, Some(P1Pr))
    assertEquals(out.currentPieceSessionId, Some("impl-1"), "session id retained through §6.1")
    assertEquals(drafts.size, 1)

  test("PieceImplementing(p) + SettleTimeout(Implement) → NHI(ResolveLocalImplementationChanges)"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Implement, "1800s exceeded"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ResolveLocalImplementationChanges(p, _)) =>
        assertEquals(p, P1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")

  test("PieceImplementing(p) + HarnessError → NHI(ResolveLocalImplementationChanges) (cross-cutting)"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.HarnessError("change_collector_denied: lock-file outside scope"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ResolveLocalImplementationChanges(p, _)) =>
        assertEquals(p, P1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")

  test("PieceImplementing(p) + BranchCreated(p, _, baseSha) idempotent — re-asserts manifest, no state change"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1, baseSha = Sha40Other), piecePending(P2, 2))
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.BranchCreated(P1, P1Branch, Sha40))
    assertEquals(out.state, FsmState.PieceImplementing(P1))
    val p1 = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1.baseSha, Some(Sha40), "baseSha refreshed to event value")
    assertEquals(drafts, Vector.empty, "no FSM transition draft; state unchanged")
