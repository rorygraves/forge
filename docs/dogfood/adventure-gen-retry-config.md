# Dogfood run #4 — `adventure-gen-retry-config` — T6 live §8.2 trigger

> Fourth real end-to-end Forge run (2026-06-05), driven specifically to close
> [`../design-3.0.md`](../design-3.0.md) **T6**: the *live* demonstration of the
> §8.2 CI-fail → local-autofix routing that dogfood #3 ([`music-poll-config.md`](music-poll-config.md))
> validated the spine for but never actually *triggered* (the driver formatted
> correctly, so no CI formatting check failed). Target: feature
> **`adventure-gen-retry-config`** ("extract `AdventureGenerator`'s inline
> `maxRetries = 2` and `Thread.sleep(500)` retry/back-off literals into an
> env-overridable `AdventureGenConfig`, mirroring `MusicPollConfig`") in
> **`llm4s/szork`**, real GitHub Actions CI + branch protection
> (`backend` + `frontend`), **Mode A** (`adapt.localGate = false`, so a
> formatting failure survives to CI and exercises the §8.2 router instead of
> being fixed pre-commit by the §8.3 local gate).
>
> Evidence captured under [`t6-run/adventure-gen-retry-config/`](t6-run/adventure-gen-retry-config/):
> `action-log.jsonl`, `forge-stats.txt`, `sec82-autofix.diff`, `pr19.json`.

## Outcome — ✅ §8.2 fired live and passed every criterion

The §8.2 CI-fail → local-autofix routing fired on a real CI failure, on a real
PR, for the first time. The scalafmt CI failure was classified
`deterministic_fix` by the **rules** baseline (no LLM call), routed to
`RunLocalCommand(sbt scalafmtAll)`, the autofix was committed and pushed, CI
re-ran green, and the FSM advanced straight to review **with no `PieceCiFailed`
transition and `attempts` unchanged at 0**. `forge stats` records *"1 fix-up
round avoided"*.

```
forge new → forge spec (/done) → forge run:
Drafting → InteractiveSpec → DesignReviewing(1, approve)
→ DesignAwaitingMerge(#18)
   → §9 watcher saw szork's automated Codex PR-review comment → DesignPrFeedback(1)
   → design driver addressed it (no-op) → DesignAwaitingMerge(#18)
   → [operator merges design PR #18, squash] → DesignReady
→ PieceImplementing(p1) → [implement driver, haiku, 55s, $0.34] → PieceAwaitingCi(#19)
   → §8 CI gate: backend "Check formatting" FAILS (scalafmt)            ← the trigger
   → §8.2 router: gh run view --log-failed → classify deterministic_fix (conf 0.97, source=rules)
     → RunLocalCommand(sbt scalafmtAll) → commit `style(...)` 6b5433ed1 → push
        (NO PieceCiFailed, attempts STAYS 0)                            ← the collapse
   → CI re-runs → GREEN → PieceAwaitingReview(#19)
→ [Forge's own reviewer] request_changes (scaladoc style — see finding #1)
   → PieceReviewFailed(attempt 1) → PieceFixingUp(1) → [fixup driver, haiku, 43s, $0.21] → PieceAwaitingCi(#19)
   ── run stopped here: the §8.2 assertion was fully captured; the review fix-up
      is a self-inflicted spec/formatter conflict (finding #1), not part of T6 ──
```

## The engineered deterministic trigger (what made this work where dogfood #3 didn't)

dogfood #3's §8.2 trigger never fired because a natural mis-format is
*stochastic* — it needs the driver to format wrongly, and modern Claude/Codex
usually format correctly. This run made the mis-format **deterministic** by
exploiting a config-specific scalafmt rule the driver cannot pre-empt and would
not naturally satisfy.

**The recipe** (proven against szork's *real* `.scalafmt.conf`, v3.7.17,
`maxColumn = 120`, before the run — not invented): mandate, as a piece
acceptance criterion, a `load` factory method with **ScalaDoc using inline
`@param`/`@return` tags** — i.e. `@param reader the description…` on a single
line. scalafmt 3.7.17 *always* rewrites an inline tag description onto its own
indented continuation line:

```diff
- /** Loads the adventure-generation retry configuration from the environment, falling back to the historical inline defaults when an override is absent or unparseable.
+ /** Loads the adventure-generation retry configuration from the environment, falling back to the historical inline
+   * defaults when an override is absent or unparseable.
    *
-   * @param reader the configuration source to read environment overrides from; defaults to the process environment loader
-   * @return a fully-resolved AdventureGenConfig whose fields equal the environment overrides where present and the historical defaults otherwise
+   * @param reader
+   *   the configuration source to read environment overrides from; defaults to the process environment loader
+   * @return
+   *   a fully-resolved AdventureGenConfig whose fields equal the environment overrides where present and the
+   *   historical defaults otherwise
    */
```

(Full diff: [`t6-run/.../sec82-autofix.diff`](t6-run/adventure-gen-retry-config/sec82-autofix.diff).)
Two independent triggers in one: the `@param`/`@return` tag-split **and** the
>120-column prose wrap. An LLM driver writes the inline form by default and —
with `localGate = false` — never runs scalafmt before committing, so the
mis-format reliably survives to CI. Crucially, scaladoc is a comment: it does
**not** affect compile or tests, so szork's `backend` job (compile → test →
**Check formatting**, in that order) passes the first two steps and fails *only*
on `scalafmtCheckAll` — a clean format-only failure the rules classifier pins as
`deterministic_fix` with 0.97 confidence.

## Evidence — the §8.2 pass criteria (runbook §5)

| Criterion | Result |
|---|---|
| `profile.snapshot` at feature start | ✅ seq 3, hash `2bfce7df9c92c42f` (the `git_flow` Mode-A profile) |
| `profile.failure_classified` `{gate:ci, kind:deterministic_fix, route:RunLocalCommand, source:rules}` | ✅ seq 17, confidence 0.97, evidence = the real `backend / Check formatting / Run sbt scalafmtCheckAll` log |
| No `PieceCiFailed` + `attempts` stays 0 across the CI failure | ✅ 0 `PieceCiFailed`; the autofix `style` commit's manifest shows `attempts: 0`; FSM went `PieceAwaitingCi → PieceAwaitingReview` directly |
| `forge stats` shows N≥1 fix-ups avoided | ✅ *"1 fix-up round avoided — a CI failure was remedied by the repo's own deterministic autofix (Phase 3 §8.2), with no driver fix-up turn."* |
| The autofix is the `sbt scalafmtAll` output | ✅ commit `6b5433ed1` `style(adventure-gen-retry-config): sbt scalafmtAll` (a fresh `style` commit per design-3.0 **T4**, not `--amend`; squash-merge collapses it) |

**Cost of the collapse vs dogfood #2.** dogfood #2 spent **$1.78 / ~12 min / 2
driver fix-up rounds** on exactly this class of scalafmt mis-format. Here the
same failure class was remedied by a ~few-second local `sbt scalafmtAll` and a
push, **$0 of driver/LLM cost, 0 fix-up rounds consumed** — `source = rules`, so
not even the §7.11 LLM classifier tail was paid. This is the dogfood-#2 waste
collapsing on a live run, the §0 exit criterion of Slice 3.0/3.1.

## Findings

| # | Finding | Class | Status |
|---|---------|-------|--------|
| 1 | **An acceptance criterion that mandates an anti-formatter style sends the run into a review↔CI fix-up loop.** The engineered piece spec required ScalaDoc with *inline* `@param`/`@return` (criterion 5, "non-negotiable"). The §8.2 autofix correctly reformats to scalafmt's canonical *split* form — which then violates that very criterion, so Forge's **own reviewer** (correctly, gate=`code`) requested changes (seq 19), spawning a code-review fix-up (attempt 1) that rewrote it back to inline… which the next CI's scalafmt would re-split, and so on until `maxFixupRounds`→NHI. | spec design (self-inflicted), **not** a §8.2 or Forge defect | **By design of this test.** The contradiction was deliberately introduced to *guarantee* the §8.2 trigger; it is the cleanest way to force a deterministic reflow. Lesson for real features: never write an acceptance criterion that contradicts the repo's deterministic formatter — the §8.3 local gate / §8.2 router will always win, and the reviewer will always bounce it. A future ConventionLearner/profile signal could flag "spec style requirement conflicts with `.scalafmt.conf`". Not filed as a Forge bug. |
| 2 | **szork runs an automated third-party PR reviewer (`chatgpt-codex-connector`) that comments on every PR.** Its advisory `COMMENTED` review on the design PR was picked up by Forge's §9 watcher as unseen feedback → a `DesignPrFeedback` round (design driver re-spawned, $0.64, addressed it as a no-op) before the merge. Handled correctly and cost-bounded, but it is an external actor injecting a feedback round Forge did not initiate. | environment interaction | **Working as designed**, noted. A repo with a chatty auto-reviewer pays one extra (bounded) driver round per PR. Candidate future refinement: distinguish Forge's own reviewer verdict from third-party advisory comments when deciding whether to open a feedback round. |
| 3 | **Driver model resolved to a mix of `claude-opus-4-8` (design revision) and `claude-haiku-4-5` (implement, fixup).** `config.claude.model = "default"`. Cost was modest ($1.19 total) but the design-revision turn on opus ($0.64, 480k input tokens) dominated. | observability / config | Noted; not pursued. If driver cost matters, pin `claude.model` explicitly. Orthogonal to T6. |

## Run mechanics (tooling notes for future live runs)

- **Launch via direct `java`, not `sbt`.** Built a runtime classpath once
  (`sbt "export forge-app/Runtime/fullClasspath"`) and launched
  `java -cp <cp> io.forge.app.Main --repo-root <szork> <cmd>`. Avoids the
  `sbt -batch` stdin-close problem dogfood #3 hit.
- **Drove the interactive `forge spec` REPL non-interactively** by feeding
  forge's stdin from a FIFO fed by `tail -f` of an input file, teeing output to
  a log; appended `/done` once the `[forge spec] Your reply` hint appeared. The
  directive brief (seeded into `.forge/specs/<feat>/design.md`, which the REPL
  loads as the spawn brief) told the driver to produce all spec files in one
  turn and ask no questions — it complied, so a single `/done` finalised it.
- **Profile fix:** the committed Mode-A szork profile declares
  `branchModel: "trunk_based"`, which since 2026-06-04 routes the orchestrator
  to the no-PR trunk lifecycle (no `PieceAwaitingCi` → no §8.2 gate). szork is a
  PR+branch-protection repo, so the run used `branchModel: "git_flow"` to keep
  the PR-based CI lifecycle. (The committed fixture's `trunk_based` predates the
  trunk path being wired; it is now misleading for szork — see cleanup.)

## Cleanup actions taken this run

- Stopped the `forge run` process at `PieceAwaitingCi(#19)` (the review fix-up
  loop of finding #1) — the §8.2 assertion was already fully captured.
- `forge abandon adventure-gen-retry-config`; closed PR #19 and deleted its
  branch. Design PR #18 had already merged (a necessary step to reach
  implement), so its spec files (`.forge/specs/adventure-gen-retry-config/`)
  remain in szork `main`, consistent with prior dogfoods' design merges.
- szork `main` left green; no Forge process left running.

## Cross-references

- [`../design-3.0.md`](../design-3.0.md) **T6** (the carry-forward this closes) and §0 exit criterion.
- [`t5-cifail-routing-runbook.md`](t5-cifail-routing-runbook.md) — the runbook this run executed (Mode A).
- [`music-poll-config.md`](music-poll-config.md) — dogfood #3, which validated the spine but did not trigger §8.2.
- [`extract-media-network-config.md`](extract-media-network-config.md) — dogfood #2, the `$1.78 / 12 min` waste this collapses.
