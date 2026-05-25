# Forge

A Scala meta-orchestrator that sits above the Claude Code and Codex
CLIs, breaks features into reviewable pieces, and shepherds each one
through design → implement → PR → merge with cross-model review and
human-in-the-loop.

**Author:** Rory  •  **License:** MIT  •  **Status:** Slice 0 (CLI
capability validation) complete; Slice 1 (agent connectors) in progress —
`Role` indirection + Codex adapter helpers (price table, prompt prepend,
sticky settings) landed; Claude/Codex connector skeletons, the
`HaltWithQuestion` loop, and integration tests still to come.

## What Forge is for

You bring raw intent. The harness shapes it into a design, breaks it
into pieces, hammers each piece through review and CI until it's
mergeable, and remembers everything it did so the process itself can
be tuned.

Concretely, v1:

- One feature at a time, one fresh agent context per piece.
- Interactive spec phase with the configured *driver* (Claude or
  Codex), then headless implementation piece-by-piece.
- Cross-model review: the other CLI reviews every design and every PR.
- One branch per piece off `main`, one PR, one CI run, human-merged.
- Per-feature action log; resumable on every failure.

`docs/forge-design-1.2.md` is the implementation contract.

## Documentation map

| File | What it is |
|---|---|
| [`docs/forge-design-1.2.md`](docs/forge-design-1.2.md) | v1 implementation contract (≈1450 lines) |
| [`docs/forge-design-1.1.md`](docs/forge-design-1.1.md) | Prior revision; kept in-tree as an evolution record (superseded by 1.2) |
| [`docs/design-rationale.md`](docs/design-rationale.md) | Non-obvious tradeoffs preserved from the 0.1 → 1.2 design evolution |
| [`docs/roadmap.md`](docs/roadmap.md) | Multi-horizon roadmap: MVP → MLP → 1.0 → 2.0 (Forge-instance pivot) → 3.0+ |
| [`docs/slice-0/slice-0-report.md`](docs/slice-0/slice-0-report.md) | CLI capability-validation results that grounded v1.1 |
| [`docs/slice-1/slice-1-findings.md`](docs/slice-1/slice-1-findings.md) | Slice-1 runtime findings that grounded v1.2 (superseded by 1.2, kept as evolution record) |
| [`AGENTS.md`](AGENTS.md) | Project guide for agentic tools working in this repo |
| [`CLAUDE.md`](CLAUDE.md) | Pointer to `AGENTS.md` for Claude Code |

## Module layout

```
modules/
  forge-core/    ← FSM, Feature, ActionLog, StateCache, domain model
  forge-agents/  ← Connector, AgentSession, Claude/Codex adapters
  forge-git/     ← BranchManager, PRWatcher (gh CLI)
  forge-specs/   ← SpecStore, DocSync, Manifest, ChangeCollector
  forge-tui/     ← termflow TUI (Slice 5)
  forge-app/     ← main entry, wiring, ProcessLock, SessionMonitor
  forge-it/      ← integration tests against real claude/codex CLIs
```

Module ownership maps onto the build order in
[`docs/forge-design-1.2.md` §17](docs/forge-design-1.2.md#17-build-order-de-risked).

## Building and testing

Requires sbt and Scala 3.5.x (set by `build.sbt`).

```bash
sbt compile          # compile all modules
sbt test             # run unit tests
sbt "project forge-core" test     # tests for one module
sbt scalafmtAll      # format
```

Integration tests in `forge-it` exercise the real `claude` and `codex`
CLIs and the GitHub `gh` CLI; they require those binaries on `PATH` and
network access. They are gated behind the `forge-it` project so unit
runs stay hermetic.

## Pinned tool floors

Slice 0 validated capabilities against these versions; Forge will not
silently support older ones:

- Claude Code CLI ≥ `2.1.150`
- Codex CLI ≥ `codex-cli 0.130.0`
- GitHub CLI ≥ `2.83.1`

## Status

| Phase | Outcome | State |
|---|---|---|
| Slice 0 — CLI validation | Three v1.1 corrections folded in | ✅ complete |
| Slice 1 — Agent connectors | `forge-agents` standalone with integration tests | 🚧 in progress (Role + Codex helpers + reviewer one-shots landed; v1.2 trait-shape spec landed; streaming spawn/resume re-enable + full §17 forge-it suite remain) |
| Slices 2–4 | FSM → git → headless loop | scoped |
| Slice 5 — TUI | termflow + Elm | scoped |
| Phase 4 — Forge-instance pivot | Multi-repo, daemon, parallel, containerised | post-1.0, needs own design doc |

See [`docs/roadmap.md`](docs/roadmap.md) for exit criteria and the
post-v1 direction.

## License

[MIT](LICENSE).
