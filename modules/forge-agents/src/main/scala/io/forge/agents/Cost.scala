package io.forge.agents

/** §12 cost increment from a single CLI turn. Aggregated by the orchestrator into per-feature, per-piece, and per-turn
  * totals.
  */
final case class Cost(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long,
    usd: BigDecimal
)
