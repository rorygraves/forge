# CLAUDE.md — Claude Code project guide for Forge

The canonical project guide lives in [`AGENTS.md`](AGENTS.md). Read
that first; everything below is Claude-Code-specific.

## TL;DR

- **Implementation contract:** [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md). The 1.1 revision is kept in-tree as an evolution record but is superseded.
- **Phase plan:** [`docs/roadmap.md`](docs/roadmap.md).
- **Active implementation plan:** [`docs/design-2.1.md`](docs/design-2.1.md) — Slice 1 / Agent connectors. Per-section breakdown into named sub-PRs (A, B, C…) with checkbox items. See `AGENTS.md` §"Per-section implementation plans" for the pattern. Read this *before* picking up Slice-1 work — it tells you what's done, what's next, and which sub-PR each task belongs to.
- **Current state:** Slice 0 complete; Slice 1 (`forge-agents`) in
  progress. Trait-shape code PR (PR-A in `design-2.1.md`) is in
  flight: items A1–A4 landed (event-shape change, trait extension,
  connector signatures, StreamingDriver `initialUserInput` +
  `encodeAnswer` hooks). Next up: A5 (Claude streaming-spec
  implementation) and A6 (Codex multi-process facade). After PR-A,
  PRs B/C/D add the real-CLI integration tests, and PR-E does the
  roadmap close-out.
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

Everything else: see [`AGENTS.md`](AGENTS.md).
