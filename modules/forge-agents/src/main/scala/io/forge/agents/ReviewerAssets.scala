package io.forge.agents

/** §7.1 / §17 Slice 1 — paths a reviewer connector needs to make a one-shot call.
  *
  * Two assets per method:
  *
  *   - **Schema file** (shared across reviewers, §17 "Schemas are shared `design-review.json`, `code-review.json`,
  *     `refine.json`"). Claude is given the schema's contents inline as a `--json-schema '<json>'` argument; Codex is
  *     given the path as `--output-schema <path>`. Either way the connector takes a path and reads/passes it
  *     accordingly.
  *   - **System-prompt file** (reviewer-specific — one per CLI × method, mirroring how driver prompts split per
  *     `~/.forge/prompts/{specify,implement,fixup}.<driver>.md`). Claude reads it via `--system-prompt-file <path>`;
  *     Codex prepends it into the user prompt via [[CodexPrompt.withSystemBlock]] (§7.10(a)).
  *
  * The orchestrator builds one value and hands it to each connector at construction time; the connector itself never
  * resolves `~/.forge/...` paths.
  */
final case class ReviewerAssets(
    designReview: ReviewerAssets.PerMethod,
    prReview: ReviewerAssets.PerMethod,
    refine: ReviewerAssets.PerMethod
)

object ReviewerAssets:

  /** Per-method asset pair: schema (JSON Schema file) + system prompt (Markdown). */
  final case class PerMethod(
      schema: os.Path,
      systemPrompt: os.Path
  )
