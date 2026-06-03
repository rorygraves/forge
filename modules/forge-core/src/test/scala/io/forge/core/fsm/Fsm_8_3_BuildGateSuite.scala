package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** §8.3 / §11.4 (1.8) — pre-PR local Build gate fix-up loop.
  *
  * The dogfood economy: a compile error caught locally before the PR is opened routes to a *driver* fix-up (a
  * `CodeFix`, unlike the deterministic-autofix Format gate), avoiding the full commit → push → PR → CI-fail → fix-up
  * round-trip. The fix-up budget is tracked **in the FSM state** (`PieceBuildFailed`/`PieceBuildFixingUp` carry
  * `attempt`), never in `manifest.attempts` — that budget stays reserved for PR-side CI fix-ups (§11.4).
  */
class Fsm_8_3_BuildGateSuite extends munit.FunSuite:

  test("PieceImplementing + LocalBuildFailed → PieceBuildFailed(attempt=1), no manifest.attempts bump"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.LocalBuildFailed(P1))
    assertEquals(out.state, FsmState.PieceBuildFailed(P1, attempt = 1))
    assertEquals(out.manifest.pieces.find(_.id == P1).map(_.attempts), Some(0), "pre-PR build budget is in-state only")
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "fsm.transition")

  test("PieceBuildFailed + SessionSpawned(piece=Some(p)) → PieceBuildFixingUp, currentPieceSessionId set"):
    val f = featureIn(
      FsmState.PieceBuildFailed(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2))
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("claude", "driver", "buildfix-1", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceBuildFixingUp(P1, attempt = 1))
    assertEquals(out.currentPieceSessionId, Some("buildfix-1"))
    assertEquals(drafts.size, 2)
    assertEquals(drafts.head.kind, "fsm.transition")
    assertEquals(drafts(1).kind, "claude.spawn")
    assertEquals(drafts(1).piece, Some(P1))
    assertEquals(drafts(1).payload("sessionId").str, "buildfix-1")

  test("PieceBuildFixingUp + SessionResumed(piece=Some(p)) → currentPieceSessionId reprojected; driver.resume draft"):
    // D3-3 (roadmap §3.5): the fix-up-phase piece-driver resume seam (symmetric with PieceFixingUp).
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("buildfix-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionResumed("codex", "driver", Some("buildfix-1"), "buildfix-1", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceBuildFixingUp(P1, attempt = 1))
    assertEquals(out.currentPieceSessionId, Some("buildfix-1"))
    assertEquals(drafts.size, 1)
    assertEquals(drafts.head.kind, "codex.resume")

  test("PieceBuildFixingUp + PrOpened → PieceAwaitingCi (re-gate passed), prNumber persisted, session retained"):
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("buildfix-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrOpened(P1, P1Pr))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr))
    assertEquals(out.manifest.pieces.find(_.id == P1).flatMap(_.prNumber), Some(P1Pr))
    assertEquals(out.currentPieceSessionId, Some("buildfix-1"), "§6.1 retains session id through awaiting-ci")

  test("PieceBuildFixingUp + LocalBuildFailed (re-fail) → PieceBuildFailed(attempt+1), within gate"):
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.LocalBuildFailed(P1))
    assertEquals(out.state, FsmState.PieceBuildFailed(P1, attempt = 2))

  test("PieceBuildFixingUp + LocalBuildFailed at maxFixupRounds → NHI(ResolveLocalImplementationChanges)"):
    // maxFixupRounds default = 3, so attempt 3 re-failing exhausts (would-be attempt 4).
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 3),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("buildfix-3")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.LocalBuildFailed(P1))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ResolveLocalImplementationChanges(p, _)) =>
        assertEquals(p, P1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")
    assertEquals(out.currentPieceSessionId, None, "§6.1 clears session id on advancing to NHI")
    assertEquals(out.manifest.pieces.find(_.id == P1).map(_.attempts), Some(0), "exhaustion never touches manifest")

  test("PieceBuildFixingUp + SettleTimeout(Fixup) → NHI(ResolveLocalImplementationChanges)"):
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("buildfix-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Fixup, "900s exceeded"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ResolveLocalImplementationChanges(p, _)) =>
        assertEquals(p, P1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")

  test("PieceBuildFixingUp + Settled(Fixup, AdapterError) → NHI(ResolveLocalImplementationChanges)"):
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.AdapterError("oom")))
    out.state match
      case FsmState.NeedsHumanIntervention(_, _: ResumeHint.ResolveLocalImplementationChanges) => ()
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")

  test("PieceBuildFixingUp + Settled(Fixup, Clean) is an FSM no-op (the orchestrator re-runs the Build gate effect)"):
    // Mirrors PieceImplementing + Settled(Implement, Clean): the post-settle ClassifyCommitOpenPr effect, not a direct
    // FSM arm, drives the re-gate. The FSM must NOT advance on the bare clean settle.
    val f = featureIn(
      FsmState.PieceBuildFixingUp(P1, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, attempts = 0), piecePending(P2, 2)),
      currentPieceSessionId = Some("buildfix-1")
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean))
    assertEquals(out.state, FsmState.PieceBuildFixingUp(P1, attempt = 1))
    assertEquals(drafts, Vector.empty)

  test("LocalBuildFailed for a different piece is ignored"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2))
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.LocalBuildFailed(P2))
    assertEquals(out.state, FsmState.PieceImplementing(P1))
    assertEquals(drafts, Vector.empty)
