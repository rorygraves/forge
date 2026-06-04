# Dogfood run #3 — `music-poll-config` — T5 live re-run findings

> Third real end-to-end Forge run (2026-06-03), driven as **T5** (the gating live
> re-run for [`../design-3.0.md`](../design-3.0.md) Task 3.1.2 / the §8.2 CI-fail →
> local-autofix routing). Target: feature **`music-poll-config`** ("extract
> `MusicGeneration.pollPrediction`'s hardcoded `maxAttempts = 30` and
> `Thread.sleep(1000)` into an env-overridable `MusicPollConfig`, mirroring
> `MediaNetworkConfig`") in **`llm4s/szork`**, Claude driver, real GitHub Actions
> CI + branch protection (`backend` + `frontend`), **Mode A** config
> (`adapt.localGate = false`, so a formatting failure would reach CI and exercise
> the §8.2 router rather than being fixed pre-commit by the §8.3 local gate).
>
> Runbook + ready-to-drop profile: [`t5-cifail-routing-runbook.md`](t5-cifail-routing-runbook.md).

## Outcome — ⚠️ partial: spine validated live; §8.2 did NOT trigger; run derailed

The run validated the Phase-3 spine end-to-end on Mode A but did **not** achieve
T5's actual goal (a live §8.2 routing demonstration), and was then derailed by an
operator (me) mistake. Honest disposition below.

```
forge new → forge spec (/done) → forge run:
Drafting → DesignReviewing(1, approve) → DesignAwaitingMerge → [merge PR #16] → DesignReady
→ PieceImplementing → PieceAwaitingCi → §8 gate: CI GREEN (driver formatted correctly)
→ PieceAwaitingReview → §9 poll → NeedsHumanIntervention (transient GitHub 503)   ← finding #5 recurrence
  ── operator-authorized log-trim recovery + forge run resume ──
→ PieceAwaitingReview → reviewer request_changes → PieceFixingUp(1) → PieceAwaitingCi → CI GREEN
→ PieceAwaitingReview(approve) → PieceAwaitingMerge
  ── operator git excursion in the live worktree → concurrent-git race ──
→ piece commit (3b1a072) pushed DIRECTLY to main, bypassing PR #17           ← operator error
→ run stopped; feature `abandoned`; PR #17 closed redundant; 3b1a072 left on main (CI green)
```

### What was validated live (genuinely useful)

- **`profile.snapshot` emitted at feature start** (hash `bd3d39fe3ab5bed5`) — the
  hand-authored Mode-A `.forge/profile.json` loaded and hashed into the action log
  per run (Task 3.0.2). First live confirmation of the profile-load path.
- The full §11 lifecycle on a profiled run: spec → design review → design PR (#16,
  merged) → implement → piece PR (#17) → CI → code review → fix-up → CI → review.
- **Slice-2.0 observability live**: `cost.update` + `session.complete` written for
  the implement turn (`$0.24`, 126k in / 3.6k out, claude-haiku) and the fix-up
  turn (`$0.42`, 286k in / 5.3k out). Driver work for the feature ≈ **$0.66**
  (reviewer one-shots not folded — finding #7 from dogfood #2 still open).
- The interactive `forge spec` REPL had to be driven via a **direct `java`
  launch**, not `sbt run`: under `sbt -batch "forge-app/run"` the forked app's
  piped stdin reaches EOF before the REPL's first `readLine` (sbt closes batch
  stdin), so `/done` never lands and the spec aborts unrecorded. `connectInput :=
  true` is set but does not survive `-batch`. (Tooling note for future live runs.)

### What was NOT achieved

- **§8.2 CI-fail → local-autofix routing never fired.** The implement driver — and
  the later fix-up driver — both produced **scalafmt-conformant** code (verified:
  `scalafmtCheckAll` passes on the p1 branch), so no formatting check failed and
  the §8.2 router had nothing to route. Unlike dogfood #2 (where the driver wrote a
  scaladoc scalafmt reflowed), a natural §8.2 trigger is **stochastic** — it
  depends on the driver mis-formatting, which did not happen here. The routing
  itself remains exhaustively unit-proven, including end-to-end against the **real**
  dogfood-#2 scalafmt failing-check log (`OrchestratorCiRoutingSuite`,
  `FailureRouterSuite`). **T5's live assertion is therefore not demonstrated;** it
  is satisfied in substance by the unit proof + this run's live spine validation
  (operator decision, 2026-06-03).

## Findings

| # | Finding | Class | Status |
|---|---------|-------|--------|
| 1 | **Finding #5 (dogfood #2) recurred live.** A transient `GitHub 503` on the §9 `PRWatcher` poll (after CI was already green) hard-routed `PieceAwaitingReview → NeedsHumanIntervention` with a misleading `RunAnotherFixup` hint. The §8.2 router has a `RateLimit → BackOff` arm, but it only covers **CI-gate** failures; the **PR-state poll loop** still hard-NHIs on a transient HTTP error. | resilience | **✅ Resolved 2026-06-04** — `GhError.Transient` (the 503/5xx bucket) on the §9 poll now surfaces as the soft `PollResult.TransientError`: the watcher backs off and keeps polling, promoting to `Failed`→NHI only after N consecutive transients (the S3-4 rate-limit-cliff twin). Both orchestrator poll consumers absorb it. Contract: design-rationale **S3-4b**; plan: [`../design-3.0.md`](../design-3.0.md) T6. |
| 2 | **No guard against a piece commit landing on a checked-out base branch.** Forge does `git checkout <piece-branch>` / `add` / `commit` / `push` on the shared worktree with no lock against concurrent external git. When the operator left `HEAD` on `main` (a `scalafmtCheckAll` verification excursion in the live worktree), Forge's piece commit landed on `main` and was pushed directly to the shared remote, bypassing the PR. | safety (operator-triggered, but a real gap) | **Open** — primarily an operator-discipline lesson (never run worktree-mutating git in a repo Forge is driving; use a separate clone or `git show <ref>:<path>`), but Forge could also assert `HEAD == expected piece branch` immediately before `git commit` and refuse otherwise. |
| 3 | **Driver formats correctly often enough that a natural §8.2 trigger is unreliable for testing.** Two driver turns here both produced conformant Scala. A live §8.2 demonstration needs either a feature engineered to force a reflow, or `adapt.localGate` left on (Mode B) where the §8.3 pre-commit gate makes the point cheaper anyway. | test methodology | **Open** — for the live §8.2 proof, engineer a guaranteed reflow (long scaladoc) or accept the unit proof. |

## Recovery / cleanup actions taken this run

- **Finding #5 NHI**: recovered (operator-authorized) by trimming the single
  spurious trailing NHI from the append-only log (backup kept), clearing the state
  cache, and re-running `forge run` from the rebuilt `PieceAwaitingReview` — the
  same dogfood-#2 recovery, now harness-gated as an audit-trail edit.
- **Operator git-race**: stopped the runaway forge process; **kept `3b1a072` on
  `main`** (correct code, CI green — reverting a shared OSS `main` is riskier than a
  valid-but-direct commit); **closed PR #17** as redundant; **dropped** the stray
  `stash@{0}` the excursion created (the 4 pre-existing stashes untouched);
  **`forge abandon music-poll-config`** to close out the dangling local feature
  state (pure local FSM transition, no remote effect).

## Disposition

- **The `MusicPollConfig` feature is delivered to `szork` `main`** (`3b1a072`, CI
  green) — a real change driven by Forge, albeit via an unintended direct push.
- **T5 (§8.2 live proof) is not closed by this run.** Task 3.1.2's checkbox is
  ticked with an explicit caveat (unit-proven + spine-validated; natural live
  trigger did not fire). A future live §8.2 demonstration (finding #3) is the clean
  way to close it fully; the runbook + prep remain in place.
- **Finding #1 (§9 poll back-off) ✅ resolved 2026-06-04** (design-rationale S3-4b;
  the §9 poll now backs off and keeps polling on a transient blip). **Finding #2**
  (optional pre-commit `HEAD` assertion) still feeds the next Forge maintenance pass.
</content>
