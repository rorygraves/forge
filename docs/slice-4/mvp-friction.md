# Slice 1.4 MVP-gate run — findings & friction (Task 1.4.16 P3/P4)

> First real end-to-end Forge run (started 2026-05-30). Target: feature
> **`image-creds-dedup`** ("de-duplicate the image-credentials feature-flag
> logic") in **`llm4s/szork`** (Scala/Vue), Claude driver, real GitHub
> Actions CI + branch protection (`backend` + `frontend` required checks).
>
> The run is the first time `forge-app` was driven end-to-end against a real
> repo + real CLIs (claude/codex/gh) + real CI. As expected for a first
> integration run, it surfaced a series of gaps — each a genuine bug or
> tuning issue that unit/e2e tests with fakes could not have caught. This is
> the MVP gate doing its job. Per §17 slice-4 P4, friction items are **Phase-2
> input**, not Slice-1.4 follow-ups — except where a gap fully blocked the
> run, which were fixed in-flight to keep going.

## Run progress

`forge new` → `forge spec` (interactive REPL, human-driven `/done`) →
`forge run`: spec → **design review (approved)** → **design PR #9** (CI green)
→ **merged** → `DesignReady` → **implement** (correct code produced) → blocked
at the implement→PR transition (gaps #7/#8 + settle-cap tuning).

The driven implementation is **correct**: `ImageProvider.huggingFaceKey` +
`imageCredsAvailable(provider, reader)` on `SzorkConfig` (with a testable
`ConfigReader` seam), all call sites routed through them (net +33 −67), a new
`ImageProviderCredsSpec` test, and it addressed the third-party Codex
reviewer's `validate()` edge-case comment.

## Gaps found (8)

Commits below are on branch `run-enablement-asset-bootstrap` unless noted.

| # | Gap | Class | Status |
|---|-----|-------|--------|
| 1 | **§8 CI-readiness policy never wired into the orchestrator** — decoder emits `required = Vector.empty`, FSM holds `PieceAwaitingCi` forever; no check-discovery timeout. | missing wiring | **Fixed** — Task 1.4.10b (`6d681af`, on `main`); design-rationale **CI8**. |
| 2 | **`AssetInstaller.installIfMissing` never called at boot** — `~/.forge/{schemas,prompts,templates}` not populated; connector can't bind reviewer assets. | missing wiring | **Fixed** — wired into `Main` step 5 (`d38a378`). |
| 3 | **Driver sessions spawned with no permission flags** — headless `-p` claude has no approver, so every `Write`/`Edit`/`Bash` is denied; spec driver couldn't persist, no driver could change code. `config.claude.{permissionMode,allowedTools}` existed but were never passed to argv. | missing wiring | **Fixed** — `ClaudeConnector` passes `--permission-mode` + `--allowedTools` (`c4de01f`). |
| 4 | **`specify` prompt under-specified the manifest schema** — driver invented piece ids (`icd-1`), wrong field names (`specFile`/`dependsOn`), omitted `acceptanceHash`, over-split; Forge rejected the manifest (`invalid PieceId`, must match `^p[0-9]+$`). | prompt quality | **Fixed** — pinned exact schema + single-piece guidance (`c4887b3`). |
| 5 | **`RealGitClient.stage` used plain `git add`** — refused the gitignored-but-force-included `.forge/specs/...` source of truth ("use -f"); committing design assets failed → NHI. | bug | **Fixed** — `git add -f` (`75492bb`). |
| 6 | **`PrSnapshotDecoder` threw on a check's empty-string `conclusion`** — gh emits `conclusion: ""` (not null) for an in-progress check; decoder only mapped null→None, so every PR poll while CI was running crashed the watcher → NHI. (Same gh null-flattening quirk as `reviewDecision`, S3-8.) | bug / fixture drift | **Fixed** — `""` decodes as `None`; fixture updated to gh's real shape (`a78244c`). |
| 7 | **`designSessionId` is not durably in the action log** — `forge spec` `/done` persists it only to the Feature/state-cache; `RebuildState.run` (which `forge run` does at startup) rebuilds from the *log* → `designSessionId = None`, so the §11.3 design-PR-feedback resume fails with "missing design session id" for any rebuilt feature. | durability bug | **Open** — deferred; needs the session id logged so `foldEvents` reconstructs it. |
| 8 | **NHI(`ResolveLocalImplementationChanges`) is unrecoverable via the CLI** — its recovery message says "continue with `forge run`", but `forge run` from an NHI is loop-terminal (no resume), and there is **no `forge resume` flag** for it (only 3 of 7 hints have flags). Same for `ApplyPlanningUpdate` ("forge run"). | missing wiring | **Fixed** — `forge run` applies `Resume(hint)` at startup for the run-recoverable hints (`Orchestrator.runRecoverableHint`, `0024d3a`). |
| 9 | **Pre-existing ignored cruft denies the whole staging** — `classifyChanges` reads `git status --porcelain --ignored` (to see the force-included `.forge/specs`), but on a real repo `--ignored` lists every ignored path (`target/`, `node_modules/`, `.env`, `.idea`, `.forge/{state,log}`, caches, logs); the ChangeCollector denies them (rule 4) and one denial blocks the stage → NHI. The e2e fakes had no such cruft. | bug | **Fixed** — filter the change set to non-ignored changes + ignored paths under `.forge/specs` (`d2a4f97`). |

## Tuning / behaviour findings (not bugs)

- **Implement settle cap too tight for self-verifying drivers (S4-5).** The
  `implement.claude.md` prompt says *"run … the build before you finish"* and
  *"Stop once … the build is green"*, so the driver runs the target repo's
  full build/tests to self-verify. On a heavy repo (szork: cold `sbt`, llm4s
  deps, large codebase) this exceeds `implementTimeoutSec = 1800` (30 min) →
  `SettleTimeout` → NHI. **In Forge's model the §8 CI gate is the
  verification** and the fix-up loop handles failures — the driver running the
  full build locally is redundant and the time sink. *Fix in progress:* soften
  the implement prompt (don't run the heavy build; let CI verify) + revisit
  `implementTimeoutSec` as an S4-5 config knob.
- **Per-turn cost cap unrealistically low (S4-5).** `maxTurnCostUsd = $2.0`
  (§12 default) vs an actual implement turn of **$9.56** (Claude default model
  exploring szork + a 4-file refactor + a test). The driver settled fast once
  the build-self-verify prompt fix landed, but then tripped the cost cap →
  `TurnBudgetBreached` → NHI. Compounded by resume re-spawning the driver
  (each retry re-pays the full exploration ~$10). Needs realistic v1 cost
  defaults + ideally a resume path that doesn't re-run a completed driver.
- **Forge treats third-party review bots as human feedback.** szork's Codex
  GitHub auto-reviewer (`chatgpt-codex-connector[bot]`) commented on the design
  PR; Forge's PRWatcher author-filter only excludes Forge's *own* identity, so
  the comment routed into the §11.3 DesignPrFeedback loop. Useful coverage of
  §11.3, but a configurable bot-allowlist (or "only react to non-bot authors")
  is worth considering for Phase 2.

## Operational notes (for re-running)

- **Target repo must gitignore `.forge/`** (Forge's lock/state would otherwise
  dirty the worktree and fail the `worktree.clean` preflight). Forge
  force-includes `.forge/specs/...` into commits via `git status --ignored` +
  `git add -f` (gap #5). For the run, `.forge/` was added to szork's
  `.git/info/exclude` (local, uncommitted) to avoid a szork commit.
- **PR merges are operator/human-gated** (the auto-mode classifier blocks
  agent merges to a shared org default branch — correct). The human merges
  each green Forge PR.
- **NHI recovery during the run** used a manual rewind: fix the code, truncate
  `.forge/log/<feature>.jsonl` to drop the bad trailing transition,
  `rm .forge/state/<feature>.json`, re-run `forge run` (rebuilds from log).

## Phase-2 / v1.3 carry-forwards

- **Gap #7** (designSessionId durability) — log the spec session id so
  `RebuildState`/`forge run` can reconstruct it; required for §11.3 to survive
  a rebuild.
- **Spec REPL UX** — `forge new` takes only a slug (driver designs from the
  bare name before any human input); the line-mode REPL fragments multi-line
  paste across prompts and AskUserQuestion routing is brittle. Phase-2 TUI /
  prompt-iteration territory.
- **S4-5** — reviewer/driver model + settle-cap + retry tuning, now with lived
  data (the implement cap is too tight for heavy-build self-verification).
- **Third-party-bot feedback filter** (above).
