package io.forge.specs

/** Task 1.4.3 C4 — failure channel for [[SpecStore]].
  *
  * Three variants are enough for the §4 source-of-truth boundary:
  *
  *   - `NotFound` — the requested file is absent from disk. Returned when an orchestrator command asks for a feature /
  *     piece that hasn't been written yet (e.g. a `forge status` before `forge new` lands).
  *   - `Malformed` — the file exists but its bytes are not parseable. Covers uPickle JSON decode failures on
  *     `manifest.json` and, by extension, `Manifest.validate` failures (a well-formed JSON document that violates §5.1
  *     invariants is still "shape-malformed" from the caller's perspective). Plain-markdown loads (`design.md`,
  *     `decomposition.md`, `pieces/<p>.md`) currently never produce this since they're byte-passthrough — kept on the
  *     ADT so any future stricter markdown decoder lands here without an ADT change.
  *   - `IoFailure` — any other underlying I/O exception (permission denied, disk full, parent unreachable, fsync
  *     failed). Carries the path and cause so the orchestrator can render a useful operator message.
  */
sealed trait SpecStoreError:
  def path: os.Path

object SpecStoreError:
  final case class NotFound(path: os.Path) extends SpecStoreError
  final case class Malformed(path: os.Path, cause: Throwable) extends SpecStoreError
  final case class IoFailure(path: os.Path, cause: Throwable) extends SpecStoreError
