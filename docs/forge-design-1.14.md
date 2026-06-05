# Forge — design doc v1.14

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.14 — Phase 3 (Repo Adaptation): the two residual "inherit a default" gaps closed — reviewer model/cap as config (**S4-5**) and commit identity consumed from the profile (**D4**)  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.12 → 1.13) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.13.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8/1.9/1.10/1.11/1.12/1.13 exception).** 1.14 is a *focused* revision closing the two Phase-3 carry-forwards that were the only places Forge still **inherited a default instead of sensing/configuring it** — the exact anti-pattern Phase 3 set out to remove (roadmap §4; design-phase3-exit §9):

- **S4-5 — reviewer model + wall-clock cap as config.** The reviewer model (`haiku` / `gpt-5.3-codex`) and the 3-minute per-call cap were hard-wired in `ConnectorFactory` (and mirrored, by hand, in `Orchestrator.reviewerWallClock` and `ProfileCommand`). They are now the §18 `reviewer` block; the defaults reproduce the prior C15 v1 values byte-for-byte, so an unset `config.json` is unchanged.
- **D4 — commit identity consumed from the profile.** §6.5's `RepoProfile.commitIdentity` (default `forge[bot]`) was sensed and decoded but **nothing consumed it** — every Forge commit used the ambient git `user.name`/`user.email`. 1.14 wires the resolved identity into the §11.4 / §11.6 commit step, so a profiled run authors **and** commits as the profile's identity.

It **restates in full only the sections it changes — §6.5, §11.4/§11.6 (the commit-author note), and §18** — and **freezes every other section at its 1.13 (hence 1.12 / … / 1.6) text**. Section numbering is preserved 1:1, so any "v1.13 §N" / "v1.6 §N" reference resolves to the same §N here.

**1.14 has accompanying code.** S4-5: the `io.forge.app.config.ReviewerConfig` block read by `ConnectorFactory.build` and `Orchestrator.reviewerWallClock` (and threaded into `ProfileCommand.run`'s cap). D4: `GitClient.commit(message, author: Option[CommitIdentity])`, `RealGitClient`'s per-invocation `-c user.name`/`-c user.email`, the run-resolved `RealSideEffects.commitIdentity` (built once by `OrchestratorBuilder.resolveCommitIdentity`). Proven by `ForgeConfigLoaderSuite` (reviewer-block defaults + partial override), `RealGitClientCommitSuite` (the real-git author/committer override + ambient fallback), and `RealSideEffectsSuite` (the identity reaches the commit seam; an unprofiled run stays ambient).

**Changed in 1.14:** §6.5 (`commitIdentity` is now consumed, not merely sensed), §11.4 / §11.6 (the commit step authors as the resolved identity), §18 (the `reviewer` config block).

**Scope note — what 1.14 does *not* change.** The replayability invariant holds: the commit identity is a §6.5 profile read resolved **once per run** (build-time, the same `ProfileStore.load()` as `Orchestrator.resolveProfile`, hashed into the log via the existing `profile.snapshot`), never read inside an FSM transition — so a replay reproduces the same commits without consulting the profile (the §6.1 invariant; the identity is an input to the side effect, like the staged file set, not an FSM decision). The reviewer knobs are pure §18 config, already part of the replay inputs. No FSM lifecycle, event, or audit-kind change; `git commit` still uses `--no-verify` (§8 / §11.4 rationale unchanged) and a clean tree still maps to `NothingToCommit`.

---

## 6.5 The committed `RepoProfile` — `commitIdentity` is consumed (changed in 1.14)

§6.5 is unchanged from 1.7 except that the `commitIdentity` field is now **consumed**, not merely sensed.

`RepoProfile.commitIdentity : { name, email }` is the git identity Forge authors commits as. The RepoProfiler senses it per the §7.1 prompt: use `forge[bot] <forge@users.noreply.github.com>` unless the repo documents a different bot/automation identity, in which case use what the repo declares. Prior to 1.14 the field was decoded and committed to `.forge/profile.json` but **no code read it** — every Forge commit used the ambient git config. 1.14 closes that loop:

- The orchestrator resolves the identity **once per run** at build time (`OrchestratorBuilder`), gated exactly like the rest of the profile: `Some(identity)` when `adapt.enabled` **and** the repo is profiled; `None` for an unprofiled / `adapt.enabled = false` run (ambient git identity — the pre-1.14 behaviour). The resolution is best-effort (a malformed `profile.json` degrades the commit author to ambient; the authoritative §6.5 malformed-profile error is still raised by `Orchestrator.resolveProfile`, not the builder).
- Every §11.4 / §11.6 commit (design, piece, fix-up, autofix-style, trunk, conventions-PR) is authored **and** committed as that identity (see §11.4 below).

This makes the §6.5 thesis — *sense the environment, don't silently inherit the human's git config* — true end-to-end: a profiled repo's commits carry `forge[bot]` (or the repo's declared bot), not whatever ambient `user.email` the operator happened to have configured. PR authorship is unaffected (it remains the `gh`-authenticated user); this changes only the git author/committer of the commits Forge makes.

---

## 11.4 / 11.6 Commit step — authoring as the resolved identity (changed in 1.14)

The §11.4 (pre-PR) and §11.6 (post-PR fix-up) commit steps are unchanged except that the commit is authored as the §6.5 resolved `commitIdentity` when one is present. The `GitClient.commit` seam gains an optional `author`; the orchestrator's effect layer (`RealSideEffects`) passes the run-resolved identity to every commit it makes. `RealGitClient` applies it as a per-invocation `git -c user.name=… -c user.email=… commit …`, which git uses for **both** the author and the committer, so a profiled commit shows the bot identity in both. `None` leaves identity ambient (pre-1.14). The seam itself stays policy-free — *which* identity (or none) is the orchestrator's decision per the resolved profile, exactly as the §11 "manifest mutations stay with the FSM / side effects own the git wire" split already prescribes. `--no-verify` and the clean-tree `NothingToCommit` mapping are unchanged.

---

## 18. Configuration — the `reviewer` block (additive to 1.13 §18)

§18 gains a `reviewer` block carrying the reviewer-side model + wall-clock tuning that was hard-wired in `ConnectorFactory` through 1.13 (the Task 1.4.9 I1 deferral, carry-forward **S4-5**). Every field defaults to the Task 1.4.7 / C15 v1 value, so an **unset block is byte-identical to the pre-1.14 hard-wiring** — a config that never mentions `reviewer` behaves exactly as before.

```jsonc
"reviewer": {
  "claudeModel":     "haiku",          // Claude reviewer one-shot model (driver model is the CLI default — no flag)
  "codexModel":      "gpt-5.3-codex",  // Codex model; the CLI's single -m covers BOTH the Codex driver and reviewer
  "wallClockCapSec": 180               // per-call reviewer wall-clock cap (= the original 3-minute cap)
}
```

- **`claudeModel`** — the model the Claude **reviewer** one-shots (`reviewDesign` / `reviewPr` / `refine` / the §7.11 sensors) run on. The Claude *driver* model is unaffected: `ClaudeConnector` exposes no driver-model flag and uses the CLI default.
- **`codexModel`** — the Codex model. Because the `codex` CLI takes a single `-m`, this one knob covers both the Codex driver and its reviewer one-shots (it replaces the prior `ConnectorFactory.CodexModel`).
- **`wallClockCapSec`** — the per-call reviewer wall-clock cap. **One source of truth:** `ConnectorFactory` passes it to the connectors as `reviewerTimeout`, `Orchestrator.reviewerWallClock` enforces the same value in `RealReviewerCall`, and `ProfileCommand` (a single reviewer-side sensing call) defaults its cap to it — so the three can no longer drift, as they did when each carried its own `3.minutes` literal.

The `reviewer` block is read at connector-construction / orchestrator-build time, not inside any FSM transition, so it is a normal §18 replay input.
