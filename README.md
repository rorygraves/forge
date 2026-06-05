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
- **Phase 3 (Repo Adaptation) — complete.** Forge auto-profiles an
  unfamiliar repository — its build tool, format/lint/build/test
  commands, commit identity, and workflow shape — into a committed,
  reviewable `.forge/profile.json`, and adapts to it. It drove a feature
  end to end on a previously unseen Node/TypeScript repo with **zero
  hardcoded-config edits**, handling a formatting failure as a free
  local step (`prettier`) rather than a paid agent fix-up round.
- **Phase 2 (MLP) — in progress.** This is the work that turns the
  working engine into something a developer other than the author can
  comfortably pick up: run observability and a read-only TUI (both
  landed), packaging of a standalone `forge` launcher (landed), and
  onboarding polish.

> **Trying it today:** install the standalone launcher with
> `scripts/install-forge.sh` and run `forge <command>` from inside any
> repo (see [Install the `forge` launcher](#install-the-forge-launcher)
> below), or run everything from source with `sbt` while hacking on
> Forge itself.

See [`docs/roadmap.md`](docs/roadmap.md) for the phase plan and
[`docs/forge-design-1.15.md`](docs/forge-design-1.15.md) for the
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
| **JDK + sbt** | — | Scala `3.7.1` (set by `build.sbt`); a recent JDK. |

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

### Install the `forge` launcher

The recommended way to use Forge is the standalone launcher. From the
Forge source checkout:

```bash
scripts/install-forge.sh
```

This builds a self-contained `forge.jar` (`sbt forge-app/assembly`),
copies it to `~/.forge/lib/forge.jar`, and installs a small `forge`
launcher into `~/.local/bin` (override with `--bin-dir DIR`). Make sure
that directory is on your `PATH`, then run `forge` from inside whichever
repo you want it to drive:

```bash
forge status        # run from inside the target repo
```

The launcher is a thin wrapper around `java -jar`; it does not change
directory, so Forge operates on your current working directory (or an
explicit `--repo-root`). Re-run `scripts/install-forge.sh` after pulling
new source to refresh the jar. Knobs: `FORGE_HOME` (state/jar root,
default `~/.forge`), `FORGE_JAR` (explicit jar path), `FORGE_JAVA_OPTS`
(extra JVM options). Requires a JDK 21+ on `PATH`.

> **No `PATH` install?** You can skip the launcher and run the jar
> directly: `java -jar ~/.forge/lib/forge.jar <command>`.

### Run Forge

The CLI entry point is `io.forge.app.Main` in the `forge-app` module.
The typical flow for a single feature is **new → spec → run**, then
watch it with **status / tail**:

```bash
forge new my-feature     # create the feature + its design branch
forge spec my-feature    # flesh out the spec in the interactive REPL (/done to finish)
forge run my-feature     # drive it: review → implement pieces → PRs → merge
forge status             # one line per feature (omit the name for the overview)
forge tail my-feature    # stream the feature's action log
```

While hacking on Forge itself, you can run any command from source
through sbt instead of installing the launcher — `sbt "forge-app/run
<command> <args>"`, e.g. `sbt "forge-app/run status"`.

The full command set (`new`, `spec`, `run`, `resume`, `reconcile`,
`refresh-cache`, `abandon`, `profile`, `status`, `tail`, `tui`,
`rebuild-state`, `stats`, `unlock --force`) is specified in
`docs/forge-design-1.15.md` §15. `forge profile` writes the repo's
auto-derived `.forge/profile.json` (Phase 3 adaptation); `forge tui`
opens a read-only terminal view of a feature's progress.

> **Keep the first feature small.** Forge is built around small,
> self-contained, reviewable pieces — and it spends real money on the
> agent CLIs. The default budget caps are **$8 per piece** and **$25
> per feature** (see configuration below).

### Configuration & where Forge keeps things

Per-project state lives under a `.forge/` directory **inside the target
repository** (everything is routed through the `ForgePaths` helper):

- `.forge/config.json` — your configuration (optional; sensible
  defaults apply if absent). A fully-populated, copy-able template
  lives at [`.forge/config.example.json`](.forge/config.example.json)
  — every key at its built-in default, so you only keep the ones you
  change.
- `.forge/profile.json` — the auto-derived **repo profile** (build/
  format/lint/test commands, commit identity, workflow shape), written
  by `forge profile` and committed so it's reviewable and shared across
  machines (Phase 3 adaptation).
- `.forge/specs/<feature>/` — the committed spec assets (`design.md`,
  `manifest.json`, `decomposition.md`, `pieces/…`).
- `.forge/state/`, `.forge/log/` — rebuilt state cache and the canonical
  per-feature action log (gitignored).

Reviewer assets (schemas, prompts, templates) are installed once per
user under `~/.forge/{schemas,prompts,templates}/` on first use.

`config.json` is JSON; the keys most worth knowing (the full §18
reference is in the example file and the design doc):

| Key | Default | Meaning |
| --- | --- | --- |
| `mode` | `"claude-driver"` | Which CLI drives implementation (the other one reviews); the other valid value is `"codex-driver"`. |
| `baseBranch` | `"main"` | Branch features are cut from and merged into. |
| `branchPrefix` | `"forge"` | Prefix for the branches Forge creates. |
| `maxPieceCostUsd` | `8.00` | Spend cap per piece. |
| `maxFeatureCostUsd` | `25.00` | Spend cap per feature. |

The reviewer models in v1 default to the built-in **Claude `haiku`** /
**Codex `gpt-5.3-codex`** pair with a per-review wall-clock cap, tunable
via the `reviewer` block. See `docs/forge-design-1.15.md` §18 for the
complete config reference.

> **Heads-up for external users:** install the launcher
> (`scripts/install-forge.sh`, above) and run `forge` from inside your
> own repo. The remaining rough edge is distribution polish — there is
> no published binary or package yet; you build the jar from this
> checkout. If you mainly want to *see Forge work* against the real
> agent CLIs right now, the integration suites below are the
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
  forge-tui/     ← termflow TUI (`forge tui <feature>`)
  forge-it/      ← integration tests against the real claude/codex/gh CLIs
```

## Documentation

- [`AGENTS.md`](AGENTS.md) — contributor guide and module map (start
  here if you want to work *on* Forge).
- [`docs/roadmap.md`](docs/roadmap.md) — phased delivery plan and
  current status.
- [`docs/forge-design-1.15.md`](docs/forge-design-1.15.md) — the design
  and implementation contract, including the full §15 command set. This
  is the authoritative description of how Forge behaves (earlier
  revisions are superseded stubs that point at the live one;
  [`docs/README.md`](docs/README.md) indexes the whole `docs/` tree).
- [`docs/design-rationale.md`](docs/design-rationale.md) — non-obvious
  tradeoffs preserved through the design's evolution.
- [`docs/slice-0/`](docs/slice-0/) — the captured CLI flag/transcript
  evidence the connectors are built against.

## License

[MIT](LICENSE).
