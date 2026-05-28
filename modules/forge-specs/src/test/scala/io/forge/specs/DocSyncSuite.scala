package io.forge.specs

import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Sha}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Task 1.4.4 D4 — `FileDocSync` against the **shipped** `decomposition.md.hbs` (loaded from the test classpath via the
  * `assets/` unmanaged resource dir wired in build.sbt), so a drift between the renderer and the real template is
  * caught here rather than at first run.
  *
  * Covers: idempotent render → write → re-read → re-render (byte-identical), the per-`PieceStatus` badge, the
  * `feature.designPr` `{{#if}}` branch, and the four `DocSyncError` channels (`TemplateMissing`, `TemplateMalformed`,
  * `RenderFailure`, `SpecStoreFailure`). Manifests are built inline via the same helper shape as `FileSpecStoreSuite`
  * (the sibling idiom) rather than JSON fixtures, keeping the manifest contract type-checked.
  */
class DocSyncSuite extends munit.FunSuite:

  private val FeatureA: FeatureId = FeatureId("stripe-webhook")

  private val realTemplate: String =
    val in = getClass.getResourceAsStream("/templates/decomposition.md.hbs")
    assert(in != null, "decomposition.md.hbs not on the test classpath — check forge-specs build.sbt resource dirs")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-docsync-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def installTemplate(paths: ForgePaths, body: String = realTemplate): Unit =
    os.makeDir.all(paths.userTemplatesDir)
    os.write.over(paths.userTemplatesDir / "decomposition.md.hbs", body)

  private def docSync(root: os.Path): (ForgePaths, FileSpecStore, FileDocSync) =
    val paths = new ForgePaths(repoRoot = root, home = root)
    val store = new FileSpecStore(paths)
    (paths, store, new FileDocSync(paths, store))

  // --- manifest builders (mirror FileSpecStoreSuite) -------------------------

  private val Sha40: Sha = Sha("0123456789abcdef0123456789abcdef01234567")

  private def pending(id: String, order: Int): Piece =
    Piece(
      PieceId(id),
      order,
      s"Piece $id",
      s"summary for $id",
      s"specs/x/$id.md",
      "sha256:" + ("0" * 64),
      PieceStatus.Pending,
      None,
      None,
      None,
      None,
      0
    )

  private def inProgress(id: String, order: Int): Piece =
    pending(id, order).copy(status = PieceStatus.InProgress, baseSha = Some(Sha40))

  private def merged(id: String, order: Int, pr: Int): Piece =
    pending(id, order).copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha40),
      prNumber = Some(PrNumber(pr)),
      mergeCommit = Some(Sha40),
      mergedAt = Some(Instant.parse("2026-05-28T00:00:00Z"))
    )

  private def manifest(pieces: Vector[Piece], designPr: Option[PrNumber] = None): Manifest =
    Manifest(
      Manifest.CurrentSchemaVersion,
      FeatureA,
      "Add Stripe webhook receiver",
      BranchName("main"),
      "forge",
      Mode.ClaudeDriver,
      designPr,
      pieces
    )

  // §5.5: merged pieces form a contiguous prefix, so merged-before-in_progress-before-pending.
  private def mixedManifest: Manifest =
    manifest(Vector(merged("p1", 1, 42), inProgress("p2", 2), pending("p3", 3)), designPr = Some(PrNumber(7)))

  // --- D4: idempotent round-trip ---------------------------------------------

  tempFixture.test("render → write → re-read → re-render is byte-identical"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    assertEquals(store.saveManifest(FeatureA, mixedManifest).unsafeRunSync(), Right(()))

    val first = sync.renderDecomposition(FeatureA).unsafeRunSync().fold(e => fail(s"render failed: $e"), identity)
    assertEquals(sync.writeDecomposition(FeatureA).unsafeRunSync(), Right(()))
    // The persisted file matches the render…
    assertEquals(store.loadDecomposition(FeatureA).unsafeRunSync(), Right(first))
    // …and a second render of the unchanged manifest is byte-identical.
    assertEquals(sync.renderDecomposition(FeatureA).unsafeRunSync(), Right(first))

  tempFixture.test("writeDecomposition persists the file at the ForgePaths location"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    sync.writeDecomposition(FeatureA).unsafeRunSync()
    assert(os.exists(paths.decomposition(FeatureA)))

  // --- D4: per-PieceStatus badge ---------------------------------------------

  tempFixture.test("each PieceStatus renders its badge and the editable-region markers"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    val out = sync.renderDecomposition(FeatureA).unsafeRunSync().fold(e => fail(s"render failed: $e"), identity)
    assert(out.contains("`merged`"), out)
    assert(out.contains("`in progress`"), out)
    assert(out.contains("`pending`"), out)
    assert(out.contains("<!-- forge:decomposition:begin -->"), out)
    assert(out.contains("<!-- forge:decomposition:end -->"), out)
    // The merged piece's optional PR / merge-commit lines render; the pending piece's do not.
    assert(out.contains("- PR: #42"), out)

  // --- D4: feature.designPr {{#if}} branch -----------------------------------

  tempFixture.test("feature.designPr renders the Design PR line when present"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    val out = sync.renderDecomposition(FeatureA).unsafeRunSync().fold(e => fail(s"render failed: $e"), identity)
    assert(out.contains("Design PR: #7"), out)

  tempFixture.test("feature.designPr line is absent when designPr is None"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    store.saveManifest(FeatureA, manifest(Vector(pending("p1", 1)), designPr = None)).unsafeRunSync()
    val out = sync.renderDecomposition(FeatureA).unsafeRunSync().fold(e => fail(s"render failed: $e"), identity)
    assert(!out.contains("Design PR:"), out)

  // --- D4: error channels ----------------------------------------------------

  tempFixture.test("a missing template surfaces TemplateMissing, not a generic failure"): root =>
    val (paths, store, sync) = docSync(root)
    // Template deliberately not installed.
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    sync.renderDecomposition(FeatureA).unsafeRunSync() match
      case Left(DocSyncError.TemplateMissing(p)) =>
        assertEquals(p, paths.userTemplatesDir / "decomposition.md.hbs")
      case other => fail(s"expected TemplateMissing, got $other")

  tempFixture.test("a malformed template surfaces TemplateMalformed"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths, body = "# {{feature.title}}\n{{#if feature.designPr}}\nunterminated\n")
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    sync.renderDecomposition(FeatureA).unsafeRunSync() match
      case Left(DocSyncError.TemplateMalformed(p, _)) =>
        assertEquals(p, paths.userTemplatesDir / "decomposition.md.hbs")
      case other => fail(s"expected TemplateMalformed, got $other")

  tempFixture.test("an unknown helper in the template surfaces RenderFailure"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths, body = "# {{frobnicate feature.title}}\n")
    store.saveManifest(FeatureA, mixedManifest).unsafeRunSync()
    sync.renderDecomposition(FeatureA).unsafeRunSync() match
      case Left(DocSyncError.RenderFailure(_)) => ()
      case other => fail(s"expected RenderFailure, got $other")

  tempFixture.test("a missing manifest surfaces SpecStoreFailure(NotFound)"): root =>
    val (paths, store, sync) = docSync(root)
    installTemplate(paths)
    // Manifest deliberately not saved.
    sync.renderDecomposition(FeatureA).unsafeRunSync() match
      case Left(DocSyncError.SpecStoreFailure(SpecStoreError.NotFound(p))) =>
        assertEquals(p, paths.manifest(FeatureA))
      case other => fail(s"expected SpecStoreFailure(NotFound), got $other")
