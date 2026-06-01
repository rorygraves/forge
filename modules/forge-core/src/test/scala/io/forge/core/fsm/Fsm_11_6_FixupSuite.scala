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
    // roadmap §3.5: the fix-up spawn now emits the §19 `<actor>.spawn` durability entry alongside the transition.
    assertEquals(drafts.size, 2)
    assertEquals(drafts.head.kind, "fsm.transition")
    assertEquals(drafts(1).kind, "claude.spawn")
    assertEquals(drafts(1).piece, Some(P1))
    assertEquals(drafts(1).payload("sessionId").str, "fixup-1")

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

  test("PieceFixingUp + SessionResumed(piece=Some(p)) → currentPieceSessionId reprojected; driver.resume draft"):
    // D3-3 (roadmap §3.5): the fix-up-phase piece-driver resume seam (symmetric with PieceImplementing).
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("fixup-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionResumed(
        "codex",
        "driver",
        oldSessionId = Some("fixup-1"),
        newSessionId = "fixup-1",
        piece = Some(P1)
      )
    )
    assertEquals(out.state, FsmState.PieceFixingUp(P1, P1Pr, attempt = 1))
    assertEquals(out.currentPieceSessionId, Some("fixup-1"))
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "codex.resume")
    assertEquals(drafts.head.piece, Some(P1))

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
