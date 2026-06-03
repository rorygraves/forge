# design-3.1-build-gate — Slice 3.1-D2 implementation plan (pre-PR local Build gate)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation, the §8.3
> local gate), [`design-3.0.md`](design-3.0.md) **decision D2** (the deferred §8.3
> Build gate), and the implementation contract [`forge-design-1.8.md`](forge-design-1.8.md)
> (§6 new states, §8.3 Build routing, §11.4/§11.5 lifecycle, §19 audit kinds).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> design-3.0's note "Later sub-slices get their own plans if they grow" — this one
> grew (a frozen-§11 FSM contract change), so it gets its own file. Tick items as
> they land; tick the roadmap §4 bullet only at the Phase-3 slice close.
>
> **Status:** 🟢 landed — 2026-06-03. The FSM contract, orchestrator wiring, side
> effects, tests, and the 1.8 spec revision are all in. The §0 live-run measurement
> (T1 below) shares design-3.0's deferred live-`szork` dogfood re-run.

---

## 0. Exit criterion

This slice is done when **a piece whose driver leaves a compile error is caught by a
local `Build` gate _before_ the PR is opened, fixed by a pre-PR driver fix-up, and the
PR then opens green — with no `manifest.attempts` consumed and no CI round-trip for the
build failure.** Proven end-to-end against scripted fakes in `OrchestratorBuildGateSuite`
(the live measurement rides design-3.0 T5 — see T1 below).

Concretely:

1. ✅ Two pre-PR fix-up FSM states (`PieceBuildFailed` / `PieceBuildFixingUp`) + a
   `LocalBuildFailed` event, with the attempt counter **in the state only** (never
   `manifest.attempts`) — `forge-core`, proven by `Fsm_8_3_BuildGateSuite` (10 cases).
2. ✅ The orchestrator runs a local Build gate in the `ClassifyCommitOpenPr` post-settle
   effect (for both `PieceImplementing` and a re-gating `PieceBuildFixingUp`), routes a
   `CodeFix` to the pre-PR fix-up via §8.2, and falls through to the 1.6 PR-open for any
   other route — `OrchestratorBuildGateSuite` (5 e2e cases).
3. ✅ Full failing build output captured (not the 500-char `runCommand` tail) and written
   to `pieces/<p>.failures.md` for the fix-up driver — `RealSideEffects.runLocalBuildGate`
   / `writeBuildFailures` / `launchBuildFixup`, proven in `RealSideEffectsSuite` (3 units).
4. ✅ Crash recovery: `PieceBuildFixingUp` is a `Fixup`-phase driver in
   `RebuildState.driverKeyFor` (in-flight resume / settled-but-unadvanced re-gate) —
   `RebuildStateInFlightSuite` (2 cases); `RestartRecovery` + `TuiSnapshot` updated.
5. ✅ `profile.local_gate {kind: build, result}` + `profile.failure_classified {gate: local}`
   recorded (no-op `Replay` projections); §19 enumerated in 1.8.
6. ⬜ **T1 — a live re-run** showing the collapse end-to-end (shared with design-3.0 §0/T5).

---

## 1. Tasks

### Task D2.1 — FSM contract (forge-core)  ✅ 2026-06-03
- [x] `FsmState.PieceBuildFailed(p, attempt)` + `PieceBuildFixingUp(p, attempt)` (no `prNumber`).
- [x] `FsmEvent.LocalBuildFailed(piece)` (log written to disk out-of-band; the event carries no payload — the FSM routes on the piece + the in-state `attempt` gate).
- [x] `Fsm.transition` arms: `PieceImplementing + LocalBuildFailed`; `PieceBuildFailed + SessionSpawned`; `PieceBuildFixingUp + {SessionSpawned, SessionResumed, PrOpened, LocalBuildFailed, SettleTimeout, AdapterError}`. Helpers `gateBuildFixup` (in-state attempt gate, **no** manifest mutation — the pre-PR sibling of `bumpAttemptsAndGate`) + `spawnBuildFixup`.
- [x] Compiler-enforced exhaustiveness: `Fsm` dispatch, `hintFromState` (both → `ResolveLocalImplementationChanges`), `StatusFields.stateLabel` + `pieceOf`. `RebuildState.driverKeyFor` (`PieceBuildFixingUp` → Fixup driver). `Feature` §6.1 docstring. `Generators.genFsmState` (property coverage).

### Task D2.2 — orchestrator + side effects (forge-app)  ✅ 2026-06-03
- [x] `PostSettleSynthesis`: `(PieceBuildFixingUp, Fixup) → ClassifyCommitOpenPr` (re-gate via the same effect as `PieceImplementing`).
- [x] `EventSources.select` + the entry hook: `PieceBuildFailed` (launch fresh, like `PieceCiFailed`) + `PieceBuildFixingUp` (resume-or-launch, like `PieceFixingUp`).
- [x] `runSettleEffect`'s `ClassifyCommitOpenPr` handles both states via `runLocalGatesThenOpenPr` → format gate → Build gate → `{PrOpened | LocalBuildFailed | fall-through}`. `routeBuildGateFailure` (§8.2, `gate = "local"`); `localBuildGateCommands` (filter `Build && required && !autofix && Deterministic`, gated by `adapt.localGate`); `localBuildGateDraft`.
- [x] `SideEffects`: `runLocalBuildGate` (full-output capture via `runCommandFull`), `writeBuildFailures`, `launchBuildFixup` (no `gh` capture). `FakeSideEffects` defaults.
- [x] `RestartRecovery` (reason + `loopResumablePieceDriver`) + `TuiSnapshot.forState` treat `PieceBuildFixingUp` as `PieceFixingUp`.

### Task D2.3 — spec + tests + green  ✅ 2026-06-03
- [x] `forge-design-1.8.md` (standalone-by-freeze; §6/§8.3/§11.4/§11.5/§19).
- [x] `Fsm_8_3_BuildGateSuite` (10), `OrchestratorBuildGateSuite` (5), `RealSideEffectsSuite` (+3), `OrchestratorPostSettleSynthesisSuite` (+1), `RebuildStateInFlightSuite` (+2). `forge-core` 441, `forge-app` 411; `scalafmtCheckAll` clean.

---

## 2. Sequencing

Task D2.1 (FSM, the riskiest contract — run first per the "runnable slice before a thicker
design pass" rule) ✅ → D2.2 (wiring) ✅ → D2.3 (spec + tests) ✅. T1 (live re-run) rides
design-3.0's §0 deferral.

---

## 3. Status log

- **2026-06-03 — slice landed.** The §8.3 local Build gate (design-3.0 D2) ships as a
  pre-PR driver fix-up over two new FSM states. A driver's compile error is now caught by
  a local `Build` check before the PR opens and fixed by a fix-up driver that re-gates,
  saving the commit → push → PR → CI-fail → fix-up round-trip — without consuming a CI
  fix-up round (`manifest.attempts` is untouched; the budget is in-state). Full build
  output is captured (not the 500-char tail) so the fix-up driver sees the real error.
  Crash recovery, restart-resume, and the TUI treat `PieceBuildFixingUp` exactly like
  `PieceFixingUp`. `forge-core` 424→441, `forge-app` 402→411; full build green,
  `scalafmtCheckAll` clean. The 1.8 spec revision reconciles §6/§8.3/§11.4/§11.5/§19;
  the roadmap §4 bullet stays unticked until the Phase-3 slice close.

---

## 4. Carry-forward / decisions opened

### D2a — a non-`CodeFix` Build gate failure falls through to the PR open, it does not escalate pre-PR — open (1.8 §8.3)
1.7 §8.3 said "a `Build` `CodeFix` routes to a pre-PR driver fix-up … an `Env` escalates."
The landed gate routes a `CodeFix` to the pre-PR fix-up (the headline save) but, for every
other §8.2 route (Env/RateLimit/Retry/Escalate/an unexpected local autofix), **falls through
to the PR open exactly as 1.6** rather than raising a pre-PR `NeedsHumanIntervention`. Reason:
the Build gate is *additive* — a pre-PR NHI the operator cannot easily act on is a regression
vs 1.6, where the same failure reaches CI and routes via the existing §8.2 path (which handles
Env via `BackOff`). So the gate can only *shorten* the `CodeFix` path, never block a failure
pre-PR. Revisit if a profiled repo wants a genuinely fatal pre-PR Env failure (a missing
toolchain) escalated immediately rather than after a wasted CI round; 1.8 §8.3 records this as
the deliberate supersession of the 1.7 "Env escalates" phrasing.

### D2b — `profile.local_gate` gains a `kind: "build"` + `result` field; `profile.failure_classified` gains `gate: "local"` — open (1.8 §19)
The Format gate (Task 3.1.3) emitted `profile.local_gate {kind: "format"}`; the Build gate
adds `kind: "build"` + a `result: "pass" | "fail"` field, and `routeBuildGateFailure` emits
`profile.failure_classified {gate: "local"}` (the CI path uses `gate: "ci"`). Both are
additive, no-op `Replay` projections — enumerated in 1.8 §19. Decide in a later revision
whether `forge stats` should fold a pre-PR build fix-up *avoided* into the "fix-ups avoided"
row (it is a larger save than the §8.2 CI collapse, since the PR round never starts) —
carried with design-3.0 D3.

### D2c — `LocalBuildFailed` carries only the piece; the build log travels via `<p>.failures.md` — open (1.8 §6)
The event is payload-light by design (mirroring how the CI fix-up routes the log through
`<p>.failures.md`, not the FSM): the orchestrator writes the full build log to disk before
the transition, so the `PieceBuildFailed` entry hook's `launchBuildFixup` reads it via the
existing `fixupBody`. This keeps the event/state lean and FSM-pure (the transition never
reads the log content — only the piece match + the `maxFixupRounds` gate). Revisit only if a
future replay needs the build log reconstructable from the action log alone (today it is a
working-tree artefact, like the CI `<p>.failures.md`).

### T1 — live `szork` re-run deferred — open (shared with design-3.0 §0/T5)
The collapse is proven end-to-end against scripted fakes + a real compile-error log
classified by `RuleBasedFailureClassifier`. The live measurement on `szork` rides
design-3.0's deferred §0 dogfood re-run.

---

## 5. References

- [`forge-design-1.8.md`](forge-design-1.8.md) — the contract this slice implements.
- [`design-3.0.md`](design-3.0.md) **D2** — the deferral this slice resolves; **D3** — the §19 `profile.*` enumeration debt.
- [`roadmap.md`](roadmap.md) §4 — Phase 3 plan; tick its bullet only at slice close.
