# CLAUDE.md — Claude Code project guide for Forge

The canonical project guide lives in [`AGENTS.md`](AGENTS.md). Read
that first; everything below is Claude-Code-specific.

## TL;DR

- **Implementation contract:** [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md). The 1.1 revision is kept in-tree as an evolution record but is superseded.
- **Phase plan:** [`docs/roadmap.md`](docs/roadmap.md).
- **Active implementation plan:** [`docs/design-2.1.md`](docs/design-2.1.md) — Slice 1 / Agent connectors. Per-section breakdown into named sub-PRs (A, B, C…) with checkbox items. See `AGENTS.md` §"Per-section implementation plans" for the pattern. Read this *before* picking up Slice-1 work — it tells you what's done, what's next, and which sub-PR each task belongs to.
- **Current state:** Slice 0 complete; Slice 1 (`forge-agents`) in
  progress. **PR-A complete (trait-shape code change), PR-B + PR-C
  complete (real-CLI integration tests):** Claude streaming-spec
  round-trip, resume, `answerQuestion(Some(id), …)`, and `kill()`
  mid-stream pass against Claude 2.1.150 in `ClaudeStreamingSpecSuite`
  (~16s). Codex headless smoke, multi-turn `send()`, resume thread_id
  preservation, `answerQuestion` via resume, and `kill()` mid-turn
  pass against `codex-cli 0.133.0` across `CodexHeadlessSmokeSuite`,
  `CodexStreamingSpecSuite` (~45s). C4 (HaltWithQuestion reliability
  sample, 20 real-CLI runs) is opt-in via `FORGE_IT_RUN_RELIABILITY=1`.
  Two upstream fixes folded into PR-C: `CodexConnector.execArgv`
  swapped `--ask-for-approval` for `-c approval_policy=...` (codex
  ≥0.131); Codex spawns now `closeStdin` immediately (JVM ProcessBuilder
  leaves stdin open; codex hangs on the open pipe). PR-C tests default
  to `gpt-5-codex` with `FORGE_IT_CODEX_MODEL` override for
  account-tier-restricted setups. Next up: PR-D is the reviewer schema
  regression suite (blocked on shipped schemas + system prompts);
  PR-E does the roadmap close-out.
- **Two architectural seams to preserve in v1 work:** `ForgePaths`
  helper (no `.forge/...` literals outside it) and `Role` indirection
  (no `match m: Mode` outside `Mode` and connector construction). See
  `AGENTS.md` for the smell tests.

## Build / test / format

```bash
sbt compile
sbt test                       # unit tests
sbt scalafmtAll                # format
sbt scalafmtCheckAll           # check
sbt "project forge-it" test    # integration tests (require claude, codex, gh on PATH)
```

`-Xfatal-warnings` is on; warnings are errors. Run a fresh `sbt
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
