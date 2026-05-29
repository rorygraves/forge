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

/** Slice 1.4a Task 1.4.7 — **hosts** the ≥19/20 native-schema regression gate (carry-forward **C15**) Slice 1.1
  * deferred. C15 only **closes** once all six method × connector pairs meet the bar in a live `FORGE_IT_RUN_REGRESSION=1`
  * run and `design-rationale.md` is updated — landing this suite does not by itself close it.
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
  * `FORGE_IT_SKIP_CLAUDE=1` / `FORGE_IT_SKIP_CODEX=1`. For an incremental ramp before the full batch, set
  * `FORGE_IT_REGRESSION_SAMPLES=<n>` (e.g. `=2`) to run a reduced-scale shakedown across all six pairs with a
  * proportionally-scaled pass bar — only the default `=20` is the C15-closing measurement (G3).
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
  private val codexSmokeCanRun: Boolean = smokeOptIn && codexOnPath.isDefined && !skipCodex

  private val codexModel: String = sys.env.getOrElse("FORGE_IT_CODEX_MODEL", "gpt-5.3-codex")

  /** Claude reviewer model. The reviewer one-shot otherwise inherits the CLI's *default* model, which is volatile (it
    * tracks the operator's configured default — currently Opus 4.8 at ~$0.40/call). Pin it via `FORGE_IT_CLAUDE_MODEL`
    * (e.g. `=haiku` for cheap mechanics validation, `=sonnet` for a realistic reviewer) so a batch's cost and its bar
    * reading are reproducible rather than whatever default happens to be set. Unset ⇒ inherit the CLI default.
    */
  private val claudeModel: Option[String] = sys.env.get("FORGE_IT_CLAUDE_MODEL").filter(_.nonEmpty)

  // --- knobs ----

  /** Samples per pair. Default 20 (the §16 bar's denominator). Override with `FORGE_IT_REGRESSION_SAMPLES=<n>` for a
    * reduced-scale **shakedown** run that exercises all six pairs without the full ~120-call spend — e.g. `=2` runs
    * 6×2=12 calls to prove the batch machinery end-to-end before committing to the full 20-sample measurement. The
    * §16 ≥19/20 bar only *holds* at the default 20; a reduced run applies the proportional bar below.
    */
  private val Samples: Int =
    sys.env.get("FORGE_IT_REGRESSION_SAMPLES").flatMap(_.toIntOption).filter(_ > 0).getOrElse(20)

  /** Pass bar scaled to the §16 ≥19/20 = 95% reliability target (ceil), so a reduced-sample shakedown still applies a
    * proportional bar: N=20 → 19 (the exact §16 bar), N=2 → 2, N=1 → 1. A reduced run is a wiring/early-signal check,
    * not the C15-closing measurement — only `Samples == 20` meets G3's denominator.
    */
  private val PassingThreshold: Int = math.ceil(Samples * 19.0 / 20.0).toInt
  private val ProcessRetries: Int = 1

  /** Per-call wall-clock cap (the [[ReviewerCall]] settle timeout). Default 3 min. Slower models on the largest input
    * (sonnet on a full pr-review diff runs a median ~77s but tails past 180s) brush this cap, so a timeout-driven bar
    * miss is a *latency*, not a schema, problem. Override with `FORGE_IT_REGRESSION_CAP=<seconds>` (e.g. `=300` to
    * match the connector's own `reviewerTimeout` default) to measure the bar under a production-realistic cap.
    */
  private val SettleCap: FiniteDuration =
    sys.env.get("FORGE_IT_REGRESSION_CAP").flatMap(_.toIntOption).filter(_ > 0).map(_.seconds).getOrElse(3.minutes)
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
    ClaudeConnector(
      binary = claudeOnPath.get.toString,
      reviewerAssets = Some(assetsFor("claude")),
      reviewerModel = claudeModel
    )

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

  /** Short failure reason for the assert clue — for a schema fail this carries the connector's error detail (which,
    * post-C18, includes the truncated offending payload), so a failing batch is self-diagnosing without a re-run.
    */
  private def detail[A](o: ReviewerOutcome[A]): Option[String] = o match
    case ReviewerOutcome.AdapterFailure(err) => Some(s"${err.getClass.getSimpleName}: ${err.getMessage}")
    case _ => None

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
    val failureDetails = outcomes.zipWithIndex.flatMap((o, i) => detail(o).map(d => s"#${i + 1} $d"))
    assert(
      passes >= PassingThreshold,
      clue = s"$label native-schema reliability $passes/${outcomes.size} < $PassingThreshold/${outcomes.size} " +
        s"(§16 / C15). breakdown: pass=$passes schemaFail=$schemaFails processFail=$processFails timeout=$timeouts. " +
        s"schemaFail counts against the bar — tighten the schema/prompt inside 1.4a per Task 1.4.7 G4. " +
        s"processFail/timeout are transient (already retried ${ProcessRetries}×); a nonzero residual points at the " +
        s"environment/network, re-run before tightening. per-sample: " +
        scores.zipWithIndex.map((s, i) => s"#${i + 1}=$s").mkString(", ") +
        (if failureDetails.nonEmpty then "\nfailure detail: " + failureDetails.mkString("\n  ", "\n  ", "") else "")
    )

  // --- wiring smoke (one call; its own cheap gate) ----

  test("smoke: one design-review call wires install→bind→decode end-to-end (claude)"):
    assume(
      claudeSmokeCanRun,
      "skipped — set FORGE_IT_RUN_REGRESSION_SMOKE=1 with claude on PATH (cheap single-call wiring check)"
    )
    assertWiresEndToEnd(RealReviewerCall(claudeConnector))

  test("smoke: one design-review call wires install→bind→decode end-to-end (codex)"):
    assume(
      codexSmokeCanRun,
      "skipped — set FORGE_IT_RUN_REGRESSION_SMOKE=1 with codex on PATH (cheap single-call wiring check)"
    )
    assertWiresEndToEnd(RealReviewerCall(codexConnector))

  private def assertWiresEndToEnd(rc: RealReviewerCall): Unit =
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
