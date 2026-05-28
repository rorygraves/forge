package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.*
import io.forge.app.bootstrap.AssetInstaller
import io.forge.app.reviewer.{RealReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Sha}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths

import java.time.Instant
import scala.concurrent.duration.*

/** Slice 1.4a Task 1.4.7 (closes carry-forward **C15**) — the ≥19/20 native-schema regression gate Slice 1.1 deferred.
  *
  * Slice 1.1 wired `ReviewerAssets(PerMethod(schema, systemPrompt))` through both connectors and the `Reviews.scala`
  * ADT but shipped no actual schema/prompt files, so the §16 "the structured-output contract holds ≥19/20 times" bar
  * could not be measured. Task 1.4.1 landed the assets under `assets/reviewer/{schemas,prompts}/`; Task 1.4.2 landed the
  * `ReviewerCall` wall-clock wrapper. This suite runs the **real** Claude and Codex CLIs against those shipped assets,
  * once per `(method × connector)` pair, and asserts each pair's structured output decodes cleanly ≥19/20 times.
  *
  * **What the bar measures.** Each reviewer one-shot binds the shipped JSON Schema (Claude `--json-schema`, Codex
  * `--output-schema`) and the shipped system prompt, then the connector decodes the CLI's structured output into the
  * `DesignReview` / `PrReview` / `RefineResult` ADT. A clean decode is [[ReviewerOutcome.Settled]] and counts as a pass;
  * a missing / malformed structured output ([[StructuredOutputMissing]] / [[StructuredOutputMalformed]]) is a
  * schema-conformance failure and counts against the bar (G3). Process-level blips
  * ([[ReviewerProcessFailure]]) and wall-clock [[ReviewerOutcome.Timeout]] are transient and **retried** before
  * scoring (G3: "adapter errors that are retryable per §7.6 don't count against the bar"); a nonzero residual after
  * retries points at the environment, not the schema, and the failure clue says so.
  *
  * **Fixture methodology (deviation from G2's literal "20 inputs each").** G2 sketched 20 distinct hand-curated input
  * files per method. The §16 bar is a *reliability* measurement of the model's structured-output formatting, which the
  * established reliability-suite idiom ([[CodexHaltWithQuestionReliabilitySuite]]) samples with **one input × 20 runs**.
  * We follow that idiom with a small real fixture set — 3 representative inputs per method, drawn from this repo's own
  * design docs and PR diffs (`src/test/resources/regression/`) — cycled to 20 samples. This measures output-format
  * reliability across a few real inputs without authoring 60 large fixtures; the variance under test is the model's, not
  * the input set's. (Decision taken with the maintainer; recorded in the Task 1.4.7 status-log entry.)
  *
  * **Opt-in by default, very slow.** 6 pairs × 20 samples = 120+ real reviewer calls against full design docs / diffs;
  * a full run is tens of minutes and costs real CLI spend. Even with the binaries on PATH this suite skips unless
  * `FORGE_IT_RUN_REGRESSION=1` is set, matching the `FORGE_IT_RUN_RELIABILITY` gate. Per-connector escape hatches:
  * `FORGE_IT_SKIP_CLAUDE=1` / `FORGE_IT_SKIP_CODEX=1`.
  */
class ReviewerRegressionSuite extends munit.FunSuite:

  override def munitTimeout: Duration = 5400.seconds

  // --- gating ----

  private def onPath(bin: String): Option[os.Path] =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).collectFirst {
      case p if os.exists(p / bin) => p / bin
    }

  private val claudeOnPath: Option[os.Path] = onPath("claude")
  private val codexOnPath: Option[os.Path] = onPath("codex")
  private val regressionOptIn: Boolean = sys.env.get("FORGE_IT_RUN_REGRESSION").contains("1")
  private val skipClaude: Boolean = sys.env.get("FORGE_IT_SKIP_CLAUDE").contains("1")
  private val skipCodex: Boolean = sys.env.get("FORGE_IT_SKIP_CODEX").contains("1")
  private val claudeCanRun: Boolean = regressionOptIn && claudeOnPath.isDefined && !skipClaude
  private val codexCanRun: Boolean = regressionOptIn && codexOnPath.isDefined && !skipCodex

  /** Separate, far cheaper gate (`FORGE_IT_RUN_REGRESSION_SMOKE=1`) for the single-call wiring check below — proves the
    * install→bind→decode path end-to-end in ~one reviewer call without committing to the full 6×20 batch.
    */
  private val smokeOptIn: Boolean = sys.env.get("FORGE_IT_RUN_REGRESSION_SMOKE").contains("1")
  private val claudeSmokeCanRun: Boolean = smokeOptIn && claudeOnPath.isDefined && !skipClaude

  private val codexModel: String = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")

  // --- knobs ----

  private val Samples: Int = 20
  private val PassingThreshold: Int = 19
  private val ProcessRetries: Int = 1
  private val SettleCap: FiniteDuration = 3.minutes
  private val limits: ReviewerLimits = ReviewerLimits(wallClockTimeout = SettleCap)

  // --- shipped reviewer assets (installed into a temp ~/.forge via the real AssetInstaller path) ----

  /** Install the shipped assets once into a throwaway home, then expose per-CLI [[ReviewerAssets]] built from the
    * installed locations — exercising the same install→bind path the orchestrator uses, not the in-tree source dir.
    */
  private lazy val installedPaths: ForgePaths =
    val home = os.temp.dir(prefix = "forge-it-regression-home-", deleteOnExit = true)
    val paths = ForgePaths(repoRoot = os.pwd, home = home)
    AssetInstaller.installIfMissing(paths).unsafeRunSync() match
      case Right(_) => paths
      case Left(err) => fail(s"reviewer asset install failed: ${err.detail}")

  private def assetsFor(reviewer: String): ReviewerAssets =
    val schemas = installedPaths.userSchemasDir
    val prompts = installedPaths.userPromptsDir
    ReviewerAssets(
      designReview = ReviewerAssets.PerMethod(schemas / "design-review.json", prompts / s"design-review.$reviewer.md"),
      prReview = ReviewerAssets.PerMethod(schemas / "code-review.json", prompts / s"code-review.$reviewer.md"),
      refine = ReviewerAssets.PerMethod(schemas / "refine.json", prompts / s"refine.$reviewer.md")
    )

  private def loadPriceTable: PriceTable =
    val stream = getClass.getResourceAsStream("/prices.example.json")
    require(stream != null, "prices.example.json missing from classpath")
    try upickle.default.read[PriceTable](scala.io.Source.fromInputStream(stream)("UTF-8").mkString)
    finally stream.close()

  private def claudeConnector: Connector =
    ClaudeConnector(binary = claudeOnPath.get.toString, reviewerAssets = Some(assetsFor("claude")))

  private def codexConnector: Connector =
    CodexConnector(
      binary = codexOnPath.get.toString,
      model = codexModel,
      priceTable = loadPriceTable,
      sessionSettings = CodexSessionSettings.driver(sandbox = "read-only", approvalMode = "never"),
      reviewerAssets = Some(assetsFor("codex"))
    )

  // --- fixtures (real repo content under src/test/resources/regression/) ----

  private def resource(path: String): String =
    val stream = getClass.getResourceAsStream(path)
    require(stream != null, s"missing test resource: $path")
    try scala.io.Source.fromInputStream(stream)("UTF-8").mkString
    finally stream.close()

  private val featureId: FeatureId = FeatureId("reviewer-regression")

  private def designFixtures: Vector[DesignReviewInput] =
    Vector("compact", "medium", "large").map { name =>
      DesignReviewInput(featureId, round = 1, designMarkdown = resource(s"/regression/design-review/$name.md"))
    }

  private def prFixtures: Vector[PrReviewInput] =
    Vector(("small", "p1", 101), ("refactor", "p2", 102), ("feature", "p3", 103)).map { (name, pid, pr) =>
      PrReviewInput(
        featureId = featureId,
        pieceId = PieceId(pid),
        prNumber = PrNumber(pr),
        pieceSpec = resource(s"/regression/pr-review/$name/spec.md"),
        diff = resource(s"/regression/pr-review/$name/change.diff"),
        changedFiles = resource(s"/regression/pr-review/$name/files.txt").linesIterator.filter(_.nonEmpty).toVector
      )
    }

  /** Real-shaped manifest built inline (type-checked, no fixture-vs-type drift — same idiom as `DocSyncSuite`): one
    * merged piece `p1` (the just-merged piece each refine fixture references) and one pending `p2`.
    */
  private def refineManifestJson: String =
    val p1 = Piece(
      id = PieceId("p1"),
      order = 1,
      title = "First piece",
      summary = "The just-merged piece.",
      specPath = ".forge/specs/reviewer-regression/pieces/p1.md",
      acceptanceHash = "a1b2c3d4",
      status = PieceStatus.Merged,
      baseSha = Some(Sha("a1b2c3d")),
      prNumber = Some(PrNumber(101)),
      mergeCommit = Some(Sha("d4e5f60")),
      mergedAt = Some(Instant.parse("2026-05-28T10:00:00Z")),
      attempts = 1
    )
    val p2 = Piece(
      id = PieceId("p2"),
      order = 2,
      title = "Second piece",
      summary = "Still pending.",
      specPath = ".forge/specs/reviewer-regression/pieces/p2.md",
      acceptanceHash = "e5f6a7b8",
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )
    Manifest.toJson(
      Manifest(
        schemaVersion = Manifest.CurrentSchemaVersion,
        featureId = featureId,
        title = "Reviewer regression feature",
        baseBranch = BranchName("main"),
        branchPrefix = "forge",
        mode = Mode.ClaudeDriver,
        designPr = None,
        pieces = Vector(p1, p2)
      )
    )

  private def refineFixtures: Vector[RefineInput] =
    Vector("no-change", "update-plan", "reopen").map { name =>
      RefineInput(
        featureId = featureId,
        mergedPieceId = PieceId("p1"),
        designMarkdown = resource(s"/regression/refine/$name/design.md"),
        manifestJson = refineManifestJson
      )
    }

  // --- scoring ----

  private enum Score:
    case Pass, SchemaFail, ProcessFail, Timeout, NotConfigured

  private def classify[A](o: ReviewerOutcome[A]): Score = o match
    case ReviewerOutcome.Settled(_) => Score.Pass
    case ReviewerOutcome.Timeout => Score.Timeout
    case ReviewerOutcome.AdapterFailure(err) =>
      err match
        case _: StructuredOutputMissing => Score.SchemaFail
        case _: StructuredOutputMalformed => Score.SchemaFail
        case _: ReviewerProcessFailure => Score.ProcessFail
        case _: ReviewerNotConfigured => Score.NotConfigured

  /** Retry transient (process / timeout) outcomes before scoring — these "don't count against the bar" (G3). A clean
    * decode or a schema failure is final.
    */
  private def withRetry[A](io: IO[ReviewerOutcome[A]], retries: Int): IO[ReviewerOutcome[A]] =
    io.flatMap {
      case ReviewerOutcome.Timeout if retries > 0 => withRetry(io, retries - 1)
      case ReviewerOutcome.AdapterFailure(_: ReviewerProcessFailure) if retries > 0 => withRetry(io, retries - 1)
      case other => IO.pure(other)
    }

  private def runPair[A, I](
      fixtures: Vector[I],
      call: I => IO[ReviewerOutcome[A]]
  ): Vector[ReviewerOutcome[A]] =
    (0 until Samples).iterator.map { i =>
      val fixture = fixtures(i % fixtures.size)
      withRetry(call(fixture), ProcessRetries).unsafeRunSync()
    }.toVector

  private def scoreAndAssert[A](label: String, outcomes: Vector[ReviewerOutcome[A]]): Unit =
    val scores = outcomes.map(classify)
    val passes = scores.count(_ == Score.Pass)
    val schemaFails = scores.count(_ == Score.SchemaFail)
    val processFails = scores.count(_ == Score.ProcessFail)
    val timeouts = scores.count(_ == Score.Timeout)
    val notConfigured = scores.count(_ == Score.NotConfigured)
    assert(
      notConfigured == 0,
      clue = s"$label: $notConfigured/${outcomes.size} ReviewerNotConfigured — reviewer assets misconfigured in the " +
        "test harness (not a model result); check the AssetInstaller wiring above."
    )
    assert(
      passes >= PassingThreshold,
      clue = s"$label native-schema reliability $passes/${outcomes.size} < $PassingThreshold/${outcomes.size} " +
        s"(§16 / C15). breakdown: pass=$passes schemaFail=$schemaFails processFail=$processFails timeout=$timeouts. " +
        s"schemaFail counts against the bar — tighten the schema/prompt inside 1.4a per Task 1.4.7 G4. " +
        s"processFail/timeout are transient (already retried ${ProcessRetries}×); a nonzero residual points at the " +
        s"environment/network, re-run before tightening. per-sample: " +
        scores.zipWithIndex.map((s, i) => s"#${i + 1}=$s").mkString(", ")
    )

  // --- wiring smoke (one call; its own cheap gate) ----

  test("smoke: one design-review call wires install→bind→decode end-to-end (claude)"):
    assume(
      claudeSmokeCanRun,
      "skipped — set FORGE_IT_RUN_REGRESSION_SMOKE=1 with claude on PATH (cheap single-call wiring check)"
    )
    val rc = RealReviewerCall(claudeConnector)
    val outcome = withRetry(rc.designReview(designFixtures.head, limits), ProcessRetries).unsafeRunSync()
    outcome match
      case ReviewerOutcome.Settled(_) => () // clean schema decode — the wiring proof
      case other =>
        fail(s"expected Settled from a healthy reviewer call; got ${classify(other)} ($other)")

  // --- the six pairs ----

  test("design-review (claude): native-schema reliability ≥ 19/20"):
    assume(claudeCanRun, "skipped — claude not on PATH, FORGE_IT_SKIP_CLAUDE=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(claudeConnector)
    scoreAndAssert("design-review/claude", runPair(designFixtures, rc.designReview(_, limits)))

  test("design-review (codex): native-schema reliability ≥ 19/20"):
    assume(codexCanRun, "skipped — codex not on PATH, FORGE_IT_SKIP_CODEX=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(codexConnector)
    scoreAndAssert("design-review/codex", runPair(designFixtures, rc.designReview(_, limits)))

  test("pr-review (claude): native-schema reliability ≥ 19/20"):
    assume(claudeCanRun, "skipped — claude not on PATH, FORGE_IT_SKIP_CLAUDE=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(claudeConnector)
    scoreAndAssert("pr-review/claude", runPair(prFixtures, rc.prReview(_, limits)))

  test("pr-review (codex): native-schema reliability ≥ 19/20"):
    assume(codexCanRun, "skipped — codex not on PATH, FORGE_IT_SKIP_CODEX=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(codexConnector)
    scoreAndAssert("pr-review/codex", runPair(prFixtures, rc.prReview(_, limits)))

  test("refine (claude): native-schema reliability ≥ 19/20"):
    assume(claudeCanRun, "skipped — claude not on PATH, FORGE_IT_SKIP_CLAUDE=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(claudeConnector)
    scoreAndAssert("refine/claude", runPair(refineFixtures, rc.refine(_, limits)))

  test("refine (codex): native-schema reliability ≥ 19/20"):
    assume(codexCanRun, "skipped — codex not on PATH, FORGE_IT_SKIP_CODEX=1, or FORGE_IT_RUN_REGRESSION!=1")
    val rc = RealReviewerCall(codexConnector)
    scoreAndAssert("refine/codex", runPair(refineFixtures, rc.refine(_, limits)))
