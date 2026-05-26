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
  next.** See design §17 / roadmap §2.2.
- Slices 3–5 scoped in design §17.
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

- *(none currently open)* — Slice 1 closed 2026-05-26
  (`docs/design-2.1.md` retained as the audit trail). Slice 2
  (`forge-core`) will get a `design-2.2.md` when work starts.

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
| `forge-core` | FSM, Feature, ActionLog, StateCache, domain model, `Mode`, `Ids`, `Question`, `FeatureIdSlugger` | Slice 2 |
| `forge-agents` | `Connector`, `AgentSession`, `StreamingSession`, Claude/Codex adapters, `Cost`, `Reviews`, `Prompts` | Slice 1 |
| `forge-specs` | `Manifest`, `ManifestPatch`, `Piece`, `PieceStatus`, SpecStore, ChangeCollector | Partly Slice 1 (manifest types), rest Slice 2 |
| `forge-git` | `BranchManager`, `PRWatcher`, `PrSnapshot` ADT | Slice 3 |
| `forge-tui` | termflow app, panes, key bindings | Slice 5 |
| `forge-app` | `main`, wiring, `ProcessLock`, `SessionMonitor`, CLI | Slice 4 |
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

Smell test: `grep '"\.forge/'` outside `ForgePaths` itself.

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
sbt compile                       # all modules
sbt test                          # unit tests across the build
sbt "project forge-core" test     # one module
sbt scalafmtAll                   # format
sbt scalafmtCheckAll              # CI-style format check
```

Integration tests live in `forge-it` and require `claude`, `codex`, and
`gh` on `PATH` with network access. They are *not* part of the default
`sbt test` for `forge-core`/`forge-agents`/etc. and must be run
explicitly:

```bash
sbt "project forge-it" test
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
