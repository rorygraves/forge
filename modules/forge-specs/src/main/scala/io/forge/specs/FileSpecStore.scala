package io.forge.specs

import cats.effect.IO
import io.forge.core.{FeatureId, PieceId}
import io.forge.core.manifest.Manifest
import io.forge.core.paths.ForgePaths

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import scala.util.control.NonFatal

/** Task 1.4.3 C3 — file-backed [[SpecStore]] under `ForgePaths.{manifest,design,decomposition,pieceSpec}`.
  *
  * Writes mirror `io.forge.core.state.FileStateCache.save`: sibling temp file with `SYNC` (file-contents fsync),
  * `Files.move` with `ATOMIC_MOVE`, then fsync the parent directory so the rename's directory entry is durable. The
  * atomic-write helper is thinly duplicated rather than lifted to a shared module — both call sites are short,
  * `forge-specs` is the only other consumer in v1, and consolidation can happen in a follow-up if a third caller
  * appears.
  *
  * Manifest reads and writes both run [[checkManifest]] — the same three guards
  * [[io.forge.core.manifest.ManifestStore]] applies on the rebuild-read path: `schemaVersion` is supported, the
  * embedded `featureId` matches the requested id (a hand-edit / stale-file swap at `.forge/specs/<id>/manifest.json`
  * could otherwise have a foreign id), and [[Manifest.validate]] passes. A failure surfaces as `Malformed` with the
  * cause's message describing which guard tripped. `saveManifest` runs the guards **before** touching disk, so the
  * committed source of truth can never be left in a state the next `loadManifest` rejects. Plain-markdown loads are
  * byte-passthrough.
  */
final class FileSpecStore(paths: ForgePaths) extends SpecStore:

  // --- manifest.json (typed) -------------------------------------------------

  override def loadManifest(feature: FeatureId): IO[Either[SpecStoreError, Manifest]] =
    val file = paths.manifest(feature)
    IO.blocking {
      if !os.exists(file) then Left(SpecStoreError.NotFound(file))
      else
        try checkManifest(file, feature, Manifest.fromJson(os.read(file)))
        catch case NonFatal(t) => Left(SpecStoreError.Malformed(file, t))
    }

  override def saveManifest(feature: FeatureId, manifest: Manifest): IO[Either[SpecStoreError, Unit]] =
    val file = paths.manifest(feature)
    checkManifest(file, feature, manifest) match
      case Left(err) => IO.pure(Left(err))
      case Right(valid) => atomicWriteBytes(file, Manifest.toJson(valid).getBytes(StandardCharsets.UTF_8))

  // --- design.md / decomposition.md / pieces/<p>.md (markdown passthrough) ---

  override def loadDesign(feature: FeatureId): IO[Either[SpecStoreError, String]] =
    loadText(paths.design(feature))

  override def saveDesign(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]] =
    atomicWriteBytes(paths.design(feature), body.getBytes(StandardCharsets.UTF_8))

  override def loadDecomposition(feature: FeatureId): IO[Either[SpecStoreError, String]] =
    loadText(paths.decomposition(feature))

  override def saveDecomposition(feature: FeatureId, body: String): IO[Either[SpecStoreError, Unit]] =
    atomicWriteBytes(paths.decomposition(feature), body.getBytes(StandardCharsets.UTF_8))

  override def loadPieceSpec(feature: FeatureId, piece: PieceId): IO[Either[SpecStoreError, String]] =
    loadText(paths.pieceSpec(feature, piece))

  override def savePieceSpec(feature: FeatureId, piece: PieceId, body: String): IO[Either[SpecStoreError, Unit]] =
    atomicWriteBytes(paths.pieceSpec(feature, piece), body.getBytes(StandardCharsets.UTF_8))

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Shared load + save guard for `manifest.json` — mirrors `FileManifestStore.load` (forge-core). Checks
    * `schemaVersion`, then the embedded `featureId` against the requested id, then [[Manifest.validate]]; the first
    * failure wins. Pure (no I/O), so `saveManifest` can run it before touching disk.
    */
  private def checkManifest(file: os.Path, id: FeatureId, m: Manifest): Either[SpecStoreError, Manifest] =
    if m.schemaVersion != Manifest.CurrentSchemaVersion then
      Left(
        SpecStoreError.Malformed(
          file,
          new IllegalStateException(
            s"manifest schemaVersion=${m.schemaVersion} is not supported " +
              s"(forge-specs expects ${Manifest.CurrentSchemaVersion})"
          )
        )
      )
    else if m.featureId != id then
      Left(
        SpecStoreError.Malformed(
          file,
          new IllegalStateException(
            s"manifest featureId=${m.featureId} does not match requested featureId=$id (file at $file)"
          )
        )
      )
    else
      m.validate match
        case Right(valid) => Right(valid)
        case Left(errs) => Left(SpecStoreError.Malformed(file, new IllegalStateException(errs.mkString("; "))))

  private def loadText(file: os.Path): IO[Either[SpecStoreError, String]] =
    IO.blocking {
      if !os.exists(file) then Left(SpecStoreError.NotFound(file))
      else
        try Right(os.read(file))
        catch case NonFatal(t) => Left(SpecStoreError.IoFailure(file, t))
    }

  private def atomicWriteBytes(target: os.Path, bytes: Array[Byte]): IO[Either[SpecStoreError, Unit]] =
    IO.blocking {
      val parent = target / os.up
      if !os.exists(parent) then os.makeDir.all(parent)
      // Sibling temp file with a UUID suffix keeps `Files.move(ATOMIC_MOVE)` inside the same filesystem (POSIX rename
      // atomicity is only guaranteed within a single device) and avoids collisions if a prior crash left a stray temp.
      val temp = parent / s".${target.last}.tmp.${UUID.randomUUID()}"
      try
        val _ = Files.write(
          temp.toNIO,
          bytes,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          StandardOpenOption.SYNC
        )
        val _ = Files.move(
          temp.toNIO,
          target.toNIO,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        // Best-effort parent fsync so the rename's directory entry is durable across a crash. On platforms where
        // opening a directory as a FileChannel doesn't work, swallow rather than failing an otherwise-successful save.
        fsyncDirectory(parent)
        Right(())
      catch
        case NonFatal(t) =>
          // Best-effort cleanup of the stray temp file; original target (if any) is untouched by the failed move.
          try
            if os.exists(temp) then
              val _ = os.remove(temp)
              ()
          catch case NonFatal(_) => ()
          Left(SpecStoreError.IoFailure(target, t))
    }

  private def fsyncDirectory(dir: os.Path): Unit =
    try
      val ch = FileChannel.open(dir.toNIO, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    catch case NonFatal(_) => ()
