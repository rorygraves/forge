# design-2.0 — Slice 2.0 implementation plan (Run observability)

> **Maps to:** [`roadmap.md`](roadmap.md) §3.1 (Phase 2 / Slice 2.0 —
> Run observability, "instrument before optimise") and the Phase-1
> MVP-gate findings in [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. The roadmap stays terse — direction and exit criteria — and
> this file owns the per-task breakdown. Items get ticked off here as they
> land; the roadmap section (§3.1) gets ticked off only after a code review
> on the section as a whole.
>
> **Status:** 🟡 open — 2026-05-31. First slice of Phase 2 (MLP). This is
> the *instrumentation* slice: the MVP-gate run (Task 1.4.16) proved Forge
> works but proved it cannot **measure** itself. Phase 2's exit criterion
> ("you'd choose Forge over running Claude directly") is unmeasurable until
> a completed run's cost / latency / turn-count are reconstructable from the
> committed log alone. The prompt-iteration (§3.3) and TUI (§3.2) work
> *consume* this data, so observability lands first.

## 0. Exit criterion for Slice 2.0

Slice 2.0 is done when **a completed run's cost, latency, and turn-count
are reconstructable from the committed action log alone, broken down per
phase, via `forge stats <feature>`** (roadmap §3.1 "Exit").

Concretely:

1. Every driver turn writes a §19 `cost.update` action carrying the
   running `{ featureTotalUsd, pieceTotalUsd, turnTotalUsd }` (plus the
   per-turn `{ provider, model, inputTokens, outputTokens, usd }`), so a
   fresh `RebuildState.run` projects non-zero `CostTotals` onto `Feature`.
   The szork MVP run's log had **zero** cost entries; the bar is that an
   equivalent run is fully cost-replayable.
2. Every driver session writes a `session.complete` audit action carrying
   `{ phase, piece, durationMs, model, turnCostUsd, success }`, closing the
   per-phase timing + attribution gap.
3. `forge stats <feature>` folds the log into a per-phase cost / wall-clock
   / turn-count breakdown — a direct answer to "did this run efficiently".
4. Working-time is distinguishable from wait-time: the log carries a marker
   when the loop blocks on a human (`DesignAwaitingMerge`,
   `*NeedsHumanInput`, `PieceAwaitingMerge`) so a 35-minute "waiting for the
   operator to merge" no longer reads as Forge being slow.
5. Driver-session transcripts are capturable after the fact via an opt-in
   per-session NDJSON sink (generalising the reviewer-only
   `FORGE_REVIEWER_RAW_DUMP_DIR`).
6. A resume-from-NHI path exists that does **not** rewrite the log
   (preserving the timing record) — or the cost of not having it is
   measured and documented as a watch item.

Tiering follows roadmap §3.1: **Tier 1** (Tasks 2.0.1–2.0.2) closes the
capture gap and is the gating deliverable; **Tier 2** (Tasks 2.0.3–2.0.4)
makes the data answerable; **Tier 3** (Tasks 2.0.5–2.0.6) is debuggability
and the dev loop. The slice exit gate requires Tier 1 + Tier 2; Tier 3
items may individually roll forward if real use doesn't justify them, but
each such deferral is recorded in §4 at close-out (Task 2.0.7).

## 1. Task breakdown

### Tier 1 — close the capture gap (the machinery already exists)

### Task 2.0.1 — Wire the `cost.update` writer  ⬅ **first runnable slice**

**The riskiest contract, exercised first** (CLAUDE.md "run code earlier").
The read side is already built and dead: `Replay.applyCostUpdate`
([`Replay.scala:333`](../modules/forge-core/src/main/scala/io/forge/core/log/Replay.scala))
projects `cost.update` → `CostTotals` on `Feature`, but **no app-layer code
ever writes the action**. The token/cost data flows
`ClaudeEventParser`/`CodexEventParser` → `AgentEvent.CostUpdate(Cost)` →
`RealSessionMonitor.handleEvent` (`updateAndGet` on the orchestrator's
`runningTotals` ref) → checked against caps → **discarded**.

Deliverables:

- The orchestrator drafts a `cost.update` action from the accumulated
  `CostTotals` after a driver session settles. The natural seam is
  `Orchestrator.handleWinner`'s `RaceResult.FromMonitor` arm
  ([`Orchestrator.scala:519`](../modules/forge-app/src/main/scala/io/forge/app/orchestrator/Orchestrator.scala)),
  which already reads the outcome and clears `driverRef`; the
  `totalsRef.get` is in scope there. The draft is appended **before** (or
  as part of) the settle transition's persist, so a crash after the
  transition still has the cost on record.
- **Payload shape (decision D1).** `Replay.applyCostUpdate` requires
  `{ featureTotalUsd, pieceTotalUsd, turnTotalUsd }` (the three totals) and
  ignores the rest. §19 documents the fuller
  `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd,
  pieceTotalUsd, turnTotalUsd }`. The three totals come straight off
  `CostTotals`; `provider/model/tokens/usd` describe the *turn delta* and
  are consumed-and-dropped by the monitor today. Resolve at implementation:
  either (a) write totals-only now and backfill the per-turn fields when
  the monitor surfaces them, or (b) have the monitor accumulate and return
  the turn's aggregate `Cost` (provider/model/summed tokens/summed usd)
  alongside the outcome so the full §19 payload is written in one pass.
  Option (b) is preferred — it makes `session.complete` (Task 2.0.2) free —
  but is gated on a `SessionMonitor` signature change; (a) is the safe
  fallback if that ripples too far.
- **Turn/piece boundary reset (decision D2 — latent correctness bug
  surfaced while scoping).** `Orchestrator.drive` creates **one**
  `totalsRef = CostTotals.zero` and threads it through the entire feature
  loop ([`Orchestrator.scala:170`](../modules/forge-app/src/main/scala/io/forge/app/orchestrator/Orchestrator.scala));
  the monitor only ever **adds** (`updateAndGet`, never resets). The
  `SessionMonitor` docstring explicitly assigns the per-turn-boundary reset
  of `CostTotals.turn` to the orchestrator ("the FSM state machine sees a
  coherent `turnTotalUsd` at every transition") — but the orchestrator
  never does it. So `turn` and `piece` accumulate across turns/pieces,
  which means **the per-turn cost cap is actually checking cumulative spend,
  not per-turn spend** (a tighter, wrong, check) and the `cost.update`
  scope totals would be wrong if written naively. Task 2.0.1 must reset
  `turn` on each new turn boundary (driver spawn / resume) and `piece` when
  advancing past a piece, per the `CostTotals` docstring (§6 / §12). This
  is in-scope because writing correct cost data is the whole point of the
  task, and the cap bug is adjacent. (Cross-check against §12 check 3 and
  the `CostTotals` field doc on
  [`Cost.scala`](../modules/forge-core/src/main/scala/io/forge/core/cost/Cost.scala).)
- **Tests.** An orchestrator e2e suite (mirror `OrchestratorAtomicMergeSuite`
  lifecycle shape) driving a scripted driver that emits `CostUpdate`s then
  `Result`, asserting (i) a `cost.update` action lands in the log with the
  right scope totals, (ii) a fresh `RebuildState.run` projects the expected
  `CostTotals` onto the rebuilt `Feature`, (iii) `turn` resets across two
  turns and `piece` resets across two pieces. Property-style coverage of
  the reset semantics if it fits the existing `forge-core` replay suite.

This is the single highest-value fix — without it the cost subsystem is
unfed dead infrastructure.

### Task 2.0.2 — `session.complete` audit event

A `session.complete` audit action carrying `{ phase, piece, durationMs,
model, turnCostUsd, success }`, built from the
`AgentEvent.Result(success, durationMs)` the parser already produces
([`AgentEvent.scala:36`](../modules/forge-agents/src/main/scala/io/forge/agents/AgentEvent.scala)).

- **Plumbing gap.** `MonitorOutcome.Settled(phase, outcome)` **drops
  `durationMs`** — `RealSessionMonitor.handleEvent` matches
  `AgentEvent.Result(success, _)` and discards the duration. To emit a
  truthful `durationMs`, either thread it onto `MonitorOutcome.Settled` (so
  the parser's value flows through) or have the orchestrator wall-clock the
  session itself (`IO.monotonic` around the monitor call). The CLI's own
  `duration_ms` is the more faithful number (it excludes orchestrator
  scheduling), so threading it through `MonitorOutcome` is preferred and
  composes with Task 2.0.1 D1 option (b).
- `turnCostUsd` / `model` reuse whatever Task 2.0.1 D1 settles on for the
  per-turn cost payload — do these two tasks together if (b) is chosen.
- Optional: model `num_turns` (currently not captured at all) if the parser
  exposes it cheaply; otherwise note as a follow-up.
- **Tests.** Assert a `session.complete` lands per settled driver session
  with the expected fields; assert it survives a log round-trip (it's an
  audit-only event — `Replay` should ignore it without erroring, same as
  other `audit.*` / `harness.*` kinds it already tolerates). Confirm the
  `Replay` unknown-kind handling does not reject it.

### Tier 2 — make the data answerable

### Task 2.0.3 — `forge stats <feature>`

A read-only CLI command that folds `.forge/log/<feature>.jsonl` into a
per-phase cost / wall-clock / turn-count breakdown.

- New `ForgeCommand.ReadOnlyKind` entry alongside `forge status` / `forge
  tail` (Task 1.4.9 I3 added that extension point); handler in
  `io.forge.app.command` mirroring `StatusReport.scala`.
- Folds `cost.update` (Task 2.0.1) + `session.complete` (Task 2.0.2) +
  the work/wait markers (Task 2.0.4) into a table: per phase (`spec`,
  `design`, `implement`, `fixup`, `review`, `refine`) → turns, wall-clock,
  USD; plus a feature total and a working-vs-waiting split.
- Pure fold over the log (no live state); reuses the `Replay` /
  `Feature.foldEvents` machinery where it fits, or a dedicated stats fold
  if the projection shape differs enough.
- **Tests.** Golden-log fixtures → expected rendered table; an empty/
  partial log degrades gracefully (no cost entries → "cost: unavailable",
  not a crash).

### Task 2.0.4 — Separate working-time from wait-time

Stamp a marker when the loop blocks on a human so wall-clock attributable
to Forge is distinguishable from wall-clock spent waiting on the operator.

- The human-blocking states are `DesignAwaitingMerge`,
  `PieceAwaitingMerge`, and the `*NeedsHumanInput` / NHI family. Emit a
  paired marker (enter-wait / leave-wait) — either dedicated `audit.*`
  actions or a flag on the existing transition payloads — so the
  wait-interval is bracketed in the timeline.
- Consumed by `forge stats` (Task 2.0.3) to subtract wait-time from the
  per-phase wall-clock. The motivating case: the szork run's ~35-min
  "waiting for the operator to merge" must not read as Forge being slow.
- **Tests.** A log with a bracketed wait interval folds to the right
  working-vs-waiting split; unbracketed (crash mid-wait) degrades to
  "wait end unknown" rather than mis-attributing.

### Tier 3 — debuggability & the dev loop itself

### Task 2.0.5 — Generalise the reviewer raw-dump to driver sessions

An opt-in per-session NDJSON sink for driver sessions, generalising the
reviewer-only `FORGE_REVIEWER_RAW_DUMP_DIR`
([`ClaudeConnector.scala:419`](../modules/forge-agents/src/main/scala/io/forge/agents/ClaudeConnector.scala)).
Makes "what did the implement driver actually do for \$9.56" answerable
after the fact, not only live (the gap #10 datum was read off the live CLI
and is now unrecoverable).

- Same env-var-gated, default-off shape as the reviewer dump (per the
  "default-on runtime <60s" discipline — this is an opt-in debug sink, no
  steady-state cost). One file per session keyed by session id / phase.
- **Tests.** With the env var set, a driver session writes its raw envelope
  stream; unset, nothing is written and there is no overhead.

### Task 2.0.6 — Clean resume-from-NHI that preserves history

The truncate-and-replay recovery used ~13× during the MVP run corrupts the
timing record (seq 0/1 share an identical timestamp because replay rewrites
early transitions in a batch) and re-pays full driver exploration on each
relaunch (gap #10's compounding cost). A resume that doesn't rewrite the
log fixes both.

- Scope this against the live recovery path
  (`Orchestrator.resumeIfRunRecoverable` + `RestartRecovery`) and the
  manual rewind documented in `mvp-friction.md` §"Operational notes". The
  goal: a `forge resume` that appends a resume marker rather than
  truncating, so the committed timeline is monotonic and the driver isn't
  re-spawned from scratch when its prior work is already on the branch.
- **Decision D3.** This is the largest Tier-3 item and overlaps the
  Task 2.0.1 turn-reset semantics and gap #7 (designSessionId durability,
  §4). If it grows past a contained change, split it / roll the remainder
  forward and record the split at close-out (Task 2.0.7) — do not silently
  expand (CLAUDE.md "ask before scope-expanding").
- **Tests.** A resume after NHI leaves the pre-NHI log intact (no truncated
  seqs), appends a resume marker, and `forge stats` still reads a coherent
  timeline across the resume boundary.

### Task 2.0.7 — Slice 2.0 close-out

- Walk the §4 carry-forward list; place each deferred item somewhere
  durable (a Phase-2 later-slice bucket, a tracking note, or a
  design-rationale deferred decision) per the CLAUDE.md "section closures
  must explicitly carry deferrals forward" gate.
- Reconcile any §19 payload-shape decisions (D1) into the spec — if the
  §19 `cost.update` / a new `session.complete` schema needs documenting,
  open it in the next-revision spec file (`forge-design-1.4.md`) per the "spec
  edits go to the next revision" rule (do **not** edit the live spec
  `forge-design-1.3.md` in place).
- Full unit suite green; `forge-it` compiles. Tick roadmap §3.1 only after
  a section-level code review.
- **Exit re-validation:** ideally re-run the MVP-gate feature (or an
  equivalent contained feature) end-to-end and confirm its cost / latency /
  turn-count are reconstructable from the committed log via `forge stats`
  alone — the real proof that the capture gap is closed.

## 2. Order of work

Tier 1 first and gating: **Task 2.0.1 → Task 2.0.2** (do together if
Task 2.0.1 D1 chooses option (b), since `session.complete` reuses the
per-turn cost payload). Then Tier 2: **Task 2.0.3** depends on 2.0.1/2.0.2
data; **Task 2.0.4** can land in parallel with 2.0.3 but 2.0.3's
working-vs-waiting column depends on it. Tier 3: **Task 2.0.5** is
independent and small; **Task 2.0.6** is the largest and is sequenced last
(it touches the resume/recovery path and overlaps the turn-reset work).
**Task 2.0.7** closes.

The first runnable slice is Task 2.0.1 — it puts executing code in front of
the riskiest contract (the cost-capture seam + the latent turn-reset bug)
rather than refining prose, per the CLAUDE.md "run code earlier" rule.

## 3. Status log

- 2026-05-31 — Slice 2.0 opened. Plan drafted from roadmap §3.1 +
  `slice-4/mvp-friction.md`, grounded against the real cost/replay/monitor
  code (`Replay.applyCostUpdate`, `CostTotals`, `AgentEvent.{CostUpdate,
  Result}`, `Orchestrator.{drive,loop,handleWinner}`,
  `RealSessionMonitor`, `MonitorOutcome`). Two design decisions surfaced
  during scoping and recorded on Task 2.0.1: **D1** (cost.update payload
  shape — totals-only vs full §19) and **D2** (latent bug: `CostTotals.turn`
  / `.piece` are never reset by the orchestrator despite the
  `SessionMonitor` contract assigning it the reset, so the per-turn cost
  cap currently checks cumulative spend).
- 2026-05-31 — **D2 fixed standalone** (ahead of the rest of Task 2.0.1).
  `Orchestrator.store` now resets `CostTotals.turn` to zero at every
  driver-turn boundary (spawn/resume), and the `PieceImplementing` entry
  hook resets `CostTotals.piece` on a new piece's first turn (a same-piece
  fix-up keeps the per-piece total). This corrects the §12 per-turn cap so
  it checks per-turn rather than cumulative spend. New
  `OrchestratorCostScopeResetSuite` (2 tests): a two-piece run proves both
  scopes reset on each new piece (and no false `TurnBudgetBreached`), and an
  implement→CI-fail→fix-up run proves the same-piece fix-up carries `piece`
  while resetting `turn`. `forge-app` 331 tests green. The `cost.update`
  writer half of Task 2.0.1 (D1) is still pending.
- 2026-05-31 — **Task 2.0.1 `cost.update` writer + Task 2.0.2
  `session.complete` landed together** (D1 resolved **option (b)** — full §19
  payload now). `SessionMonitor.monitor` now returns a `MonitorReport`
  (settle `MonitorOutcome` + this turn's aggregate `Cost` + the CLI
  `durationMs`), accumulated **locally** per `monitor` call so it carries no
  cross-turn-reset hazard (contrast the shared `CostTotals` ref whose missing
  reset *was* D2). `Orchestrator.handleWinner`'s `FromMonitor` arm drafts the
  §19 `cost.update` (full `{ provider, model, inputTokens, outputTokens, usd,
  featureTotalUsd, pieceTotalUsd, turnTotalUsd }`, totals off the running
  `CostTotals`) and a new `session.complete` audit action (`{ phase, piece,
  durationMs, model, turnCostUsd, success }`); both are appended in the **same
  atomic batch** as the settle transition (`applyAndPersist` `extraDrafts`), so
  a crash after the transition still has the cost on record.
  `cost.update` is emitted only when the turn actually spent (`turnCost` is
  `Some`); `session.complete` is emitted for every settle. New
  `OrchestratorCostUpdateWriterSuite` (2 tests, full lifecycle from `Drafting`):
  the Implement `cost.update` carries the full §19 payload, a `session.complete`
  lands per settled driver session, a fresh `RebuildState.run` projects the
  running totals onto `Feature.cost` (proving the formerly-dead
  `Replay.applyCostUpdate` is now fed and that replay tolerates the new
  `session.complete` kind as a no-op projection), and the two-piece run shows
  `feature` carrying across pieces while `turn`/`piece` restart in the
  *written* record. The 8 direct-call `SessionMonitor*Suite`s adapt with a
  one-line `.map(_.outcome)` (assertions unchanged). `forge-app` 333 tests
  green; `forge-it` compiles. Exit criteria #1 + #2 met. **Tier 1 (Tasks
  2.0.1 + 2.0.2) complete**; next is Tier 2 (`forge stats`, work-vs-wait).
- 2026-05-31 — **Task 2.0.3 `forge stats <feature>` landed.** A new
  `ReadOnlyKind.Stats` (wired through `Cli.phase1`/`phase2`,
  `CommandRouter.readOnly`, and the `stats` handler — the §15 read-only class,
  no lock) backed by `StatsReport`. The command folds the committed log into a
  per-phase **turns / wall-clock / cost** table plus a feature total: the
  per-phase rows come off `session.complete` (Task 2.0.2 — phase tag, CLI
  `durationMs`, `turnCostUsd`), and the feature-total USD prefers the last
  `cost.update`'s running `featureTotalUsd` (the §13 single-writer
  authoritative total), falling back to the summed per-turn cost when no
  `cost.update` is present. `fold` / `render` are pure seams; the handler
  reads the log in place (skipping a malformed tail line) and never rewrites
  it. Graceful degradation per the §1 Task 2.0.3 bar: no
  session/cost entries → "no session data recorded"; null `durationMs`
  (timeout/kill) → counted and footnoted, never crashes; pre-observability
  logs → "cost: unavailable". New `StatsReportSuite` (12 tests: fold
  aggregation, cost.update-vs-summed feature total, missing-duration handling,
  empty-log + unrecognised-phase degradation, render table + footnotes, and
  the handler missing-log / usage / malformed-tail / synthetic-log paths);
  `CliParserSuite` extended to cover the new kind. The **working-vs-waiting
  split (exit #4) is intentionally deferred to Task 2.0.4** — `fold` gains a
  wait column once Task 2.0.4 emits the enter-/leave-wait markers. `forge-app`
  345 tests green; `forge-it` compiles. Exit criterion #3 met (per-phase
  cost/wall-clock/turn-count answerable from the committed log alone).
  **Tier 2 next item: Task 2.0.4 (work-vs-wait markers + the stats wait
  column).**
- 2026-05-31 — **Task 2.0.4 work-vs-wait markers landed; Tier 2 complete.**
  Chose the **"flag on the existing transition payloads"** option (design-2.0
  §1 Task 2.0.4's lighter of the two sanctioned representations) over a
  dedicated `audit.*` action: `Fsm.fsmTransitionDraft` now stamps a `wait`
  marker (`{ edge: "enter"|"leave", kind }`) on the `fsm.transition` payload
  whenever a transition enters/leaves a human-blocking state, classified by a
  single new `Fsm.humanWaitKind` (the §0 #4 set: `DesignNeedsHumanInput` →
  `design-input`, `DesignAwaitingMerge` → `design-merge`, `PieceAwaitingMerge`
  → `piece-merge`, `NeedsHumanIntervention` → `intervention`). **No
  orchestrator or write-path change** — the markers ride the existing
  `fsm.transition` drafts already persisted by `applyAndPersist`, and `Replay`
  ignores the extra payload field (round-trip safe, verified by the full
  forge-core suite). `StatsReport.foldWaits` pairs each `enter` marker with the
  **next** transition (whatever it is) to recover the interval — robust to
  self-poll transitions and a missing `leave` edge; a wait still open at
  end-of-log (process stopped mid-wait) is reported as "end unknown" rather
  than mis-attributed an unbounded duration. `render` adds a distinct **"waiting
  (human)"** breakdown below the totals (per-kind), keeping the per-phase table
  as pure Forge *working* time. **Render choice:** waits are a separate section,
  not a per-phase column, because they fall *between* driver sessions, not
  inside any one phase — so subtracting them from a SessionPhase row would
  mis-attribute (the per-phase rows are already session-duration sums =
  working-only, so there is nothing to subtract). This satisfies exit #4
  ("working-time distinguishable from wait-time") more truthfully than a
  column. New `FsmWaitMarkerSuite` (8 tests: enter+leave markers for all four
  blocking states, plus a working→working transition carrying no marker) pins
  the write side; `StatsReportSuite` +8 (fold bracketing, close-on-next-
  transition without an explicit leave, distinct-kind sum, open-interval
  degradation, pre-marker no-op; render breakdown + omit-when-no-wait +
  open-wait note). `forge-core` 385, `forge-app` 353 (full suite green);
  `forge-it` compiles. **Exit criterion #4 met; Tier 1 + Tier 2 complete —
  the slice exit gate (Tier 1 + Tier 2) is satisfied.** Remaining Tier-3 items
  (Tasks 2.0.5 / 2.0.6) are debuggability and may individually roll forward at
  close-out (Task 2.0.7).
- 2026-05-31 — **Task 2.0.5 driver raw-dump landed.** New `RawDumpSink`
  (`forge-agents`) generalises the reviewer-only `FORGE_REVIEWER_RAW_DUMP_DIR`
  one-shot dump to streaming **driver** sessions via a new, independent
  `FORGE_DRIVER_RAW_DUMP_DIR`: when set, every driver session appends its raw
  stdout NDJSON stream to one `<connector>-<label>-<uuid>.jsonl` file
  (`label` ∈ `spec` / `spec-resume` / `implement` / `fixup`), so "what did the
  implement driver do for \$9.56" (mvp-friction gap #10) is answerable offline.
  Same discipline as the reviewer dump: **default off** (`driver(..)` returns
  `None` when the env var is unset — no overhead, no file, so it is safe to
  leave wired in per the "default-on runtime <60s" rule) and **best-effort**
  (a write failure is swallowed, never failing the session). The tap sits at
  the single raw-line seam in `StreamingDriver.parseStreamPipeline` (covers
  Claude spec/resume/headless + Codex headless) **and** in
  `CodexStreamingSession.runOneTurn`'s per-turn drain (covers Codex
  streaming-spec/resume — all turns of one session share the same once-resolved
  sink, so they append to the same file). The tap runs **before** parsing, so
  even unparseable lines are captured (the offline-triage point). Session id is
  deliberately not in the filename (not known until Init arrives); it is
  recoverable from the file's first line. `RawDumpSink.sinkTo` is split out so
  the file mechanics are unit-testable without mutating the (immutable) process
  env. New `RawDumpSinkSuite` (3: env-unset→None, append-skips-blanks-one-file,
  distinct-file-per-session) + `StreamingDriverSuite` +2 (tap captures every raw
  line incl. parse-error garbage; end-to-end through `sinkTo` writes the
  NDJSON file) + `CodexConnectorSuite` +1 (the Codex facade taps both turns'
  raw lines into one sink). `forge-agents` 203 (full unit suite green across all
  modules); `forge-it` compiles. **Exit criterion #5 met.** Tier-3 remaining:
  Task 2.0.6 (clean resume-from-NHI). `FORGE_DRIVER_RAW_DUMP_DIR` is filed for
  the Task 2.0.7 spec reconciliation alongside the other §19 additions.
- 2026-05-31 — **Task 2.0.6 clean resume-from-NHI landed (contained half;
  driver-respawn-avoidance split forward — D3 resolved).** Scoping surfaced that
  the existing resume machinery is **already append-only**: `Fsm.handleResume`
  emits an `fsm.transition` draft (NHI→target) and never truncates, and
  Task 2.0.4 already brackets the NHI as an `intervention` wait (enter on NHI
  entry, `leave` on the resume transition). So exit #6's "a resume that does not
  rewrite the log + a coherent timeline" was largely already satisfied; the only
  remaining reason the MVP run *truncated* was that the resume **re-spawns the
  driver from scratch** (re-paying ~\$10 of exploration), which is the large
  half. Per **D3** + exit #6's "or document the cost as a watch item" clause, the
  contained half ships now and the driver-respawn-avoidance rolls forward (see
  §4 D3). Delivered: a new explicit **`audit.resume_from_nhi`** marker
  (`{ hint, from, to, reason }`) stamped at the single FSM resume seam
  (`Fsm.handleResume`), so **both** resume paths emit it — `forge resume --<flag>`
  and `forge run`'s startup auto-resume of the run-recoverable hints — making the
  committed timeline self-describing across a resume without any truncation. The
  kind is `audit.resume_from_nhi` (not `audit.resume`) deliberately: a `*.resume`
  suffix collides with `Replay`'s `<actor>.resume` session-resume dispatch; the
  snake-case name mirrors `audit.piece_merged` and is a no-op projection in
  `Replay` (verified). The marker is emitted only on a real state move (a no-op
  resume — e.g. `ApplyPlanningUpdate` whose patch fails to apply, staying in NHI
  — records nothing). `forge stats` folds an `audit.resume_from_nhi` **count**
  (not timed) and renders a "N operator resume(s)… (recovered in place, not
  truncated)" footnote, so a multi-resume run reads as *recovered*, not stuck.
  New `FsmResumeMarkerSuite` (6: marker payload for RunAnotherFixup /
  ResolveLocalImplementationChanges / ReopenDesign / AbortOrAbandon, marker
  precedes the transition, no-op resume emits nothing) +
  `FeatureFoldEventsSuite` +1 (the marker is a no-op projection and the timeline
  folds coherently across the resume boundary) + `StatsReportSuite` +4 (fold
  count, resumeCount 0, render note, omit-when-zero) +
  `OrchestratorUserCommandSuite` +1 (end-to-end: a `forge resume` appends the
  marker, the pre-NHI log line survives byte-for-byte, the log only grows).
  `forge-core` 391, `forge-app` 358 (full unit suite green); `forge-it`
  compiles. **Exit criterion #6 met** (contained half + documented watch item
  for the respawn-avoidance). `audit.resume_from_nhi` is filed for the Task 2.0.7
  §19 spec reconciliation alongside `session.complete` and the `fsm.transition`
  `wait` field. **Tier 3 complete; only Task 2.0.7 (close-out) remains.**
- 2026-05-31 — **Task 2.0.7 close-out — spec reconciliation + carry-forward walk
  landed (review + roadmap tick + live re-validation pending).** (1) **Spec
  reconciled into a new standalone revision.** Created
  [`forge-design-1.4.md`](forge-design-1.4.md) (full copy-forward of 1.3 per the
  §23 standalone-revision rule) folding the Slice-2.0 §19 additions into the
  action-log schema: the new `session.complete` kind (D1 option (b)), the
  optional `wait` field on `fsm.transition` (D4), the new `audit.resume_from_nhi`
  kind (D3 contained half), and the operational `FORGE_DRIVER_RAW_DUMP_DIR`
  debug-sink note (Task 2.0.5). 1.3 became a SUPERSEDED redirect stub; the
  active "live contract" pointers (CLAUDE.md, AGENTS.md, roadmap, design-rationale,
  the 1.1/1.2 stubs, and the closed-trail headers) were repointed to 1.4, while
  historical-fact references ("resolved/filed in 1.3") were left to resolve via
  the stub redirect + the preserved-numbering convention. (2) **Carry-forward
  walked into durable homes** (new roadmap **§3.5** "Deferred to a later Phase-2
  slice" + design-rationale close-out notes on **S4-3**/**S4-5**): the reviewer/
  driver model+cap+retry §18 tuning (S4-3/S4-5, now measurable against Slice-2.0
  data), the **D3 large half** (driver-respawn-avoidance), and **gap #7**
  (`designSessionId` durability) all now have forward-looking homes outside this
  (about-to-freeze) audit trail. (3) **D2 confirmed needs no spec edit:** §12
  (`forge-design-1.4.md:641` / `:1187` / `:1393`) already specifies the per-turn
  cap as a true per-turn check ("checked after every `cost.update`", "enforced
  mid-turn"); the D2 fix brought the code into conformance with the spec, so no
  design-rationale deviation is filed. **Remaining close-out steps:** local
  `/code-review` of the Slice 2.0 diff → fix findings → flip the roadmap §3.1
  checkboxes (the tick is gated on that review per the roadmap convention) → the
  exit-criterion live MVP re-validation via `forge stats`.
- 2026-05-31 — **Live re-validation reproduced gap #7; fixed it (pulled into
  scope).** Driving the exit-criterion re-run (feature `readme-quickstart` on
  `llm4s/szork`): `forge new` ✓, `forge spec` → `/done` ✓ (design PR opened), but
  `forge run` dead-ended at `NeedsHumanIntervention("missing design session id,
  cannot resume")` **before** the implement turn — the only thing that writes
  `cost.update` / `session.complete`. Root cause = **gap #7**, reproduced live:
  the committed log had **no `<actor>.spawn` entry** (only `fsm.transition`), so a
  cold `RebuildState.run` on `forge run` startup rebuilt `designSessionId = None`.
  Investigation found the §19 `<actor>.spawn` / `<actor>.resume` kinds are
  *consumed* by `Replay`/`RebuildState` but were **never produced anywhere** — the
  F1/F5/F6 property suites missed it because they assert on `Fsm.transition`
  reconstruction or the cleared happy-path end-state, not mid-trajectory log
  projection. **Fix (focused, gap #7 only):** the spec `SessionSpawned` seam
  (`Fsm.scala`) now also emits the `<actor>.spawn` durability draft (after the
  transition, so seq-0 stays the `Drafting → InteractiveSpec` record), so
  `designSessionId` projects from the log. `Fsm_11_1_SpecPhaseSuite` updated (the
  spec spawn now emits 2 drafts) + a new `FeatureFoldEventsSuite` producer→consumer
  rebuild regression. The **broader** piece-spawn / resume log-durability stays a
  carry-forward (roadmap §3.5 — pieces re-spawn fresh each turn, so it bites far
  less). `forge-core` 392, `forge-app` 358 green. **Re-validation continues** on a
  fresh run with the fixed code (the existing `readme-quickstart` log predates the
  fix, so it must be re-driven).

## 4. Carry-forward (inherited + new)

### Inherited Phase-1 deferrals that land here

- **S4-3 / S4-5 / S4-6** — rolled to Phase 2 at Phase-1 close ("instrument
  before optimise", roadmap §2.4 / `design-rationale.md`). S4-5 (reviewer/
  driver model + settle-cap + retry tuning) now has lived data from the
  szork run (implement cap too tight for heavy-build self-verification;
  `maxTurnCostUsd = $2.0` vs an actual $9.56 turn). Slice 2.0 produces the
  *measurement* these tuning decisions need; the tuning itself may be a
  later Phase-2 slice. Confirm placement at Task 2.0.7.

### Related Phase-1 gaps (in-scope only if cheap)

- **Gap #7 — `designSessionId` durability.** ✅ **Fixed 2026-05-31** at Task 2.0.7
  close-out — the live re-validation reproduced it (a `forge run` after `forge
  spec` dead-ended at `NHI("missing design session id")` before the implement
  turn). `forge spec` `/done` persisted the design session id only to the
  state-cache, not the action log, so a cold `RebuildState.run` rebuilt it as
  `None`. The fix wires the §19 `<actor>.spawn` durability entry at the spec
  `SessionSpawned` seam (`Fsm.scala`) so the id projects from the log; see the
  status-log entry above + roadmap §3.5. The **broader** session-id log-durability
  for piece spawns + resumes (the §19 `<actor>.spawn`/`.resume` kinds are consumed
  but were never produced anywhere) stays a carry-forward (roadmap §3.5).

### New decisions opened in Slice 2.0 (reconcile at Task 2.0.7)

- **D1 — `cost.update` payload shape.** ✅ Resolved **option (b)** 2026-05-31
  (full §19 payload now). The monitor accumulates the turn's aggregate `Cost`
  and returns it on `MonitorReport`, so the writer emits the complete
  `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd,
  pieceTotalUsd, turnTotalUsd }` in one pass and Task 2.0.2's `session.complete`
  reuses the same payload. `cost.update`'s §19 field set is unchanged (the spec
  already documents it); the **new** kind is `session.complete`
  (`{ phase, piece, durationMs, model, turnCostUsd, success }`), which §19 does
  not yet list — file it in the next-revision spec (`forge-design-1.4.md`) at
  Task 2.0.7 (do not edit the live spec `forge-design-1.3.md` in place).
- **D2 — turn/piece cost-total reset ownership.** ✅ Fixed standalone
  2026-05-31 (`Orchestrator.store` resets `turn`; `PieceImplementing` entry
  resets `piece` on a new piece), with `OrchestratorCostScopeResetSuite`.
  This restores the §12 per-turn cap to a true per-turn check. At
  Task 2.0.7, confirm whether the spec describes the cap semantics
  differently and, if so, file a design-rationale note rather than editing
  the spec in place.
- **D3 — resume-from-NHI scope.** ✅ Resolved **split** 2026-05-31. Task 2.0.6
  shipped the contained half — the explicit `audit.resume_from_nhi` marker at the
  FSM resume seam + `forge stats` count/footnote — which, on top of the
  already-append-only resume machinery and Task 2.0.4's NHI wait-bracketing,
  satisfies exit #6. The **large half rolls forward**: a resume that detects
  already-committed driver work on the piece branch and **skips re-spawning the
  implement/fix-up driver** (the ~\$10 re-exploration cost that *was* the real
  reason the MVP run truncated rather than resumed). It is deferred because it
  touches git branch inspection + driver `--resume` semantics and would have to
  revisit `RestartRecovery`'s deliberate "no transparent resume" stance
  (headless worktrees may carry partial uncommitted changes) — too large for a
  Tier-3 debuggability item, and exit #6 explicitly sanctions documenting the
  cost as a watch item instead. **Watch item:** until the respawn-avoidance
  lands, each resume from an implement/fix-up NHI re-pays the driver's full
  exploration; the per-turn cost cap (S4-5 tuning) bounds the blast radius.
  Place this in a later Phase-2 slice (alongside the S4-5 tuning that has the
  same lived-data driver) at Task 2.0.7. **Gap #7 (`designSessionId`
  durability)** was *not* pulled in — Task 2.0.6 added a new audit kind but did
  not touch the spec-`/done` session-id persistence surface, so per §4's
  "in-scope only if cheap" gate it stays a standalone §11.3 durability fix;
  confirm its disposition at Task 2.0.7.
- **D4 — human-wait marker representation.** ✅ Resolved 2026-05-31:
  **flag on the existing `fsm.transition` payload** (not a dedicated `audit.*`
  action). Entering/leaving a human-blocking state stamps
  `wait: { edge: "enter"|"leave", kind }` on the transition; `kind` ∈
  `{ design-input, design-merge, piece-merge, intervention }` (the
  `Fsm.humanWaitKind` set). §19 does not yet document this optional
  `fsm.transition` field — file it in the next-revision spec
  (`forge-design-1.4.md`) at Task 2.0.7 (do **not** edit the live spec
  `forge-design-1.3.md` in place), alongside the `session.complete` kind from
  D1. Note the deliberate exclusions (`PieceAwaitingCi` / `PieceAwaitingReview`
  = automated, not a human block; `PlanningUpdate` = a human approval but
  outside the §0 #4 set) so a later slice can decide whether to widen the set.

## 5. Cross-references

- Roadmap: [`roadmap.md`](roadmap.md) §3.1 (Slice 2.0 tiered task list).
- Source findings: [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md);
  run evidence under
  [`slice-4/mvp-run/image-creds-dedup/`](slice-4/mvp-run/image-creds-dedup/).
- Spec: [`forge-design-1.4.md`](forge-design-1.4.md) (the live contract; this
  slice's §19 additions — `session.complete`, the `fsm.transition` `wait` field,
  `audit.resume_from_nhi` — were reconciled into it at Task 2.0.7 close-out) §12
  (budget caps), §19 (action-log kinds incl. `cost.update`), §6 (`Feature`
  projections); §1 carries the Slice 1–3 spec reconciliations.
- Code anchors: `Replay.applyCostUpdate`
  (`forge-core/.../log/Replay.scala:333`), `CostTotals`
  (`forge-core/.../cost/Cost.scala`), `AgentEvent`
  (`forge-agents/.../AgentEvent.scala`), `Orchestrator`
  (`forge-app/.../orchestrator/Orchestrator.scala`), `RealSessionMonitor` /
  `MonitorOutcome` (`forge-app/.../monitor/`).
- Prior audit trail: [`design-1.4.md`](design-1.4.md) (Slice 1.4, closed).
