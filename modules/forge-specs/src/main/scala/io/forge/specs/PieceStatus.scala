package io.forge.specs

import upickle.default.{readwriter, ReadWriter}

/** §5.1 piece-lifecycle status. Transitions (the only ones permitted): pending -> in_progress (set when baseSha is
  * recorded; §11.4 step 1) in_progress -> merged (atomic with prNumber/mergeCommit/mergedAt; §11.5 step 1)
  */
enum PieceStatus:
  case Pending
  case InProgress
  case Merged

object PieceStatus:
  def fromString(s: String): Either[String, PieceStatus] = s match
    case "pending" => Right(PieceStatus.Pending)
    case "in_progress" => Right(PieceStatus.InProgress)
    case "merged" => Right(PieceStatus.Merged)
    case other => Left(s"unknown piece status '$other'")

  extension (s: PieceStatus)
    def asString: String = s match
      case Pending => "pending"
      case InProgress => "in_progress"
      case Merged => "merged"

  given ReadWriter[PieceStatus] = readwriter[String].bimap(
    _.asString,
    s => fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  )
