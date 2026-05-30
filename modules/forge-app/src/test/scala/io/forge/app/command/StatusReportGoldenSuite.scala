package io.forge.app.command

import io.forge.app.config.ForgeConfig
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Question, QuestionSeverity}
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, FsmState}
import io.forge.core.log.Action
import io.forge.core.manifest.{Manifest, ManifestPatch, Piece, PieceStatus}

import java.time.Instant

/** Task 1.4.15 O2 — golden-file render of `forge status` ([[StatusReport.renderFeature]]) for a fixture feature in
  * every [[FsmState]].
  *
  * `renderFeature` is the §2.5 "current state, current piece, last action, budget remaining at a glance" block. The
  * `stateLabel` match is total over the FSM, so the *interesting* drift is wording / layout — exactly what a byte-for-
  * byte golden catches. Each state renders against one fixed manifest (two pieces: `p1` in progress with PR #7, `p2`
  * pending), a fixed cost, and a fixed last action, so the only thing that varies between goldens is the state line and
  * the active-piece line. A no-cache row pins the "freshly `forge new`'d" rendering (cache absent → manifest-only).
  *
  * Regenerating goldens: `FORGE_UPDATE_GOLDEN=1 sbt "forge-app/testOnly *StatusReportGoldenSuite"`, then inspect the
  * diff before committing. Mirrors `forge-specs` `TemplateRenderSuite`: regeneration is the *only* write path; a
  * missing golden on a normal run is a **failure**, not a silent write, so adding an FSM state without committing its
  * golden fails CI rather than passing while mutating the working tree.
  */
class StatusReportGoldenSuite extends munit.FunSuite:

  private val goldenDir: os.Path =
    os.pwd / "modules" / "forge-app" / "src" / "test" / "resources" / "golden" / "status"

  private val updateGolden: Boolean = sys.env.get("FORGE_UPDATE_GOLDEN").contains("1")

  private val featureId = FeatureId("stripe-webhook")
  private val p1 = PieceId("p1")
  private val pr7 = PrNumber(7)

  private val manifest: Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "Add Stripe webhook receiver",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector(
        Piece(
          id = p1,
          order = 1,
          title = "Wire the parser",
          summary = "s1",
          specPath = "pieces/p1.md",
          acceptanceHash = "h1",
          status = PieceStatus.InProgress,
          baseSha = None,
          prNumber = Some(pr7),
          mergeCommit = None,
          mergedAt = None,
          attempts = 0
        ),
        Piece(
          id = PieceId("p2"),
          order = 2,
          title = "Add the cache",
          summary = "s2",
          specPath = "pieces/p2.md",
          acceptanceHash = "h2",
          status = PieceStatus.Pending,
          baseSha = None,
          prNumber = None,
          mergeCommit = None,
          mergedAt = None,
          attempts = 0
        )
      )
    )

  private val cost = CostTotals(BigDecimal("1.50"), BigDecimal("0.75"), BigDecimal(0))

  private val lastAction = Some(
    Action(
      seq = 7,
      at = Instant.parse("2026-05-30T09:00:00Z"),
      feature = featureId,
      piece = Some(p1),
      actor = None,
      role = None,
      kind = "fsm.transition",
      payload = ujson.Obj()
    )
  )

  private val refiningAt = Instant.parse("2026-05-30T08:30:00Z")

  /** Every FSM state, with stable representative arguments. The label is the golden file name. */
  private val states: Vector[(String, FsmState)] = Vector(
    "drafting" -> FsmState.Drafting,
    "interactive-spec" -> FsmState.InteractiveSpec,
    "design-reviewing" -> FsmState.DesignReviewing(1),
    "design-needs-human-input" -> FsmState.DesignNeedsHumanInput(
      1,
      Vector(Question("Why verify?", Vector("a", "b"), allowFreeText = true, QuestionSeverity.Blocking))
    ),
    "design-awaiting-merge" -> FsmState.DesignAwaitingMerge(pr7),
    "design-pr-feedback" -> FsmState.DesignPrFeedback(pr7, 2),
    "design-ready" -> FsmState.DesignReady,
    "piece-implementing" -> FsmState.PieceImplementing(p1),
    "piece-awaiting-ci" -> FsmState.PieceAwaitingCi(p1, pr7),
    "piece-awaiting-review" -> FsmState.PieceAwaitingReview(p1, pr7),
    "piece-ci-failed" -> FsmState.PieceCiFailed(p1, pr7, 1),
    "piece-review-failed" -> FsmState.PieceReviewFailed(p1, pr7, 1),
    "piece-fixing-up" -> FsmState.PieceFixingUp(p1, pr7, 1),
    "piece-awaiting-merge" -> FsmState.PieceAwaitingMerge(p1, pr7),
    "refining" -> FsmState.Refining(p1, pr7, refiningAt),
    "planning-update" -> FsmState.PlanningUpdate("add a piece", ManifestPatch("add a piece", Vector.empty)),
    "needs-human-intervention" -> FsmState.NeedsHumanIntervention(
      "ci failed after 3 fix-up attempts",
      io.forge.core.fsm.ResumeHint.RunAnotherFixup(p1, pr7)
    ),
    "feature-done" -> FsmState.FeatureDone,
    "abandoned" -> FsmState.Abandoned("operator abandoned")
  )

  private def featureIn(state: FsmState): Feature =
    Feature.initial(featureId, manifest).copy(state = state, cost = cost)

  private def checkGolden(name: String, rendered: String): Unit =
    val goldenFile = goldenDir / s"status-$name.golden"
    if updateGolden then
      os.makeDir.all(goldenDir)
      os.write.over(goldenFile, rendered)
      println(s"[StatusReportGoldenSuite] wrote golden $goldenFile (${rendered.length} chars)")
    else if !os.exists(goldenFile) then
      fail(
        s"golden $goldenFile is missing — an FSM state was added without committing its golden. " +
          "Regenerate with FORGE_UPDATE_GOLDEN=1 and commit the result."
      )
    else
      assertEquals(
        rendered,
        os.read(goldenFile),
        s"status-$name render drifted from golden — regenerate with FORGE_UPDATE_GOLDEN=1"
      )

  states.foreach { case (name, state) =>
    test(s"status render matches golden — $name"):
      checkGolden(name, StatusReport.renderFeature(manifest, Some(featureIn(state)), lastAction, ForgeConfig.Default))
  }

  // The no-cache path is a distinct rendering branch (no state line, no budget, piece-count summary). Pin it too.
  test("status render matches golden — no-cache"):
    checkGolden("no-cache", StatusReport.renderFeature(manifest, None, None, ForgeConfig.Default))
