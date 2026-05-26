package io.forge.git.watcher

/** PR-B B2 — typed failures from [[PrSnapshotDecoder]]. Three variants cover the wire-shape problems that aren't
  * "transient `gh` exit" (those land as `GhError` from the CLI layer). The decoder is pure, so each `DecodeError`
  * pinpoints a JSON path + a reason; callers route them to the action log.
  *
  *   - [[DecodeError.MissingField]] — a required JSON path was absent or `null`.
  *   - [[DecodeError.UnknownEnumValue]] — a string field carried a value outside the closed enum. `knownValues` is
  *     spelled out so a future GitHub addition (e.g. a new `CheckConclusion`) is diagnosable without grepping enum
  *     source.
  *   - [[DecodeError.MalformedShape]] — JSON shape mismatch (e.g. array where object expected, number where string
  *     expected).
  */
sealed trait DecodeError extends Product with Serializable:
  def message: String

object DecodeError:

  final case class MissingField(path: String) extends DecodeError:
    def message: String = s"missing field: $path"

  final case class UnknownEnumValue(field: String, observed: String, knownValues: Vector[String]) extends DecodeError:
    def message: String =
      s"unknown enum value '$observed' for $field (known: ${knownValues.mkString(", ")})"

  final case class MalformedShape(path: String, expected: String, observed: String) extends DecodeError:
    def message: String = s"malformed at $path: expected $expected, observed $observed"
