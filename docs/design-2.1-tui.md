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
> **Status:** 🟡 open — 2026-06-01. Second slice of Phase 2 (MLP). **Tier 1 and
> Tier 2 are complete** — Tasks 2.1.1 (runnable slice + the Scala 3.7.1 bump it
> forced), 2.1.2 (`TuiSnapshot` builder), 2.1.3 (`forge tui <feature>` command),
> 2.1.4 (scrollable log-tail pane), 2.1.6 (Q&A display), and 2.1.7
> (theme/resize/key-help) ✅ landed 2026-06-01. The slice exit criterion is met;
> Task 2.1.5 (live tap) is carried forward unless real use justifies the new
> orchestrator seam, and Task 2.1.8 close-out remains open pending the
> section-wide review / spec revision. Roadmap §3.2 stays unticked until then.
> The TUI is the §3.1 "richer view" over data
> Slice 2.0 already made replayable — so it is built **log-tail-first** over the
> canonical action log + state cache, with a live event tap deferred (see §0 /
> Task 2.1.5).

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
2.1.4 and 2.1.6) makes it answerable (scrollable log-tail, Q&A display).
**Tier 3** is split between polish (Task 2.1.7, landed) and richer liveness
(Task 2.1.5 live tap, carried forward). The exit gate requires Tier 1 + the
log-tail pane (2.1.4), now also backed by the Q&A and resize/key-help polish tasks.

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

### Task 2.1.2 — `TuiSnapshot` builder (fold log + cache → panes)  ✅ 2026-06-01

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

Landed:

- `modules/forge-core/src/main/scala/io/forge/core/status/StatusFields.scala` — the
  shared pure render helpers (`stateLabel` / `pieceOf` / `pieceLabel` / `pieceSummary`
  / `lastActionLabel` / `budgetLine` + the `NoStateCacheLabel` fallback) **lifted out
  of `StatusReport`** (the "lift the pure bits into `forge-core` rather than
  duplicate" path). `StatusReport.renderFeature` now delegates to them and
  `RebuildStateCommand` / `summaryLines` use `StatusFields.stateLabel`; the
  byte-for-byte `StatusReportGoldenSuite` (20 FSM states + no-cache) still passes, so
  `forge status` and `forge tui` cannot drift.
- `modules/forge-tui/src/main/scala/io/forge/tui/TuiSnapshotBuilder.scala` — pure
  `build(manifest, cached, actions, maxFeatureCostUsd, maxPieceCostUsd)` folding
  committed data into `TuiSnapshot` (status fields via `StatusFields`; a
  `StatsReport`-style ` · N turn(s)` roll-up appended to the budget line from the
  `session.complete` count; active-pane lines = committed log tail / question prompts)
  plus a read-only `load(...)` IO seam (decodes the log **in place** like
  `StatsReport.readActions`, reads the cache directly, never `replay`/`RebuildState`,
  never the §13 lock). Task 2.1.3 polls `load`.
- `TuiSnapshot.scala` — `ActivePane.forState(FsmState)` implements the design's
  state→pane map. Spec/design *driver* phases (`InteractiveSpec` / `DesignReviewing` /
  `DesignPrFeedback`) fall to `LogTail` (no live tap until Task 2.1.5); the `Streaming`
  cases stand in with the committed log tail meanwhile.
- `build.sbt` — `catsEffect` added to `forge-tui` (the `load` seam returns `IO`).
- `TuiSnapshotBuilderSuite` — 14 tests (pure fold field mapping, the turn roll-up,
  the pane map, log-tail cap, Q&A display, and the read-only `load` incl. a
  log-left-unchanged assertion). Green: `forge-tui` 23 tests; full unit suite + the
  `StatusReport` goldens pass; scalafmt clean; `forge-it` compiles.

### Task 2.1.3 — `forge tui <feature>` command  ✅ 2026-06-01

Wire a read-only `forge tui <feature>` into `io.forge.app.command` (alongside
`status` / `tail` / `stats`): resolve paths + config, build an initial snapshot
(Task 2.1.2), and `ForgeTui.run` it with a `Sub.Every` refresh that re-folds the
log. **Read-only per §15** — never acquires `ProcessLock`, never writes; mirror
`StatusReport`'s "reads cache directly, never `RebuildState`" stance so it is safe
to run against an in-flight `forge run`. Register the command in `CommandRouter` +
`ForgeCommand`.

Landed:

- `ForgeTui.scala` — the app's snapshot is now **refreshed by polling**. A new
  `Msg.Refreshed(snapshot)` replaces the rendered snapshot (leaving `scrollBack`
  untouched, so a follow-tail viewport keeps tracking the newest lines while a
  parked one stays anchored); the 1s `Sub.Every` `Msg.Tick` now fires a
  `Cmd.FCmd` over a `reload: () => Future[Option[TuiSnapshot]]` thunk (the
  idiomatic termflow effect — `update` stays pure, the async re-fold lands as a
  follow-up `Msg`). A `None` reload is a no-op (keeps the last good frame). New
  `run(initial, reload)` entry alongside the static `run(snapshot)`; the default
  `App` reload is a no-op so existing static callers/tests are unaffected.
- `TuiCommand.scala` (new) — `forge tui <feature>`: `requireFeature`, then build
  the initial snapshot and a `reload` thunk both from `TuiSnapshotBuilder.load`
  (the §15 read-only fold — cache-direct, log-in-place, no lock), and run the app
  on the blocking pool. Unknown feature → exit 1 (mirrors `StatusReport`'s
  not-found); missing feature arg → exit 64. The per-tick `IO → Future` bridge
  uses the global CE runtime.
- Wiring: `ReadOnlyKind.Tui` (`forge-git`), `Cli` phase1/phase2 + the
  `NoCommand` help line, `CommandRouter.readOnly`, the `tui` handler object, and
  the `Main` command-surface docstring.
- Tests: 3 new `ForgeTuiAppSuite` cases (Tick re-folds; `None` keeps the
  snapshot; scroll preserved across a refresh) → `forge-tui` 33; 2 new
  `ReadOnlyHandlerSuite` cases (unknown-feature exit 1, no-arg exit 64) +
  `CliParserSuite` extended to cover `tui`. Full unit suite + `StatusReport`
  goldens green; scalafmt clean; `forge-it` compiles.

This **closes the Tier-1 gate** (a runnable read-only status+active dashboard
wired to `forge tui`); with Task 2.1.4 (log-tail pane) already landed, the
slice's exit criterion is met pending the section-wide code review (roadmap §3.2
stays unticked until then).

### Tier 2 — make it answerable

### Task 2.1.4 — scrollable action-log pane  ✅ 2026-06-01

A log-tail active-pane view over `FileActionLog` (the `forge tail` data) with
`ArrowUp`/`ArrowDown`/`PageUp`/`PageDown` scroll and follow-tail. This is the pane
that makes the dashboard a usable substitute for `forge tail`; **required for the
exit gate.**

Landed:

- `ForgeTui.scala` — `Model` gains a `scrollBack` field: the number of lines the
  *bottom* of the active-pane viewport sits above the newest line. `0` =
  **follow-tail** (newest lines shown); positive parks the viewport that many lines
  back. Measuring from the tail (not the top) makes follow-tail fall out for free —
  a poll refresh (Task 2.1.3) that appends lines keeps a `scrollBack == 0` viewport
  on the newest data. `ArrowUp`/`ArrowDown` move one line, `PageUp`/`PageDown` a full
  `ActiveRows` viewport; `scrollDelta` + `clampScrollBack` keep it in `[0,
  maxScrollBack]` (clamped again at render, so a snapshot that shrinks can't strand
  the viewport). New `activeView` projects lines+scroll onto the viewport (replacing
  the old `take`-from-top); the active-pane header carries a compact `[↑a ↓b]`
  hidden-above/below indicator (`↓0` = following), and the footer advertises the
  scroll keys. Non-scroll keys keep their existing last-key-recorded behaviour.
- `ForgeTuiAppSuite` — 7 new tests (now 16) through `TuiTestDriver`: default
  follow-tail window, single-line `ArrowUp`, `ArrowDown` floor, `PageUp` page +
  top clamp, `PageDown`-back-to-tail, no-indicator-when-it-fits, and scroll keys
  recorded non-fatally. Green: `forge-tui` 30 tests; full unit suite + `StatusReport`
  goldens pass; scalafmt clean.

### Task 2.1.5 — live `AgentEvent` tap (deferred liveness) — Tier 3, carried forward

Token-by-token streaming in the active pane. Needs an **in-process event-tap seam**
on the orchestrator: today streamed `AgentEvent`s are consumed by `SessionMonitor`
and discarded (no observer seam). Add an opt-in tap that publishes events to a
`termflow` `Sub`, so `forge run --tui` (or an attach handshake) can host the
orchestrator and stream live. Revisits the §3.1 in-process Sub/Cmd model; gated on
real use justifying the coupling (§4 T3). Out-of-scope of the Tier-1 gate.
**Carried forward:** make this the first task in the next TUI/liveness slice if
per-settle log refresh proves too coarse.

### Task 2.1.6 — Q&A pane (display) ✅ 2026-06-01

Render `DesignNeedsHumanInput.questions` / pending `AskUserQuestion` content in the
active pane when `ActivePane.Question`. v1 **displays** the question + points the
operator at the existing answer path (`forge spec` REPL / PR); *answering from the
TUI* is a later slice (it would need the write path + lock, breaking §15 read-only).

Landed:

- `TuiSnapshotBuilder` renders design-review questions with severity, option list,
  free-text/default hints, and an explicit display-only answer pointer.
- `NeedsHumanIntervention` renders the reason plus the existing `forge resume`
  path; if the committed log contains a latest unanswered `.ask_user_question`
  payload, the pane surfaces that driver question too.
- `TuiSnapshotBuilderSuite` covers both FSM-backed design questions and the
  best-effort pending `AskUserQuestion` audit projection.

### Tier 3 — polish

### Task 2.1.7 — theme / resize / key-help ✅ 2026-06-01

Adopt a `termflow.tui.Theme`, switch the view to `Layout` for reflow-on-resize
(today's frame is a fixed 80×20), and add a key-binding help overlay. "Subjective;
iterate based on what feels wrong during real use" (roadmap §3.2).

Landed:

- `ForgeTui.Model` now tracks terminal width/height and subscribes to
  `Sub.TerminalResize`; the frame reflows from runtime dimensions instead of
  assuming a fixed 80×20 surface.
- The two-pane body is resolved through `termflow.tui.Layout`, uses a small
  Forge-specific `Theme`, and keeps page scrolling tied to the current active-pane
  viewport height.
- `?` opens a modal key-help overlay; `Esc` closes it. The footer advertises the
  help key alongside quit and scroll bindings.
- `ForgeTuiAppSuite` covers resize, help overlay toggle/close, and the updated
  dynamic viewport behavior.

### Task 2.1.8 — Slice 2.1 close-out

Walk §4 carry-forward into durable homes; **reconcile the stale spec §3.3
dependency note** (termflow `0.0.1`/`0.1.0-SNAPSHOT` → `0.4.0` multi-module + the
Scala-3.7.1 floor) into a `forge-design-1.6.md` revision (per the §23 standalone-
revision rule — don't edit 1.5 in place); confirm the Scala-bump decision recorded
in `design-rationale.md` still matches the spec revision; then tick roadmap §3.2.

## 2. Order of work

2.1.1 (done) → 2.1.2 (builder) → 2.1.3 (`forge tui` command) gives a usable
read-only dashboard = Tier-1 gate. Then 2.1.4 (log-tail, required for exit) →
2.1.6 (Q&A display) → 2.1.7 (polish). 2.1.5 (live tap) rolls forward unless real
use demands token-level liveness. 2.1.8 closes.

## 3. Status log

- **2026-06-01 — Task 2.1.3 review pass (4 TUI findings addressed).**
  (1) `TuiSnapshotBuilder.MaxTailLines` 10 → 500 with a prepended truncation
  marker — the old 10-line cap was applied *before* the scroll viewport saw the
  data, so `PageUp` had no production history to reveal (the scroll tests only
  passed because they hand-built 20-line snapshots). (2) `ForgeTui` now resets
  `scrollBack` to 0 on a pane change in `Msg.Refreshed` (it stays preserved for
  append-only refreshes within a pane), so a viewport parked deep in an old log
  isn't stranded when the feature moves to a different pane. (3) Removed the dead
  `.ask_user_question` action-log scan from the Question pane — the orchestrator
  commits no such record, so it only ever matched synthetic test data; the pane
  now surfaces only the cached-state questions/reason (see §4 T4 for the durable-
  event / live-tap follow-up). (4) `init` no longer double-registers the
  `Sub.Every`/`Sub.InputKey`/`Sub.TerminalResize` subscriptions (each auto-
  registers with the `RuntimeCtx`; the outer `ctx.registerSub` wrap was a second
  registration). Tests updated (cap+marker, pane-change reset, log-only-inert
  Question pane); `forge-tui` 38, full unit suite + goldens green, `forge-it`
  compiles, scalafmt clean.
- **2026-06-01 — Tasks 2.1.6 and 2.1.7 landed (Q&A display + resize/key-help
  polish).** `TuiSnapshotBuilder` now renders human-blocking questions with
  severity/options/free-text hints, includes the display-only answer path, and
  best-effort surfaces the latest unanswered `.ask_user_question` audit payload
  when the committed log has one *(superseded — that scan was removed in the
  2026-06-01 review pass above; it never matched real data, see §4 T4)*.
  `ForgeTui` now tracks terminal dimensions via
  `Sub.TerminalResize`, resolves the pane body through `Layout`, applies a
  Forge-specific termflow `Theme`, keeps page scrolling tied to the current
  viewport height, and adds a `?` modal help overlay (`Esc` closes). Coverage:
  `forge-tui` 36 tests green.
- **2026-06-01 — Task 2.1.3 landed (`forge tui <feature>` command) — Tier-1 gate
  closed.** Added the read-only `forge tui` command: a new `TuiCommand` builds an
  initial `TuiSnapshot` and a `reload` thunk (both from the §15 read-only
  `TuiSnapshotBuilder.load`) and runs `ForgeTui` on the blocking pool. `ForgeTui`
  gained polling: `Msg.Tick` now fires a `Cmd.FCmd` over the `reload` thunk and
  the re-folded snapshot lands as a new `Msg.Refreshed` (keeping `update` pure and
  `scrollBack` anchored to the tail). Wired `ReadOnlyKind.Tui` through
  `Cli`/`CommandRouter`/`Handlers`; `Main` routes it generically (no per-kind
  branch). 5 new tests (3 `forge-tui` refresh, 2 `ReadOnlyHandler` exit-code) +
  `CliParserSuite` extended; `forge-tui` 33, full unit suite green, scalafmt
  clean, `forge-it` compiles. With 2.1.4 already in, the slice exit criterion is
  met; roadmap §3.2 stays unticked pending the section-wide review.
- **2026-06-01 — Task 2.1.4 landed (scrollable log pane).** Added a `scrollBack`
  field to `ForgeTui.Model` (lines-above-the-tail; `0` = follow-tail) plus
  `ArrowUp`/`ArrowDown`/`PageUp`/`PageDown` handling, a clamped-at-render viewport
  projection (`activeView`), a `[↑a ↓b]` header indicator, and a scroll-keys footer
  hint. Measuring scroll from the tail makes follow-tail automatic across a future
  poll refresh. 7 new `TuiTestDriver` tests (`forge-tui` now 30); full unit suite +
  goldens green; scalafmt clean. Note this lands **ahead of Task 2.1.3** (the `forge
  tui` command wiring) — the scroll behaviour is internal to the app and headlessly
  testable, so it does not depend on the CLI entry point; 2.1.3 still gates the
  Tier-1 exit.
- **2026-06-01 — Task 2.1.2 landed (snapshot builder).** Lifted `StatusReport`'s pure
  render helpers into `forge-core`'s new `StatusFields` (rather than duplicate them in
  `forge-tui`), so `forge status` and `forge tui` share one definition; `StatusReport`
  now delegates and its byte-for-byte golden suite still passes. Added
  `TuiSnapshotBuilder` (pure `build` fold + read-only `load` IO seam, mirroring
  `StatusReport`'s §15 in-place decode / cache-direct / no-`replay` stance) and
  `ActivePane.forState`. Budget line carries a `StatsReport`-style turn roll-up. Added
  `catsEffect` to `forge-tui`. 14 new tests (`forge-tui` now 23); full unit suite +
  goldens green; scalafmt clean; `forge-it` compiles.
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
2.1.1. **Durable record:** `design-rationale.md` BT1 records the bump as an
external-constraint decision, not a preference. **Watch item:** Maven Central
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

### T4 — Question pane can't show *live driver* AskUserQuestion prompts (no durable record)

Surfaced by a Task-2.1.3 review (2026-06-01). The Q&A pane (Task 2.1.6) originally
scanned the action log for `.ask_user_question` records with an absent/blank
`answer` field, intending to display a "pending driver question." But the
orchestrator never commits such a record: the only §19 kinds written are
`session.complete` / `cost.update` / `harness.*` / `audit.*` / `ci.skipped` /
`user.command` / the monitor-outcome kind (verified by sweeping every `kind = "…"`
literal in `modules/**/src/main`). A driver `AskUserQuestion` is answered in-band
via the `forge spec` REPL / resume path and leaves no durable "unanswered" row, so
that scan was **dead code** against real data (it only matched synthetic test
payloads). Removed in this review; the Question pane now surfaces only what the §15
read-only projection can observe in the cached FSM state — `DesignNeedsHumanInput`'s
`questions` and `NeedsHumanIntervention`'s `reason`.

To show live driver questions later, pick one (deferred — both are out of a
read-only viewer's scope):
- a durable **"question opened" audit event** the orchestrator commits when a
  driver halts on `AskUserQuestion` (a small §19 `kind` addition), which the TUI
  could then poll read-only; or
- the **Task 2.1.5 live `AgentEvent` tap**, which carries the question in-process.

Close at Task 2.1.8 (route to the 1.6 revision or a tracking issue).

## 5. Cross-references

- [`roadmap.md`](roadmap.md) §3.2 — the terse slice direction this expands.
- [`forge-design-1.5.md`](forge-design-1.5.md) §3.1 (panes), §3.2 (`forge-tui`
  module), §3.3 (deps — stale, see T2), §15 (read-only commands).
- `StatusReport.renderFeature` / `StatsReport` (`modules/forge-app/.../command/`)
  — the field-for-field reference for the Task 2.1.2 snapshot builder.
- [`design-2.0.md`](design-2.0.md) — the prior slice that made the action log a
  replayable, self-describing source (what the TUI consumes).
- [`design-rationale.md`](design-rationale.md) — Scala-3.7.1-bump decision (T1).
