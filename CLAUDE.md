# CLAUDE.md — Claude Code project guide for Forge

The canonical project guide lives in [`AGENTS.md`](AGENTS.md). Read
that first; everything below is Claude-Code-specific.

## TL;DR

- **Implementation contract:** [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md). The 1.1 revision is kept in-tree as an evolution record but is superseded.
- **Phase plan:** [`docs/roadmap.md`](docs/roadmap.md).
- **Active implementation plan:**
  [`docs/design-2.3.md`](docs/design-2.3.md) — Slice 3 opened
  2026-05-26 (`BranchManager`, `PRWatcher` in `forge-git`;
  `ProcessLock`, `SessionMonitor` in `forge-app`). Entry point is
  PR-A. The most recent closed audit trails are
  [`docs/design-2.2.md`](docs/design-2.2.md) (Slice 2) and
  [`docs/design-2.1.md`](docs/design-2.1.md) (Slice 1). See
  `AGENTS.md` §"Per-section implementation plans" for the pattern
  (sub-PR breakdown with checkbox items).
- **Current state:** Slices 1 and 2 ✅ closed 2026-05-26; Slice 3
  🟢 active. Slice 1 shipped both connectors against v1.2 §7.1 with
  real-CLI integration coverage in `forge-it`. Slice 2 shipped
  `forge-core` — `ForgePaths`, relocated manifest data types,
  `FsmState` / `FsmEvent` / `Feature` / `ResumeHint` / `Action`
  domain model, the pure `Fsm.transition` covering every §11
  lifecycle rule, `FileActionLog` + `Feature.foldEvents`,
  `FileStateCache` + `RebuildState.run`, and the §17 slice-2
  property-test suite (357 unit tests). Slice 3 is now active per
  `design-2.3.md`. **Carry-forward** to v1.3 / Slice 4 (see
  [`docs/design-rationale.md`](docs/design-rationale.md) and
  [`docs/roadmap.md`](docs/roadmap.md) §7.2): **C14** (Codex
  `resumeStreamingSpec` system-prompt prepending), **C15** (PR-D
  ≥19/20 native schema regression suite deferred to the reviewer-asset
  PR in Slice 4), and **S2-1** through **S2-10**.
- **Two architectural seams to preserve in v1 work:**
  - `ForgePaths` helper — no `.forge/...` literals outside it. Enforced
    by `ForgePathsSuite`'s `os.walk` sweep over
    `modules/**/src/main/**/*.scala` (test fixtures exempt). Adding a
    new `".forge` literal outside `ForgePaths.scala` fails the build.
  - `Role` indirection — no `match m: Mode` outside `Mode` itself and
    connector construction.

## Build / test / format

```bash
sbt compile
sbt test                                              # unit tests
sbt scalafmtAll                                       # format
sbt scalafmtCheckAll                                  # check
sbt "project forge-it" test                           # IT (require claude, codex, gh on PATH)
sbt "testOnly *ClaudeStreamingSpecSuite"              # one unit suite
sbt "project forge-it" "testOnly *CodexStreaming*"    # one IT suite
FORGE_IT_RUN_RELIABILITY=1 sbt "project forge-it" test  # opt-in long-running suites
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
- **Tick the active design-`<section>`.md as items land.** When you
  complete a sub-PR item (e.g. `A5` in `design-2.1.md`), flip its
  checkbox in the same change and add a one-line dated entry under
  `§3. Status log`. Don't tick the roadmap bullet — that happens only
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
  `CodexHaltWithQuestionReliabilitySuite` for the pattern. Applies to
  PR-D (reviewer regression) before it lands.

- **Consistency sweep before declaring a sub-PR item done.** For each
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

- **Section closures must explicitly carry deferrals forward.** PR-E
  (or equivalent close-out PRs) cannot flip a roadmap `[~]` to `[x]`
  without first walking the active `design-<section>.md`
  "Carry-forward" list and placing each item somewhere durable
  (roadmap v1.3 bucket, tracking issue, or design-rationale deferred
  decision). The close-out checklist should make this a gating step,
  not a memory check.

- **Ask before scope-expanding.** If a "focused fix" needs code
  outside the current PR's scope, use `AskUserQuestion` with 2–3
  concrete options + a recommendation rather than silently expanding.
  Even in auto mode. The codex 0.130→0.133 flag drift discovery is
  the worked example.

Everything else: see [`AGENTS.md`](AGENTS.md).
