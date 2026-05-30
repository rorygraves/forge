package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.config.ForgeConfig
import io.forge.core.FeatureId
import io.forge.core.manifest.{Manifest, ManifestPatch, ManifestPatchOp}
import io.forge.core.paths.ForgePaths
import io.forge.specs.{DocSync, DocSyncError, FileDocSync, FileSpecStore, Reconcile, SpecStore, SpecStoreError}

/** Task 1.4.13 **M6** / §5.4 — `forge reconcile <feature>`: import operator edits made in `decomposition.md`'s editable
  * regions back into `manifest.json`.
  *
  * **Apply path (user decision 2026-05-30): direct manifest apply, no FSM transition.** Reconcile is a *planning* edit
  * orthogonal to the lifecycle state — the feature may be `DesignReady`, mid-implementation, anything. The FSM's
  * `PlanningUpdate` state (§14.3) advances to the next piece on accept, which is the refinery flow's semantics and is
  * wrong here, so reconcile applies the validated [[ManifestPatch]] straight through [[SpecStore.saveManifest]]
  * (atomic, §11.5 step 1) and leaves the FSM state untouched. `manifest.json` is the committed source of truth (§4);
  * the state cache rebuilds from the fresh manifest on the next `forge run` / `rebuild-state`, and `forge status`
  * already reads the manifest authoritatively for piece data, so no cache write is needed here. **This is a v1
  * deviation from §5.4's "as a `PlanningUpdate` (§14)" wording (no FSM/log audit action) — filed as S4-9 in
  * `design-1.4.md` §4.**
  *
  * **Algorithm (the [[Reconcile]] docstring has the rationale).** Render the manifest → `canonical`; read the on-disk
  * `decomposition.md` → `onDisk`. Equal ⇒ no edits. Otherwise parse `onDisk`'s editable regions, build the diff patch,
  * apply it to get a candidate manifest, and re-render: a candidate render that matches `onDisk` byte-for-byte proves
  * every on-disk diff was a reconcilable editable-region edit (summary or reorder). Anything else — a touched piece
  * title, status badge, or surrounding prose — leaves a residual the candidate can't reproduce, and we refuse with the
  * offending hunks.
  *
  * **Confirmation is interactive y/N** (§5.4): the patch ops are printed, the operator confirms, and only `y` applies.
  * The [[ReplConsole]] seam (shared with [[SpecRepl]]) keeps the prompt unit-testable.
  */
object ReconcileCommand:

  def execute(
      paths: ForgePaths,
      config: ForgeConfig,
      featureId: FeatureId,
      io: ReplConsole = ReplConsole.real
  ): IO[ExitCode] =
    val _ = config // reconcile needs no config; accepted for handler-signature symmetry with its siblings.
    val specStore = new FileSpecStore(paths)
    run(specStore, new FileDocSync(paths, specStore), featureId, io)

  /** Testable core: same logic, injectable [[SpecStore]] / [[DocSync]] / [[ReplConsole]]. */
  private[command] def run(
      specStore: SpecStore,
      docSync: DocSync,
      featureId: FeatureId,
      io: ReplConsole
  ): IO[ExitCode] =
    specStore.loadManifest(featureId).flatMap {
      case Left(err) =>
        fail(featureId, s"cannot load manifest — ${specErr(err)}. Does the feature exist? Run `forge status`.")
      case Right(manifest) =>
        docSync.renderManifest(manifest).flatMap {
          case Left(err) => fail(featureId, s"cannot render decomposition.md — ${docErr(err)}.")
          case Right(canonical) => withCanonical(specStore, docSync, featureId, manifest, canonical, io)
        }
    }

  private def withCanonical(
      specStore: SpecStore,
      docSync: DocSync,
      featureId: FeatureId,
      manifest: Manifest,
      canonical: String,
      io: ReplConsole
  ): IO[ExitCode] =
    specStore.loadDecomposition(featureId).flatMap {
      case Left(_: SpecStoreError.NotFound) =>
        // No rendered view yet — nothing to reconcile, but the manifest exists, so (re)render it as a courtesy.
        specStore.saveDecomposition(featureId, canonical).flatMap {
          case Left(err) => fail(featureId, s"could not write decomposition.md — ${specErr(err)}.")
          case Right(_) =>
            io.println(
              s"forge reconcile ${featureId.value}: decomposition.md was missing; rendered it from manifest.json. " +
                "No edits to import."
            ).as(ExitCode.Success)
        }
      case Left(err) => fail(featureId, s"cannot read decomposition.md — ${specErr(err)}.")
      case Right(onDisk) if onDisk == canonical =>
        io.println(
          s"forge reconcile ${featureId.value}: no edits to import — decomposition.md already matches manifest.json."
        ).as(ExitCode.Success)
      case Right(onDisk) =>
        reconcileEdits(specStore, docSync, featureId, manifest, onDisk, io)
    }

  private def reconcileEdits(
      specStore: SpecStore,
      docSync: DocSync,
      featureId: FeatureId,
      manifest: Manifest,
      onDisk: String,
      io: ReplConsole
  ): IO[ExitCode] =
    Reconcile.parse(onDisk) match
      case Left(parseErr) => refuse(featureId, parseErr.message)
      case Right(parsed) =>
        val currentIds = manifest.pieces.map(_.id).toSet
        if parsed.order.toSet != currentIds then
          refuse(
            featureId,
            s"decomposition.md adds or removes pieces (got [${parsed.order.map(_.value).mkString(", ")}], " +
              s"expected [${manifest.pieces.map(_.id.value).mkString(", ")}]). `forge reconcile` imports only summary " +
              "edits and reordering — edit manifest.json or pieces/<p>.md to add or remove pieces."
          )
        else
          val patch = Reconcile.buildPatch(manifest, parsed)
          patch.applyTo(manifest) match
            case Left(errs) =>
              refuse(featureId, s"the edits can't be applied to the manifest:\n${errs.map("  - " + _).mkString("\n")}")
            case Right(updated) =>
              docSync.renderManifest(updated).flatMap {
                case Left(err) => fail(featureId, s"cannot render candidate decomposition.md — ${docErr(err)}.")
                case Right(candidate) if candidate == onDisk =>
                  confirmAndApply(specStore, featureId, manifest, patch, updated, io)
                case Right(candidate) =>
                  refuse(
                    featureId,
                    "decomposition.md has edits outside the editable regions (piece id/title, status badge, or the " +
                      "surrounding text). Those aren't reconcilable — edit manifest.json directly, or the underlying " +
                      s"pieces/<p>.md file, then re-run. Offending lines:\n${Reconcile.hunks(candidate, onDisk).mkString("\n")}"
                  )
              }

  private def confirmAndApply(
      specStore: SpecStore,
      featureId: FeatureId,
      manifest: Manifest,
      patch: ManifestPatch,
      updated: Manifest,
      io: ReplConsole
  ): IO[ExitCode] =
    val preview =
      s"forge reconcile ${featureId.value}: the following change(s) will be imported from decomposition.md:\n" +
        patch.ops.map(op => "  - " + describeOp(manifest, op)).mkString("\n")
    io.println(preview) >> io.println("\nApply? (y/N):") >> io.readLine.flatMap {
      case Some(raw) if isYes(raw) =>
        specStore.saveManifest(featureId, updated).flatMap {
          case Left(err) => fail(featureId, s"could not write manifest.json — ${specErr(err)}.")
          case Right(_) =>
            // decomposition.md already equals the candidate render of `updated`, so no re-write is needed.
            io.println(
              s"forge reconcile ${featureId.value}: imported ${patch.ops.size} change(s) into manifest.json."
            ).as(ExitCode.Success)
        }
      case _ =>
        io.println(s"forge reconcile ${featureId.value}: no changes applied.").as(ExitCode.Success)
    }

  private def isYes(raw: String): Boolean =
    val t = raw.trim.toLowerCase
    t == "y" || t == "yes"

  /** Human description of a reconcilable op for the y/N preview. Only `EditPiece(summary)` and `ReorderPieces` reach
    * here (the only ops [[Reconcile.buildPatch]] produces); other variants render generically rather than throw.
    */
  private def describeOp(manifest: Manifest, op: ManifestPatchOp): String = op match
    case ManifestPatchOp.EditPiece(id, _, Some(summary), _, _) =>
      val old = manifest.pieces.find(_.id == id).map(_.summary).getOrElse("")
      s"edit ${id.value} summary: \"${truncate(old)}\" → \"${truncate(summary)}\""
    case ManifestPatchOp.ReorderPieces(newOrder) =>
      s"reorder pieces: [${newOrder.map(_.value).mkString(", ")}]"
    case other => other.toString

  private def truncate(s: String, max: Int = 60): String =
    val one = s.replace("\n", " ").trim
    if one.length <= max then one else one.take(max - 1) + "…"

  private def fail(featureId: FeatureId, detail: String): IO[ExitCode] =
    Console[IO].errorln(s"forge reconcile ${featureId.value}: $detail").as(ExitCode(1))

  private def refuse(featureId: FeatureId, detail: String): IO[ExitCode] =
    Console[IO].errorln(s"forge reconcile ${featureId.value}: $detail").as(ExitCode(1))

  private def specErr(e: SpecStoreError): String = e match
    case SpecStoreError.NotFound(p) => s"not found: $p"
    case SpecStoreError.Malformed(p, c) => s"malformed $p: ${c.getMessage}"
    case SpecStoreError.IoFailure(p, c) => s"io error $p: ${c.getMessage}"

  private def docErr(e: DocSyncError): String = e match
    case DocSyncError.TemplateMissing(p) => s"template missing: $p"
    case DocSyncError.TemplateMalformed(p, c) => s"template malformed $p: ${c.getMessage}"
    case DocSyncError.RenderFailure(c) => s"render failed: ${c.getMessage}"
    case DocSyncError.SpecStoreFailure(s) => specErr(s)
