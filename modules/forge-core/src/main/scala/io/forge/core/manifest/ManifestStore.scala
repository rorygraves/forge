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
  * Failures (file missing, JSON malformed, [[Manifest.validate]] errors, identity mismatch between the requested
  * `FeatureId` and the manifest's `featureId`, or an unsupported `schemaVersion`) surface as
  * [[RebuildError.ManifestLoadFailed]] on the `Either` channel so callers can lift them into the rebuild pipeline's
  * error vocabulary without re-discovering the cause.
  */
trait ManifestStore:
  def load(id: FeatureId): IO[Either[RebuildError.ManifestLoadFailed, Manifest]]

/** File-backed [[ManifestStore]] — reads `paths.manifest(id)`, runs [[Manifest.validate]], cross-checks `schemaVersion`
  * against [[Manifest.CurrentSchemaVersion]] and the manifest's `featureId` against the caller's `id`, and surfaces
  * every failure mode as [[RebuildError.ManifestLoadFailed]].
  *
  * The identity check matters because `paths.manifest(id)` resolves to `.forge/specs/<id>/manifest.json` — a hand-edit
  * or stale-file swap could leave a manifest with a different `featureId` at that path, and without this check
  * `RebuildState.run` would happily seed `Feature.initial(id, manifest)` with a foreign manifest. The `schemaVersion`
  * check is the same shape: v1 pins to a single schema version, and a manifest that claims a future version is one we
  * shouldn't silently downgrade.
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
          if parsed.schemaVersion != Manifest.CurrentSchemaVersion then
            Left(
              RebuildError.ManifestLoadFailed(
                id,
                new IllegalStateException(
                  s"manifest schemaVersion=${parsed.schemaVersion} is not supported " +
                    s"(forge-core expects ${Manifest.CurrentSchemaVersion})"
                )
              )
            )
          else if parsed.featureId != id then
            Left(
              RebuildError.ManifestLoadFailed(
                id,
                new IllegalStateException(
                  s"manifest featureId=${parsed.featureId} does not match requested featureId=$id " +
                    s"(file at ${file.toString})"
                )
              )
            )
          else
            parsed.validate match
              case Right(m) => Right(m)
              case Left(errs) =>
                Left(RebuildError.ManifestLoadFailed(id, new IllegalStateException(errs.mkString("; "))))
      catch case t: Throwable => Left(RebuildError.ManifestLoadFailed(id, t))
    }
