package io.forge.specs

import io.forge.core.*
import io.forge.core.Json.given

import upickle.default.{ReadWriter, read, write}

/** §5.1 manifest — machine source of truth for a feature's pieces. */
final case class Manifest(
    schemaVersion: Int,
    featureId: FeatureId,
    title: String,
    baseBranch: BranchName,
    branchPrefix: String,
    mode: Mode,
    designPr: Option[PrNumber],
    pieces: Vector[Piece]
) derives ReadWriter:

  /** Run the §5.1 invariants. Returns the manifest unchanged on success, or
    * a non-empty list of error messages. */
  def validate: Either[Vector[String], Manifest] =
    val errs = Vector.newBuilder[String]

    val ids = pieces.map(_.id.value)
    if ids.distinct.size != ids.size then errs += "duplicate piece ids in manifest"

    pieces.foreach: p =>
      if p.status != PieceStatus.Pending && p.baseSha.isEmpty then
        errs += s"piece ${p.id.value}: status=${p.status.asString} requires non-null baseSha"
      if p.status == PieceStatus.Merged then
        if p.prNumber.isEmpty then    errs += s"piece ${p.id.value}: merged status requires prNumber"
        if p.mergeCommit.isEmpty then errs += s"piece ${p.id.value}: merged status requires mergeCommit"
        if p.mergedAt.isEmpty then    errs += s"piece ${p.id.value}: merged status requires mergedAt"

    val list = errs.result()
    if list.isEmpty then Right(this) else Left(list)

  /** §5.1: design branch derived from prefix + featureId. */
  def designBranch: BranchName =
    BranchName(s"$branchPrefix/${featureId.value}/design")

  /** §5.1: piece branch derived from prefix + featureId + pieceId. */
  def pieceBranch(p: PieceId): BranchName =
    BranchName(s"$branchPrefix/${featureId.value}/${p.value}")

  /** §11.3: snapshot tag for design revision N. */
  def designSnapshotTag(round: Int): String =
    s"$branchPrefix/_snapshots/${featureId.value}/design-r$round"

  /** Pieces still to be implemented, in manifest order. */
  def pending: Vector[Piece] = pieces.filter(_.status == PieceStatus.Pending)

  /** Pieces already merged, in manifest order. */
  def merged: Vector[Piece] = pieces.filter(_.status == PieceStatus.Merged)

  /** §11.7 next-piece selector: first pending piece in manifest order. */
  def nextPending: Option[PieceId] = pieces.find(_.status == PieceStatus.Pending).map(_.id)

object Manifest:
  /** §5.1 schema version pinned by Forge v1. */
  val CurrentSchemaVersion: Int = 1

  def fromJson(s: String): Manifest = read[Manifest](s)
  def toJson(m: Manifest, indent: Int = 2): String = write(m, indent = indent)
