# Forge — design doc v1.15

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.15 — Phase 3 (Repo Adaptation): section-review fixes — the `trunk_based` direct-push hazard (P0), the CI-autofix staging bypass (P1), and the profiler's missing README input (P2)  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.13 → 1.14) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.14.

**Standalone-by-freeze (this revision only, continuing the 1.7→1.14 exception).** 1.15 is a *focused* revision resolving three Phase-3 whole-section-review findings:

- **P0 (safety) — `trunk_based` could direct-push a PR repo.** The profiler prompt emitted `branchModel: trunk_based` as the *default* for "PRs merge to a single main/trunk" (i.e. ordinary GitHub repos), but the orchestrator reads `BranchModel.TrunkBased` as **commit-directly-to-trunk, no PR** (§11.4 trunk path). A normal PR repo profiled `trunk_based` could therefore be direct-pushed to its base branch, bypassing the PR / CI / review gates. 1.15 adds a distinct **`pr_based`** branch model (the safe default), reserves `trunk_based` for genuinely no-PR repos, bumps `schemaVersion` 1 → 2, and gates the §11.4 direct-push on the profile being sensed under the current schema.
- **P1 — the CI autofix committed all dirty paths.** §8.2's `RunLocalCommand` autofix ran the formatter, then staged **every** changed path from a raw `git status`, bypassing the §10.1 `ChangeCollector` deny/ask staging policy that every other commit uses — so unrelated operator edits, or denied files, dirty while Forge polled CI could be committed. 1.15 requires a clean worktree before the formatter and routes the result through the same staging classification as piece/fix-up commits.
- **P2 — the profiler did not read README.** §7.11's `RepoProfiler` input carried `AGENTS.md` / `CLAUDE.md` / build files / workflows but not `README.md`, though §3.3 / roadmap §4 list README as a profiler source — and repos often document the canonical package manager / test command / merge requirements only there. 1.15 adds `README.md` as a first-class profiler input.

It **restates in full only the sections it changes — §6.5, §7.11, §8.2, and §11.4** — and **freezes every other section at its 1.14 (hence 1.13 / … / 1.6) text**. Section numbering is preserved 1:1.

**1.15 has accompanying code.** P0: `BranchModel.PrBased` + `pr_based` wire form, `RepoProfile.CurrentSchemaVersion = 2`, the `repo-profile.json` schema enum + both `repo-profile.<cli>.md` prompts, and `Orchestrator.shouldCommitToTrunk` gated on `branchModel == TrunkBased && schemaVersion == CurrentSchemaVersion`. P1: the clean-worktree guard + `classifyChanges`/`stageChanges` routing in `RealSideEffects.runLocalAutofixAndPush`. P2: `RepoProfilerInput.readmeDoc`, `ProfileCommand.gatherInput` reading `README.md`, and `ReviewerPrompts.repoProfileBody` rendering it. Proven by `RepoProfileDecoderSuite` (the three branch models), `OrchestratorTrunkPathSuite` (PrBased + stale-v1 → PR path), `RealSideEffectsSuite` (autofix clean/dirty/denied), and `ProfileCommandSuite` (README gathered).

**Changed in 1.15:** §6.5 (`BranchModel` gains `pr_based`; `schemaVersion` → 2), §7.11 (profiler reads README; emits `pr_based` by default, `trunk_based` only for no-PR repos), §8.2 (autofix staging guards), §11.4 (direct-push gated on `TrunkBased` *and* current schema).

**Scope note — what 1.15 does *not* change.** The replayability invariant holds: the branch-model decision stays in the orchestrator (`shouldCommitToTrunk` / `withTrunkBranchModel`), never in `Fsm.transition`; the schema-version gate reads the already-hashed committed profile. The autofix change only tightens *which* paths a §8.2 `RunLocalCommand` may stage — its routing, audit kind, and "no driver / no `attempts`" property are unchanged. README is an additional profiler *input*; the `RepoProfile` output schema is unchanged.

---

## 6.5 The committed `RepoProfile` — `BranchModel.pr_based` + `schemaVersion` 2 (changed in 1.15)

§6.5 is unchanged from 1.7 except for the `BranchModel` enum and the schema version.

**`BranchModel` now has three values** (`workflow.branchModel`):

- **`pr_based`** — the common GitHub flow: short-lived piece branches whose **PRs merge to a single main/trunk**. Drives the full §11 PR lifecycle (branch → push → CI → review → merge). **The safe default** — what the profiler emits whenever the repo uses pull requests, even merging to one `main`.
- **`git_flow`** — long-lived `develop`/`release` branches. Behaviourally identical to `pr_based` today (the PR lifecycle); kept distinct so the workflow shape is recorded honestly.
- **`trunk_based`** — the repo commits **directly to the trunk with no PR / review process** (§11.4 trunk path). This is the **only** value that lets Forge push straight to `baseBranch`, so it is reserved for genuinely no-PR repos.

**`schemaVersion` is bumped 1 → 2.** In v1 the profiler emitted `trunk_based` as the *default* for any PR-to-trunk repo, so a v1 `trunk_based` profile is semantically ambiguous (most likely a normal PR repo). The §11.4 direct-push therefore requires a *current-schema* profile (see §11.4); a v1 profile degrades to the safe PR lifecycle until the repo is re-profiled under v2. The decoder continues to **stamp** `CurrentSchemaVersion` on a freshly sensed profile (it is a Forge-internal concern, not an LLM judgment); a committed profile retains its written version on load.

---

## 7.11 Repo-adaptation sensors — README input + the `branchModel` default (changed in 1.15)

§7.11 is unchanged from 1.13 except for two `RepoProfiler` perceptions:

1. **README is a first-class input (P2).** The `RepoProfiler` input (`RepoProfilerInput`) now carries `readmeDoc` — the repo's `README.md` — alongside `AGENTS.md` / `CLAUDE.md` / build files / workflow files, rendered under a stable `## README.md` header in the prompt body. Repos frequently document the canonical package manager, the test command, or merge requirements only in the README, so omitting it produced wrong profiles on unseen repos.
2. **`branchModel` default (P0).** The profiler emits `pr_based` by default — for any repo that uses pull requests — and emits `trunk_based` **only** for a repo that commits directly to the trunk with no PR / review process. "When in doubt, `pr_based`" is explicit in both `repo-profile.<cli>.md` prompts, because `trunk_based` is the one value that authorises a direct push.

The sensor stays perceive-and-propose; nothing here changes the deterministic consumption (§11.0) or the replay invariant.

---

## 8.2 Classified failure routing — the `RunLocalCommand` autofix staging guards (changed in 1.15)

§8.2's `RunLocalCommand` deterministic-fix route is unchanged except that its commit now obeys the §10.1 staging policy and refuses to sweep unrelated changes (P1). When the orchestrator runs a profiled autofix command (e.g. `sbt scalafmtAll` / `npm run format`) on the piece branch and pushes the result:

- **The worktree must be clean before the formatter runs.** The piece is already committed and pushed and the driver has settled, so the only legitimate pre-autofix state is a clean tree. A dirty tree means an operator edited files while Forge polled CI; rather than fold those into the `style(…)` autofix commit, the autofix degrades to a `Left` and the failure routes to a normal fix-up round.
- **The formatter's delta is staged through the §10.1 `ChangeCollector`**, exactly as the §11.4 / §11.6 piece commits — not a raw `git status` + stage-all. A delta touching a denied path aborts the autofix instead of committing it.

Everything else is unchanged: it remains a single deterministic tool run with no driver, no LLM, and no `attempts` increment; the separate `style(…)` commit (not an amend) and its `--no-verify` push are as in 1.13.

---

## 11.4 Trunk path — direct-push gated on a current-schema `trunk_based` profile (changed in 1.15)

The §11.4 trunk path (design-3.3 W3) is unchanged except for its trigger. `Orchestrator.shouldCommitToTrunk` now requires **all** of: `adapt.workflowGate` on, `workflow.branchModel == TrunkBased`, **and** `schemaVersion == CurrentSchemaVersion`. An unprofiled run, `workflowGate = false`, a `pr_based` / `git_flow` repo, **or a pre-`pr_based` (v1) `trunk_based` profile** keeps the 1.10 PR lifecycle. The schema-version conjunct is the P0 safety belt: it prevents a stale v1 `trunk_based` profile — written when `trunk_based` was the default for ordinary PR repos — from triggering an unintended direct push; such a repo degrades to PRs until re-profiled under v2. The decision stays in the orchestrator (the FSM remains profile-agnostic — the §6.1 replay invariant).
