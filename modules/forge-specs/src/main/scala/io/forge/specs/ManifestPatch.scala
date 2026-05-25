package io.forge.specs

import io.forge.core.*
import io.forge.core.Json.given

import upickle.default.ReadWriter

/** §6 ManifestPatchOp. A patch is a sequence of these; the refinery and the
  * `forge reconcile` flow both produce them. */
enum ManifestPatchOp derives ReadWriter:
  /** Insert a new piece. `after = None` means "at the head of the pending
    * region" (only valid when no pieces are merged). `after = Some(p)`
    * requires `p` to be either a pending piece or the *last* merged piece — *
    * inserting after any other merged piece would split the merged prefix. */
  case AddPiece(after: Option[PieceId], piece: Piece)

  /** Remove a pending piece. Merged pieces are immutable (§5.1, §14.3). */
  case RemovePiece(id: PieceId)

  /** Edit the human-meaningful fields of a pending piece. Each field is
    * optional: `None` leaves it unchanged. */
  case EditPiece(
      id: PieceId,
      title: Option[String],
      summary: Option[String],
      specPath: Option[String],
      acceptanceHash: Option[String]
  )

  /** Replace the piece ordering. Must be a permutation of the current ids
    * and must preserve the merged prefix (§5.5). */
  case ReorderPieces(newOrder: Vector[PieceId])

/** §6 — a named bundle of ops. `validate` runs every op against the **original**
  * manifest (not against the result of preceding ops in the same patch). This
  * means a single patch that adds a piece and then reorders to include it
  * will be rejected; refinery output and reconcile output are both expected
  * to keep ops independent. */
final case class ManifestPatch(reason: String, ops: Vector[ManifestPatchOp]) derives ReadWriter:

  def validate(manifest: Manifest): Either[Vector[String], ManifestPatch] =
    val currentIds   = manifest.pieces.map(_.id.value).toSet
    val mergedIds    = manifest.merged.map(_.id.value).toSet
    val mergedCount  = manifest.merged.size
    val currentOrder = manifest.pieces.map(_.id.value)
    val lastMerged   = manifest.merged.lastOption.map(_.id.value)

    val errs = Vector.newBuilder[String]

    ops.foreach:
      case ManifestPatchOp.AddPiece(after, piece) =>
        if currentIds(piece.id.value) then
          errs += s"AddPiece: piece '${piece.id.value}' already exists"
        if piece.status != PieceStatus.Pending then
          errs += s"AddPiece: new piece '${piece.id.value}' must have status=pending (got ${piece.status.asString})"
        if piece.baseSha.isDefined then
          errs += s"AddPiece: new piece '${piece.id.value}' must have null baseSha"
        after match
          case Some(aid) =>
            if !currentIds(aid.value) then
              errs += s"AddPiece: 'after' references unknown piece '${aid.value}'"
            else if mergedIds(aid.value) && !lastMerged.contains(aid.value) then
              errs += s"AddPiece: 'after' refers to merged piece '${aid.value}' that is not the last merged piece"
          case None =>
            if mergedCount > 0 then
              errs += s"AddPiece: cannot insert at head when ${mergedCount} pieces are merged"

      case ManifestPatchOp.RemovePiece(id) =>
        if !currentIds(id.value) then
          errs += s"RemovePiece: unknown piece '${id.value}'"
        else if mergedIds(id.value) then
          errs += s"RemovePiece: cannot remove merged piece '${id.value}'"

      case ManifestPatchOp.EditPiece(id, _, _, _, _) =>
        if !currentIds(id.value) then
          errs += s"EditPiece: unknown piece '${id.value}'"
        else if mergedIds(id.value) then
          errs += s"EditPiece: cannot edit merged piece '${id.value}'"

      case ManifestPatchOp.ReorderPieces(newOrder) =>
        val newIds = newOrder.map(_.value)
        if newIds.distinct.size != newIds.size then
          errs += "ReorderPieces: contains duplicates"
        else if newIds.size != currentOrder.size then
          errs += s"ReorderPieces: size ${newIds.size} does not match current ${currentOrder.size}"
        else if newIds.toSet != currentIds then
          errs += "ReorderPieces: not a permutation of current piece ids"
        else
          val expected = currentOrder.take(mergedCount)
          val actual   = newIds.take(mergedCount)
          if actual != expected then
            errs += s"ReorderPieces: merged prefix changed (expected [${expected.mkString(", ")}]; got [${actual.mkString(", ")}])"

    val list = errs.result()
    if list.isEmpty then Right(this) else Left(list)
