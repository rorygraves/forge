package io.forge.agents

import io.forge.core.cost.Cost

/** §7.1 / S4-3 — a reviewer/sensor one-shot result paired with the cost the CLI envelope reported for it.
  *
  * The driver path already surfaces cost on the event stream (`AgentEvent.CostUpdate`); the one-shot reviewer/sensor
  * methods historically returned only the typed value and discarded the cost the envelope already carried (Claude:
  * `total_cost_usd` / `usage` / `modelUsage`; Codex: the `turn.completed.usage` line). Carrying it here lets the
  * orchestrator fold reviewer spend into `Feature.cost` exactly like driver spend (carry-forward **S4-3** closed).
  *
  * `cost` is `Option` because it genuinely can be absent: an `is_error` / cost-less Claude envelope yields `None`, and
  * a Codex run with no `turn.completed` line yields `None` (a Codex model merely missing from the price table still
  * produces a `Cost` with `usd = 0`, mirroring the driver-path `harness.price_missing` convention — see
  * [[CodexEventParser]]).
  */
final case class Reviewed[A](value: A, cost: Option[Cost])
