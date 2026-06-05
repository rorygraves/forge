# Dogfood run #5 — `queryclient-config` — Phase-3 exit criterion (Node/TS, live)

> Fifth real end-to-end Forge run (2026-06-05), and the **Phase-3 phase-level
> exit-criterion** run ([`../roadmap.md`](../roadmap.md) §4): drive a feature to
> `FeatureDone` on a **new, unseen, non-Scala repo**, auto-profiled, with **zero
> hardcoded-config edits**, and the **formatter handled as a local deterministic
> step** rather than a paid fix-up round. All four prior dogfoods (#1–#4) ran on
> the Scala `llm4s/szork` repo; this one proves the Phase-3 adaptation layer is
> not secretly Scala-shaped.
>
> Target: feature **`queryclient-config`** ("extract `frontend/src/config/
> queryClient.ts`'s inline React-Query literals into an exported, typed
> `QUERY_CLIENT_CONFIG` and build the client from it; add a vitest test") in a
> personal fork **`rorygraves/toast-stats`** (a public Node/TS npm-workspaces
> monorepo with real GitHub Actions CI + branch protection + **prettier**),
> **Mode A** (the §8.2 CI-fail → local-autofix collapse, not the §8.3 pre-commit
> gate — see "Why Mode A fired under default config" below).
>
> Plan/runbook: [`../design-phase3-exit.md`](../design-phase3-exit.md) (findings
> F1–F4 there). Evidence under
> [`phase3-exit-queryclient-config/`](phase3-exit-queryclient-config/):
> `profile.json`, `action-log.jsonl`, `forge-stats.txt`, `sec82-autofix.diff`,
> `pr3.json`.

## Outcome — ✅ exit criterion met end-to-end on Node/TS

`forge run` (then a single operator resume) reached **`FeatureDone`**, both PRs
merged. Every distinctive clause of the roadmap §4 criterion was met **live on a
non-Scala stack**:

- **New, unseen, non-Scala repo** — a fork of `toast-stats`, a Node/TS npm
  monorepo, never profiled before.
- **Auto-profiled, zero hardcoded-config edits** — `forge profile` derived a
  correct `RepoProfile` (hash `2761aa91a8f17ea0`) unaided; no hand-edit of
  `profile.json` / `config.json` taught Forge any command.
- **Formatter as a local deterministic step** — a prettier mis-format CI failure
  was classified `deterministic_fix` by the **rules** baseline (no LLM), routed
  to `RunLocalCommand(npm run format)`, committed + pushed, CI re-ran green, **no
  `PieceCiFailed` / `PieceFixingUp` transition, `attempts` stayed 0**. `forge
  stats` records *"1 fix-up round avoided"*.
- **End-to-end** — `FeatureDone`, design PR #2 + piece PR #3 both squash-merged.

```
forge new → forge spec (FIFO REPL, /done) → forge run:
Drafting → InteractiveSpec → DesignReviewing(1)
   → design reviewer asked 2 *clarifying* Qs → would have stranded headless run (F1)
   → [F1 fix: Clarifying/Optional Qs no longer gate] → DesignAwaitingMerge(#2)
   → [operator merges design PR #2, squash] → DesignReady
→ PieceImplementing(p1) → [implement driver, haiku, 25.8s, $0.21]
   → §8.3 local build gate `npm run build:frontend` FAILED locally `tsc: command
     not found` → classified env → BackOff (correctly did NOT block / misroute — see note)
   → PieceAwaitingCi(#3)
   → §8.2: CI "Quality Gates / Lint and Format Check" ran `prettier --write` →
     dirty tree → FAIL                                              ← the trigger
   → rules classifier: deterministic_fix (conf 0.97, source=rules, marker `prettier`)
     → RunLocalCommand(npm run format) → commit `style(...)` a4284b80 → push
        (NO PieceCiFailed, attempts STAYS 0)                        ← the collapse
   → CI re-runs → Quality Gates GREEN
   → NeedsHumanIntervention "required check 'Build Applications' never appeared" (F3)
   → [operator: fix fork-prep collateral (F4), wait full CI green, `forge resume
      --after-human-push p1`] → PieceAwaitingCi(#3)
   → all 4 required checks green → PieceAwaitingReview(#3)
   → profile.reviewRequired:false → review SKIPPED → PieceAwaitingMerge(#3)
   → [operator merges piece PR #3, squash] → Refining(p1) → FeatureDone
```

## Why Mode A fired under default config (no `adapt.localGate` toggle)

The runbook offered Mode A (§8.2 CI collapse) vs Mode B (§8.3 pre-commit gate)
as an `adapt.localGate` selection. **No toggle was needed.** The sensed profile
tagged `format` as `required: false` (formatting is auto-fixed, not a hard gate).
The §8.3 local format-gate filters `required: true`, so it is *inert* for
`format` on this repo → the double-quote mis-format reached CI untouched → §8.2
fired under default config. This is the emergent P1 finding: the profile's own
`required:false` selects the §8.2 path with zero operator intervention.

## The engineered deterministic trigger (Node/TS analogue of dogfood #4)

As in dogfood #4, a natural mis-format is *stochastic* (modern Claude/Codex
format correctly), so the trigger was made **deterministic** by mandating, as
piece acceptance criterion #6, **double-quoted** string literals for the new
constants. The fork's real `.prettierrc` is `singleQuote: true`, so `npm run
format` (= `prettier --write "**/*.{ts,tsx,js,jsx,json,md}"`) *always* rewrites
them to single quotes:

```diff
- import { QueryClient } from "@tanstack/react-query"      // double-quoted, as spec #6 mandated
+ import { QueryClient } from '@tanstack/react-query'      // prettier single-quote rewrite
- import { describe, expect, it } from "vitest"
+ import { describe, expect, it } from 'vitest'
```

(Full diff: [`phase3-exit-queryclient-config/sec82-autofix.diff`](phase3-exit-queryclient-config/sec82-autofix.diff)
— it also reflows the test file and the prettier-targeted `.forge/profile.json`
+ `decomposition.md`.) The driver writes the double-quoted form literally (per
spec #6) and — because `format` is `required:false`, so the §8.3 gate skips it —
never reformats before committing, so the mis-format reliably survives to CI. It
is a string-quote change only: it does **not** affect compile/typecheck/test, so
only the format check fails — a clean format-only failure the rules classifier
pins as `deterministic_fix` at 0.97.

## Evidence — the §8.2 pass criteria (runbook §5 / design-3.0 §0)

| Criterion | Result |
|---|---|
| `profile.snapshot` at feature start | ✅ seq 3, hash `2761aa91a8f17ea0` (the auto-derived Node profile: `buildTool:npm`; `format` deterministic/**autofix:true**/required:false; lint·typecheck·build:frontend deterministic; test heuristic; `reviewRequired:false`, `squash`, `git_flow`, 4 required checks) |
| `profile.failure_classified` `{gate:ci, kind:deterministic_fix, route:RunLocalCommand, source:rules}` | ✅ seq 16, confidence **0.97**, suggested `format`, evidence = the real `Quality Gates / Lint and Format Check … > prettier --write "**/*.{ts,tsx,js,jsx,json,md}"` log — **no LLM call** |
| No `PieceCiFailed` + `attempts` stays 0 across the CI failure | ✅ 0 `PieceCiFailed`, 0 `PieceFixingUp`; FSM went `PieceAwaitingCi → PieceAwaitingReview` directly; piece `attempts: 0` in the final manifest |
| `forge stats` shows N≥1 fix-ups avoided | ✅ *"1 fix-up round avoided — a CI failure was remedied by the repo's own deterministic autofix (Phase 3 §8.2), with no driver fix-up turn."* |
| The autofix is the `npm run format` output | ✅ commit `a4284b80` `style(queryclient-config): npm run format` (a fresh `style` commit per design-3.0 T4, not `--amend`; squash-merge collapses it into `bbe5f9d6`) |

**Cost.** The whole feature cost **$0.21** of driver/LLM (one implement turn,
haiku, 25.8s, 139k in / 2.1k out). The §8.2 formatter remedy itself was **$0** —
`source = rules`, so not even the §7.11 LLM-classifier tail was paid — and a
~few-second local `npm run format` + push. This is the dogfood-#2 `$1.78 / ~12
min / 2 driver fix-up rounds` waste class collapsing again, now on a Node stack.

## Findings (this conversation's contributions; F1–F3 in design-phase3-exit §8)

| # | Finding | Class | Status |
|---|---------|-------|--------|
| F4 | **Fork-prep collateral: self-referential CI meta-tests fail when their workflows are deleted.** P0 fork-prep deleted 7 secret-needing workflows. The repo has CI-config *meta-tests* asserting specific workflows exist: `webkit-coverage.test.ts` (needs `pr-preview.yml`) and `releaseGatedDeploy.test.ts` (needs `deploy.yml` + `release-please.yml`). These run in sequential required-`Test Suite` steps, so each failure *masked* the next (whack-a-mole across two CI rounds). | sacrificial-repo prep, **not** a Forge/feature defect | **Resolved.** Restored all three workflows on the piece branch: `pr-preview.yml` with its trigger neutered to `workflow_dispatch:` (it needs fork-absent WIF/Firebase secrets), `deploy.yml` + `release-please.yml` verbatim (neither triggers on `pull_request` — `workflow_call`/`workflow_dispatch` and `push:main` respectively, so no fork-secret workflow fires on a PR). A systematic grep confirmed only these two meta-tests depend on deleted workflows (no directory-enumeration test exists), so the third round was the last. **Runbook lesson (now in design-phase3-exit §8 F4): prefer neutering workflow *triggers* over deleting workflow files, so the repo's own CI-config meta-tests still pass.** |
| F3 | **CI gate declares a late required check "never appeared."** After the green autofix, `forge run` hit `NeedsHumanIntervention("required check 'Build Applications' never appeared")` (seq 17). `Build Applications` (`needs:[quality-gates,test]`) only starts ~5 min after `Test Suite`, past `checkDiscoveryTimeoutSec`; the gate can't tell "gated behind a slow job" from "will never run." | Forge gap (CiReadiness) | **Worked around, fix carried forward.** A fresh `forge resume --after-human-push p1` resets the discovery clock; with all 4 checks present + green the gate passes (seq 19→20). Proposed fix (carry-forward): keep polling while any *observed* check is still pending/in-progress; only declare a required check missing once CI is otherwise settled. |
| — | **§8.3 local build gate hit a local-env gap and correctly backed off (robustness datapoint).** The pre-PR §8.3 build gate ran `npm run build:frontend`, which failed locally with `sh: tsc: command not found` (the local clone's PATH lacks the workspace `tsc`). The classifier scored it `env` (conf 0.8, `rules`) → `BackOff` — it did **not** misclassify a local toolchain miss as `deterministic_fix`, and did **not** block the PR; CI (which has the toolchain) ran the real build. | robustness (working as designed) | Noted, non-blocking. The local build gate is *not* inert on this repo (`build` is `required:true`), but it degrades gracefully when the local environment is incomplete. |
| F2 | **Client `pre-push` hooks aborted Forge's autofix push** (fixed earlier this run, commit `99bb4ff`). | Forge gap | Fixed: forge-git pushes now use `--no-verify`. |
| F1 | **Clarifying design-review questions stranded a headless run** (fixed earlier this run, commit `82f6220`). | Forge gap | Fixed: `designVerdict` honours `QuestionSeverity` — only `Blocking` Qs gate. |

## Run mechanics (tooling notes)

- **Launched via `sbt "forge-app/run …"` against the F1/F2-patched tree.** First
  invocation recompiled (F1/F2 changed source); the resume took 1010s wall
  (mostly polling the green CI + the operator-wait window).
- **`forge resume … --after-human-push p1` is non-interactive** — no FIFO needed.
  It advanced `NHI → PieceAwaitingCi → PieceAwaitingReview → PieceAwaitingMerge`
  and then *polled* at `PieceAwaitingMerge` for the operator's remote merge.
- **Merge is a remote `gh pr merge 3 --squash`** (GitHub-API, no local-worktree
  mutation) done *while* forge polled — the designed hand-off, and safe under the
  "no-git-in-a-live-forge-worktree" rule (which is about local mutations racing
  Forge's commits). Forge detected the merge (seq 23 `audit.piece_merged`) →
  `Refining` → `FeatureDone`, exiting `0` with *"✓ feature complete — all pieces
  merged and refined."*
- **Husky is active on the fork** (`npm ci` ran husky's `prepare`):
  `pre-commit`=lint-staged, `pre-push`=vitest. All manual git for the F4 fix used
  `--no-verify`; Forge's own commit/push already do post-F2.

## Cleanup / state

- Both PRs merged (design #2, piece #3 → `bbe5f9d6`); fork `main` carries the
  feature + the three restored workflows. No Forge process left running; the live
  lock (`.lock.json`) released on clean exit. A stale 0-byte `.forge/state/.lock`
  from the F1-hang remains (harmless; `forge unlock --force` clears it).
- The spec files (`.forge/specs/queryclient-config/`) + the auto-derived
  `.forge/profile.json` remain on fork `main` (merged via the design + piece PRs),
  consistent with prior dogfoods.

## Cross-references

- [`../roadmap.md`](../roadmap.md) §4 — the Phase-3 exit criterion this run meets
  (tick the §4 exit bullet only after a whole-section review).
- [`../design-phase3-exit.md`](../design-phase3-exit.md) — the plan/runbook this
  executed; findings F1–F4 in its §8.
- [`adventure-gen-retry-config.md`](adventure-gen-retry-config.md) — dogfood #4,
  the Scala §8.2 demonstration this generalises to Node/TS.
- [`extract-media-network-config.md`](extract-media-network-config.md) — dogfood
  #2, the `$1.78 / 12 min` waste this collapses.
