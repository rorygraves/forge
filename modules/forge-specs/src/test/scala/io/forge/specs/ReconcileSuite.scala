package io.forge.specs

import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Sha}
import io.forge.core.manifest.{Manifest, ManifestPatchOp, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Task 1.4.13 **M6** / §5.4 — the pure [[Reconcile]] parse/diff half of `forge reconcile`.
  *
  * Two layers: (1) `parse` / `buildPatch` / `hunks` exercised directly against rendered fixtures, and (2) a
  * render-backed round-trip through the real [[FileDocSync]] + shipped `decomposition.md.hbs` (the same setup as
  * `DocSyncSuite`) proving `parse` is the exact inverse of the render — an unedited rendered doc parses to the
  * manifest's own order + summaries, so `buildPatch` is empty. The handler's apply path + interactive y/N live in
  * `ReconcileCommandSuite` (forge-app).
  */
class ReconcileSuite extends munit.FunSuite:

  private val FeatureA: FeatureId = FeatureId("stripe-webhook")
  private val Sha40: Sha = Sha("0123456789abcdef0123456789abcdef01234567")

  private val realTemplate: String =
    val in = getClass.getResourceAsStream("/templates/decomposition.md.hbs")
    assert(in != null, "decomposition.md.hbs not on the test classpath")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-reconcile-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  // --- manifest builders (mirror DocSyncSuite) -------------------------------

  private def pending(id: String, order: Int, summary: String): Piece =
    Piece(
      PieceId(id),
      order,
      s"Piece $id",
      summary,
      s"specs/x/$id.md",
      "sha256:" + ("0" * 64),
      PieceStatus.Pending,
      None,
      None,
      None,
      None,
      0
    )

  private def merged(id: String, order: Int, pr: Int, summary: String): Piece =
    pending(id, order, summary).copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha40),
      prNumber = Some(PrNumber(pr)),
      mergeCommit = Some(Sha40),
      mergedAt = Some(Instant.parse("2026-05-28T00:00:00Z"))
    )

  private def manifest(pieces: Vector[Piece], designPr: Option[PrNumber] = Some(PrNumber(7))): Manifest =
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

  private val threePending: Manifest =
    manifest(
      Vector(pending("p1", 1, "first summary"), pending("p2", 2, "second summary"), pending("p3", 3, "third summary"))
    )

  private def render(m: Manifest, root: os.Path): String =
    val paths = new ForgePaths(repoRoot = root, home = root)
    os.makeDir.all(paths.userTemplatesDir)
    os.write.over(paths.userTemplatesDir / "decomposition.md.hbs", realTemplate)
    val store = new FileSpecStore(paths)
    store.saveManifest(FeatureA, m).unsafeRunSync()
    new FileDocSync(paths, store).renderManifest(m).unsafeRunSync().fold(e => fail(s"render: $e"), identity)

  // --- parse round-trip (the inverse-of-render guarantee) --------------------

  tempFixture.test("parse of an unedited render recovers the manifest order and summaries"): root =>
    val doc = render(threePending, root)
    Reconcile.parse(doc) match
      case Left(e) => fail(s"parse failed: ${e.message}")
      case Right(parsed) =>
        assertEquals(parsed.order, Vector(PieceId("p1"), PieceId("p2"), PieceId("p3")))
        assertEquals(parsed.summaries(PieceId("p1")), "first summary")
        assertEquals(parsed.summaries(PieceId("p2")), "second summary")
        assertEquals(parsed.summaries(PieceId("p3")), "third summary")

  tempFixture.test("buildPatch of an unedited render is empty (idempotence)"): root =>
    val doc = render(threePending, root)
    val parsed = Reconcile.parse(doc).fold(e => fail(e.message), identity)
    assertEquals(Reconcile.buildPatch(threePending, parsed).ops, Vector.empty)

  tempFixture.test("an edited summary line becomes a single EditPiece(summary)"): root =>
    val doc = render(threePending, root).replace("second summary", "an edited second summary")
    val parsed = Reconcile.parse(doc).fold(e => fail(e.message), identity)
    val ops = Reconcile.buildPatch(threePending, parsed).ops
    assertEquals(
      ops,
      Vector(ManifestPatchOp.EditPiece(PieceId("p2"), None, Some("an edited second summary"), None, None))
    )

  tempFixture.test("a reordered render becomes a single ReorderPieces"): root =>
    // The on-disk doc is the render of a reordered manifest; parsing it yields the new order.
    val reordered = manifest(
      Vector(pending("p1", 1, "first summary"), pending("p3", 2, "third summary"), pending("p2", 3, "second summary"))
    )
    val doc = render(reordered, root)
    val parsed = Reconcile.parse(doc).fold(e => fail(e.message), identity)
    val ops = Reconcile.buildPatch(threePending, parsed).ops
    assertEquals(ops, Vector(ManifestPatchOp.ReorderPieces(Vector(PieceId("p1"), PieceId("p3"), PieceId("p2")))))

  tempFixture.test("a combined summary edit + reorder yields EditPiece(s) before ReorderPieces"): root =>
    val reordered = manifest(
      Vector(pending("p1", 1, "first summary"), pending("p3", 2, "third summary"), pending("p2", 3, "second summary"))
    )
    val doc = render(reordered, root).replace("first summary", "edited first")
    val parsed = Reconcile.parse(doc).fold(e => fail(e.message), identity)
    val ops = Reconcile.buildPatch(threePending, parsed).ops
    assertEquals(ops.head, ManifestPatchOp.EditPiece(PieceId("p1"), None, Some("edited first"), None, None))
    assertEquals(ops.last, ManifestPatchOp.ReorderPieces(Vector(PieceId("p1"), PieceId("p3"), PieceId("p2"))))

  // --- parse structural refusals ---------------------------------------------

  test("parse refuses a doc with no order markers"):
    Reconcile.parse("# Title\n\nsome hand-written notes\n") match
      case Left(e) => assert(e.message.contains("forge:order-start"), e.message)
      case Right(p) => fail(s"expected refusal, got $p")

  tempFixture.test("parse refuses a doc with a duplicated order-start marker"): root =>
    val doc = render(threePending, root)
    val dup = doc.replace("<!-- forge:order-start -->", "<!-- forge:order-start -->\n<!-- forge:order-start -->")
    Reconcile.parse(dup) match
      case Left(e) => assert(e.message.contains("duplicated") || e.message.contains("2 start"), e.message)
      case Right(p) => fail(s"expected refusal, got $p")

  // --- hunks -----------------------------------------------------------------

  test("hunks reports each differing line with expected vs on-disk text"):
    val expected = "alpha\nbeta\ngamma"
    val actual = "alpha\nBETA\ngamma"
    val h = Reconcile.hunks(expected, actual)
    assertEquals(h.size, 1)
    assert(h.head.contains("line 2"), h.head)
    assert(h.head.contains("beta") && h.head.contains("BETA"), h.head)

  test("hunks caps long diffs"):
    val expected = (1 to 50).map(i => s"e$i").mkString("\n")
    val actual = (1 to 50).map(i => s"a$i").mkString("\n")
    val h = Reconcile.hunks(expected, actual, max = 5)
    assertEquals(h.size, 6) // 5 lines + the "… and N more" tail
    assert(h.last.contains("more differing line"), h.last)
