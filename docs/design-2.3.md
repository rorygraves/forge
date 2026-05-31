# design-2.3 — Slice 1.3 implementation plan (✅ CLOSED 2026-05-27)

> **Status:** ✅ closed 2026-05-27. Condensed audit trail — the full per-sub-PR breakdown (§1) and round-by-round status log (§3) were summarised on 2026-05-31 in the Phase-1 docs consolidation; the complete original is recoverable from git history. The live spec is [`forge-design-1.4.md`](forge-design-1.4.md); deferred decisions / carry-forwards live in [`design-rationale.md`](design-rationale.md) and roadmap §7.2.

## Summary

Slice 1.3 stood up the `forge-git` and `forge-app` module skeletons (both started with zero sources) and shipped the components the Slice-4 orchestrator needs to produce every external-world `FsmEvent`. In `forge-git`: `GhClient` / `GitClient` traits with `os-lib`-backed one-shot impls and typed `GhError` / `GitError` ADTs; `PrSnapshotDecoder` + `PollBaseline` (provider-neutral decode of `gh pr view --json …`, with `BaselineCursor(at, seenIds)` cursors and the CI6 `mergeStateStatus` trap handled); `BranchManager` + `BranchProtectionCache` (§9 / §15 / §11.3 logic, in-memory epoch-keyed protection cache); and `PRWatcher` (fs2-stream poller with rate-limit back-off). In `forge-app`: `ProcessLock` (file-channel lock + metadata sidecar per §13) and `SessionMonitor` (settle-timeout / cost-cap enforcer over the four driver phases). Headline outcome: a sacrificial-repo integration path in `forge-it` drove branch creation → push → PR creation → watcher polling → merge transitions against real `gh` + `git` (opt-in via `FORGE_IT_GH_REPO`), and cross-JVM `ProcessLock` contention via `FORGE_IT_RUN_PROCLOCK`.

## 0. Exit criterion (met)

Roadmap §2.3: `forge-git` ships `BranchManager` + `PRWatcher`; `forge-app` ships `ProcessLock` + `SessionMonitor`. Concretely the slice was done — and the close-out confirmed it — when:

1. `forge-git` exposed `GhClient` / `GitClient` (typed CLI seams, no raw HTTP), `PrSnapshotDecoder.decode → Either[DecodeError, DecodedSnapshot]` (owning the CI6 merge-detection trap and the "new since baseline" filter), `BranchManager` (§9 surface: preflight/syncBase/create*Branch/baseFreshness/pushCurrentBranch with force-with-lease/createPr/tag+push/prune), `BranchProtectionCache` (in-memory, keyed by `(featureId, baseBranch, cacheEpoch)`, 1h TTL), and `PRWatcher.watch → Stream[IO, PollResult]` (rate-limit back-off honouring `Retry-After`).
2. `forge-app` exposed `ProcessLock` (`FileChannel.tryLock` + metadata sidecar, returning `Acquired | Stale | Held`) and `SessionMonitor` (per-turn cost / settle-timeout enforcement producing `Settled` / `SettleTimeout` / `TurnBudgetBreached` / `BudgetBreached`, covering the four `SessionPhase` driver phases only).
3. Unit coverage met the "fake-`gh` unit coverage" bar — decoder edge cases against fixture JSON, `BranchManager` §9 rules against fakes, `PRWatcher` back-off/baseline/merge detection, `ProcessLock` same-JVM + sibling-JVM contention, `SessionMonitor` cost/timeout under `TestControl`.
4. One sacrificial-repo integration path ran in `forge-it` against real `gh` + `git` (opt-in `FORGE_IT_GH_REPO`).
5. A section code review confirmed (1)–(4) and that the §4 carry-forward list was durably handed off; the `[~]` bullets in roadmap §2.3 flipped to `[x]`.

## 3. Status log (condensed)

- **2026-05-26** — Slice opened (`design-2.3.md` created on the close of Slice 2 / `design-2.2.md`).
- **2026-05-26** — PR-A landed: `forge-git` skeleton — `GhClient` / `GitClient` traits + `Real*` impls (`os.proc.call` one-shot, **S3-1** filed), `GhError` / `GitError` ADTs + classifiers, `Fake*` fixtures. forge-git 0 → 32 tests.
- **2026-05-26** — PR-B landed: `PrSnapshotDecoder` + `PollBaseline`, `DecodedSnapshot`, `DecodeError` ADT, `Comments.*` helpers, CI6 handling. forge-git 32 → 70.
- **2026-05-27** — PR-B review rounds 1 + 2 (**S3-7**): cursor switched off the non-existent `databaseId` to `BaselineCursor(at: Instant, seenIds)` (one-second-resolution tie-breaker), empty-body posts dropped at decode, `nextBaseline` surfaced via `Comments.advance`. forge-git → 81.
- **2026-05-27** — PR-C landed: `ForgeCommand`, `PreflightReport`, `BranchNaming` (BM7), `BranchProtectionCache` + in-memory impl, `BranchError`, `BranchManager` + `RealBranchManager` (full §9 + `pruneSnapshotTags`), C6 `requiredChecksOverlay`. forge-git 81 → 135.
- **2026-05-27** — PR-D landed: `PRWatcher` + `PollResult` ADT, `PRWatcherConfig` (§18 defaults), `RealPRWatcher` (`pollOnce` + streaming `watch`, baseline-Ref advance on `Snapshot` only, **S3-4** rate-limit escalation). forge-git 135 → 151.
- **2026-05-27** — PR-C/PR-D review round 1: §15 preflight rebuilt, `BaseFreshness.Updated` carries `newBaseSha`, watcher emit-before-sleep fix, `checkout` widened to commit-ish. forge-git → 162.
- **2026-05-27** — PR-E landed: `forge-app` skeleton — `LockMetadata`, `LockAcquireResult`, `ForceReleaseResult`, `ProcessLock` + `FileProcessLock` (same-JVM coverage only; cross-JVM in PR-G). forge-app 0 → 11; review round 1 reworked acquire around per-instance refcounting → 14.
- **2026-05-27** — PR-F landed: `MonitorOutcome`, `SessionMonitor` + `SessionLimits`, `RealSessionMonitor` (Deferred-based race; §12 check 3 kills on turn/settle, check 2 doesn't on feature/piece; reviewer/refine phases refused per **S3-5** / **S2-8**). forge-app 14 → 36. Review rounds 1 + 2 fixed kill-before-publish ordering, end-of-turn budget flush, and infallible-kill handling (`killError`) → 46.
- **2026-05-27** — PR-G landed: sacrificial-repo `BranchManagerIntegrationSuite` (G2) and `ProcessLockMultiJvmSuite` (G3, opt-in `FORGE_IT_RUN_PROCLOCK`). IT-only `RealGhClient.prMerge`. IT surfaced a decoder bug → **S3-8** (`reviewDecision: ""` decodes as `None`); also filed **S3-6** (`gh pr create` has no `--json` flag). forge-git → 163.
- **2026-05-27** — PR-H review round 1: `apiBranchProtection` 404-only flattening + `RequiredChecksOverlay.source` discriminator; `forge-it` removed from root aggregation so `sbt test` is unit-only.
- **2026-05-27** — PR-H landed; **Slice 3 closed.** Section review returned "no findings"; roadmap §2.3 flipped to `[x]` (status line bumped to draft v0.8, Slices 1/2/3 closed); S3-1…S3-8 placed in §7.2 buckets; this file flipped to audit trail. Final scope: forge-core 358, forge-agents 181, forge-git 163, forge-app 46, forge-it 10 default-on + 5 opt-in.

## 4. Carry-forward (dispositions)

PR-H's H1 coherence review returned "no findings"; expected and actual sets matched 1:1. All entries below have a durable home in [`design-rationale.md`](design-rationale.md) and/or [`roadmap.md`](roadmap.md) §7.2.

### Inherited from Slices 1–2

- **C14** — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a) system-prompt prepending. Untouched by Slice 3; rolls forward to Slice 4B per roadmap §7.2.2.
- **C15** — Native schema regression suite deferred to the reviewer-asset PR. Untouched by Slice 3; rolls forward to Slice 4A.
- **S2-1 … S2-10** — Slice-2 spec deviations (design-rationale "Slice 2 spec deviations"). Three direct touch-points noted: **S2-2** (Slice 3 is first consumer of the `FsmEvent` ADT — H1 verified it sufficient), **S2-5** (`createPieceBranch` returns `(BranchName, Sha)` but does NOT persist the manifest mutation — the atomic write is Slice 4's obligation; PR-C C3 docstring anchors it), **S2-8** (SessionMonitor covers only the four driver phases; reviewer/refine caps land in Slice 4A — F4 docstring anchors it).

### New in Slice 3

All filed in [`design-rationale.md`](design-rationale.md) "Slice 3 spec deviations" except **S3-2**, which lives only as a conditional watch item in roadmap §7.2.3 (reopens as a rationale entry only if a cost cliff surfaces). At close, S3-6/S3-7/S3-8 went to §7.2.1 (spec-text edits), S3-2/S3-4 to §7.2.3 (conditional watch), S3-1/S3-3/S3-5 to §7.2.4 (no spec change).

- **S3-1** — `forge-git` uses `os-lib` `os.proc.call` (one-shot), not `forge-agents.Subprocess` streaming. Module-layout call; no v1.3 spec change.
- **S3-2** — `BranchProtectionCache` is process-local in-memory (no on-disk persistence). Default: epoch bump on `forge resume` re-fetches from `gh api` (~150ms). Conditional watch in roadmap §7.2.3.
- **S3-3** — `GhClient` / `GitClient` trait abstractions (a testability seam not in §9). Filed against rationale.
- **S3-4** — `PRWatcher.PollResult.RateLimited` is a non-failing stream event (not a `GhError`); after three consecutive, emits `Failed`. Slice 4 may tighten.
- **S3-5** — SessionMonitor scope excludes reviewer/refine phases (parallels S2-8); their wall-clock caps live in the Slice-4A reviewer-asset PR.
- **S3-6** — `gh pr create` has no `--json` flag; PR-number capture is a stdout-URL regex parse. v1.3 rationale BM8 should be corrected.
- **S3-7** — `PollBaseline` cursors are `BaselineCursor(at: Instant, seenIds: Set[String])`, not `databaseId: Long`; empty-body posts dropped at decode (PR-B review rounds 1 + 2). v1.3 RL2 pins the cursor type, the same-second tie-breaker mechanic, and the empty-body filter.
- **S3-8** — `reviewDecision: ""` decodes as `None` (`gh` flattens GraphQL null to empty string); PR-G IT-surfaced, PR-B decoder fix. Scoped to `reviewDecision` only; v1.3 §9 should note the quirk.

## 5. Cross-references

- v1.2 spec: §8 / §8.1 (CI policy + branch-protection cache), §9 (`BranchManager` / `PRWatcher` traits), §15 (command-aware preflight), §13 (`ProcessLock`), §7.9 + §12 (`SessionMonitor` settle bounds + budget enforcement).
- Lifecycle steps Slice-3 components surface events for: §11.2 step 13 (PR open), §11.3 step 5 (force-push-with-lease), §11.4 steps 1 + 6 (`createPieceBranch` / `createPr`), §11.5 (`Merged`, CI + review polling).
- Action-log signals produced: §19 (`gh.poll`, `gh.action`, `harness.rate_limited`, `harness.session_killed`).
- Backing decisions: design-rationale **BM1** (syncBase + ff), **BM2** (stale base), **BM3** (command-aware preflight), **BM4** (OS lock + metadata), **BM5** (stale-lock UX), **BM6** (commit-human-fix branch check), **BM7** (derived branch names), **BM8** (PR number capture), **CI5** (epoch-scoped cache), **CI6** (`mergeStateStatus` trap), **RL1** (rate-limit + caching), **RL2** (baseline IDs).
- Slice 0 wire-shape findings consumed by `PrSnapshotDecoder`: `slice-0/slice-0-report.md` §9 (`gh pr view --json` field set), §10 (branch-protection endpoint), §11 (line-based comment API).
- Phase context + seam discipline: `roadmap.md` §2.3 (this slice), §2.6 (seams to leave open).
- Predecessors: `design-2.1.md` (Slice 1, closed 2026-05-26), `design-2.2.md` (Slice 2, closed 2026-05-26).
