package io.forge.it

import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.reviewer.{RealReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.{CommandKind, Determinism, RepoProfile}

import scala.concurrent.duration.*

/** Task 3.0.3 — live wiring smoke for the §7.11 `RepoProfiler` sensor (`Connector.profileRepo`). Runs the **real**
  * Claude / Codex CLI against the shipped `repo-profile.json` schema + `repo-profile.<cli>.md` prompt, feeding this
  * repo's own `AGENTS.md` / `CLAUDE.md` / `build.sbt`, and asserts the structured output decodes cleanly into a
  * [[RepoProfile]] with a plausible build tool. This is the "capture a real Claude/Codex structured-output sample
  * before pinning the schema" discipline as an executable check (the deterministic decode is proven against the
  * committed `szork` / `forge` fixtures in `RepoProfileDecoderSuite`; this proves the live CLI honours the schema).
  *
  * **Opt-in by default.** Real CLI spend; even with the binary on PATH this skips unless
  * `FORGE_IT_RUN_PROFILER_SMOKE=1`. Per-connector escape hatches `FORGE_IT_SKIP_CLAUDE=1` / `FORGE_IT_SKIP_CODEX=1`
  * (matching [[ReviewerRegressionSuite]]); Claude model pinnable via `FORGE_IT_CLAUDE_MODEL` (default = CLI default),
  * Codex via `FORGE_IT_CODEX_MODEL` (default `gpt-5.3-codex`).
  */
class RepoProfilerSmokeSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 600.seconds

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val optIn: Boolean = sys.env.get("FORGE_IT_RUN_PROFILER_SMOKE").contains("1")
  private val claudeOnPath: Option[os.Path] = onPath("claude")
  private val codexOnPath: Option[os.Path] = onPath("codex")
  private val claudeCanRun: Boolean = optIn && claudeOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val codexCanRun: Boolean = optIn && codexOnPath.isDefined && !sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")

  private val claudeModel: Option[String] = sys.env.get("FORGE_IT_CLAUDE_MODEL").filter(_.nonEmpty)
  private val codexModel: String = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")
  private val limits: ReviewerLimits = ReviewerLimits(wallClockTimeout = 5.minutes)

  private lazy val installedPaths: ForgePaths =
    val home = os.temp.dir(prefix = "forge-it-profiler-home-", deleteOnExit = true)
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
      profileRepo = ReviewerAssets.PerMethod(schemas / "repo-profile.json", prompts / s"repo-profile.$cli.md")
    )

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try upickle.default.read[PriceTable](scala.io.Source.fromInputStream(stream)(using scala.io.Codec("UTF-8")).mkString)
    finally stream.close()

  /** This repo's own facts — `forge` is the canonical `sbt` repo the `forge.json` fixture describes. */
  private def forgeInput: RepoProfilerInput =
    def readOpt(leaf: String): Option[String] =
      val p = os.pwd / leaf
      if os.exists(p) then Some(os.read(p).take(16384)) else None
    RepoProfilerInput(
      repoName = "forge",
      agentsDoc = readOpt("AGENTS.md"),
      claudeDoc = readOpt("CLAUDE.md"),
      buildFiles = readOpt("build.sbt").map(c => RepoFile("build.sbt", c)).toVector,
      workflowFiles = Vector.empty
    )

  private def assertPlausible(profile: RepoProfile): Unit =
    assert(profile.buildTool.nonEmpty, s"buildTool should be non-empty: $profile")
    assertEquals(profile.schemaVersion, RepoProfile.CurrentSchemaVersion)
    // A formatter command, if present, must be the routable shape the §8 collapse depends on.
    profile.command(CommandKind.Format).foreach { fmt =>
      assert(fmt.argv.nonEmpty, s"format command argv should be non-empty: $fmt")
      assert(
        fmt.determinism == Determinism.Deterministic,
        s"a format command should be deterministic (it is what routes to a local autofix): $fmt"
      )
    }

  test("profileRepo (claude) on the forge repo decodes into a plausible RepoProfile".flaky):
    assume(claudeCanRun, "set FORGE_IT_RUN_PROFILER_SMOKE=1 with `claude` on PATH")
    val connector =
      ClaudeConnector(binary = claudeOnPath.get.toString, reviewerAssets = Some(assetsFor("claude")), reviewerModel = claudeModel)
    new RealReviewerCall(connector).profileRepo(forgeInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(profile) => assertPlausible(profile)
      case other => fail(s"expected a settled RepoProfile, got $other")

  test("profileRepo (codex) on the forge repo decodes into a plausible RepoProfile".flaky):
    assume(codexCanRun, "set FORGE_IT_RUN_PROFILER_SMOKE=1 with `codex` on PATH")
    val connector = CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never"),
      reviewerAssets = Some(assetsFor("codex"))
    )
    new RealReviewerCall(connector).profileRepo(forgeInput, limits).unsafeRunSync() match
      case ReviewerOutcome.Settled(profile) => assertPlausible(profile)
      case other => fail(s"expected a settled RepoProfile, got $other")
