package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict}

/** §19 / Slice 2.0 Task 2.0.4 — human-wait markers on `fsm.transition`. Entering one of the four human-blocking states
  * (`DesignNeedsHumanInput` / `DesignAwaitingMerge` / `PieceAwaitingMerge` / `NeedsHumanIntervention`) stamps a `wait`
  * `enter` marker carrying the kind tag; leaving one stamps a `leave` marker. `StatsReport.foldWaits` pairs these to
  * separate wall-clock spent waiting on the operator from Forge working-time (design-2.0 §0 #4).
  *
  * These suites pin the *write* side (the markers land with the right edge + kind); `StatsReportSuite` pins the *read*
  * side (the fold + render).
  */
class FsmWaitMarkerSuite extends munit.FunSuite:

  /** The `wait` object on the (single) `fsm.transition` draft, as `(edge, kind)`; `None` if absent. */
  private def waitMarker(drafts: Vector[io.forge.core.log.ActionDraft]): Option[(String, String)] =
    drafts.find(_.kind == "fsm.transition").flatMap { d =>
      for
        obj <- d.payload.objOpt
        w <- obj.get("wait")
        wObj <- w.objOpt
        edge <- wObj.get("edge").flatMap(_.strOpt)
        kind <- wObj.get("kind").flatMap(_.strOpt)
      yield (edge, kind)
    }

  // --- enter markers (one per human-blocking state) ---

  test("enter DesignNeedsHumanInput → wait enter design-input"):
    val qs = Vector(Question("Is X correct?", Vector("yes", "no"), allowFreeText = false, QuestionSeverity.Blocking))
    val f = featureIn(FsmState.DesignReviewing(round = 2), designSessionId = Some("sess-1"))
    val (_, drafts) =
      Fsm.transition(f, FsmEvent.DesignReviewReceived(round = 2, verdict = DesignReviewVerdict.BlockingQuestions(qs)))
    assertEquals(waitMarker(drafts), Some(("enter", "design-input")))

  test("enter DesignAwaitingMerge → wait enter design-merge"):
    val f = featureIn(FsmState.DesignReviewing(round = 1), designSessionId = Some("sess-1"))
    val (_, drafts) =
      Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(emptySnapshot(DesignPr, io.forge.core.pr.PrState.Open)))
    assertEquals(waitMarker(drafts), Some(("enter", "design-merge")))

  test("enter PieceAwaitingMerge → wait enter piece-merge"):
    val f = featureIn(
      FsmState.PieceAwaitingReview(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (_, drafts) = Fsm.transition(f, FsmEvent.CodeReviewVerdict(P1, PrReviewVerdict.Approve))
    assertEquals(waitMarker(drafts), Some(("enter", "piece-merge")))

  test("enter NeedsHumanIntervention → wait enter intervention"):
    val f = featureIn(FsmState.InteractiveSpec, designSessionId = Some("sess-1"))
    val (out, drafts) = Fsm.transition(f, FsmEvent.SettleTimeout(SessionPhase.Spec, "bound exceeded"))
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention])
    assertEquals(waitMarker(drafts), Some(("enter", "intervention")))

  // --- leave markers (transition out of a human-blocking state into a working state) ---

  test("leave DesignNeedsHumanInput (clarified) → wait leave design-input"):
    val qs = Vector(Question("?", Vector.empty, allowFreeText = true, QuestionSeverity.Blocking))
    val f = featureIn(FsmState.DesignNeedsHumanInput(round = 2, questions = qs), designSessionId = Some("sess-1"))
    val (_, drafts) = Fsm.transition(f, FsmEvent.DesignReviewClarified(round = 2))
    assertEquals(waitMarker(drafts), Some(("leave", "design-input")))

  test("leave DesignAwaitingMerge (merged) → wait leave design-merge"):
    val f =
      featureIn(FsmState.DesignAwaitingMerge(DesignPr), designSessionId = Some("sess-1"), designPr = Some(DesignPr))
    val (_, drafts) = Fsm.transition(f, FsmEvent.DesignPrSnapshotUpdated(mergedSnapshot(DesignPr)))
    assertEquals(waitMarker(drafts), Some(("leave", "design-merge")))

  test("leave PieceAwaitingMerge (Merged) → wait leave piece-merge"):
    val f = featureIn(
      FsmState.PieceAwaitingMerge(P1, P1Pr),
      pieces = Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr)), piecePending(P2, 2)),
      currentPieceSessionId = Some("impl-1")
    )
    val (_, drafts) = Fsm.transition(f, FsmEvent.Merged(P1, P1Pr, Sha40Other, MergedAt, ObservedAt))
    assertEquals(waitMarker(drafts), Some(("leave", "piece-merge")))

  // --- non-wait transitions carry no marker ---

  test("a working→working transition (Drafting → InteractiveSpec) has no wait marker"):
    val f = featureIn(FsmState.Drafting)
    val (_, drafts) = Fsm.transition(f, FsmEvent.SessionSpawned("claude", "driver", "sess-1", None))
    assertEquals(waitMarker(drafts), None)
    // the `from`/`to` fields are still present — only `wait` is omitted
    assertEquals(drafts.head.payload("from").str, "Drafting")
    assertEquals(drafts.head.payload("to").str, "InteractiveSpec")
