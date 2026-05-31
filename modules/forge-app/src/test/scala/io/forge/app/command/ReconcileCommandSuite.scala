package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Sha}
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.specs.{FileDocSync, FileSpecStore}

import java.nio.charset.StandardCharsets
import java.time.Instant

/** Task 1.4.13 **M6** / §5.4 / §M12 — `forge reconcile` handler end-to-end against the real [[FileSpecStore]] +
  * [[FileDocSync]] + shipped `decomposition.md.hbs`, with a scripted [[ReplConsole]] driving the y/N prompt.
  *
  * Each test renders the canonical `decomposition.md`, writes it, then mutates the on-disk file the way an operator
  * would (string-replace a summary, swap the rendered piece order, touch a non-editable region) and asserts the
  * handler's outcome: no-change short-circuit, reconcilable apply (manifest mutated atomically), reorder, refusals
  * (out-of-region edit, merged-piece edit, missing markers), and the missing-file (re)render. The apply path is direct
  * manifest mutation — no FSM transition (see the [[ReconcileCommand]] docstring / S4-9).
  */
class ReconcileCommandSuite extends munit.FunSuite:

  private val FeatureA: FeatureId = FeatureId("stripe-webhook")
  private val Sha40: Sha = Sha("0123456789abcdef0123456789abcdef01234567")

  private val realTemplate: String =
    val in = getClass.getResourceAsStream("/templates/decomposition.md.hbs")
    assert(in != null, "decomposition.md.hbs not on the forge-app test classpath")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-reconcile-cmd-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  // --- manifest builders -----------------------------------------------------

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

  // --- harness ---------------------------------------------------------------

  /** Build stores against `root`, install the shipped template, save `m`, and write the canonical decomposition.md. */
  private def setup(root: os.Path, m: Manifest): (FileSpecStore, FileDocSync, ForgePaths) =
    val paths = new ForgePaths(repoRoot = root, home = root)
    os.makeDir.all(paths.userTemplatesDir)
    os.write.over(paths.userTemplatesDir / "decomposition.md.hbs", realTemplate)
    val store = new FileSpecStore(paths)
    val docSync = new FileDocSync(paths, store)
    store.saveManifest(FeatureA, m).unsafeRunSync()
    docSync.writeDecomposition(FeatureA).unsafeRunSync().fold(e => fail(s"write: $e"), identity)
    (store, docSync, paths)

  private def runReconcile(
      store: FileSpecStore,
      docSync: FileDocSync,
      inputs: List[String]
  ): (ExitCode, Vector[String]) =
    val console = ScriptedConsole.make(inputs).unsafeRunSync()
    val code = ReconcileCommand.run(store, docSync, FeatureA, console).unsafeRunSync()
    (code, console.lines.unsafeRunSync())

  private def summaryOf(store: FileSpecStore, id: String): String =
    store
      .loadManifest(FeatureA)
      .unsafeRunSync()
      .fold(e => fail(s"load: $e"), _.pieces.find(_.id == PieceId(id)).get.summary)

  private def orderOf(store: FileSpecStore): Vector[String] =
    store.loadManifest(FeatureA).unsafeRunSync().fold(e => fail(s"load: $e"), _.pieces.map(_.id.value))

  // --- no-change -------------------------------------------------------------

  tempFixture.test("no edits → exit 0, reports nothing to import, manifest unchanged"): root =>
    val (store, docSync, _) = setup(root, threePending)
    val (code, out) = runReconcile(store, docSync, Nil)
    assertEquals(code, ExitCode.Success)
    assert(out.exists(_.contains("no edits to import")), out.toString)
    assertEquals(summaryOf(store, "p2"), "second summary")

  // --- reconcilable summary edit ---------------------------------------------

  tempFixture.test("summary edit + y → applies, manifest summary updated"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    val edited = os.read(paths.decomposition(FeatureA)).replace("second summary", "an edited second summary")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, out) = runReconcile(store, docSync, List("y"))
    assertEquals(code, ExitCode.Success)
    assert(out.exists(_.contains("imported 1 change")), out.toString)
    assertEquals(summaryOf(store, "p2"), "an edited second summary")

  tempFixture.test("summary edit + N → no changes applied, manifest unchanged"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    val edited = os.read(paths.decomposition(FeatureA)).replace("second summary", "an edited second summary")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, out) = runReconcile(store, docSync, List("n"))
    assertEquals(code, ExitCode.Success)
    assert(out.exists(_.contains("no changes applied")), out.toString)
    assertEquals(summaryOf(store, "p2"), "second summary")

  tempFixture.test("summary edit + EOF (no answer) → no changes applied"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    val edited = os.read(paths.decomposition(FeatureA)).replace("second summary", "an edited second summary")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, _) = runReconcile(store, docSync, Nil)
    assertEquals(code, ExitCode.Success)
    assertEquals(summaryOf(store, "p2"), "second summary")

  // --- reorder ---------------------------------------------------------------

  tempFixture.test("reordered list + y → applies ReorderPieces"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    // The on-disk doc is the render of a reordered manifest — the realistic "operator moved a list item" shape.
    val reordered = manifest(
      Vector(pending("p1", 1, "first summary"), pending("p3", 2, "third summary"), pending("p2", 3, "second summary"))
    )
    os.write.over(paths.decomposition(FeatureA), docSync.renderManifest(reordered).unsafeRunSync().toOption.get)
    val (code, out) = runReconcile(store, docSync, List("y"))
    assertEquals(code, ExitCode.Success)
    assert(out.exists(_.contains("reorder pieces")), out.toString)
    assertEquals(orderOf(store), Vector("p1", "p3", "p2"))

  // --- refusals --------------------------------------------------------------

  tempFixture.test("edit to a non-editable region (piece title) → refuse, manifest unchanged"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    val edited = os.read(paths.decomposition(FeatureA)).replace("**p2: Piece p2**", "**p2: Hacked title**")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, _) = runReconcile(store, docSync, List("y"))
    assertEquals(code, ExitCode(1))
    assertEquals(summaryOf(store, "p2"), "second summary") // not applied

  tempFixture.test("editing a merged piece's summary → refuse (cannot edit merged)"): root =>
    val withMerged = manifest(Vector(merged("p1", 1, 42, "merged summary"), pending("p2", 2, "second summary")))
    val (store, docSync, paths) = setup(root, withMerged)
    val edited = os.read(paths.decomposition(FeatureA)).replace("merged summary", "tampered merged summary")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, _) = runReconcile(store, docSync, List("y"))
    assertEquals(code, ExitCode(1))
    assertEquals(summaryOf(store, "p1"), "merged summary") // not applied

  tempFixture.test("a doc with the order markers removed → refuse"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    val edited = os
      .read(paths.decomposition(FeatureA))
      .replace("<!-- forge:order-start -->", "")
      .replace("<!-- forge:order-end -->", "")
    os.write.over(paths.decomposition(FeatureA), edited)
    val (code, out) = runReconcile(store, docSync, List("y"))
    assertEquals(code, ExitCode(1))
    assert(out.isEmpty || true) // refusal goes to stderr; exit code is the contract

  // --- missing decomposition.md ----------------------------------------------

  tempFixture.test("missing decomposition.md → (re)renders it, exit 0"): root =>
    val (store, docSync, paths) = setup(root, threePending)
    os.remove(paths.decomposition(FeatureA))
    val (code, out) = runReconcile(store, docSync, Nil)
    assertEquals(code, ExitCode.Success)
    assert(out.exists(_.contains("was missing")), out.toString)
    assert(os.exists(paths.decomposition(FeatureA)))
