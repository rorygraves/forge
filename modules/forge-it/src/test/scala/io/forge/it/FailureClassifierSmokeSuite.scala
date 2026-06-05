package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.reviewer.{RealReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.{
  BranchModel,
  Classification,
  CommandKind,
  CommitIdentity,
  Determinism,
  FailureKind,
  MergeStrategy,
  RepoCommand,
  RepoProfile,
  WorkflowProfile
}

import scala.concurrent.duration.*

/** Task 3.1.4 — live wiring smoke for the §7.11 `FailureClassifier` sensor (`Connector.classifyFailure`). Runs the
  * **real** Claude / Codex CLI against the shipped `failure-classifier.json` schema + `failure-classifier.<cli>.md`
  * prompt, feeding the real dogfood-#2 scalafmt failure plus a profile that exposes `sbt scalafmtAll`, and asserts the
  * structured output decodes cleanly into a [[Classification]] and that the model perceives it as a `DeterministicFix`
  * (the high-value collapse case). This is the "capture a real Claude/Codex structured-output sample before pinning the
  * schema" discipline as an executable check — the deterministic decode is proven in
  * `FailureClassificationDecoderSuite`; this proves the live CLI honours the schema and the prompt steers the
  * perception correctly.
  *
  * **Opt-in by default.** Real CLI spend; even with the binary on PATH this skips unless
  * `FORGE_IT_RUN_CLASSIFIER_SMOKE=1`. Per-connector escape hatches `FORGE_IT_SKIP_CLAUDE=1` / `FORGE_IT_SKIP_CODEX=1`
  * (matching [[RepoProfilerSmokeSuite]]); Claude model pinnable via `FORGE_IT_CLAUDE_MODEL` (default = CLI default),
  * Codex via `FORGE_IT_CODEX_MODEL` (default `gpt-5.3-codex`).
  */
class FailureClassifierSmokeSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 600.seconds

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val optIn: Boolean = sys.env.get("FORGE_IT_RUN_CLASSIFIER_SMOKE").contains("1")
  private val claudeOnPath: Option[os.Path] = onPath("claude")
  private val codexOnPath: Option[os.Path] = onPath("codex")
  private val claudeCanRun: Boolean =
    optIn && claudeOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val codexCanRun: Boolean = optIn && codexOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")

  private val claudeModel: Option[String] = sys.env.get("FORGE_IT_CLAUDE_MODEL").filter(_.nonEmpty)
  private val codexModel: String = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")
  private val limits: ReviewerLimits = ReviewerLimits(wallClockTimeout = 5.minutes)

  private lazy val installedPaths: ForgePaths =
    val home = os.temp.dir(prefix = "forge-it-classifier-home-", deleteOnExit = true)
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

  /** A profile that exposes `sbt scalafmtAll` as the deterministic, in-place format autofix — so the model can name a
    * `suggested` kind that actually maps to a routable command.
    */
  private val scalafmtProfile: RepoProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector(
      RepoCommand(
        CommandKind.Format,
        Vector("sbt", "scalafmtAll"),
        Determinism.Deterministic,
        required = true,
        autofix = true
      )
    ),
    commitIdentity = CommitIdentity("forge[bot]", "forge@users.noreply.github.com"),
    workflow = WorkflowProfile(true, Vector("backend"), BranchModel.TrunkBased, MergeStrategy.Squash)
  )

  /** The real dogfood-#2 failing log (the case the §8 collapse was designed around). */
  private val scalafmtFailureLog: String =
    """[warn] scalafmt: src/main/scala/szork/MediaNetworkConfig.scala isn't formatted properly!
      |[error] scalafmt: 1 files must be formatted (/home/runner/work/szork/szork)""".stripMargin

  private def classifierInput: FailureClassifierInput =
    FailureClassifierInput(
      FeatureId("smoke-feat"),
      gate = "ci",
      failureLog = scalafmtFailureLog,
      profile = scalafmtProfile
    )

  private def assertPlausible(c: Classification): Unit =
    assert(c.confidence >= 0.0 && c.confidence <= 1.0, s"confidence out of range: $c")
    assert(c.evidence.nonEmpty, s"evidence should be non-empty: $c")
    assertEquals(c.kind, FailureKind.DeterministicFix, s"a scalafmt failure should be a deterministic fix: $c")
    assertEquals(c.suggested, Some(CommandKind.Format), s"should suggest the format command: $c")

  test("classifyFailure (claude) on a real scalafmt failure → DeterministicFix(Format)".flaky):
    assume(claudeCanRun, "set FORGE_IT_RUN_CLASSIFIER_SMOKE=1 with `claude` on PATH")
    val connector =
      ClaudeConnector(
        binary = claudeOnPath.get.toString,
        reviewerAssets = Some(assetsFor("claude")),
        reviewerModel = claudeModel
      )
    new RealReviewerCall(connector).classifyFailure(classifierInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(c, _) => assertPlausible(c)
      case other => fail(s"expected a settled Classification, got $other")

  test("classifyFailure (codex) on a real scalafmt failure → DeterministicFix(Format)".flaky):
    assume(codexCanRun, "set FORGE_IT_RUN_CLASSIFIER_SMOKE=1 with `codex` on PATH")
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never"),
      reviewerAssets = Some(assetsFor("codex"))
    )
    new RealReviewerCall(connector).classifyFailure(classifierInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(c, _) => assertPlausible(c)
      case other => fail(s"expected a settled Classification, got $other")
