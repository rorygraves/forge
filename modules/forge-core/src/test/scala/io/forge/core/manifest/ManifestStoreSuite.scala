package io.forge.core.manifest

import cats.effect.unsafe.implicits.global
import io.forge.core.*
import io.forge.core.fsm.FsmFixtures
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.paths.ForgePaths
import io.forge.core.state.RebuildError

/** PR-E E4 — file-backed `ManifestStore` reads `paths.manifest(id)`, runs `Manifest.validate`, and surfaces failures as
  * `RebuildError.ManifestLoadFailed`.
  */
class ManifestStoreSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-manifest-store-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  tempFixture.test("load returns Right(manifest) when the file exists and validates"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    val manifest = FsmFixtures.manifest(Vector(piecePending(P1, 1), piecePending(P2, 2)))
    val target = paths.manifest(FeatureA)
    os.makeDir.all(target / os.up)
    os.write(target, Manifest.toJson(manifest))
    val loaded = store.load(FeatureA).unsafeRunSync()
    loaded match
      case Right(m) => assertEquals(m, manifest)
      case Left(err) => fail(s"expected Right, got Left($err)")

  tempFixture.test("load returns Left(ManifestLoadFailed) when the file is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    val result = store.load(FeatureA).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, cause)) =>
        assertEquals(id, FeatureA)
        assert(
          cause.isInstanceOf[java.nio.file.NoSuchFileException],
          s"expected NoSuchFileException, got ${cause.getClass.getName}"
        )
      case other => fail(s"expected ManifestLoadFailed, got $other")

  tempFixture.test("load returns Left(ManifestLoadFailed) when the JSON is malformed"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    val target = paths.manifest(FeatureA)
    os.makeDir.all(target / os.up)
    os.write(target, "{ not valid json")
    val result = store.load(FeatureA).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, _)) => assertEquals(id, FeatureA)
      case other => fail(s"expected ManifestLoadFailed, got $other")

  tempFixture.test("load returns Left(ManifestLoadFailed) when validate fails (duplicate piece ids)"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    // Two pieces with the same id — Manifest.validate rejects this.
    val invalid = FsmFixtures.manifest(Vector(piecePending(P1, 1), piecePending(P1, 2)))
    val target = paths.manifest(FeatureA)
    os.makeDir.all(target / os.up)
    os.write(target, Manifest.toJson(invalid))
    val result = store.load(FeatureA).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, cause)) =>
        assertEquals(id, FeatureA)
        assert(
          cause.getMessage.contains("duplicate piece ids"),
          s"expected validate failure message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected ManifestLoadFailed, got $other")

  // --- Regression: identity + schema-version checks (review finding 3) ---

  tempFixture.test("load rejects a manifest whose featureId does not match the requested id"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    // Write a manifest under FeatureA's path but with FeatureB's featureId — a hand-edit / stale-file swap scenario.
    val mismatched = FsmFixtures
      .manifest(Vector(piecePending(P1, 1)))
      .copy(featureId = FeatureId("other-feature"))
    val target = paths.manifest(FeatureA)
    os.makeDir.all(target / os.up)
    os.write(target, Manifest.toJson(mismatched))
    val result = store.load(FeatureA).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, cause)) =>
        assertEquals(id, FeatureA)
        assert(
          cause.getMessage.contains("featureId"),
          s"expected featureId-mismatch message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected ManifestLoadFailed, got $other")

  tempFixture.test("load rejects a manifest with an unsupported schemaVersion"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileManifestStore(paths)
    // Write a manifest claiming a future schemaVersion. We can't construct one via copy on Manifest (the field is
    // hard-typed), but we can mutate the JSON directly.
    val current = FsmFixtures.manifest(Vector(piecePending(P1, 1)))
    val target = paths.manifest(FeatureA)
    os.makeDir.all(target / os.up)
    val json = ujson.read(Manifest.toJson(current))
    json("schemaVersion") = ujson.Num((Manifest.CurrentSchemaVersion + 1).toDouble)
    os.write(target, ujson.write(json, indent = 2))
    val result = store.load(FeatureA).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, cause)) =>
        assertEquals(id, FeatureA)
        assert(
          cause.getMessage.contains("schemaVersion"),
          s"expected schemaVersion-rejection message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected ManifestLoadFailed, got $other")
