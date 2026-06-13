# Forge — design doc v1.16

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with configurable validation — including true cross-model review — and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.16 — Phase-2 §3.5 (driver/reviewer tuning): reviewer-cost widening (S4-3) + the per-turn cost cap as a post-hoc advisory  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.14 → 1.15) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.15.

**Validation-mode reconciliation (2026-06-13).** Forge's product requirement is to support **both** same-CLI validation and true **cross-model validation**. The current shipped resolver from 1.10 maps each built-in `Mode` to a same-CLI driver/reviewer pair; that is a valid mode for cost/locality, not the long-term limit. The roadmap carries the follow-up to make role pairings persisted/configurable so one CLI can drive while another independently reviews.

**Standalone-by-freeze (this revision only, continuing the 1.7→1.15 exception).** 1.16 is a *focused* revision landing the open tail of roadmap §3.5 (carry-forwards **S4-3** / **S4-5**):

- **Reviewer/sensor cost joins `Feature.cost` (S4-3).** Through 1.15 the one-shot reviewer/sensor calls returned only the typed verdict and **discarded** the cost the CLI envelope already carried (Claude's `--output-format json` envelope has `total_cost_usd` / `usage` / `modelUsage`; Codex's drained JSONL has the `turn.completed.usage` line). So §12's USD budget caps were a *driver-session-only* invariant. 1.16 widens the `Connector` reviewer/sensor methods to return the verdict **paired with its cost**, surfaces it on `ReviewerOutcome.Settled`, and has the orchestrator write a `cost.update` (`actor = "reviewer"`) that folds into `Feature.cost` exactly like a driver cost.update.
- **The per-turn cost cap (`maxTurnCostUsd`) is a post-hoc advisory, not a mid-turn interrupt (Slice 2.2 A1).** Cost is reported only in the turn-end frame, so a per-turn breach is observed only *after* the turn — and its spend — is complete; killing then reclaims nothing and, for the single-turn headless drivers, only strands an already-settled valuable turn (the dogfood-#1 `$9.56`-vs-`$2` finding). 1.16 makes the per-turn breach **non-killing**: the turn settles on its own terms and the overrun stays visible in the audit. The **mid-turn interrupt is the wall-clock settle cap**; the **cumulative feature/piece caps** (next-spawn refusal) remain the real budget enforcement.
- **Re-tuned §18 defaults.** `maxTurnCostUsd` 2.00 → 15.00 (covers the observed implement-turn cost with headroom; advisory now). The other caps and settle bounds are reviewed against dogfood data and unchanged.

It **restates in full only the sections it changes — §7.1, §12, and §18** — and **freezes every other section at its 1.15 (hence 1.14 / … / 1.6) text**. Section numbering is preserved 1:1.

**Changed in 1.16:** §7.1 (reviewer/sensor methods return `Reviewed[A]` = verdict + `Option[Cost]`; the per-turn-kill sentence corrected), §12 (per-turn cap post-hoc/non-killing; reviewer spend joins the totals), §18 (`maxTurnCostUsd` default 15.00; the `reviewer` block from 1.14 unchanged).

**Scope note — what 1.16 does *not* change.** The replayability invariant holds: a reviewer `cost.update` is a normal §19 action with `actor = "reviewer"`, projected by the unchanged, actor-agnostic `Replay.applyCostUpdate`; the reviewer wall-clock cap, the `reviewer` §18 block (1.14), and the §7.6 process-retry knobs are all unchanged. The `ReviewerOutcome.Timeout` observable-kill diagnostic (`killError`) stays deferred (S4-3 second half).

**1.16 has accompanying code.** S4-3: the `Reviewed[A]` carrier + widened `Connector` reviewer/sensor signatures, `ClaudeEventParser.costFromResult` / `ClaudeConnector.extractReviewerCost`, `CodexConnector.extractTurnTokens`, `ReviewerOutcome.Settled(result, cost)`, and `Orchestrator.reviewerCostDraft` (folds feature+piece, never turn) co-persisted at the design/PR/refine settle + `learnConventions`. A1: `RealSessionMonitor.applyCaps` no longer kills on the per-turn cap. A2: `ForgeConfig.maxTurnCostUsd = 15.00` + `.forge/config.example.json`. Proven by `ReviewerCostExtractionSuite`, `OrchestratorReviewerCostSuite` (actor="reviewer" cost.update folds onto `Feature.cost`, turn untouched), and the rewritten `SessionMonitorTurnCostSuite` / `SessionMonitorReviewRound{1,2}Suite` (per-turn breach is non-killing). Plan + audit trail: [`design-2.2-tuning.md`](design-2.2-tuning.md).

**Deferred (documented).** The orchestrator-side `cost.update` *write* for `classifyFailure` is deferred (the connector boundary carries its cost): wiring it would thread the live cost-totals ref through the CI-watcher + local-build-gate side-effect chains for the rarest reviewer call (`Unknown`-classification only). `profileRepo` is exempt from `Feature.cost` (out-of-band `forge profile`, no feature to attribute to). See [`design-rationale.md`](design-rationale.md) S4-3 and roadmap §3.5.

---

## 7.1 `AgentSession`, `StreamingSession`, and `Connector` traits (changed in 1.16)

§7.1 is unchanged from 1.6 except for the reviewer/sensor method return types and the per-turn-kill sentence.

The reviewer and §7.11 sensor methods on `Connector` now return the typed verdict **paired with the cost the CLI envelope reported for the call** — a `Reviewed[A](value: A, cost: Option[Cost])`:

```scala
  // Reviewer methods (S4-3: return value + the call's cost)
  def reviewDesign(input: DesignReviewInput): IO[Reviewed[DesignReview]]
  def reviewPr(input: PrReviewInput): IO[Reviewed[PrReview]]
  def refine(input: RefineInput): IO[Reviewed[RefineResult]]

  // §7.11 sensor methods — same Reviewed[A] shape
  def profileRepo(input: RepoProfilerInput): IO[Reviewed[RepoProfile]]
  def classifyFailure(input: FailureClassifierInput): IO[Reviewed[Classification]]
  def learnConventions(input: ConventionLearnerInput): IO[Reviewed[ConventionDeltas]]
```

`cost` is `Option` because it can be genuinely absent: an `is_error` / cost-less Claude envelope yields `None`, and a Codex run with no `turn.completed` line yields `None` (a Codex model merely missing from the price table still produces a `Cost` with `usd = 0`, mirroring the driver-path `harness.price_missing` convention). The cost is read off the **same envelope** the verdict is decoded from — Claude via the shared `ClaudeEventParser.costFromResult` (the one cost parser for both the streaming-driver and one-shot paths), Codex via `extractTurnTokens` + `priceTable.usdFor`. The wall-clock cap wrapper surfaces it on `ReviewerOutcome.Settled[A](result, cost)`; the `ReviewerCall` trait signature is unchanged (the cost rides transitively on `Settled`).

**Per-turn kill (corrected).** The 1.6 sentence "The orchestrator's `SessionMonitor` calls `session.kill()` uniformly when either a settle timeout **or a per-turn cost cap** is breached" no longer holds: per §12 (below), the settle timeout is the only `SessionMonitor` kill; the per-turn cost cap is a post-hoc advisory and does not kill.

---

## 12. Budget enforcement (changed in 1.16)

Three caps:

```json
{
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd": 8.00,
  "maxTurnCostUsd": 15.00
}
```

`feature.cost` now accumulates **both** driver-session spend **and** reviewer/sensor spend (S4-3): every settled design review, code review, refine, and `learnConventions` writes a `cost.update` (`actor = "reviewer"`) that folds into the same running totals as a driver `cost.update`. Reviewer spend folds into the **feature** scope (and the **piece** scope for the piece-scoped PR review / refine), **never** the per-turn scope — a reviewer call is not a driver turn. (Two sensors are excepted: `classifyFailure`'s cost.update write is deferred — see the §0 deferral note — and `profileRepo` is exempt as an out-of-band repo-level command with no feature to attribute to. Both still *carry* their cost on the `Reviewed`/`Settled` value.)

Checks:

1. **Before spawning any agent:** if `feature.cost + estimatedSpawnCost > maxFeatureCostUsd`, refuse → `NeedsHumanIntervention("feature budget would be exceeded", AbortOrAbandon)`. Same for the piece cap. Estimated spawn cost is conservative.
2. **After every `cost.update`:** re-evaluate the **cumulative** caps. A per-feature/per-piece breach → let the current turn complete, no new spawns, transition to `NeedsHumanIntervention("budget exceeded", <hint>)`. **These cumulative caps are the real preventive enforcement** (they refuse the next spawn).
3. **Per-turn cap (`maxTurnCostUsd`) — post-hoc advisory, non-killing.** Cost is reported only in the turn-end frame, so a per-turn breach is observed only *after* the turn (and its spend) is complete. The monitor therefore does **not** `session.kill()` on a per-turn breach and does **not** strand the run; the turn settles on its own terms and the overrun stays visible in the `cost.update` / `session.complete` audit (and `forge stats`). `maxTurnCostUsd` is the threshold above which a turn is flagged as unusually expensive — guidance, not a gate. **The only mid-turn interrupt is the wall-clock settle cap (§7.9 / §18 `settle.*`).**

`SessionMonitor` is the orchestrator component responsible for settle-timeout enforcement (the mid-turn kill) and the cumulative cost-cap checks. TUI status pane shows running totals against caps.

---

## 18. Configuration (changed in 1.16)

§18 is unchanged from 1.15 (which carries the 1.14 `reviewer` block) except for the `maxTurnCostUsd` default.

`.forge/config.json` per repo — the cost caps now read:

```jsonc
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd":   8.00,
  "maxTurnCostUsd":    15.00   // post-hoc advisory (§12 check 3); retuned from 2.00 against dogfood data
```

Notes:
- **`maxTurnCostUsd`** is a post-hoc advisory threshold (§12 check 3), not a mid-turn gate. Retuned 2.00 → 15.00: the dogfood-#1 implement turn cost $9.56 against the old $2 cap, which post-hoc-killed and stranded it. The preventive caps are `maxFeatureCostUsd` / `maxPieceCostUsd`; the mid-turn interrupt is the `settle.*` wall-clock cap.
- The `reviewer` block (1.14, `claudeModel` / `codexModel` / `wallClockCapSec`) is unchanged; an unset block reproduces the C15 v1 values.
- `reviewProcessRetries` / `refineProcessRetries` cover **process-level failures only** (§7.6).
- Global config, prompts, prices, and per-repo overrides as in 1.6 §18.

---

*Every section not listed under "Changed in 1.16" is frozen at its 1.15 text and resolves 1:1 by section number.*
