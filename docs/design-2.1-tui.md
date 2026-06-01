# design-2.1-tui — Slice 2.1 implementation plan (TUI)

> **Maps to:** [`roadmap.md`](roadmap.md) §3.2 (Phase 2 / Slice 2.1 — TUI) and the
> §3.1 component diagram in [`forge-design-1.5.md`](forge-design-1.5.md) ("Forge TUI
> (termflow, Elm-architecture)" — status / active panes).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every uncomplete roadmap section gets a `design-<slice-id>.md` companion. The
> roadmap stays terse — direction + exit criteria — and this file owns the
> per-Task breakdown. Items get ticked here as they land; the roadmap bullet
> (§3.2) is ticked only after a section-wide code review.
>
> **Filename note:** the bare `design-2.1.md` is taken by the legacy Slice 1.1
> audit trail (the `design-2.1/2.2/2.3.md` files predate the WBS slice-id scheme
> and map to Slices 1.1/1.2/1.3). This Slice-2.1 plan therefore uses the
> disambiguated `design-2.1-tui.md`.
>
> **Status:** 🟡 open — 2026-06-01. Second slice of Phase 2 (MLP). Task 2.1.1
> (first runnable slice + the Scala 3.7.1 bump it forced) ✅ landed 2026-06-01;
> the rest is open. The TUI is the §3.1 "richer view" over data Slice 2.0 already
> made replayable — so it is built **log-tail-first** over the canonical action
> log + state cache, with a live event tap deferred (see §0 / Task 2.1.5).

## 0. Exit criterion for Slice 2.1

Slice 2.1 is done when **`forge tui <feature>` opens an Elm-architecture
(termflow) dashboard that renders a running feature's status and active work
from the committed action log + state cache, refreshing as the log grows, and
quits cleanly** — i.e. the §3.1 panes are usable as a daily read-only cockpit
over a real run.

Concretely:

1. A `termflow.tui.TuiApp` renders the §3.1 **status pane** (feature / FSM state /
   current piece / last action / budget-vs-cap) and **active pane**
   (streaming / log-tail / question / idle) from a pure [[TuiSnapshot]].
2. The snapshot is a **projection of committed data** — the same `FileStateCache`
   + `FileActionLog` + `Manifest` that `forge status` / `forge tail` / `forge stats`
   read (`StatusReport.renderFeature` is the field-for-field reference) — so the
   panes are replayable and unit-testable without a live orchestrator.
3. `forge tui <feature>` launches it **read-only** (§15: never takes the
   `ProcessLock`, never mutates state), polling the log via `Sub.Every` so a
   concurrent `forge run` is reflected within the poll interval.
4. Keyboard quit (`q` / `Ctrl-C`) and a `:q` prompt command exit cleanly,
   restoring terminal state.
5. Coverage is headless: `termflow.testkit.TuiTestDriver` drives the app and
   asserts the rendered frame + transitions with no real terminal.

**Deliberately deferred inside this slice (see §4):** token-by-token *live*
streaming in the active pane needs an in-process `AgentEvent` tap on the
orchestrator (a new seam). Log-tail-first satisfies the exit criterion with
per-settle granularity; the live tap is Task 2.1.5 and rolls forward if real
use doesn't justify the added coupling. This matches the Slice-2.0
"instrument before optimise" ordering and the data-flow decision recorded in §4.

Tiering: **Tier 1** (Tasks 2.1.1–2.1.3) is the gating deliverable — a runnable,
read-only status+active dashboard wired to `forge tui`. **Tier 2** (Tasks
2.1.4–2.1.6) makes it answerable (scrollable log-tail, Q&A display, theming/resize).
**Tier 3** (Task 2.1.5 live tap + 2.1.7 polish) is richer liveness and may roll
forward. The exit gate requires Tier 1 + the log-tail pane (2.1.4).

## 1. Task breakdown

### Tier 1 — a runnable read-only dashboard

### Task 2.1.1 — termflow wiring + app skeleton  ⬅ **first runnable slice** ✅ 2026-06-01

**The riskiest contract, exercised first** (CLAUDE.md "run code earlier"): does
termflow's Elm contract wire cleanly into this repo at all? Standing up a real
`TuiApp` immediately surfaced the **Scala-version wall** (see §4 T1) that a
design-doc pass would have missed.

Landed:

- `modules/forge-tui/src/main/scala/io/forge/tui/TuiSnapshot.scala` — the pure
  pane read-model (`TuiSnapshot` + `ActivePane`), documented as a committed-data
  projection (the Task 2.1.2 builder fills it).
- `ForgeTui.scala` — `ForgeTui.App(initial: TuiSnapshot)` extending
  `TuiApp[Model, Msg]` (`init`/`update`/`view`/`toMsg`): a two-pane (status +
  active) virtual-DOM view, `Sub.Every(1s)` tick + `Sub.InputKey` quit-on-`q`/`Ctrl-C`,
  `:q` prompt command, and a `run(snapshot)` entry over `TuiRuntime.run`.
- `ForgeTuiAppSuite` — 9 tests through `TuiTestDriver` + `GoldenFrame.serialize`
  (frame content + Tick/Key/quit transitions), no real terminal.
- `build.sbt` — termflow `0.4.0` (aggregator `termflow` + Test-only
  `termflow-testkit`) on `forge-tui`; **`ThisBuild / scalaVersion` 3.5.2 → 3.7.1**
  (forced — see §4 T1). Whole-repo fallout fixed: 1 stale unused import
  (`forge-app` test) + 7 positional-implicit `Codec` rewrites (`forge-it`).
- Green: 1342 unit tests pass, `forge-it` compiles, scalafmt clean.

### Task 2.1.2 — `TuiSnapshot` builder (fold log + cache → panes)

Build the snapshot the skeleton currently takes by hand. Fold `FileStateCache`
(fast path) + the last `FileActionLog` actions + `Manifest` into `TuiSnapshot`,
**reusing `StatusReport.renderFeature`'s field semantics** (state label, piece
label, last action, budget-vs-cap) rather than re-deriving them — and add a
`StatsReport`-style cost/turn roll-up for the budget line. Map `FsmState` →
`ActivePane`:

- `PieceImplementing` / `PieceFixingUp` / `Refining` → `Streaming`
- `DesignNeedsHumanInput` / `*NeedsHumanIntervention` → `Question`
- `PieceAwaitingCi` / `*AwaitingReview` / `*AwaitingMerge` / `DesignAwaitingMerge`
  → `Idle` (awaiting external)
- otherwise → `LogTail`

`forge-tui` can't depend on `forge-app` (`forge-app dependsOn forge-tui`), so the
builder lives in `forge-tui` over `forge-core` types. If `StatusReport`'s render
helpers are worth sharing verbatim, lift the pure bits into `forge-core` first
(small, separate change) rather than duplicating — **consistency-sweep this against
`StatusReport` before declaring done** (CLAUDE.md).

### Task 2.1.3 — `forge tui <feature>` command

Wire a read-only `forge tui <feature>` into `io.forge.app.command` (alongside
`status` / `tail` / `stats`): resolve paths + config, build an initial snapshot
(Task 2.1.2), and `ForgeTui.run` it with a `Sub.Every` refresh that re-folds the
log. **Read-only per §15** — never acquires `ProcessLock`, never writes; mirror
`StatusReport`'s "reads cache directly, never `RebuildState`" stance so it is safe
to run against an in-flight `forge run`. Register the command in `CommandRouter` +
`ForgeCommand`.

### Tier 2 — make it answerable

### Task 2.1.4 — scrollable action-log pane

A log-tail active-pane view over `FileActionLog` (the `forge tail` data) with
`ArrowUp`/`ArrowDown`/`PageUp`/`PageDown` scroll and follow-tail. This is the pane
that makes the dashboard a usable substitute for `forge tail`; **required for the
exit gate.**

### Task 2.1.5 — live `AgentEvent` tap (deferred liveness) — Tier 3, may roll forward

Token-by-token streaming in the active pane. Needs an **in-process event-tap seam**
on the orchestrator: today streamed `AgentEvent`s are consumed by `SessionMonitor`
and discarded (no observer seam). Add an opt-in tap that publishes events to a
`termflow` `Sub`, so `forge run --tui` (or an attach handshake) can host the
orchestrator and stream live. Revisits the §3.1 in-process Sub/Cmd model; gated on
real use justifying the coupling (§4 T3). Out-of-scope of the Tier-1 gate.

### Task 2.1.6 — Q&A pane (display)

Render `DesignNeedsHumanInput.questions` / pending `AskUserQuestion` content in the
active pane when `ActivePane.Question`. v1 **displays** the question + points the
operator at the existing answer path (`forge spec` REPL / PR); *answering from the
TUI* is a later slice (it would need the write path + lock, breaking §15 read-only).

### Tier 3 — polish

### Task 2.1.7 — theme / resize / key-help

Adopt a `termflow.tui.Theme`, switch the view to `Layout` for reflow-on-resize
(today's frame is a fixed 80×20), and add a key-binding help overlay. "Subjective;
iterate based on what feels wrong during real use" (roadmap §3.2).

### Task 2.1.8 — Slice 2.1 close-out

Walk §4 carry-forward into durable homes; **reconcile the stale spec §3.3
dependency note** (termflow `0.0.1`/`0.1.0-SNAPSHOT` → `0.4.0` multi-module + the
Scala-3.7.1 floor) into a `forge-design-1.6.md` revision (per the §23 standalone-
revision rule — don't edit 1.5 in place); record the Scala-bump decision in
`design-rationale.md`; then tick roadmap §3.2.

## 2. Order of work

2.1.1 (done) → 2.1.2 (builder) → 2.1.3 (`forge tui` command) gives a usable
read-only dashboard = Tier-1 gate. Then 2.1.4 (log-tail, required for exit) →
2.1.6 (Q&A display) → 2.1.7 (polish). 2.1.5 (live tap) slots in whenever real use
demands token-level liveness, else rolls forward. 2.1.8 closes.

## 3. Status log

- **2026-06-01 — Slice 2.1 opened; Task 2.1.1 landed.** Data-flow decided
  log-tail-first via `AskUserQuestion` (§4 T3); doc filename `design-2.1-tui.md`
  chosen to avoid the legacy `design-2.1.md` collision. Grounded the plan against
  termflow **0.4.0**'s published sources (Elm contract: `TuiApp`/`Cmd`/`Sub`/`VNode`/
  `RootNode`/`Layout`/`Theme`/`Keymap`/widgets + `TuiTestDriver` testkit). Standing
  up the first runnable slice immediately surfaced the **Scala-version wall**
  (§4 T1): termflow ships only for Scala 3.7.x (TASTy 28.7), unreadable by forge's
  3.5.2 compiler. Per "ask before scope-expanding," confirmed the repo-wide bump
  with the operator, then bumped `ThisBuild / scalaVersion` 3.5.2 → 3.7.1. Fallout
  was small: main compiled clean; 1 newly-flagged unused import in a `forge-app`
  test and 7 positional-implicit `Codec` call sites in `forge-it` (rewritten to
  `(using scala.io.Codec("UTF-8"))`). Final state: `forge-core` / `forge-agents` /
  `forge-git` / `forge-specs` / `forge-app` + new `forge-tui` (9 tests) all green
  = 1342 unit tests; `forge-it` compiles; scalafmt clean.

## 4. Carry-forward / decisions opened

### T1 — termflow forces Scala 3.7.1 (repo-wide bump) ✅ done this slice

termflow `0.3.0` and `0.4.0` are both built with Scala **3.7.1** (TASTy 28.7); a
3.5.2 compiler reads only TASTy ≤ 28.5, so there is **no** termflow build forge
could consume at 3.5.2. Bumping forge to 3.7.1 was the only path. Done in Task
2.1.1. **Durable record:** add a `design-rationale.md` entry (the bump was an
external-constraint decision, not a preference). **Watch item:** Maven Central
currently carries only termflow `0.3.0`; `0.4.0` resolves from the local ivy repo
(`publishLocal`, on sbt's default resolver chain). Before §3.4 OSS-readiness,
either `0.4.0` must ship to Central or forge must pin a Central-published version —
otherwise a fresh clone can't resolve the build. Close at Task 2.1.8.

### T2 — spec §3.3 dependency note is stale

`forge-design-1.5.md` §3.3 still says `termflow:0.0.1` / "depend on a
`0.1.0-SNAPSHOT` from publishLocal until 0.1.0 ships". Reality: `0.4.0`, a
multi-module split (`termflow-{terminal,screen,app,widgets,testkit}` + aggregator),
and a Scala-3.7.x floor. Reconcile into `forge-design-1.6.md` at close-out (Task
2.1.8) — **not** edited into 1.5 in place (§23 standalone-revision rule).

### T3 — data-flow: log-tail-first, live tap deferred

Decided via `AskUserQuestion` (2026-06-01): v1 reads committed data (replayable,
no orchestrator change, testable without a live run); a live `AgentEvent` tap
(Task 2.1.5) adds token streaming later if real use justifies the coupling. The
§3.1 component diagram shows in-process Sub/Cmd over the orchestrator — our v1 is a
narrower read-only viewer; the in-process host is the Task 2.1.5 evolution, not a
contradiction. Reconcile the diagram's framing in the 1.6 revision if it ships.

## 5. Cross-references

- [`roadmap.md`](roadmap.md) §3.2 — the terse slice direction this expands.
- [`forge-design-1.5.md`](forge-design-1.5.md) §3.1 (panes), §3.2 (`forge-tui`
  module), §3.3 (deps — stale, see T2), §15 (read-only commands).
- `StatusReport.renderFeature` / `StatsReport` (`modules/forge-app/.../command/`)
  — the field-for-field reference for the Task 2.1.2 snapshot builder.
- [`design-2.0.md`](design-2.0.md) — the prior slice that made the action log a
  replayable, self-describing source (what the TUI consumes).
- [`design-rationale.md`](design-rationale.md) — Scala-3.7.1-bump decision (T1).
