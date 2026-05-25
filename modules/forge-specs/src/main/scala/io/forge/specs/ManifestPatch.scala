package io.forge.specs

import io.forge.core.*
import io.forge.core.Json.given

import upickle.default.ReadWriter

/** §6 ManifestPatchOp. A patch is a sequence of these; the refinery and the `forge reconcile` flow both produce them.
  */
enum ManifestPatchOp derives ReadWriter:
  /** Insert a new piece. `after = None` means "at the head of the list"; with any merged pieces present the resulting
    * manifest fails `Manifest.validate` (§5.5), and `applyTo` surfaces that as an op-local error.
    *
    * `after = Some(p)` inserts immediately after `p`. The op rejects `p` pointing to a merged piece that isn't the last
    * merged piece, because the insertion would split the merged prefix.
    */
  case AddPiece(after: Option[PieceId], piece: Piece)

  /** Remove a pending piece. Merged pieces are immutable (§5.1, §14.3). */
  case RemovePiece(id: PieceId)

  /** Edit the human-meaningful fields of a pending piece. Each field is optional: `None` leaves it unchanged.
    */
  case EditPiece(
      id: PieceId,
      title: Option[String],
      summary: Option[String],
      specPath: Option[String],
      acceptanceHash: Option[String]
  )

  /** Replace the piece ordering. Must be a duplicate-free permutation of the current piece ids and must preserve the
    * merged prefix (§5.5).
    */
  case ReorderPieces(newOrder: Vector[PieceId])

object ManifestPatchOp:

  extension (op: ManifestPatchOp)
    /** Short tag used in patch-level error messages. */
    def kind: String = op match
      case _: ManifestPatchOp.AddPiece => "AddPiece"
      case _: ManifestPatchOp.RemovePiece => "RemovePiece"
      case _: ManifestPatchOp.EditPiece => "EditPiece"
      case _: ManifestPatchOp.ReorderPieces => "ReorderPieces"

    /** Apply this op to `m` and return the new manifest, or an op-local error.
      *
      * This is the lowest layer of patch validation. Op-local rules give targeted errors ("cannot edit merged piece
      * p1"); structural invariants (every field/status combination, the merged-prefix contiguity, the order-field
      * mirror) are caught by `Manifest.validate` after the op lands. `ManifestPatch.applyTo` threads both layers
      * per-op.
      *
      * Structural ops (Add, Remove, Reorder) call `withRenumberedOrder` so the `Piece.order` mirror of vector position
      * stays in sync regardless of what `order` the caller put on the new/existing pieces.
      */
    def applyTo(m: Manifest): Either[String, Manifest] = op match
      case ManifestPatchOp.AddPiece(after, piece) =>
        if m.pieces.exists(_.id == piece.id) then Left(s"piece '${piece.id.value}' already exists")
        else if piece.status != PieceStatus.Pending then
          Left(s"new piece '${piece.id.value}' must have status=pending (got ${piece.status.asString})")
        else
          after match
            case None =>
              if m.pieces.exists(_.status == PieceStatus.Merged) then
                Left("cannot insert at head while pieces are merged; specify the last merged piece as 'after'")
              else Right(m.copy(pieces = piece +: m.pieces).withRenumberedOrder)
            case Some(aid) =>
              m.pieces.indexWhere(_.id == aid) match
                case -1 => Left(s"'after' references unknown piece '${aid.value}'")
                case i =>
                  val mergedPrefix = m.pieces.takeWhile(_.status == PieceStatus.Merged)
                  val isInPrefix = mergedPrefix.exists(_.id == aid)
                  val isLastMerged = mergedPrefix.lastOption.exists(_.id == aid)
                  if isInPrefix && !isLastMerged then
                    Left(s"'after' refers to merged piece '${aid.value}' that is not the last merged piece")
                  else
                    val (before, tail) = m.pieces.splitAt(i + 1)
                    Right(m.copy(pieces = before ++ Vector(piece) ++ tail).withRenumberedOrder)

      case ManifestPatchOp.RemovePiece(id) =>
        m.pieces.indexWhere(_.id == id) match
          case -1 => Left(s"unknown piece '${id.value}'")
          case i if m.pieces(i).status == PieceStatus.Merged =>
            Left(s"cannot remove merged piece '${id.value}'")
          case i =>
            Right(m.copy(pieces = m.pieces.patch(i, Nil, 1)).withRenumberedOrder)

      case ManifestPatchOp.EditPiece(id, title, summary, specPath, acceptanceHash) =>
        m.pieces.indexWhere(_.id == id) match
          case -1 => Left(s"unknown piece '${id.value}'")
          case i if m.pieces(i).status == PieceStatus.Merged =>
            Left(s"cannot edit merged piece '${id.value}'")
          case i =>
            val p = m.pieces(i)
            val edited = p.copy(
              title = title.getOrElse(p.title),
              summary = summary.getOrElse(p.summary),
              specPath = specPath.getOrElse(p.specPath),
              acceptanceHash = acceptanceHash.getOrElse(p.acceptanceHash)
            )
            // EditPiece is structure-preserving; no renumber needed.
            Right(m.copy(pieces = m.pieces.updated(i, edited)))

      case ManifestPatchOp.ReorderPieces(newOrder) =>
        val currentIds = m.pieces.map(_.id)
        if newOrder.distinct.size != newOrder.size then Left("contains duplicates")
        else if newOrder.size != currentIds.size then
          Left(s"size ${newOrder.size} does not match current ${currentIds.size}")
        else if newOrder.toSet != currentIds.toSet then Left("not a permutation of current piece ids")
        else
          val mergedCount = m.pieces.count(_.status == PieceStatus.Merged)
          val expectedPrefix = currentIds.take(mergedCount).map(_.value)
          val actualPrefix = newOrder.take(mergedCount).map(_.value)
          if actualPrefix != expectedPrefix then
            Left(
              s"merged prefix changed (expected [${expectedPrefix.mkString(", ")}]; got [${actualPrefix.mkString(", ")}])"
            )
          else
            val byId = m.pieces.iterator.map(p => p.id -> p).toMap
            Right(m.copy(pieces = newOrder.map(byId(_))).withRenumberedOrder)

/** §6 — a named bundle of ops applied as a unit. `validate` and `applyTo` thread the ops sequentially: each op sees the
  * manifest that the previous ops produced, and the post-op manifest is re-checked against `Manifest.validate` before
  * the next op runs.
  *
  * This is what catches internally contradictory patches — two AddPiece for the same id, RemovePiece(p3) followed by
  * EditPiece(p3), etc. — that a one-shot validator against the original manifest would let through.
  */
final case class ManifestPatch(reason: String, ops: Vector[ManifestPatchOp]) derives ReadWriter:

  /** Apply each op in order. On any failure (op-local error or post-op `Manifest.validate` rejection) stop, prefix the
    * error(s) with the failing op's index/kind, and return them; remaining ops are not attempted.
    */
  def applyTo(initial: Manifest): Either[Vector[String], Manifest] =
    ops.iterator.zipWithIndex.foldLeft[Either[Vector[String], Manifest]](Right(initial)):
      case (left @ Left(_), _) => left
      case (Right(m), (op, idx)) =>
        op.applyTo(m) match
          case Left(err) =>
            Left(Vector(s"op[$idx] ${op.kind}: $err"))
          case Right(m2) =>
            m2.validate match
              case Left(errs) => Left(errs.map(e => s"op[$idx] ${op.kind}: $e"))
              case Right(_) => Right(m2)

  /** Run `applyTo` without committing the result — the gate used by `forge reconcile` and the §14.3 refinery flow.
    */
  def validate(manifest: Manifest): Either[Vector[String], ManifestPatch] =
    applyTo(manifest).map(_ => this)
