package io.forge.specs

/** Named helpers the shipped `.hbs` templates bind via `{{ helper path }}` (see [[HandlebarsLite]]). One registry so
  * every template render draws from the same set rather than each call site re-declaring helpers: `decomposition.md`
  * (Task 1.4.4 [[FileDocSync]]) and the answer / PR-body templates (orchestrator, Task 1.4.10).
  */
object TemplateHelpers:

  /** `{{statusBadge this.status}}` — inline-code piece-status badge for `decomposition.md`. Plain text (no emoji) so
    * the rendered markdown is stable for the `forge reconcile` byte-diff.
    */
  def statusBadge(status: String): String = status match
    case "pending" => "`pending`"
    case "in_progress" => "`in progress`"
    case "merged" => "`merged`"
    case other => s"`$other`"

  /** `{{questionNumber @index}}` — 1-based question number for the human-facing answer files, derived from the 0-based
    * `@index` [[HandlebarsLite]] exposes. This keeps `@index` honest (0-based, matching Handlebars) while the rendered
    * Q&A reads naturally (Q1, Q2, …) — rather than expanding the template language with `{{@index + 1}}` arithmetic.
    * Non-numeric input passes through unchanged (defensive; `@index` is always numeric).
    */
  def questionNumber(index: String): String = index.toIntOption.fold(index)(n => (n + 1).toString)

  val all: Map[String, String => String] = Map(
    "statusBadge" -> statusBadge,
    "questionNumber" -> questionNumber
  )
