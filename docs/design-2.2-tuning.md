# Slice 2.2 — driver/reviewer cap tuning + S4-3 reviewer-cost widening

> Phase-2 §3.5 ("Deferred to a later Phase-2 slice", [`roadmap.md`](roadmap.md))
> implementation plan + audit trail. Named to dodge the legacy `design-2.2.md`
> (= Slice 1.2) collision, mirroring [`design-2.1-tui.md`](design-2.1-tui.md).
> Live contract revision: [`forge-design-1.16.md`](forge-design-1.16.md)
> (standalone-by-freeze, restating §7.1 / §12 / §18). Approved plan archived at
> the session plan file `keen-jingling-parnas.md`.

## §0. Exit criterion

A completed run's **reviewer/sensor spend joins `Feature.cost`** (no longer a
driver-session-only invariant): a `cost.update` action with `actor = "reviewer"`
is written for each settled design/PR/refine review (and `learnConventions`), and
`forge stats` folds it. AND the per-turn cost cap is a **post-hoc backstop** that
no longer false-kills a cleanly-settled turn (A1), with re-tuned default caps
(A2). Full unit suite green; `forge-it` compiles.

## §1. Background

Exploration (session, 2026-06-05) established two facts:
1. **The per-turn cost cap is post-hoc, not pre-emptive.** Claude/Codex emit cost
   only in the final `result`/`turn.completed` frame; the monitor processes the
   `CostUpdate` then `kill()`s — *after* the turn (and its cost) completed. The
   $2 cap could not stop the szork $9.56 implement turn; it only converted a
   clean settle into a `TurnBudgetBreached` kill that strands the piece.
2. **Reviewer cost is parsed-past and discarded.** Claude's one-shot envelope
   carries `total_cost_usd`/`usage`/`modelUsage`; Codex's `turn.completed.usage`
   is in the drained JSONL. Both are reachable at the collector seam.

## §2. Task breakdown

### Workstream B — S4-3 reviewer-cost widening

- [x] **B1.** `Reviewed[A](value, cost: Option[Cost])` carrier (`forge-agents`);
  `Connector` six reviewer/sensor methods `IO[A]` → `IO[Reviewed[A]]`.
- [x] **B2.** Capture cost at both collector seams: `ClaudeEventParser.costFromResult`
  shared parser + `ClaudeConnector.extractReviewerCost`; `CodexConnector.extractTurnTokens`
  + `priceTable.usdFor`; both `runReviewer` return `Reviewed`.
- [x] **B3.** `ReviewerOutcome.Settled[A](result, cost: Option[Cost])`;
  `RealReviewerCall.runWithCap` maps `Reviewed`. `ReviewerCall` trait +
  `RetryingReviewerCall` unchanged.
- [x] **B4.** Orchestrator `cost.update` writer (actor="reviewer"):
  `costUpdateDraft(actor)` + `reviewerCostDraft` (feature+piece, **not** turn).
  `designReview`/`prReview`/`refine` co-persist via `handleWinner`
  (`RaceResult.FromReviewer(event, spend)`); `learnConventions` via `driveWith`
  (feature-only, piece=None). **`classifyFailure` cost.update write deferred**
  (§4, below); `profileRepo` exempt (no feature).

### Workstream A — cap tuning

- [x] **A1.** `RealSessionMonitor.applyCaps`: per-turn cost breach is now
  **non-killing** (post-hoc advisory) — no kill, no NHI. Wall-clock settle cap is
  the only mid-turn interrupt; cumulative feature/piece caps stay preventive.
  Walked the `MonitorOutcome.TurnBudgetBreached` consumers + rewrote the
  monitor/review-round suites.
- [x] **A2.** Re-tuned `ForgeConfig` defaults (`maxTurnCostUsd` 2 → 15; piece/
  feature/settle reviewed vs dogfood data, unchanged); `config.example.json` in
  lockstep (`ForgeConfigLoaderSuite` green).

### Docs

- [x] **Spec** `forge-design-1.16.md` (§7.1 / §12 / §18). [ ] roadmap §3.5 flip +
  CLAUDE.md TL;DR await the **whole-section review** (not flipped here per the
  tick-discipline rule); design-rationale S4-3/S4-5 dispositions + the
  classifyFailure deferral recorded.

## §3. Status log

- 2026-06-05 — slice opened; plan approved; exploration findings recorded.
- 2026-06-05 — **implementation landed.** B1–B4 + A1 + A2 shipped; new
  `ReviewerCostExtractionSuite` (6) + `OrchestratorReviewerCostSuite` (1) pass;
  the per-turn-kill monitor suites rewritten for the non-killing semantics. Full
  unit suite green (1555 across the six test groups), `forge-it` compiles,
  scalafmt clean. Contract reconciled into [`forge-design-1.16.md`](forge-design-1.16.md).
  **Roadmap §3.5 `[~]` stays open pending a whole-section code review.**

## §4. Carry-forward / deferrals

- **classifyFailure orchestrator cost.update write** — the connector boundary
  carries its cost (`Reviewed`/`Settled`), but the orchestrator does not yet write
  a reviewer `cost.update` for it. Correct wiring needs the live `totalsRef`
  threaded through the CI-watcher + local-build-gate side-effect chains (5+ sites
  on crash-recovery-sensitive hot paths) — disproportionate for the rarest
  reviewer call (fires only when `adapt.llmClassifierOnUnknown` AND the rules
  classifier returns `Unknown`). Bounded under-count, filed as a design-rationale
  S4-series entry with a roadmap carry-forward.
- **S4-3 `killError`-on-`Timeout`** — stays deferred (separate connector exposure,
  low value).
