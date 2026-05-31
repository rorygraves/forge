package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** PR-C review-round fixes — three reviewer findings tightened.
  *
  *   - **High**: `Resume(RunAnotherFixup)` parks at `PieceCiFailed` (not `PieceFixingUp`) so the orchestrator's
  *     `runFixup → SessionSpawned` drives the fresh `PieceFixingUp` transition with the new session id. The defensive
  *     `PieceFixingUp + SessionSpawned` handler also refreshes a late session id.
  *   - **Medium**: design PR feedback rounds monotonically increase across `DesignAwaitingMerge ↔ DesignPrFeedback`
  *     cycles instead of restarting at 1.
  *   - **Medium**: snapshot/merge handlers guard against PR-number mismatch. Snapshot mismatches silently no-op (stale
  *     poll); a `Merged` mismatch routes to `NHI(AbortOrAbandon)` + `harness.error pr_number_mismatch`.
  */
class FsmReviewFixesSuite extends munit.FunSuite:

  // ---------------------------------------------------------------------------
  // High: Resume(RunAnotherFixup) + PieceFixingUp.SessionSpawned defensive handler
  // ---------------------------------------------------------------------------

  test("Resume(RunAnotherFixup) lands at PieceCiFailed and clears stale currentPieceSessionId"):
    val f = featureIn(
      FsmState.NeedsHumanIntervention("ci exhausted", ResumeHint.RunAnotherFixup(P1, P1Pr)),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2)),
      currentPieceSessionId = Some("stale-impl")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.UserCommandReceived(UserCommand.Resume(ResumeHint.RunAnotherFixup(P1, P1Pr)))
    )
    assertEquals(out.state, FsmState.PieceCiFailed(P1, P1Pr, attempt = 3))
    assertEquals(out.currentPieceSessionId, None)
    assertEquals(out.branchProtectionCacheEpoch, 1L)
    assert(drafts.exists(_.kind == "fsm.transition"))

  test("after Resume(RunAnotherFixup), SessionSpawned drives PieceCiFailed → PieceFixingUp with new session id"):
    val resumed = featureIn(
      FsmState.PieceCiFailed(P1, P1Pr, attempt = 3),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2)),
      currentPieceSessionId = None
    )
    val (out, _) = Fsm.transition(
      resumed,
      FsmEvent.SessionSpawned("claude", "driver", "fixup-fresh", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceFixingUp(P1, P1Pr, attempt = 3))
    assertEquals(out.currentPieceSessionId, Some("fixup-fresh"))

  test("PieceFixingUp + SessionSpawned (defensive late spawn) refreshes currentPieceSessionId without state change"):
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 2),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 2), piecePending(P2, 2)),
      currentPieceSessionId = Some("fixup-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionSpawned("claude", "driver", "fixup-2", piece = Some(P1))
    )
    assertEquals(out.state, FsmState.PieceFixingUp(P1, P1Pr, attempt = 2))
    assertEquals(out.currentPieceSessionId, Some("fixup-2"))
    assertEquals(drafts, Vector.empty)

  // ---------------------------------------------------------------------------
  // Medium: design PR feedback round is monotonic across cycles
  // ---------------------------------------------------------------------------

  test("first DesignPrFeedback entry uses round = 1 when Feature.designPrFeedbackRound = 0"):
    val f = featureIn(
      FsmState.DesignAwaitingMerge(DesignPr),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(changesRequestedSnapshot(DesignPr)))
    assertEquals(out.state, FsmState.DesignPrFeedback(DesignPr, round = 1))

  test("Settled back to DesignAwaitingMerge persists round; next entry uses round + 1"):
    val f = featureIn(
      FsmState.DesignPrFeedback(DesignPr, round = 1),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr),
      designPrFeedbackRound = 0
    )
    val (after1, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
    assertEquals(after1.state, FsmState.DesignAwaitingMerge(DesignPr))
    assertEquals(after1.designPrFeedbackRound, 1, "round persisted on settle back to awaiting-merge")

    val (after2, _) = Fsm.transition(after1, FsmEvent.DesignPrSnapshotUpdated(humanCommentSnapshot(DesignPr)))
    assertEquals(after2.state, FsmState.DesignPrFeedback(DesignPr, round = 2))

  test("two full cycles produce rounds 1 → 2 → 3, never reusing audit/snapshot suffixes"):
    var f = featureIn(
      FsmState.DesignAwaitingMerge(DesignPr),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    // cycle 1
    val (a, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(changesRequestedSnapshot(DesignPr)))
    assertEquals(a.state, FsmState.DesignPrFeedback(DesignPr, round = 1))
    val (b, _) = Fsm.transition(a, FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
    // cycle 2
    val (c, _) = Fsm.transition(b, FsmEvent.DesignPrSnapshotUpdated(humanCommentSnapshot(DesignPr)))
    assertEquals(c.state, FsmState.DesignPrFeedback(DesignPr, round = 2))
    val (d, _) = Fsm.transition(c, FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
    // cycle 3
    val (e, _) = Fsm.transition(d, FsmEvent.DesignPrSnapshotUpdated(changesRequestedSnapshot(DesignPr)))
    assertEquals(e.state, FsmState.DesignPrFeedback(DesignPr, round = 3))
    f = e // appease the linter

  test("merge clears designPrFeedbackRound (DesignReady resets)"):
    val f = featureIn(
      FsmState.DesignAwaitingMerge(DesignPr),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr),
      designPrFeedbackRound = 3
    )
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(mergedSnapshot(DesignPr)))
    assertEquals(out.state, FsmState.DesignReady)
    assertEquals(out.designPrFeedbackRound, 0)

  test("Resume(ReopenDesign) resets designPrFeedbackRound to 0"):
    val f = featureIn(
      FsmState.NeedsHumanIntervention("x", ResumeHint.ReopenDesign(Some(DesignPr))),
      designPrFeedbackRound = 4,
      designPr = Some(DesignPr)
    )
    val (out, _) = Fsm.transition(
      f,
      FsmEvent.UserCommandReceived(UserCommand.Resume(ResumeHint.ReopenDesign(Some(DesignPr))))
    )
    assertEquals(out.state, FsmState.DesignReviewing(round = 1))
    assertEquals(out.designPrFeedbackRound, 0)

  // ---------------------------------------------------------------------------
  // Medium: PR number guards
  // ---------------------------------------------------------------------------

  test("DesignAwaitingMerge ignores DesignPrSnapshotUpdated for a different prNumber (stale poll)"):
    val f = featureIn(
      FsmState.DesignAwaitingMerge(DesignPr),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (out, drafts) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(mergedSnapshot(wrongPr)))
    assertEquals(out.state, FsmState.DesignAwaitingMerge(DesignPr), "state unchanged on PR-number mismatch")
    assertEquals(out.designSessionId, Some("sess-1"), "designSessionId not cleared")
    assertEquals(drafts, Vector.empty)

  test("PieceAwaitingCi ignores PrSnapshotUpdated whose snapshot.number != state.prNumber"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (out, drafts) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, successfulCi(wrongPr)))
    assertEquals(out.state, FsmState.PieceAwaitingCi(P1, P1Pr), "no CI-ready transition on stale snapshot")
    assertEquals(drafts, Vector.empty)

  test("PieceAwaitingReview ignores stale human-override snapshot"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (out, drafts) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, changesRequestedSnapshot(wrongPr)))
    assertEquals(out.state, FsmState.PieceAwaitingReview(P1, P1Pr))
    assertEquals(out.manifest.pieces.find(_.id == P1).get.attempts, 0, "attempts not bumped on stale snapshot")
    assertEquals(drafts, Vector.empty)

  test("PieceAwaitingMerge ignores stale snapshot (Closed/human-override)"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (closedOut, closedDrafts) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, closedSnapshot(wrongPr)))
    assertEquals(closedOut.state, FsmState.PieceAwaitingMerge(P1, P1Pr))
    assertEquals(closedDrafts, Vector.empty)

    val (humanOut, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, changesRequestedSnapshot(wrongPr)))
    assertEquals(humanOut.state, FsmState.PieceAwaitingMerge(P1, P1Pr))

  // ---------------------------------------------------------------------------
  // §6.1: every NHI transition from a piece state clears currentPieceSessionId.
  //
  // The reviewer's third Medium finding: toNeedsHumanIntervention was preserving the stale piece-side projection on
  // most failure paths. The fix enforces the invariant in the helper plus the two direct-construction sites
  // (handleMerged mismatch, prNumberMismatch). One test per piece-phase NHI entry that the FSM can actually drive.
  // ---------------------------------------------------------------------------

  test("PieceImplementing + SettleTimeout → NHI clears currentPieceSessionId (§6.1)"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Implement, "1800s"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceImplementing + HarnessError → NHI clears currentPieceSessionId"):
    val f = featureIn(
      FsmState.PieceImplementing(P1),
      pieces = Vector(pieceInProgress(P1, 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.HarnessError("change_collector_denied"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceAwaitingCi exhausted → NHI clears currentPieceSessionId"):
    val f = featureIn(
      FsmState.PieceAwaitingCi(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, failedCi(P1Pr)))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceAwaitingMerge + PR closed → NHI clears currentPieceSessionId"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.PrSnapshotUpdated(P1, closedSnapshot(P1Pr)))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceFixingUp + SettleTimeout → NHI clears currentPieceSessionId"):
    val f = featureIn(
      FsmState.PieceFixingUp(P1, P1Pr, attempt = 1),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 1), piecePending(P2, 2)),
      currentPieceSessionId = Some("fixup-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Fixup, "900s"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceAwaitingMerge + Merged field mismatch (handleMerged direct construction) clears currentPieceSessionId"):
    val pre = pieceMerged(P1, 1, P2Pr /* wrong */, Sha40Other, MergedAt)
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pre, piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, _) = Fsm.transition(f, FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("PieceAwaitingMerge + Merged prNumber mismatch (prNumberMismatch helper) clears currentPieceSessionId"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (out, _) = Fsm.transition(f, FsmEvent.Merged(P1, wrongPr, Sha40Other, MergedAt, ObservedAt))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)

  test("design-phase NHI is a no-op for currentPieceSessionId (already None)"):
    val f = featureIn(
      FsmState.DesignReviewing(round = 1),
      designSessionId = Some("sess-1"),
      currentPieceSessionId = None
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.DesignRevision, "x"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(out.currentPieceSessionId, None)
    assertEquals(
      out.designSessionId,
      Some("sess-1"),
      "designSessionId NOT cleared on NHI — §6.1 ties its clear to entering DesignReady"
    )

  test("Merged with mismatched prNumber → NHI(AbortOrAbandon) + harness.error pr_number_mismatch"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2))
    )
    val wrongPr = io.forge.core.PrNumber(9999)
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.Merged(P1, wrongPr, Sha40Other, MergedAt, ObservedAt)
    )
    out.state match
      case FsmState.NeedsHumanIntervention(reason, ResumeHint.AbortOrAbandon) =>
        assert(reason.contains("PR number mismatch"), s"reason was: $reason")
      case other => fail(s"expected NHI(AbortOrAbandon), got $other")
    // Manifest piece is NOT mutated to the wrong prNumber.
    val p1After = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1After.prNumber, Some(P1Pr), "manifest prNumber unchanged on mismatch")
    val mismatch = drafts.find(_.kind == "harness.error").getOrElse(fail("expected harness.error draft"))
    assertEquals(mismatch.payload("kind").str, "pr_number_mismatch")
    assertEquals(mismatch.payload("event").str, "Merged")
    assertEquals(mismatch.payload("expectedPr").num.toInt, P1Pr.value)
    assertEquals(mismatch.payload("observedPr").num.toInt, wrongPr.value)

  // ---------------------------------------------------------------------------
  // M7: refresh-cache bumps branchProtectionCacheEpoch without a state change (§15)
  // ---------------------------------------------------------------------------

  test("M7 — refresh-cache bumps branchProtectionCacheEpoch without a state change (§15)"):
    // `forge refresh-cache` (UserCommand.RefreshCache) is the manual cache-invalidation command: it bumps the epoch
    // exactly like resume's §8.1 bump, but must stay in the current lifecycle state and emit no drafts.
    val f = featureIn(FsmState.DesignReviewing(1), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, drafts) = Fsm.transition(f, FsmEvent.UserCommandReceived(UserCommand.RefreshCache))
    assertEquals(out.branchProtectionCacheEpoch, f.branchProtectionCacheEpoch + 1)
    assertEquals(out.state, f.state, "refresh-cache must not change the lifecycle state")
    assertEquals(drafts, Vector.empty, "refresh-cache emits no drafts")
