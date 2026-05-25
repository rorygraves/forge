package io.forge.core

import scala.util.matching.Regex

/** Opaque identifiers used throughout Forge. Each carries a validation rule
  * sourced from the design doc; `apply` enforces it eagerly. */

opaque type FeatureId = String
object FeatureId:
  /** §5.2 explicit-id form: lowercase letter, then up to 49 chars of `[a-z0-9-]`. */
  val Pattern: Regex = "^[a-z][a-z0-9-]{0,49}$".r

  def fromString(s: String): Either[String, FeatureId] =
    if Pattern.matches(s) then Right(s)
    else Left(s"invalid FeatureId '$s' (must match ${Pattern.regex})")

  def apply(s: String): FeatureId =
    fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)

  extension (id: FeatureId) def value: String = id

opaque type PieceId = String
object PieceId:
  /** Piece IDs are stable once assigned; §5.1 uses `p1, p2, ...` */
  val Pattern: Regex = "^p[0-9]+$".r

  def fromString(s: String): Either[String, PieceId] =
    if Pattern.matches(s) then Right(s)
    else Left(s"invalid PieceId '$s' (must match ${Pattern.regex})")

  def apply(s: String): PieceId =
    fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)

  extension (id: PieceId) def value: String = id

opaque type PrNumber = Int
object PrNumber:
  def apply(n: Int): PrNumber =
    require(n > 0, s"PR number must be positive, got $n")
    n
  extension (n: PrNumber) def value: Int = n

opaque type BranchName = String
object BranchName:
  def apply(s: String): BranchName =
    require(s.nonEmpty, "branch name must not be empty")
    s
  extension (b: BranchName) def value: String = b

opaque type Sha = String
object Sha:
  val Pattern: Regex = "^[0-9a-f]{7,40}$".r
  def fromString(s: String): Either[String, Sha] =
    if Pattern.matches(s) then Right(s)
    else Left(s"invalid SHA '$s' (must match ${Pattern.regex})")
  def apply(s: String): Sha =
    fromString(s).fold(msg => throw IllegalArgumentException(msg), identity)
  extension (s: Sha) def value: String = s
