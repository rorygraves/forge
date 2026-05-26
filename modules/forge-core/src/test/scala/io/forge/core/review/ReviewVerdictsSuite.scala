package io.forge.core.review

import io.forge.core.{PieceId, Question, QuestionSeverity}
import io.forge.core.manifest.{ManifestPatch, ManifestPatchOp}

import upickle.default.{read, write}

/** PR-B B7 — codec round-trip for the §6 reviewer-verdict summaries (`DesignReviewVerdict`, `PrReviewVerdict`,
  * `RefineVerdict`).
  */
class ReviewVerdictsSuite extends munit.FunSuite:

  private def roundTrip[A: upickle.default.ReadWriter](a: A): Unit =
    val json = write(a)
    val parsed = read[A](json)
    assertEquals(parsed, a, s"round-trip failed for: $json")

  test("DesignReviewVerdict — Approve round-trips"):
    roundTrip[DesignReviewVerdict](DesignReviewVerdict.Approve)

  test("DesignReviewVerdict — RequestChanges round-trips with blockers"):
    roundTrip[DesignReviewVerdict](
      DesignReviewVerdict.RequestChanges(Vector("missing acceptance test", "no rollback plan"))
    )

  test("DesignReviewVerdict — BlockingQuestions round-trips with embedded Question"):
    roundTrip[DesignReviewVerdict](
      DesignReviewVerdict.BlockingQuestions(
        Vector(
          Question(
            text = "Should we cap at 10 retries?",
            options = Vector("yes", "no"),
            allowFreeText = true,
            severity = QuestionSeverity.Blocking
          )
        )
      )
    )

  test("PrReviewVerdict — round-trips both variants"):
    roundTrip[PrReviewVerdict](PrReviewVerdict.Approve)
    roundTrip[PrReviewVerdict](PrReviewVerdict.RequestChanges(Vector("missing test")))

  test("RefineVerdict — NoChange round-trips"):
    roundTrip[RefineVerdict](RefineVerdict.NoChange)

  test("RefineVerdict — UpdatePlan round-trips with embedded ManifestPatch"):
    val patch = ManifestPatch(
      reason = "p2 needs renaming",
      ops = Vector(
        ManifestPatchOp.EditPiece(
          id = PieceId("p2"),
          title = Some("new title"),
          summary = None,
          specPath = None,
          acceptanceHash = None
        )
      )
    )
    roundTrip[RefineVerdict](RefineVerdict.UpdatePlan(patch))

  test("RefineVerdict — ReopenDesign round-trips with reason string"):
    roundTrip[RefineVerdict](RefineVerdict.ReopenDesign("upstream API changed"))
