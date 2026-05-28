package io.forge.specs

import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, Mode, PieceId}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths

/** Task 1.4.3 C5 — `FileSpecStore` behaviour over a real temp directory.
  *
  * Covers round-trip for every method pair, atomic-write hygiene (no leftover temp file alongside the target),
  * parent-dir creation on demand, and the three `SpecStoreError` variants (`NotFound`, `Malformed`, `IoFailure`).
  *
  * The writer-side atomic-merge ordering test that closes **S2-5** lands later at Slice 1.4b Task 1.4.11 — that one
  * asserts the orchestrator persists the manifest before its action-log + state-cache writes. This suite covers the
  * per-call atomic-write invariant in isolation.
  */
class FileSpecStoreSuite extends munit.FunSuite:

  private val FeatureA: FeatureId = FeatureId("stripe-webhook")
  private val P1: PieceId = PieceId("p1")

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-spec-store-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def piecePending(id: PieceId, order: Int): Piece =
    Piece(
      id = id,
      order = order,
      title = s"Piece ${id.value}",
      summary = s"summary ${id.value}",
      specPath = s"specs/${FeatureA.value}/pieces/${id.value}.md",
      acceptanceHash = "sha256:" + ("0" * 64),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  private def manifestWith(pieces: Vector[Piece]): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = FeatureA,
      title = "Add Stripe webhook receiver",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = pieces
    )

  // --- C5: round-trip for every method --------------------------------------

  tempFixture.test("saveManifest then loadManifest returns the same Manifest"): root =>
    val store = new FileSpecStore(new ForgePaths(repoRoot = root))
    val original = manifestWith(Vector(piecePending(P1, 1)))
    val saved = store.saveManifest(FeatureA, original).unsafeRunSync()
    assertEquals(saved, Right(()))
    assertEquals(store.loadManifest(FeatureA).unsafeRunSync(), Right(original))

  tempFixture.test("saveDesign then loadDesign returns the same body"): root =>
    val store = new FileSpecStore(new ForgePaths(repoRoot = root))
    val body = "# Design\n\nFeature design body here.\n"
    assertEquals(store.saveDesign(FeatureA, body).unsafeRunSync(), Right(()))
    assertEquals(store.loadDesign(FeatureA).unsafeRunSync(), Right(body))

  tempFixture.test("saveDecomposition then loadDecomposition returns the same body"): root =>
    val store = new FileSpecStore(new ForgePaths(repoRoot = root))
    val body = "# Decomposition\n\n- [ ] p1: Piece p1\n"
    assertEquals(store.saveDecomposition(FeatureA, body).unsafeRunSync(), Right(()))
    assertEquals(store.loadDecomposition(FeatureA).unsafeRunSync(), Right(body))

  tempFixture.test("savePieceSpec then loadPieceSpec returns the same body"): root =>
    val store = new FileSpecStore(new ForgePaths(repoRoot = root))
    val body = "# Piece p1\n\nspec text\n"
    assertEquals(store.savePieceSpec(FeatureA, P1, body).unsafeRunSync(), Right(()))
    assertEquals(store.loadPieceSpec(FeatureA, P1).unsafeRunSync(), Right(body))

  tempFixture.test("saveManifest overwrites an existing manifest.json"): root =>
    val store = new FileSpecStore(new ForgePaths(repoRoot = root))
    val first = manifestWith(Vector(piecePending(P1, 1)))
    val second = manifestWith(Vector(piecePending(P1, 1), piecePending(PieceId("p2"), 2)))
    store.saveManifest(FeatureA, first).unsafeRunSync()
    store.saveManifest(FeatureA, second).unsafeRunSync()
    assertEquals(store.loadManifest(FeatureA).unsafeRunSync(), Right(second))

  // --- C5: atomic-write hygiene (no leftover temp file alongside target) ----

  tempFixture.test("saveManifest leaves no sibling temp file"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    store.saveManifest(FeatureA, manifestWith(Vector(piecePending(P1, 1)))).unsafeRunSync()
    val target = paths.manifest(FeatureA)
    val siblings = os.list(target / os.up).filter(_ != target)
    assert(siblings.isEmpty, s"expected no sibling files, got: ${siblings.map(_.last).mkString(", ")}")

  tempFixture.test("savePieceSpec leaves no sibling temp file"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    store.savePieceSpec(FeatureA, P1, "body").unsafeRunSync()
    val target = paths.pieceSpec(FeatureA, P1)
    val siblings = os.list(target / os.up).filter(_ != target)
    assert(siblings.isEmpty, s"expected no sibling files, got: ${siblings.map(_.last).mkString(", ")}")

  tempFixture.test("saveManifest creates the per-feature spec directory on demand"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    // Parent (.forge/specs/<feature>/) does not exist yet.
    assert(!os.exists(paths.featureSpecDir(FeatureA)))
    store.saveManifest(FeatureA, manifestWith(Vector(piecePending(P1, 1)))).unsafeRunSync()
    assert(os.exists(paths.manifest(FeatureA)))

  tempFixture.test("savePieceSpec creates the pieces/ subdirectory on demand"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val piecesDir = paths.featureSpecDir(FeatureA) / "pieces"
    assert(!os.exists(piecesDir))
    store.savePieceSpec(FeatureA, P1, "body").unsafeRunSync()
    assert(os.exists(paths.pieceSpec(FeatureA, P1)))

  // --- C5: NotFound ----------------------------------------------------------

  tempFixture.test("loadManifest returns NotFound when manifest.json is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    assertEquals(store.loadManifest(FeatureA).unsafeRunSync(), Left(SpecStoreError.NotFound(paths.manifest(FeatureA))))

  tempFixture.test("loadDesign returns NotFound when design.md is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    assertEquals(store.loadDesign(FeatureA).unsafeRunSync(), Left(SpecStoreError.NotFound(paths.design(FeatureA))))

  tempFixture.test("loadDecomposition returns NotFound when decomposition.md is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    assertEquals(
      store.loadDecomposition(FeatureA).unsafeRunSync(),
      Left(SpecStoreError.NotFound(paths.decomposition(FeatureA)))
    )

  tempFixture.test("loadPieceSpec returns NotFound when pieces/<p>.md is missing"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    assertEquals(
      store.loadPieceSpec(FeatureA, P1).unsafeRunSync(),
      Left(SpecStoreError.NotFound(paths.pieceSpec(FeatureA, P1)))
    )

  // --- C5: Malformed ---------------------------------------------------------

  tempFixture.test("loadManifest surfaces Malformed on truncated JSON"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val file = paths.manifest(FeatureA)
    os.makeDir.all(file / os.up)
    os.write(file, "{ \"schemaVersion\": 1, \"featureId\": \"stripe-webhook\"") // truncated
    store.loadManifest(FeatureA).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, _)) => assertEquals(p, file)
      case other => fail(s"expected Malformed, got $other")

  tempFixture.test("loadManifest surfaces Malformed when JSON parses but §5.1 invariants fail"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val file = paths.manifest(FeatureA)
    os.makeDir.all(file / os.up)
    // Duplicate piece id — well-formed JSON, fails Manifest.validate.
    val invalid = manifestWith(Vector(piecePending(P1, 1), piecePending(P1, 2)))
    os.write(file, Manifest.toJson(invalid))
    store.loadManifest(FeatureA).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, file)
        assert(
          cause.getMessage.contains("duplicate piece ids") || cause.getMessage.contains("order"),
          s"validation message should mention §5.1 invariant, got: ${cause.getMessage}"
        )
      case other => fail(s"expected Malformed, got $other")

  // --- C5: IoFailure ---------------------------------------------------------

  tempFixture.test("saveManifest surfaces IoFailure when parent path is a regular file"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    // Stamp `.forge/specs/stripe-webhook` as a regular file so os.makeDir.all fails when save tries to mkdir parents.
    val collidingFile = paths.featureSpecDir(FeatureA)
    os.makeDir.all(collidingFile / os.up)
    os.write(collidingFile, "not a directory")
    store.saveManifest(FeatureA, manifestWith(Vector(piecePending(P1, 1)))).unsafeRunSync() match
      case Left(SpecStoreError.IoFailure(p, _)) => assertEquals(p, paths.manifest(FeatureA))
      case other => fail(s"expected IoFailure, got $other")

  tempFixture.test("loadDesign surfaces IoFailure when the path is a directory"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val file = paths.design(FeatureA)
    // Create a directory at the design.md path so os.read fails.
    os.makeDir.all(file)
    store.loadDesign(FeatureA).unsafeRunSync() match
      case Left(SpecStoreError.IoFailure(p, _)) => assertEquals(p, file)
      case other => fail(s"expected IoFailure, got $other")

  // --- C5: manifest identity + schema-version guards (mirror ManifestStoreSuite) ---

  tempFixture.test("loadManifest rejects a manifest whose featureId does not match the requested id"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    // Manifest under FeatureA's path but carrying a different featureId — hand-edit / stale-file swap scenario.
    val mismatched = manifestWith(Vector(piecePending(P1, 1))).copy(featureId = FeatureId("other-feature"))
    val file = paths.manifest(FeatureA)
    os.makeDir.all(file / os.up)
    os.write(file, Manifest.toJson(mismatched))
    store.loadManifest(FeatureA).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, file)
        assert(cause.getMessage.contains("featureId"), s"expected featureId-mismatch message, got: ${cause.getMessage}")
      case other => fail(s"expected Malformed, got $other")

  tempFixture.test("loadManifest rejects a manifest with an unsupported schemaVersion"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    // Bump schemaVersion in the JSON directly so the unsupported-version guard (not validate) trips.
    val current = manifestWith(Vector(piecePending(P1, 1)))
    val file = paths.manifest(FeatureA)
    os.makeDir.all(file / os.up)
    val json = ujson.read(Manifest.toJson(current))
    json("schemaVersion") = ujson.Num((Manifest.CurrentSchemaVersion + 1).toDouble)
    os.write(file, ujson.write(json, indent = 2))
    store.loadManifest(FeatureA).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, file)
        assert(
          cause.getMessage.contains("schemaVersion"),
          s"expected schemaVersion-rejection message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected Malformed, got $other")

  tempFixture.test("saveManifest rejects a manifest whose featureId does not match the requested id"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val mismatched = manifestWith(Vector(piecePending(P1, 1))).copy(featureId = FeatureId("other-feature"))
    store.saveManifest(FeatureA, mismatched).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, paths.manifest(FeatureA))
        assert(cause.getMessage.contains("featureId"), s"expected featureId-mismatch message, got: ${cause.getMessage}")
      case other => fail(s"expected Malformed, got $other")
    // The committed source of truth must never have been written.
    assert(!os.exists(paths.manifest(FeatureA)), "saveManifest must not write a feature-id-mismatched manifest")

  tempFixture.test("saveManifest rejects a manifest with an unsupported schemaVersion"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    val bumped = manifestWith(Vector(piecePending(P1, 1))).copy(schemaVersion = Manifest.CurrentSchemaVersion + 1)
    store.saveManifest(FeatureA, bumped).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, paths.manifest(FeatureA))
        assert(
          cause.getMessage.contains("schemaVersion"),
          s"expected schemaVersion-rejection message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected Malformed, got $other")
    assert(!os.exists(paths.manifest(FeatureA)), "saveManifest must not write an unsupported-schema manifest")

  tempFixture.test("saveManifest rejects a §5.1-invalid manifest and leaves the existing file untouched"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val store = new FileSpecStore(paths)
    // Seed a valid committed manifest first.
    val valid = manifestWith(Vector(piecePending(P1, 1)))
    store.saveManifest(FeatureA, valid).unsafeRunSync()
    // Attempt to overwrite with a manifest that parses but violates §5.1 (duplicate piece ids).
    val invalid = manifestWith(Vector(piecePending(P1, 1), piecePending(P1, 2)))
    store.saveManifest(FeatureA, invalid).unsafeRunSync() match
      case Left(SpecStoreError.Malformed(p, cause)) =>
        assertEquals(p, paths.manifest(FeatureA))
        assert(
          cause.getMessage.contains("duplicate piece ids"),
          s"expected validate-failure message, got: ${cause.getMessage}"
        )
      case other => fail(s"expected Malformed, got $other")
    // The prior valid manifest must survive intact — a bad save can't brick the feature.
    assertEquals(store.loadManifest(FeatureA).unsafeRunSync(), Right(valid))
