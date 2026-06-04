# Forge — design doc v1.11

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.11 — Phase 3 (Repo Adaptation): the `WorkflowProfile.branchModel` trunk-commit (no-PR) lifecycle path  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.9 → 1.10) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.10.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8/1.9/1.10 exception).** 1.11 is a *focused* revision: it lands roadmap sub-slice **3.3-W3 — branch-model trunk path** (design-3.3 carry-forward **W3**: "`branchModel` is sensed-but-unused pending a trunk-commit path — a no-PR trunk-commit lifecycle is a new §11 branch, not a parameterization"; structural precedent design-3.1-build-gate). It **restates in full only the sections it changes — §6, §8.3, §11.4, §11.5, §19** — and **freezes every other section at its 1.10 (hence 1.9 / 1.8 / 1.7 / 1.6) text**. Section numbering is preserved 1:1, so any "v1.10 §N" / "v1.6 §N" reference resolves to the same §N here. The runnable contract these sections describe already exists: the slice landed in `forge-core` (`FsmState.Refining.prNumber: Option`, `FsmEvent.CommittedToTrunk`, the `handleIntegrated` trunk arm, the `Manifest.validate` merged-piece relaxation, the `RebuildState.reconcile` trunk crash-window, the nullable `audit.piece_merged`) + `forge-app` (`SideEffects.commitToTrunk` / `RealSideEffects`, the `ClassifyCommitToTrunk` post-settle effect, `withTrunkBranchModel`), proven by `Fsm_11_5_TrunkPathSuite` + `OrchestratorTrunkPathSuite` with the §6.1 replay invariant intact (`ProfileReplayInvarianceSuite` R1/R2). See [`design-3.3-trunk.md`](design-3.3-trunk.md).

**Changed in 1.11:** §6 (`Refining.prNumber` becomes `Option`; the neutral `CommittedToTrunk` event; the `Manifest.validate` merged-piece relaxation — decision **D2**), §8.3 (the local Build gate still runs pre-integration, then routes to commit-to-trunk on a `TrunkBased` repo instead of open-PR), §11.4 (the post-settle effect commits straight to trunk and emits `CommittedToTrunk` instead of `PrOpened`), §11.5 (a `TrunkBased` piece has **no** PR tail — no `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge`; `CommittedToTrunk` advances straight to `Refining`), §19 (`audit.piece_merged` carries a nullable `prNumber`).

**Scope note — what 1.11 does *not* change.** The branch-model decision lives **wholly in the orchestrator** (which holds the resolved `WorkflowProfile`); the **FSM stays profile-agnostic** — it routes on the neutral `CommittedToTrunk` event + piece id, never reading a profile (the §6.1 replayability invariant — `ProfileReplayInvarianceSuite` R2 enforces the `io.forge.core.fsm` → `io.forge.core.profile` package boundary). This mirrors W1's `ReviewSkipped` (1.9 §6) and the §8.2/§8.3 "decision in orchestrator, pure FSM" split (decision **D1**). An unprofiled run, `adapt.workflowGate = false`, or a `GitFlow` repo keeps the **byte-identical** 1.10 PR lifecycle — `Refining.prNumber` is `Some` on that path, `manifest[p].prNumber` is set, and the §8 CI gate + reviewer one-shot + `PieceAwaitingMerge` tail all run exactly as 1.10. The **design phase keeps its PR even under `TrunkBased`** (decision **D3**): the design doc PR is a human plan-approval gate, not a code-integration mechanism, so `DesignReviewing → DesignAwaitingMerge` is unchanged; trunk-based scopes to the piece tail only. There is **no real `TrunkBased` fixture repo** to validate against (both committed profile fixtures — `szork`, `forge` — are PR/GitFlow repos); the fakes-driven `OrchestratorTrunkPathSuite` is the slice bar and a **live `TrunkBased`-repo demonstration is carried forward** (decision **D5**, the W5-and-build-gate precedent).

---

## 6. Domain model — the trunk-commit lifecycle (additive to 1.10 §6)

The no-PR trunk path is a **new §11 branch**, not a parameterization. It touches three §6 surfaces: one `FsmState` field becomes optional, one neutral `FsmEvent` case is added, and the `Manifest.validate` merged-piece rule is relaxed. None of it makes the FSM read a profile (decision **D1**).

**`Refining.prNumber` becomes `Option[PrNumber]`.** A trunk-committed piece is genuinely integrated into mainline but has no PR, so it refines with no PR number:

```scala
enum FsmState:
  // … 1.10 (hence 1.6) §6 cases unchanged …
  case Refining(p: PieceId, prNumber: Option[PrNumber], startedAt: Instant)   // 1.11: Option — None on a trunk piece
```

- **Consumers updated, all `Option`-aware.** `StatusFields` renders `"(PR #n)"` only when a number is present, else `"(on trunk)"`. The `SettleTimeout(Refine)` recovery hint and `Fsm.hintFromState` split on presence: the PR path yields `RunAnotherFixup(p, pr)` (there is a fix-up PR to re-run), the trunk path yields `AbortOrAbandon` (there is no fix-up PR). `pieceOf` is unaffected (it already ignores the PR number). The §6.1 `currentPieceSessionId` retention set is **unchanged** — `Refining` remains a retained state regardless of whether it carries a PR number.

**`FsmEvent.CommittedToTrunk` — the neutral trunk sibling of `Merged`:**

```scala
enum FsmEvent:
  // … 1.10 (hence 1.9 / 1.8) §6 cases unchanged …
  case CommittedToTrunk(piece: PieceId, commitSha: Sha, committedAt: Instant, observedAt: Instant)
```

- It carries only the piece + the commit facts, **never a profile** — the FSM routes on it alone, exactly as `ReviewSkipped` (1.9 §6) routes on the piece + `prNumber`. The two timestamps mirror `Merged`: `committedAt` is the historical fact (when the commit landed on trunk; stored on the piece's `mergedAt`), `observedAt` seeds `Refining.startedAt` (the §14.1 elapsed clock). Emitted by the orchestrator's `ClassifyCommitToTrunk` post-settle effect (§11.4) **instead of** `PrOpened`. An unprofiled run, `adapt.workflowGate = false`, or a `GitFlow` repo never emits it.

**The "piece merged event flow" comment (1.6 §6) gains a trunk variant:**

```scala
// PR path (GitFlow, 1.6/1.10 — unchanged):
//   PieceAwaitingMerge -> [atomic manifest mutation: status="merged", prNumber, mergeCommit, mergedAt]
//                      -> Refining(p, Some(prNumber), now) -> next piece | FeatureDone | …
// Trunk path (TrunkBased, 1.11):
//   PieceImplementing | PieceBuildFixingUp
//                      -> [atomic manifest mutation: status="merged", prNumber=None, mergeCommit, mergedAt]
//                      -> Refining(p, None, observedAt) -> next piece | FeatureDone | …
// Both record the milestone via audit.piece_merged; on the trunk path its prNumber is null (§19).
```

Both paths share one FSM handler — `handleIntegrated`, generalised from `handleMerged` so the PR `Merged` and trunk `CommittedToTrunk` integrations **cannot drift** — and both are idempotent w.r.t. the manifest mutation. The trunk transition fires from `PieceImplementing(p)` **or** `PieceBuildFixingUp(p)` (a piece that needed a pre-PR Build fix-up still integrates straight to trunk once the build is clean); the Build gate's `LocalBuildFailed` arm is untouched (§8.3 — trunk still gates the build pre-integration).

**`Manifest.validate` merged-piece relaxation (decision D2).** A `Merged` piece now requires `baseSha` + `mergeCommit` + `mergedAt`; **`prNumber` is optional** — a trunk piece is a genuine merge into mainline that simply has no PR. The manifest cannot know a piece's branch model (it stays profile-agnostic), so the universal merged invariant is the **facts of integration** (`mergeCommit` + `mergedAt`), not the presence of a PR. The PR path still *sets* `prNumber` (FSM behaviour, unchanged) — it is simply no longer *validated*. This is a documented weakening: an old check that would have caught a PR-path regression dropping its number no longer fires. The contiguity invariant (§5.5) and every other per-status check (`pending` / `in_progress` null-ness) are unchanged.

---

## 8.3 Local format/build gate (shift-left, pre-PR) — trunk-integration variant (restated)

The §8.3 Build gate (1.8) is **unchanged in its gating semantics**: after Forge commits a piece (§11.4 step 6, **before** integration), it runs the profile's `required` deterministic `Format` (autofix) and `Build` (check) gates locally, gated by `adapt.localGate`, a no-op when unprofiled / `adapt.localGate = false` / no `required` `Build` command. A local `Build` `CodeFix` still routes to a **pre-PR driver fix-up** (`PieceBuildFailed` → `PieceBuildFixingUp`), and any other classified route still falls through to integration exactly as 1.8 (the gate is strictly additive — it can only *shorten* the `CodeFix` path).

**What 1.11 changes:** the gate's *success* exit (and the `CodeFix` fall-through) is no longer "open the PR" unconditionally — it is "**integrate the piece**", and the integration action is chosen by the resolved `WorkflowProfile.branchModel`:

- **`GitFlow` (or unprofiled / `adapt.workflowGate = false`):** push + `createPr` and emit `PrOpened`, byte-identical to 1.8/1.10.
- **`TrunkBased` (and `adapt.workflowGate` on):** commit-to-trunk + push and emit `CommittedToTrunk` (§11.4). **The Build gate still runs first** — a trunk repo must not push a broken compile to mainline (the design-3.3-trunk §0 exit-criterion rule). A build failure still routes to the pre-PR `LocalBuildFailed` fix-up; only once the build is clean does the piece integrate to trunk.

The Format + Build gates and the `routeBuildGateFailure` non-`DriverFixup` fall-through are **byte-identical across the two paths** (in the implementation they are the shared `runLocalGatesThenIntegrate` helper, parameterized only by the integration action). The branch-model choice is the orchestrator's, made from the resolved profile — the gate itself never reads a profile.

---

## 11.4 Implementation phase — step 6 addendum (trunk integration), restated

Step 6 (post-settle), after "Forge commits with `feat(<feature>): <piece title>`" and the §8.3 local Format then Build gate: the **integration** action that previously was "Push, then `createPr`" (1.6) / "emit `PrOpened`" is now branch-model-chosen.

- The orchestrator resolves the run's `WorkflowProfile` once (§11.0) and decides `shouldCommitToTrunk = adapt.workflowGate && profile.workflow.branchModel == TrunkBased`. This is the **only** place the branch model is consulted; the §11 recipe table itself stays profile-agnostic (decision **D1** — implemented as a `withTrunkBranchModel` rewrite over the pure post-settle plan, so the recipe table still names `ClassifyCommitOpenPr` unconditionally).
- **`GitFlow` / unprofiled / gate-off:** unchanged from 1.10 — push, `createPr`, atomically persist `manifest.pieces[i].prNumber`, emit `PrOpened`, FSM → `PieceAwaitingCi(p, prNumber)`.
- **`TrunkBased`:** the post-settle effect is `ClassifyCommitToTrunk` (the trunk sibling of `ClassifyCommitOpenPr`). It runs the §8.3 local gates, then commits the piece straight to the trunk branch:
  1. `ChangeCollector` (§10.1) classify + `docSync`, then `assertHeadIs(baseBranch)` (the trunk analogue of the PR path's `assertHeadIs(pieceBranch)` guard), `git.commit`, `git.currentSha`, push.
  2. Emit **`CommittedToTrunk(piece, sha, committedAt = now, observedAt = now)`** **instead of** `PrOpened`.
  3. `PieceImplementing(p)` (or `PieceBuildFixingUp(p)`) `+ CommittedToTrunk(p)` → `handleIntegrated` mutates `manifest[p]` to `Merged(prNumber = None, mergeCommit = Some(sha), mergedAt = Some(committedAt))` and transitions to `Refining(p, None, observedAt)` — **no `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge` tail** (§11.5). The §8 CI gate and the reviewer one-shot do not run.

The crash window between the manifest mutation and the persisted `fsm.transition` / `audit` drafts is recovered symmetrically to the PR window (decision **D4**): `RebuildState.reconcile` anchors a trunk piece on a `PieceImplementing|PieceBuildFixingUp → Refining(p, None)` fold-state and synthesizes a `CommittedToTrunk(p, mergeCommit, mergedAt, observedAt = mergedAt)` — the exact analogue of the PR window's synthetic `Merged` anchored on `PieceAwaitingMerge`. (`classify` branches on `piece.prNumber`: `Some(pr)` keeps the PR anchor, `None` uses the trunk anchor.)

*D5 carry-forward:* under `TrunkBased`, `advancePieceBranch` should sync the trunk branch rather than cut a piece branch — part of the carried-forward live demonstration (no real `TrunkBased` fixture repo yet).

---

## 11.5 CI & review polling — no PR tail under `TrunkBased` (restated addendum)

The §11.5 `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge` rules (1.6/1.8/1.9) are **unchanged for a `GitFlow` (or unprofiled) repo**. **New:** a `TrunkBased` piece (with `adapt.workflowGate` on) **has no PR tail at all** — the `CommittedToTrunk` integration (§11.4) advances `PieceImplementing | PieceBuildFixingUp` **straight to `Refining(p, None)`**, bypassing the three `PieceAwaiting*` states entirely:

- **No `PieceAwaitingCi`** — there is no PR for the §8 CI gate to observe; the local §8.3 Build gate is the pre-integration check, and it has already run.
- **No `PieceAwaitingReview`** — no PR to review; no reviewer one-shot is spent (cf. 1.9's `ReviewSkipped`, which skipped review but still transited the `PieceAwaiting*` tail because a PR existed; the trunk path has no PR, so the tail does not exist).
- **No `PieceAwaitingMerge`** — the commit-to-trunk *is* the merge; the human-merges-the-PR rule (§11.5 "**v1 rule: piece PRs are merged by the human, not by Forge**") does not apply because there is no PR. The trunk commit lands on mainline directly, gated only by the local Build gate.

`manifest[p].attempts` is untouched on the trunk integration (no CI / review fix-up ran). A pre-PR Build fix-up still tracks its budget in `PieceBuildFailed` / `PieceBuildFixingUp`'s in-state `attempt` (1.8 §11.5, unchanged) and never increments `manifest[p].attempts`. Replay reconstructs the trunk transition generically from the `fsm.transition` `from`/`to` payload — no new projection rule.

**Inert for the GitFlow path.** An unprofiled run, `adapt.workflowGate = false`, `adapt.enabled = false`, or `branchModel == GitFlow` never emits `CommittedToTrunk`: the post-settle effect opens the PR and the full §11.5 `PieceAwaiting*` tail runs, byte-identical to 1.10.

The §11.7 post-merge `Refining` advance is **unchanged** — `Refining` advances to the next `pending` piece / `FeatureDone` / `PlanningUpdate` / NHI identically whether it carries a PR number or not (the refinery `refine` one-shot runs the same way; only the `SettleTimeout(Refine)` recovery hint splits on the `Option` — §6).

---

## 19. Action log — nullable `prNumber` on `audit.piece_merged` (restated)

The `audit.piece_merged` kind (1.6 §19) is unchanged except that its `prNumber` is now **nullable**:

| Kind | Payload | When | Replay projection |
|---|---|---|---|
| `audit.piece_merged` (1.11) | `{ p, prNumber: number \| null, mergeCommit, mergedAt }` | written on entering `Refining` — per §11.5 step 2 on the PR path, or §11.4 on the trunk path | cross-check when the log entry carries a number; skip when it is `null`; `p` always required |

- On the **PR path** `prNumber` is the merged PR number (unchanged). On the **trunk path** it is `null` (`Fsm.auditPieceMergedDraft` emits `prNumber: null` for a `Refining(p, None, …)`).
- `Replay.applyAuditPieceMerged` collects the piece id into `observedPieceMerges`, and performs the manifest `prNumber` cross-check **only when the log entry carries a number** — and then requires the manifest record to carry the *same* number (a log number with no matching manifest number is `AuditPrNumberMismatch`, unchanged). A trunk piece (log `prNumber: null`) **skips** the cross-check and just collects the id. A payload **missing the `p` key** is still `MalformedPayload` (the piece id is the load-bearing field; the PR number is optional). This keeps replay total: a committed trunk-piece timeline folds without a spurious cross-check failure.

All other §19 kinds are frozen at 1.10 (hence 1.6 / 1.8) — the trunk path adds no new action-log kind; it reuses `fsm.transition` (the `PieceImplementing|PieceBuildFixingUp → Refining` edge) and the nullable `audit.piece_merged`. `forge stats` / the TUI fold a trunk merge identically to a PR merge, modulo the absent PR number rendered as `(on trunk)` (§6).

---

*Everything else — §0–§5, §7, §8.0–§8.2, §9–§10, §11.0–§11.3, §11.6–§11.7, §12–§18, §20–§24 — is frozen at its 1.10 (hence 1.9 / 1.8 / 1.7 / 1.6) text.*
