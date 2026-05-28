package io.forge.specs

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.manifest.{Manifest, Piece}
import io.forge.core.paths.ForgePaths

import scala.util.control.NonFatal

/** Task 1.4.4 D1 — renders `decomposition.md` from `manifest.json` per **M1** / §5.3.
  *
  * `manifest.json` is the machine source of truth; `decomposition.md` is a rendered view. DocSync is the one-way render
  * (manifest → markdown). The inverse direction (`forge reconcile` reading operator edits back through the
  * editable-region markers) is **M6** / Slice 1.4b Task 1.4.15 and does not live here.
  *
  * `renderDecomposition` is pure over the manifest, so re-rendering an unchanged manifest is byte-identical
  * (idempotent) — the property `forge reconcile` relies on to tell "operator edited the doc" from "Forge re-rendered
  * it". `writeDecomposition` persists the render via [[SpecStore.saveDecomposition]] (atomic temp+rename+fsync).
  */
trait DocSync:
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

  override def writeDecomposition(feature: FeatureId): IO[Either[DocSyncError, Unit]] =
    renderDecomposition(feature).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(body) =>
        store.saveDecomposition(feature, body).map {
          case Left(specErr) => Left(DocSyncError.SpecStoreFailure(specErr))
          case Right(()) => Right(())
        }
    }

  private def renderManifest(manifest: Manifest): IO[Either[DocSyncError, String]] =
    IO.blocking {
      if !os.exists(templateFile) then Left(DocSyncError.TemplateMissing(templateFile))
      else
        readTemplate().flatMap { source =>
          HandlebarsLite.render(source, FileDocSync.context(manifest), FileDocSync.helpers) match
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

  /** The `{{statusBadge this.status}}` helper. Plain inline-code badges (no emoji) so the rendered markdown is stable
    * for the `forge reconcile` byte-diff and renders cleanly in any markdown viewer.
    */
  private[specs] def statusBadge(status: String): String = status match
    case "pending" => "`pending`"
    case "in_progress" => "`in progress`"
    case "merged" => "`merged`"
    case other => s"`$other`"

  private[specs] val helpers: Map[String, String => String] = Map("statusBadge" -> statusBadge)

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
