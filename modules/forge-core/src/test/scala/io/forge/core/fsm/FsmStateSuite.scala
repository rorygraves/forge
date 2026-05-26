package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.manifest.{ManifestPatch, ManifestPatchOp}

import java.time.Instant
import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for `FsmState` and `ResumeHint`. One representative value per case. Transition rules are
  * PR-C's responsibility; this suite only proves the wire shape is stable.
  */
class FsmStateSuite extends munit.FunSuite:

  private def roundTrip[A: upickle.default.ReadWriter](a: A): Unit =
    val json = write(a)
    val parsed = read[A](json)
    assertEquals(parsed, a, s"round-trip failed for: $json")

  private val p1 = PieceId("p1")
  private val prNumber = PrNumber(4291)
  private val branch = BranchName("forge/feat/p1")

  // --- ResumeHint ---

  test("ResumeHint — every variant round-trips"):
    val patch = ManifestPatch("reason", Vector.empty)
    Seq(
      ResumeHint.ResumeAfterHumanPush(p1, prNumber),
      ResumeHint.CommitAndPushHumanFix(p1, prNumber),
      ResumeHint.RunAnotherFixup(p1, prNumber),
      ResumeHint.ResolveLocalImplementationChanges(p1, branch),
      ResumeHint.ReopenDesign(Some(PrNumber(99))),
      ResumeHint.ReopenDesign(None),
      ResumeHint.ApplyPlanningUpdate(patch),
      ResumeHint.AbortOrAbandon
    ).foreach(roundTrip(_))

  // --- FsmState ---

  test("FsmState spec phase — every variant round-trips"):
    roundTrip[FsmState](FsmState.Drafting)
    roundTrip[FsmState](FsmState.InteractiveSpec)
    roundTrip[FsmState](FsmState.DesignReviewing(1))
    roundTrip[FsmState](
      FsmState.DesignNeedsHumanInput(
        round = 2,
        questions = Vector(
          Question("Are we sure?", Vector("yes", "no"), allowFreeText = false, QuestionSeverity.Blocking)
        )
      )
    )
    roundTrip[FsmState](FsmState.DesignAwaitingMerge(prNumber))
    roundTrip[FsmState](FsmState.DesignPrFeedback(prNumber, round = 1))
    roundTrip[FsmState](FsmState.DesignReady)

  test("FsmState implementation phase — every variant round-trips"):
    roundTrip[FsmState](FsmState.PieceImplementing(p1))
    roundTrip[FsmState](FsmState.PieceAwaitingCi(p1, prNumber))
    roundTrip[FsmState](FsmState.PieceAwaitingReview(p1, prNumber))
    roundTrip[FsmState](FsmState.PieceCiFailed(p1, prNumber, attempt = 1))
    roundTrip[FsmState](FsmState.PieceReviewFailed(p1, prNumber, attempt = 2))
    roundTrip[FsmState](FsmState.PieceFixingUp(p1, prNumber, attempt = 3))
    roundTrip[FsmState](FsmState.PieceAwaitingMerge(p1, prNumber))
    roundTrip[FsmState](FsmState.Refining(p1, prNumber, startedAt = Instant.parse("2026-05-26T12:00:00Z")))
    roundTrip[FsmState](
      FsmState.PlanningUpdate(
        reason = "p3 needs to come first",
        patch = ManifestPatch("reorder", Vector(ManifestPatchOp.ReorderPieces(Vector.empty)))
      )
    )

  test("FsmState recovery/terminal — every variant round-trips"):
    roundTrip[FsmState](FsmState.NeedsHumanIntervention("spec settle timeout", ResumeHint.AbortOrAbandon))
    roundTrip[FsmState](FsmState.FeatureDone)
    roundTrip[FsmState](FsmState.Abandoned("user requested"))
