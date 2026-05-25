package io.forge.specs

import io.forge.core.*
import java.time.Instant

class ManifestSuite extends munit.FunSuite:

  // Helpers ---

  private def loadFixture(): String =
    val stream = getClass.getResourceAsStream("/manifest-fixture.json")
    assert(stream != null, "manifest-fixture.json not found on test classpath")
    try scala.io.Source.fromInputStream(stream).mkString
    finally stream.close()

  private val mergeSha = Sha("abcdef0123456789abcdef0123456789abcdef01")

  private val basePending = Piece(
    id = PieceId("p1"),
    order = 1,
    title = "T",
    summary = "S",
    specPath = "x.md",
    acceptanceHash = "sha256:" + "a" * 64,
    status = PieceStatus.Pending,
    baseSha = None,
    prNumber = None,
    mergeCommit = None,
    mergedAt = None,
    attempts = 0
  )

  private val emptyManifest = Manifest(
    schemaVersion = 1,
    featureId = FeatureId("hello"),
    title = "Hello",
    baseBranch = BranchName("main"),
    branchPrefix = "forge",
    mode = Mode.ClaudeDriver,
    designPr = None,
    pieces = Vector.empty
  )

  // Codec round-trip ---

  test("Manifest deserialises the §5.1 fixture and round-trips back identically"):
    val raw      = loadFixture()
    val parsed   = Manifest.fromJson(raw)
    assertEquals(parsed.featureId.value, "stripe-webhook")
    assertEquals(parsed.mode, Mode.ClaudeDriver)
    assertEquals(parsed.designPr.map(_.value), Some(4290))
    assertEquals(parsed.pieces.size, 1)

    val p1 = parsed.pieces.head
    assertEquals(p1.id.value, "p1")
    assertEquals(p1.status, PieceStatus.Pending)
    assertEquals(p1.baseSha, None)
    assertEquals(p1.attempts, 0)

    // Round-trip equality on the parsed model
    val rendered = Manifest.toJson(parsed)
    assertEquals(Manifest.fromJson(rendered), parsed)

  test("Manifest reports a round-trip for a fully populated merged piece"):
    val merged = basePending.copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha("abc1234")),
      prNumber = Some(PrNumber(42)),
      mergeCommit = Some(mergeSha),
      mergedAt = Some(Instant.parse("2026-05-25T15:42:18.341Z")),
      attempts = 1
    )
    val m       = emptyManifest.copy(pieces = Vector(merged))
    val rendered = Manifest.toJson(m)
    assertEquals(Manifest.fromJson(rendered), m)

  // Branch / tag derivation (§5.1) ---

  test("branch names derive from prefix + featureId + pieceId"):
    val m = emptyManifest.copy(featureId = FeatureId("foo"), branchPrefix = "forge")
    assertEquals(m.designBranch.value, "forge/foo/design")
    assertEquals(m.pieceBranch(PieceId("p3")).value, "forge/foo/p3")
    assertEquals(m.designSnapshotTag(2), "forge/_snapshots/foo/design-r2")

  // Validator (§5.1 invariants) ---

  test("validator accepts a pending-only manifest with all baseShas null"):
    assert(emptyManifest.copy(pieces = Vector(basePending)).validate.isRight)

  test("validator rejects an in-progress piece with null baseSha"):
    val bad = basePending.copy(status = PieceStatus.InProgress, baseSha = None)
    val errs = emptyManifest.copy(pieces = Vector(bad)).validate.swap.toOption.get
    assert(errs.exists(_.contains("requires non-null baseSha")))

  test("validator rejects a merged piece missing prNumber/mergeCommit/mergedAt"):
    val bad = basePending.copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha("abc1234")),
      prNumber = None, mergeCommit = None, mergedAt = None
    )
    val errs = emptyManifest.copy(pieces = Vector(bad)).validate.swap.toOption.get
    assert(errs.exists(_.contains("requires prNumber")))
    assert(errs.exists(_.contains("requires mergeCommit")))
    assert(errs.exists(_.contains("requires mergedAt")))

  test("validator rejects duplicate piece ids"):
    val a = basePending
    val b = basePending.copy(order = 2)
    val errs = emptyManifest.copy(pieces = Vector(a, b)).validate.swap.toOption.get
    assert(errs.exists(_.contains("duplicate piece ids")))

  // Query helpers ---

  test("nextPending picks the first pending piece in manifest order"):
    val merged = basePending.copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha("abc1234")),
      prNumber = Some(PrNumber(1)),
      mergeCommit = Some(mergeSha),
      mergedAt = Some(Instant.parse("2026-05-25T00:00:00Z"))
    )
    val p2 = basePending.copy(id = PieceId("p2"), order = 2)
    val p3 = basePending.copy(id = PieceId("p3"), order = 3)
    val m  = emptyManifest.copy(pieces = Vector(merged, p2, p3))
    assertEquals(m.nextPending.map(_.value), Some("p2"))
