package io.forge.core.cost

import io.forge.core.Json.given

import upickle.default.ReadWriter

/** §12 cost increment from a single CLI turn. Aggregated by the orchestrator into per-feature, per-piece, and per-turn
  * totals (see [[CostTotals]]).
  */
final case class Cost(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long,
    usd: BigDecimal
) derives ReadWriter

/** §6 — projection of `cost.update` action-log events stored on `Feature`. Tracks the running USD totals at the three
  * scopes §12 enforces caps against:
  *
  *   - `feature`: total USD spent on this feature so far.
  *   - `piece`: total USD spent on the current piece. Reset to zero when advancing past a piece (next
  *     `PieceImplementing`, `FeatureDone`, `PlanningUpdate`, or `NeedsHumanIntervention`).
  *   - `turn`: USD spent in the current driver turn. Reset on each new turn boundary (driver spawn / resume /
  *     halt-with-question respawn).
  *
  * The §19 `cost.update` event materialises these into `featureTotalUsd` / `pieceTotalUsd` / `turnTotalUsd`.
  */
final case class CostTotals(
    feature: BigDecimal,
    piece: BigDecimal,
    turn: BigDecimal
) derives ReadWriter

object CostTotals:
  val zero: CostTotals = CostTotals(BigDecimal(0), BigDecimal(0), BigDecimal(0))
