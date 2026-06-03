# design-3.3 — Slice 3.3 implementation plan (WorkflowProfile FSM parameterization)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation), sub-slice
> **3.3 — `WorkflowProfile` FSM parameterization** (review / CI / merge-strategy /
> branch model); the spine/senses decision in [`design-rationale.md`](design-rationale.md)
> **A5** ("repo variety handled by a `WorkflowProfile` that *parameterizes* the
> still-deterministic §11 FSM — not by an LLM composing the workflow shape"); and the
> implementation contract [`forge-design-1.9.md`](forge-design-1.9.md) (§6 the
> `ReviewSkipped` event, §11.5 review-required parameterization, §18 `adapt.workflowGate`).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap sub-slice gets a `design-<slice-id>.md` companion with a Task
> breakdown (checkbox items), an exit criterion, a status log, and a carry-forward list.
> Tick items as they land — but not during a review round; tick the roadmap §4 bullet only
> at the Phase-3 slice close after a whole-section review.
>
> **Status:** 🟡 open — 2026-06-03. **Tier 1 (review-required) landed** the same day; the
> remaining tiers (CI-required, merge-strategy/auto-merge) and the deferred branch-model
> trunk path are scoped below.

---

## 0. Exit criterion for Slice 3.3

Slice 3.3 is done when **the `WorkflowProfile` a repo declares parameterizes the §11 FSM
end-to-end** — at minimum, a repo whose `workflow.reviewRequired = false` has its piece PRs
advance to the merge gate **without spending a reviewer call**, and a repo that requires
review behaves exactly as 1.6/1.8. The FSM stays deterministic and profile-agnostic (the §6.1
replayability invariant): every workflow decision is made in the orchestrator from the
resolved profile and reaches the FSM only as a neutral event.

The architectural crux — solved by Tier 1 and reused by every later tier — is that the
`io.forge.core.fsm` package **may not reference `io.forge.core.profile`** (enforced by
`ProfileReplayInvarianceSuite` R2). So a workflow knob cannot be passed into `Fsm.transition`
as a profile type. The pattern instead mirrors the §8.2 router and the §8.3 build gate:
**the orchestrator (which holds the resolved profile) decides, and emits a neutral FsmEvent;
the FSM routes on the event alone.**

---

## 1. Task breakdown

### Tier 1 — review-required parameterization (the cost lever)  ✅ 2026-06-03

The highest-ROI, lowest-risk first runnable, and the one that establishes the
"orchestrator-decides / FSM-stays-pure" pattern for the whole sub-slice. A repo whose
workflow has no PR code-review step should not pay Forge's reviewer one-shot at
`PieceAwaitingReview`.

- [x] **forge-core (the §11.5 / 1.9 contract change).** `FsmEvent.ReviewSkipped(piece,
      prNumber)` + the `pieceAwaitingReviewTransitions` arm `PieceAwaitingReview +
      ReviewSkipped → PieceAwaitingMerge` (same target as an `Approve`, PR-number guarded,
      retains `currentPieceSessionId`, never touches `attempts`). No new **state** is needed
      (the merge gate already exists), so `Replay`/`Generators`/`StatusFields`/`RebuildState`
      are unchanged — the transition replays generically from the `from`/`to` payload
      (`ProfileReplayInvarianceSuite` R1 still holds; R2 holds because the `fsm` package never
      names a profile type — the doc comments say `WorkflowProfile`, which is marker-free).
- [x] **forge-app (the decision + the gate flag).** `AdaptConfig.workflowGate: Boolean =
      true` (§18); the `profile` threaded into `runEntryHook`; a `PieceAwaitingReview`
      entry-hook arm that, when `skipReview(profile)` is true, returns
      `Some(FsmEvent.ReviewSkipped(p, prNumber))` **instead of** falling through to the
      reviewer/watcher race; `skipReview = config.adapt.workflowGate &&
      profile.exists(!_.workflow.reviewRequired)` (a pure decision, the §8.2 split). An
      unprofiled / `adapt.enabled = false` / `reviewRequired = true` / `workflowGate = false`
      run returns `None` and takes the byte-identical 1.6/1.8 reviewer race.
- [x] **Tests.** `Fsm_11_5_CiReviewPollingSuite` (+2: the `ReviewSkipped → PieceAwaitingMerge`
      transition + the stale-prNumber no-op); `OrchestratorReviewGateSuite` (4 e2e:
      `reviewRequired=false` ⇒ reviewer never called + reaches `FeatureDone` via the merge
      gate; `reviewRequired=true` ⇒ reviewer consulted once, no `ReviewSkipped`;
      `workflowGate=false` ⇒ profile ignored, reviewer consulted; unprofiled ⇒ reviewer
      consulted). `forge-core` 443, `forge-app` 415; `scalafmtCheckAll` clean.
- [x] **Contract.** [`forge-design-1.9.md`](forge-design-1.9.md) (standalone-by-freeze;
      §6/§11.5/§18).

### Tier 2 — CI-required + merge-strategy parameterization  [ ]

Lower ROI than Tier 1 and partly already covered by config; scoped here, not yet built.

- [ ] **CI-required (`workflow.ciRequiredChecks`).** The §8 CI gate already reads the
      required-check set from the PR snapshot + `config.ci.requiredChecksOverlay`
      (`CiReadiness`). The profile's `ciRequiredChecks` should feed that overlay so a profiled
      repo's required set is sensed, not hand-configured. **Open question:** a repo with *no*
      CI at all — today an empty required set lands at `CiOutcome.Pending` and the gate's
      discovery rules eventually NHI (`no CI checks discovered`). A "CI not required" profile
      should instead let the piece advance straight to the review/merge gate. This is a real
      §8/§11.5 behaviour change (a `CiNotRequired` neutral event, or a gate-level short-circuit
      in the orchestrator) — design it against a real no-CI repo before building.
- [ ] **merge-strategy (`workflow.mergeStrategy`).** **Blocked on a prerequisite:** Forge does
      **not auto-merge today** — it only *detects* an upstream merge (`PieceAwaitingMerge`
      polls for `Merged`). `mergeStrategy` (squash/merge/rebase) is moot until an auto-merge
      side effect exists (`gh pr merge --squash|--merge|--rebase`). Auto-merge is itself an
      outward-facing, irreversible action that wants its own decision (and a config gate); fold
      `mergeStrategy` in when/if auto-merge lands. Until then the field is sensed-but-unused
      (documented as such).

### Deferred — branch-model / trunk-vs-PR path  [ ]

- [ ] **`workflow.branchModel` (`TrunkBased` vs `GitFlow`) + a no-PR trunk-commit path.** The
      single largest §11 structural change: a workflow that commits a piece straight to trunk
      with no PR has no `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge` tail at
      all. That is a new lifecycle branch (new states/events), not a parameterization of the
      existing one. Defer to its own slice (like the build gate's D2 → its own slice). The
      `branchModel` field is sensed-but-unused until then.

---

## 2. Order of work

Tier 1 (review-required) ✅ — the runnable slice that proves the pattern → Tier 2
{CI-required, merge-strategy behind auto-merge} → deferred {branch-model trunk path, own
slice}. Tier 1 alone satisfies the §0 minimum; Tiers 2/deferred land incrementally behind it.

---

## 3. Status log

- **2026-06-03 — Tier 1 landed: review-required parameterization.** A repo whose
  `WorkflowProfile.reviewRequired` is `false` now skips the PR code-review step: the
  orchestrator's `PieceAwaitingReview` entry hook emits the new `FsmEvent.ReviewSkipped(piece,
  prNumber)` (→ `PieceAwaitingMerge`) **instead of** spawning a reviewer one-shot, spending no
  reviewer call. The decision is the pure `Orchestrator.skipReview` (`adapt.workflowGate &&
  profile.workflow.reviewRequired == false`); the FSM gains only the neutral event + one arm and
  never reads a profile (the §6.1 replayability invariant — `ProfileReplayInvarianceSuite` R1/R2
  still green; the `fsm`-package doc comments say `WorkflowProfile`, not the marker `RepoProfile`).
  No new FSM state was needed (the merge gate already exists), so `Replay`/`Generators`/
  `RebuildState`/`StatusFields` are untouched and the transition replays generically. New config
  flag `AdaptConfig.workflowGate` (default `true`, inert for an unprofiled / disabled run). New
  tests: `Fsm_11_5_CiReviewPollingSuite` (+2), `OrchestratorReviewGateSuite` (4 e2e). `forge-core`
  443, `forge-app` 415; full build green, `scalafmtCheckAll` clean. Contract:
  [`forge-design-1.9.md`](forge-design-1.9.md) (standalone-by-freeze; §6/§11.5/§18). The Tier 1
  items are ticked; the roadmap §4 bullet stays **unticked** until Tier 2 + the whole-section
  review.

---

## 4. Carry-forward / decisions opened

### W1 — `ReviewSkipped` rides the entry hook, not the CI-ready transition — open (1.9 §11.5)
The skip is implemented as a `PieceAwaitingReview` **entry-hook** decision (emit `ReviewSkipped`
before the reviewer/watcher race), not by branching the `PieceAwaitingCi → PieceAwaitingReview`
CI-ready transition straight to `PieceAwaitingMerge`. Reason: the CI-ready transition lives in
the pure FSM, which cannot read the profile (R2); routing it would need a neutral policy passed
into `Fsm.transition` (a signature + `Replay` change). The entry-hook approach keeps the FSM
change to one additive event/arm and the decision wholly in the orchestrator — the established
§8.2/§8.3 split. The cost is one extra `fsm.transition` row (`PieceAwaitingReview →
PieceAwaitingMerge`) per skipped piece, which is honest audit (the piece *did* pass through the
review gate, it just wasn't reviewed). Revisit only if the extra row proves noisy.

### W2 — `mergeStrategy` is sensed-but-unused pending auto-merge — open (1.9 §11.5 / roadmap §4)
`WorkflowProfile.mergeStrategy` is decoded and committed but not acted on, because Forge detects
merges rather than performing them. Folding it in requires an auto-merge side effect (`gh pr
merge --<strategy>`) — an outward-facing irreversible action with its own decision + gate.
Tracked as Tier 2.

### W3 — `branchModel` is sensed-but-unused pending a trunk-commit path — open (roadmap §4)
A no-PR trunk-commit lifecycle is a new §11 branch, not a parameterization of the existing one.
Deferred to its own slice (the build-gate D2 precedent). `WorkflowProfile.branchModel` is decoded
and committed but unused until then.

### W4 — `adapt.workflowGate` is a single umbrella flag for the whole sub-slice — open (1.9 §18)
One `workflowGate` boolean gates the entire `WorkflowProfile` parameterization (review now; CI /
merge later) rather than a per-knob flag. Rationale: an operator either trusts the sensed
workflow shape or pins the fixed 1.6 workflow; a per-knob matrix is premature until more than one
knob is live. Split into per-knob flags only if a repo needs (say) sensed review but pinned CI.

---

## 5. Cross-references

- [`roadmap.md`](roadmap.md) §4 — Phase 3 plan, sub-slice 3.3; tick its bullet only at slice close.
- [`forge-design-1.9.md`](forge-design-1.9.md) — the contract this slice implements (§6/§11.5/§18).
- [`forge-design-1.8.md`](forge-design-1.8.md) / [`design-3.1-build-gate.md`](design-3.1-build-gate.md) — the precedent: a neutral FSM event + an orchestrator-side decision + a config gate.
- [`design-3.0.md`](design-3.0.md) — the `WorkflowProfile` model + the §8.2 "decision in orchestrator, pure FSM" split this reuses; **Task 3.0.2** R2 (the replayability invariant this had to respect).
- [`design-rationale.md`](design-rationale.md) **A5** — "a `WorkflowProfile` that parameterizes the still-deterministic §11 FSM, not an LLM composing the workflow."
