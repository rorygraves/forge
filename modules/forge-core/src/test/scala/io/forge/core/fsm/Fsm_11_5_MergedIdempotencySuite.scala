package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.manifest.PieceStatus

/** §11.5 step 1 merge handler — the three idempotency cases (PR-B B4 contract, PR-C C3 suite).
  *
  *   - (a) manifest[p] InProgress → mutate to Merged + transition + drafts
  *   - (b) manifest[p] already Merged with matching fields → no mutation, still transition + drafts (this is the
  *     RebuildState.reconcile case (c) entry point)
  *   - (c) manifest[p] already Merged but disagreeing fields → NHI(AbortOrAbandon) + harness.error
  *     merged_field_mismatch draft
  */
class Fsm_11_5_MergedIdempotencySuite extends munit.FunSuite:

  test("(a) InProgress + Merged → manifest mutated, Refining, two drafts (fsm.transition + audit.piece_merged)"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.Refining(P1, P1Pr, startedAt = ObservedAt))
    val p1 = out.manifest.pieces.find(_.id == P1).get
    assertEquals(p1.status, PieceStatus.Merged)
    assertEquals(p1.prNumber, Some(P1Pr))
    assertEquals(p1.mergeCommit, Some(Sha40Other))
    assertEquals(p1.mergedAt, Some(MergedAt))
    assertEquals(drafts.size, 2)
    assertEquals(drafts(0).kind, "fsm.transition")
    assertEquals(drafts(1).kind, "audit.piece_merged")
    val auditPayload = drafts(1).payload
    assertEquals(auditPayload("p").str, P1.value)
    assertEquals(auditPayload("prNumber").num.toInt, P1Pr.value)

  test("(b) manifest[p] already Merged with matching fields → no mutation, still Refining + drafts"):
    val pre = pieceMerged(P1, 1, P1Pr, Sha40Other, MergedAt)
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pre, piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt)
    )
    assertEquals(out.state, FsmState.Refining(P1, P1Pr, startedAt = ObservedAt))
    assertEquals(out.manifest.pieces.find(_.id == P1).get, pre, "manifest left untouched on idempotent re-apply")
    assertEquals(drafts.size, 2, "still emits fsm.transition + audit.piece_merged drafts")
    assertEquals(drafts(0).kind, "fsm.transition")
    assertEquals(drafts(1).kind, "audit.piece_merged")

  test("(c) manifest[p] already Merged with disagreeing prNumber → NHI(AbortOrAbandon) + harness.error mismatch"):
    val pre = pieceMerged(P1, 1, P2Pr /* wrong */, Sha40Other, MergedAt)
    val f = featureIn(FsmState.PieceAwaitingMerge(P1, P1Pr), pieces = Vector(pre, piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt)
    )
    out.state match
      case FsmState.NeedsHumanIntervention(reason, ResumeHint.AbortOrAbandon) =>
        assert(reason.contains("disagrees"))
      case other => fail(s"expected NHI(AbortOrAbandon), got $other")
    val mismatch = drafts.find(_.kind == "harness.error").getOrElse(fail("expected harness.error draft"))
    assertEquals(mismatch.payload("kind").str, "merged_field_mismatch")
    assertEquals(mismatch.payload("piece").str, P1.value)

  test("(c) manifest[p] already Merged with disagreeing mergeCommit → mismatch path"):
    val pre = pieceMerged(P1, 1, P1Pr, Sha40 /* differs from event Sha40Other */, MergedAt)
    val f = featureIn(FsmState.PieceAwaitingMerge(P1, P1Pr), pieces = Vector(pre, piecePending(P2, 2)))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt)
    )
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assert(drafts.exists(_.kind == "harness.error"))
