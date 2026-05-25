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

  /** Run the §5.1 + §5.5 invariants. Returns the manifest unchanged on
    * success, or a non-empty list of error messages.
    *
    * Per-piece field rules (§5.1):
    *   - pending:     baseSha, prNumber, mergeCommit, mergedAt all null
    *   - in_progress: baseSha non-null; mergeCommit, mergedAt null
    *                  (prNumber may be set after PR creation, §11.4 step 6)
    *   - merged:      every field set (baseSha + prNumber + mergeCommit + mergedAt)
    *
    * Structural rules:
    *   - piece ids are unique
    *   - merged pieces form a contiguous prefix of the piece list (§5.5) */
  def validate: Either[Vector[String], Manifest] =
    val errs = Vector.newBuilder[String]

    val ids = pieces.map(_.id.value)
    if ids.distinct.size != ids.size then errs += "duplicate piece ids in manifest"

    pieces.foreach: p =>
      val tag = s"piece ${p.id.value}"
      p.status match
        case PieceStatus.Pending =>
          if p.baseSha.isDefined     then errs += s"$tag: pending status requires null baseSha"
          if p.prNumber.isDefined    then errs += s"$tag: pending status requires null prNumber"
          if p.mergeCommit.isDefined then errs += s"$tag: pending status requires null mergeCommit"
          if p.mergedAt.isDefined    then errs += s"$tag: pending status requires null mergedAt"
        case PieceStatus.InProgress =>
          if p.baseSha.isEmpty       then errs += s"$tag: in_progress status requires non-null baseSha"
          if p.mergeCommit.isDefined then errs += s"$tag: in_progress status requires null mergeCommit"
          if p.mergedAt.isDefined    then errs += s"$tag: in_progress status requires null mergedAt"
        case PieceStatus.Merged =>
          if p.baseSha.isEmpty     then errs += s"$tag: merged status requires non-null baseSha"
          if p.prNumber.isEmpty    then errs += s"$tag: merged status requires prNumber"
          if p.mergeCommit.isEmpty then errs += s"$tag: merged status requires mergeCommit"
          if p.mergedAt.isEmpty    then errs += s"$tag: merged status requires mergedAt"

    // §5.5: merged pieces must form a contiguous prefix.
    val totalMerged = pieces.count(_.status == PieceStatus.Merged)
    val prefixLen   = pieces.takeWhile(_.status == PieceStatus.Merged).size
    if prefixLen != totalMerged then
      errs += s"§5.5 violated: merged pieces must form a contiguous prefix " +
        s"(total merged=$totalMerged, contiguous at head=$prefixLen)"

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
