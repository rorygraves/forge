# Forge — design doc v1.8

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.8 — Phase 3 (Repo Adaptation): the §8.3 local **Build** gate + its pre-PR fix-up FSM path  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.6 → 1.7) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.7.

**Standalone-by-freeze (this revision only, continuing the 1.7 exception).** 1.8 is a *focused* revision: it resolves the one item 1.7 deferred — decision **D2**, the §8.3 local **Build** gate, which 1.7 scoped out because the frozen §11 FSM had no **pre-PR** fix-up path (its only fix-up states, `PieceCiFailed` / `PieceFixingUp`, both carry a PR number). 1.8 adds that path. It **restates in full only the sections it changes — §6, §8.3, §11.4, §11.5, §19** — and **freezes every other section at its 1.7 (hence 1.6) text**. Section numbering is preserved 1:1, so any "v1.7 §N" / "v1.6 §N" reference resolves to the same §N here. The runnable contract these sections describe already exists: the slice landed in `forge-core` (`FsmState.PieceBuildFailed` / `PieceBuildFixingUp`, `FsmEvent.LocalBuildFailed`) + `forge-app` (the orchestrator Build gate, `SideEffects.runLocalBuildGate` / `writeBuildFailures` / `launchBuildFixup`), proven by `Fsm_8_3_BuildGateSuite` + `OrchestratorBuildGateSuite`.

**Changed in 1.8:** §6 (two pre-PR build fix-up FSM states + the `LocalBuildFailed` event), §8.3 (the Build gate now routes a `CodeFix` to a pre-PR driver fix-up — the half 1.7 deferred), §11.4 (the post-settle Build gate + its fix-up loop), §11.5 (no `attempts` involvement until a PR exists), §19 (`profile.local_gate {kind: "build"}` + `profile.failure_classified {gate: "local"}`).

---

## 6. Domain model — pre-PR Build fix-up states (additive to 1.7 §6)

Two new `FsmState` cases and one new `FsmEvent` case extend the §11.4–§11.5 implementation phase. They are the **pre-PR analogues** of the CI fix-up states (`PieceCiFailed` / `PieceFixingUp`), with two deliberate differences: **no `prNumber`** (the PR does not exist yet) and the fix-up attempt counter lives **in the FSM state only**, never in `manifest[p].attempts`.

```scala
enum FsmState:
  // … 1.7 §6 cases unchanged …
  case PieceBuildFailed(p: PieceId, attempt: Int)     // local Build gate failed → awaiting a fresh fix-up driver spawn
  case PieceBuildFixingUp(p: PieceId, attempt: Int)   // a pre-PR Build fix-up driver is running

enum FsmEvent:
  // … 1.7 §6 cases unchanged …
  case LocalBuildFailed(piece: PieceId)               // §8.3 Build gate failed + §8.2 classified it as a CodeFix
```

- `currentPieceSessionId` (§6.1) is populated at the `PieceBuildFixingUp` spawn and retained through it and `PieceBuildFailed`, exactly as for `PieceFixingUp` — see the unchanged §6.1 projection rules.
- `ResolveLocalImplementationChanges(p, branch)` (the existing pre-PR §6 resume hint) is the recovery hint for an exhausted or timed-out pre-PR Build fix-up. `Resume(ResolveLocalImplementationChanges)` re-enters `PieceImplementing(p)` (1.7 §11), so no new resume target or hint is introduced.

---

## 8.3 Local format/build gate (shift-left, pre-PR) — restated

After Forge commits a piece (§11.4 step 6, **before** push/PR), it runs the profile's `required` deterministic gates **locally**:

- `Format` (`autofix`, `Deterministic`): run `cmd.argv`; rewrite the working tree in place so the piece commit is format-clean before it reaches CI (1.7, unchanged — kills dogfood #3 at the source, zero round-trip).
- `Build` (**non-`autofix`**, `Deterministic`): run `cmd.argv` as a **check**. On a non-zero exit, capture the **full** stdout+stderr and feed it through §8.2:
  - a `CodeFix` (`DriverFixup`) routes to a **pre-PR driver fix-up** — write the full build log to `pieces/<p>.failures.md`, spawn a fix-up driver (`PieceBuildFailed` → `PieceBuildFixingUp`), and re-run the Build gate when it settles. This catches a compile error **before** the PR exists, saving the commit → push → PR → CI-fail → fix-up round-trip the 1.6/1.7 path would pay.
  - **any other route** (Env / RateLimit / Retry / Escalate / an unexpected local autofix) **falls through to the PR open exactly as 1.6** — the PR opens and the existing §8.2 CI path handles the failure. This keeps the Build gate **strictly additive**: it can only *shorten* the `CodeFix` path, never block or escalate a failure pre-PR that the CI path would have handled (a pre-PR NHI the operator cannot easily act on is a regression vs 1.6; falling through is not). *(decision: the 1.7 §8.3 phrasing "an Env escalates" pre-PR is superseded by fall-through — see design-3.1-build-gate §4 D2a.)*

The Build gate is gated by `adapt.localGate` (the §18 `adapt` block; **not** `adapt.autofix` — a build is a check, not an autofix, and the remediation is a driver turn). It is a no-op when unprofiled, `adapt.localGate = false`, or the profile declares no `required` deterministic non-`autofix` `Build` command — each path is byte-identical to 1.6. `Heuristic` commands (a flaky integration suite) stay on the CI side, per the unchanged §8.3 rule.

---

## 11.4 Implementation phase — step 6 addendum (local gate), restated

Step 6 (post-settle), after "Forge commits with `feat(<feature>): <piece title>`" and **before** "Push, then `createPr`": run the §8.3 local Format then Build gate.

- A `Format` autofix rewrites the working tree in place (1.7, unchanged); the existing classify → commit folds it into the one piece commit.
- A local **`Build` `CodeFix`** routes to a **pre-PR driver fix-up**:
  1. The orchestrator writes the full build log to `pieces/<p>.failures.md` and emits `LocalBuildFailed(p)` **instead of** `PrOpened`.
  2. `PieceImplementing(p) + LocalBuildFailed(p)` → `PieceBuildFailed(p, attempt = 1)` (gated by `maxFixupRounds`; exhaustion → `NeedsHumanIntervention(ResolveLocalImplementationChanges(p, branch))`). **`manifest[p].attempts` is NOT incremented** — the pre-PR build budget is in the state only (§11.5).
  3. `PieceBuildFailed(p, attempt) + SessionSpawned(p)` → `PieceBuildFixingUp(p, attempt)`. The entry hook spawns a fresh fix-up driver (`launchBuildFixup`) — the same `runFixup` as the CI path but with **no** `gh`-derived failures capture (the Build gate already wrote `pieces/<p>.failures.md`).
  4. `PieceBuildFixingUp(p, attempt) + Settled(Fixup, Clean)` is an FSM no-op; the orchestrator re-runs the §8.3 Build gate via the **same** `ClassifyCommitOpenPr` post-settle effect as `PieceImplementing`. Pass → `PrOpened` → `PieceAwaitingCi`. Re-fail → `LocalBuildFailed(p)` → `PieceBuildFixingUp(p, attempt) → PieceBuildFailed(p, attempt + 1)` (gated; exhaustion → NHI).
  5. A `SettleTimeout(Fixup)` / `AdapterError` in `PieceBuildFixingUp` → `NeedsHumanIntervention(ResolveLocalImplementationChanges)`.

Then push/PR as in 1.6.

A mid-`PieceBuildFixingUp` process crash is recoverable exactly like `PieceFixingUp`: it is a `Fixup`-phase piece driver, so `RebuildState` projects it as an in-flight (→ D3-3 resume) or settled-but-unadvanced (→ re-run the idempotent Build gate + `ClassifyCommitOpenPr`) session.

---

## 11.5 CI & review polling — `attempts` accounting (clarified)

The §11.5 rule "`DriverFixup`: persist `attempts += 1`" (1.7) applies to **PR-side** (CI / review) fix-ups only. A **pre-PR** Build gate fix-up (§11.4) **never** increments `manifest[p].attempts`: its budget is tracked in `PieceBuildFailed` / `PieceBuildFixingUp`'s in-state `attempt` and bounded by the same `maxFixupRounds`. Consequently the full `maxFixupRounds` budget remains available for PR-side CI fix-ups after a piece's PR finally opens — a compile error fixed pre-PR does not consume a CI fix-up round.

---

## 19. Action log — new local-gate kinds (additive to 1.7 §19)

| Kind | Payload | When | Replay projection |
|---|---|---|---|
| `profile.local_gate` (extended) | `{ gate: "local", kind: "format" \| "build", result?: "pass" \| "fail", commands: [argv…] }` | each time a local Format / Build gate runs | no-op (default branch) |
| `profile.failure_classified` (extended) | `{ gate: "ci" \| "local", classification, route, source }` | before acting on a classified failure (CI **or** local Build gate) | no-op |

Both remain no-op `Replay` projections (the FSM never reads them; the profile reaches a later run only as the committed `.forge/profile.json` input), so replayability is preserved. `forge stats` / the TUI fold them for observability. The 1.7 §19 table enumerated only `profile.snapshot` / `profile.failure_classified`; `profile.local_gate` (added for the Format gate in Task 3.1.3, extended here for Build) and the `gate: "local"` discriminant on `profile.failure_classified` are now enumerated.

---

*Everything else — §0–§5, §7, §8.0–§8.2, §9–§18, §20–§24 — is frozen at its 1.7 (hence 1.6) text.*
