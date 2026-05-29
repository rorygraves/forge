# Forge — product roadmap

> Companion to [`forge-design-1.2.md`](forge-design-1.2.md). The design doc is
> the implementation contract for v1; this document is the multi-horizon plan
> the design lives inside. Early phases are concrete (and trace directly into
> §17 of the design); later phases capture direction and have not yet been
> turned into specs.
>
> **Status:** draft v0.8 — 2026-05-27. **Slices 1.1, 1.2, and 1.3 closed.**
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

## 0. How to read this

| Phase | Outcome | Source of detail |
|---|---|---|
| 0 — Slice 0 | CLI capabilities validated | [`slice-0/slice-0-report.md`](slice-0/slice-0-report.md) |
| 1 — Testability MVP | Forge ships its own next slice | `forge-design-1.2.md` §17 slices 1–4 |
| 2 — MLP | Pleasant single-repo daily-driver | §17 slice 5 + polish |
| 3 — v1.0 | Single-repo, OSS-ready, role-pluggable | §20 v2 candidates + role-trait refactor |
| 4 — v2.0 | Forge-instance pivot (multi-repo, daemon, parallel, containerised) | Needs its own design doc (`forge-design-2.0.md`) before work starts |
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

🟢 **Slice 1.4a complete, 1.4b open — 2026-05-29.** Implementation plan
lives in [`design-1.4.md`](design-1.4.md) (Task 1.4.1 through Task 1.4.17
across Slice 1.4a + Slice 1.4b). Slice 1.4a (Task 1.4.1 → Task 1.4.8) shipped
the writable foundation: reviewer assets under `~/.forge/`, the
`ReviewerCall` wall-clock wrappers, the repopulated `forge-specs`
(`SpecStore` / `DocSync` / `ChangeCollector`), the v1 templates, and
the Task 1.4.7 regression gate (C15 closed — ≥19/20 for all six method ×
connector pairs with the v1 `haiku` / `gpt-5.3-codex` reviewer config).
**Task 1.4.9 (`forge-app` entry skeleton + config loader) is the 1.4b entry
point.** The §2.4 bullets below stay `[~]` until Task 1.4.17 lands; the
audit trail in `design-1.4.md` ticks per-sub-PR checklists as items
land.

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
- **C14 resolution** — orchestrator's resume code path either
  re-issues Codex role framing in the resume message, or v1.3
  widens the trait to carry `systemPromptPath`. Coupled with the
  v1.3 spec decision; either way the orchestrator has to handle it.
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

**MVP gate (Slice 1.4b exit, Phase 1 exit gate):** drive a small, concrete,
low-variance feature through Forge end-to-end. Pick a contained
target — a small `forge-git` helper, a narrow `forge-app`
reporting/replay feature, or one of the v1.3 spec-text corrections
with tests. **Do not** pick "have Forge build its own Slice 2.1 (TUI)" —
TUI is a subjective UX-iteration loop, the wrong shape for a first
self-hosted run.

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

### 3.1 Slice 2.1 — TUI

Termflow + Elm architecture. Panes per §3.1: status, active (streaming /
log tail / Q&A / idle). Subjective; iterate based on what feels wrong
during real use.

### 3.2 Prompt iteration

The four role prompts (driver-spec, driver-implement, reviewer-design,
reviewer-code) ship with v1 placeholders. After ~5–10 real features:
revise based on observed failure modes, not on lab fixtures. Track
prompt diffs in git; they're load-bearing for behaviour.

### 3.3 OSS-readiness scaffolding

- README that's actually useful to a stranger.
- Config templates committed (`.forge/config.example.json`).
- `prices.example.json` kept current with OpenAI model list.
- Pointer to design doc + rationale from README.
- LICENSE already in place.

---

## 4. Phase 3 — v1.0

**Exit criterion:** you'd recommend it to a friend with a Claude/Codex
license, on their single repo, with a straight face.

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
feature visibly requires it. Phase 1's role-trait *stub* (§2.6) means
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

### 4.3 Hardening

- Documented escape hatch for every `NeedsHumanIntervention` reason.
- Bug log triaged; recurring failure modes covered by tests.
- Prompt-injection / sandbox-escape audit of both driver paths.

---

## 5. Phase 4 — v2.0: Forge-instance pivot

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
require changes to the v1 contract (1.1 / 1.2); all are deliberately
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
- **C14 (spec half) — §7.10(a) "applies to resume" claim.** v1.3
  must either drop the claim or widen the trait signature to carry
  `systemPromptPath`. Coupled to the Slice 1.4 orchestrator
  decision below.
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

- **C14 (orchestrator half).** Slice 1.4b orchestrator's resume path
  either re-issues Codex role framing in the resume message, or
  (if v1.3 chose to widen the trait) calls the widened signature.
  The two halves of C14 ship coupled.
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
