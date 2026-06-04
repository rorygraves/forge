# Forge — design doc v1.13

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.13 — Phase 3 (Repo Adaptation): reviewer-comment mining (the §19 `review.request_changes` audit kind)  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.11 → 1.12) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.12.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8/1.9/1.10/1.11/1.12 exception).** 1.13 is a *focused* revision resolving decision **D8** (design-3.0 §4): the `ConventionLearner` (§7.11 / §11.7) was framed from the start as mining "failure→remedy patterns **+ recurring reviewer comments**", but Task 3.2 grounded it on the failure half only — the action log captured classified gate failures (`profile.failure_classified`) but **not reviewer comment text**, so feeding "recurring reviewer comments" would have meant inventing a field with no backing data. 1.13 closes that gap: it adds a **`review.request_changes`** audit kind that records the blocker prose whenever Forge's own reviewer asks for changes, widens the §7.11 cost lever to fire on *failures OR reviewer comments*, and adds a `reviewerComments` channel to the §11.7 learner input. It **restates in full only the sections it changes — §7.11, §11.7, and §19** — and **freezes every other section at its 1.12 (hence 1.11 / 1.10 / 1.9 / 1.8 / 1.7 / 1.6) text**. Section numbering is preserved 1:1, so any "v1.12 §N" / "v1.6 §N" reference resolves to the same §N here.

**Unlike 1.12 (a pure spec-text reconciliation), 1.13 has accompanying code** — `io.forge.core.review.ReviewRequestedChangesAction` (the new kind), the `Orchestrator.logReviewerRequestChanges` append at the reviewer seam, the `Orchestrator.observedReviewerComments` miner, the widened `maybeLearnConventions` cost lever, and the `ConventionLearnerInput.reviewerComments` field. Proven by `ReviewRequestedChangesActionSuite`, `ProfileReplayInvarianceSuite` R1 (replay inertness), and `OrchestratorConventionLearnerSuite` (the D8 e2e + the append seam).

**Changed in 1.13:** §7.11 (the learner's mined signals + cost lever widened to failures-or-comments), §11.7 (the `reviewerComments` input channel), §19 (the `review.request_changes` kind enumerated).

**Scope note — what 1.13 does *not* change.** The replayability invariant holds: `review.request_changes` is a **no-op `Replay` projection** (it falls through `Replay`'s default `case _ => Right(state)` arm, like every `profile.*` kind — the FSM consumes the reviewer *verdict* via `FsmEvent.DesignReviewReceived` / `CodeReviewVerdict`, never this audit row; a mined reviewer comment reaches a later run **only** as a human-approved CLAUDE.md / profile change, never inside a transition — the §6.1 replayability invariant, `ProfileReplayInvarianceSuite`). The learner remains advisory and never-blocking (§14.2 posture), additive-only on commands, and **no-autonomous-doc-mutation** (it proposes a CLAUDE.md PR, never a merge — decision D9, unchanged). The FSM lifecycle (§11), the reviewer one-shots themselves (§10.2 / §14.3), and the verdict→event projection are unchanged — 1.13 only *records* the verdict's blocker prose as a side audit row and *feeds* it to the learner.

---

## 7.11 Repo-adaptation sensors — the `ConventionLearner`'s mined signals (changed in 1.13)

The §7.11 adaptation sensors (RepoProfiler / FailureClassifier / ConventionLearner) are unchanged from 1.7 except for **what the `ConventionLearner` mines and when it is consulted**.

The `ConventionLearner` is the post-run sensor consulted out-of-band on the transition to `FeatureDone` (§11.7). It is a **perceive-and-propose** sensor: advisory, never-blocking, additive-only, never mutating a doc autonomously. It mines two run signals against the committed `RepoProfile` and the repo's CLAUDE.md:

1. **failure→remedy patterns** — the run's classified gate failures, distilled from the §19 `profile.failure_classified` actions (`Orchestrator.observedFailures`). A `DriverFixup` route is a fix-up round the run *paid for*; a missing autofix command is the canonical learnable delta.
2. **recurring reviewer comments (new in 1.13)** — the run's reviewer `RequestChanges` blockers, distilled from the §19 `review.request_changes` actions (`Orchestrator.observedReviewerComments`). A blocker that recurs across pieces/rounds (e.g. "add ScalaDoc to public methods", "tests not added alongside the change") is a strong CLAUDE.md-note signal — the driver kept making a mistake the reviewer kept catching.

**The §7.11 cost lever (widened in 1.13).** The learner is the most expensive sensor (a reviewer-call after every feature), so it is consulted **only** when the run produced a mineable signal: previously "the run hit a classified gate failure" (failures non-empty); now "the run hit a classified gate failure **or** a reviewer `RequestChanges`" (failures **or** reviewer comments non-empty). A clean run — no classified failure and no reviewer change-request — still spends no learner reviewer-call. Both signals are read from the committed action log, so the gate is deterministic and replay-stable.

A learner timeout / adapter failure is logged-and-dropped (the §14.2 refinery posture); it never turns a `FeatureDone` feature into a crash.

---

## 11.7 Refinery & the post-run `ConventionLearner` — the `reviewerComments` input channel (changed in 1.13)

§11.7's `ConventionLearner` invocation is unchanged except that its input now carries both mined signals. The orchestrator, on the transition to `FeatureDone` (gated on `adapt.enabled` + `adapt.conventionLearner` + a resolved profile, and on the §7.11 cost lever above), builds the learner input from the feature's committed action log:

- `failures` — `Vector[ObservedFailure]` from the `profile.failure_classified` actions (unchanged).
- `reviewerComments` — `Vector[ObservedReviewerComment]` from the `review.request_changes` actions (**new in 1.13**); each carries `{ gate ∈ {"design","code"}, round: Int | None, blocker: String }` (one entry per blocker — a single `RequestChanges` verdict's blocker list fans out).

On a settled proposal the behaviour is exactly as in 1.12: fresh command deltas merge into the committed `.forge/profile.json` (`ProfileStore.save`, skipped when nothing is fresh); a proposed CLAUDE.md edit is **opened as a PR** for human approval (decision D9 — Forge opens a PR, never merges; on failure it falls back to persisting the proposal to the feature's audit dir); and a `profile.conventions_learned` action (1.12 §19) records what was proposed. The proposal may now be grounded in a recurring reviewer comment as well as a paid fix-up round.

---

## 19. Action log — the `review.request_changes` kind (additive to 1.12 §19)

Whenever Forge's own reviewer one-shot returns a `RequestChanges` verdict — design-review (§11.2) or code-review (§11.5) — the orchestrator records the **blocker prose** as a side audit action, so the post-run `ConventionLearner` (§7.11 / §11.7) can mine recurring reviewer comments (decision **D8**). Approve / `BlockingQuestions` (human questions, not a remedy signal) and an empty blocker list record nothing.

| Kind | Payload | When | Replay projection |
|---|---|---|---|
| `review.request_changes` (1.13) | `{ gate: "design" \| "code", round: number \| null, blockers: [string…] }` | each time the reviewer one-shot returns `RequestChanges` with a non-empty blocker list | no-op (default branch) |

- **`gate`** — `"design"` for a §11.2 design review, `"code"` for a §11.5 code review.
- **`round`** — the design-review round (1-based) for `gate == "design"`; `null` for a code review.
- **`blockers`** — the reviewer's blocking-comment summaries (the mineable prose).
- The top-level `piece` tag is the reviewed piece for a code review, and absent (`None`) for a (piece-less) design review.

Like the `profile.*` audit kinds, this is a **no-op `Replay` projection**: it falls through `Replay`'s default arm, so the FSM never reads it (the §6.1 replayability invariant — the FSM consumes the verdict via `FsmEvent.DesignReviewReceived` / `CodeReviewVerdict`, and the blocker text reaches a later run only as a human-approved CLAUDE.md / profile change). `forge stats` / the TUI may fold it for observability.

All other §19 kinds are frozen at 1.12 (hence 1.11 / 1.10 / 1.9 / 1.8 / 1.7 / 1.6) — including the full Phase-3 `profile.*` audit set (`profile.snapshot` / `profile.failure_classified` / `profile.local_gate` / `profile.conventions_learned`) and the **1.11 nullable `audit.piece_merged` `prNumber`** (the trunk path). With 1.13 the Phase-3 audit surface gains its first non-`profile.*` adaptation kind, `review.request_changes`. 1.13 adds no other kind and changes no existing one.

---

*Everything else — §0–§7.10, §8–§11.6, §12–§18, §20–§24 — is frozen at its 1.12 (hence 1.11 / 1.10 / 1.9 / 1.8 / 1.7 / 1.6) text.*
