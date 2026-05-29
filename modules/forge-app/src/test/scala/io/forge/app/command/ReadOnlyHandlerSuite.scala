package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber}
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, FsmState}
import io.forge.core.log.Action
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildError, RebuildState}
import io.forge.specs.FileSpecStore

import java.time.Instant

/** Task 1.4.13 increment 1 — the read-only trio (`status` M4 / `tail` M11 / `rebuild-state` M9). Each path is exercised
  * without git / connector / lock: `status` reads the cache + manifest, `tail` reads the on-disk log, and
  * `rebuild-state` runs the pure-then-persist [[RebuildState]] pipeline against a seeded manifest + empty log. The §2.5
  * formatting/proof polish (O2 golden status, O3 tail smoke, O4 corrupted-cache rebuild) lands in Task 1.4.15.
  */
class ReadOnlyHandlerSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-readonly-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")

  private def manifest(pieces: Vector[Piece] = Vector.empty): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "My Feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = pieces
    )

  private def seedManifest(paths: ForgePaths, m: Manifest = manifest()): Unit =
    val _ = new FileSpecStore(paths).saveManifest(m.featureId, m).unsafeRunSync()

  // --- status --------------------------------------------------------------

  tempFixture.test("status on an unknown feature → FeatureNotFound"): root =>
    val result = StatusReport.describe(new ForgePaths(root), ForgeConfig.Default, featureId).unsafeRunSync()
    assertEquals(result, StatusReport.Result.FeatureNotFound(featureId))

  tempFixture.test("status on a manifest with no state cache → renders the no-cache hint"): root =>
    val paths = new ForgePaths(root)
    seedManifest(paths)
    StatusReport.describe(paths, ForgeConfig.Default, featureId).unsafeRunSync() match
      case StatusReport.Result.Rendered(text) =>
        assert(text.contains("My Feature"), text)
        assert(text.contains("no state cache"), text)
      case other => fail(s"expected Rendered, got $other")

  tempFixture.test("status with a cached state → renders the state label + budget"): root =>
    val paths = new ForgePaths(root)
    seedManifest(paths)
    val feature = Feature
      .initial(featureId, manifest())
      .copy(
        state = FsmState.DesignAwaitingMerge(PrNumber(42)),
        cost = CostTotals(BigDecimal("1.50"), BigDecimal("0.75"), BigDecimal(0))
      )
    new FileStateCache(paths).save(featureId, feature).unsafeRunSync()
    StatusReport.describe(paths, ForgeConfig.Default, featureId).unsafeRunSync() match
      case StatusReport.Result.Rendered(text) =>
        assert(text.contains("design PR #42"), text)
        assert(text.contains("$1.50 / $25.00"), text)
      case other => fail(s"expected Rendered, got $other")

  tempFixture.test("status overview lists every feature with a manifest"): root =>
    val paths = new ForgePaths(root)
    seedManifest(paths)
    seedManifest(paths, manifest().copy(featureId = FeatureId("other")))
    val text = StatusReport.overview(paths).unsafeRunSync()
    assert(text.contains("feat:"), text)
    assert(text.contains("other:"), text)

  tempFixture.test("status overview with no features → friendly empty message"): root =>
    val text = StatusReport.overview(new ForgePaths(root)).unsafeRunSync()
    assert(text.contains("no features found"), text)

  test("renderFeature names the active piece by title") {
    val p = Piece(
      id = PieceId("p1"),
      order = 1,
      title = "Wire the parser",
      summary = "s",
      specPath = "pieces/p1.md",
      acceptanceHash = "h",
      status = PieceStatus.InProgress,
      baseSha = None,
      prNumber = Some(PrNumber(7)),
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )
    val m = manifest(Vector(p))
    val feature = Feature.initial(featureId, m).copy(state = FsmState.PieceImplementing(PieceId("p1")))
    val action = Action(
      seq = 3,
      at = Instant.parse("2026-05-29T12:00:00Z"),
      feature = featureId,
      piece = Some(PieceId("p1")),
      actor = None,
      role = None,
      kind = "fsm.transition",
      payload = ujson.Obj()
    )
    val text = StatusReport.renderFeature(m, Some(feature), Some(action), ForgeConfig.Default)
    assert(text.contains("p1 — Wire the parser"), text)
    assert(text.contains("fsm.transition @ 2026-05-29T12:00:00Z"), text)
  }

  // --- tail ----------------------------------------------------------------

  tempFixture.test("tail existing reads the current NDJSON lines"): root =>
    val paths = new ForgePaths(root)
    val logPath = paths.featureLog(featureId)
    os.makeDir.all(logPath / os.up)
    os.write(logPath, "{\"a\":1}\n{\"b\":2}\n")
    val lines = TailCommand.existing(logPath).unsafeRunSync()
    assertEquals(lines, Vector("{\"a\":1}", "{\"b\":2}"))

  tempFixture.test("tail existing on a missing log → empty"): root =>
    val lines = TailCommand.existing(new ForgePaths(root).featureLog(featureId)).unsafeRunSync()
    assertEquals(lines, Vector.empty[String])

  tempFixture.test("tail on a feature with no log → exit 0 (nothing to tail)"): root =>
    val code = TailCommand.run(new ForgePaths(root), Vector(featureId.value)).unsafeRunSync()
    assertEquals(code, ExitCode.Success)

  tempFixture.test("tail with no feature argument → exit 64 (usage)"): root =>
    val code = TailCommand.run(new ForgePaths(root), Vector.empty).unsafeRunSync()
    assertEquals(code, ExitCode(64))

  // --- rebuild-state -------------------------------------------------------

  tempFixture.test("rebuild-state on a seeded manifest + empty log → exit 0 (recovers to Drafting)"): root =>
    val paths = new ForgePaths(root)
    seedManifest(paths)
    val code = RebuildStateCommand.run(paths, Vector(featureId.value)).unsafeRunSync()
    assertEquals(code, ExitCode.Success)
    // The cache was rewritten by the pipeline.
    val cached = new FileStateCache(paths).load(featureId).unsafeRunSync()
    assertEquals(cached.map(_.state), Some(FsmState.Drafting))

  tempFixture.test("rebuild-state on an undesigned feature → exit 1 (no manifest)"): root =>
    val code = RebuildStateCommand.run(new ForgePaths(root), Vector(featureId.value)).unsafeRunSync()
    assertEquals(code, ExitCode(1))

  tempFixture.test("rebuild-state with no feature argument → exit 64 (usage)"): root =>
    val code = RebuildStateCommand.run(new ForgePaths(root), Vector.empty).unsafeRunSync()
    assertEquals(code, ExitCode(64))

  test("renderSuccess surfaces interrupted in-flight sessions") {
    val feature = Feature.initial(featureId, manifest()).copy(state = FsmState.PieceImplementing(PieceId("p1")))
    val result = RebuildState.RebuildResult(
      feature,
      Vector(RebuildState.InFlightSession(io.forge.core.fsm.SessionPhase.Implement, "sess-1", Some(PieceId("p1"))))
    )
    val text = RebuildStateCommand.renderSuccess(featureId, result)
    assert(text.contains("interrupted driver session"), text)
    assert(text.contains("sess-1"), text)
  }

  test("renderError maps each RebuildError variant to an operator message") {
    val inconsistent = RebuildStateCommand.renderError(featureId, RebuildError.InconsistentRecovery("boom"))
    assert(inconsistent.contains("unrecoverable"), inconsistent)
    assert(inconsistent.contains("boom"), inconsistent)
  }
