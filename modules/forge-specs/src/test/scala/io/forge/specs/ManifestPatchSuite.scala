package io.forge.specs

import io.forge.core.*
import io.forge.specs.ManifestPatchOp.*
import java.time.Instant

class ManifestPatchSuite extends munit.FunSuite:

  // --- fixtures --------------------------------------------------------------

  private val sampleSha = Sha("abcdef0123456789abcdef0123456789abcdef01")
  private val sampleAt  = Instant.parse("2026-05-25T00:00:00Z")

  private def piece(id: String, status: PieceStatus = PieceStatus.Pending): Piece =
    val merged = status == PieceStatus.Merged
    Piece(
      id = PieceId(id),
      order = id.tail.toInt,
      title = s"piece $id",
      summary = "",
      specPath = s".forge/specs/x/pieces/$id.md",
      acceptanceHash = "sha256:" + "0" * 64,
      status = status,
      baseSha = if status != PieceStatus.Pending then Some(sampleSha) else None,
      prNumber = if merged then Some(PrNumber(100 + id.tail.toInt)) else None,
      mergeCommit = if merged then Some(sampleSha) else None,
      mergedAt = if merged then Some(sampleAt) else None,
      attempts = 0
    )

  private def manifest(pieces: Piece*): Manifest =
    Manifest(
      schemaVersion = 1,
      featureId = FeatureId("x"),
      title = "X",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = pieces.toVector
    )

  private val allPending     = manifest(piece("p1"), piece("p2"), piece("p3"))
  private val oneMerged      = manifest(piece("p1", PieceStatus.Merged), piece("p2"), piece("p3"))
  private val twoMerged      = manifest(
    piece("p1", PieceStatus.Merged), piece("p2", PieceStatus.Merged),
    piece("p3"), piece("p4")
  )

  // --- AddPiece --------------------------------------------------------------

  test("AddPiece: valid new piece with after=last-merged"):
    val newPiece = piece("p5")
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p2")), newPiece)))
    assert(patch.validate(twoMerged).isRight)

  test("AddPiece: valid new piece appended after pending"):
    val newPiece = piece("p4")
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p3")), newPiece)))
    assert(patch.validate(oneMerged).isRight)

  test("AddPiece: after=None is fine when nothing is merged"):
    val patch = ManifestPatch("add", Vector(AddPiece(None, piece("p4"))))
    assert(patch.validate(allPending).isRight)

  test("AddPiece: after=None is rejected when pieces are merged"):
    val patch = ManifestPatch("add", Vector(AddPiece(None, piece("p5"))))
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("cannot insert at head")))

  test("AddPiece: rejects collision with existing id"):
    val patch = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p2")), piece("p3"))))
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("already exists")))

  test("AddPiece: rejects non-pending new piece"):
    val newPiece = piece("p5", PieceStatus.InProgress)
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p2")), newPiece)))
    val errs     = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("must have status=pending")))

  test("AddPiece: rejects new piece with baseSha set"):
    val newPiece = piece("p5").copy(baseSha = Some(sampleSha))
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p2")), newPiece)))
    val errs     = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("null baseSha")))

  test("AddPiece: rejects 'after' that points to a non-last merged piece"):
    val newPiece = piece("p5")
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p1")), newPiece)))
    val errs     = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("not the last merged piece")))

  test("AddPiece: rejects 'after' that references unknown piece"):
    val newPiece = piece("p5")
    val patch    = ManifestPatch("add", Vector(AddPiece(Some(PieceId("p99")), newPiece)))
    val errs     = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("unknown piece")))

  // --- RemovePiece -----------------------------------------------------------

  test("RemovePiece: removes a pending piece"):
    val patch = ManifestPatch("drop", Vector(RemovePiece(PieceId("p3"))))
    assert(patch.validate(twoMerged).isRight)

  test("RemovePiece: rejects removing a merged piece"):
    val patch = ManifestPatch("drop", Vector(RemovePiece(PieceId("p1"))))
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("cannot remove merged piece")))

  test("RemovePiece: rejects unknown piece"):
    val patch = ManifestPatch("drop", Vector(RemovePiece(PieceId("p99"))))
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("unknown piece")))

  // --- EditPiece -------------------------------------------------------------

  test("EditPiece: edits a pending piece"):
    val patch = ManifestPatch("edit", Vector(EditPiece(PieceId("p3"), Some("New title"), None, None, None)))
    assert(patch.validate(twoMerged).isRight)

  test("EditPiece: rejects editing a merged piece"):
    val patch = ManifestPatch("edit", Vector(EditPiece(PieceId("p1"), Some("X"), None, None, None)))
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("cannot edit merged piece")))

  // --- ReorderPieces — §5.5 invariant ---------------------------------------

  test("ReorderPieces: shuffling the pending tail is allowed"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p1"), PieceId("p2"), PieceId("p4"), PieceId("p3"))))
    )
    assert(patch.validate(twoMerged).isRight)

  test("ReorderPieces: reordering across the merged boundary is rejected"):
    // Move 'p2' (merged) after 'p1' (merged) is fine (same prefix), but swapping
    // p2 to position 3 changes the merged prefix and must be rejected.
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p1"), PieceId("p3"), PieceId("p2"), PieceId("p4"))))
    )
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("merged prefix changed")))

  test("ReorderPieces: swapping merged pieces among themselves is rejected"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p2"), PieceId("p1"), PieceId("p3"), PieceId("p4"))))
    )
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("merged prefix changed")))

  test("ReorderPieces: non-permutation is rejected"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p1"), PieceId("p2"), PieceId("p3"), PieceId("p99"))))
    )
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("not a permutation")))

  test("ReorderPieces: duplicates are rejected"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p1"), PieceId("p2"), PieceId("p3"), PieceId("p3"))))
    )
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("duplicates")))

  test("ReorderPieces: size mismatch is rejected"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p1"), PieceId("p2"))))
    )
    val errs  = patch.validate(twoMerged).swap.toOption.get
    assert(errs.exists(_.contains("does not match current")))

  test("ReorderPieces: when nothing is merged the whole list may be reordered"):
    val patch = ManifestPatch("reorder",
      Vector(ReorderPieces(Vector(PieceId("p3"), PieceId("p1"), PieceId("p2"))))
    )
    assert(patch.validate(allPending).isRight)

  // --- JSON round-trip -------------------------------------------------------

  test("ManifestPatch JSON round-trips through upickle"):
    val patch = ManifestPatch(
      reason = "refinery: split p2 into auth + storage",
      ops = Vector(
        EditPiece(PieceId("p2"), title = Some("Authentication path"), None, None, None),
        AddPiece(Some(PieceId("p2")), piece("p3"))
      )
    )
    val json    = upickle.default.write(patch)
    val parsed  = upickle.default.read[ManifestPatch](json)
    assertEquals(parsed, patch)
