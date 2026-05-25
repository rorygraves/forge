package io.forge.agents

import io.forge.core.Json.given
import upickle.default.{read, ReadWriter}

/** §7.10(b) per-model rates. Each rate is USD per *million* tokens of the named bucket — the units match the design
  * example verbatim so users can cross-check against OpenAI's published numbers without arithmetic.
  */
final case class ModelPrice(
    inputPerMillionUsd: BigDecimal,
    cachedInputPerMillionUsd: BigDecimal,
    outputPerMillionUsd: BigDecimal,
    reasoningOutputPerMillionUsd: BigDecimal
) derives ReadWriter

/** Per-turn token counts as Codex emits them in `turn.completed.usage` (Slice 0 §2.2). Kept distinct from `Cost`: this
  * is the wire shape Codex gives us; `Cost` is the aggregated action-log shape (§19).
  */
final case class CodexTokens(
    inputTokens: Long,
    cachedInputTokens: Long,
    outputTokens: Long,
    reasoningOutputTokens: Long
)

/** §7.10(b) price table. Loaded from `~/.forge/prices.json` (per-user) or `<repoRoot>/.forge/prices.json` (per-repo
  * override). The connector itself never reaches the filesystem; the orchestrator passes a `PriceTable` in at
  * construction.
  */
final case class PriceTable(
    schemaVersion: Int,
    models: Map[String, ModelPrice]
) derives ReadWriter:

  /** USD for a single Codex turn. `None` means "can't compute cost confidently" — the orchestrator's documented
    * behaviour (§7.10(b)) on `None` is to emit `harness.price_missing` once per `(feature, model)` and proceed with
    * `usd = 0` for budget accounting. Two causes:
    *
    *   1. The model has no entry in the table. 2. The reported usage shape violates OpenAI's documented invariants: any
    *      bucket negative, or `cachedInputTokens > inputTokens`, or `reasoningOutputTokens > outputTokens` — defensive
    *      against a future Codex CLI emitting an unexpected shape; today this case is unreachable.
    *
    * Formula follows OpenAI's reasoning + usage docs: `cached_input_tokens` is a *subset* of `input_tokens`, and
    * `reasoning_output_tokens` is a subset of `output_tokens`. So:
    *
    * ```
    * usd = ((input - cached) × inputRate
    *      + cached                × cachedRate
    *      + (output - reasoning) × outputRate
    *      + reasoning              × reasoningRate) / 1_000_000
    * ```
    *
    * Sources:
    *   - https://platform.openai.com/docs/api-reference/usage/costs
    *   - https://platform.openai.com/docs/guides/reasoning
    */
  def usdFor(model: String, tokens: CodexTokens): Option[BigDecimal] =
    val anyNegative =
      tokens.inputTokens < 0 ||
        tokens.cachedInputTokens < 0 ||
        tokens.outputTokens < 0 ||
        tokens.reasoningOutputTokens < 0
    val subsetViolation =
      tokens.cachedInputTokens > tokens.inputTokens ||
        tokens.reasoningOutputTokens > tokens.outputTokens
    if anyNegative || subsetViolation then None
    else
      models
        .get(model)
        .map: p =>
          val perMillion = (n: Long, rate: BigDecimal) => BigDecimal(n) * rate / PriceTable.MillionTokens
          val uncachedInput = tokens.inputTokens - tokens.cachedInputTokens
          val nonReasoningOutput = tokens.outputTokens - tokens.reasoningOutputTokens
          perMillion(uncachedInput, p.inputPerMillionUsd) +
            perMillion(tokens.cachedInputTokens, p.cachedInputPerMillionUsd) +
            perMillion(nonReasoningOutput, p.outputPerMillionUsd) +
            perMillion(tokens.reasoningOutputTokens, p.reasoningOutputPerMillionUsd)

object PriceTable:
  /** §7.10(b) — pinned by Forge v1. */
  val CurrentSchemaVersion: Int = 1

  private val MillionTokens: BigDecimal = BigDecimal(1_000_000)

  /** No entries; every `usdFor` returns `None`. Used when the price file is missing or unreadable so the orchestrator
    * can keep running.
    */
  val empty: PriceTable = PriceTable(CurrentSchemaVersion, Map.empty)

  /** Three-way load outcome so callers can distinguish "file absent" (warn once at startup, §7.10(b)) from "file
    * present but malformed" (loud error, almost certainly a hand-edit gone wrong).
    */
  enum LoadOutcome:
    case Missing
    case Loaded(table: PriceTable)
    case Malformed(error: String)

  /** Read a price table from disk. Never throws. */
  def load(path: os.Path): LoadOutcome =
    if !os.exists(path) then LoadOutcome.Missing
    else
      try
        val raw = os.read(path)
        val parsed = read[PriceTable](raw)
        if parsed.schemaVersion != CurrentSchemaVersion then
          LoadOutcome.Malformed(
            s"unexpected schemaVersion ${parsed.schemaVersion} (expected $CurrentSchemaVersion)"
          )
        else LoadOutcome.Loaded(parsed)
      catch case t: Throwable => LoadOutcome.Malformed(t.getMessage)
