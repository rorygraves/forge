# design-3.3-trunk — Slice 3.3-W3 implementation plan (trunk-based / no-PR lifecycle path)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation; the deferred
> sub-slice **W3 — branch-model trunk path**), [`design-3.3.md`](design-3.3.md) **carry-forward
> W3** ("`branchModel` is sensed-but-unused pending a trunk-commit path — a no-PR trunk-commit
> lifecycle is a new §11 branch, not a parameterization"), and the implementation contract
> [`forge-design-1.10.md`](forge-design-1.10.md) (§6 domain types, §8.3 local gate, §11.4/§11.5
> lifecycle, §19 actions). The §11 contract change lands in a new
> [`forge-design-1.11.md`](forge-design-1.11.md) revision (Task 3).
>
> **Filename note:** the precedent is [`design-3.1-build-gate.md`](design-3.1-build-gate.md) — a
> deferred §11-structural sub-slice of an otherwise-closed parent slice gets its own plan file. W3
> is the `branchModel` half of Slice 3.3's `WorkflowProfile` parameterization, deferred at 3.3
> close because it is a lifecycle change beyond a parameterization. The `-trunk` suffix dodges the
> closed [`design-3.3.md`](design-3.3.md).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"): every in-flight
> roadmap sub-slice gets a `design-<slice-id>.md` companion with a Task breakdown (checkbox items),
> an exit criterion, a status log, and a carry-forward list. Tick items as they land — but not
> during a review round; tick the roadmap §4 W3 bullet only at slice close after a whole-section
> review.
>
> **Status:** 🟡 open — 2026-06-04. **Task 1 ✅ landed** (the FSM trunk lifecycle core — the thin
> runnable slice that proves the riskiest contract: the new §11 branch + the manifest-invariant
> relaxation + reconcile, all green in `forge-core`). **Task 2 ✅ landed** (orchestrator branch-model
> wiring + `SideEffects.commitToTrunk` + e2e `OrchestratorTrunkPathSuite`, `forge-app` 428/428).
> **Task 3 (the 1.11 spec revision + whole-section review + roadmap tick) remains.**

---

## 0. Exit criterion

This slice is done when **a piece on a repo whose `WorkflowProfile.branchModel` is `TrunkBased`
is committed straight to the trunk branch and integrated with no PR — no `PieceAwaitingCi` /
`PieceAwaitingReview` / `PieceAwaitingMerge` tail, no §8 CI gate, no reviewer one-shot — and the
feature still advances piece-by-piece to `FeatureDone` through the refinery, with the FSM staying
profile-agnostic (the §6.1 replay invariant) throughout.** The pre-PR local **Build** gate (§8.3,
design-3.1-build-gate) still runs — a trunk repo must not push a broken compile to mainline — so a
build failure still routes to a pre-PR driver fix-up.

Proven end-to-end against scripted fakes in `OrchestratorTrunkPathSuite` (Task 2), mirroring how
`OrchestratorBuildGateSuite` proved the build gate without a live run. There is **no real
`TrunkBased` repo to validate against** (both committed fixtures — `szork`, `forge` — are PR/GitFlow
repos), exactly as W5 noted for no-CI; the fakes-driven proof is the bar, with a live demonstration
carried forward.

Concretely:

1. The FSM gains a neutral `CommittedToTrunk(piece, commitSha, committedAt, observedAt)` event and a
   trunk-integration transition (`PieceImplementing` / `PieceBuildFixingUp` → `Refining`) that
   mutates the manifest piece to `Merged` with **no PR number**. The FSM never reads a profile (R2).
2. `Refining.prNumber` becomes `Option[PrNumber]` and `Manifest.validate`'s merged-piece rule
   requires `mergeCommit` + `mergedAt` (the universal facts of integration) with `prNumber` optional
   — a trunk piece is a genuine merge into mainline that simply has no PR.
3. `RebuildState.reconcile` recovers a trunk piece's crash window symmetrically to the PR window
   (anchor on `PieceImplementing|PieceBuildFixingUp → Refining(p, None)`; synthesize a
   `CommittedToTrunk`).
4. The orchestrator decides `TrunkBased` from the resolved `WorkflowProfile` (gated on
   `adapt.workflowGate`) and runs a `ClassifyCommitToTrunk` post-settle effect (the trunk sibling of
   `ClassifyCommitOpenPr`): local gates + Build gate, then commit-to-trunk + push, emitting
   `CommittedToTrunk` instead of `PrOpened`. Unprofiled / `workflowGate = false` / `GitFlow` keeps
   the byte-identical 1.10 PR lifecycle.
5. The §11 contract change is reconciled into `forge-design-1.11.md` (§6 `Refining` shape, §8.3/§11.4
   trunk success branch, §19 `audit.piece_merged` nullable `prNumber`).

---

## 1. Tasks

### Task 1 — FSM trunk lifecycle core (forge-core)  ⬅ **thin runnable slice** [x] 2026-06-04

The riskiest contract, proven first against fakes — the new §11 branch + the manifest-invariant
relaxation + reconcile, all in `forge-core`, kept fully green. No orchestrator/side-effect wiring
yet (Task 2).

- [x] **`Refining.prNumber: Option[PrNumber]`** (`FsmState`). A trunk piece refines with no PR. All
      consumers updated: `StatusFields` (render "(PR #n)" only when present, else "(on trunk)"),
      `Fsm.refiningTransitions` (the `SettleTimeout(Refine)` hint is `RunAnotherFixup(p, pr)` for the
      PR path, `AbortOrAbandon` for trunk — there is no fix-up PR to re-run), `Fsm.hintFromState`
      (same split). `pieceOf` is unaffected (already ignores the prNumber).
- [x] **`Manifest.validate` merged-piece relaxation** (decision **D2**). `Merged` requires
      `baseSha` + `mergeCommit` + `mergedAt`; `prNumber` is now optional (a trunk piece has none).
      The PR path still sets `prNumber` (FSM behaviour, unchanged) — it is simply no longer
      *validated*, because the manifest stays profile-agnostic and cannot know a piece's branch
      model. Documented weakening; the contiguity invariant (§5.5) and all other per-status checks
      are unchanged.
- [x] **`FsmEvent.CommittedToTrunk(piece, commitSha, committedAt, observedAt)`**. The trunk sibling
      of `Merged`, minus the PR number. Two timestamps for the same reason `Merged` carries them:
      `committedAt` is the historical fact (when the commit landed on trunk, stored on the piece's
      `mergedAt`); `observedAt` seeds `Refining.startedAt`. Emitted by the orchestrator's
      `ClassifyCommitToTrunk` post-settle effect instead of `PrOpened`.
- [x] **Trunk-integration transition** in `pieceImplementingTransitions` and
      `pieceBuildFixingUpTransitions`: on `CommittedToTrunk(p)` (PR-number-free, piece-guarded),
      mutate `manifest[p]` to `Merged(prNumber = None, mergeCommit = Some(commitSha), mergedAt =
      Some(committedAt))`, transition to `Refining(p, None, observedAt)`, and emit the
      `fsm.transition` + `audit.piece_merged` (nullable `prNumber`) drafts. Idempotent w.r.t. the
      manifest mutation, exactly like `handleMerged` (shared via a generalized `handleIntegrated`
      helper so the PR and trunk merge paths cannot drift). The Build gate's `LocalBuildFailed` arm
      is untouched (trunk still gates the build pre-integration).
- [x] **`audit.piece_merged` nullable `prNumber`** (`Fsm.auditPieceMergedDraft`, `Replay`). The draft
      emits `prNumber: null` for a trunk piece; `Replay.applyAuditPieceMerged` collects the piece id
      into `observedPieceMerges` and performs the manifest cross-check **only when both the log and
      manifest carry a number** (a trunk piece skips the cross-check; a `p`-key-missing payload is
      still `MalformedPayload`).
- [x] **`RebuildState.reconcile` trunk recovery** (decision **D4**). `classify` branches on
      `piece.prNumber`: `Some(pr)` keeps the PR anchor (`PieceAwaitingMerge(p, pr) → Refining(p,
      Some(pr))`); `None` uses the trunk anchor (`PieceImplementing(p) | PieceBuildFixingUp(p) →
      Refining(p, None)`). The crash-window (case (c)) synthesizes a `CommittedToTrunk(p,
      mergeCommit, mergedAt, observedAt = mergedAt)` and requires the fold-state to be
      `PieceImplementing(p)` or `PieceBuildFixingUp(p)`. The `piece.prNumber.getOrElse(throw)`
      invariant-guard is removed (trunk pieces legitimately have none).
- [x] **Tests**: `Fsm_11_5_TrunkPathSuite` (trunk integration from `PieceImplementing` and
      `PieceBuildFixingUp`; idempotency; advance-to-next-piece + `FeatureDone`), `FsmState`/
      `Manifest` round-trip + validate cases (merged-with-no-prNumber accepted; merged-without-
      mergeCommit still rejected), `RebuildStateSuite` trunk crash-window + fully-recovered cases,
      and a `ProfileReplayInvarianceSuite` extension proving a trunk trajectory folds identically
      with/without `profile.*` actions (R1) — the FSM never reads the profile (R2 already enforces
      the package boundary).

**Exit:** the §0 criteria 1–3 (the FSM contract), green in `forge-core`, with the §6.1 replay
invariant intact.

### Task 2 — orchestrator wiring + trunk side effect (forge-app)  [x] 2026-06-04

- [x] **Branch-model decision** — the orchestrator resolves `TrunkBased` from the already-resolved
      `WorkflowProfile` (gated on `adapt.workflowGate`, the same umbrella flag W1/Half-A use), and
      routes the `PieceImplementing` / `PieceBuildFixingUp` post-settle effect to a new
      `ClassifyCommitToTrunk` instead of `ClassifyCommitOpenPr`. Implemented as a rewrite
      (`withTrunkBranchModel` over the pure `PostSettleSynthesis.plan` result, keyed on
      `shouldCommitToTrunk`) so the §11 recipe table stays profile-agnostic (D1). Applied at both
      live-settle (`handleWinner`) and cold-rebuild (`postSettleRecover`) sites; `recordSettleMarker`
      and the rebuild recovery discriminator now include `ClassifyCommitToTrunk` alongside
      `ClassifyCommitOpenPr` / `ClassifyCommitPush`. Unprofiled / gate-off / `GitFlow` keeps the exact
      1.10 PR path.
- [x] **`SideEffects.commitToTrunk`** (`RealSideEffects`) — `ChangeCollector` classify + `docSync`,
      `assertHeadIs(baseBranch)` (the trunk analogue of the PR path's `assertHeadIs(pieceBranch)`
      guard), `git.commit`, `git.currentSha`, push, then emit
      `CommittedToTrunk(piece, sha, committedAt = now, observedAt = now)`. The shared
      `runLocalGatesThenIntegrate` helper (extracted from `runLocalGatesThenOpenPr`) parameterizes the
      integration action so the local Format + Build gates — and `routeBuildGateFailure`'s
      non-`DriverFixup` fall-through — are byte-identical across PR and trunk; a build failure still
      routes to the pre-PR `LocalBuildFailed` fix-up (the §0 "no broken compile to mainline" rule).
      *D5 carry-forward noted in code:* under `TrunkBased`, `advancePieceBranch` should sync trunk
      rather than cut a piece branch — part of the carried-forward live demonstration.
- [x] **e2e `OrchestratorTrunkPathSuite`** — scripted-fakes runs on a `TrunkBased` profile: (1)
      `PieceImplementing → commit-to-trunk → Refining → FeatureDone` with no PR / no CI poll / no
      review (asserted via `audit.piece_merged` with `prNumber: null`, and the absence of
      `PieceAwaitingCi` / `PieceAwaitingReview`); (2) build-fail → pre-PR fix-up → re-gate → trunk
      integrate, attempts stays 0; (3) two-piece trunk run to `FeatureDone`; plus GitFlow and
      `workflowGate = false` negatives proving the PR path is taken (open-PR called, trunk not). The
      five PR-lifecycle suites that incidentally used `BranchModel.TrunkBased` while it was inert were
      flipped to `GitFlow` (the branch model is now load-bearing).
- [x] **Design phase keeps its PR** (decision **D3**) — trunk-based scopes to the piece tail; the
      design doc still goes through `DesignReviewing → DesignAwaitingMerge` (the human plan-approval
      gate). Documented; revisit if a fully-trunk repo wants the design committed to trunk too.

### Task 3 — spec revision + close-out  [ ]

- [ ] **`forge-design-1.11.md`** (standalone-by-freeze over 1.10) restating §6 (`Refining` shape),
      §8.3/§11.4 (the trunk success branch), §11.5 (no PR tail under `TrunkBased`), and §19
      (`audit.piece_merged` nullable `prNumber`); freeze the rest at 1.10.
- [ ] Whole-section review; flip the roadmap §4 W3 bullet to closed; carry-forward the live
      `TrunkBased`-repo demonstration (no real fixture repo yet — the W5 / build-gate precedent).

---

## 2. Order of work

Task 1 (FSM core — thin runnable proof) → Task 2 (orchestrator wiring + e2e) → Task 3 (spec +
close-out). Task 1 alone proves the §0 contract crux; Tasks 2/3 wire it to a live run shape and
reconcile the spec.

---

## 3. Status log

- **2026-06-04 — Task 2 landed: orchestrator trunk wiring + side effect + e2e (forge-app), green.**
  The branch-model decision lives in the orchestrator (D1): `shouldCommitToTrunk(profile)` (=
  `adapt.workflowGate && branchModel == TrunkBased`) drives `withTrunkBranchModel`, which rewrites the
  pure `PostSettleSynthesis.plan`'s `ClassifyCommitOpenPr → ClassifyCommitToTrunk` at both the
  live-settle (`handleWinner`) and cold-rebuild (`postSettleRecover`) sites — the §11 recipe table
  itself stays profile-agnostic (`OrchestratorPostSettleSynthesisSuite` still asserts
  `ClassifyCommitOpenPr` unconditionally). A new `SettleEffect.ClassifyCommitToTrunk` dispatches in
  `runSettleEffect` to `runLocalGatesThenCommitToTrunk`, the trunk arm of the extracted
  `runLocalGatesThenIntegrate` helper (the Format + Build gates and `routeBuildGateFailure`'s
  non-`DriverFixup` fall-through are now shared verbatim between PR and trunk, differing only in the
  integration action). `SideEffects.commitToTrunk` (+ `RealSideEffects` impl: classify → `docSync` →
  stage → `assertHeadIs(baseBranch)` → commit → `currentSha` → push → `CommittedToTrunk`) integrates a
  piece straight to trunk with no PR; the `FakeSideEffects` default returns `CommittedToTrunk` for the
  e2e. `recordSettleMarker` + the rebuild recovery discriminator now include `ClassifyCommitToTrunk`
  for crash-window symmetry (D4). New `OrchestratorTrunkPathSuite` (5 tests) proves the trunk path to
  `FeatureDone` (no PR/CI/review), the build-fail → pre-PR fix-up → trunk-integrate variant, a
  two-piece run, and the GitFlow / `workflowGate=false` PR-path negatives; the five PR-lifecycle
  suites that incidentally used `TrunkBased` (build-gate, local-gate, review-gate, ci-routing,
  convention-learner) were flipped to `GitFlow` now that the branch model is load-bearing. `forge-app`
  428/428; full `sbt test` + `scalafmtCheckAll` clean. **Task 2 ticked**; the roadmap §4 W3 bullet
  stays **unticked** (Task 3 — the 1.11 spec revision + whole-section review — remains).

- **2026-06-04 — Task 1 landed: the FSM trunk lifecycle core (forge-core), green.** The new §11
  trunk branch is in: a neutral `FsmEvent.CommittedToTrunk(piece, commitSha, committedAt, observedAt)`
  routed in `pieceImplementingTransitions` + `pieceBuildFixingUpTransitions` to a shared
  `handleIntegrated` (generalised from `handleMerged`, so the PR `Merged` and trunk paths can't drift)
  → `Refining(p, None, observedAt)` with the manifest piece marked `merged` and **no PR number**; the
  Build gate's `LocalBuildFailed` arm is untouched (trunk still gates the build pre-integration).
  Supporting contract changes: `Refining.prNumber: Option[PrNumber]` (with `StatusFields` /
  `Fsm.hintFromState` / the refine-timeout hint `Option`-aware — trunk → `AbortOrAbandon`),
  `Manifest.validate`'s merged rule relaxed to `mergeCommit` + `mergedAt` (prNumber optional — **D2**),
  `audit.piece_merged` with nullable `prNumber` (`Replay.applyAuditPieceMerged` skips the cross-check
  when null, still requires `p`), and `RebuildState.reconcile` trunk crash-window recovery (**D4** —
  `classify` branches on `piece.prNumber`; trunk anchors on `PieceImplementing|PieceBuildFixingUp →
  Refining(p, None)` and synthesizes a `CommittedToTrunk`). The §6.1 replay invariant held throughout
  (`ProfileReplayInvarianceSuite` R2's `fsm`-package sweep stays green; the new handler references no
  profile) — plus a new R1-trunk case proving a trunk trajectory folds identically with/without a
  `profile.snapshot`. New tests: `Fsm_11_5_TrunkPathSuite` (8), `ManifestSuite` (+1 accept / 1 revised),
  `RebuildStateSuite` (+3 trunk reconcile), `ProfileReplayInvarianceSuite` (+1 R1-trunk); ~25
  mechanical `Refining(p, Some(pr), …)` construction fixups across forge-core/app/tui test sites. Full
  unit suite green (`sbt test` 0 failures); `scalafmtCheckAll` clean. **Task 1 ticked**; the roadmap §4
  W3 bullet stays **unticked** (Tasks 2–3 + whole-section review remain).

- **2026-06-04 — slice opened; Task 1 in progress.** Plan authored from the design-3.3 W3
  carry-forward. Key decisions fixed up front (see §4): the FSM stays profile-agnostic (orchestrator
  decides `TrunkBased`, FSM sees only the neutral `CommittedToTrunk`); a trunk piece is a genuine
  merge into mainline with no PR (`Refining.prNumber` / merged-invariant / `audit.piece_merged`
  become `Option`/nullable on the PR number); the design phase keeps its PR; the local Build gate
  still runs pre-integration.

---

## 4. Carry-forward / decisions opened

### D1 — the FSM stays profile-agnostic; the orchestrator decides `TrunkBased`
Mirrors W1 (`ReviewSkipped`) and Half A (sensed CI set): the branch-model decision lives in the
orchestrator (which holds the resolved `WorkflowProfile`); the FSM routes purely on the neutral
`CommittedToTrunk` event + piece id, never reading a profile (the §6.1 replay invariant —
`ProfileReplayInvarianceSuite` R2 enforces the `io.forge.core.fsm` → `io.forge.core.profile`
package boundary).

### D2 — `Manifest.validate` no longer requires `prNumber` on a merged piece
A trunk piece is `Merged` with `prNumber = None`. The manifest cannot know a piece's branch model
(it stays profile-agnostic), so the universal merged invariant becomes `mergeCommit` + `mergedAt`
(the facts of integration), with `prNumber` optional. The PR path still sets `prNumber`; it is just
no longer *validated*. Honest weakening; revisit only if a PR-path regression slips through where
the old check would have caught it.

### D3 — trunk-based scopes to the piece tail; the design phase keeps its PR
The design doc PR is a human plan-approval gate, not a code-integration mechanism. A fully-trunk
repo might want the design committed to trunk too, but that removes the human plan gate — unsafe as
a default. Scoped out; documented here as the place to revisit.

### D4 — trunk crash-window recovery is symmetric to the PR window
`reconcile` recovers a trunk piece's crash window (manifest mutated to `Merged` + committed to
trunk, but the `fsm.transition` / `audit` drafts not yet persisted) by synthesizing a
`CommittedToTrunk` from the manifest record, anchored on a `PieceImplementing|PieceBuildFixingUp`
fold-state — the exact analogue of the PR window's synthetic `Merged` anchored on
`PieceAwaitingMerge`.

### D5 — live `TrunkBased`-repo demonstration carried forward
Both committed profile fixtures are PR/GitFlow repos; there is no real `TrunkBased` repo to drive
end-to-end (the W5-and-build-gate precedent of "design against a real instance before claiming the
live win"). The fakes-driven `OrchestratorTrunkPathSuite` is the slice bar; the live demonstration
is a roadmap §4 carry-forward.

---

## 5. Cross-references

- Parent slice: [`design-3.3.md`](design-3.3.md) (W3 carry-forward) — closed.
- Structural precedent: [`design-3.1-build-gate.md`](design-3.1-build-gate.md) (a deferred
  §11-structural sub-slice with its own contract revision).
- Replay invariant: `ProfileReplayInvarianceSuite` R1/R2 (the FSM never reads a profile).
- Contract: [`forge-design-1.10.md`](forge-design-1.10.md) → [`forge-design-1.11.md`](forge-design-1.11.md) (Task 3).
