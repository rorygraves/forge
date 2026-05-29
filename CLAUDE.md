# CLAUDE.md — Claude Code project guide for Forge

The canonical project guide lives in [`AGENTS.md`](AGENTS.md). Read
that first; everything below is Claude-Code-specific.

## TL;DR

- **Implementation contract:** [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md). The 1.1 revision is kept in-tree as an evolution record but is superseded.
- **Phase plan:** [`docs/roadmap.md`](docs/roadmap.md).
- **Active implementation plan:**
  [`docs/design-1.4.md`](docs/design-1.4.md) — Slice 1.4 (Phase-1 MVP
  gate). Opened 2026-05-27. **Slice 1.4a (Task 1.4.1 → Task 1.4.8) closed
  2026-05-29; Slice 1.4b open** — Task 1.4.9 (`forge-app` entry skeleton +
  config loader) is the entry point. The most recent closed audit
  trails are [`docs/design-2.3.md`](docs/design-2.3.md) (Slice 1.3,
  closed 2026-05-27), [`docs/design-2.2.md`](docs/design-2.2.md)
  (Slice 1.2), and [`docs/design-2.1.md`](docs/design-2.1.md) (Slice
  1.1). See `AGENTS.md` §"Per-section implementation plans" for the
  pattern (Task breakdown with checkbox items).
- **Current state:** Slices 1.1, 1.2, 1.3 ✅ closed 2026-05-27;
  **Slice 1.4a ✅ closed 2026-05-29; Slice 1.4b 🟢 open.**
  Slice 1.1 shipped both connectors against v1.2 §7.1 with real-CLI
  integration coverage in `forge-it`. Slice 1.2 shipped `forge-core` —
  `ForgePaths`, relocated manifest data types,
  `FsmState` / `FsmEvent` / `Feature` / `ResumeHint` / `Action`
  domain model, the pure `Fsm.transition` covering every §11
  lifecycle rule, `FileActionLog` + `Feature.foldEvents`,
  `FileStateCache` + `RebuildState.run`. Slice 1.3 shipped
  `forge-git` (`GhClient` / `GitClient` one-shot CLI seams,
  `PrSnapshotDecoder` + `BaselineCursor(at, seenIds)`,
  `BranchManager` + `BranchProtectionCache`, `PRWatcher`) and
  `forge-app` (`ProcessLock`, `SessionMonitor`). **Slice 1.4a** shipped
  the orchestrator's writable foundation: in-tree reviewer assets +
  `AssetInstaller` (first-run install into `~/.forge/`), the
  `io.forge.app.reviewer` wall-clock boundary (`ReviewerCall` /
  `ReviewerOutcome` / `RealReviewerCall`), the repopulated `forge-specs`
  (`SpecStore` / `FileSpecStore`, `DocSync` + `HandlebarsLite`,
  `ChangeCollector` + `StagingConfig`), the v1 templates, and the
  Task 1.4.7 `ReviewerRegressionSuite` that **closed C15** (≥19/20 for all
  six method × connector pairs; v1 config claude=`haiku` /
  codex=`gpt-5.3-codex`, 3-min cap). Test scope: `forge-core` 358,
  `forge-agents` 196, `forge-git` 168, `forge-specs` 132 (new),
  `forge-app` 96, plus `forge-it` opt-in regression/smoke suites.
  **Carry-forward into Slice 1.4b** (see
  [`docs/design-1.4.md`](docs/design-1.4.md) §4,
  [`docs/design-rationale.md`](docs/design-rationale.md), and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **C14** (Codex resume
  role-framing — Task 1.4.14), **S2-5** (writer-side atomic-merge test —
  Task 1.4.11), **S2-8** / **S3-5** (reviewer/refine `SettleTimeout`
  mapping, B3 chose option (a) — Task 1.4.12), **S4-3** (reviewer cost /
  kill diagnostics — watch item), **S4-5** (production reviewer
  model/cap/retry tuning — Task 1.4.9 `ForgeConfig`); the long-standing
  **S2-1**–**S2-10** / **S3-1**–**S3-8** spec-text items reconcile at
  Task 1.4.17.
- **Two architectural seams to preserve in v1 work:**
  - `ForgePaths` helper — no `.forge/...` literals outside it. Enforced
    by `ForgePathsSuite`'s `os.walk` sweep over
    `modules/**/src/main/**/*.scala` (test fixtures exempt). Adding a
    new `".forge` literal outside `ForgePaths.scala` fails the build.
  - `Role` indirection — no `match m: Mode` outside `Mode` itself and
    connector construction.

## Build / test / format

```bash
sbt compile                                           # unit modules (forge-it excluded — see AGENTS.md "Building")
sbt test                                              # unit tests (forge-it excluded)
sbt scalafmtAll                                       # format
sbt scalafmtCheckAll                                  # check
sbt "project forge-it" compile                        # rebuild forge-it after a refactor
sbt "project forge-it" test                           # IT (require claude, codex, gh on PATH)
sbt "testOnly *ClaudeStreamingSpecSuite"              # one unit suite
sbt "project forge-it" "testOnly *CodexStreaming*"    # one IT suite
FORGE_IT_RUN_RELIABILITY=1 sbt "project forge-it" test  # opt-in long-running suites
# Reviewer regression bar (C15). Opt-in; v1 config = claude reviewer on haiku, 3-min cap.
FORGE_IT_RUN_REGRESSION=1 FORGE_IT_CLAUDE_MODEL=haiku sbt "project forge-it" "testOnly *ReviewerRegressionSuite"
FORGE_IT_RUN_REGRESSION_SMOKE=1 sbt "project forge-it" "testOnly *ReviewerRegressionSuite"  # cheap wiring smoke
# More knobs (all documented in ReviewerRegressionSuite's docstring): FORGE_IT_REGRESSION_SAMPLES,
# FORGE_IT_REGRESSION_CAP, FORGE_IT_SKIP_CLAUDE/CODEX, FORGE_REVIEWER_RAW_DUMP_DIR (raw-envelope capture for drift triage).
```

Scala 3.5.2, sbt build. `-Xfatal-warnings`, `-Wunused:imports`, and
`-Wvalue-discard` are on; warnings are errors. Run a fresh `sbt
compile` before declaring a task done.

## Things Forge itself happens to care about

Forge is a meta-orchestrator that drives Claude Code and Codex via
their CLIs. A few cross-cutting points that show up while working on
*this* repo:

- The pinned Claude Code CLI floor is `2.1.150`. Slice 0 validated
  flags (`--bare`, `--setting-sources project,local`,
  `--strict-mcp-config`, `--system-prompt-file`, `--json-schema`,
  `--resume`, `--output-format stream-json`) are documented in
  `docs/slice-0/slice-0-report.md`. Don't change connector code in a
  way that assumes flags outside that set.
- `AskUserQuestion` is the native Claude question mechanism Forge
  relies on (§7.2). The `assistant.message.content[].type == "tool_use"`
  event shape is the contract; integration tests assert against it.
- The session-id-preserved-on-resume behaviour (§6.1) is also pinned
  via Slice 0 transcripts in `docs/slice-0/transcripts/`. If you find
  yourself updating those, write up *why* in `docs/design-rationale.md`
  too.

## Claude-Code-specific etiquette in this repo

- **Default to small, contract-conformant edits.** The v1.2 spec is
  long but settled; surprises usually mean a misread of the spec,
  not a needed change.
- **Tick the active design-`<section>`.md as items land — but not
  during a review round.** When you complete a Task item (e.g. `A5`
  in `design-2.1.md`), flip its checkbox in the same change and add a
  one-line dated entry under `§3. Status log`. Do **not** flip `[ ]`
  → `[x]` in the same commit as a change that is still under review;
  premature ticks mask outstanding issues and force a "round N+1 to
  un-tick" later. Don't tick the roadmap bullet — that happens only
  after a code review on the whole section. See `AGENTS.md`
  §"Per-section implementation plans".
- **Spec edits go to the next revision file.** Don't edit
  `forge-design-1.2.md` in place — open a `forge-design-1.3.md` (per
  the §23 "standalone revisions" rule). The exception is the small
  inline pointer to `roadmap.md` already in §17, which exists to keep
  readers oriented.
- **Don't introduce `langfuse` / `llm4s` / API-direct calls** in
  v1 code. The orchestrator talks to CLIs only (§3.3, §22).
- **Verify integration tests with real CLIs**, not mocks, whenever the
  change touches `forge-agents`. Mocks for Claude/Codex behaviour
  drift from reality faster than the test suite catches.

## Testing & review discipline

Lessons that have repeatedly bitten in this repo. Each one is also
captured as a feedback memory; this list is the source of truth for
human + agent reviewers.

- **Mirror existing test idioms.** Before writing a new test against
  `forge-agents` or `forge-it`, grep for the closest analogous existing
  test and copy its lifecycle shape. Especially: **close-then-drain**
  is the only safe order for streaming-spec / multi-process-facade
  sessions. The events channel stays open until the underlying CLI
  exits (which only happens after `closeStdin`), so `compile.toVector`
  before `close()` deadlocks. Headless `-p '<prompt>'` mode is the
  exception (CLI exits on its own). See `CodexConnectorSuite` facade
  tests and `ClaudeStreamingSpecSuite` for the canonical idiom.

- **Fake-CLI scripts must mirror real-CLI blocking behaviour.** A fake
  shell script that doesn't `read` stdin can't catch hangs on the real
  CLI (codex 0.133 blocks waiting for EOF on a JVM-spawned open stdin
  pipe; that was invisible to every unit test). For any new CLI code
  path, pair the fake-CLI unit test with an integration test in
  `forge-it`, or convince yourself the fake's I/O behaviour matches the
  real binary's.

- **Default-on test runtime: <60s.** Anything multi-minute (≥20-sample
  reliability checks, regression batches) is opt-in via env var. Forge
  convention is `FORGE_IT_RUN_*`; document the gate in the suite
  docstring's "Opt-in by default" line. See
  `CodexHaltWithQuestionReliabilitySuite` for the pattern, and
  `ReviewerRegressionSuite` (Task 1.4.7, landed) whose docstring is the
  canonical reference for the reviewer-bar env knobs.

- **Consistency sweep before declaring a Task item done.** For each
  new file, diff lifecycle / error handling / invariant checks against
  the closest sibling. For each invariant assertion, grep for every
  code path that touches the underlying datum and confirm the check
  applies there too. Most "obvious in hindsight" review comments come
  from this gap — the knowledge wasn't missing, the consistency check
  was.

- **Review comments on design docs are signals, not the patch list.**
  When a reviewer flags a contract problem in a design doc — an FSM
  signature, a trait method, an error channel — *don't* edit only the
  spot they pointed at and call it done. Re-walk the call chain end to
  end: every producer of the changed type, every consumer, every
  cross-reference, every test that asserts against the old contract.
  The original mistake almost certainly leaked into adjacent places
  you also wrote, and patching only the named line moves the
  inconsistency one section over. After each round, ask "what does
  this change *imply* elsewhere?" before declaring the round done.
  The worked example is `design-2.2.md`'s three review rounds: round 1
  surfaced the FSM signature gap; rounds 2 and 3 found further
  contract leaks (`Feature` lacks history, `Refining.startedAt`
  semantics, partial-batch crash recovery, inconsistent error
  channels) that were already implied by round 1's fix but went
  unaddressed because the patch was local. Cost: three review rounds
  to settle what should have been one coherence pass.

- **"We deviate from spec §X because…" comments are flags, not
  resolutions.** File the gap as a numbered entry in
  `docs/design-rationale.md` (C-series for connector items) with
  proposed v1.3 resolutions; rewrite the code comment to point at it;
  add a carry-forward item to the active `design-<section>.md` §4 so
  the section close can't bury it. The C14 entry is the worked
  example.

- **Section closures must explicitly carry deferrals forward.** The
  close-out Task (the last numbered Task in a Slice) cannot flip a
  roadmap `[~]` to `[x]` without first walking the active
  `design-<slice-id>.md`
  "Carry-forward" list and placing each item somewhere durable
  (roadmap v1.3 bucket, tracking issue, or design-rationale deferred
  decision). The close-out checklist should make this a gating step,
  not a memory check.

- **Ask before scope-expanding.** If a "focused fix" needs code
  outside the current PR's scope, use `AskUserQuestion` with 2–3
  concrete options + a recommendation rather than silently expanding.
  Even in auto mode. The codex 0.130→0.133 flag drift discovery is
  the worked example.

- **Run code earlier — prefer a thin runnable slice over a thicker
  design pass.** When opening a new section, the first Task should
  put *executing* code (or a real-CLI capture, or a property-test
  harness against an existing module) in front of the riskiest
  contract, not just refine the design doc. A 50-line spike that
  proves the FSM signature or the connector wire shape catches more
  bugs than another paragraph of prose, and gives later Tasks real
  fixtures to ground on. Two ways this pays off: (a) the design-doc
  review rounds compress, because the contract has already been
  exercised; (b) the "real shape" rule below is satisfied as a
  byproduct. Design-doc cycles that ran 4–5 rounds (design-2.2,
  design-1.4) had no runnable code until late; Slices where Task 1
  shipped exercisable code (Slice 1.1, Slice 1.3) settled in 1–2 rounds.
  This does **not** license skipping the design doc — it licenses
  reordering, so the design absorbs feedback from running code.

- **Capture real external shapes before writing decoders / schemas /
  flag tables.** Before writing code that parses output from an
  external tool (`gh`, `claude`, `codex`, OpenAI pricing JSON, etc.)
  or that asserts which CLI flags exist, capture a real sample:
  `gh ... --json ... > docs/slice-N/fixtures/...`, `claude --help`,
  the live pricing page. Pin the capture as a fixture or a
  design-rationale snippet. Never derive field or flag names from
  the spec, from a prior version, or from another tool — they drift.
  Worked examples: the `gh 2.83.1` decoder that asserted on a
  `databaseId` field gh no longer emits; the `runStreamingSpec`
  variant that omitted `-p / --print` even though `claude --help`
  documents it; OpenAI prices invented from memory landing in a
  fixture. This rule extends [`io-integration-tests`] — that one is
  about subprocess *lifecycle*; this one is about *wire shape*.

- **Two rounds on the same contract → reconciliation note, not
  another patch.** If two consecutive review rounds flag the same
  underlying contract (the same FSM signature, the same trait
  method, the same error channel) in different cells, stop
  patching. Write a one-paragraph contract-reconciliation note
  enumerating every affected surface — producers, consumers,
  cross-references, dependent tests, exit criterion, status log,
  later Task handoffs — then patch all in one diff. The cost of
  the note is one paragraph; the cost of not writing it is round 3
  and round 4. Worked example: `design-2.2.md` ran 5 rounds where
  rounds 2–5 were all implications of round 1's signature change;
  `design-1.4.md` ran 4 rounds with the same shape.

- **`AskUserQuestion` is licensed mid-round, not just at scope-set.**
  When a review round surfaces a finding whose fix could be either
  "patch the cited cell" or "rewrite the surrounding contract,"
  ask. Two options + a recommendation, in `AskUserQuestion`. One
  question replaces 1–2 review rounds. The prior rule against
  silent scope expansion already covers this; this bullet is an
  explicit reminder that the same tool applies inside an active
  review cycle, not only at the start of new work.

Everything else: see [`AGENTS.md`](AGENTS.md).
