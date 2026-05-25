package io.forge.core

import java.time.Instant
import upickle.default.{ReadWriter, readwriter}

/** JSON codecs that can't live in a companion object.
  *
  * For opaque types (FeatureId, PieceId, …) the companion isn't usable: an
  * opaque type and its underlying are structurally identical inside the
  * defining scope, so `readwriter[String].bimap(_.value, FeatureId(_))` makes
  * upickle's implicit search for `ReadWriter[String]` find the partially
  * defined `ReadWriter[FeatureId]` recursively. Defining them in a separate
  * object hides the opaque-alias view and resolves the ambiguity.
  *
  * For `java.time.Instant` there is no companion at all to hook into.
  *
  * Consumers `import io.forge.core.Json.given` to bring everything here into
  * implicit scope for derivation. */
object Json:
  given ReadWriter[Instant]    = readwriter[String].bimap(_.toString, Instant.parse(_))
  given ReadWriter[FeatureId]  = readwriter[String].bimap(_.value, FeatureId(_))
  given ReadWriter[PieceId]    = readwriter[String].bimap(_.value, PieceId(_))
  given ReadWriter[PrNumber]   = readwriter[Int].bimap(_.value, PrNumber(_))
  given ReadWriter[BranchName] = readwriter[String].bimap(_.value, BranchName(_))
  given ReadWriter[Sha]        = readwriter[String].bimap(_.value, Sha(_))
