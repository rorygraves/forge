package io.forge.specs

/** Task 1.4.4 D3 — failure channel for [[DocSync]].
  *
  *   - `TemplateMissing` — the `decomposition.md.hbs` template is absent from `~/.forge/templates/`. Distinct from a
  *     parse error so the orchestrator can tell the operator to re-run the asset bootstrap (Task 1.4.1's
  *     `AssetInstaller`) rather than hunt for a syntax bug.
  *   - `TemplateMalformed` — the template exists but its Handlebars-subset shape is invalid (unbalanced / unknown
  *     tags). Carries the path + the parse cause.
  *   - `RenderFailure` — render-time failure against an otherwise-parseable template (unknown helper, a non-scalar
  *     where a scalar was needed, or an I/O error reading the template file).
  *   - `SpecStoreFailure` — the underlying [[SpecStore]] load (`manifest.json`) or save (`decomposition.md`) failed;
  *     the wrapped [[SpecStoreError]] carries the path and cause.
  */
sealed trait DocSyncError

object DocSyncError:
  final case class TemplateMissing(path: os.Path) extends DocSyncError
  final case class TemplateMalformed(path: os.Path, cause: Throwable) extends DocSyncError
  final case class RenderFailure(cause: Throwable) extends DocSyncError
  final case class SpecStoreFailure(spec: SpecStoreError) extends DocSyncError
