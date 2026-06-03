# Forge — design doc v1.9

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.9 — Phase 3 (Repo Adaptation): the `WorkflowProfile` begins parameterizing the §11 FSM (review-required)  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.7 → 1.8) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.8.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8 exception).** 1.9 is a *focused* revision: it lands the first slice of roadmap sub-slice **3.3 — `WorkflowProfile` FSM parameterization** (design-rationale **A5**: "repo variety handled by a `WorkflowProfile` that *parameterizes* the still-deterministic §11 FSM"). It **restates in full only the sections it changes — §6, §11.5, §18** — and **freezes every other section at its 1.8 (hence 1.7 / 1.6) text**. Section numbering is preserved 1:1, so any "v1.8 §N" / "v1.6 §N" reference resolves to the same §N here. The runnable contract these sections describe already exists: the slice landed in `forge-core` (`FsmEvent.ReviewSkipped`) + `forge-app` (`AdaptConfig.workflowGate`, the `PieceAwaitingReview` entry-hook skip, `Orchestrator.skipReview`), proven by `Fsm_11_5_CiReviewPollingSuite` + `OrchestratorReviewGateSuite`. See [`design-3.3.md`](design-3.3.md).

**Changed in 1.9:** §6 (the `ReviewSkipped` event), §11.5 (a `WorkflowProfile.reviewRequired = false` repo skips the PR code-review step), §18 (`adapt.workflowGate`).

**Scope note — what 1.9 does *not* change.** The `WorkflowProfile` (1.7 §6) already carries `reviewRequired`, `ciRequiredChecks`, `branchModel`, `mergeStrategy`. 1.9 acts on **`reviewRequired` only**. `ciRequiredChecks` / `mergeStrategy` / `branchModel` remain **sensed-but-unused** (decoded and committed, not yet consulted) — `mergeStrategy` is blocked on a not-yet-built auto-merge side effect, and `branchModel`'s no-PR trunk path is a larger §11 structural change deferred to its own slice. See design-3.3 §4 W2/W3.

---

## 6. Domain model — the `ReviewSkipped` event (additive to 1.8 §6)

One new `FsmEvent` case extends the §11.5 implementation/review phase. It is the workflow-parameterization analogue of the §8.2/§8.3 pattern: a **neutral** event (it carries only the piece + PR number, never a profile) that the orchestrator emits from a profile-derived decision, so the FSM routes on the event alone and stays profile-agnostic (the §6.1 replayability invariant — the FSM never reads `RepoProfile` / `ProfileStore`, enforced structurally by `ProfileReplayInvarianceSuite` R2).

```scala
enum FsmEvent:
  // … 1.8 §6 cases unchanged …
  case ReviewSkipped(piece: PieceId, prNumber: PrNumber)   // §11.5: WorkflowProfile.reviewRequired = false → skip review
```

- No new `FsmState` is introduced: `ReviewSkipped` advances to the **existing** `PieceAwaitingMerge` — the same target as a reviewer `Approve`. `currentPieceSessionId` (§6.1) is retained across the transition exactly as on the `Approve` path, and `manifest[p].attempts` is untouched (no review ran). Replay reconstructs the transition generically from the `fsm.transition` `from`/`to` payload — no new projection rule.

---

## 11.5 CI & review polling — review-required parameterization (restated addendum)

The §11.5 `PieceAwaitingReview` rules (1.8) are unchanged for a repo that requires review. **New:** when the repo's `WorkflowProfile.reviewRequired` is `false` (and `adapt.workflowGate` is on), Forge **skips the PR code-review step entirely**:

- On entering `PieceAwaitingReview`, the orchestrator's entry hook emits `ReviewSkipped(p, prNumber)` **instead of** spawning the reviewer one-shot or racing the reviewer/watcher. `PieceAwaitingReview(p, pr) + ReviewSkipped(p, pr) → PieceAwaitingMerge(p, pr)`. **No reviewer call is spent.**
- The decision is made wholly in the orchestrator, which holds the run's resolved profile (§11.0): `skipReview = adapt.workflowGate && profile.workflow.reviewRequired == false`. The pure FSM never sees the profile — it routes on the `ReviewSkipped` event alone (the §8.2 "decision in orchestrator, pure FSM" split, reused). PR-number-guarded like the snapshot arms; a stale `prNumber` is a no-op.
- **Inert for the 1.6/1.8 path.** An unprofiled run, `adapt.enabled = false` (⇒ no resolved profile), `reviewRequired = true`, or `adapt.workflowGate = false` never emits `ReviewSkipped`: the entry hook falls through to the normal reviewer/watcher race, byte-identical to 1.8. Because the FSM still transits `PieceAwaitingReview` (briefly) before advancing, the audit log honestly records that the piece passed the review gate without a verdict.

`mergeStrategy` is **not** consulted at `PieceAwaitingMerge`: Forge still *detects* an upstream merge rather than performing one (auto-merge — and with it `mergeStrategy` — is future work, design-3.3 §4 W2). `ciRequiredChecks` does not yet alter the §8 CI gate (design-3.3 §4 Tier 2).

---

## 18. Config — `adapt.workflowGate` (additive to 1.8 §18)

The §18 `adapt` block gains one field:

```scala
final case class AdaptConfig(
    enabled: Boolean = true,
    localGate: Boolean = true,
    autofix: Boolean = true,
    llmClassifierOnUnknown: Boolean = true,
    conventionLearner: Boolean = true,
    workflowGate: Boolean = true          // 1.9 — WorkflowProfile parameterizes the §11 FSM
) derives ReadWriter
```

- `workflowGate` (default `true`) lets the committed `WorkflowProfile` parameterize the §11 FSM. With it on, a profile declaring `workflow.reviewRequired = false` makes Forge skip the PR code-review step (§11.5). It is a single umbrella flag for the whole sub-slice (review now; CI / merge-strategy later) — design-3.3 §4 W4.
- It is **inert for an unprofiled / `enabled = false` run** (no resolved profile ⇒ review required ⇒ 1.6/1.8 behaviour). Set `workflowGate = false` to pin the fixed 1.6 workflow even for a profiled repo (the operator override). Every default preserves the 1.8 invariant: an unprofiled or default-config repo behaves exactly as 1.8.

---

*Everything else — §0–§5, §7, §8, §9–§17, §19–§24 — is frozen at its 1.8 (hence 1.7 / 1.6) text.*
