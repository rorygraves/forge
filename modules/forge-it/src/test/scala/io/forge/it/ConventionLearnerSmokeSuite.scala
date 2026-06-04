package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.reviewer.{RealReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.{
  BranchModel,
  CommandKind,
  CommitIdentity,
  ConventionDeltas,
  Determinism,
  MergeStrategy,
  RepoCommand,
  RepoProfile,
  WorkflowProfile
}

import scala.concurrent.duration.*

/** Task 3.2 — live wiring smoke for the §7.11 `ConventionLearner` sensor (`Connector.learnConventions`). Runs the
  * **real** Claude / Codex CLI against the shipped `convention-deltas.json` schema + `learn-conventions.<cli>.md`
  * prompt, feeding the canonical dogfood signal — a `DeterministicFix` that was paid as a `DriverFixup` because the
  * profile (deliberately) declares no `format` autofix — and asserts the structured output decodes cleanly into a
  * [[ConventionDeltas]]. The deterministic decode is proven in `ConventionDeltasDecoderSuite`; this proves the live CLI
  * honours the schema and the prompt steers the perception (the model should propose the missing `format` command
  * and/or a CLAUDE.md note rather than nothing).
  *
  * **Opt-in by default.** Real CLI spend; even with the binary on PATH this skips unless
  * `FORGE_IT_RUN_LEARNER_SMOKE=1`. Per-connector escape hatches `FORGE_IT_SKIP_CLAUDE=1` / `FORGE_IT_SKIP_CODEX=1`
  * (matching [[FailureClassifierSmokeSuite]]); Claude model pinnable via `FORGE_IT_CLAUDE_MODEL` (default = CLI
  * default), Codex via `FORGE_IT_CODEX_MODEL` (default `gpt-5.3-codex`).
  */
class ConventionLearnerSmokeSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 600.seconds

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val optIn: Boolean = sys.env.get("FORGE_IT_RUN_LEARNER_SMOKE").contains("1")
  private val claudeOnPath: Option[os.Path] = onPath("claude")
  private val codexOnPath: Option[os.Path] = onPath("codex")
  private val claudeCanRun: Boolean =
    optIn && claudeOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val codexCanRun: Boolean = optIn && codexOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")

  private val claudeModel: Option[String] = sys.env.get("FORGE_IT_CLAUDE_MODEL").filter(_.nonEmpty)
  private val codexModel: String = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")
  private val limits: ReviewerLimits = ReviewerLimits(wallClockTimeout = 5.minutes)

  private lazy val installedPaths: ForgePaths =
    val home = os.temp.dir(prefix = "forge-it-learner-home-", deleteOnExit = true)
    val paths = ForgePaths(repoRoot = os.pwd, home = home)
    AssetInstaller.installIfMissing(paths).unsafeRunSync() match
      case Right(_) => paths
      case Left(err) => fail(s"reviewer asset install failed: ${err.detail}")

  private def assetsFor(cli: String): ReviewerAssets =
    val schemas = installedPaths.userSchemasDir
    val prompts = installedPaths.userPromptsDir
    ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schemas / "design-review.json", prompts / s"design-review.$cli.md"),
      prReview = ReviewerAssets.PerMethod(schemas / "code-review.json", prompts / s"code-review.$cli.md"),
      refine = ReviewerAssets.PerMethod(schemas / "refine.json", prompts / s"refine.$cli.md"),
      profileRepo = ReviewerAssets.PerMethod(schemas / "repo-profile.json", prompts / s"repo-profile.$cli.md"),
      classifyFailure =
        ReviewerAssets.PerMethod(schemas / "failure-classifier.json", prompts / s"failure-classifier.$cli.md"),
      learnConventions =
        ReviewerAssets.PerMethod(schemas / "convention-deltas.json", prompts / s"learn-conventions.$cli.md")
    )

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try
      upickle.default.read[PriceTable](scala.io.Source.fromInputStream(stream)(using scala.io.Codec("UTF-8")).mkString)
    finally stream.close()

  /** A profile that deliberately omits a `format` autofix — so the learner has a genuine gap to propose filling. */
  private val profileMissingFormat: RepoProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector(
      RepoCommand(
        CommandKind.Build,
        Vector("sbt", "compile"),
        Determinism.Deterministic,
        required = true,
        autofix = false
      )
    ),
    commitIdentity = CommitIdentity("forge[bot]", "forge@users.noreply.github.com"),
    workflow = WorkflowProfile(true, Vector("backend"), BranchModel.TrunkBased, MergeStrategy.Squash)
  )

  /** The canonical dogfood signal: a scalafmt `DeterministicFix` the run paid as a `DriverFixup` (because the profile
    * had no autofix) — exactly the pattern a learned convention should prevent next time.
    */
  private def learnerInput: ConventionLearnerInput =
    ConventionLearnerInput(
      FeatureId("smoke-feat"),
      profile = profileMissingFormat,
      claudeDoc = Some("# CLAUDE.md\n\nForge drives this repo."),
      failures = Vector(
        ObservedFailure(
          gate = "ci",
          kind = "deterministic_fix",
          suggested = Some("format"),
          route = "DriverFixup",
          evidence = "scalafmt: 1 files must be formatted"
        )
      ),
      reviewerComments = Vector(
        ObservedReviewerComment(gate = "code", round = None, blocker = "add ScalaDoc to the new public method")
      )
    )

  /** The live output is judged for *plausibility*, not byte-equality (the model's perception of the run is its own): a
    * non-empty summary, and either a proposed `format` command or a CLAUDE.md note — i.e. it learned *something*.
    */
  private def assertPlausible(d: ConventionDeltas): Unit =
    assert(d.summary.nonEmpty, s"summary should be non-empty: $d")
    assert(
      d.addCommands.nonEmpty || d.claudeMdProposal.isDefined,
      s"a paid format fix-up should yield at least one proposal: $d"
    )
    d.addCommands.foreach(c => assert(c.argv.nonEmpty, s"a proposed command needs argv: $c"))

  test("learnConventions (claude) on a paid format fix-up → a plausible proposal".flaky):
    assume(claudeCanRun, "set FORGE_IT_RUN_LEARNER_SMOKE=1 with `claude` on PATH")
    val connector =
      ClaudeConnector(
        binary = claudeOnPath.get.toString,
        reviewerAssets = Some(assetsFor("claude")),
        reviewerModel = claudeModel
      )
    new RealReviewerCall(connector).learnConventions(learnerInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(d) => assertPlausible(d)
      case other => fail(s"expected a settled ConventionDeltas, got $other")

  test("learnConventions (codex) on a paid format fix-up → a plausible proposal".flaky):
    assume(codexCanRun, "set FORGE_IT_RUN_LEARNER_SMOKE=1 with `codex` on PATH")
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never"),
      reviewerAssets = Some(assetsFor("codex"))
    )
    new RealReviewerCall(connector).learnConventions(learnerInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(d) => assertPlausible(d)
      case other => fail(s"expected a settled ConventionDeltas, got $other")
