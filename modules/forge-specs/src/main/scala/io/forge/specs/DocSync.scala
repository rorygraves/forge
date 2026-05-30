package io.forge.specs

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.manifest.{Manifest, Piece}
import io.forge.core.paths.ForgePaths

import scala.util.control.NonFatal

/** Task 1.4.4 D1 — renders `decomposition.md` from `manifest.json` per **M1** / §5.3.
  *
  * `manifest.json` is the machine source of truth; `decomposition.md` is a rendered view. DocSync is the one-way render
  * (manifest → markdown). The inverse direction — `forge reconcile` reading operator edits back through the
  * editable-region markers (**M6** / §5.4) — parses in [[Reconcile]] and is driven by the `forge reconcile` handler; it
  * reuses [[renderManifest]] here to round-trip a candidate manifest against the on-disk file.
  *
  * `renderDecomposition` is pure over the manifest, so re-rendering an unchanged manifest is byte-identical
  * (idempotent) — the property `forge reconcile` relies on to tell "operator edited the doc" from "Forge re-rendered
  * it". `writeDecomposition` persists the render via [[SpecStore.saveDecomposition]] (atomic temp+rename+fsync).
  */
trait DocSync:
  /** Render a *given* manifest directly, without loading it from the store. The reconcile flow (**M6** / §5.4) uses
    * this to render a *candidate* manifest (the manifest as it would be after a parsed editable-region edit) and
    * round-trip it against the on-disk `decomposition.md` — the candidate render matching the on-disk file is exactly
    * the proof that every on-disk diff is explained by reconcilable editable-region edits and nothing else.
    */
  def renderManifest(manifest: Manifest): IO[Either[DocSyncError, String]]
  def renderDecomposition(feature: FeatureId): IO[Either[DocSyncError, String]]
  def writeDecomposition(feature: FeatureId): IO[Either[DocSyncError, Unit]]

/** File-backed [[DocSync]]. Reads the template from `ForgePaths.userTemplatesDir`, the manifest via the injected
  * [[SpecStore]], and writes the rendered `decomposition.md` back through the same store so the atomic-write invariant
  * is shared with every other spec write.
  */
final class FileDocSync(paths: ForgePaths, store: SpecStore) extends DocSync:

  private val templateFile: os.Path = paths.userTemplatesDir / "decomposition.md.hbs"

  override def renderDecomposition(feature: FeatureId): IO[Either[DocSyncError, String]] =
    store.loadManifest(feature).flatMap {
      case Left(err) => IO.pure(Left(DocSyncError.SpecStoreFailure(err)))
      case Right(manifest) => renderManifest(manifest)
    }

  override def renderManifest(manifest: Manifest): IO[Either[DocSyncError, String]] =
    renderManifestBlocking(manifest)

  override def writeDecomposition(feature: FeatureId): IO[Either[DocSyncError, Unit]] =
    renderDecomposition(feature).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(body) =>
        store.saveDecomposition(feature, body).map {
          case Left(specErr) => Left(DocSyncError.SpecStoreFailure(specErr))
          case Right(()) => Right(())
        }
    }

  private def renderManifestBlocking(manifest: Manifest): IO[Either[DocSyncError, String]] =
    IO.blocking {
      if !os.exists(templateFile) then Left(DocSyncError.TemplateMissing(templateFile))
      else
        readTemplate().flatMap { source =>
          HandlebarsLite.render(source, FileDocSync.context(manifest), TemplateHelpers.all) match
            case Right(out) => Right(out)
            case Left(HandlebarsLite.RenderError.Parse(msg)) =>
              Left(DocSyncError.TemplateMalformed(templateFile, new IllegalStateException(msg)))
            case Left(HandlebarsLite.RenderError.Eval(msg)) =>
              Left(DocSyncError.RenderFailure(new IllegalStateException(msg)))
        }
    }

  private def readTemplate(): Either[DocSyncError, String] =
    try Right(os.read(templateFile))
    catch case NonFatal(t) => Left(DocSyncError.RenderFailure(t))

object FileDocSync:

  /** Builds the render context the `decomposition.md.hbs` template binds against: `feature.*` at the root and a
    * `pieces` array of per-piece objects. Optional manifest fields map to `Absent` so the template's `{{#if}}` guards
    * behave.
    */
  private[specs] def context(manifest: Manifest): HandlebarsLite.Value.Obj =
    import HandlebarsLite.Value.*
    Obj(
      Map(
        "feature" -> Obj(
          Map(
            "id" -> Str(manifest.featureId.value),
            "title" -> Str(manifest.title),
            "baseBranch" -> Str(manifest.baseBranch.value),
            "branchPrefix" -> Str(manifest.branchPrefix),
            "designPr" -> opt(manifest.designPr.map(_.value.toString))
          )
        ),
        "pieces" -> Arr(manifest.pieces.map(pieceContext))
      )
    )

  private def pieceContext(piece: Piece): HandlebarsLite.Value.Obj =
    import HandlebarsLite.Value.*
    Obj(
      Map(
        "order" -> Str(piece.order.toString),
        "id" -> Str(piece.id.value),
        "title" -> Str(piece.title),
        "summary" -> Str(piece.summary),
        "status" -> Str(piece.status.asString),
        "prNumber" -> opt(piece.prNumber.map(_.value.toString)),
        "mergeCommit" -> opt(piece.mergeCommit.map(_.value))
      )
    )

  private def opt(value: Option[String]): HandlebarsLite.Value =
    value.fold(HandlebarsLite.Value.Absent)(HandlebarsLite.Value.Str.apply)
