package io.forge.core.fsm

import io.forge.core.fsm.FsmFixtures.*

/** §11.3 — Design PR gate. Merged, new comment / CHANGES_REQUESTED, closed-without-merge, feedback settle. */
class Fsm_11_3_DesignPrGateSuite extends munit.FunSuite:

  test("DesignAwaitingMerge + merged snapshot → DesignReady, designSessionId cleared"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(mergedSnapshot(DesignPr)))
    assertEquals(out.state, FsmState.DesignReady)
    assertEquals(out.designSessionId, None, "designSessionId cleared on entering DesignReady (§6.1)")

  test("DesignAwaitingMerge + closed snapshot → NHI(ReopenDesign(Some(prNumber)))"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(closedSnapshot(DesignPr)))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ReopenDesign(Some(pr))) =>
        assertEquals(pr, DesignPr)
      case other => fail(s"expected NHI(ReopenDesign(Some(DesignPr))), got $other")

  test("DesignAwaitingMerge + ChangesRequested snapshot → DesignPrFeedback(prNumber, 1)"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(changesRequestedSnapshot(DesignPr)))
    assertEquals(out.state, FsmState.DesignPrFeedback(DesignPr, round = 1))

  test("DesignAwaitingMerge + human comment snapshot → DesignPrFeedback(prNumber, 1)"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(humanCommentSnapshot(DesignPr)))
    assertEquals(out.state, FsmState.DesignPrFeedback(DesignPr, round = 1))

  test("DesignAwaitingMerge + feedback without session id → NHI(ReopenDesign(Some(prNumber)))"):
    val f = featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = None, designPr = Some(DesignPr))
    val (out, _) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(changesRequestedSnapshot(DesignPr)))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ReopenDesign(Some(pr))) =>
        assertEquals(pr, DesignPr)
      case other => fail(s"expected NHI(ReopenDesign(Some(DesignPr))), got $other")

  test("DesignAwaitingMerge + open snapshot (no feedback) → no-op"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (out, drafts) =
      Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(emptySnapshot(DesignPr, io.forge.core.pr.PrState.Open)))
    assertEquals(out.state, FsmState.DesignAwaitingMerge(DesignPr))
    assertEquals(drafts, Vector.empty)

  test("DesignPrFeedback + Settled(DesignRevision, Clean) → DesignAwaitingMerge"):
    val f = featureIn(
      FsmState.DesignPrFeedback(DesignPr, round = 1),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    val (out, _) = Fsm.transition(f, FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean))
    assertEquals(out.state, FsmState.DesignAwaitingMerge(DesignPr))

  test("DesignPrFeedback + SessionResumed → designSessionId updated"):
    val f = featureIn(FsmState.DesignPrFeedback(DesignPr, round = 1), designSessionId = Some("old"))
    val (out, drafts) = Fsm.transition(
      f,
      FsmEvent.SessionResumed("codex", "driver", "old", "new", piece = None)
    )
    assertEquals(out.state, FsmState.DesignPrFeedback(DesignPr, round = 1))
    assertEquals(out.designSessionId, Some("new"))
    assertEquals(drafts, Vector.empty)

  test("DesignPrFeedback + SettleTimeout(DesignRevision) → NHI(ReopenDesign(Some(prNumber)))"):
    val f = featureIn(
      FsmState.DesignPrFeedback(DesignPr, round = 1),
      designSessionId = Some("sess-1"),
      designPr = Some(DesignPr)
    )
    val (out, _) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.DesignRevision, "bound exceeded"))
    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ReopenDesign(Some(pr))) =>
        assertEquals(pr, DesignPr)
      case other => fail(s"expected NHI(ReopenDesign(Some(DesignPr))), got $other")
