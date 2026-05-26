package io.forge.core.manifest

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths
import io.forge.core.state.RebuildError

/** PR-E E4 — load a feature's `manifest.json` (§4 committed source of truth) into a validated [[Manifest]].
  *
  * Decoupled from the rebuild pipeline as a small trait so tests can hand in fixture manifests without touching disk.
  * The default file impl is [[FileManifestStore]].
  *
  * `manifest.json` is the §4 source of truth; this trait is the only path through which `forge-core` reads it. The
  * Slice-4 `SpecStore` in `forge-specs` is the *write* counterpart (rendering + DocSync). The rebuild reader only ever
  * needs read access, which this trait provides.
  *
  * Failures (file missing, JSON malformed, [[Manifest.validate]] errors) surface as [[RebuildError.ManifestLoadFailed]]
  * on the `Either` channel so callers can lift them into the rebuild pipeline's error vocabulary without re-discovering
  * the cause.
  */
trait ManifestStore:
  def load(id: FeatureId): IO[Either[RebuildError.ManifestLoadFailed, Manifest]]

/** File-backed [[ManifestStore]] — reads `paths.manifest(id)`, runs [[Manifest.validate]], and surfaces failures as
  * [[RebuildError.ManifestLoadFailed]].
  */
final class FileManifestStore(paths: ForgePaths) extends ManifestStore:
  override def load(id: FeatureId): IO[Either[RebuildError.ManifestLoadFailed, Manifest]] =
    IO.blocking {
      val file = paths.manifest(id)
      try
        if !os.exists(file) then
          Left(RebuildError.ManifestLoadFailed(id, new java.nio.file.NoSuchFileException(file.toString)))
        else
          val text = os.read(file)
          val parsed = Manifest.fromJson(text)
          parsed.validate match
            case Right(m) => Right(m)
            case Left(errs) =>
              Left(RebuildError.ManifestLoadFailed(id, new IllegalStateException(errs.mkString("; "))))
      catch case t: Throwable => Left(RebuildError.ManifestLoadFailed(id, t))
    }
