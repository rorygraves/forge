package io.forge.app.orchestrator

import io.forge.agents.{DesignReview, ReviewBlocker, ReviewVerdict}
import io.forge.core.{Question, QuestionSeverity}
import io.forge.core.review.DesignReviewVerdict

import OrchestratorTestKit.testConfig

/** Phase-3 exit-run finding **F1** — `Orchestrator.designVerdict` must honour `QuestionSeverity` so a headless `forge
  * run` is not stranded by a design reviewer that merely asks *clarifying* questions.
  *
  * Headless `forge run` has no `UserQa` source (`RunFeature` leaves `userInput = IO.never`), so a
  * `DesignNeedsHumanInput` state hangs the run with no way to answer. The reviewer already tags each question's
  * severity, and `Question.scala` documents that "only `Blocking` forces a state transition into a NeedsHumanInput
  * state during design review (§11.2 step 11)" — but the prior mapping treated *any* question as blocking. These tests
  * pin the corrected, severity-aware mapping.
  *
  * `designVerdict` is a pure method that touches none of the orchestrator's collaborators, so the suite builds an
  * `Orchestrator` with `null` deps (only `config` is read at construction) and calls it directly.
  */
class OrchestratorDesignVerdictSuite extends munit.FunSuite:

  private val orch = new Orchestrator(
    sideEffects = null,
    monitor = null,
    watcher = null,
    reviewer = null,
    specStore = null,
    manifestStore = null,
    log = null,
    cache = null,
    paths = null,
    config = testConfig
  )

  private def question(severity: QuestionSeverity): Question =
    Question(text = s"a $severity question", options = Vector.empty, allowFreeText = true, severity = severity)

  private def blocker(summary: String): ReviewBlocker =
    ReviewBlocker(summary = summary, path = None, line = None, anchorText = None)

  private def review(
      verdict: ReviewVerdict,
      blockers: Vector[ReviewBlocker] = Vector.empty,
      questions: Vector[Question] = Vector.empty
  ): DesignReview = DesignReview(verdict, blockers, questions, summary = "summary")

  test("Approve verdict → Approve"):
    assertEquals(orch.designVerdict(review(ReviewVerdict.Approve)), DesignReviewVerdict.Approve)

  test("RequestChanges with a Blocking question → BlockingQuestions (only the blocking ones)"):
    val qs = Vector(question(QuestionSeverity.Clarifying), question(QuestionSeverity.Blocking))
    orch.designVerdict(review(ReviewVerdict.RequestChanges, questions = qs)) match
      case DesignReviewVerdict.BlockingQuestions(kept) =>
        assertEquals(kept.map(_.severity), Vector(QuestionSeverity.Blocking))
      case other => fail(s"expected BlockingQuestions, got $other")

  test("F1 — RequestChanges with only Clarifying/Optional questions and no blockers → Approve (does not strand)"):
    val qs = Vector(question(QuestionSeverity.Clarifying), question(QuestionSeverity.Optional))
    assertEquals(
      orch.designVerdict(review(ReviewVerdict.RequestChanges, questions = qs)),
      DesignReviewVerdict.Approve
    )

  test("RequestChanges with blockers (no blocking questions) → RequestChanges carrying the blocker summaries"):
    val r = review(
      ReviewVerdict.RequestChanges,
      blockers = Vector(blocker("decompose the slice")),
      questions = Vector(question(QuestionSeverity.Clarifying))
    )
    assertEquals(orch.designVerdict(r), DesignReviewVerdict.RequestChanges(Vector("decompose the slice")))

  test("RequestChanges with nothing actionable (no questions, no blockers) → Approve"):
    assertEquals(orch.designVerdict(review(ReviewVerdict.RequestChanges)), DesignReviewVerdict.Approve)
