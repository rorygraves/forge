# design-phase3-exit — Phase-3 exit-criterion live run (non-Scala / Node·TS repo)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation) **exit
> criterion** (the only Phase-3 work remaining — all six sub-slices 3.0–3.5 are
> ✅ closed); the spine/senses thesis in [`design-rationale.md`](design-rationale.md)
> **A5**; the live contract [`forge-design-1.13.md`](forge-design-1.13.md).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> this is the design/runbook companion for the phase-level gate. Unlike the
> sub-slice plans this ships **almost no new code** — the adaptation layer is
> built; the gate is a *live demonstration on a new stack*. Tick the roadmap §4
> exit bullet only after the run passes and a whole-section review lands.
>
> **Status:** ✅ **closed 2026-06-05** — the live run reached **`FeatureDone`** on
> the Node/TS fork `rorygraves/toast-stats`, both PRs merged, with the §8.2
> formatter-collapse demonstrated on a prettier failure and `forge stats` folding
> *"1 fix-up round avoided"*. The exit criterion is met; the **roadmap §4 exit
> bullet stays un-ticked until a whole-section review** (project discipline). Run
> write-up: [`dogfood/phase3-exit-queryclient-config.md`](dogfood/phase3-exit-queryclient-config.md).
> One Forge gap (F3) is carried forward (§9). See §7 status log.

---

## 0. Exit criterion (verbatim from roadmap §4)

> Forge drives a feature end-to-end on a **new, unseen repo with a different
> stack** (e.g. a Node or Python repo), having **auto-profiled** it, with **zero
> hardcoded-config edits**, and the **formatter handled as a local deterministic
> step** rather than a paid fix-up round.

This is the friend's-repo test: a stranger's repo is not Scala/sbt, and the whole
Phase-3 thesis is that Forge senses the repo (its CLAUDE.md + a derived, hashed
`RepoProfile`) instead of running blind. All four prior dogfoods (#1–#4) were on
the Scala `llm4s/szork` repo; this run proves the adaptation layer is not
secretly Scala-shaped.

**What "zero hardcoded-config edits" means here:** the operator runs `forge
profile` → `forge new` → `forge spec` → `forge run` and does **not** hand-edit
`.forge/config.json` or `.forge/profile.json` to teach Forge the repo's
format/lint/build/test commands. (A pinned *reviewer model* — haiku/gpt-5.3-codex,
see §2 — is acceptable: it is stack-independent, the reviewer is the same CLI
regardless of repo language.)

---

## 1. Target repo selection

**Hard requirements** (each is a current-code constraint, not a preference):

1. **A repo the operator controls** — must be able to open *and merge* PRs, and
   the live run mutates it. Sacrificial-but-real preferred.
2. **GitHub remote** — Forge is `gh`/GitHub-only; GitLab repos are out.
3. **GitHub Actions CI with a required check + branch protection** — the
   genuinely-no-CI path is the **deferred W5** item (design-3.3 §"no-CI-repo
   short-circuit"); a repo with no required check would hit unimplemented
   lifecycle, or force a `ci.policy: "none"` config edit (which violates "zero
   hardcoded-config edits"). So the target *must* have CI.
4. **A deterministic formatter with an autofix** (prettier `--write` / eslint
   `--fix`) wired into a CI check — this is what collapses to a local step.
5. **Non-Scala / different stack** — Node/TS chosen (operator decision 2026-06-05).

**Local Node/TS survey (2026-06-05):**

| Repo | Remote | CI workflows | Formatter | Verdict |
|---|---|---|---|---|
| `home/gameit` | `rorygraves/gameit` (yours) | **none** | eslint | ❌ no CI → hits deferred W5 |
| `home/toast-stats` | `taverns-red/toast-stats` | **rich** (`ci.yml` Quality Gates + 8 more) | **prettier** (`--write`/`--check`) | ✅ **recommended** |
| `home/claudia` | `getAsterisk/claudia` | build-test.yml | — | ❌ not yours (can't merge) |
| `home/mcp`, `home/uigen` | BrowserMCP / none | none | — | ❌ no CI / no remote |
| `home/langfuse` | `langfuse/langfuse` | rich | prettier | ❌ not yours; huge |
| `work/*` | gitlab.adsrvr.org | — | prettier | ❌ GitLab (gh-only) |

**Recommendation: a personal fork of `taverns-red/toast-stats`** (e.g.
`rorygraves/toast-stats`). The operator's permission on the upstream is **READ**
(`gh repo view` 2026-06-05), so upstream merge is impossible — but the upstream is
**PUBLIC**, so a fork is public too and gets **branch protection for free**. The
fork is *better* than running on the upstream directly: full merge control + own
branch-protection settings, and it is **sacrificial** (zero risk to the real
project) while still being a genuinely new/unseen Node·TS repo with real CI +
prettier — the exit criterion is fully satisfied. Captured facts (real, not
invented):

- **CI "Quality Gates" → "Lint and Format Check"** (`.github/workflows/ci.yml`
  lines 114–135) runs `npm run format` (= `prettier --write
  "**/*.{ts,tsx,js,jsx,json,md}"`) then fails the job on a dirty tree
  (`git status --porcelain` → "❌ Code formatting issues detected" + `git diff` +
  `exit 1`). The failing log contains `prettier`, which the rules classifier's
  format markers (`FailureClassifier.scala:111` — `"prettier"`, `"code style
  issues found"`, `"must be formatted"`) match → `deterministic_fix`, **no LLM
  call** — exactly the dogfood-#4 shape on a different stack.
- **`.prettierrc`** (real): `{ semi: false, singleQuote: true, tabWidth: 2,
  trailingComma: "es5", printWidth: 80, arrowParens: "avoid" }` — gives a clean
  deterministic mis-format trigger (see §3 P2).
- **Autofix command**: `npm run format` (root) or `prettier --write` — language-
  agnostic `RunLocalCommand` route (`RealSideEffects.scala:302`).
- Active repo (last commit #1026), default branch `main`, npm workspaces
  monorepo (`frontend` + `packages/{collector-cli,analytics-core,shared-contracts}`).

**Caveat — monorepo.** toast-stats is a 4-workspace monorepo. The format autofix
is repo-wide (`npm run format` at root), so the §8.2 collapse is unaffected, but
the *feature* must be scoped to a single workspace (recommend `frontend`) and the
profile's build/test commands are per-workspace. This is the one added complexity
vs a flat repo; it does not block the gate.

**Caveat — workflow noise on a fork.** Four workflows trigger on `pull_request`:
`ci.yml` (the wanted Quality-Gates / format check), plus `lighthouse-ci.yml`,
`pr-preview.yml`, `pr-preview-cleanup.yml`. The latter three need deploy/preview
secrets a fork won't have → they fail on every PR and would muddy Forge's §8 CI
gate (a non-format failure classifies as `env`→back-off, not the clean format
collapse). **Fork-prep removes them** (keep only `ci.yml`); see P0. GitHub also
**disables Actions on forks by default** — must be re-enabled.

---

## 2. Current-state readiness map

From a code walk of the adaptation layer (file:line cited), what is wired vs what
is a caveat for this run:

| Capability | State | Ref |
|---|---|---|
| `forge profile` end-to-end (sensor → `.forge/profile.json`) | ✅ wired | `ProfileCommand.scala`, `CommandRouter.scala:23` |
| `RepoProfile` expresses Node (`argv` commands, `Determinism`/`autofix`/`required` tiers) | ✅ generic, no sbt assumption | `RepoProfile.scala:124` |
| `RunLocalCommand` autofix executes any `argv` (npm/prettier) | ✅ language-agnostic (`os.proc`) | `RealSideEffects.scala:302` |
| Rules classifier recognizes prettier failures | ✅ marker present | `FailureClassifier.scala:111` |
| §8.2 CI-fail → autofix → push, **no `attempts` increment** | ✅ wired | `FailureRouter.scala`, `Orchestrator.scala:~768` |
| §8.3 local format/build gate (pre-PR shift-left) | ✅ wired, gated by `adapt.localGate` | `Orchestrator.scala:919/1039` |
| `adapt.*` knobs (`enabled`/`localGate`/`autofix`/`workflowGate`/…) all default `true` | ✅ | `ForgeConfig.scala:115` |
| **Reviewer model** | ⚠️ still **hardcoded** haiku/gpt-5.3-codex — *stack-independent*, OK for this gate | `ConnectorFactory.scala:34` |
| **Commit identity** | ⚠️ sensed into profile but **not consumed** — uses ambient git `user.*` | `GitClient.scala:128` (carry-forward D4) |
| **No-CI repo** | ⚠️ deferred W5 — **moot** (toast-stats has CI) | design-3.3 |

**Net:** the format/lint/build/test commands — the thing the exit criterion is
about — are fully auto-profiled and consumed. The two ⚠️ rows (reviewer model,
commit identity) do not block this gate: the reviewer is stack-independent, and
ambient git identity is acceptable for the operator's own machine. No code change
is required to *attempt* the run; any gap surfaced is a finding for §8.

---

## 3. Pre-flight runbook

Mirrors the dogfood discipline (cf. `t5-cifail-routing-runbook.md`). **Nothing
below is irreversible until P4.** Drive P0–P3 first; only proceed to P4 when the
P3 go/no-go is all-green.

### P0 — fork + prepare the sacrificial target (no Forge yet)
Upstream `taverns-red/toast-stats` is PUBLIC, operator perm = READ → run on a fork.
- [ ] `gh repo fork taverns-red/toast-stats --clone=false` → `<you>/toast-stats`.
- [ ] **Enable Actions on the fork** (off by default): Settings → Actions → Allow,
      or `gh api -X PUT repos/<you>/toast-stats/actions/permissions -f enabled=true`.
- [ ] **Trim PR-triggering workflow noise** — on the fork's `main`, delete every
      workflow except `ci.yml` (removing `lighthouse-ci.yml`, `pr-preview.yml`,
      `pr-preview-cleanup.yml` at minimum — the secret-needing ones that fail on a
      fork). This edits the *target repo's* files (sacrificial-repo prep), **not**
      Forge's `.forge/` config, so "zero hardcoded-config edits" still holds.
      Commit + push to the fork.
- [ ] **Add branch protection** on the fork's `main` requiring the `ci.yml` check
      (the "Quality Gates" job — confirm the exact check name from a first PR run),
      so a format failure blocks merge:
      `gh api -X PUT repos/<you>/toast-stats/branches/main/protection …`.
- [ ] **Clone the fork locally** (writable origin) — do NOT reuse the existing
      `home/toast-stats` checkout (its origin is the READ-only upstream). Work on
      this fresh clone (the "no-git-in-a-live-forge-worktree" rule).
- [ ] Confirm `claude`, `codex`, `gh` on PATH at the pinned floors; `gh auth` good.

### P1 — dry-run the sensor (`forge profile`), inspect, do NOT edit
- [ ] `cd` to the toast-stats checkout; `forge profile`.
- [ ] Inspect the committed `.forge/profile.json`. **Assert (read-only):**
  - a `Format` command with `argv` ≈ `["npm","run","format"]` or
    `["prettier","--write",…]`, tagged `determinism: deterministic, autofix:
    true, required: true` — this is what routes the §8.2 collapse.
  - `buildTool: "npm"`, `workflow.mergeStrategy` sane, and
    `workflow.ciRequiredChecks` containing the real required check name(s)
    (so §8 binds the right gate).
  - build/test/typecheck commands present (per-workspace is fine).
- [ ] If the profile is wrong, that is a **finding** (the sensor/prompt needs
      work) — record it in §8; do **not** hand-fix `profile.json` (that would
      violate "zero hardcoded-config edits"). The point of the gate is that the
      sensor gets it right unaided.

### P2 — design the feature + choose the gate mode
- [ ] **Feature**: small, single-workspace (recommend `frontend`), self-contained
      — e.g. extract a couple of inline literals into a small typed config module
      (the dogfood-#4 shape: an env-overridable config object), with a unit test.
      Keep the diff tiny so CI/cost are bounded.
- [ ] **Engineered deterministic mis-format trigger** (so the §8.2 path actually
      *fires* — a modern driver usually formats correctly, so the trigger must be
      one the driver writes literally but prettier deterministically rewrites).
      Tuned to the **real** `.prettierrc` above — pick one acceptance criterion:
  - mandate **double-quoted** string literals for the new constants (config is
    `singleQuote: true` → prettier rewrites to single quotes), **or**
  - mandate **semicolons** on the new statements (config is `semi: false` →
    prettier strips them), **or**
  - mandate the config object written **on a single line** > 80 cols (config is
    `printWidth: 80` → prettier wraps).
  Double-quotes is the most robust. The driver follows the literal instruction →
  CI format check fails → §8.2 routes to `RunLocalCommand(npm run format)`.
- [ ] **Gate mode** (one knob, no code edit):
  - **Mode A (primary) — `adapt.localGate = false`**: the mis-format survives to
    CI; the **§8.2 CI-fail → local-autofix** router fires (faithful dogfood-#4
    reproduction on a new stack; strongest evidence — `attempts` stays 0, "1
    fix-up round avoided").
  - **Mode B (fallback) — `adapt.localGate = true` (default)**: the **§8.3
    pre-commit local gate** runs `npm run format` before the PR opens, so the
    mis-format never reaches CI. Also satisfies "formatter handled as a local
    deterministic step," but shows the shift-left gate rather than the CI-fail
    collapse. Use if Mode A is flaky.
  - Toggling `adapt.localGate` is a *mode selection for the demo*, not a
    repo-specific config edit; document whichever is used.

### P3 — go/no-go checklist (all must be ✅ before P4)
- [ ] P0 access + branch protection confirmed.
- [ ] P1 profile asserts the deterministic format-autofix command **without any
      hand-edit**.
- [ ] P2 feature scoped, mis-format trigger chosen against the real `.prettierrc`,
      gate mode decided.
- [ ] Cost ceiling agreed (per-turn cap; the run is bounded by the §12 budget).
- [ ] Evidence sink dir created: `docs/dogfood/phase3-exit-<feature>/`.

### P4 — the live run (irreversible: opens/mutates real PRs)
- [ ] `forge new <feature>` → `forge spec` (interactive; encode the acceptance
      criteria incl. the mis-format trigger) → `/done`.
- [ ] `forge run` — drive the §11 lifecycle to `FeatureDone`. Watch for the §8.2
      trigger firing on the piece CI (Mode A) or the §8.3 gate (Mode B).
- [ ] Merge the design PR and piece PR when Forge surfaces the merge gate.

---

## 4. Evidence to capture (→ `docs/dogfood/phase3-exit-<feature>/`)

Mirror dogfood #4's artifact set:
- `profile.json` — the auto-derived `RepoProfile` (the "sensed, not hardcoded" proof).
- `action-log.jsonl` — the committed run log (replayable; carries `profile.snapshot`).
- `forge-stats.txt` — must show **"1 fix-up round avoided"** (Mode A) and
  `attempts` unchanged on the formatter path.
- `sec82-autofix.diff` (Mode A) — the `style(...)` autofix commit the router made.
- `prNN.json` — the piece PR snapshot.
- A short outcome writeup `docs/dogfood/phase3-exit-<feature>.md` like the
  `adventure-gen-retry-config.md` template.

---

## 5. Success = exit-criterion clauses, each checked off live

- [x] **New, unseen, non-Scala repo** — fork `rorygraves/toast-stats` (Node/TS), never profiled before.
- [x] **Auto-profiled** — `forge profile` produced a correct `RepoProfile` unaided (hash `2761aa91a8f17ea0`).
- [x] **Zero hardcoded-config edits** — no hand-edit of `profile.json` /
      `config.json`; not even a gate-mode toggle (the profile's `format
      required:false` selected the §8.2 path under default config).
- [x] **Formatter as a local deterministic step** — prettier mis-format remedied
      by `RunLocalCommand(npm run format)` via the **§8.2 CI-fail collapse**: rules
      classifier `deterministic_fix` conf 0.97, **no `attempts`, no LLM**; `forge
      stats` = *"1 fix-up round avoided"*.
- [x] **End-to-end** — `forge run` + one operator resume reached `FeatureDone`;
      design PR #2 + piece PR #3 (→ `bbe5f9d6`) both squash-merged.

---

## 6. Risks & mitigations

- **Monorepo confuses the profiler / driver** → scope the feature to one
  workspace (`frontend`); inspect the profile's commands in P1 before running.
- **Driver formats correctly → §8.2 never fires** (the dogfood-#3 problem) →
  engineered deterministic trigger in P2 tuned to the real `.prettierrc`; Mode B
  as fallback.
- **CI failing-log doesn't contain a recognized marker** → P1/P2 verify the log
  carries `prettier`; if not, that is a classifier finding (§8), and `npm run
  format` in the failing step's command echo should still match `prettier`.
- **Branch protection / merge access wrong** → P0 gates this before any spend.
- **Cost overrun** → per-turn cap (§12) bounds blast radius; small feature scope.
- **Sensor mis-tags a Node test as deterministic** → P1 inspection catches it;
  record as a finding rather than hand-fixing.

---

## 7. Status log

- **2026-06-05** — Plan + runbook drafted. Confirmed all six Phase-3 sub-slices
  closed; this gate is a live run, not new code. Surveyed local Node/TS repos;
  recommended `taverns-red/toast-stats` (only candidate meeting all five hard
  requirements). Code-walked the adaptation layer to confirm readiness (§2);
  captured the real `.prettierrc` + CI format step + `FailureClassifier` markers
  to ground the §3 P2 trigger.
- **2026-06-05 (rev 2)** — operator perm on upstream toast-stats = READ (can't
  merge); upstream is PUBLIC → **switched target to a personal fork**
  (`<you>/toast-stats`): full merge control, free branch protection, sacrificial.
  Surveyed PR-triggering workflows → fork-prep trims all but `ci.yml` (the others
  need fork-absent secrets) + enable Actions on the fork. §1/§3-P0 updated.
- **2026-06-05 (rev 3)** — **P0 + P1 executed and verified (§8).** Fork
  `rorygraves/toast-stats` prepped + branch-protected; smoke PR confirmed clean CI;
  `forge profile` auto-derived a correct profile with zero edits; verified the §8.2
  route resolves `npm run format` despite `required:false`. Paused at the P1/P2
  boundary — P2 (feature choice + interactive `forge spec`) and P4 (the paid driver
  run) need the operator. Candidate feature scouted: extract `frontend/src/config/
  queryClient.ts` inline React-Query literals into a typed config + test, with a
  double-quote mis-format trigger (prettier `singleQuote:true`).
- **2026-06-05 (rev 4) — ✅ CLOSED: live run reached `FeatureDone`.** P4 driven to
  completion. The §8.2 prettier-collapse fired on a Node CI failure (rules,
  conf 0.97, `RunLocalCommand(npm run format)`, **no `attempts`, no LLM**); F1/F2
  (Forge gaps) fixed and committed; F3 (CiReadiness late-check) worked around via a
  fresh resume + carried forward (§9); F4 (fork-prep collateral — deleted workflows
  break self-referential CI meta-tests) resolved by **restoring** the three needed
  workflows on the piece branch (`pr-preview.yml` trigger neutered to
  `workflow_dispatch`; `deploy.yml` + `release-please.yml` verbatim — none trigger
  on PRs), making the required `Test Suite` green. Operator squash-merged design PR
  #2 + piece PR #3 (→ `bbe5f9d6`); Forge detected the merge → `Refining` →
  `FeatureDone`, exit 0. Evidence + write-up:
  [`dogfood/phase3-exit-queryclient-config.md`](dogfood/phase3-exit-queryclient-config.md)
  (+ `phase3-exit-queryclient-config/{profile,action-log,forge-stats,sec82-autofix,pr3}`).
  **Roadmap §4 exit bullet left un-ticked pending a whole-section review.**

---

## 8. Findings (filled during the run)

**P0 (fork prep) — done 2026-06-05.** Forked `taverns-red/toast-stats` →
`rorygraves/toast-stats` (upstream perm = READ, so a fork was required); enabled
Actions; trimmed `.github/workflows` to `ci.yml` only (removed the 8 secret-needing
/ scheduled workflows); branch-protected `main` requiring the **Quality Gates**
check (no enforced GitHub reviews, admin can merge); cloned the fork fresh to
`home/toast-stats-fork`. A throwaway smoke PR (#1, closed) confirmed **all `ci.yml`
checks pass on the fork** (Quality Gates, Security Scan, Test Suite, Flake
Detection, Trivy) — no fork-noise, so `ci.yml` jobs did **not** need trimming.

**P1 (`forge profile`) — done 2026-06-05, ✅ correct, zero edits.** `sbt
"forge-app/run profile --repo-root …/toast-stats-fork"` (103s, hash
`2761aa91a8f17ea0`). Auto-derived: `buildTool: npm`; commands
`format=npm run format` (deterministic, **autofix:true**), `lint`/`typecheck`/`build:frontend`
(deterministic), `test` (heuristic); `workflow.reviewRequired: false`,
`mergeStrategy: squash`, `branchModel: git_flow`, `ciRequiredChecks: [Quality
Gates, Security Scan, Test Suite, Build Applications]`. Verified, not assumed:
- "Build Applications" is a **real** `ci.yml` job (`needs:[quality-gates,test]`) —
  not a phantom; it didn't show in the smoke only because the PR closed before
  `test` finished. So the sensed required-check set is accurate; the §8 gate won't
  hang on a never-reporting check.
- The §8.2 autofix lookup (`FailureRouting.localAutofix`, forge-core:58) filters on
  `autofix && Deterministic` **only — not `required`** — so `format`'s `required:false`
  does **not** block the §8.2 collapse.
- Emergent: because `format` is `required:false`, the §8.3 local format gate (which
  filters `required:true`) won't pre-fix it → the mis-format reaches CI → **§8.2
  fires under default config, no `adapt.localGate` toggle / no config edit needed.**

**P2 (`forge new` + interactive `forge spec`) — done 2026-06-05.** `forge new
queryclient-config` cut `forge/queryclient-config/design`. Drove the spec REPL (via
a FIFO harness) for the **queryClient config-extraction** feature. The spec driver
first over-scoped (a repo-wide `cacheTimes.ts` refactor across ~9 hooks); a firm
free-text redirect narrowed it to a single piece `p1` touching only
`frontend/src/config/queryClient.ts` + a new test, with criterion #6 = the verbatim
double-quote requirement (the §8.2 trigger). The driver itself flagged the
prettier-singleQuote conflict, confirming the trigger is real. `/done` advanced to
`DesignReviewing(1)`.

**P4 (`forge run`) — BLOCKED 2026-06-05 on a genuine Forge gap (finding F1).**
Design review ran and the design reviewer returned **two clarifying questions** (are
the five values confirmed-current? which test style?) → `DesignNeedsHumanInput(1)`.
Headless `forge run` then **hung**: `isLoopTerminal` excludes
`DesignNeedsHumanInput`, its only `EventSource` is `UserQa = userInput`, and
`RunFeature.execute` leaves `userInput = IO.never` — so there is no way to answer
design-review questions from a headless run. The `--answer-file` named in
`EventSources.scala:20` is **unimplemented**; `SpecRepl.classifyStart` *refuses*
`DesignNeedsHumanInput` (tells the operator to run `forge run`, which then hangs —
circular). `reviewRequired:false` only skips the **piece** code-review
(`Orchestrator.scala:947`, `PieceAwaitingReview`), not design review. The
`design.md` the reviewer read is crisp and unambiguous, so a re-spec is unlikely to
reliably avoid the questions — the design reviewer (haiku) simply tends to ask.
**This is a real headless-usability gap, not a quirk of this feature.** Run killed;
stale `.forge/state/.lock` remains (clear with `forge unlock --force`). Awaiting an
operator decision on the fix path (see below).

**P4 (`forge run`) — the live run, three findings surfaced (F1 fixed, F2 fixed,
F3 worked-around). §8.2 collapse DEMONSTRATED on Node/TS.**

The implement driver (haiku, ~26s, \$0.21) wrote `QUERY_CLIENT_CONFIG` with
**double-quoted** strings (per p1.md #6). Forge committed it (`--no-verify`, so the
double quotes survived to CI — see F2) and opened piece PR #3. Then:
- **§8.2 fired, on a Node repo, no LLM:** CI "Quality Gates" ran `prettier --write`
  → dirty tree → fail. Forge fetched the failing log, the **rules** classifier scored
  it `deterministic_fix` (conf 0.97, marker `prettier`), routed `RunLocalCommand`
  (`npm run format`). After the F2 fix the autofix committed
  (`style(queryclient-config): npm run format`, double→single quotes) and **pushed**
  → CI re-ran → **Quality Gates green**, `attempts` unchanged. This is the exit
  criterion's formatter clause, met live on a non-Scala stack.

**F1 — clarifying design-review questions strand a headless run (FIXED, committed
`82f6220`).** First `forge run` attempt hung at `DesignNeedsHumanInput`: the design
reviewer asked two *clarifying* questions, but headless `forge run` has no `UserQa`
source (`RunFeature` leaves `userInput = IO.never`); `isLoopTerminal` excludes the
state; `--answer-file` is unimplemented; `forge spec` refuses it. Fix:
`Orchestrator.designVerdict` now honours `QuestionSeverity` — only `Blocking`
questions gate; `Clarifying`/`Optional` (no blockers) → `Approve` (the documented
intent in `Question.scala`). `OrchestratorDesignVerdictSuite` (+5).

**F2 — client `pre-push` hooks abort Forge's push (FIXED, committed `99bb4ff`).**
The §8.2 autofix push silently failed → degraded to a paid fix-up round
(`PieceCiFailed`→`PieceFixingUp`, `attempts`++). Root cause: the repo's **husky**
`pre-push` hook runs `vitest` (activated when `npm ci` ran husky's `prepare`).
`commit` already used `--no-verify`; `push` did not. Fix: forge-git's automated
pushes (branch + tags) now use `--no-verify` — CI is the gate of record (the hooks
say so). `RealGitClientCommitSuite` (+1, real pre-push hook + bare remote).

**F3 — CI gate declares a late required check "never appeared" (WORKED AROUND;
fix proposed).** After the green autofix, `forge run` hit
`NeedsHumanIntervention("required check 'Build Applications' never appeared")`.
Cause: `CiReadiness.evaluate` (CiReadiness.scala:82-86) blocks once
`checkDiscoveryTimeoutSec` elapses if any required check is absent from `observed`.
The profile sensed 4 required checks; `Build Applications` only starts after
`Test Suite` (`needs:[quality-gates,test]`, ~5min), so it hadn't appeared inside the
discovery window. The gate can't tell "will never run" from "gated behind a slow
job." **Proposed fix (carry-forward F3):** keep polling while any observed check is
still pending/in-progress; only declare a required check missing once CI is
otherwise settled. **Workaround for this run:** wait for the full chain to go green,
then `forge resume --after-human-push p1` (fresh resume resets the discovery clock;
`Build Applications` is present+green → gate passes).

**F4 (not a Forge issue) — fork-prep collateral: a self-referential CI meta-test.**
After the F3 work-around the piece PR's required **Test Suite** failed: `272 passed,
1 failed`. Our `frontend/src/config/queryClient.test.ts` **passed** (✓ 2 tests). The
one failure is `src/__tests__/ci/webkit-coverage.test.ts` — a meta-test that asserts
`.github/workflows/pr-preview.yml` installs webkit — which fails because **P0
fork-prep deleted `pr-preview.yml`** (it needs fork-absent secrets). So the blocker to
`FeatureDone` is the sacrificial-repo trimming, not Forge or the feature. **Runbook
lesson:** prefer neutering workflow *triggers* (or removing their self-referential
meta-tests) over deleting workflow files, so the repo's own CI-config tests still
pass. To reach `FeatureDone`: restore `pr-preview.yml` (triggers neutered) or skip
that meta-test, then resume.

**F4 — RESOLVED (2026-06-05).** Fork-prep deleted **7** workflows; a systematic grep
showed exactly **two** meta-tests depend on deleted ones — `webkit-coverage.test.ts`
(`pr-preview.yml`) and `releaseGatedDeploy.test.ts` (`deploy.yml` + `release-please.yml`)
— and no test enumerates the workflows directory. Because the meta-tests run in
*sequential* required-`Test Suite` steps, the first failure masked the second (two CI
rounds to surface both). Fix on the piece branch `forge/queryclient-config/p1`: restored
all three — `pr-preview.yml` with its trigger neutered to `workflow_dispatch:` (needs
fork-absent WIF/Firebase secrets), `deploy.yml` (already `workflow_call`/`workflow_dispatch`
only) + `release-please.yml` (`push:main`) **verbatim** — so **none fire on a PR**, no
fork-secret workflow muddies the §8 gate, and all three meta-tests pass. Required `Test
Suite` went green; `Build Applications` then ran and passed; the fresh `forge resume`
cleared F3; review skipped (`reviewRequired:false`); operator squash-merged → `FeatureDone`.

**Exit-criterion status — ✅ MET END-TO-END (2026-06-05).** All five clauses checked off
live (§5): unseen non-Scala repo, auto-profiled with zero hardcoded-config edits,
**formatter as a local deterministic step** (§8.2 collapse on a Node prettier failure,
`attempts` 0, no LLM, *"1 fix-up round avoided"*), and `FeatureDone` with both PRs merged.
The two non-substance blockers are closed: F4 resolved (above); F3 worked-around and
carried forward (§9).

**Minor profiler notes (non-blocking, carry-forward candidates):**
- `commitIdentity` was *invented* (`forge[bot]` / noreply) rather than sensed — but
  it is not consumed yet (D4 — ambient git identity used), so decorative for now.
- `format required:false` is defensible (formatting is auto-fixed, not a hard gate)
  but means the §8.3 local format-gate is inert for this repo; worth a profiler-prompt
  review if the §8.3 shift-left behaviour is wanted (revisit-if).

---

## 9. Carry-forward (pre-identified, may grow during the run)

- **F3 — `CiReadiness` declares a late required check "never appeared"** (surfaced
  live, §8). `CiReadiness.evaluate` (`CiReadiness.scala:82-86`) blocks once
  `checkDiscoveryTimeoutSec` elapses if any required check is absent from
  `observed` — but a check gated behind a slow upstream job (here `Build
  Applications`, `needs:[quality-gates,test]`, ~5 min behind `Test Suite`) is
  indistinguishable from one that will never run, so a clean green run is forced
  into `NeedsHumanIntervention`. **Worked around** this run by a fresh `forge
  resume --after-human-push` (resets the discovery clock; all checks then
  present+green). **Proposed fix:** keep polling while any *observed* check is
  still pending/in-progress, and only declare a required check missing once CI is
  otherwise settled (no pending checks). Should land before the roadmap §4
  whole-section review, or be filed as a tracking issue. *Worked example for the
  fix:* the `git_flow` Node profile's 4 required checks where one is two jobs deep.
- **D4 — commit identity from profile** (sensed but not consumed; uses ambient
  git). Not blocking this gate; revisit if the run shows wrong-author commits.
- **S4-5 — reviewer model from profile/config** (still pinned in
  `ConnectorFactory`). Stack-independent; out of scope here.
- **W5 — no-CI-repo short-circuit** (deferred; moot for toast-stats). Stays
  deferred until a real no-CI target exists.
