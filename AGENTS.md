# AGENTS.md — Forge project guide

This file is the canonical guide for any agentic tool working inside
this repository (Codex CLI, Claude Code via [`CLAUDE.md`](CLAUDE.md),
and others). Read it before writing code.

## What this project is

Forge is a Scala 3 meta-orchestrator that drives the Claude Code and
Codex CLIs to take a feature from intent → design → piece-by-piece
implementation → PR → merge. Cross-model review; human-in-the-loop;
incremental merge.

The implementation contract is [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md).
1.1 is kept in-tree as an evolution record but is superseded. If the
spec and this file disagree, the spec wins — and please open a PR to
fix this file.

## Current state

- **Slice 0 (CLI validation) — complete.** Findings folded into design
  v1.1 and carried forward into v1.2.
- **Slice 1 (`forge-agents` — connectors) — ✅ closed 2026-05-26.**
  `forge-agents` ships both connectors against the v1.2 §7.1
  trait: deterministic event parsers
  (`AskUserQuestion(toolUseId: Option[String])` carries the
  Native tool_use id and emits `None` on the §7.3 HaltWithQuestion
  path), `Subprocess` + `StreamingDriver` plumbing
  (init-event synchronisation, stderr-drain buffer, `UserMessage`
  mirror event, connector-supplied `encodeUserInput` /
  `initialUserInput` / `encodeAnswer` hooks), `ClaudeConnector`
  (headless + streaming-spec end-to-end with the §7.2 `tool_result`
  wire encoder; `MissingToolUseId` adapter error for the
  parser-regression diagnostic), `CodexConnector` + the
  `CodexStreamingSession` multi-process facade (one `codex exec
  [resume] --json` subprocess per turn under
  `cats.effect.std.Mutex`, single shared events channel with
  resume-turn Init filtered, thread-id mismatch raises, per-turn
  failure surfaces non-zero exit / missing Result to the caller,
  and the in-mutex `closedRef` recheck rejects sends queued behind a
  concurrent `close()` / `kill()`), shared `ReviewDecoders` +
  `ReviewerPrompts` + `ReviewerAssets` + typed `ReviewerError`
  adapter errors. PR-A through PR-E in `design-2.1.md` are landed.
  Real-CLI integration suites in `forge-it`: Claude headless smoke,
  `ClaudeStreamingSpecSuite`, Codex headless smoke,
  `CodexStreamingSpecSuite`, and `CodexHaltWithQuestionReliabilitySuite`
  (opt-in via `FORGE_IT_RUN_RELIABILITY=1`). **Carry-forward to
  v1.3 / Slice 4** (see [`docs/design-rationale.md`](docs/design-rationale.md)
  and [`docs/roadmap.md`](docs/roadmap.md) §7.2): **C14** —
  `CodexConnector.resumeStreamingSpec` can't honour §7.10(a)
  system-prompt prepending under the shared trait signature
  (orchestrator-side resume code, lands with Slice 2 FSM, must be
  written aware of it); **C15** — PR-D (≥19/20 native schema
  regression suite) deferred to the reviewer-asset PR in Slice 4 (it
  needs shipped schemas + reviewer system prompts to run). The
  orchestrator-side `HaltWithQuestion` re-spawn loop also lands with
  Slice 2 FSM — that's an orchestrator concern, not a connector one.
- **Slice 2 (`forge-core` — FSM, Feature, ActionLog, StateCache) —
  ✅ closed 2026-05-26.** `forge-core` ships the `ForgePaths` helper
  (build-gated smell test for `".forge` literals outside the helper),
  the relocated manifest data types under `io.forge.core.manifest`
  (carry-forward **S2-1**), the §6 domain model (`FsmState`,
  `FsmEvent` 20-variant ADT, `Feature`, `ResumeHint`, `Action` /
  `ActionDraft`, `PrSnapshot`, core-side reviewer-verdict
  projections), the pure `Fsm.transition(feature, event, config):
  (Feature, Vector[ActionDraft])` covering every §11 lifecycle rule,
  `FileActionLog` (NDJSON `APPEND | SYNC` with replay
  truncate-and-recover on partial trailing line), `Feature.foldEvents`
  projecting every §6.1 field plus `observedTransitions` /
  `observedPieceMerges`, `FileStateCache` (atomic temp +
  `ATOMIC_MOVE` + parent fsync) with `verifyAgainstLog` per §11.0 step
  4, and `RebuildState.run` with a pure `reconcile` over the four
  §11.5 partial-merge sub-cases. Property-test suite covers §17
  slice-2 invariants 1–13; invariant 14's writer side is deferred to
  Slice 4 (**S2-5**). PR-A through PR-G in `design-2.2.md` are
  landed. **Carry-forward to v1.3 / Slice 4** (see
  [`docs/design-rationale.md`](docs/design-rationale.md) and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **S2-1** through
  **S2-10**, plus the Slice-1 carry-forwards **C14** and **C15**.
- **Slice 3 (`forge-git` — BranchManager + PRWatcher; `forge-app` —
  ProcessLock + SessionMonitor) — ✅ closed 2026-05-27.**
  `forge-git` ships `GhClient` / `GitClient` traits with
  `os-lib`-backed `RealGhClient` / `RealGitClient` (one-shot
  `os.proc.call` per **S3-1**), typed `GhError` / `GitError` ADTs,
  `FakeGhClient` / `FakeGitClient` builder fixtures (**S3-3**
  testability seam), `PrSnapshotDecoder` covering every §6 field
  with explicit CI6 handling (`mergeStateStatus` ignored;
  merge driven by `state == "MERGED"` + non-null `mergedAt`),
  `PollBaseline` cursors as `BaselineCursor(at, seenIds)` with the
  round-2 same-second tie-breaker (**S3-7**), the empty-body
  `unseenComments` filter, the `reviewDecision: ""` null-flattening
  quirk (**S3-8**), `Comments.unseen` / `Comments.advance` pure
  helpers, `BranchManager` covering the full §9 surface (preflight
  per §15, syncBase per BM1, createDesignBranch / createPieceBranch
  returning `(branch, baseSha)`, baseFreshness per BM2 with
  `Updated(newBaseSha)` re-read after `gh pr update-branch`,
  force-with-lease push surfacing `ForceLeaseRejected` per §11.3
  step 5, createPr per BM8 via stdout-URL parse (**S3-6**),
  tagSnapshot / pushTag / deleteRemoteTag / pruneSnapshotTags per
  §11.3 step 4 retention), `BranchProtectionCache` keyed by
  `(featureId, baseBranch, cacheEpoch)` per CI5 with TTL eviction
  and an Unauthorized-empty-overlay fallback (**S3-2** process-local
  watch item), `PRWatcher` as `fs2.Stream[IO, PollResult]` against
  the §9 pinned 11-field set with rate-limit back-off honouring
  `Retry-After` per RL1, baseline cursor advancement on `Snapshot`
  only, and three-consecutive-rate-limits-before-failing (**S3-4**).
  `forge-app` ships `ProcessLock` per §13 (`FileChannel.tryLock` on
  `paths.lockFile` + sibling `paths.lockMetadataFile`, per-instance
  reference counting so nested same-JVM acquires share the OS lock,
  `forceRelease` with `LiveHolderRefused` against an in-process
  holder), `SessionMonitor` per §12 / §7.9 (settle timeout +
  per-turn cost cap invoke `session.kill()`, feature/piece budget
  breaches emit `BudgetBreached` without killing per §12 check 2 via
  an end-of-turn flush, kill-failure resilience via
  `killError: Option[String]` on `SettleTimeout` /
  `TurnBudgetBreached`, scope limited to the four driver phases per
  **S3-5** / S2-8 — reviewer/refine deferred to Slice 4A). PR-A
  through PR-H in `design-2.3.md` are landed. Real-`gh` + real-`git`
  integration coverage in `forge-it`: `BranchManagerIntegrationSuite`
  (opt-in via `FORGE_IT_GH_REPO`) drives clone → bootstrap-main →
  syncBase → createPieceBranch → push → createPr → pollOnce(Open)
  → prMerge → pollOnce(Merged); `ProcessLockMultiJvmSuite` (opt-in
  via `FORGE_IT_RUN_PROCLOCK`) covers the three cross-JVM
  `FileProcessLock` scenarios (live `Held`, crash-stale recovery,
  `forceRelease` live-refusal). **Carry-forward to v1.3 / Slice 4**
  (see [`docs/design-rationale.md`](docs/design-rationale.md) and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **S3-1** through
  **S3-8**, plus the Slice-1/2 carry-forwards **C14**, **C15**,
  and **S2-1** through **S2-10**.
- Slices 4–5 scoped in design §17.
- Phase 4 (Forge-instance pivot: multi-repo, daemon, parallel,
  containerised) is post-v1 and needs its own design doc before any
  code lands. See [`docs/roadmap.md`](docs/roadmap.md).

Where to look first when starting a task:

| Question | File |
|---|---|
| What's the v1 contract? | `docs/forge-design-1.2.md` |
| What's actively being worked on right now? | `docs/design-<section>.md` for the open roadmap section (see "Per-section implementation plans" below) |
| Why was X decided that way? | `docs/design-rationale.md` |
| What's the phase plan beyond v1? | `docs/roadmap.md` |
| What did Slice 0 actually find? | `docs/slice-0/slice-0-report.md` |
| What did Slice 1 find before v1.2 folded it in? | `docs/slice-1/slice-1-findings.md` (now superseded by v1.2) |

## Per-section implementation plans

Each in-progress roadmap section (`docs/roadmap.md` §2.1, §2.2, …) has a
companion `docs/design-<section>.md` carrying the detailed implementation
plan and the checklist used to track progress. Three docs in three
layers — read top-down on a new task:

| Layer | File | Purpose | Lifecycle |
|---|---|---|---|
| Contract | `docs/forge-design-1.2.md` | What the system is *for*; signatures and invariants. | Standalone revisions (`forge-design-1.x.md`) when corrections land. |
| Phase plan | `docs/roadmap.md` | Direction, exit criteria, gates between phases. | Stays terse; ticks bullets `[~]` → `[x]` only after a section's code review passes. |
| Implementation plan | `docs/design-<section>.md` | Per-task checklist for one roadmap section, broken into named sub-PRs. | Created when work on the section starts; lives until the section closes; ticks granular checkboxes as items land. |

### Workflow

1. **Starting a new section** — create `docs/design-<section>.md`
   (mirror the structure of `docs/design-2.1.md`). Break the roadmap
   bullet list into named sub-PRs (A, B, C…) and within each, atomic
   checkbox items (A1, A2, …). Cross-reference v1.2 spec sections and
   design-rationale entries that bear on each item.
2. **Mid-section** — tick items off as they land. Add a dated entry to
   the doc's `§3. Status log` whenever a sub-PR closes.
3. **Closing a section** — only after every sub-PR has landed *and* a
   code review on the section as a whole has passed, flip the roadmap
   bullet from `[~]` to `[x]`. The roadmap is the contract that the
   section is done; design-`<section>`.md is the audit trail.

### Active design-`<section>`.md files

- [`docs/design-2.4.md`](docs/design-2.4.md) — Slice 4 (Phase-1 MVP gate;
  reviewer assets + `forge-specs` (4A) → headless orchestrator + REPL
  (4B)). Opened 2026-05-27. PR-A (reviewer schemas + system prompts under
  `~/.forge/`) is the entry point.

Recently-closed audit trails: [`docs/design-2.1.md`](docs/design-2.1.md)
(Slice 1, closed 2026-05-26), [`docs/design-2.2.md`](docs/design-2.2.md)
(Slice 2, closed 2026-05-26), [`docs/design-2.3.md`](docs/design-2.3.md)
(Slice 3, closed 2026-05-27).

Don't pre-write design-`<section>`.md files for sections that aren't
being actively worked. They drift; the roadmap is enough until the
section opens.

## Code conventions

- **Scala 3.5.x**, sbt build (`build.sbt` at root, module sub-projects
  under `modules/`).
- **`-Xfatal-warnings` is on.** Treat warnings as errors. `-Wunused:imports`
  and `-Wvalue-discard` are also on — discarded non-Unit values must be
  explicit (`val _ = ...`).
- **scalafmt** rules in `.scalafmt.conf`: maxColumn 120, scala3 dialect,
  `RedundantBraces`, `RedundantParens`, `SortImports`. Run
  `sbt scalafmtAll` before committing.
- **Tests:** munit (+ munit-cats-effect for any IO). Test classes are
  `*Suite extends munit.FunSuite`. One suite per source file, mirroring
  the package.
- **JSON:** uPickle. Domain types derive `ReadWriter`. Wire-format
  bimaps for enum-as-string go in companion objects.
- **No upstream `null`** in domain types. Optionality is `Option[T]`.
- **No `.get` on a required session id.** Use `requireSessionId(...)`
  per design §6.2; missing → `Left(NeedsHumanIntervention)`.

Reference file for the style we're aiming at:
`modules/forge-specs/src/main/scala/io/forge/specs/Manifest.scala`.

## Module layout and slice ownership

| Module | Owns | Lands in |
|---|---|---|
| `forge-core` | FSM, `Feature`, `ActionLog`, `StateCache`, `RebuildState`, `Manifest` / `ManifestPatch` / `Piece` / `PieceStatus`, `PrSnapshot` ADT, `ForgePaths`, domain model, `Mode`, `Ids`, `Question`, `FeatureIdSlugger`, `Cost` / `CostTotals` | Slice 2 (manifest types relocated here per **S2-1**; `PrSnapshot` here per §3.2, correcting an earlier `AGENTS.md` row that placed it under `forge-git` — **S2-4**) |
| `forge-agents` | `Connector`, `AgentSession`, `StreamingSession`, Claude/Codex adapters, `Reviews`, `Prompts` | Slice 1 |
| `forge-specs` | `SpecStore`, `DocSync`, `ChangeCollector` | Slice 4 |
| `forge-git` | `BranchManager`, `PRWatcher` | Slice 3 |
| `forge-tui` | termflow app, panes, key bindings | Slice 5 |
| `forge-app` | `ProcessLock`, `SessionMonitor` (Slice 3); `main`, wiring, CLI (Slice 4) | Slice 3+ |
| `forge-it` | Integration tests against real `claude`, `codex`, `gh` | Slice 1+ |

A change that spans multiple modules usually means a slice boundary
needs revisiting — surface that in the PR rather than papering over it.

## Architectural seams to preserve

Two seams are intentionally placed early so the Phase 4 (Forge-instance)
pivot doesn't become a sweep through every caller. **Do not undo
either of these in v1 work** — see `docs/roadmap.md` §2.6.

### 1. Paths helper (design §17 Slice 2)

Every `.forge/` location resolves through a single `ForgePaths` object
constructed from the repo root. No call site hardcodes a `.forge/...`
literal.

```scala
// good
forgePaths.featureLog(featureId)      // → .forge/log/<feature>.jsonl

// bad — hardcoded path; bypasses the seam
os.write(repoRoot / ".forge" / "log" / s"$feature.jsonl", ...)
```

Enforcement: a build-gated unit test (`ForgePathsSuite`'s `os.walk`
sweep) fails the build if a `".forge` literal appears in any
`modules/**/src/main/**/*.scala` file outside `ForgePaths.scala`.
Test fixtures (`src/test/`) are exempt by design.

### 2. Role-trait stub (design §17 Slice 1)

Connectors and orchestrator callers route through a thin `Role`
indirection (`Role.Driver`, `Role.Reviewer`) instead of pattern-matching
on `Mode`. The two-case `Mode` ADT and its `fromString` config wiring
stay unchanged for v1; the seam is purely about call-site discipline.

```scala
// good
role.connector.runStreamingSpec(...)

// bad — match on Mode outside Mode itself
mode match
  case Mode.ClaudeDriver => claudeConnector.run(...)
  case Mode.CodexDriver  => codexConnector.run(...)
```

Smell test: `match m: Mode` (or its destructuring equivalent) outside
`Mode` itself and connector construction.

## Building and testing

```bash
sbt compile                       # all aggregated modules (excludes forge-it)
sbt test                          # unit tests across the build (excludes forge-it)
sbt "project forge-core" test     # one module
sbt scalafmtAll                   # format
sbt scalafmtCheckAll              # CI-style format check
```

Integration tests live in `forge-it` and require `claude`, `codex`, and
`gh` on `PATH` with network access. `forge-it` is **deliberately not in
root's `.aggregate(...)` list** so a default `sbt test` doesn't try to
spawn real CLI subprocesses in a CI / sandbox / cold-laptop environment
where they aren't usable. Run them explicitly:

```bash
sbt "project forge-it" compile    # verify the IT module still compiles after a refactor
sbt "project forge-it" test       # run IT suites (need real CLIs + network)
```

The pinned CLI floors are listed in the README and in §17 of the
design.

## Documentation discipline

- **Spec changes** → next `forge-design-1.x.md` (standalone, per §23).
  Don't edit `forge-design-1.2.md` in place.
- **Non-obvious tradeoff worth preserving** → `docs/design-rationale.md`
  with a cross-reference into the current spec.
- **Phase-level direction** → `docs/roadmap.md`.
- **In-code comments** — Forge follows the policy in this file: write
  comments only when the *why* is non-obvious. Don't restate the spec
  section number unless the code is a direct implementation of a
  specific invariant (e.g. §5.5 reorder, §6.2 `requireSessionId`).

## Things to not do

These are deliberately rejected in v1 (see design §22 and §1
non-goals). Pull requests that introduce them will be rejected:

- **Parallel features.** Concurrency unit in v1 is one feature on one
  laptop. Phase 4 changes this; not before.
- **Worktrees.** Devcontainer-incompatible per prior experience. Use a
  full clone if a second checkout is needed.
- **Real-time webhooks.** Polling `gh` every 30s is the model.
- **LLM4S in the orchestrator.** Forge talks to Claude and Codex via
  their CLIs, not via APIs.
- **A "manager LLM"** choosing which agent does what.
- **Per-session Langfuse traces.** Action log is the source of truth.
- **Capability emulation.** v1 requires native or in-protocol
  capabilities only.
- **Mid-feature mode switching.** The configured `Mode` is locked at
  feature creation.
- **Same CLI in both driver and reviewer roles.** Cross-model review
  is a core property.
- **`.get` on a required session id.** Use `requireSessionId`.
- **Committing `.forge/log/` or `.forge/state/`.** Both are in
  `.gitignore` for a reason — local canonical runtime artefacts only.

## Pull request expectations

- Type-checks, tests pass, scalafmt clean (`sbt scalafmtCheckAll`).
- If the change touches a module's slice ownership, say so in the PR
  description and link to the relevant design section.
- If the change is in `forge-agents`, exercise the relevant integration
  tests when feasible.
- New public types or non-trivial methods get a one-line doc comment.
  Long docstrings are not required; the design doc carries the
  narrative.
- Don't introduce a new top-level dependency without a one-line
  justification in the PR.
