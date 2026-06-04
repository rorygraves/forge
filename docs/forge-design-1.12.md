# Forge — design doc v1.12

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.12 — Phase 3 (Repo Adaptation): the §19 `profile.conventions_learned` audit kind  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.10 → 1.11) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.11.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8/1.9/1.10/1.11 exception).** 1.12 is a *focused* revision: it closes the one remaining §19 schema gap — decision **D7** (design-3.0 §4) — left open since Task 3.2 landed the `ConventionLearner`. The learner emits a `profile.conventions_learned` action so the run records *what the learner proposed* (observable in the TUI / `forge stats`), but no contract revision yet enumerates that kind: 1.7 §19 listed only `profile.snapshot` / `profile.failure_classified`, and 1.8 §19 added `profile.local_gate` (decision **D3**) but not this sibling. 1.12 enumerates it. It **restates in full only the section it changes — §19** — and **freezes every other section at its 1.11 (hence 1.10 / 1.9 / 1.8 / 1.7 / 1.6) text**. Section numbering is preserved 1:1, so any "v1.11 §N" / "v1.6 §N" reference resolves to the same §N here. This is a **pure spec-text reconciliation**: the runnable contract already exists and is unchanged — the kind is emitted by `forge-core` (`ConventionsLearnedAction`) and written by `forge-app` (`Orchestrator.applyConventionDeltas`, hooked at the §11.7 transition to `FeatureDone`), proven by `ConventionDeltasSuite` + `OrchestratorConventionLearnerSuite`; no code lands with 1.12.

**Changed in 1.12:** §19 only (the `profile.conventions_learned` kind enumerated — no payload, behaviour, or projection change; the kind has been emitted since Task 3.2).

**Scope note — what 1.12 does *not* change.** Nothing behavioural. `profile.conventions_learned` is a **no-op `Replay` projection** (it falls through `Replay`'s default `case _ => Right(state)` arm, like every other `profile.*` kind — the FSM never reads it; a learned profile delta reaches a later run **only** as the committed `.forge/profile.json` / proposed CLAUDE.md PR input, never inside a transition — the §6.1 replayability invariant, `ProfileReplayInvarianceSuite`). The decision to *open the CLAUDE.md proposal as a PR* (decision **D9**) and the *mine-failures-not-reviewer-comments* scope (decision **D8**) are unchanged — 1.12 only names the audit kind those behaviours already write. The companion `profile.local_gate` (D3) was already enumerated in 1.8 §19; with 1.12 the full set of Phase-3 `profile.*` audit kinds (`snapshot`, `failure_classified`, `local_gate`, `conventions_learned`) is enumerated in the contract.

---

## 19. Action log — the `profile.conventions_learned` kind (additive to 1.8 §19)

The `ConventionLearner` (§7.11 / §11.7) is consulted out-of-band on the transition to `FeatureDone` and, when it settles, records what it proposed so the learning loop is observable (TUI / `forge stats`). That record is the `profile.conventions_learned` action — the last unenumerated Phase-3 `profile.*` kind (decision **D7**):

| Kind | Payload | When | Replay projection |
|---|---|---|---|
| `profile.conventions_learned` (1.12) | `{ addedCommands: [string…], hasClaudeMdProposal: bool, claudeMdPrNumber: number \| null, summary: string }` | once, out-of-band, after a feature reaches `FeatureDone` when the `ConventionLearner` (§11.7) was consulted and settled | no-op (default branch) |

- **`addedCommands`** — the deduped set of commands the learner actually merged into `.forge/profile.json` (`ConventionDeltas.freshCommands`), each rendered as its space-joined argv (e.g. `"sbt scalafmtAll"`). Empty when the learner proposed no new command.
- **`hasClaudeMdProposal`** — `true` when the learner proposed a CLAUDE.md edit for human approval.
- **`claudeMdPrNumber`** — the number of the PR Forge opened for that CLAUDE.md edit (§11.7 / decision **D9** — Forge opens a PR, never merges); `null` when there was no proposal, or when the open failed and the proposal was persisted locally as a fallback (D9).
- **`summary`** — the learner's one-line description of what it learned.

Like `profile.local_gate` (1.8 §19, decision D3) and `profile.failure_classified`, this is a **no-op `Replay` projection**: it falls through `Replay`'s default arm, so the FSM never reads it and replayability is preserved (the profile delta and the CLAUDE.md proposal reach a later run only as committed-input / a human-approved PR, never inside a transition). `forge stats` / the TUI fold it for observability (a "conventions learned" row is a candidate future fold — left to the consuming pass, not the contract).

All other §19 kinds are frozen at 1.11 (hence 1.10 / 1.9 / 1.8 / 1.7 / 1.6) — including the **1.11 nullable `audit.piece_merged` `prNumber`** (the trunk path), the **1.8 `profile.local_gate` + the `gate: "local"` discriminant on `profile.failure_classified`** (the local gate), and the **1.7 `profile.snapshot` / `profile.failure_classified`** (the deterministic-routing audit). 1.12 adds no other kind and changes no existing one.

---

*Everything else — §0–§18, §20–§24 — is frozen at its 1.11 (hence 1.10 / 1.9 / 1.8 / 1.7 / 1.6) text.*
