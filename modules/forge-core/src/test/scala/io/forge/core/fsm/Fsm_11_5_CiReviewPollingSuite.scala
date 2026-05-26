package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.review.PrReviewVerdict

/** §11.5 — CI & review polling. CI ready/failed + attempts gate, reviewer verdicts, human override, merge. */
class Fsm_11_5_CiReviewPollingSuite extends munit.FunSuite:

  // --- PieceAwaitingCi ---

  test("PieceAwaitingCi + PrSnapshotUpdated(all required Success) → PieceAwaitingReview"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, successfulCi(P1Pr)))
    assertEquals(out.state, FsmState.PieceAwaitingReview(P1, P1Pr))
    assertEquals(out.currentPieceSessionId, Some("impl-1"))

  test("PieceAwaitingCi + PrSnapshotUpdated(required Failure) → PieceCiFailed(attempt=1), attempts+=1"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, failedCi(P1Pr)))
    assertEquals(out.state, FsmState.PieceCiFailed(P1, P1Pr, attempt = 1))
    assertEquals(out.manifest.pieces.find(_.id == P1).get.attempts, 1)

  test("PieceAwaitingCi + PrSnapshotUpdated(Failure) at maxFixupRounds → NHI(RunAnotherFixup)"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, failedCi(P1Pr)))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.RunAnotherFixup(p, pr)) =>
        assertEquals(p, P1)
        assertEquals(pr, P1Pr)
      case other => fail(s"expected NHI(RunAnotherFixup), got $other")

  test("PieceAwaitingCi + CheckDiscoveryComplete → no-op (informational)"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, drafts) = Fsm.transition(f, FsmEvent.CheckDiscoveryComplete(P1, P1Pr))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr))
    assertEquals(drafts, Vector.empty)

  test("PieceAwaitingCi + PrSnapshotUpdated(empty rollup) → no-op (CI still pending)"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, drafts) =
      Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, emptySnapshot(P1Pr, io.forge.core.pr.PrState.Open)))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr))
    assertEquals(drafts, Vector.empty)

  // --- PieceAwaitingReview ---

  test("PieceAwaitingReview + CodeReviewVerdict(Approve) → PieceAwaitingMerge"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.CodeReviewVerdict(P1, PrReviewVerdict.Approve))
    assertEquals(out.state, FsmState.PieceAwaitingMerge(P1, P1Pr))

  test("PieceAwaitingReview + CodeReviewVerdict(RequestChanges) → PieceReviewFailed(attempt=1)"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.CodeReviewVerdict(P1, PrReviewVerdict.RequestChanges(Vector("rename foo")))
    )
    assertEquals(out.state, FsmState.PieceReviewFailed(P1, P1Pr, attempt = 1))
    assertEquals(out.manifest.pieces.find(_.id == P1).get.attempts, 1)

  test("PieceAwaitingReview + PrSnapshotUpdated(human CHANGES_REQUESTED) → PieceReviewFailed (human override)"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, changesRequestedSnapshot(P1Pr)))
    assertEquals(out.state, FsmState.PieceReviewFailed(P1, P1Pr, attempt = 1))

  test("PieceAwaitingReview + PrSnapshotUpdated(human comment) → PieceReviewFailed (human override)"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, humanCommentSnapshot(P1Pr)))
    assertEquals(out.state, FsmState.PieceReviewFailed(P1, P1Pr, attempt = 1))

  // --- PieceAwaitingMerge ---

  test("PieceAwaitingMerge + PrSnapshotUpdated(Closed) → NHI(RunAnotherFixup)"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, closedSnapshot(P1Pr)))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.RunAnotherFixup(p, pr)) =>
        assertEquals(p, P1)
        assertEquals(pr, P1Pr)
      case other => fail(s"expected NHI(RunAnotherFixup), got $other")

  test("PieceAwaitingMerge + PrSnapshotUpdated(human override) → PieceReviewFailed (attempts+=1)"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, changesRequestedSnapshot(P1Pr)))
    assertEquals(out.state, FsmState.PieceReviewFailed(P1, P1Pr, attempt = 1))
