# Forge

Forge is a Scala meta-orchestrator that sits above the Claude Code and
Codex CLIs. You bring a feature request; Forge shepherds it through
design → piece-by-piece implementation → PR → merge, with cross-model
review and a human in the loop.

Forge is not itself an LLM. It drives the coding-agent CLIs you already
use, coordinating them with a finite-state machine and Git so one person
can supervise many features instead of pair-driving each one.

## Status

Forge is in active development.

- **Phase 1 (MVP) — complete.** The orchestration engine takes a single
  feature from intake to a merged pull request against a real
  repository, and has done so end to end on a live repo. The CLI
  (`forge run` / `forge spec` / `forge status`, plus the rest of the
  §15 command set), the headless orchestrator loop, cross-model review,
  and crash/restart durability are all implemented and tested.
- **Phase 2 (MLP) — in progress.** This is the work that turns the
  working engine into something a developer other than the author can
  comfortably pick up: run observability, packaging/distribution of a
  standalone launcher, and onboarding polish.

> **Trying it today:** everything runs from source with `sbt`. There is
> no packaged `forge` binary yet — a standalone launcher is part of the
> Phase 2 OSS-readiness work — so the commands below are invoked through
> sbt. The engine itself is complete; the rough edge is distribution.

See [`docs/roadmap.md`](docs/roadmap.md) for the phase plan and
[`docs/forge-design-1.4.md`](docs/forge-design-1.4.md) for the
authoritative implementation contract.

## What it does

Concretely, in v1:

- One feature at a time, with a fresh agent context per piece.
- An interactive **spec phase** with the configured *driver* (Claude or
  Codex), then headless implementation, piece by piece.
- **Cross-model review:** the *other* CLI reviews every design and every
  PR.
- One branch per piece off `main`, one PR, one CI run, human-merged.
- A per-feature action log, so any run is resumable after a failure or
  restart.

## Getting started

### Prerequisites

Forge shells out to other tools, so they must be installed, on your
`PATH`, and authenticated the way you normally use them (Forge invokes
them as you — it does not manage their credentials):

| Tool | Floor | Notes |
| --- | --- | --- |
| **Claude Code CLI** | `2.1.150` | Validated flag set is pinned to this floor (see `docs/slice-0/`). |
| **OpenAI Codex CLI** | `0.130.0` | Integration suite runs against `0.133.0`. |
| **GitHub CLI (`gh`)** | `2.83.1` | All PR/branch operations go through it; run `gh auth login` first. |
| **JDK + sbt** | — | Scala `3.5.2` (set by `build.sbt`); a recent JDK. |

### Build and check

```bash
git clone <this-repo>
cd forge

sbt compile          # build all modules
sbt test             # unit tests — fast, hermetic, no external CLIs
sbt scalafmtCheckAll
```

The default test run uses fake CLIs and never touches Claude, Codex, or
GitHub, so it runs anywhere.

### Run Forge

The CLI entry point is `io.forge.app.Main` in the `forge-app` module.
There is no `forge` binary on your `PATH` yet (packaging is Phase 2
work), so today it is launched through sbt:

```bash
sbt "forge-app/run <command> <args>"
```

The typical flow for a single feature is **new → spec → run**, then
watch it with **status / tail**:

```bash
sbt "forge-app/run new my-feature"     # create the feature + its design branch
sbt "forge-app/run spec my-feature"    # flesh out the spec in the interactive REPL (/done to finish)
sbt "forge-app/run run my-feature"     # drive it: review → implement pieces → PRs → merge
sbt "forge-app/run status"             # one line per feature (omit the name for the overview)
sbt "forge-app/run tail my-feature"    # stream the feature's action log
```

The full command set (`new`, `spec`, `run`, `resume`, `reconcile`,
`refresh-cache`, `status`, `tail`, `rebuild-state`, `stats`,
`unlock --force`) is specified in `docs/forge-design-1.4.md` §15.

> **Keep the first feature small.** Forge is built around small,
> self-contained, reviewable pieces — and it spends real money on the
> agent CLIs. The default budget caps are **$8 per piece** and **$25
> per feature** (see configuration below).

### Configuration & where Forge keeps things

Per-project state lives under a `.forge/` directory **inside the target
repository** (everything is routed through the `ForgePaths` helper):

- `.forge/config.json` — your configuration (optional; sensible
  defaults apply if absent).
- `.forge/specs/<feature>/` — the committed spec assets (`design.md`,
  `manifest.json`, `decomposition.md`, `pieces/…`).
- `.forge/state/`, `.forge/log/` — rebuilt state cache and the canonical
  per-feature action log (gitignored).

Reviewer assets (schemas, prompts, templates) are installed once per
user under `~/.forge/{schemas,prompts,templates}/` on first use.

`config.json` is JSON; the keys most worth knowing:

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `ClaudeDriver` | Which CLI drives implementation (the other one reviews). |
| `baseBranch` | `main` | Branch features are cut from and merged into. |
| `branchPrefix` | `forge` | Prefix for the branches Forge creates. |
| `maxPieceCostUsd` | `8.00` | Spend cap per piece. |
| `maxFeatureCostUsd` | `25.00` | Spend cap per feature. |

The reviewer models in v1 are the built-in **Claude `haiku`** /
**Codex `gpt-5.3-codex`** pair with a per-review wall-clock cap. See
`docs/forge-design-1.4.md` §18 for the complete config reference.

> **Heads-up for external users:** running the workflow against your own
> repo from an sbt checkout is awkward today (the packaged `forge`
> launcher that you'd run from inside any repo is exactly the Phase 2
> OSS-readiness gap). If you mainly want to *see Forge work* against the
> real agent CLIs right now, the integration suites below are the
> lowest-friction path.

### See it drive the real CLIs (tests)

The `forge-it` module exercises the *real* `claude`, `codex`, and `gh`
binaries. These are opt-in (they need those tools on `PATH`) and some
are slow, so they are excluded from the default test run:

```bash
sbt "project forge-it" compile
sbt "project forge-it" test

# Watch the reviewer actually review a change (opt-in, ~3-min cap):
FORGE_IT_RUN_REGRESSION_SMOKE=1 \
  sbt "project forge-it" "testOnly *ReviewerRegressionSuite"
```

## Module layout

```
modules/
  forge-core/    ← FSM, Feature, ActionLog, StateCache, domain model
  forge-agents/  ← Connector, AgentSession, Claude/Codex adapters
  forge-git/     ← BranchManager, PRWatcher (gh CLI)
  forge-specs/   ← SpecStore, DocSync, manifest, ChangeCollector
  forge-app/     ← orchestrator, the forge CLI, config, process lock
  forge-tui/     ← termflow TUI (later slice)
  forge-it/      ← integration tests against the real claude/codex/gh CLIs
```

## Documentation

- [`AGENTS.md`](AGENTS.md) — contributor guide and module map (start
  here if you want to work *on* Forge).
- [`docs/roadmap.md`](docs/roadmap.md) — phased delivery plan and
  current status.
- [`docs/forge-design-1.4.md`](docs/forge-design-1.4.md) — the design
  and implementation contract, including the full §15 command set. This
  is the authoritative description of how Forge behaves (the 1.1–1.3
  revisions are superseded stubs that point here).
- [`docs/design-rationale.md`](docs/design-rationale.md) — non-obvious
  tradeoffs preserved through the design's evolution.
- [`docs/slice-0/`](docs/slice-0/) — the captured CLI flag/transcript
  evidence the connectors are built against.

## License

[MIT](LICENSE).
