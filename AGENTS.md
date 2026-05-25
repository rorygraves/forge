# AGENTS.md — Forge project guide

This file is the canonical guide for any agentic tool working inside
this repository (Codex CLI, Claude Code via [`CLAUDE.md`](CLAUDE.md),
and others). Read it before writing code.

## What this project is

Forge is a Scala 3 meta-orchestrator that drives the Claude Code and
Codex CLIs to take a feature from intent → design → piece-by-piece
implementation → PR → merge. Cross-model review; human-in-the-loop;
incremental merge.

The implementation contract is [`docs/forge-design-1.1.md`](docs/forge-design-1.1.md).
If the spec and this file disagree, the spec wins — and please open a
PR to fix this file.

## Current state

- **Slice 0 (CLI validation) — complete.** Findings folded into design
  v1.1.
- **Slice 1 (`forge-agents` — connectors) — in progress.** Landed:
  `Role` indirection seam, `PriceTable` + shipped `prices.example.json`,
  `CodexPrompt` (system-prompt prepending, §7.10(a)),
  `CodexSessionSettings` (sticky session-scoped flags, §7.10(c)). Still
  to come: `ClaudeConnector` / `CodexConnector` skeletons,
  `HaltWithQuestion` parsing + re-spawn loop, integration tests in
  `forge-it`.
- Slices 2–5 scoped in design §17.
- Phase 4 (Forge-instance pivot: multi-repo, daemon, parallel,
  containerised) is post-v1 and needs its own design doc before any
  code lands. See [`docs/roadmap.md`](docs/roadmap.md).

Where to look first when starting a task:

| Question | File |
|---|---|
| What's the v1 contract? | `docs/forge-design-1.1.md` |
| Why was X decided that way? | `docs/design-rationale.md` |
| What's the phase plan beyond v1? | `docs/roadmap.md` |
| What did Slice 0 actually find? | `docs/slice-0/slice-0-report.md` |

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
  Don't edit `forge-design-1.1.md` in place.
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
