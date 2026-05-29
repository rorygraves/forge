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
- **Slice 1.1 (`forge-agents` — connectors) — ✅ closed 2026-05-26.**
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
  v1.3 / Slice 1.4** (see [`docs/design-rationale.md`](docs/design-rationale.md)
  and [`docs/roadmap.md`](docs/roadmap.md) §7.2): **C14** —
  `CodexConnector.resumeStreamingSpec` can't honour §7.10(a)
  system-prompt prepending under the shared trait signature
  (orchestrator-side resume code, lands with Slice 2 FSM, must be
  written aware of it); **C15** — PR-D (≥19/20 native schema
  regression suite) — **✅ closed in Slice 1.4a Task 1.4.7** (2026-05-29):
  `ReviewerRegressionSuite` met the bar for all six method × connector
  pairs with the v1 reviewer config (claude=`haiku` / codex=`gpt-5.3-codex`,
  3-min cap); see design-rationale C15/C16/C17/C18 + carry-forward S4-5. The
  orchestrator-side `HaltWithQuestion` re-spawn loop also lands with
  Slice 2 FSM — that's an orchestrator concern, not a connector one.
- **Slice 1.2 (`forge-core` — FSM, Feature, ActionLog, StateCache) —
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
  Slice 1.4 (**S2-5**). PR-A through PR-G in `design-2.2.md` are
  landed. **Carry-forward to v1.3 / Slice 1.4** (see
  [`docs/design-rationale.md`](docs/design-rationale.md) and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **S2-1** through
  **S2-10**, plus the Slice-1 carry-forwards **C14** and **C15**.
- **Slice 1.3 (`forge-git` — BranchManager + PRWatcher; `forge-app` —
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
  **S3-5** / S2-8 — reviewer/refine deferred to Slice 1.4a). PR-A
  through PR-H in `design-2.3.md` are landed. Real-`gh` + real-`git`
  integration coverage in `forge-it`: `BranchManagerIntegrationSuite`
  (opt-in via `FORGE_IT_GH_REPO`) drives clone → bootstrap-main →
  syncBase → createPieceBranch → push → createPr → pollOnce(Open)
  → prMerge → pollOnce(Merged); `ProcessLockMultiJvmSuite` (opt-in
  via `FORGE_IT_RUN_PROCLOCK`) covers the three cross-JVM
  `FileProcessLock` scenarios (live `Held`, crash-stale recovery,
  `forceRelease` live-refusal). **Carry-forward to v1.3 / Slice 1.4**
  (see [`docs/design-rationale.md`](docs/design-rationale.md) and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **S3-1** through
  **S3-8**, plus the Slice-1/2 carry-forwards **C14**, **C15**,
  and **S2-1** through **S2-10**.
- **Slice 1.4a (reviewer assets + `forge-specs` repopulation +
  regression gate) — ✅ complete 2026-05-29; Slice 1.4b open.**
  Reviewer assets ship in-tree under `assets/reviewer/{schemas,prompts}/`
  (3 JSON Schemas + 6 per-method × per-CLI system prompts) and
  `assets/templates/` (7 templates per §11.4 / §7.7 / §14.3), with
  `io.forge.app.bootstrap.AssetInstaller.installIfMissing(paths)`
  copying each into `~/.forge/{schemas,prompts,templates}/` on first
  run (existing files preserved; typed `WriteFailed` /
  `InvalidExistingDestination` on failure). `ForgePaths` grew
  `userSchemasDir` / `userPromptsDir` / `userTemplatesDir` so the
  install destinations keep the `.forge` seam intact. The
  `io.forge.app.reviewer` boundary (`ReviewerCall` +
  `ReviewerLimits(wallClockTimeout)` +
  `ReviewerOutcome[+A] = Settled | Timeout | AdapterFailure`,
  `RealReviewerCall` racing each one-shot `connector.review*` against
  `IO.sleep(cap)`) colocates the §7.9 wall-clock cap with the reviewer
  call — fiber-cancellation only, subprocess cleanup left to the
  connector `Resource` finalizer (no observable kill channel:
  carry-forward **S4-3**). `forge-specs` is re-populated (it lost its
  sources in Slice 1.2 per **S2-1**): `SpecStore` /
  `FileSpecStore` (atomic temp+`ATOMIC_MOVE`+fsync persistence of
  `manifest.json` / `design.md` / `decomposition.md` / `pieces/<p>.md`
  with `Manifest.validate` on load), `DocSync` (renders
  `decomposition.md` from the manifest via a hand-rolled
  `HandlebarsLite`, idempotent byte-identical re-render, §5.3 reconcile
  markers), and `ChangeCollector` (Allow/Deny/Ask per §10.1, all 16
  §18 default deny patterns enforced via `StagingConfig.DefaultDenyPatterns`,
  `**/`-prefix glob workaround per **CC4**). **Task 1.4.7 closed C15**:
  `ReviewerRegressionSuite` (`forge-it`, opt-in
  `FORGE_IT_RUN_REGRESSION=1`) met the ≥19/20 native-schema bar for all
  six method × connector pairs with the v1 reviewer config
  (claude=`haiku` / codex=`gpt-5.3-codex`, 3-min cap); three real-CLI
  drifts (**C16** Claude 2.1.153 `result`-string payload, **C17** Codex
  `--output-schema` strict subset, **C18** Claude 2.1.156 tolerant
  parse) were found and fixed inside 1.4a. Test scope grew: `forge-app`
  46 → 96, new `forge-specs` 132; `forge-agents` 181 → 196 (C16/C17/C18
  decode tests); `forge-core` 358 / `forge-git` 168 preserved.
  **Carry-forward into Slice 1.4b** (see
  [`docs/design-1.4.md`](docs/design-1.4.md) §4 and
  [`docs/design-rationale.md`](docs/design-rationale.md)): **C14**
  (Codex resume role-framing — Task 1.4.14), **S2-5** (writer-side
  atomic-merge test — Task 1.4.11), **S2-8** / **S3-5** (reviewer/refine
  `SettleTimeout` mapping, B3 chose option (a) — Task 1.4.12), **S4-3**
  (reviewer cost / kill diagnostics — watch item), **S4-5** (production
  reviewer model/cap/retry tuning — Task 1.4.9 `ForgeConfig`). Slice 1.4b
  (Task 1.4.9 → Task 1.4.17) builds the headless orchestrator, the §17
  CLI, and the MVP self-hosting gate on top.
- Slices 1.4b and 2.1 (TUI) scoped in design §17.
- Phase 4 (Forge-instance pivot: multi-repo, daemon, parallel,
  containerised) is post-v1 and needs its own design doc before any
  code lands. See [`docs/roadmap.md`](docs/roadmap.md).

Where to look first when starting a task:

| Question | File |
|---|---|
| What's the v1 contract? | `docs/forge-design-1.2.md` |
| What's actively being worked on right now? | `docs/design-<slice-id>.md` for the open Slice (see "Per-section implementation plans" below) |
| Why was X decided that way? | `docs/design-rationale.md` |
| What's the phase plan beyond v1? | `docs/roadmap.md` |
| What did Slice 0 actually find? | `docs/slice-0/slice-0-report.md` |
| What did Slice 1 find before v1.2 folded it in? | `docs/slice-1/slice-1-findings.md` (now superseded by v1.2) |

## Work breakdown numbering

Work is broken down hierarchically. Three levels carry IDs; the fourth
is free-form checklists. The scheme is dotted-decimal (WBS — Work
Breakdown Structure, the PM standard).

| Level | Name | ID shape | Example |
|---|---|---|---|
| 1 | **Phase** | `N` | `1` = Phase 1 (Testability MVP); `4` = Phase 4 (Forge-instance pivot) |
| 2 | **Slice** | `N.M` | `1.2` = Phase 1 Slice 2 (`forge-core` / FSM); `1.4` = Phase 1 Slice 1.4 (orchestrator) |
| 3 | **Task** | `N.M.K` | `1.4.1` = Phase 1, Slice 1.4, Task 1 (reviewer schemas + system prompts) |
| 4 | (sub-task) | none | free-form checklist within a Task |

Names map to existing vocabulary: Phase and Slice are unchanged; "Task"
replaces the older "Sub-PR" label (which collided with GitHub PRs).

### Prefix-qualifier rule

In prose, always lead with the level name on first mention in a
document: **"Slice 1.4"**, **"Task 1.4.2"**. The bare numeric form
(`1.4`, `1.4.2`) is only valid inside a "Slice ID" / "Task ID" column
or when context already pins the level. Section markers from
`forge-design-1.X.md` keep their `§` prefix (e.g. `§7.1`, `§11.3 step 5`)
so spec references stay distinct from breakdown IDs.

### Retroactive bisection

If a Slice or Task needs to be split *after* it opens (work surfaced
implicit halves, design proved larger than expected), append a letter
suffix:

- Slice 1.4 splits → **Slice 1.4a** + **Slice 1.4b**. Later sibling
  Slices (1.5, 1.6, …) keep their numbers.
- Task 1.4.7 splits → **Task 1.4.7a** + **Task 1.4.7b**. Same rule.

Do not *plan* bisection. If a Slice is large enough that you know up
front it's two halves, give them their own IDs (1.4 + 1.5). Letter
suffixes are for splits that emerge during work, because they preserve
the position of later siblings.

### Sub-task checklists (level 4)

Inside a Task, atomic items are free-form checkboxes — no numeric ID:

```
### Task 1.4.1 — Reviewer schemas + system prompts under ~/.forge/

- [x] In-tree asset layout under `assets/reviewer/`:
  - schemas/design-review.json, code-review.json, refine.json
  - prompts/design-review.{claude,codex}.md, …
- [x] `io.forge.app.bootstrap.AssetInstaller` — installIfMissing(…)
- [x] Unit suite under modules/forge-app/src/test/scala/…
- [x] Wire design-1.4.md into the parent docs
- [x] Landing checklist (compile clean, tests green, scalafmt clean)
```

Cite a sub-task by Task ID + description, not by position
("Task 1.4.1's `AssetInstaller` item"). The numbered `A1, A2` style
the codebase used historically is retired — descriptions disambiguate
better than position counters.

### Carry-forward replacement (no parallel code series)

Items that defer from one Slice to a later Slice are **not** given
codes like `C14` or `S2-5`. Instead:

1. **While Slice N.M is active**, deferrals live in
   `design-N.Mb.md §4 Carry-forward` as plain prose items. Each names
   *what* defers and *where* it's expected to land
   (e.g. "Slice 1.5 reviewer assets" before Slice 1.5 has opened).
2. **When Slice N.M+1 opens**, the `design-N.(M+1).md` author pulls
   items from the predecessor's §4 and incorporates them as **numbered
   Tasks** in the new plan. The §4 entry is then annotated
   "→ became Task N.(M+1).K".
3. **Historical codes** (C14, C15, S2-1 … S2-10, S3-1 … S3-8) stay
   in committed history and existing docs — they are references to
   past work, not active identifiers. Do not retroactively renumber
   them; do not create new ones.

The roadmap + the active `design-N.M.md` are the single source of
truth for "what's left." No parallel coding scheme to maintain, no
mapping table.

### File naming

`design-N.M.md` matches the Slice ID. Slice 1.4 → `docs/design-1.4.md`.
When a Slice closes, its design file stays in-tree as the audit
trail. The historical filenames `design-2.1.md` … `design-2.3.md`
predate this convention; they remain as-is (closed audit trails) and
are not renamed.

## Per-section implementation plans

Each in-progress roadmap Slice (`docs/roadmap.md` §2.1, §2.2, …) has a
companion `docs/design-<slice-id>.md` (e.g. `design-1.4.md` for Slice
1.4) carrying the detailed implementation plan and the checklist used
to track progress. Three docs in three layers — read top-down on a new
task:

| Layer | File | Purpose | Lifecycle |
|---|---|---|---|
| Contract | `docs/forge-design-1.2.md` | What the system is *for*; signatures and invariants. | Standalone revisions (`forge-design-1.x.md`) when corrections land. |
| Phase plan | `docs/roadmap.md` | Direction, exit criteria, gates between phases. | Stays terse; ticks bullets `[~]` → `[x]` only after a section's code review passes. |
| Implementation plan | `docs/design-<slice-id>.md` | Per-Task checklist for one Slice, broken into numbered Tasks. | Created when work on the Slice starts; lives until the Slice closes; ticks granular checkboxes as items land. |

### Workflow

1. **Starting a new Slice** — create `docs/design-<slice-id>.md`
   (mirror the structure of an existing design file). Break the
   roadmap bullet list into numbered **Tasks** (1.N.1, 1.N.2, …) and
   within each, free-form checkbox items (no numeric ID). Cross-
   reference v1.2 spec sections and design-rationale entries that bear
   on each item. **Task 1.N.1 should prefer a thin runnable slice over
   a doc-only opener** — see "Run code early" below.
2. **Mid-Slice** — tick items off as they land. Add a dated entry to
   the doc's `§3. Status log` whenever a Task closes. **Do not flip
   `[ ]` → `[x]` in the same commit as a change still under review.**
3. **Per-Task coherence-sweep checklist** — before declaring a Task
   "✅ landed" (i.e. before the landing checklist passes and the
   header flip happens), tick the items below. They are the most
   repeatedly-burned-by gaps across Slices 1.1–1.4 and exist to
   compress the review-round count:

   - [ ] **Grep sweep on every new/changed type, signature, or state
     name.** Every reference across this design doc, the active spec
     revision, later Task plans, exit criterion (§0), status log
     (§3), and dependent-Task handoffs points at the *post-change*
     shape. No stale wording survives from the prior contract.
   - [ ] **Sibling diff.** For every new file, locate its closest
     sibling in the existing codebase and read both side-by-side.
     For every modified handler/branch, ask "is the analogous branch
     in this file or a peer file handled the same way?"
   - [ ] **Invariants live at the helper.** Every invariant added is
     enforced at the constructor / helper / transition function that
     all paths funnel through — not inline at the cited call site.
     Sibling code paths feeding the same data are checked too.
   - [ ] **Real external shapes captured.** Any new decoder, schema,
     flag table, or wire assertion against an external tool (gh,
     claude, codex, pricing JSON) is grounded in a captured sample
     (fixture under `docs/slice-N/fixtures/`, design-rationale
     snippet, or `--help` capture) — not derived from the spec.
   - [ ] **Tests mirror the closest existing idiom.** Subprocess /
     streaming / async-resource tests follow the
     close-then-drain pattern unless headless `-p '<prompt>'` mode.
     Fake-CLI scripts mirror real-CLI blocking behaviour.
   - [ ] **Carry-forward placed.** Anything deferred from this Task
     is filed in `§4 Carry-forward` as a plain prose item naming the
     destination Slice. No new C-series or SN-M codes — see
     "Carry-forward replacement" above. Cross-cutting items that
     also need spec-text edits get a cross-reference in
     `docs/design-rationale.md`. "We deviate from spec §X because…"
     in code comments is a flag, not a resolution.

4. **Closing a Slice** — only after every Task has landed *and* a
   code review on the Slice as a whole has passed, flip the roadmap
   bullet from `[~]` to `[x]`. The roadmap is the contract that the
   Slice is done; `design-<slice-id>.md` is the audit trail. The
   close-out Task must walk the `§4 Carry-forward` list and place
   each item somewhere durable (next-Slice numbered Task, tracking
   issue, design-rationale deferred decision).

### Run code early — thin runnable Task 1 over doc-only opener

The lesson from Slices 1.1–1.4 is unambiguous: Slices whose Task 1
shipped **executing code** against the riskiest contract settled in
1–2 review rounds; Slices whose Task 1 was a doc-pass (or a wide
design extension without code) settled in 4–5 rounds, with each round
surfacing the next implication of the previous round's fix.

When opening a Slice:

- Identify the riskiest contract — the FSM signature, the connector
  wire shape, the JSON schema the reviewer must satisfy, the
  pagination cursor invariant. The thing that, if wrong, invalidates
  the rest of the Slice.
- Make Task 1 a **thin slice that exercises it**: a 50-line spike, a
  property-test harness against an existing module, a captured
  real-CLI fixture plus the decoder that consumes it, a single
  end-to-end test against a stub. Just enough to run.
- Subsequent Tasks can do the breadth work, and they have real
  fixtures + a proved-out contract to ground on.
- The design doc still leads — but it absorbs feedback from running
  code, rather than absorbing it from review rounds.

This does not replace the design doc; it reorders the work inside
each Slice. The reviewer can argue with a paragraph for three rounds;
they can't argue with a green test.

### Escalating after two same-contract rounds

If two consecutive review rounds flag the same underlying contract
(same FSM signature, same trait method, same error channel, same
state name) in different cells, stop patching. Write a one-paragraph
contract-reconciliation note in `§3 Status log` enumerating every
affected surface — producers, consumers, cross-references, dependent
Task handoffs — then patch all in one diff. Worked examples:
`design-2.2.md` (Slice 1.2) ran 5 rounds where rounds 2–5 were all
implications of round 1's signature change; `design-1.4.md`
(Slice 1.4, formerly `design-2.4.md`) ran 4 rounds in the same shape.

### Active design-`<slice-id>`.md files

- [`docs/design-1.4.md`](docs/design-1.4.md) — Slice 1.4 (Phase-1 MVP
  gate; reviewer assets + `forge-specs` (Slice 1.4a) → headless
  orchestrator + REPL (Slice 1.4b)). Opened 2026-05-27. **Slice 1.4a
  (Task 1.4.1 → Task 1.4.8) closed 2026-05-29; Slice 1.4b open** — Task 1.4.9
  (`forge-app` entry skeleton + config loader) is the entry point.

Recently-closed audit trails: [`docs/design-2.1.md`](docs/design-2.1.md)
(Slice 1.1, closed 2026-05-26), [`docs/design-2.2.md`](docs/design-2.2.md)
(Slice 1.2, closed 2026-05-26), [`docs/design-2.3.md`](docs/design-2.3.md)
(Slice 1.3, closed 2026-05-27). Historical files retain their
`design-2.N.md` filenames; they are sealed audit trails.

Don't pre-write design-`<slice-id>`.md files for Slices that aren't
being actively worked. They drift; the roadmap is enough until the
Slice opens.

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
| `forge-core` | FSM, `Feature`, `ActionLog`, `StateCache`, `RebuildState`, `Manifest` / `ManifestPatch` / `Piece` / `PieceStatus`, `PrSnapshot` ADT, `ForgePaths`, domain model, `Mode`, `Ids`, `Question`, `FeatureIdSlugger`, `Cost` / `CostTotals` | Slice 1.2 (manifest types relocated here per **S2-1**; `PrSnapshot` here per §3.2, correcting an earlier `AGENTS.md` row that placed it under `forge-git` — **S2-4**) |
| `forge-agents` | `Connector`, `AgentSession`, `StreamingSession`, Claude/Codex adapters, `Reviews`, `Prompts` | Slice 1.1 |
| `forge-specs` | `SpecStore`, `DocSync`, `ChangeCollector` | Slice 1.4 |
| `forge-git` | `BranchManager`, `PRWatcher` | Slice 1.3 |
| `forge-tui` | termflow app, panes, key bindings | Slice 2.1 (TUI; was "Slice 5" pre-WBS) |
| `forge-app` | `ProcessLock`, `SessionMonitor` (Slice 1.3); `main`, wiring, CLI (Slice 1.4) | Slice 1.3+ |
| `forge-it` | Integration tests against real `claude`, `codex`, `gh` | Slice 1.1+ |

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
