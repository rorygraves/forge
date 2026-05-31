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
  open it in the next-revision spec file per the "spec edits go to the next
  revision" rule (do **not** edit `forge-design-1.2.md` / `-1.3.md` in
  place).
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

- **Gap #7 — `designSessionId` durability.** `forge spec` `/done` persists
  the design session id only to the Feature/state-cache, not the action
  log, so `RebuildState.run` rebuilds it as `None` and the §11.3
  design-PR-feedback resume fails after a rebuild. This is a log-
  completeness bug adjacent to the observability theme (and overlaps
  Task 2.0.6's "preserve history on resume"). Pull it in if Task 2.0.6
  touches the same surface cheaply; otherwise leave it as a standalone
  §11.3 durability fix and record the disposition at close-out.

### New decisions opened in Slice 2.0 (reconcile at Task 2.0.7)

- **D1 — `cost.update` payload shape.** Totals-only vs the full §19
  `{ provider, model, inputTokens, outputTokens, usd, ... }`. Settle while
  implementing Task 2.0.1; if it changes the §19 contract, document in the
  next-revision spec.
- **D2 — turn/piece cost-total reset ownership.** ✅ Fixed standalone
  2026-05-31 (`Orchestrator.store` resets `turn`; `PieceImplementing` entry
  resets `piece` on a new piece), with `OrchestratorCostScopeResetSuite`.
  This restores the §12 per-turn cap to a true per-turn check. At
  Task 2.0.7, confirm whether the spec describes the cap semantics
  differently and, if so, file a design-rationale note rather than editing
  the spec in place.
- **D3 — resume-from-NHI scope.** Task 2.0.6 may exceed a contained change;
  any split rolls forward with a recorded disposition.

## 5. Cross-references

- Roadmap: [`roadmap.md`](roadmap.md) §3.1 (Slice 2.0 tiered task list).
- Source findings: [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md);
  run evidence under
  [`slice-4/mvp-run/image-creds-dedup/`](slice-4/mvp-run/image-creds-dedup/).
- Spec: [`forge-design-1.2.md`](forge-design-1.2.md) §12 (budget caps),
  §19 (action-log kinds incl. `cost.update`), §6 (`Feature` projections);
  [`forge-design-1.3.md`](forge-design-1.3.md) §1 (Slice 1–3 reconciliation).
- Code anchors: `Replay.applyCostUpdate`
  (`forge-core/.../log/Replay.scala:333`), `CostTotals`
  (`forge-core/.../cost/Cost.scala`), `AgentEvent`
  (`forge-agents/.../AgentEvent.scala`), `Orchestrator`
  (`forge-app/.../orchestrator/Orchestrator.scala`), `RealSessionMonitor` /
  `MonitorOutcome` (`forge-app/.../monitor/`).
- Prior audit trail: [`design-1.4.md`](design-1.4.md) (Slice 1.4, closed).
