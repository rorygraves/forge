# Forge — product roadmap

> Companion to [`forge-design-1.4.md`](forge-design-1.4.md). The design doc is
> the implementation contract for v1; this document is the multi-horizon plan
> the design lives inside. Early phases are concrete (and trace directly into
> §17 of the design); later phases capture direction and have not yet been
> turned into specs.
>
> **Status:** draft v0.13 — 2026-06-02. **DIRECTION CHANGE (2026-06-02):** the roadmap is re-centered on _repo adaptation_ — Forge grows agentic *senses* on its deterministic spine so it stops running blind on a repo (RepoProfiler, FailureClassifier, ConventionLearner; see §4 Phase 3 and design-rationale **A5**). The compact picture is in "## Status at a glance" below; this paragraph keeps the detailed history. **Phase 1 (MVP) ✅ COMPLETE — Slices 1.1, 1.2, 1.3, and 1.4 all closed.** Phase 2 (MLP) is open; **Slice 2.0 (run observability, §3.1) ✅ closed 2026-05-31** — all three tiers landed (`cost.update` + `session.complete` writers, `forge stats`, work-vs-wait markers, driver raw-dump, clean resume-from-NHI); the final implement-turn `forge stats` capture against real CLI output is a watch item (the writers + fold are unit-tested). **§3.5 driver-respawn-avoidance (the D3 large half) ✅ closed 2026-06-01** — a restart from a mid-exploration implement/fix-up crash now resumes the existing driver session instead of re-paying the full exploration (gap #10), gated by a worktree-safety classifier; `forge stats` folds the resumed turn as a measured saving. The live contract is now **[`forge-design-1.6.md`](forge-design-1.6.md)** (1.4 and 1.5 → redirect stubs), which reconciles the Slice-2.0 §19 additions, the §3.5 D3 deltas, and (new in 1.6) the §3.3 termflow `0.4.0` dependency + §3.3.1 Scala-3.7.1 floor against what Slice 2.1 shipped; §3.5's second bullet (§18 reviewer/driver tuning, S4-3/S4-5) stays open. Audit trails: [`design-2.0.md`](design-2.0.md), [`design-3.5.md`](design-3.5.md), [`design-2.1-tui.md`](design-2.1-tui.md). **Slice 2.1 (TUI, §3.2) ✅ closed 2026-06-01** — plan in [`design-2.1-tui.md`](design-2.1-tui.md); Tasks 2.1.1→2.1.4 plus 2.1.6→2.1.8 landed (read-only `forge tui <feature>`, polling snapshot builder, scrollable log pane, Q&A display, resize-aware themed view, key-help overlay, and the Task 2.1.8 close-out → v1.6). Task 2.1.5 live `AgentEvent` tap is carried forward unless real use needs token-level liveness; the termflow-`0.4.0`-to-Central watch item (rolled to §3.4) has since ✅ resolved upstream — `0.4.0` is on Maven Central as of 2026-04-30, so fresh clones resolve the build (the spec §3.3 note corrects in the next 1.7 revision).
> Slice 1.1 (Task 1.1.1 → Task 1.1.5 in [`design-2.1.md`](design-2.1.md)) ships
> both connectors against the v1.2 §7.1 streaming-spec trait with
> real-CLI integration tests in `forge-it`. Slice 1.2 (Task 1.2.1 → Task 1.2.7 in
> [`design-2.2.md`](design-2.2.md)) ships `forge-core` — `ForgePaths`
> + relocated manifest types, `FsmState`/`FsmEvent`/`Feature`,
> `Fsm.transition` per §11, `FileActionLog` + `Feature.foldEvents`,
> `FileStateCache` + `RebuildState.run`, and a property-test suite
> covering the §17 slice-2 invariants. Slice 1.3 (Task 1.3.1 → Task 1.3.8 in
> [`design-2.3.md`](design-2.3.md)) ships `forge-git` (`GhClient` /
> `GitClient` one-shot CLI seams, `PrSnapshotDecoder` + `PollBaseline`
> with `BaselineCursor(at, seenIds)`, `BranchManager` +
> `BranchProtectionCache`, `PRWatcher`) and `forge-app` (`ProcessLock`,
> `SessionMonitor`) — every component the Slice 1.4 orchestrator needs
> to produce `FsmEvent`s from the outside world. Carry-forwards to
> v1.3 / Slice 1.4: **C14**, **C15**, **S2-1** through **S2-10**, and
> **S3-1** through **S3-8** (each with a durable home in
> [`design-rationale.md`](design-rationale.md) or §7.2 below — §7.2
> is grouped by what closing each item requires). Next active slice:
> 4, split dependency-shaped per v0.7 into **Slice 1.4a** (reviewer assets,
> `forge-specs` repopulation, Task 1.4.7 regression gate) → **Slice 1.4b**
> (orchestrator loop, CLI, self-hosting gate). MVP-gate recommendation
> stays as v0.7: pick a contained, low-variance first feature, not
> "Forge builds its own Slice 2.1 (TUI)".

## Status at a glance

| Phase | Theme | State |
|---|---|---|
| 0 — Slice 0 | CLI capabilities validated | ✅ closed |
| 1 — MVP | Single-repo self-host end-to-end | ✅ closed |
| 2 — MLP | Pleasant single-repo daily-driver | open — Slice 2.0 (observability) ✅, Slice 2.1 (TUI) ✅, §3.4 OSS-readiness ✅ (config example + README + standalone `forge` launcher; published-binary polish still open); **§3.5 tuning + S4-3 reviewer-cost widening ✅ (Slice 2.2, contract 1.16 — whole-section review landed clean 2026-06-05)**; a small dogfood-#2 resilience pass still open |
| 3 — v1.0: **Repo Adaptation** ⭐ | Deterministic spine + agentic senses; works on any repo via its CLAUDE.md + a derived, hashed `RepoProfile` | ✅ **closed 2026-06-05** (contract `forge-design-1.15.md`) — **all six sub-slices 3.0–3.5 ✅ closed**; **exit criterion MET live** (`queryclient-config` on the Node/TS fork `rorygraves/toast-stats` → `FeatureDone`, §8.2 prettier-collapse; [`design-phase3-exit.md`](design-phase3-exit.md) closed); **whole-section review landed** — all findings fixed (F3 CiReadiness late-check, S4-5 reviewer config, D4 commit identity, P0 `pr_based`/schema-2 trunk-push safety, P1 §8.2 autofix staging, P2 profiler reads README) |
| 4 — v2.0 | Workspace (multi-repo) + workstreams + daemon + multi-workstream cockpit TUI | **design ratified 2026-06-05** ([`forge-design-2.0.md`](forge-design-2.0.md)); implementation not started (sub-slices 4.0–4.6, §5/§11) |
| 5 — v3.0+ | Agentic-dev cockpit (knowledge base, reactive review, custom triggers) | concept |

**Live docs:** this roadmap · spec [`forge-design-1.16.md`](forge-design-1.16.md) · [`design-rationale.md`](design-rationale.md) · index [`README.md`](README.md). The per-slice `design-*.md` files (`design-1.4.md` … `design-3.5.md`) are *closed* audit trails kept for historical reference; [`design-2.2-tuning.md`](design-2.2-tuning.md) (Slice 2.2 — §3.5 driver/reviewer tuning + S4-3) is the most recent, **✅ closed 2026-06-05 (whole-section review landed clean)**. The index sorts live-vs-historical.

---

## 0. How to read this

| Phase | Outcome | Source of detail |
|---|---|---|
| 0 — Slice 0 | CLI capabilities validated | [`slice-0/slice-0-report.md`](slice-0/slice-0-report.md) |
| 1 — Testability MVP | Forge ships its own next slice | `forge-design-1.4.md` §17 slices 1–4 |
| 2 — MLP | Pleasant single-repo daily-driver | §17 slice 5 + polish |
| 3 — v1.0: Repo Adaptation | Works on a stranger's repo / any stack via its CLAUDE.md + derived profile; deterministic spine + agentic senses | §4 + `forge-design-1.7.md` (to open) + role-trait refactor |
| 4 — v2.0 | Forge-instance pivot (multi-repo, daemon, parallel, containerised) | [`forge-design-2.0.md`](forge-design-2.0.md) (design ratified 2026-06-05; implementation sub-slices 4.0–4.6) |
| 5 — v3.0+ | Agentic-dev cockpit (knowledge base, reactive review, triggers) | Concept notes only |

Phases are gates, not calendar quarters. Each gate has an explicit exit
criterion; we don't move on until the prior phase actually passes it.

---

## 1. Phase 0 — Slice 0 (complete)

Done 2026-05-25. Findings folded into design v1.1: Native schema on both
reviewers, session-id preserved across resume on both CLIs, three small
Codex-adapter notes. No scope narrowing.

---

## 2. Phase 1 — Testability MVP

**Exit criterion:** Rory drives one real, small feature on the Forge repo
itself through Forge end-to-end from the command line. This is the
self-hosting moment — not "all the commands compile."

Maps 1:1 to design §17 slices 1–4. Nothing to invent at the spec level;
risks are integration-shaped.

### 2.1 Slice 1.1 — Agent connectors

`forge-agents` standalone with CLI demo + integration tests.

- [x] `AgentSession`, `StreamingSession`, `Connector` traits per §7.1.
- [x] `Role` indirection seam (Phase 4/5 enabler — see §2.6 below).
- [x] Codex price-table (`PriceTable` + `ModelPrice` + `CodexTokens`) and
  shipped `prices.example.json` resource covering the current Codex
  lineup (`gpt-5-codex`, `gpt-5.1-codex{,-max,-mini}`, `gpt-5.2-codex`,
  `gpt-5.3-codex`, `codex-mini-latest`). Formula follows OpenAI's usage
  shape (cached as subset of input, reasoning as subset of output).
- [x] Codex system-prompt prepending (`CodexPrompt.withSystemBlock`,
  §7.10(a)).
- [x] Codex sticky-settings rule (`CodexSessionSettings` value type +
  `isCompatibleForResume`, §7.10(c)).
- [x] `ClaudeConnector` and `CodexConnector`, both
  `schemaMechanism = Native`. Slice 1.1 covered: event parsers,
  `Subprocess` + `StreamingDriver` plumbing (`send` JSON-encoder hook,
  `UserMessage` mirror event, `initialUserInput`, `encodeAnswer` hooks
  per v1.2 §7.1), Claude + Codex headless driver methods, Layer 5
  reviewer one-shots (`reviewDesign` / `reviewPr` / `refine`) with
  shared `ReviewDecoders` + `ReviewerPrompts` and typed retryable vs
  non-retryable adapter errors, `AgentEvent.AskUserQuestion` carrying
  `toolUseId: Option[String]`,
  `StreamingSession.answerQuestion(toolUseId, answer)` plumbed through
  `StreamingDriver` with a connector-supplied `encodeAnswer` hook,
  `ClaudeConnector.runStreamingSpec` / `resumeStreamingSpec` against
  the §7.2 `tool_result` frame, `CodexConnector` streaming via the
  multi-process `CodexStreamingSession` facade (one `codex exec
  [resume] --json` subprocess per turn under
  `cats.effect.std.Mutex`, single shared events Channel with
  resume-turn Init filtered, thread-id mismatch raises, per-turn
  failure surfaces non-zero exit / missing Result to the caller).
  Closed 2026-05-26 by [`design-2.1.md`](design-2.1.md) Task 1.1.1 → Task 1.1.5.
- [x] `HaltWithQuestion` parsing + re-spawn loop for Codex. Envelope
  decoder (`HaltWithQuestion.detect` / `tryParse`) lands in this
  slice; the orchestrator-side re-spawn loop lands with slice 2
  (FSM) — that's an orchestrator concern, not a connector one.
- [x] Integration tests on real CLIs. Claude headless hello-world
  smoke, `ClaudeStreamingSpecSuite` (resume preserves session id,
  kill mid-stream, `answerQuestion` end-to-end against a contrived
  `AskUserQuestion`), Codex headless smoke, `CodexStreamingSpecSuite`,
  and `CodexHaltWithQuestionReliabilitySuite` (opt-in via
  `FORGE_IT_RUN_RELIABILITY=1`) land in `forge-it`. The reviewer
  ≥19/20 native schema regression suite (Slice 1.1 PR-D historical; deferred to Task 1.4.7) is deferred to the
  reviewer-asset PR per design-rationale **C15**; fake-CLI
  end-to-end reviewer coverage in `*ConnectorSuite` is the Slice 1.1
  bar.

✅ **Slice 1.1 closed 2026-05-26.** Detailed history of how it got
there (Task 1.1.1 through Task 1.1.5) lives in
[`design-2.1.md`](design-2.1.md) §3 (status log) and §4
(carry-forward to v1.3). Carry-forward bullets — **C14** (Codex
`resumeStreamingSpec` system-prompt prepending) and **C15**
(Slice 1.1's PR-D regression suite deferred to Slice 1.4 — lands
as Task 1.4.7) — have durable homes in
[`design-rationale.md`](design-rationale.md) and §7.2 below.

### 2.2 Slice 1.2 — FSM, Feature, ActionLog, StateCache

- [x] `ForgePaths(repoRoot)` owns every `.forge/` location; build-gated
  smell test (`ForgePathsSuite` `os.walk` sweep) blocks new
  `".forge` literals outside the helper.
- [x] Manifest data types relocated from `forge-specs` to `forge-core`
  (`io.forge.core.manifest`); spec deviation tracked as **S2-1** in
  [`design-rationale.md`](design-rationale.md).
- [x] `FsmState` / `FsmEvent` / `Feature` / `ResumeHint` / `Action`
  domain model with codecs (Task 1.2.2). `PrSnapshot` ADT and core-side
  reviewer-verdict projections live in `forge-core` per v1.2 §3.2.
- [x] `Fsm.transition(feature, event, config): (Feature,
  Vector[ActionDraft])` — pure, covers every §11 lifecycle rule.
- [x] `FileActionLog` (NDJSON append-only, `APPEND | SYNC`, replay
  truncate-and-recover on partial trailing line) and
  `Feature.foldEvents` projecting every §6.1 field plus
  `observedTransitions` / `observedPieceMerges` for reconcile.
- [x] `FileStateCache` (atomic temp + `Files.move(ATOMIC_MOVE)` + parent
  fsync) and `StateCache.verifyAgainstLog` per §11.0 step 4.
- [x] `RebuildState.run(featureId, paths, manifestStore, log, cache)`
  with pure `reconcile` over the four §11.5 partial-merge sub-cases.
- [x] Property-test suite covers §17 slice-2 invariants 1–13; invariant
  14's writer-side test is deferred to Slice 1.4 (S2-5).

✅ **Slice 1.2 closed 2026-05-26.** Detailed history of how it got
there (Task 1.2.1 through Task 1.2.7) lives in
[`design-2.2.md`](design-2.2.md) §3 (status log) and §4
(carry-forward to v1.3). Carry-forward bullets — **S2-1** (manifest
relocation), **S2-2** (FsmEvent ADT shape), **S2-3** (ActionLog
durability), **S2-4** (PrSnapshot ownership in `AGENTS.md`), **S2-5**
(writer-side atomic-merge test, Slice 1.4), **S2-6**
(`designPrFeedbackRound` projection), **S2-7** (`fsm.transition`
payload encoding), **S2-8** (`SettleTimeout` reviewer/refine phase
coverage), **S2-9** (`verifyAgainstLog` cache-write skip on
Consistent), **S2-10** (`audit.piece_merged` payload key tightened
to `"p"` only) — have durable homes in
[`design-rationale.md`](design-rationale.md) and §7.2 below.

### 2.3 Slice 1.3 — BranchManager, PRWatcher, ProcessLock, SessionMonitor

- [x] `forge-git` module skeleton — `GhClient` / `GitClient` traits
  with `os-lib`-backed `RealGhClient` / `RealGitClient` one-shot CLI
  invocation; typed `GhError` / `GitError` ADTs (rate-limit,
  not-found, auth, transient, parse-failure); `FakeGhClient` /
  `FakeGitClient` builder fixtures. Subprocess-utility ownership
  decision (no `forge-agents` dependency, no `forge-core` streaming
  primitive) filed as **S3-1**.
- [x] `PrSnapshotDecoder` + `Comments.unseen` / `Comments.advance`
  — pure `ujson.Value → Either[DecodeError, DecodedSnapshot]`
  decoder covering every §6 field, including the `mergeStateStatus`
  trap (CI6: merge driven by `state == "MERGED"` + non-null
  `mergedAt`), the `reviewDecision: ""` null-flattening quirk
  (**S3-8**), and the empty-body filter on `unseenComments`.
  `PollBaseline` cursors are
  `BaselineCursor(at: Instant, seenIds: Set[String])` with a
  round-2 same-second tie-breaker (**S3-7**); `Comments.advance`
  surfaces the next cursor on `DecodedSnapshot.nextBaseline` so the
  orchestrator persists exactly what the next poll needs.
- [x] `BranchManager` + `BranchProtectionCache` — full §9 surface
  (`preflight` per §15, `syncBase` per BM1, `createDesignBranch` /
  `createPieceBranch` returning `(branch, baseSha)`, `baseFreshness`
  per BM2 with `Updated(newBaseSha)` re-read after
  `gh pr update-branch`, `pushCurrentBranch` with force-with-lease
  surfacing `BranchError.ForceLeaseRejected` per §11.3 step 5,
  `createPr` per BM8 via stdout-URL parse (**S3-6**),
  `tagSnapshot` / `pushTag` / `deleteRemoteTag` /
  `pruneSnapshotTags` per §11.3 step 4 retention).
  `(featureId, baseBranch, cacheEpoch)`-keyed in-memory cache per
  CI5 with TTL eviction and an Unauthorized-empty-overlay fallback.
  Process-local in-memory cache scope filed as **S3-2** (watch item
  only).
- [x] `PRWatcher` — `fs2.Stream[IO, PollResult]` polling against
  `GhClient.prView` with the §9 pinned 11-field set, rate-limit
  back-off honouring `Retry-After` per RL1, baseline cursor
  advancement on `Snapshot` only per S3-7 round 2, and three-
  consecutive-rate-limits-before-failing per **S3-4**. `pollOnce` +
  `watch(pr, baselineRef)` factory methods covering both startup
  one-shot snapshots and the continuous-polling FSM driver path.
- [x] `forge-app` module skeleton — `ProcessLock` per §13
  (`FileChannel.tryLock` on `paths.lockFile` + sibling
  `paths.lockMetadataFile`), per-instance reference counting so
  nested same-JVM acquires share the OS lock, `LockAcquireResult`
  = `Acquired | Stale(meta) | Held(otherMeta)` per BM4 / BM5,
  `forceRelease` with `LiveHolderRefused` against an in-process
  holder.
- [x] `SessionMonitor` per §12 / §7.9 — watches the connector's
  `Stream[IO, AgentEvent]`, tracks per-session elapsed time +
  accumulated `BigDecimal` cost, invokes `session.kill()` on settle
  timeout or per-turn cost breach (§12 check 3); feature/piece
  budget breaches emit `MonitorOutcome.BudgetBreached` without
  killing (§12 check 2, end-of-turn flush). Kill-failure resilience
  via `killError: Option[String]` on `SettleTimeout` /
  `TurnBudgetBreached`. Scope is the four driver phases
  (`Spec`, `DesignRevision`, `Implement`, `Fixup`); reviewer/refine
  phases deferred to Slice 1.4a per **S3-5** / S2-8. Trait abstractions
  on `GhClient` / `GitClient` filed as **S3-3** (testability seam).
- [x] Fake-`gh` unit coverage — `PrSnapshotDecoderSuite` against 11
  fixture JSON files under `gh-pr-view/` plus inline negative cases,
  `CommentsSuite` (round-2 cursor mechanics), `BranchManagerPreflightSuite`,
  `BranchManagerBaseFreshnessSuite`, `BranchProtectionCacheSuite`,
  `PRWatcherRateLimitSuite` / `…BaselineSuite` /
  `…MergedDetectionSuite`, `FileProcessLockSuite`, and
  `SessionMonitorSettleSuite` / `…TurnCostSuite` / `…FeatureCostSuite`
  / `…PieceCostSuite` / `…PhaseCoverageSuite` /
  `…ReviewRound{1,2}Suite`. `forge-git` 163 tests, `forge-app` 46
  tests across the new sources.
- [x] Sacrificial-repo integration path —
  `BranchManagerIntegrationSuite` + `ProcessLockMultiJvmSuite` in
  `forge-it`, opt-in via `FORGE_IT_GH_REPO` / `FORGE_IT_RUN_PROCLOCK`
  per the default-on `<60s` budget; drives clone → bootstrap-main →
  syncBase → createPieceBranch → push → createPr → pollOnce(Open) →
  prMerge → pollOnce(Merged) against real `gh` + `git`. IT surfaced
  the `reviewDecision: ""` decoder quirk now pinned as **S3-8**.

✅ **Slice 1.3 closed 2026-05-27.** Detailed history of how it got
there (Task 1.3.1 through Task 1.3.8) lives in
[`design-2.3.md`](design-2.3.md) §3 (status log) and §4
(carry-forward to v1.3). Carry-forward bullets — **S3-1** through
**S3-8** — have durable homes in
[`design-rationale.md`](design-rationale.md) and §7.2 below.

### 2.4 Slice 1.4 — Reviewer assets + `forge-specs` (Slice 1.4a) → headless orchestrator + REPL (Slice 1.4b)

✅ **Slice 1.4 closed — 2026-05-31. This closes Phase 1 (MVP).**
Implementation plan / audit trail lives in
[`design-1.4.md`](design-1.4.md) (Task 1.4.1 through Task 1.4.17 across
Slice 1.4a + Slice 1.4b). Slice 1.4a (Task 1.4.1 → Task 1.4.8, closed
2026-05-29) shipped the writable foundation: reviewer assets under
`~/.forge/`, the `ReviewerCall` wall-clock wrappers, the repopulated
`forge-specs` (`SpecStore` / `DocSync` / `ChangeCollector`), the v1
templates, and the Task 1.4.7 regression gate (C15 closed — ≥19/20 for
all six method × connector pairs with the v1 `haiku` / `gpt-5.3-codex`
reviewer config). Slice 1.4b (Task 1.4.9 → Task 1.4.17, closed
2026-05-31) shipped the headless `Orchestrator` (the §11 feature loop
over `Fsm.transition`, S2-5 atomic-persist order), the eleven §15 CLI
commands + the `forge spec` line-mode REPL, `ForgeConfig`, and the
Task 1.4.16 MVP self-host run that drove a real feature end-to-end
through Forge to `FeatureDone`.

Slice 1.4 is the largest of Phase 1 and has accreted a meaningful set of
implicit deliverables. Split dependency-shaped: Slice 1.4a ships the writable
foundation, Slice 1.4b builds the orchestrator on top. **TUI deferred to
Slice 5.**

**Slice 1.4a — reviewer assets, `forge-specs` repopulation, regression gate.**

- Reviewer schemas under
  `~/.forge/schemas/{design-review,code-review,refine}.json` per v1.2
  §17 / §10.2 / §14.3. The middle filename is `code-review` (not
  `pr-review`); the location is `~/.forge/` (not in-repo). Matches
  what `Reviews.scala` / `ReviewDecoders.scala` already encode. See
  design-rationale C15.
- Reviewer system-prompt files (per-method) under `~/.forge/prompts/`.
- PR body / decomposition / answer templates per §11.4 / §7.7.
- `SpecStore` (manifest + design + decomposition persistence) in
  `forge-specs`.
- `DocSync` (rewrites `decomposition.md` from `manifest.json`) in
  `forge-specs`.
- `ChangeCollector` (Allow/Deny/Ask classification per §10.1) in
  `forge-specs`.
- **Task 1.4.7 regression suite (C15)** — ≥19/20 native schema bar on
  `reviewDesign` / `reviewPr` / `refine` against each connector.
  Gating check on Slice 1.4a close. Failure → schema/prompt tightening
  inside Slice 1.4a, not deferred further.

**Slice 1.4b — orchestrator loop, CLI, self-hosting gate.**

- Headless feature loop wiring §11 lifecycle steps through the
  Slice 1.2 FSM and the Slice 1.3 watchers/lock/monitor.
- Line-mode REPL (no TUI) for the §17 command set: `forge new`,
  `forge spec`, `forge run`, `forge status`, `forge resume`,
  `forge reconcile`, `forge refresh-cache`, `forge abandon`,
  `forge rebuild-state`, `forge unlock --force`.
- **C14 resolution** — ✅ closed in Task 1.4.14 (option ii): v1.3 §7.1 /
  §7.10(a) widens the trait to carry `systemPromptPath` on
  `resumeStreamingSpec`; the orchestrator's resume path passes the
  spawn-time `specify` prompt, Codex re-prepends it, Claude ignores it.
- **S2-5 writer-side atomic-merge test** — ✅ closed in Task 1.4.11
  (`OrchestratorAtomicMergeSuite`): asserts the orchestrator persists
  `manifest.json` before the FSM transition action and the state-cache
  write (§11.5 step 1 writer side); cache lags at the pre-transition state.
- **S2-8 settle-timeout coverage decision** — either explicit
  reviewer/refine handlers in `Fsm.transition` (with
  `ResumeHintCoverageSuite` rows) or documented orchestrator-side
  conversion to `HarnessError`.
- **S2-9 `verifyAgainstLog` skip-on-consistent** — measure perf
  under orchestrator load; fix if it fires hot.
- **S2-3 `ActionLog` sync trade-off** — only act if Slice 1.4b surfaces
  a per-batch perf cliff under real load.
- **Targeted polish (§2.5)** lands as part of Slice 1.4b, not as a
  separate slice.

**MVP gate (Slice 1.4b exit, Phase 1 exit gate): ✅ PASSED 2026-05-31.**
Drove the feature `image-creds-dedup` end-to-end through Forge against the
external test repo `llm4s/szork` (real GitHub Actions CI + branch protection),
`forge new` → `forge spec` → `forge run` → `FeatureDone`, both PRs merged. The
full §11 lifecycle ran, including the §8 CI gate on both paths (fail→fix-up→
green). The run surfaced 13 integration gaps (12 fixed, gap #7 deferred) and a
class of **observability gaps now captured as Slice 2.0** (§3.1) — Forge cannot
yet measure its own cost/latency. Task 1.4.16 audit trail in
[`design-1.4.md`](design-1.4.md); findings in
[`slice-4/mvp-friction.md`](slice-4/mvp-friction.md). The formal Slice 1.4
close-out (Task 1.4.17, 2026-05-31) is **done** — full unit suite green
(1234 tests across the five modules; `forge-it` compiles), the
carry-forward walk landed (C14 / C15 / S2-5 / S2-8 / S3-5 closed;
S4-3 / S4-5 / S4-6 rolled to Phase 2), and this §2.4 block is ✅ closed.

### 2.5 Targeted polish in Phase 1

These are MVP-gate enablers, not "nice to haves" — Forge is unusable
without them:

- Clear human-readable rendering of every `NeedsHumanIntervention` reason +
  its `ResumeHint` (six paths). The CLI says exactly what to run next.
- `forge status` outputs something a human can read at a glance: current
  state, current piece, last action, budget remaining.
- `.forge/log/<feature>.jsonl` tailable via `forge tail <feature>`.
- `forge rebuild-state` proven on a corrupted cache.

### 2.6 Architectural seams to leave open in Phase 1

We know Phase 4 is coming; leaving seams costs almost nothing now. Both
of the explicit seams below are folded into design §17 (Slice 1.1
role-trait, Slice 1.2 paths helper) so they ship as part of the v1 work
rather than as a separate "Phase 4 prep" pass:

- **Paths helper (design §17 Slice 2).** Every `.forge/` location
  resolved through a single `ForgePaths` object; no caller hardcodes a
  `.forge/...` literal. Phase 4 swaps the constructor to re-root
  state/log/lock at `~/.forge/instances/<name>/` while leaving
  in-repo specs/audit alone. Test rule: `grep '"\.forge/'` outside the
  helper is a smell.
- **Role-trait stub (design §17 Slice 1).** Connectors and orchestrator
  callers route through a thin `Role` indirection (`Role.Driver`,
  `Role.Reviewer`) instead of pattern-matching on `Mode`. v1 keeps the
  two-case `Mode` ADT; the seam is purely about call-site discipline so
  the Phase 3 *full* role-trait refactor (§4.2) has nothing to
  disentangle. Caller-side rule: `match m: Mode` outside `Mode` itself
  and connector construction is a smell.
- **`.forge/state/.lock` scope is "this checkout".** Don't assume it's
  the only lock in the world. Phase 4 introduces an instance-level lock
  above it.
- **No global singletons.** Pass config/paths through; don't reach for
  process-wide statics.

That's it. Don't invent daemon hooks, container hooks, or workstream
abstractions in Phase 1 — the role-trait *stub* is the only Phase 3/4
seam that lands earlier than its phase, and only because the cost of not
doing it grows with every connector caller written against `Mode`
directly.

---

## 3. Phase 2 — MLP

**Exit criterion:** you'd choose Forge over running Claude Code directly
for any new feature on this repo.

Maps to design §17 slice 5 plus the polish that only real use surfaces.

### 3.1 Slice 2.0 — Run observability (instrument before optimise)

**Why this is first.** The Phase-1 MVP-gate run (Task 1.4.16) proved Forge
*works* but also proved we cannot *measure* it. The action log captures FSM
transitions + timestamps only, and the timestamps conflate three different
things — Forge working, Forge waiting on a human, and the operator stopping the
run to patch code and relaunch. Token counts and per-turn cost flow through the
orchestrator at runtime (`ClaudeEventParser` → `AgentEvent.CostUpdate`) but are
consumed by the cost-cap check and then **discarded**: the §19 `cost.update`
action is fully specified and replayable (`Replay.applyCostUpdate`, `CostTotals`
on `Feature`) yet **no app-layer code ever writes it** — the szork run's log has
zero cost entries, so the entire cost-projection subsystem is dead
infrastructure. The one hard efficiency datum from the run — gap #10's 2.18M
tokens / 21 min / $9.56 implement turn — was read off the live CLI and is now
unrecoverable. Phase 2's exit criterion ("you'd choose Forge over running Claude
directly") is unmeasurable until this is fixed, and the prompt-iteration (§3.3)
and TUI (§3.2) below both *consume* this data — so observability lands first.
Findings: [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md); evidence:
[`slice-4/mvp-run/image-creds-dedup/`](slice-4/mvp-run/image-creds-dedup/).

> **Progress (2026-05-31):** Tiers 1–3 all ✅ landed — Tier 1 (Tasks 2.0.1
> `cost.update` writer + 2.0.2 `session.complete`, D2 turn/piece-reset bug fixed
> en route), Tier 2 (Task 2.0.3 `forge stats <feature>` + Task 2.0.4 work-vs-wait
> markers / wait column), Tier 3 (Task 2.0.5 driver raw-dump + Task 2.0.6 clean
> resume-from-NHI). Task 2.0.7 close-out ✅ done: the spec was reconciled into the
> new standalone [`forge-design-1.4.md`](forge-design-1.4.md) (§19
> `session.complete` / `fsm.transition` `wait` / `audit.resume_from_nhi`), the §4
> carry-forward walked into durable homes (§3.5 below + design-rationale
> S4-3/S4-5), a local section code review landed one cleanup (raw-dump
> generalisation). The checkboxes are now ticked. **Live re-validation residual:**
> driving the exit-criterion run reproduced and fixed **gap #7** (`designSessionId`
> was never written to the log → `forge run` dead-ended before implement; now wired
> at the spec `<actor>.spawn` seam, with a rebuild regression). The final
> implement-turn `forge stats` capture against real CLI output was not driven to
> completion (it needs a fresh interactive spec + design-PR merge); the
> `cost.update`/`session.complete` writers and the `forge stats` fold are covered by
> unit tests + a live graceful-degradation check, so the exit criterion is met in
> substance with the live implement-turn capture as a watch item. Per-Task detail
> and dated status log live in [`design-2.0.md`](design-2.0.md).

**Tier 1 — close the capture gap (the machinery already exists):**

- [x] **Wire the `cost.update` writer.** Draft a `cost.update` action from each
  `AgentEvent.CostUpdate` the monitor already receives; `Replay.applyCostUpdate`
  (`Replay.scala:333`) + `CostTotals` (`Cost.scala`) already project it. This is
  the single highest-value fix — without it the cost subsystem is unfed.
- [x] **`session.complete` audit event** carrying `{phase, piece, durationMs,
  model, turnCostUsd, success}`, built from the `AgentEvent.Result(success,
  durationMs)` the parser already produces (`AgentEvent.scala:36`). Closes the
  per-phase timing + attribution gap in one event. (Optionally model `num_turns`,
  which is currently not captured at all.)

**Tier 2 — make the data answerable:**

- [x] **`forge stats <feature>`** — fold the log into a per-phase cost /
  wall-clock / turn-count breakdown. Turns the captured data into a direct answer
  to "did this run efficiently".
- [x] **Separate working-time from wait-time** — stamp a marker when the loop
  blocks on a human (`DesignAwaitingMerge`, `*NeedsHumanInput`,
  `PieceAwaitingMerge`) so a 35-min "waiting for the operator to merge" no longer
  reads as Forge being slow.

**Tier 3 — debuggability & the dev loop itself:**

- [x] **Generalise the reviewer raw-dump to driver sessions** — an opt-in
  per-session NDJSON sink (today only reviewers have `FORGE_REVIEWER_RAW_DUMP_DIR`,
  `ClaudeConnector.scala:419`). Makes "what did the implement driver actually do
  for $9.56" answerable after the fact, not only live.
- [x] **Clean resume-from-NHI that preserves history.** The truncate-and-replay
  recovery used ~13× during the MVP run corrupts the timing record (seq 0/1 share
  an identical timestamp because replay rewrites early transitions in a batch) and
  re-pays full driver exploration on each relaunch (gap #10's compounding cost). A
  resume that doesn't rewrite the log fixes both.

**Exit:** a completed run's cost, latency, and turn-count are reconstructable
from the committed log alone, broken down per phase, via `forge stats`.

### 3.2 Slice 2.1 — TUI

Termflow + Elm architecture. Panes per §3.1: status, active (streaming /
log tail / Q&A / idle). Subjective; iterate based on what feels wrong
during real use.

> **✅ closed 2026-06-01.** Per-Task breakdown + audit trail:
> [`design-2.1-tui.md`](design-2.1-tui.md) (so named to avoid the legacy
> `design-2.1.md` = Slice 1.1 collision). Built **log-tail-first** over the
> Slice-2.0 action log (a live `AgentEvent` tap is deferred — see that doc §0/§4).
> Shipped: termflow `0.4.0` wiring + repo-wide Scala 3.7.1 bump, the
> pure `TuiSnapshot` builder, read-only `forge tui <feature>` command, polling
> refresh, scrollable log tail, Q&A display, resize-aware themed panes, `?`
> key-help overlay, and the Task 2.1.8 close-out (§3.3/§3.1 reconciliation →
> [`forge-design-1.6.md`](forge-design-1.6.md), 1.5 → redirect stub). Carried
> forward: Task 2.1.5 live `AgentEvent` tap (unless real use shows per-settle log
> refresh is too coarse) and the §4 T4 live-driver-question display (needs a
> durable "question opened" audit event or the live tap). The termflow watch item
> rolled to §3.4 (`0.4.0` was `publishLocal`-only at close) has since ✅ **resolved
> upstream** — `0.4.0` was published to Maven Central 2026-04-30, so fresh clones
> resolve the build; see §3.4 and design-rationale BT1.

### 3.3 Prompt iteration

The four role prompts (driver-spec, driver-implement, reviewer-design,
reviewer-code) ship with v1 placeholders. After ~5–10 real features:
revise based on observed failure modes, not on lab fixtures. Track
prompt diffs in git; they're load-bearing for behaviour.

> **Observed-failure-mode log (feeds this section):** dogfood run #1 =
> [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md) (`image-creds-dedup`);
> dogfood run #2 = [`dogfood/extract-media-network-config.md`](dogfood/extract-media-network-config.md)
> (2026-06-02) — surfaced the implement-driver-doesn't-format and
> fix-up-hand-formats prompt items (#3/#4 there), plus a fixed `spec→run`
> restart-recovery regression and small resilience gaps.
>
> **Re-scope (2026-06-02):** the formatter findings (#3 implement-driver-doesn't-
> format, #4 fix-up-hand-formats / never sees the failing log) are **no longer
> prompt tweaks** — they become **FailureClassifier deterministic-fix routing**
> in Phase 3 (§4). The prompt log stays the source of truth for genuine *code*
> failure modes; repo-blindness moves to the adaptation layer.

### 3.4 OSS-readiness scaffolding

- ✅ **README that's actually useful to a stranger** (2026-06-05) — freshened to
  the live contract (`forge-design-1.4.md` → `forge-design-1.15.md`, ×4), the
  Scala floor (`3.5.2` → `3.7.1`), the full §15 command set (added `abandon` /
  `profile` / `tui`), the correct `mode` wire value (`"claude-driver"`, not
  `ClaudeDriver`), the `.forge/config.example.json` + `.forge/profile.json`
  pointers, and a new Phase-3 (Repo Adaptation) status bullet.
- ✅ **Config templates committed (`.forge/config.example.json`)** (2026-06-05) —
  every §18 key at its built-in default, mirroring `ForgeConfig.scala`
  field-for-field; a `ForgeConfigLoaderSuite` regression decodes the committed
  example to `ForgeConfig.Default` so it can't drift from the schema (and proves
  it's copy-able to `config.json` unchanged — the `_comment` doc keys are
  unknown-key-tolerated).
- `prices.example.json` kept current with OpenAI model list — already shipped
  (Slice 1.1, `modules/forge-agents/src/main/resources/`); perpetual upkeep item.
- ✅ **Pointer to design doc + rationale from README** (2026-06-05) — the
  Documentation section points at the live `forge-design-1.15.md`,
  `design-rationale.md`, and the `docs/README.md` index.
- LICENSE already in place.
- ✅ **Standalone launcher / packaging** (2026-06-05) — `sbt-assembly` builds a
  self-contained `forge.jar` (every module + the `assets/` and
  `prices.example.json` classpath resources, so `AssetInstaller`'s
  `getResourceAsStream` reads work outside the checkout). `scripts/install-forge.sh`
  builds it, copies it to `~/.forge/lib/forge.jar`, and installs the thin `bin/forge`
  launcher (`java -jar`, no `cd` — Forge resolves the target repo from `os.pwd` /
  `--repo-root`) onto `PATH`. README's "Trying it today" + Run-Forge sections now
  lead with `forge <command>`; `sbt "forge-app/run …"` stays documented as the
  from-source dev path. Verified end-to-end: install to a sandbox `FORGE_HOME`,
  `forge --repo-root <repo> status` runs standalone from a non-Forge directory.
- **Still open: published binary / OS package.** The jar is still built from this
  checkout — there is no released `forge` artifact (Homebrew / GitHub release /
  Coursier) yet. The README "Heads-up" note flags this as the remaining
  distribution-polish gap.
- ✅ **Resolve the termflow dependency for fresh clones** (was carried forward
  from Slice 2.1 / design-rationale **BT1**) — **RESOLVED UPSTREAM 2026-06-01.**
  `forge-tui` is written against termflow `0.4.0`; at Slice 2.1 close that was
  `publishLocal`-only (Central carried only `0.3.0`). termflow `0.4.0` (both the
  `termflow_3` aggregator and `termflow-testkit_3` — the exact artifacts
  `build.sbt` pins) has since been published to Maven Central (released
  2026-04-30; POMs verified HTTP 200), so a fresh clone now resolves the build
  from Central. No Forge-side action remains. (The live spec `forge-design-1.6.md`
  §3.3 still carries the stale "publishLocal-only" note; it corrects in the next
  `forge-design-1.7.md` revision, not in place.)

### 3.5 Deferred to a later Phase-2 slice (from Slice 2.0 close-out)

Slice 2.0 ("instrument before optimise") deliberately built the *measurement*
and left the *tuning* and a couple of larger durability fixes for a later
Phase-2 slice now that there is per-run cost/latency data to act against. These
are the forward-looking homes for the design-2.0 §4 carry-forwards that
out-scoped the observability slice (full dispositions in
[`design-2.0.md`](design-2.0.md) §4 and [`design-rationale.md`](design-rationale.md)):

- [x] **Reviewer + driver model / wall-clock-cap / retry §18 tuning**
  (design-rationale **S4-5**, and **S4-3** reviewer-cost widening). **The S4-5
  model + cap knobs landed** (Phase-3 section-review prep, 2026-06-05): the §18
  `reviewer` block (`reviewer.claudeModel` / `reviewer.codexModel` /
  `reviewer.wallClockCapSec`) replaces the `ConnectorFactory` hard-wiring, read by
  `ConnectorFactory` + `Orchestrator.reviewerWallClock` + `ProfileCommand` (one
  source of truth), contract [`forge-design-1.14.md`](forge-design-1.14.md) §18.
  **Slice 2.2 landed the rest (2026-06-05, contract
  [`forge-design-1.16.md`](forge-design-1.16.md); plan
  [`design-2.2-tuning.md`](design-2.2-tuning.md)):** (a) **S4-3 reviewer-cost
  widening** — the `Connector` reviewer/sensor methods return `Reviewed[A]` =
  verdict + `Option[Cost]`, surfaced on `ReviewerOutcome.Settled` and written as a
  `cost.update` (`actor="reviewer"`) that folds into `Feature.cost` (feature+piece,
  never turn), so the §12 caps are no longer driver-session-only (`classifyFailure`
  write deferred — rarest path; `profileRepo` exempt — no feature); (b) the cap
  re-tuning + the **per-turn-cap-is-post-hoc** finding — `maxTurnCostUsd` is now a
  non-killing advisory (the $2 cap could only kill *after* the $9.56 turn already
  completed), retuned 2 → 15; the wall-clock settle cap is the only mid-turn
  interrupt. Reviewer process-retry *counts* need no change. **✅ Whole-section
  code review landed clean 2026-06-05** — one documentation-only coherence finding
  fixed (the `MonitorOutcome.TurnBudgetBreached` / `SessionMonitor` docstrings,
  stale after the per-turn kill path was removed, now mark the variant dormant); no
  functional findings. `[~]` → `[x]`.
- [x] **Driver-respawn-avoidance on resume-from-NHI** (design-2.0 §4 **D3**
  large half). ✅ **CLOSED 2026-06-01** — all five chunks (D3-0…D3-4) landed and
  the spec deltas are reconciled into the live contract (then v1.5, now
  [`forge-design-1.6.md`](forge-design-1.6.md))
  (§7.1 `resumeHeadlessDriver`, §11.4 restart-recovery resume-instead-of-respawn
  gated by a worktree-safety classifier (default-on once safe), §19 `<actor>.resume`
  for piece drivers + `session.complete.resumed`). A restart from a mid-exploration
  implement/fix-up crash now **resumes the existing driver session** instead of
  re-paying the full exploration (gap #10's ~$10 / 2.18M-token turn), and
  `forge stats` folds the resumed turn as a measured saving. **Per-chunk plan +
  audit trail: [`design-3.5.md`](design-3.5.md)** (D3-0 spike → D3-1 connector
  resume seam → D3-2 worktree-safety classifier → D3-3 orchestrator
  resume-instead-of-respawn → D3-4 stats/close-out).
  Slice 2.0 made resume append-only and self-describing
  (`audit.resume_from_nhi`) but a resume still **re-spawns the implement/fix-up
  driver from scratch**, re-paying the full exploration (~$10 in the szork run).
  A resume that detects already-committed driver work on the piece branch and
  skips the respawn closes gap #10's compounding cost. Deferred because it
  touches git branch inspection + driver `--resume` semantics and must revisit
  `RestartRecovery`'s deliberate "no transparent resume" stance. **Watch item:**
  until it lands, each resume from an implement/fix-up NHI re-pays the driver's
  full exploration; the per-turn cost cap bounds the blast radius.
  **Included the `monitor.outcome` writer** (surfaced by the §3.5 piece-spawn
  durability review, 2026-05-31): `RebuildState.MonitorOutcomeKind` /
  `inFlightSessions`'s `settledAfter` branch was dormant infrastructure — *no code
  wrote `monitor.outcome`*, so with piece spawns now logged, a cold rebuild in the
  narrow post-settle window (driver settled clean, crashed before the next
  transition persists) routed to NHI like any other in-flight spawn — the correct
  conservative behaviour *until* a recovery existed (a post-settle restart cannot
  synthesise the not-yet-persisted `PrOpened` from state alone; the only other
  "recovery" would be a silent driver re-spawn — exactly this item's cost). **Unit
  B (below) wired the writer + recovery**, so the post-settle window now advances
  via the idempotent side effect instead of routing to NHI.
  - **Unit A landed 2026-06-01 (idempotency only; box stays open).** Scoping this
    item surfaced that **the driver never commits** — implement/fix-up prompts say
    "Do not commit — Forge will commit after you settle" — so "already-committed
    work on the branch" exists *only* in the post-settle crash window (the
    orchestrator opens the piece PR, then crashes before the `PrOpened` transition
    persists). On the operator's resume that re-ran `classifyCommitOpenPr` →
    `gh pr create` → "a pull request already exists for branch …"
    (`GhError.Transient`) → NHI, stranding the run. Unit A makes
    `classifyCommitOpenPr` idempotent: a new `GhClient.prForBranch`
    (`gh pr list --head <b> --state open --json number`, pinned `parsePrList` +
    `RealGhClientPrListSuite`) looks up the open PR for the piece branch and reuses
    it instead of re-creating. (`git commit` already models a clean tree as
    `CommitResult.NothingToCommit`, and `git push` is naturally idempotent, so this
    closed the last gap; `classifyCommitPush` needed no change.) `RealGhClient` +
    both `FakeGhClient`s updated; `RealSideEffectsSuite` + `FakeGhClientSuite`
    extended.
  - **Unit B landed 2026-06-01 (post-settle window now recovers; box stays open
    for the D3 large half).** The three pieces landed atomically: (1) the
    `monitor.outcome` **writer** — `Orchestrator.handleWinner` appends the
    piece-keyed marker *before* running the recoverable piece-driver post-settle
    effect (`ClassifyCommitOpenPr` / `ClassifyCommitPush`), and *only* for those
    (the design-phase settles keep the conservative in-flight → NHI behaviour, so
    no marker exists for them — a marker without recovery would silently re-spawn
    the design driver, strictly worse than NHI); (2) the **`settledButUnadvanced`
    projection** on `RebuildState` (complement of `inFlightSessions` over the same
    tail spawn — marker present + state still the driver's → settled-but-unadvanced;
    widened `RebuildResult` with the field; both projections now share one
    `lastDriverSpawn` decision so they can't disagree); (3) the **effectful
    post-settle recovery step** `Orchestrator.postSettleRecover`, run in
    `Orchestrator.run` after restart recovery, which re-runs the *idempotent*
    (Unit A) side effect and applies the synthesized event — advancing the FSM past
    the settle **without re-spawning the driver**. Coverage:
    `RebuildStateInFlightSuite` +7 (the projection + mutual-exclusivity + an
    FSM-emitted producer→consumer link), `OrchestratorPostSettleRecoverySuite` (the
    writer scoping + a crash-after-marker e2e that reaches `FeatureDone` with the
    implement driver launched exactly once — the empty pass-2 monitor would raise on
    any stray re-spawn), `ReadOnlyHandlerSuite` +1 (the rebuild-state report line).
    `forge-core` 402, `forge-app` 364 (full unit suite green); `forge-it` compiles.
    **The D3 large half (the rest of this bullet) ✅ closed 2026-06-01** —
    driver-CLI `--resume` for the *mid-exploration* (uncommitted) cost now
    resumes instead of re-spawning (D3-0…D3-4, design-3.5.md; the watch item
    above no longer stands for a worktree-safe restart). Unit B recovered the
    *post-settle* window (driver finished exploring, crashed before the
    transition persisted); D3 recovers the *mid-exploration* window (driver
    killed while still exploring), gated by the worktree-safety classifier.
- [x] **`designSessionId` durability** (Task 1.4.16 **gap #7**). ✅ **Fixed
  2026-05-31** during the Slice 2.0 live re-validation, which reproduced it: a
  `forge run` started after `forge spec` dead-ended at
  `NeedsHumanIntervention("missing design session id")` before implementation,
  because `forge spec /done` wrote the design session id only to the state cache,
  never to the action log, so a cold `RebuildState.run` rebuilt it as `None`. The
  fix wires the §19 `<actor>.spawn` entry at the spec `SessionSpawned` seam
  (`Fsm.scala`) so the id projects from the log; covered by `Fsm_11_1_SpecPhaseSuite`
  + a `FeatureFoldEventsSuite` producer→consumer rebuild regression.
- [x] **Session-id log durability for piece spawns + resumes** (broader finding
  from the gap #7 fix). ✅ **Done 2026-05-31.** The §19 `<actor>.spawn` /
  `<actor>.resume` kinds were *consumed* by `Replay`/`RebuildState` but
  **never produced anywhere** — the gap #7 fix wired only the design spawn. Piece
  spawns (`PieceImplementing` / `PieceFixingUp` `SessionSpawned`) and every resume
  emitted no durability entry, so `currentPieceSessionId` did not survive a cold
  rebuild. (It bit less than gap #7 — pieces re-spawn a fresh driver each turn, and
  the happy-path end-state clears all session ids — which is also why the F1/F5/F6
  property suites never caught it: they assert on `Fsm.transition` reconstruction or
  the cleared final state, not mid-trajectory log projection.) **Fix:** wired
  `sessionSpawnDraft(piece = Some(p))` at all four piece-spawn seams
  (`PieceImplementing` + the defensive `PieceFixingUp` late spawn, and the
  `PieceCiFailed` / `PieceReviewFailed → PieceFixingUp` spawns) and a new
  `sessionResumeDraft` at the two design-resume seams (`DesignReviewing` /
  `DesignPrFeedback` `SessionResumed`), guarded on a non-empty `oldSessionId` so an
  orchestrator `oldSessionId = ""` (which `RealSideEffects.resumeDesign` refuses in
  practice) can never poison a cold rebuild via `ResumeWithoutSpawn`. Regression
  coverage folds the **committed log** (not re-applied FSM): a new
  `F5CurrentPieceSessionIdLifecycleSuite` property folds log prefixes and asserts
  `currentPieceSessionId` at each piece spawn; `FeatureFoldEventsSuite` +3 and
  `RebuildStateInFlightSuite` +1 are producer→consumer rebuild regressions; the six
  affected `Fsm_*`/`FsmReviewFixesSuite` draft assertions updated. **Beneficial side
  effect:** this activates an intended-but-dormant restart path — a process crash
  mid-implement/fix-up now routes to `NeedsHumanIntervention` with a recovery hint
  (via `RebuildState.inFlightSessions` → `RestartRecovery`, whose `(Implement,
  PieceImplementing)` / `(Fixup, PieceFixingUp)` reason rows + `OrchestratorRestartSuite`
  were already built and tested for exactly this) instead of silently re-spawning
  the driver, matching the design-phase behaviour and the "no transparent resume"
  guarantee. `forge-core` 398, full unit suite (1290) green; `forge-it` compiles.
  Spec text unchanged (the §19 kinds were already documented as producible). The
  remaining respawn-*avoidance* (skip re-exploration on resume) is the separate D3
  large half, still deferred above.
- [x] **`NeedsHumanIntervention(ReopenDesign)` recovery hint is unactionable**
  (found during the Slice 2.0 live re-validation). ✅ **Fixed 2026-06-01** —
  chose the spec-sanctioned option: make `forge spec` accept the `ReopenDesign`
  re-entry (§15 lists `forge spec` "on the design branch", so the spec already
  intends it). The *other* option (point the hint at a different command) was
  rejected on investigation: `Resume(ReopenDesign)` lands in `DesignReviewing(1)`
  whose orchestrator entry hook re-reviews the **same, unchanged** design PR
  headlessly, so a `forge run` recovery would loop straight back to NHI for the
  cases that produce `ReopenDesign` (PR closed-without-merge, "design did not
  converge", changes-requested) — the human must re-engage interactively.
  **What landed:** `SpecRepl.classifyStart` now returns a `Reopen` decision for
  `NeedsHumanIntervention(_, ReopenDesign(pr))`; `forge spec` spawns a fresh
  interactive spec session (seeded with the existing design + the NHI reason) and
  on `/done` `finalizeReopen` folds `Resume(ReopenDesign(pr))` (`NHI →
  DesignReviewing(1)`, emitting the append-only `audit.resume_from_nhi` marker +
  transition) **then** `SessionSpawned("driver","spec",…)` to project the fresh
  `designSessionId` durably (a new `DesignReviewing + SessionSpawned(piece=None)`
  FSM case emits the §19 `<actor>.spawn` entry — the same projection that closed
  gap #7, so a cold rebuild after the re-entry no longer dead-ends at "missing
  design session id"; this also repopulates the id for the `ReopenDesign(None)`
  missing-session-id trigger). The log is append-only (no truncation), so `forge
  stats` reads a coherent timeline across the re-open, and the manifest is not
  written back (driver-owned, as in `finalizeDone`). The `TerminalReport`
  ReopenDesign hint ("re-open … with `forge spec`") is now accurate.
  `Fsm_11_2_DesignReviewSuite` +1, `SpecReplSuite` +3 (`classifyStart` Reopen,
  non-ReopenDesign NHI still refuses, `finalizeReopen` re-entry + append-only).
  `forge-core` 399, `forge-app` 360 (full unit suite green).
- [x] **Model `FsmEvent.SessionResumed.oldSessionId` as `Option[String]`**
  (§3.5 piece-spawn durability review #2, 2026-05-31). ✅ **Done 2026-06-01.**
  It was a `String`, so `Orchestrator.resumed` passed a `""` sentinel for a missing
  id (`oldSessionId.getOrElse("")`), and `Fsm.applyDesignResume` special-cased that
  sentinel (`if oldSid.isEmpty`) to avoid a poison `<actor>.resume` that would fail
  `Replay.ResumeWithoutSpawn` on a cold rebuild. **Fix:** the event field is now
  `Option[String]` so `None` is the missing case structurally — `Orchestrator.resumed`
  passes the `Option` straight through (the `getOrElse("")` is gone) and
  `applyDesignResume` matches `None`/`Some` (the `isEmpty` sentinel check is gone).
  Behaviour is identical (`None` → project in memory, no durability draft; `Some(id)`
  → emit `<actor>.resume`); the empty-string sentinel is no longer representable.
  Touched `FsmEvent`, `Fsm.applyDesignResume`, `Orchestrator.resumed`, and six
  construction sites in tests (the empty-oldSessionId guard test now passes `None`;
  `FsmEventSuite` gained a `None` round-trip). Full unit suite (1290+) green.
- [x] **Centralize FSM test action-builders + base timestamp in `FsmFixtures`**
  (§3.5 review #6, 2026-05-31). ✅ **Done 2026-06-01.** The `<actor>.spawn` /
  `<actor>.resume` `Action` builders were re-declared per suite
  (`spawnAction`/`resumeAction` in `FeatureFoldEventsSuite` vs `spawn`/`resume` in
  `RebuildStateInFlightSuite`), as was the base test `Instant` (`ts0` / `Epoch`),
  so a §19 spawn/resume payload-schema change had to be updated in each copy. **Fix:**
  pulled a single `Epoch` + `at(n)` and the `spawnAction(seq, actor, sessionId, piece)`
  / `resumeAction(seq, actor, oldSid, newSid, piece)` builders into `FsmFixtures`
  (alongside `MergedAt` / `ObservedAt`); both suites now import them. The builders
  are payload-identical to the two prior copies, so the change is behaviour-preserving
  (both suites assert on projected state / session ids / merges, not wall-clock, so
  sharing one base instant is safe); the differently-named `FsmResumeMarkerSuite.resume`
  transition-helper and the local `RebuildStateSuite`/`RebuildStateInFlightSuite`
  `action` helpers are untouched (no collision — a class-level member shadows a
  wildcard import cleanly). `forge-core` 399 (full unit suite green); scalafmt clean.
  Test-only cleanup.

---

## 4. Phase 3 — v1.0: Repo Adaptation ⭐

**The pivot.** Phase 3's original exit criterion — "you'd recommend it to a
friend, on *their* single repo, with a straight face" — is exactly what _repo
adaptation_ delivers. A friend's repo is not Scala/sbt, and Forge today runs
blind to it: hardcoded reviewer models, ambient git identity, and no knowledge
of the repo's format/lint/build/test commands. Both dogfood runs proved the
cost; dogfood #2 spent **$0.73 on real implement work, then $1.78 / 12 min /
2 fix-up rounds** fixing a formatting issue the driver could have avoided by
running `sbt scalafmtAll` — pure repo-blindness waste. Architecture write-up:
design-rationale **A5**.

**Deterministic spine + agentic senses.** Keep the spine (`Fsm.transition`,
action log, replay, restart recovery, budget caps, push/PR/merge) deterministic
— that determinism is *why* the dogfood-#1 projection bug was fixable in one
session. Add agentic **sensors** at well-defined seams that *perceive and
propose*; only the core *decides, records, and touches irreversible things*:

- **RepoProfiler** (first encounter / on CLAUDE.md or CI change) — reads
  CLAUDE.md + AGENTS.md + `.github/workflows` + build files + README → a
  structured **`RepoProfile`**: build tool; format/lint/build/test/typecheck
  commands *tiered by determinism* and tagged required/optional; commit
  identity; merge strategy; workflow shape. Committed at `.forge/profile.json`
  (reviewable in PRs, shared across machines/workstreams) and **hashed into the
  action log per run** so a replay uses the profile-as-of-that-run —
  `Fsm.transition` stays pure-given-inputs. Learning mutates the profile only
  *between* runs, never inside a transition; that is what preserves replay,
  audit, and reconstructable cost.
- **FailureClassifier** (on any CI/build/format gate failure) — failure log →
  `{kind, confidence, suggestedAction}`; the core routes deterministically:
  `deterministic-fix` → Forge runs the repo's own tool (no driver turn — the
  formatter case becomes a ~2s local step, not a paid fix-up round); `code-fix`
  → driver fix-up **with the real failing log piped in** (dogfood #4 carried
  only the `gh pr checks` summary, never the `scalafmt: 1 file must be
  formatted` log); `flaky` → retry; `env`/`rate-limit` → back off and keep
  polling (dogfood #5's false-NHI).
- **ConventionLearner** (post-run / periodic) — mines failure→remedy patterns +
  recurring reviewer comments → profile deltas **and a proposed PR to the repo's
  own CLAUDE.md** ("implement driver must run `sbt scalafmtAll` before
  settling"). Human-approved; no autonomous doc mutation.

**Workflow-shape adaptation stays deterministic.** Repo variety
(review-required? CI required? merge strategy? trunk vs PR?) is handled by a
**`WorkflowProfile`** that *parameterizes* the still-deterministic §11 FSM — not
by an LLM composing the workflow shape. Resist making the spine itself agentic
until command-level adaptation is proven insufficient (design-rationale A5, "the
honest hard edge").

**Sub-slices (runnable-first, per the AGENTS.md "thin runnable slice" rule):**

- **3.0 — `RepoProfile` model + `ProfileStore` + hash-into-log.** New
  `profile.snapshot` action (§19). Spike: profile szork *and* forge itself,
  commit the captured profiles as fixtures. Proves the determinism story before
  any LLM is in the loop. ✅ **closed 2026-06-05** (with 3.1) —
  [`design-3.0.md`](design-3.0.md).
- **3.1 — `FailureClassifier` + deterministic-fix routing.** Highest-ROI first
  runnable: re-run the dogfood-#2 formatter case and watch the $1.78 / 12-min
  fix-up collapse to a local `scalafmtAll`. Pipe `gh run view --log-failed` into
  the fix-up context. ✅ **closed 2026-06-05** — the §8.2 collapse fired **live and
  measured** on a real `szork` run (dogfood #4, `adventure-gen-retry-config`): scalafmt
  CI failure → `RunLocalCommand(sbt scalafmtAll)` → CI green, `attempts` unchanged,
  `forge stats` folding "1 fix-up round avoided" at $0 driver cost. Plan + close-out:
  [`design-3.0.md`](design-3.0.md); evidence:
  [`dogfood/adventure-gen-retry-config.md`](dogfood/adventure-gen-retry-config.md).
- **3.2 — `RepoProfiler` LLM sensor** replacing hardcoded reviewer config +
  commit-identity sourced from the profile (sense the environment, don't inherit
  ambient defaults — the tiniest instance of the whole thesis). ✅ **closed 2026-06-05**
  — landed as [`design-3.0.md`](design-3.0.md) Task 3.0.3 (`Connector.profileRepo` +
  `forge profile` writing `.forge/profile.json`); closed in that slice's whole-section review.
- **3.3 — `WorkflowProfile` FSM parameterization** (review / CI / merge-strategy
  / branch model). ✅ **closed 2026-06-04** — the two knobs whose §11 wiring exists
  today landed: **review-required** (Tier 1 — a `reviewRequired=false` repo skips the
  reviewer one-shot via the neutral `FsmEvent.ReviewSkipped`) and **CI required-check
  sensing** (Tier 2 Half A — `workflow.ciRequiredChecks` feeds the §8 required set).
  The FSM stayed deterministic and profile-agnostic throughout (the §6.1 replay
  invariant; `ProfileReplayInvarianceSuite` R1/R2). Plan + carry-forward:
  [`design-3.3.md`](design-3.3.md). **Deferred to their own future slices** (each is a
  contract/lifecycle change beyond a parameterization, not 3.3 scope):
  - **no-CI-repo short-circuit** (a genuinely no-CI repo advances straight to merge) —
    needs an explicit `WorkflowProfile.ciRequired` signal + a real no-CI repo to design
    against (design-3.3 **W5**).
  - **merge-strategy** (`squash`/`merge`/`rebase`) — moot until an auto-merge side effect
    exists; Forge detects merges, it does not perform them (design-3.3 **W2**; field
    sensed-but-unused).
  - **branch-model trunk path** (`TrunkBased`: commit-to-trunk, no PR tail) — a new §11
    lifecycle branch, not a parameterization (design-3.3 **W3**). ✅ **closed 2026-06-04**
    in its own sub-slice: a `TrunkBased` piece commits straight to mainline and advances to
    `Refining` with **no** `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge`
    tail — gated only by the pre-integration local **Build** gate (no broken compile to
    trunk). The FSM stayed profile-agnostic, routing on the neutral
    `FsmEvent.CommittedToTrunk` (the §6.1 replay invariant; `ProfileReplayInvarianceSuite`
    R1/R2); the orchestrator alone decides `TrunkBased` from the resolved `WorkflowProfile`.
    Contract: [`forge-design-1.11.md`](forge-design-1.11.md) (§6 / §8.3 / §11.4 / §11.5 / §19);
    plan + decisions: [`design-3.3-trunk.md`](design-3.3-trunk.md). **Carried forward (D5):**
    a **live `TrunkBased`-repo demonstration** — both committed profile fixtures (`szork`,
    `forge`) are PR/GitFlow repos, so the slice is proven against scripted fakes
    (`OrchestratorTrunkPathSuite`), with the live win deferred until a real trunk repo exists
    (the W5 / build-gate precedent).
- **3.4 — `ConventionLearner`** → proposed CLAUDE.md PRs (human-approved). ✅ **closed
  2026-06-05** — landed as [`design-3.0.md`](design-3.0.md) Task 3.2: the §11.7 learner at
  `FeatureDone` mines failure→remedy patterns **and** recurring reviewer comments (D8) into
  profile deltas + a proposed CLAUDE.md edit **opened as a PR** for human approval (D9, never
  auto-merged); closed in that slice's whole-section review.
- **3.5 — Role-trait refactor** (§4.2 below) — the sensors are the first
  concrete third+ roles, so the refactor lands *inside* the phase that needs it.
  ✅ **closed 2026-06-04** — a base `Agent` trait + `Driver` / `Reviewer` /
  `Sensor` roles replace `Mode`-dispatch; `Mode` resolves **once** to a
  `RolePairing` configuration (`RolePairing.of`), and cross-model review is
  reconciled to **same-CLI** (D1 — one CLI drives + reviews on a cheaper model,
  as C15 validated). The §6.1 replay invariant held throughout
  (`ProfileReplayInvarianceSuite` R1/R2; the `fsm` package names no role type,
  the wire form is byte-identical). Contract:
  [`forge-design-1.10.md`](forge-design-1.10.md) §7; plan + carry-forward (C1 —
  Connector-trait split → Phase 4/5): [`design-3.5-role-trait.md`](design-3.5-role-trait.md).

**Exit criterion:** Forge drives a feature end-to-end on a **new, unseen repo
with a different stack** (e.g. a Node or Python repo), having auto-profiled it,
with **zero hardcoded-config edits**, and the formatter handled as a local
deterministic step rather than a paid fix-up round.

> **✅ MET live 2026-06-05; whole-section review landed — Phase 3 CLOSED.**
> The `queryclient-config` run drove the Node/TS fork `rorygraves/toast-stats` to
> `FeatureDone`: auto-profiled, zero config edits, the §8.2 prettier-collapse on a
> CI failure (rules, conf 0.97, `attempts` 0, no LLM), both PRs merged. Plan +
> evidence: [`design-phase3-exit.md`](design-phase3-exit.md) (✅ closed) +
> [`dogfood/phase3-exit-queryclient-config.md`](dogfood/phase3-exit-queryclient-config.md).
> **Follow-up — ✅ FIXED (2026-06-05):** **F3** — `CiReadiness` declared a
> required check gated behind a slow upstream job ("Build Applications") as "never
> appeared" once `checkDiscoveryTimeoutSec` elapsed, forcing a green run into NHI
> (worked around at run-time by a fresh resume). Fix landed: §8 rule 2 now keeps
> polling while any *observed* check is still pending (non-`Completed`), declaring
> a required check missing only once CI is otherwise settled. `CiReadiness.scala`
> (`isPending` helper) + `CiReadinessSuite` (+3). See design-phase3-exit §9.
>
> **Two residual "inherit a default" items closed before the section review — ✅ FIXED
> (2026-06-05):** these were the only places Phase 3 still inherited a default instead
> of sensing/configuring it (the §4 intro's "hardcoded reviewer models, ambient git
> identity"). **S4-5** — the reviewer model + 3-min cap, hard-wired in `ConnectorFactory`,
> are now the §18 `reviewer` config block (read by `ConnectorFactory` +
> `Orchestrator.reviewerWallClock` + `ProfileCommand` — one source of truth; defaults =
> the C15 v1 values). **D4** — `RepoProfile.commitIdentity` (sensed but never consumed)
> is now wired into the §11.4/§11.6 commit step, so a profiled run authors commits as
> `forge[bot]` instead of the operator's ambient git identity. Contract:
> [`forge-design-1.14.md`](forge-design-1.14.md) (§18 + §6.5/§11.4); see
> design-phase3-exit §9.
>
> **Whole-section-review findings — ✅ FIXED (2026-06-05):** a section review surfaced
> three more issues, all fixed in [`forge-design-1.15.md`](forge-design-1.15.md). **P0
> (safety)** — the profiler emitted `branchModel: trunk_based` as the *default* for
> ordinary PR repos, but the orchestrator reads `TrunkBased` as direct-commit-no-PR, so a
> normal repo could be direct-pushed past its PR/CI/review gates. Fix: a distinct
> `BranchModel.pr_based` (the safe default), `trunk_based` reserved for genuinely no-PR
> repos, `schemaVersion` 1→2, and the §11.4 direct-push gated on a current-schema profile
> (a stale v1 `trunk_based` degrades to PRs). **P1** — the §8.2 CI autofix staged every
> dirty path via raw `git status`, bypassing the `ChangeCollector` deny/ask policy; fix:
> require a clean worktree + route through the shared staging classification. **P2** — the
> profiler didn't read `README.md` (§3.3 / §4 say it should); fix: README is now a
> first-class `RepoProfiler` input.

### 4.1 v2 candidates from design §20, picked by lived experience

Pick the ones the bug log and prompt log actually justify; don't carry
the whole list. My prior on most likely picks:

- **Process-tuning loop** — replay action logs to suggest prompt/FSM
  tweaks. Highest leverage if Phase 2 prompt iteration becomes a chore.
- **Stacked PRs onto a per-feature integration branch with composite CI**
  — pick this if rerunning full CI per piece becomes the dominant cost.
- **GitLab adapter** — pick this when you actually need it (likely yes,
  given the work projects). The `PrSnapshot` ADT already keeps the seam
  clean.

Probably skip in Phase 3 (re-evaluate in Phase 4):

- Auto-merge — value only if Phase 2 surfaced "clicking Merge on clearly
  fine PRs" as friction.
- Parallel features — wait for the Phase 4 pivot; bolting it onto v1's
  single-feature model is wasted work.

### 4.2 Role-trait refactor (architectural seam for Phase 4–5)

This is the one architectural change Phase 3 should fund even if no
feature visibly requires it — and the adaptation sensors above
(RepoProfiler / FailureClassifier / ConventionLearner) now make it
*demand-driven* rather than speculative: they are the first concrete
third+ roles, so the refactor lands as sub-slice 3.5. Phase 1's
role-trait *stub* (§2.6) means
this is a refactor of `Mode`'s implementation and the connector
factories, not a sweep through every caller — call sites already speak
to `Role`, not `Mode`.

- Generalise `Mode` (sealed 2-case ADT) into role traits — `Driver`,
  `Reviewer`, and a base `Agent` for future roles (knowledge-base
  consultant, PR-watcher, etc.).
- The two concrete modes (`ClaudeDriver`, `CodexDriver`) become
  configurations of those traits, not enum cases.
- Phase 4's daemon + Phase 5's reactive review and knowledge-base agent
  all depend on this. Doing it in Phase 3 (when there are still only two
  concrete pairings) is much cheaper than doing it during the Phase 4
  pivot.
- **✅ Landed as sub-slice 3.5 (closed 2026-06-04).** The `Agent` base
  trait + `Driver` / `Reviewer` / `Sensor` roles shipped; `Mode` is now a
  wire token resolved **once** to a `RolePairing` configuration
  (`RolePairing.of`), with cross-model review reconciled to **same-CLI**
  (decision D1 — the same CLI drives and reviews on a cheaper model, as
  C15 validated). Contract: [`forge-design-1.10.md`](forge-design-1.10.md)
  §7; audit trail [`design-3.5-role-trait.md`](design-3.5-role-trait.md).
- **Carry-forward to Phase 4/5 (C1):** splitting the `Connector` god-trait
  into per-role capability traits (`DriverConnector` / `ReviewerConnector`
  / `SensorConnector`) was deliberately *excluded* from sub-slice 3.5 (it
  sweeps every caller, which this refactor scoped out). It becomes
  worthwhile when the daemon (Phase 4) / reactive review (Phase 5) add the
  4th/5th concrete role and a per-role capability surface stops being
  speculative — pick it up there. See design-3.5-role-trait §5 C1.

### 4.3 Hardening

- Documented escape hatch for every `NeedsHumanIntervention` reason.
- Bug log triaged; recurring failure modes covered by tests.
- Prompt-injection / sandbox-escape audit of both driver paths.

---

## 5. Phase 4 — v2.0: Workspace & Workstream platform

> **Terminology (2026-06-02):** what earlier drafts call a Forge *instance*
> is the **workspace** — one project that may span multiple repos. The
> destination this phase builds toward is the one stated up front: **one
> workspace per project, N concurrent workstreams, a multi-workstream cockpit
> TUI that flags and accepts human input at the right stage of each stream**
> (NHI, a driver question, a merge gate, a profile-change or convention-PR
> approval). Phase 3's adaptation layer is the prerequisite — a cockpit driving
> several repos at once is only safe once each repo is profiled rather than run
> blind.

**This is the big architectural change**, and it should land as a single
phase because the four sub-pieces unlock each other:

- You can't do parallel workstreams cleanly without per-checkout
  isolation → containers.
- Containers are wasted overhead unless something supervises and
  observes them → daemon.
- A daemon owning multiple containers needs to know what set of repos
  and workstreams it speaks for → instance scope.
- An instance scope only earns its weight if more than one workstream
  runs at a time → parallel.

**Treat this phase as needing its own design doc** — `forge-design-2.0.md`
— before any code lands. The v1 spec (1.1 / 1.2) explicitly rejects
pieces of this (`Multi-repo / monorepo split work`, `Long-running
daemon`, `Parallel features`, `Worktrees devcontainer-incompatible`)
and those rejections are correct *for v1*; v2 revisits them with a
different set of constraints.

**Exit criterion:** one Forge instance manages the llm4s family (≥2
repos), running ≥2 workstreams concurrently in containers, with the TUI
attaching to / detaching from the daemon cleanly.

### 5.1 Forge instance

- New top-level concept: an instance owns N repos, M workstreams, its
  own config, prompts, and (Phase 5) knowledge base.
- Likely layout: `~/.forge/instances/<instance-name>/` with `repos/`,
  `workstreams/`, `config.json`, `prompts/`, `state/`, `log/`.
- Per-repo `.forge/` (committed: specs, manifests, audit) remains
  inside each repo — that's the right home for review history.
  Per-instance `.forge/state/` and `.forge/log/` move out of the repo
  into the instance directory.
- `forge init-instance <name>` / `forge add-repo <path>` / `forge
  list-repos`.

### 5.2 Workstream

A workstream is *the unit a developer thinks about*; a feature is the
unit Forge implements. A workstream can span:

- One feature in one repo (the common case, isomorphic to today's
  Feature).
- Multiple features in one repo (parallel work on different pieces of
  one project).
- Coordinated features across multiple repos (e.g. an llm4s-core
  change needs a matching termflow update).

The workstream object tracks goal, current state, active feature(s),
next feature(s), and (Phase 5) a backing issue.

### 5.3 Daemon mode

- Long-running supervisor; TUI is one client. CLI commands remain the
  primary scripting interface; both talk to the same daemon via Unix
  socket.
- Polling cadence per-feature (the existing 30s rule) becomes
  per-workstream; the daemon can multiplex.
- Daemon owns the instance lock; per-workstream locks live below it.

### 5.4 Containerised execution

The user's stated goals — parallelism, observability, reproducibility,
isolation, broad-permission agent runs without cross-contamination — all
point at "every workstream runs in its own container with a clean
checkout, pinned tool versions, and host-isolated permissions."

- Each workstream is one container (not one-per-piece) — the workstream
  is the unit that has a coherent checkout.
- Container has: claude, codex, gh, scala/sbt/node/etc. pinned per
  repo's `Forgefile` or equivalent.
- Logs, processes, ports are inspectable from the daemon's status API
  and surfaced in the TUI — this is the CMUX-style visibility layer.
- Worktrees stay rejected (v1 spec §1); containers are *not* worktrees,
  they're isolated checkouts of full clones. Different mechanism, same
  goal of "concurrent work without colliding."
- CMUX integration, if it happens, is a viewer over the container
  status feed, not a replacement for the daemon.

### 5.5 Parallel features

- Drop the v1 non-goal.
- Concurrency unit is the workstream; budgets become per-instance and
  per-workstream.
- Cost-cap enforcement still happens per session (unchanged) but
  aggregate budgets need a fan-in.

---

## 6. Phase 5 — v3.0+: agentic-dev cockpit

These are directionally clear but conceptually fluid. Each will earn its
own mini-design when its phase starts. Ordered by leverage:

### 6.1 Reactive PR review (≈ first Phase 5 capability)

Watch GitHub/GitLab for incoming PRs Forge didn't create; review against
project guidelines + project state; post inline comments.

- Reuses the reviewer connector and the §10.2 line-based comment posting
  path. Most of the code already exists.
- Triggering: poll the org/repo PR list on a slow cadence (5–15 min);
  webhooks remain rejected (§22) — polling stays the model.
- This is the first capability that *exercises* the Phase 3 role-trait
  refactor; without it, a reactive reviewer is an awkward special case.
- Optional second-order: configurable "custom action triggers" (e.g.,
  on PR with label `hal`, post a `/hal` comment to fire an internal
  workflow). Cheap once the watcher exists.

### 6.2 Workstream ↔ issue tracker

- Source of "goal, progress, active/next" for each workstream.
- Adapters for Jira / Linear / GH Issues. Read-mostly initially; write
  on milestone events (PR opened, merged).
- This is what makes the TUI workstream pane actually useful instead of
  a glorified `git log`.

### 6.3 Queriable knowledge base

- RAG over: in-repo design docs, audit logs, issue-tracker history,
  optional external sources (Slack channels, Confluence).
- Two interfaces:
  - TUI query pane for the developer.
  - **MCP server** for the driver/reviewer to consult during normal
    work. The MCP path is probably the higher-leverage one — it lets
    the agent answer "what was decided about X" without a human in
    the loop.
- Cleanest to scope per-instance, not per-repo.

### 6.4 Container support/debug tooling

- Log tails, process lists, port maps, restart, attach — all per
  workstream container, surfaced through the daemon's status API and
  the TUI.
- CMUX integration likely fits here as the visualisation layer.
- Lowest priority of the Phase 5 set: useful, but only after 6.1–6.3
  prove the cockpit framing is actually right.

---

## 7. Divergences from the v1 spec

Tracked here so they don't surprise anyone mid-implementation. None
require changes to the v1 contract (now `forge-design-1.6.md`); all are deliberately
deferred to Phase 3+.

| Long-term direction | v1 spec stance | Phase that resolves it |
|---|---|---|
| Forge instance per project group | §1 non-goal: "Multi-repo / monorepo split work" | 4 — promote instance to first-class concept |
| Containerised execution | §1 rejection refers to *worktrees + devcontainers as working tree*; containers as *runtime* are a different design point | 4 — re-decide explicitly in `forge-design-2.0.md` |
| Long-running daemon | §1 non-goal: "Forge runs on the user's laptop, lifetime = TUI session" | 4 — daemon mode lands with instance pivot |
| Parallel workstreams | §1 non-goal: "Parallel features" | 4 — workstream replaces feature as concurrency unit |
| Role-pluggability | §20 v2 candidate: "Third-party agents / arbitrary role pairings" | 3 — generalise `Mode` to role traits before Phase 4 |
| Reactive PR review | Not in spec; §22 explicitly rejects webhooks (polling stays acceptable) | 5 — reuses reviewer + comment-posting path |
| Knowledge base / RAG | Not in spec | 5 — new module, MCP-exposed |
| Custom action triggers (`/hal`-style) | Not in spec | 5 — cheap addition once reactive watch exists |
| GitLab support | §20 v2 candidate | 3 if needed by work projects, otherwise 4 |

### 7.1 Two tensions worth resolving in Phase 3, not later

1. **`Mode` as sealed 2-case ADT vs. role-traits.** The longer code is
   written against the 2-case shape, the more it costs to generalise.
   Phase 3 funds the refactor while there are still only two concrete
   pairings.
2. **`.forge/state/` and `.forge/log/` location.** Committed audit/specs
   stay in-repo (correct, §4). Local state/log paths get routed through
   a helper from Slice 1.2 onward (see §2.6 and design §17 Slice 2) so
   Phase 4 can re-root them at an instance directory without touching
   every callsite.

### 7.2 Known v1.2 spec/code gaps deferred to v1.3 (or next-revision spec)

Surface here so they don't get lost between sub-PRs. Each one has an
explicit deferred-decision entry in
[`design-rationale.md`](design-rationale.md); the durable home for
"what v1.3 must close" lives there. Items are grouped by what closing
each one actually requires — Slice 1.4 needs to walk all four buckets
before flipping §2.4 from `[~]` to `[x]`.

#### 7.2.1 Spec-text edits (close in the v1.3 revision)

These need wording or example changes in `forge-design-1.3.md`; they
do not block Slice 1.4 code.

- **S2-1 — Manifest data types live in `forge-core`, not `forge-specs`.**
  v1.3 §3.2 needs the re-attribution. Implementation already correct.
- **S2-2 — `FsmEvent` ADT shape not enumerated in v1.2.** Slice 1.2
  Task 1.2.2 settled the 20-variant list. v1.3 should lift the variants
  into §6 or §11 as appropriate.
- **S2-6 — `Feature.designPrFeedbackRound: Int` projection not in
  v1.2 §6.** v1.3 §6 needs the field added to `Feature` (and
  optionally a §11.3 sentence naming the counter source).
- **S2-7 — `fsm.transition` payload encodes full `FsmState`, not
  the class-name tag.** v1.3 §19's worked example needs lifting to
  a parameterised case so the encoding is documented, not just
  illustrated by the singleton-case form.
- **S2-10 — `audit.piece_merged` payload key tightened to `"p"`
  only.** v1.3 §19 should pin the payload schema explicitly
  (`{ p, prNumber, mergeCommit, mergedAt }`).
- **C14 (spec half) — §7.10(a) "applies to resume" claim. ✅ CLOSED
  2026-05-30 at Task 1.4.14 (option ii).** `forge-design-1.3.md` §7.1 /
  §7.10(a) widens the trait to carry `systemPromptPath` on
  `resumeStreamingSpec`; Claude ignores it, Codex re-prepends the
  block. Shipped coupled with the orchestrator half below.
- **S3-6 — `gh pr create` has no `--json` flag.** v1.3
  design-rationale BM8 needs the wording corrected to name the
  stdout-URL parse contract (`gh pr create … | parse /pull/<n>/`);
  optionally name a two-call fallback (`gh pr create … && gh pr
  view <url> --json number`) for installations behind a
  strict-no-stdout proxy. Slice 1.3 already ships the URL-regex
  parser.
- **S3-7 — `PollBaseline` cursors are
  `BaselineCursor(at: Instant, seenIds: Set[String])`; empty-body
  posts are dropped at decode time.** v1.3 design-rationale RL2 and
  v1.2 §6 / §9 need the cursor shape, the same-second `seenIds`
  tie-breaker (one-second timestamp resolution on `gh`), and the
  empty-body filter on `unseenComments` documented. Slice 1.3 already
  ships the round-2 contract.
- **S3-8 — `reviewDecision: ""` (empty string) decodes as `None`.**
  v1.3 §9 should note the `gh` null-flattening quirk on the
  `reviewDecision` field alongside the field listing. Slice 1.3
  already pins the contract via `open-fresh-no-reviews.json` +
  unit test.

#### 7.2.2 Slice 1.4 implementation / test gates (must land before §2.4 closes)

These need code in Slice 1.4, not just spec text. Each is a gating
deliverable on the relevant sub-PR (Slice 1.4a or Slice 1.4b per §2.4).

- **C14 (orchestrator half). ✅ CLOSED 2026-05-30 at Task 1.4.14.**
  v1.3 chose to widen the trait (option ii), so the orchestrator's
  resume path (`RealSideEffects.resumeDesign`) calls the widened
  signature, passing `promptPath("specify")` — the same driver prompt
  the spawn used. Both halves of C14 shipped coupled in Task 1.4.14.
- **C15 — Native schema regression suite (landed in Task 1.4.7). ✅ CLOSED
  2026-05-29.** ≥19/20 bar on `reviewDesign` / `reviewPr` / `refine`
  for each connector — **met for all six method × connector pairs** in
  a full live batch (claude reviewer on `haiku`, codex on
  `gpt-5.3-codex`, 3-min cap). En route, three real-CLI drifts were
  found and fixed inside 1.4a (C16 envelope, C17 Codex strict schema,
  C18 Claude 2.1.156 tolerant parse). Production reviewer (model, cap,
  timeout-retry) tuning deferred to Task 1.4.9 / S4-3.
- **S2-5 — Writer-side atomic-merge ordering test (landed in Task 1.4.11).
  ✅ CLOSED 2026-05-29.** `OrchestratorAtomicMergeSuite` drives the real
  orchestrator loop to `PieceAwaitingMerge`, crashes on the merge-audit
  append, and pins the §11.5-step-1 writer order: manifest persisted
  `merged` first, the action-log batch absent, and the state cache lagging
  at the pre-transition state. A direct `RebuildState.run` restart recovers
  to `Refining` via reconcile case (c).
- **S2-8 / S3-5 — `SettleTimeout` reviewer/refine coverage decision
  (landed in Task 1.4.12). ✅ CLOSED 2026-05-29.** Option (i) chosen:
  `Fsm.transition` now handles all 7 `SessionPhase` variants —
  `SettleTimeout(SessionPhase.{DesignReview, CodeReview, Refine}, _)`
  route from `DesignReviewing` / `PieceAwaitingReview` / `Refining` to
  phase-appropriate `NHI` hints (`ReopenDesign` / `RunAnotherFixup` ×2),
  with three new `ResumeHintCoverageSuite` rows. The orchestrator-side
  `ReviewerOutcome.Timeout` → `FsmEvent.SettleTimeout` mapping landed at
  Task 1.4.10. The `SessionMonitor` driver-phase carve-out (S3-5) stands
  as designed. See design-rationale **S2-8** / **S3-5** CLOSED notes.

#### 7.2.3 Conditional watch items (fix only if Slice 1.4 measures the cost)

These do not need work unless Slice 1.4 surfaces the named cost cliff.
If they stay quiet under real load, they roll into v1.3 as documented
defaults, not code changes.

- **S2-3 — `ActionLog` write durability vs. throughput.** Current
  `APPEND + SYNC` may hit a perf cliff under heavy orchestrator
  write rate; fallback is per-batch `force()` after a non-syncing
  write, with the trade-off documented. Watch item only.
- **S2-9 — `verifyAgainstLog` always writes the cache.** Current
  unconditional `RebuildState.run` → `cache.save` costs
  temp+rename+fsync on every consistency check; fallback is a
  byte-identical compare-then-skip or a manifest+log fingerprint
  cache. Watch item only.
- **S3-2 — `BranchProtectionCache` is process-local in-memory.**
  Default behaviour: no on-disk persistence; epoch bumps on every
  `forge resume` re-fetch from `gh api` (~150ms per resume). Watch
  item only — if Slice 1.4 surfaces a need to persist the cache
  across orchestrator restarts, S3-2 reopens as a Slice 1.4 watch
  item.
- **S3-4 — `PRWatcher.PollResult.RateLimited` is a non-failing
  stream event with a three-consecutive-rate-limit cliff.** The
  watcher emits `RateLimited(retryAfter)` once per back-off and
  promotes the Nth consecutive into `Failed(GhError.RateLimited)`.
  Watch item only — if Slice 1.4 wants a tighter contract (e.g.
  first rate-limit becomes a hard `Failed`, or the threshold
  becomes config-shaped), S3-4 is the anchor.

**Walked at Slice 1.4b Task 1.4.15 O5 (2026-05-30) — all four roll into
v1.3 as documented defaults; none triggered a code change.** No cost
cliff was surfaced: Slice 1.4b ships no throughput-benchmark harness
(the orchestrator e2e suites assert correctness, not steady-state write
rate) and the first lived-cadence run is the Task 1.4.16 MVP gate, which
had not yet run when 1.4b closed its polish. Per-item disposition:
**S2-3** — keep `CREATE,APPEND,SYNC`; at the single-digit-actions/sec
peak the spec cites, fsync is dominated by orchestrator/network latency.
**S2-9** — moot for v1: the landed `Orchestrator` rebuilds via
`RebuildState.run` **only at startup** (`Orchestrator.scala:74/100`) and
persists steady-state via `cache.save` per transition
(`Orchestrator.scala:185`); `verifyAgainstLog` is **not wired into the
loop at all**, so the unconditional-write cost it flagged never lands on
a hot path. **S3-2** — keep the process-local `BranchProtectionCache`;
the ~150ms re-fetch is per-`forge resume`, not per-poll, so it is
imperceptible. **S3-4** — keep the three-consecutive cliff; no lived
`forge run` cadence yet to judge it against. **Re-trigger:** the Task
1.4.16 MVP run, or Phase-2 lived cadence, is the first real observation
point; any cliff there reopens the matching fallback (documented in
`design-rationale.md` S2-3 / S2-9 / S3-4 and in the S3-2 bullet above).

#### 7.2.4 No v1.3 spec change needed (durable explanation in design-rationale)

- **S2-4 — `PrSnapshot` ownership doc mismatch.** v1.2 §3.2 was
  already correct; the `AGENTS.md` module-layout table was the
  outlier and was corrected by Task 1.2.7 G3. Closed.
- **S3-1 — `forge-git` invokes `gh` / `git` via `os-lib` directly,
  not `forge-agents.Subprocess`.** Module-layout call: one-shot CLI
  invocations don't need the streaming wrapper Slice 1.1 introduced
  for the long-lived Claude / Codex sessions. No v1.3 §3.2 / §3.3
  edit needed; design-rationale S3-1 captures the reasoning so a
  future contributor doesn't re-derive it.
- **S3-3 — `GhClient` / `GitClient` trait abstractions.** v1.2 §9
  lists `BranchManager` / `PRWatcher` methods but doesn't mandate
  an inner abstraction; Slice 1.3 introduces traits + `Real…` impls +
  `Fake…` test fixtures purely as a testability seam so the §9 /
  §11.3 / §11.4 / §11.5 logic exercises don't need a real `gh`
  binary. No v1.3 spec change needed.
- **S3-5 — `SessionMonitor` scope excludes reviewer/refine phases.**
  Mirrors **S2-8** on the SessionMonitor side: the four driver
  phases are the only ones with a `Stream[IO, AgentEvent]` to
  watch; reviewer/refine are one-shot adapter calls whose
  wall-clock caps live in Slice 1.4a's reviewer-asset wrappers. No
  separate v1.3 spec change — S2-8's resolution covers both
  sides. ✅ CLOSED 2026-05-29 with **S2-8** at Task 1.4.12 (see §7.2.2).

---

## 8. What this roadmap deliberately does *not* do

- Lock calendar dates. Phases are gates.
- Promise specific v2 candidates from §20. Pick by lived experience.
- Pre-design Phase 4. It needs `forge-design-2.0.md` before any code,
  written when Phase 3 is close to shipping (so the constraints are
  fresh).
- Commit to CMUX. CMUX is one possible viewer for the Phase 4
  container status feed; the daemon's status API is the actual
  contract, and any viewer (CMUX, a web dashboard, the TUI itself) can
  consume it.
