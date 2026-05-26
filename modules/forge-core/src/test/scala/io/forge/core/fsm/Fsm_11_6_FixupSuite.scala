package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** §11.6 — Fix-up. Spawn → PieceFixingUp, settle clean → PieceAwaitingCi, settle bounds → NHI. */
class Fsm_11_6_FixupSuite extends munit.FunSuite:

  test("PieceCiFailed + SessionSpawned(piece=Some(p)) → PieceFixingUp, currentPieceSessionId set"):
    val f = featureIn(
      FsmState.PieceCiFailed(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2))
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("claude", "driver", "fixup-1", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceFixingUp(P1, P1Pr, attempt = 1))
    assertEquals(out.currentPieceSessionId, Some("fixup-1"))
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "fsm.transition")

  test("PieceReviewFailed + SessionSpawned(piece=Some(p)) → PieceFixingUp, currentPieceSessionId set"):
    val f = featureIn(
      FsmState.PieceReviewFailed(P1, P1Pr, attempt = 2),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 2), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("codex", "driver", "fixup-2", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceFixingUp(P1, P1Pr, attempt = 2))
    assertEquals(out.currentPieceSessionId, Some("fixup-2"))

  test("PieceFixingUp + Settled(Fixup, Clean) → PieceAwaitingCi, currentPieceSessionId retained"):
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("fixup-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr))
    assertEquals(out.currentPieceSessionId, Some("fixup-1"), "§6.1 retains session id through awaiting-ci")

  test("PieceFixingUp + SettleTimeout(Fixup) → NHI(RunAnotherFixup)"):
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("fixup-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Fixup, "900s exceeded"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.RunAnotherFixup(p, pr)) =>
        assertEquals(p, P1)
        assertEquals(pr, P1Pr)
      case other => fail(s"expected NHI(RunAnotherFixup), got $other")

  test("PieceFixingUp + Settled(Fixup, AdapterError) → NHI(RunAnotherFixup)"):
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.AdapterError("oom")))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
