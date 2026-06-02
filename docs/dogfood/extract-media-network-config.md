# Dogfood run #2 — `extract-media-network-config` — findings & friction

> Second real end-to-end Forge run (2026-06-02; the first was the Slice-1.4
> MVP gate, [`../slice-4/mvp-friction.md`](../slice-4/mvp-friction.md)). Target:
> feature **`extract-media-network-config`** ("extract the duplicated network
> timeout/buffer literals in the `media` package into a shared config") in
> **`llm4s/szork`** (Scala/Vue), Claude driver, real GitHub Actions CI +
> branch protection (`backend` + `frontend` required checks).
>
> Purpose: exercise the Phase-2 codebase end-to-end on a fresh feature and feed
> roadmap §3.3 (prompt iteration) / §3.4 (OSS-readiness). As with the MVP gate,
> the run's job is to surface gaps that fakes can't. It found a **blocking
> `main`-branch regression** (#1 below) plus a cluster of prompt/efficiency
> items.

## Run progress — ✅ COMPLETE (`FeatureDone`, 2026-06-02)

```
forge new → forge spec (/done) → forge run:
Drafting → DesignReviewing(1, approve) → DesignAwaitingMerge → [merge PR #14] → DesignReady
→ PieceImplementing → PieceAwaitingCi → §8 gate: CI-fail (scalafmt) → PieceCiFailed → PieceFixingUp
→ (fix-up loop ×2) → §8 gate: green → PieceAwaitingReview → (reviewer approve)
→ PieceAwaitingMerge → [merge PR #15] → Refining → FeatureDone
```

Both **PR #14 (design)** and **PR #15 (implementation)** merged to szork `main`
(`e91463a`, `ac44c2c`). The driven implementation is correct: a new
`api/MediaNetworkConfig.scala` (env-overridable `connectTimeoutMs`/`readTimeoutMs`
via the existing `ConfigReader`/`EnvLoader` seam, matching the `image-creds-dedup`
sibling), the five duplicated timeout sites in `MusicGeneration` / `TextToSpeech`
/ `SpeechToText` routed through it, and a new `MediaNetworkConfigSpec`.

### `forge stats` (Slice 2.0 observability — validated live)

```
  phase        turns   wall-clock     cost
  implement        1      1m 46s     $0.73
  fixup            2     12m 23s     $1.78
  total            3     14m  9s     $2.51
  waiting (human)        249m 40s   (design merge 16m, piece merge 233m)
```

The work-vs-wait split worked as designed: **~14 min / $2.51 of actual Forge
driver work**, with the large "waiting" figure dominated by this session's
debugging detour (the #1 regression + #5 rate-limit recovery), not Forge being
slow. Two caveats this surfaced: the breakdown is **driver-only** (reviewer
one-shot cost is not folded — see #7), and the "piece merge" wait is inflated by
the manual recovery, not a true human-merge latency.

## Findings

| # | Finding | Class | Status |
|---|---------|-------|--------|
| 1 | **`spec → run` false-NHI regression (blocking, on `main`).** After a clean `forge spec` `/done` (state `DesignReviewing(1)`), the next `forge run` synthesized `NeedsHumanIntervention("design revision interrupted by process restart", ReopenDesign)` and never ran the design review. Root cause: the 2026-05-31 "session-id log durability" fix started logging the spec driver's `driver.spawn` (`piece=None`); `RebuildState.lastDriverSpawn` matched spawns by piece key only, so at `DesignReviewing(1)` it mistook the *completed* spec spawn for a live design-revision driver. `DesignReviewing(1)` is a fresh reviewer one-shot with **no** live driver (only `round > 1` resumes one — `Orchestrator.runEntryHook`). The MVP run (pre-fix) had no spec `driver.spawn`, so it never hit this; the F1/F5/F6 property suites assert on FSM reconstruction / cleared final state, not this mid-trajectory spec→run projection. | regression / projection bug | **Fixed** — `RebuildState.driverKeyFor` scoped to `DesignReviewing(round > 1)`; regression test in `RebuildStateInFlightSuite` (spec→run handoff → empty in-flight). All recovery suites green. |
| 2 | **`forge new` preflight fails its own clean-worktree check.** On a repo where `.forge/` is not git-ignored, lock acquisition writes `.forge/state/.lock` *before* preflight runs, so `git status --porcelain` is non-empty and `forge new` aborts with `worktree.clean`. The MVP `szork` checkout had `.forge/` in `.git/info/exclude` (local, uncommitted); a fresh checkout does not. | first-run friction | **Open** — worked around with a local `.git/info/exclude` entry. Forge should self-provision the exclude (or exempt its own `.forge/state`+`.forge/log` from the preflight check). |
| 3 | **Implement driver doesn't run the formatter before settling.** It produced scalafmt-non-conformant code → guaranteed CI failure (`backend` "Check formatting") on any format-gated repo → a full CI round-trip + fix-up turn. | prompt quality / efficiency | **Open** (§3.3) — the `implement.*.md` prompt should instruct the driver to run `sbt scalafmtAll` (or the repo's formatter) before settling. |
| 4 | **Fix-up driver hand-formats instead of running the formatter.** Given the CI failure, fix-up #1 hand-reflowed a scaladoc comment by eye (got it wrong → CI failed again); it took **2 fix-up rounds / ~$1.78 / ~12 min** to fix a trivial formatting issue, nearly exhausting the `maxFixupRounds = 3` cap (→ NHI). The `p1.failures.md` context says "run the project's formatter on the changed files" but isn't forceful enough, and it carries only the `gh pr checks` *summary*, not the failing-check *log* (so the driver never sees `scalafmt: 1 file must be formatted`). | prompt quality / efficiency | **Open** (§3.3) — mandate `sbt scalafmtAll` + commit its output ("do not hand-edit formatting"); pipe `gh run view --log-failed` into `p1.failures.md`. |
| 5 | **Transient GitHub rate-limit while polling → hard NHI.** A `GraphQL: API rate limit already exceeded` during the `PieceAwaitingMerge` poll routed the whole run to `NeedsHumanIntervention` with a misleading `RunAnotherFixup` hint (no fix-up was needed — the PR was green). The limit reset within minutes. Aggravated by concurrent `gh` observation calls competing for the GraphQL budget. | resilience | **Open** — a transient/rate-limit poll failure should back off and keep polling (or surface a "wait and re-run" hint), not hard-stop with a fix-up hint. |
| 6 | **`p1.failures.md` fix-up scratch note is committed and merged into `main`.** The transient CI-failure note Forge writes for the fix-up driver lands in the piece PR and merges into `main` as clutter. | minor | **Open** — strip it before the final piece commit (or write it outside the committed tree). |
| 7 | **Reviewer one-shot cost is not in `forge stats`.** The breakdown shows only driver phases (implement/fixup); the design-review and code-review LLM calls aren't folded, so the reported `$2.51` understates total feature cost. | observability gap | **Open** — consistent with design-rationale **S4-3** (reviewer-cost widening). |

## Recovery actions taken this run

Findings #1 and #5 each persisted a (spurious) `NeedsHumanIntervention` to the
action log. Because the log is append-only and both NHIs were artifacts (a
projection bug and a transient infra blip, not real states), each was recovered
by trimming the single bug-induced trailing entry (with a backup), clearing the
state cache, and re-running `forge run` from the rebuilt pre-NHI state — exactly
the post-`/done` `DesignReviewing(1)` (for #1) and `PieceAwaitingMerge` (for #5).
A `forge`-native recovery for #5 (resume-and-keep-polling) does not exist; see
the finding.

## Disposition

- **#1** — fixed in this session (the only blocking item); the fix is the run's
  primary artifact.
- **#3 / #4 / #7** — Phase-2 **§3.3 prompt-iteration** + **S4-3** input; this is
  dogfood run #2 of the "~5–10 real features" §3.3 calls for before revising the
  role prompts.
- **#2 / #5 / #6** — small resilience/UX fixes; candidates for the next Forge
  maintenance pass (not yet scheduled).
