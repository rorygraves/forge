# design-3.0 — Slice 3.0/3.1 implementation plan (Repo Adaptation)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation), the
> spine/senses decision in [`design-rationale.md`](design-rationale.md) **A5**, and
> the implementation contract [`forge-design-1.7.md`](forge-design-1.7.md) (§3 spine/senses,
> §6 domain types, §7 sensor roles, §8 classified routing, §11 lifecycle seams, §19 actions).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a Task
> breakdown (checkbox items), an exit criterion, a status log, and a carry-forward list.
> Tick items as they land — but not during a review round; tick the roadmap bullet only
> at slice close after a whole-section review.
>
> **Filename note:** this plan covers roadmap sub-slices **3.0** (RepoProfile model +
> store + hash) and **3.1** (FailureClassifier + deterministic routing) together, because
> 3.1 is the highest-ROI first runnable and shares all of 3.0's types. Later sub-slices
> (RepoProfiler role population, ConventionLearner) get their own plans if they grow.
>
> **Status:** ✅ closed — 2026-06-05. The §0 exit criterion is met **live and measured**:
> T6 (dogfood #4) drove the §8.2 scalafmt CI failure → local `sbt scalafmtAll` collapse on a
> real `szork` run — no driver fix-up turn, no LLM, `attempts` unchanged, `forge stats` folding
> "1 fix-up round avoided" (dogfood-#2's \$1.78 / ~12 min / 2 rounds → a ~few-second \$0 step).
> Every Task (3.0.1/3.0.2/3.0.3, 3.1.1/3.1.2/3.1.3-Format/3.1.4, 3.2) landed; Tier-2/3 came along
> behind Tier 1. Carry-forwards placed durably (§4): the §8.3 **Build** gate resolved in its own
> slice (D2 → [`forge-design-1.8.md`](forge-design-1.8.md)); the §19 `profile.*` audit set
> reconciled into the contract through 1.13 (D3/D7/D8); the remaining **T2 / T4 / D1 / D4 / D5 / D6**
> are recorded "revisit-if" deferrals (no code or contract gap). Whole-section review done; the
> roadmap §4 sub-slice 3.0/3.1 bullets are ticked. Tier-1 types + deterministic routing originally
> **landed ahead of this plan via the Phase-3 spike** (commit `16396d2`, `forge-core` 420/420 green).

---

## 0. Exit criterion for Slice 3.0/3.1

Slice 3.0/3.1 is done when **a re-run of the dogfood-#2 formatter case routes the
scalafmt CI failure to a local `sbt scalafmtAll` — no driver fix-up turn, no LLM
call, no `maxFixupRounds` consumed — and `forge stats` records it as a fix-up round
avoided.** The \$1.78 / 12-min / 2-round waste (dogfood #2 findings #3/#4) collapses
to one ~2s local step on a live run, not just in the spike's unit proof.

Concretely:

1. `RepoProfile` model + `ProfileStore` + `contentHash`, with committed `szork`/`forge`
   fixtures proving the model expresses both real repos (**determinism before any LLM**).
2. A deterministic `RuleBasedFailureClassifier` + pure `FailureRouting.route` that the
   spike exercises against the real scalafmt / rate-limit / compile-error logs.
3. `gh run view --log-failed` plumbed into the failure edge so the classifier — and any
   `DriverFixup` prompt — sees the real `scalafmt: 1 file must be formatted`, not the
   `gh pr checks` summary (dogfood #4).
4. The §8.2 classified router wired into §11.5 so a `RunLocalCommand` route amends + pushes
   + re-polls **without** an `attempts` increment, and a `RateLimit` backs off instead of
   hard-NHI (dogfood #5).
5. `profile.snapshot` + `profile.failure_classified` (§19) recorded so the run is replayable
   against the profile/classification-as-of-then, and `forge stats` can fold "fix-ups avoided".
6. A live `extract-media-network-config` (or equivalent format-gated) re-run on `szork`
   showing the collapse end-to-end.

**Landed since (Tier 2/3):** the LLM `RepoProfiler` that *populates* `.forge/profile.json` (Task 3.0.3), the LLM
`classifyFailure` consulted on rules-`Unknown` (Task 3.1.4), and the full `ConventionLearner` at `FeatureDone` — sensor +
profile-delta apply + the proposed CLAUDE.md edit **opened as a PR** for human approval (Task 3.2, D9 resolved). **Still
deferred (see §4):** reviewer-comment mining (D8 — needs a `review.*` comment-text action kind); the §8.3 local **Build**
gate (decision D2 — needs a pre-PR fix-up FSM path).

Tiering: **Tier 1** (Tasks 3.0.1, 3.1.1, 3.1.2) is the gating deliverable — it is the
dogfood-#2 collapse, live and measured. **Tier 2** (Tasks 3.0.2, 3.0.3, 3.1.3, 3.1.4) makes
the layer self-sufficient (profiles produced by a sensor, the local shift-left gate, the LLM
tail). **Tier 3** (Task 3.2) is the learning loop.

---

## 1. Task breakdown

### Tier 1 — the dogfood-#2 collapse, live

### Task 3.1.1 — RepoProfile model + deterministic classifier + routing  ⬅ **first runnable slice** ✅ 2026-06-02 (spike `16396d2`)

The thin runnable slice that grounds the whole contract — built before any LLM, exercised
against the real dogfood-#2/#5 strings (the "thin runnable slice over thick design pass"
discipline).

Landed (`modules/forge-core/src/main/scala/io/forge/core/profile/`):

- `RepoProfile.scala` — `RepoProfile` / `WorkflowProfile` / `RepoCommand` (commands tiered by
  `Determinism` + `required`/`autofix`), `CommitIdentity`, `MergeStrategy`, `BranchModel`;
  `contentHash` (stable 16-hex SHA-256 over canonical JSON).
- `FailureClassifier.scala` — `FailureKind` / `Classification`, the `FailureClassifier` sensor
  trait, and `RuleBasedFailureClassifier` (rules over the real markers).
- `FailureRouting.scala` — `FixupRoute` + the pure, total `FailureRouting.route`.
- `RepoProfileSpikeSuite` (15 tests) — the load-bearing proof: real scalafmt log →
  `RunLocalCommand(sbt scalafmtAll)`; compile error → `DriverFixup(fullLog)`; rate-limit →
  `BackOff`; hash stability; both fixtures round-trip.

### Task 3.0.1 — ProfileStore + ForgePaths + profile.snapshot + fixtures  ✅ 2026-06-02 (spike `16396d2`)

Landed:

- `ProfileStore.scala` — `ProfileStore` + atomic `FileProfileStore` over `.forge/profile.json`
  (mirrors `FileStateCache`; decode-failure propagates — committed source of truth, not a cache).
- `ForgePaths.profileFile` (the one sanctioned `.forge/profile.json` literal; literal-sweep green).
- `ProfileSnapshot.scala` — the §19 `profile.snapshot` action draft (hash + schemaVersion).
- `src/test/resources/profiles/{szork,forge}.json` — committed fixtures; the suite proves the
  model expresses both real repos.

### Task 3.1.2 — live CI-fail routing wired into the orchestrator + dogfood re-run  ⬅ **gating** [x] (code 2026-06-02; live §8.2 trigger 2026-06-05 — T6)

The step that turns the spike's unit proof into a real run. Risk: the §11.5 failed-check edge,
`attempts` accounting, and the gh log fetch are all live-CLI / FSM-touching — exercise with a
real run, not just fakes (the `gh` wire-shape + subprocess-lifecycle discipline applies).

- [x] Plumb `gh run view <runId> --log-failed` into the §11.5 failed-check edge (real fixture
      captured first — `docs/slice-3/fixtures/gh-run-view-log-failed.scalafmt.txt`, the actual
      dogfood-#2 scalafmt failure). `CheckResult.detailsUrl` + `runId` extractor (forge-core);
      `GhClient.runViewLogFailed` + `stripAnsi` (forge-git); `writeFailures` now folds the full
      failing log into `<p>.failures.md` for every fix-up path (kills dogfood #4 universally).
- [x] Insert §8.2 classified routing in place of the blind "any required check failed →
      `attempts += 1` → fix-up": a `FailureRouter` (forge-app) over the rules classifier, wired
      into `pieceCiWatcherIO`. `RunLocalCommand` runs the autofix + pushes + keeps polling with
      **no** `attempts` increment; `DriverFixup`/`Escalate` fall back to the existing Failed →
      `PieceCiFailed` edge; `Retry`/`BackOff` keep polling. Unprofiled / `adapt.enabled = false`
      keeps the exact 1.6 blind fix-up. Profile loaded + `profile.snapshot` emitted at feature
      start (§11.0, the minimal Task 3.0.2 load path came along since routing needs it).
- [x] Emit `profile.failure_classified` (§19) before acting; `forge stats` folds a "fix-ups
      avoided" row from `route == "RunLocalCommand"`.
- [x] Re-run a format-gated feature on `szork` with a hand-authored `.forge/profile.json` (**live
      run done 2026-06-03 — dogfood #3, `music-poll-config`, Mode A `localGate=false`**).
      **CAVEAT — §8.2 did NOT trigger live:** the implement *and* fix-up drivers both produced
      scalafmt-conformant code, so no CI formatting check failed and the router had nothing to
      route. A natural §8.2 trigger is stochastic (needs the driver to mis-format, as in dogfood
      #2). What *was* validated live: `profile.snapshot` load/hash (Task 3.0.2), the full §11
      lifecycle on a profiled run, and Slice-2.0 cost/session observability. The §8.2 routing
      itself stays exhaustively unit-proven, **including end-to-end against the real dogfood-#2
      scalafmt failing-check log** (`OrchestratorCiRoutingSuite` / `FailureRouterSuite`). Ticked as
      "live re-run performed + spine validated + §8.2 unit-proven"; the live §8.2 *trigger* is
      carried forward as **T6** below. Full write-up + findings (incl. a finding-#5 recurrence and
      an operator concurrent-git race that pushed the piece commit to `main`):
      [`dogfood/music-poll-config.md`](dogfood/music-poll-config.md);
      runbook [`dogfood/t5-cifail-routing-runbook.md`](dogfood/t5-cifail-routing-runbook.md).

**Exit:** the §0 criterion — **met live 2026-06-05** (T6 resolved: a guaranteed-reflow feature fired
§8.2 on a real `szork` run; §4 **T6**). Phase 3 has teeth on a live run, not only against the unit
fixture.

### Tier 2 — self-sufficiency

### Task 3.0.2 — profile load + snapshot at feature start (§11.0)  ✅ 2026-06-02

- [x] At orchestrator startup (post-lock, pre-first-transition): `ProfileStore.load()`; on
      `Some`, append `profile.snapshot` and thread the `RepoProfile` as a read-only input to the
      router; on `None`, run unprofiled (1.6 behaviour). Landed as `Orchestrator.resolveProfile`
      (gated on `adapt.enabled`, resolved **once** per run); `run` emits the snapshot from the
      resolved value and hands it to a new `driveWith(feature, profile)` which threads
      `Option[RepoProfile]` down `loop → sourceIO → watcherIO → pieceCiWatcherIO → ciPollToEvent →
      routeCiFailure`. `routeCiFailure` no longer re-loads per failure — the `profileStore.load()`
      and the `!adapt.enabled` short-circuit it carried in 3.1.2 are now folded into the single
      resolve (a disabled run resolves to `None` → blind 1.6 fix-up). `drive` resolves the profile
      itself so the direct-`drive` e2e callers (incl. `OrchestratorCiRoutingSuite`) are unchanged.
- [x] Assert `Fsm.transition` never reads `ProfileStore` (profile reaches it only as input) —
      the replayability invariant. `ProfileReplayInvarianceSuite` (forge-core): **R1** a happy-path
      trajectory with `profile.snapshot` + `profile.failure_classified` injected folds to the
      identical final `Feature` as without them (both are audit-only no-op projections in
      `Replay`'s default branch); **R2** a `ForgePathsSuite`-style source sweep that no
      `io.forge.core.fsm` source references the `io.forge.core.profile` package (the FSM cannot
      read the profile even by accident — enforced at the type level).

### Task 3.0.3 — RepoProfiler LLM role (forge-agents)  ✅ 2026-06-02

- [x] `Connector.profileRepo` + `~/.forge/schemas/repo-profile.json` Native schema + a
      `ReviewDecoders.repoProfile` decoder, routed reviewer-side via `Role.pairFor` (§7.11). Landed as a
      reviewer-side one-shot mirroring `reviewDesign`/`reviewPr`/`refine`: `RepoProfilerInput` + `RepoFile`
      (forge-agents), `ReviewerPrompts.repoProfileBody`, the `repo-profile.{claude,codex}.md` prompts +
      `repo-profile.json` schema (in `assets/reviewer/`, registered in `AssetInstaller.ShippedAssets` +
      `ConnectorFactory.reviewerAssets`), and `profileRepo` on the `Connector` trait + both connectors +
      `ReviewerCall`/`RealReviewerCall`/`RetryingReviewerCall` (wall-clock-capped). **Decision D4** below:
      `schemaVersion` is **not** in the LLM schema (Forge stamps `CurrentSchemaVersion` in the decoder — a
      versioning concern, not an LLM judgment); `commitIdentity` *is* in the schema, defaulting to `forge[bot]`
      via the prompt. The "real structured-output sample" is grounded two ways: the decoder is proven against
      the committed `szork`/`forge` fixtures (`RepoProfileDecoderSuite`), and a live Claude/Codex capture is the
      opt-in `forge-it` `RepoProfilerSmokeSuite` (`FORGE_IT_RUN_PROFILER_SMOKE=1`).
- [x] `forge profile` writes `.forge/profile.json` (human-reviewable committed diff). Landed as the feature-less
      state-changing command `ForgeCommand.Profile` → `ProfileCommand` (gathers `AGENTS.md`/`CLAUDE.md`/top-level
      build files/`.github/workflows/*` into a `RepoProfilerInput`, picks the reviewer-side connector via
      `Role.pairFor(config.mode, …)`, runs `profileRepo` under the 3-min cap, persists via `FileProfileStore`,
      prints a summary). Validated against the fixtures: `RepoProfileDecoderSuite` proves the decoder reproduces
      the exact `szork`/`forge` `RepoProfile` upickle deserialises; `ProfileCommandSuite` proves the
      gather→perceive→persist pipeline end-to-end with a fake reviewer.

### Task 3.1.3 — local format gate, pre-PR (§8.3) — **Format landed; Build deferred** [ ]

- [x] **Format autofix gate (the dogfood-#3 collapse).** In the §11.4-step-6 path
      (`ClassifyCommitOpenPr`), **before** the piece commit, run the profile's `required` /
      `Deterministic` / in-place `autofix` `Format` commands on the working tree, so the driver's
      non-conformant output is rewritten in place and the eventual classify → commit picks up the
      conformant version — the piece commit is format-clean before it ever reaches CI, zero
      round-trip, no `attempts`. Landed as `SideEffects.runLocalFormatGate` (executed via the
      proven `runCommand` seam) + the orchestrator's `runLocalFormatGate` / `localFormatGateCommands`
      (pure profile filter) wired into `runSettleEffect`'s `ClassifyCommitOpenPr` case (profile
      threaded through `handleWinner` / `postSettleRecover`). A `profile.local_gate` audit action is
      recorded (a no-op `Replay` projection — proven in `ProfileReplayInvarianceSuite` R1).
      **Decision D1** (format-before-commit, not commit-then-`--amend`) and the §19 schema gap noted
      in §4. Tests: `OrchestratorLocalGateSuite` (e2e: gate runs once pre-PR with the profile command,
      `attempts` stays 0, action logged; unprofiled / `localGate = false` ⇒ gate never runs, nothing
      logged) + `RealSideEffectsSuite` real-command-execution units (runs in repo root; short-circuits
      on a non-zero exit). `forge-app` 384 / `forge-core` 424 green; `scalafmtCheckAll` clean.
- [x] Gated by `adapt.localGate` (+ `adapt.autofix`); `Heuristic` commands are never run locally
      (the `Deterministic` filter excludes them); `adapt.enabled = false` ⇒ `None` profile ⇒ no-op.
- [x] **Build gate — ✅ landed 2026-06-03 in its own slice ([`design-3.1-build-gate.md`](design-3.1-build-gate.md), decision D2).**
      A local `Build` `CodeFix` "routes to a pre-PR driver fix-up" needed new FSM machinery — the only fix-up
      states (`PieceCiFailed` / `PieceFixingUp`) all carry a PR number; there was no pre-PR fix-up path. That
      slice adds two pre-PR states (`PieceBuildFailed` / `PieceBuildFixingUp`) + a `LocalBuildFailed` event
      (the §11/1.8 contract change), catching a compile error pre-PR and fixing it with a re-gating driver
      fix-up — without consuming a CI fix-up round. Contract: [`forge-design-1.8.md`](forge-design-1.8.md).

### Task 3.1.4 — LLM classifyFailure on Unknown  ✅ 2026-06-02

- [x] `Connector.classifyFailure(FailureClassifierInput): IO[Classification]` consulted **only** when the rules
      `RuleBasedFailureClassifier` returns `Unknown` (the §7.11 cost lever — the rules baseline handles every dogfood
      case for free; the LLM tail pays a reviewer-call only for the genuinely ambiguous failure). A reviewer-side
      one-shot mirroring `profileRepo` exactly: `failure-classifier.json` Native schema + `failure-classifier.{claude,
      codex}.md` system prompts (in `assets/reviewer/`, registered in `AssetInstaller.ShippedAssets` +
      `ConnectorFactory.reviewerAssets` + `ReviewerAssets.classifyFailure`), a stable `ReviewerPrompts.classifyFailureBody`
      (renders the failing log + the profile's Known commands so the model can name a `suggested` kind the repo
      actually has), and a `ReviewDecoders.failureClassification` decoder (reuses the `forge-core`
      `FailureKind`/`CommandKind` `fromString` parsers so wire strings can't drift). The orchestrator's pure
      `FailureRouter` gained `routeFrom(classification, …, source)`; `Orchestrator.maybeLlmClassify` consults the sensor,
      re-routes its proposal through `routeFrom` with `source = "llm"`, and records `profile.failure_classified`
      `source: "llm"` (the action already carried the field). The IO stays out of the pure router (the §8.2 "decision in
      orchestrator, pure router" split). **Decision D6** below: a `Timeout`/adapter/process failure keeps the rules
      `Escalate` — Forge never blocks a feature on a stalled sensor.
- [x] Gated by `adapt.llmClassifierOnUnknown` (already in `AdaptConfig`, default `true`); wall-clock-capped via the
      `ReviewerCall` boundary (`RealReviewerCall.classifyFailure` under the 3-min cap; `RetryingReviewerCall` shares the
      `reviewRetries` process-failure budget). `adapt.enabled = false` ⇒ no profile ⇒ blind 1.6 fix-up (no consultation).
      Tests: `FailureClassificationDecoderSuite` (9 — the decode + malformed Lefts), Claude+Codex `classifyFailure`
      fake-CLI end-to-end, `RetryingReviewerCallSuite` (shares `reviewRetries`), `FailureRouterSuite` (`routeFrom`
      source=llm + autofix degradation), `OrchestratorCiRoutingSuite` (e2e: rules-Unknown → LLM pins → local autofix,
      `attempts` 0, `source=llm`; + LLM-Timeout → blind Escalate, `source=rules`), and the opt-in `forge-it`
      `FailureClassifierSmokeSuite` (real Claude/Codex capture, `FORGE_IT_RUN_CLASSIFIER_SMOKE=1`).

### Tier 3 — the learning loop

### Task 3.2 — ConventionLearner at FeatureDone (§11.7) — **landed** [x] 2026-06-03

- [x] `Connector.learnConventions(ConventionLearnerInput): IO[ConventionDeltas]` — a reviewer-side one-shot mirroring
      `profileRepo`/`classifyFailure` exactly: `convention-deltas.json` Native schema + `learn-conventions.{claude,
      codex}.md` prompts (in `assets/reviewer/`, registered in `AssetInstaller.ShippedAssets` +
      `ConnectorFactory.reviewerAssets`), a stable `ReviewerPrompts.learnConventionsBody`, and a
      `ReviewDecoders.conventionDeltas` decoder (reuses the `repo-profile.json` per-command decode so the enum wire
      strings can't drift). Rides the `ReviewerCall`/`RealReviewerCall`/`RetryingReviewerCall` wall-clock boundary
      (shares the `reviewRetries` budget). Output `ConventionDeltas` (forge-core): `addCommands` (additive, deduped) +
      a nullable `claudeMdProposal` + `summary`.
- [x] Invoked **out-of-band on the transition to `FeatureDone`** (`Orchestrator.maybeLearnConventions`, hooked at the
      tail of `driveWith`): advisory and **never-blocking** (`handleErrorWith` swallows; the §14.2 refinery posture),
      gated on `adapt.enabled` + `adapt.conventionLearner`. The **§7.11 cost lever**: consulted **only** when the
      feature actually hit a classified gate failure (`Orchestrator.observedFailures` mines the §19
      `profile.failure_classified` actions from the log) — a clean run learns nothing and spends no reviewer call.
- [x] `RepoProfile` deltas applied via `ProfileStore.save` (additive command merge, deduped by `(kind, argv)`; the save
      is skipped when nothing is fresh).
- [x] **The proposed CLAUDE.md edit is opened as a PR for human approval (§11.7, decision D9 resolved 2026-06-03).**
      `SideEffects.openConventionsPr` (in `RealSideEffects`) branches from base, appends the convention to CLAUDE.md
      (creating it if absent), commits, pushes, and `gh pr create`s — **never merges it** (the human does); idempotent
      (an already-open conventions PR for the branch is reused). The open is advisory: on failure (dirty tree / push
      rejected / gh down) the orchestrator **falls back to persisting** the proposal to
      `.forge/specs/<feature>/audit/learned-conventions.md` so it is never lost. **No autonomous doc mutation** — Forge
      opens a PR, never a merge. A `profile.conventions_learned` audit action (no-op `Replay` projection, D7) records the
      opened PR number (or `null` on the fallback path). Tests: `ConventionDeltasSuite` (forge-core merge/dedup),
      `ConventionDeltasDecoderSuite`, Claude+Codex `learnConventions` fake-CLI e2e, `RetryingReviewerCallSuite` (shares
      `reviewRetries`), `RealSideEffectsSuite` (`openConventionsPr` real git sequence + idempotent reuse),
      `OrchestratorConventionLearnerSuite` (e2e: FeatureDone + classified failure → delta saved + PR opened + number
      logged; PR-open failure → persist-locally fallback; `conventionLearner = false` ⇒ never consulted; no-failure ⇒
      cost-lever skip), and the opt-in `forge-it` `ConventionLearnerSmokeSuite` (`FORGE_IT_RUN_LEARNER_SMOKE=1`).
- [x] **`recurring reviewer comments` mining (decision D8) — ✅ resolved 2026-06-04.** The action log now captures
      reviewer comment text via the new §19 `review.request_changes` kind, so the §7.11 cost lever widened to gate on
      *failures OR reviewer comments* and the learner input gained a `reviewerComments` channel. Contract:
      [`forge-design-1.13.md`](forge-design-1.13.md) (§7.11/§11.7/§19). See the D8 entry in §4 below.

---

## 2. Order of work

3.1.1 ✅ → 3.0.1 ✅ → 3.1.2 ✅ (code 2026-06-02; live §8.2 trigger 2026-06-05 — T6) → 3.0.2 ✅ → 3.1.3 ✅
(Format gate; Build gate → own slice, D2) → 3.0.3 ✅ → 3.1.4 ✅ → 3.2 ✅ (sensor + profile deltas + CLAUDE.md PR;
D9 resolved) → 3.1.3-Build ✅ (own slice — D2) → T5 ✅ → T6 ✅ → D8 ✅ → **slice closed 2026-06-05**.
Tier-1 closed the slice's exit criterion; Tier 2/3 landed incrementally behind it.

---

## 3. Status log

- **2026-06-05 — Slice 3.0/3.1 CLOSED (whole-section review).** With T6 (the last gating carry-forward)
  resolved, the §0 exit criterion is met live and measured, so the slice closes. Whole-section review:
  every Task landed and is ticked — **3.0.1** (`ProfileStore`/`ForgePaths`/`profile.snapshot`/fixtures),
  **3.0.2** (profile load + snapshot at feature start), **3.0.3** (`RepoProfiler` LLM sensor + `forge profile`),
  **3.1.1** (model + rules classifier + routing), **3.1.2** (live CI-fail routing wired + live §8.2 trigger),
  **3.1.3** (Format gate; Build gate split into its own slice, [`design-3.1-build-gate.md`](design-3.1-build-gate.md), D2),
  **3.1.4** (LLM `classifyFailure` on `Unknown`), **3.2** (`ConventionLearner` at `FeatureDone` — sensor + profile
  deltas + CLAUDE.md PR, D9; reviewer-comment mining, D8). Build re-verified docs-only-green
  (`scalafmtCheckAll; compile; test` exit 0; forge-core 430, forge-agents 235 re-run, the rest cached-green from
  the D8 commit). **Carry-forwards placed durably** (the §4 list is the durable home; close-out walk):
  - **Resolved in-flight:** D2 (Build gate → own slice / 1.8), D7 (`profile.conventions_learned` → 1.12 §19),
    D8 (reviewer-comment mining → 1.13), D9 (conventions PR-open). The full Phase-3 `profile.*` audit set
    (`snapshot` / `failure_classified` / `local_gate` / `conventions_learned`) plus `review.request_changes` is in
    the contract through 1.13 — **no §19 contract gap remains** (D3's `profile.local_gate` is enumerated in 1.8 §19).
  - **Carried forward as recorded "revisit-if" deferrals** (no code or contract gap, each with a disposition in §4):
    **T2** (`profile.snapshot` records hash + schemaVersion, not the full profile — 1.7 §19), **T4** / **D1**
    (`RunLocalCommand` fresh `style(...)` commit vs the pre-push Format gate's fold-into-piece-commit — 1.7 §8.3/§11.4;
    the two are deliberately different because §8.2's target is already pushed), **D4** (decoder stamps `schemaVersion`;
    `commitIdentity` perceived with a `forge[bot]` default — 1.7 §6/§7.11), **D5** (`forge profile` is the first
    feature-less state-changing command, takes the §13 lock — 1.7 §15), **D6** (LLM `classifyFailure` gated on the
    rules `Unknown` sentinel + degrades to rules `Escalate` on stall — 1.7 §7.11/§8.2). These are design records, not
    open work; promote to a contract revision only if a future need (listed in each entry's "Revisit if…") arises.
  The roadmap §4 sub-slice **3.0** and **3.1** bullets are now ticked ✅. The Phase-3 *overall* exit criterion (a live
  run on a new, unseen, non-Scala repo with zero hardcoded-config edits) remains the gate for the **whole phase** —
  the remaining sub-slices (3.4 `ConventionLearner` was 3.2; the cross-stack live demo) are the next Phase-3 work.

- **2026-06-05 — T6 resolved: the §8.2 CI-fail → local-autofix routing fired *live* (dogfood #4,
  `adventure-gen-retry-config`).** Drove a real end-to-end Forge run on `llm4s/szork` (Mode A,
  `adapt.localGate = false`, `branchModel = git_flow`) with a feature **engineered to force a guaranteed
  scalafmt reflow** — a piece acceptance criterion mandating inline `@param`/`@return` ScalaDoc, which
  scalafmt 3.7.17 deterministically splits (proven against szork's real `.scalafmt.conf` before the run).
  The implement driver wrote the inline form; with the local gate off it survived to CI; `backend`'s
  `scalafmtCheckAll` failed; the §8.2 router classified it `deterministic_fix` (conf 0.97, **source =
  rules**, no LLM tail), ran `RunLocalCommand(sbt scalafmtAll)`, committed `style(...) 6b5433ed1`, pushed,
  CI re-ran **green**, and the FSM advanced `PieceAwaitingCi → PieceAwaitingReview` with **no
  `PieceCiFailed` and `attempts` = 0**. `forge stats` records *"1 fix-up round avoided."* This is the §0
  exit criterion live and measured — dogfood-#2's **$1.78 / ~12 min / 2 rounds** collapsed to a
  ~few-second local step at **$0 driver cost**. Evidence:
  [`dogfood/t6-run/adventure-gen-retry-config/`](dogfood/t6-run/adventure-gen-retry-config/); write-up:
  [`dogfood/adventure-gen-retry-config.md`](dogfood/adventure-gen-retry-config.md). The §4 **T6** entry is
  flipped to resolved. Two by-design observations (the engineered inline-`@param` criterion is
  self-contradictory with scalafmt's split form, so the run looped on the *code-review* gate after the
  §8.2 heal — a spec-design lesson, not a §8.2 defect; and szork's third-party Codex auto-reviewer injected
  one bounded design-PR feedback round) are in the write-up's findings, not Forge bugs. With T6 closed, the
  §0 exit criterion is met live; the roadmap §4 bullet stays **unticked** pending only the whole-section
  review.

- **2026-06-04 — D8 resolved: reviewer-comment mining (the `ConventionLearner`'s second signal).** Closed the deferred
  half of Task 3.2: the learner now mines *recurring reviewer comments* alongside failure→remedy patterns, completing the
  §7.11/§11.7 "failure patterns + recurring reviewer comments" framing. Added the backing signal first (the
  "capture real shapes, don't invent" discipline): a new §19 `review.request_changes` audit kind
  (`io.forge.core.review.ReviewRequestedChangesAction`, a no-op `Replay` projection) appended by
  `Orchestrator.logReviewerRequestChanges` whenever a design/code reviewer one-shot returns `RequestChanges` with a
  non-empty blocker list, carrying `{ gate, round, blockers }`. Then widened the consumer:
  `Orchestrator.observedReviewerComments` mines it into `Vector[ObservedReviewerComment]`; `ConventionLearnerInput` gained
  a `reviewerComments` channel (rendered into `learnConventionsBody` + both `learn-conventions.*` prompts); the §7.11 cost
  lever in `maybeLearnConventions` widened to `failures.nonEmpty || reviewerComments.nonEmpty`. Replay/§6.1 invariant
  preserved (the FSM consumes the verdict via `DesignReviewReceived`/`CodeReviewVerdict`, never the audit row). Contract:
  the new [`forge-design-1.13.md`](forge-design-1.13.md) (§7.11/§11.7/§19, standalone-by-freeze over 1.12; README
  live-contract pointer updated). Tests: `ReviewRequestedChangesActionSuite`, `ProfileReplayInvarianceSuite` R1 extended,
  `OrchestratorConventionLearnerSuite` +2 (the D8 e2e + the per-verdict append/skip seam); the three connector / retrying
  / smoke construction sites updated for the new field. `forge-core` + `forge-agents` + `forge-app` full unit suites green;
  `scalafmtCheckAll` clean. The §4 D8 entry is flipped to resolved; the roadmap §4 bullet stays **unticked** (slice closes
  only after the whole-section review + the remaining live-demo carry-forwards T6).

- **2026-06-04 — D7 resolved: `profile.conventions_learned` enumerated in the §19 contract
  ([`forge-design-1.12.md`](forge-design-1.12.md)).** A pure spec-text reconciliation — no code change. The
  `ConventionLearner` (Task 3.2) has emitted the `profile.conventions_learned` audit action since it landed, but no
  contract revision enumerated the kind (1.7 §19 listed only `profile.snapshot` / `profile.failure_classified`; 1.8 §19
  added `profile.local_gate`, decision D3, but not this sibling). 1.12 is a focused standalone-by-freeze revision
  restating only §19 to add the kind with its real payload (`{ addedCommands: [string…], hasClaudeMdProposal: bool,
  claudeMdPrNumber: int|null, summary }`, captured from `ConventionsLearnedAction`), documenting it as a no-op `Replay`
  projection (the replayability invariant holds). With 1.12 the full Phase-3 `profile.*` audit set
  (`snapshot` / `failure_classified` / `local_gate` / `conventions_learned`) is in the contract. README live-contract
  pointer + the `ConventionsLearnedAction` D7-gap docstring updated to point at 1.12; the build is unchanged (no source
  touched beyond the doc comment). Closes the last open §19 schema gap.

- **2026-06-04 — T6 finding #2 resolved: a pre-commit `HEAD` assertion guards every commit site.**
  `RealSideEffects.assertHeadIs(expected)` runs immediately before each `git.commit` (design /
  `classifyCommitOpenPr` / `classifyCommitPush` / `runLocalAutofixAndPush` / `openConventionsPr`) and
  refuses with a `Left` → `HarnessError` → NHI when `HEAD` is not the expected design/piece/conventions
  branch — closing the dogfood-#3 concurrent-git race where an operator excursion left `HEAD` on `main` and
  Forge's piece commit was pushed straight to the shared remote, bypassing the PR. The expected branch is
  derived from `feature.manifest` at each site; a detached `HEAD` is refused via `git.currentBranch`'s
  `ParseFailure`. Guarding *all* commit sites (not just the cited piece one) follows the
  invariant-enforcement discipline. New test in `RealSideEffectsSuite` (HEAD-on-wrong-branch → Left, no
  commit/push); the test-local `FakeGitClient` now tracks the checked-out branch. `forge-app` + `forge-git`
  green, `scalafmtCheckAll` clean. **Both T6 runnable findings (#5, #2) are now resolved**; the only T6
  residual is the live §8.2 *trigger* demonstration (stochastic / human-gated). Roadmap §4 stays **unticked**.

- **2026-06-04 — T6 finding #5 resolved: transient §9 poll errors back off instead of hard-NHI'ing.** A transient
  GitHub 503 on the `PRWatcher` PR-state poll (which recurred live in dogfood #2 and #3, hard-routing
  `PieceAwaitingReview → NeedsHumanIntervention` after CI was already green) is now a soft, retry-worthy signal.
  `RealPRWatcher.pollOnce` maps `GhError.Transient` → the new `PollResult.TransientError`; the watcher backs off
  (`transientBackoff`) and keeps polling, promoting to `Failed` only after `consecutiveTransientFailuresBeforeFailing`
  consecutive transients (default 3 — the S3-4 rate-limit-cliff twin, with independent counters). Both orchestrator
  poll consumers (`pollResultToEvent`, `ciPollToEvent`) absorb the new signal (`None`, keep polling); fatal `gh`
  errors (`NotFound`/`Unauthorized`/`ParseFailure`) still escalate to NHI unchanged. New tests: `PRWatcherTransientSuite`
  (4), `OrchestratorTransientPollSuite` (2: absorb→`FeatureDone`, fatal→NHI), `GhErrorClassifierSuite` (+1: 503→Transient);
  `PRWatcherBasicSuite` / `PRWatcherRateLimitSuite` updated for the new contract. `forge-git` + `forge-app` green,
  `scalafmtCheckAll` clean. Contract: design-rationale **S3-4b**. The other T6 half (live §8.2 trigger) and the
  pre-commit `HEAD` assertion remain open; the roadmap §4 bullet stays **unticked**.

- **2026-06-03 — Task 3.1.2 live re-run performed (dogfood #3, `music-poll-config`); §8.2 trigger NOT exercised
  (carried forward as T6).** Drove a real format-gated feature end-to-end on `llm4s/szork` in Mode A
  (`adapt.localGate=false`) with a hand-authored `.forge/profile.json`. **Validated live:** `profile.snapshot`
  load+hash (`bd3d39fe…`), the full §11 lifecycle on a profiled run (spec → design review → PR #16 merged →
  implement → PR #17 → CI → review → fix-up → CI → review), and Slice-2.0 `cost.update`/`session.complete`
  observability (implement `$0.24`, fix-up `$0.42`). **Not achieved:** the §8.2 CI-fail → local-autofix routing
  never fired — the implement *and* fix-up drivers both produced scalafmt-conformant code (`scalafmtCheckAll`
  passes on the piece branch), so no formatting check failed. A natural §8.2 trigger is stochastic (dogfood #2's
  was a scaladoc reflow). The routing stays exhaustively unit-proven, incl. end-to-end against the **real**
  dogfood-#2 scalafmt failing-check log. The run also (a) **reproduced dogfood-#2 finding #5** (transient GitHub
  503 on the §9 poll → hard NHI) and (b) hit an **operator concurrent-git race** (a `scalafmtCheckAll` excursion
  in the live worktree left `HEAD` on `main`, so Forge's piece commit `3b1a072` was pushed directly to `main`,
  bypassing PR #17). Cleanup: `3b1a072` kept on `main` (correct, CI green), PR #17 closed redundant, feature
  `abandon`ed, stray stash dropped. Tooling note: `forge spec` must be driven via direct `java` launch, not
  `sbt -batch run` (batch closes the forked app's stdin → `/done` aborts). Task 3.1.2 box ticked with the §8.2
  caveat; **T6** opened below; roadmap §4 stays unticked. Write-up: [`dogfood/music-poll-config.md`](dogfood/music-poll-config.md).

- **2026-06-03 — Task 3.2 completed (D9 resolved): the `ConventionLearner`'s proposed CLAUDE.md edit is now opened as a
  PR.** The deferred half of Task 3.2: `SideEffects.openConventionsPr` (`RealSideEffects`) branches from base, appends the
  proposed convention to CLAUDE.md (creating it if absent), commits, pushes, and `gh pr create`s — mirroring
  `commitDesignAndOpenPr`, idempotent (reuses an already-open conventions PR for the branch). `Orchestrator.applyConventionDeltas`
  opens the PR on a `claudeMdProposal` and records its number in `profile.conventions_learned`; on a `Left` it falls back
  to persisting the proposal to the audit dir (so it is never lost). Advisory/never-blocking throughout (`handleErrorWith`);
  **no autonomous doc mutation** — Forge opens a PR, never merges. New/updated tests: `RealSideEffectsSuite` (+2 —
  `openConventionsPr` real git sequence + idempotent reuse), `OrchestratorConventionLearnerSuite` (PR-opened-and-numbered +
  the new PR-open-failure → persist-fallback case). Full build green (`forge-core` 429, `forge-agents` 236, `forge-app`
  402, `forge-it` compiles); `scalafmtCheckAll` clean. **Task 3.2 header now ticked**; the roadmap §4 bullet stays
  **unticked** until the §0 live re-run (T5) + the whole-section review. Merged to `main` (the branch work was
  fast-forwarded onto `main` at the user's request before this follow-up).

- **2026-06-03 — Task 3.2 landed (sensor + profile deltas + persisted CLAUDE.md proposal; auto-PR deferred): the §11.7
  `ConventionLearner` at `FeatureDone`.** The Tier-3 learning loop's first runnable: `Connector.learnConventions(
  ConventionLearnerInput): IO[ConventionDeltas]` is a reviewer-side one-shot mirroring `profileRepo`/`classifyFailure`
  exactly — `convention-deltas.json` Native schema + `learn-conventions.{claude,codex}.md` prompts (registered in
  `AssetInstaller.ShippedAssets` + `ConnectorFactory.reviewerAssets` + a new `ReviewerAssets.learnConventions` field) +
  `ReviewerPrompts.learnConventionsBody` + a `ReviewDecoders.conventionDeltas` decoder (reuses the `repo-profile.json`
  per-command decode so enum wire strings can't drift), riding the `ReviewerCall`/`RealReviewerCall`/
  `RetryingReviewerCall` wall-clock boundary (shares the `reviewRetries` budget). Output `ConventionDeltas` (forge-core):
  additive deduped `addCommands` + a nullable `claudeMdProposal` + `summary`. The orchestrator's `maybeLearnConventions`
  (hooked at the tail of `driveWith`) consults it **out of band on the transition to `FeatureDone`** — advisory and
  never-blocking (`handleErrorWith`; §14.2 posture), gated on `adapt.enabled` + `adapt.conventionLearner`. The §7.11
  **cost lever**: consulted **only** when the run actually hit a classified gate failure (`Orchestrator.observedFailures`
  mines the §19 `profile.failure_classified` actions) — a clean run spends no reviewer call. On a settled proposal: fresh
  command deltas merge into the committed profile via `ProfileStore.save` (skipped when nothing is fresh), the proposed
  CLAUDE.md edit is persisted to `.forge/specs/<feature>/audit/learned-conventions.md` for human review (**no autonomous
  doc mutation / no PR**), and a `profile.conventions_learned` audit action records it (a no-op `Replay` projection — D7).
  **Decisions opened:** D7 (`profile.conventions_learned` is a new §19 `profile.*` kind to enumerate in the next contract
  revision), D8 (reviewer-comment mining deferred — the action log captures classified failures, not reviewer comment
  text, so the cost lever gates on `profile.failure_classified` today), D9 (the §11.7 auto-PR-open of the CLAUDE.md
  proposal is deferred — an outward-facing action + git machinery off the main flow; this pass persists the proposal for
  human review instead). New tests: `ConventionDeltasSuite` (forge-core, 5), `ConventionDeltasDecoderSuite` (7),
  Claude+Codex `learnConventions` fake-CLI e2e, `RetryingReviewerCallSuite` (+1 shares `reviewRetries`),
  `OrchestratorConventionLearnerSuite` (3 — e2e collapse + gated-off + cost-lever skip), and the opt-in `forge-it`
  `ConventionLearnerSmokeSuite` (`FORGE_IT_RUN_LEARNER_SMOKE=1`). Full build green (`forge-core` 429, `forge-agents` 236,
  `forge-app` 399, `forge-it` compiles); `scalafmtCheckAll` clean. The Task 3.2 header + the roadmap §4 bullet stay
  **unticked** until the auto-PR-open (D9) + the §0 live re-run (T5) land.

- **2026-06-02 — Task 3.1.4 landed: the LLM `FailureClassifier` sensor consulted on rules-`Unknown` (the §7.11 cost
  lever).** `Connector.classifyFailure(FailureClassifierInput): IO[Classification]` is a reviewer-side one-shot mirroring
  `profileRepo` exactly — `failure-classifier.json` Native schema + `failure-classifier.{claude,codex}.md` prompts (in
  `assets/reviewer/`, registered in `AssetInstaller.ShippedAssets` + `ConnectorFactory.reviewerAssets` +
  `ReviewerAssets.classifyFailure`) + a stable `ReviewerPrompts.classifyFailureBody` (renders the failing log + the
  profile's Known commands so the model can name a `suggested` kind the repo actually exposes) + a
  `ReviewDecoders.failureClassification` decoder (reuses the `forge-core` `FailureKind`/`CommandKind` `fromString`
  parsers so wire strings can't drift). It rides the existing `ReviewerCall`/`RealReviewerCall`/`RetryingReviewerCall`
  wall-clock boundary (sharing the `reviewRetries` process-failure budget). The consultation is wired **only** on the
  rules-`Unknown` path: the pure `FailureRouter` gained `routeFrom(classification, …, source)`, and the orchestrator's
  `maybeLlmClassify` (gated on `adapt.llmClassifierOnUnknown`, default `true`) consults the sensor, re-routes its
  proposal through `routeFrom` with `source = "llm"`, and emits `profile.failure_classified{source:"llm"}` (the §19
  action already carried the field). The IO stays out of the pure router — the §8.2 "decision in orchestrator, pure
  router, execution in SideEffects" split. **Decision D6**: a `Timeout`/adapter/process failure keeps the rules
  `Escalate` — Forge never blocks a feature on a stalled sensor (it degrades to the safe human-escalation the rules
  already chose). New tests: `FailureClassificationDecoderSuite` (9), Claude+Codex `classifyFailure` fake-CLI e2e,
  `RetryingReviewerCallSuite` (shares `reviewRetries`), `FailureRouterSuite` (`routeFrom` source=llm + autofix
  degradation), `OrchestratorCiRoutingSuite` (+2: rules-Unknown → LLM pins → local autofix, `attempts` 0, `source=llm`;
  + LLM-Timeout → blind Escalate, `source=rules`), and the opt-in `forge-it` `FailureClassifierSmokeSuite`
  (`FORGE_IT_RUN_CLASSIFIER_SMOKE=1`). Full build green (`forge-core` 424, `forge-agents` 227, `forge-app` 395,
  `forge-it` compiles); `scalafmtCheckAll` clean. The Task 3.1.4 header is ticked; the roadmap §4 bullet stays
  **unticked** (slice closes only after the §0 live re-run + the whole-section review).

- **2026-06-02 — Task 3.0.3 landed: the `RepoProfiler` LLM sensor + `forge profile` command.** The first agentic
  *sense* that produces a profile (Tier 2 — it closes carry-forward T1's "profiles are hand-authored until 3.0.3").
  `Connector.profileRepo(RepoProfilerInput): IO[RepoProfile]` is a reviewer-side one-shot mirroring
  `reviewDesign`/`reviewPr`/`refine` exactly: a `repo-profile.json` Native schema (Claude `--json-schema`, Codex
  `--output-schema`) + `repo-profile.{claude,codex}.md` system prompts (in `assets/reviewer/`, registered in
  `AssetInstaller.ShippedAssets` + `ConnectorFactory.reviewerAssets`) + a stable `ReviewerPrompts.repoProfileBody` +
  a `ReviewDecoders.repoProfile` decoder (reuses the `forge-core` enum `fromString` parsers so the accepted wire
  strings can't drift from the model). It rides the existing `ReviewerCall`/`RealReviewerCall`/`RetryingReviewerCall`
  wall-clock boundary. `forge profile` is a new **feature-less** state-changing command (`ForgeCommand.Profile` →
  `ProfileCommand`): it gathers `AGENTS.md`/`CLAUDE.md`/top-level build files/`.github/workflows/*` into the input,
  picks the **reviewer-side** connector via `Role.pairFor(config.mode, …)`, runs the sensor under the 3-min cap, and
  writes the committed `.forge/profile.json` via `FileProfileStore`. Decisions **D4** (`schemaVersion` stamped by the
  decoder, not asked of the LLM; `commitIdentity` in-schema with a `forge[bot]` default) and **D5** (`forge profile`
  is feature-less but takes the process lock for its write). New tests: `RepoProfileDecoderSuite` (the decoder
  reproduces the exact `szork`/`forge` `RepoProfile`s upickle deserialises, + malformed-input Lefts),
  `ProfileCommandSuite` (gather→perceive→persist e2e with a fake reviewer; failure writes nothing), Claude+Codex
  `profileRepo` fake-CLI end-to-end tests, `CliParserSuite` profile rows, and the opt-in `forge-it`
  `RepoProfilerSmokeSuite` (real Claude/Codex capture, `FORGE_IT_RUN_PROFILER_SMOKE=1`). `forge-agents` 216,
  `forge-app` 390, `forge-it` compiles; full build green, `scalafmtCheckAll` clean. The Task 3.0.3 header is ticked;
  the roadmap §4 bullet stays **unticked** (slice closes only after the §0 live re-run + the whole-section review).

- **2026-06-02 — Task 3.1.3 Format gate landed (Build deferred): the §8.3 shift-left format gate, pre-PR.**
  The dogfood-#3 collapse, killed at the source: in the §11.4-step-6 `ClassifyCommitOpenPr` path, **before** the
  piece commit, the orchestrator runs the profile's `required` / `Deterministic` / in-place `autofix` `Format`
  commands on the working tree (`SideEffects.runLocalFormatGate`, executed via the existing `runCommand` seam), so
  the driver's non-conformant output is rewritten in place and the piece commit is format-clean before it ever
  reaches CI — zero round-trip, no `attempts`. The decision (which commands; `adapt.localGate` / `adapt.autofix`
  gating) is the pure orchestrator filter `localFormatGateCommands`; `profile` is threaded down through
  `handleWinner` / `postSettleRecover` → `runSettleEffect` (matching the §8.2 "decision in orchestrator, execution
  in SideEffects" split). A `profile.local_gate` audit action records each run (a no-op `Replay` projection —
  `ProfileReplayInvarianceSuite` R1 extended to prove it). Unprofiled / `localGate = false` / `enabled = false` ⇒
  empty command set ⇒ byte-identical 1.6 behaviour. New tests: `OrchestratorLocalGateSuite` (3, e2e collapse +
  both 1.6 paths) and two `RealSideEffectsSuite` real-command units (runs in repo root; short-circuits on non-zero
  exit). `forge-app` 384/384, `forge-core` 424/424; `scalafmtCheckAll` clean (no `RedundantBraces` crash this time).
  **Build gate deferred** (decision D2): its "pre-PR driver fix-up" needs new FSM states the frozen §11 lacks, and
  the shortcut regresses vs 1.6 — scoped out via `AskUserQuestion` and filed for a 1.8 slice. The Task 3.1.3 header
  and the roadmap §4 bullet stay **unticked** until the Build gate lands (and the §0 live re-run, T5).

- **2026-06-02 — Task 3.0.2 landed: profile resolved once, threaded as a read-only router input;
  replayability invariant under test.** `Orchestrator.resolveProfile` (gated on `adapt.enabled`)
  loads the committed profile **once** per run; `run` emits `profile.snapshot` from that value and
  passes it to the new `driveWith(feature, profile)`, which threads `Option[RepoProfile]` down
  `loop → sourceIO → watcherIO → pieceCiWatcherIO → ciPollToEvent → routeCiFailure`. This removes
  the per-failure `profileStore.load()` (and the redundant `!adapt.enabled` short-circuit) that
  3.1.2 carried inside `routeCiFailure` — a disabled run now resolves to `None` and takes the
  blind 1.6 fix-up by the same code path as an unprofiled run. `drive` resolves the profile itself
  so direct-`drive` e2e callers are behaviour-identical. New `ProfileReplayInvarianceSuite`
  (forge-core): **R1** round-trip — a happy-path trajectory with `profile.snapshot` +
  `profile.failure_classified` injected folds to the identical final `Feature` as without them
  (both are audit-only no-op projections in `Replay`'s default branch); **R2** structural guard —
  a `ForgePathsSuite`-style sweep that no `io.forge.core.fsm` source references the
  `io.forge.core.profile` package, so `Fsm.transition` cannot read `ProfileStore` even by accident.
  `forge-core` 424/424; `OrchestratorCiRoutingSuite` / `FailureRouterSuite` unchanged green;
  `scalafmtCheckAll` clean.

- **2026-06-02 — Task 3.1.2 code landed (orchestrator wiring + tests); live dogfood re-run
  pending.** Captured the real `gh run view --log-failed` fixture
  (`docs/slice-3/fixtures/gh-run-view-log-failed.scalafmt.txt`, the actual dogfood-#2 scalafmt
  failure) before writing any parse. Added `CheckResult.detailsUrl` + `runId` extractor and
  `GhClient.runViewLogFailed` + `stripAnsi`; the `FailureRouter` (forge-app) wraps the rules
  classifier + `FailureRouting.route`, wired into `pieceCiWatcherIO`: a profiled scalafmt CI
  failure routes to a local `sbt scalafmtAll` with **no `attempts` increment**, an unprofiled /
  `adapt.enabled = false` run keeps the exact 1.6 blind fix-up. Profile loaded + `profile.snapshot`
  emitted at feature start (§11.0); `profile.failure_classified` recorded before acting; `forge
  stats` folds a "fix-ups avoided" row. `writeFailures` now also folds the full failing log into
  `<p>.failures.md` for every fix-up path (dogfood #4, universal). New tests:
  `OrchestratorCiRoutingSuite` (the collapse end-to-end with scripted fakes: `attempts` stays 0 +
  `RunLocalCommand` logged, vs the unprofiled blind path incrementing `attempts`), `FailureRouterSuite`,
  decoder `detailsUrl`/`runId` + `stripAnsi` units. Full build green (`scalafmtAll` clean — same
  3.8.3 `RedundantBraces` "next on empty iterator" doc-comment crash as the spike, reworded per
  [[reference-scalafmt-redundantbraces-crash]]). Remaining for the §0 exit: the live `szork` re-run.

- **2026-06-02 — Tasks 3.1.1 + 3.0.1 landed via the Phase-3 spike (commit `16396d2`) — Tier-1
  types complete.** Built the deterministic `io.forge.core.profile` package runnable-first and
  exercised it against the real dogfood-#2/#5 logs before opening this plan or the 1.7 contract.
  `RepoProfileSpikeSuite` (15 tests) proves the load-bearing route — the real scalafmt CI log
  collapses to `RunLocalCommand(sbt scalafmtAll)` — plus compile-error → `DriverFixup(fullLog)`,
  rate-limit → `BackOff`, hash stability, and both `szork`/`forge` fixtures round-tripping.
  `forge-core` 420/420 green; `scalafmtCheckAll` + `ForgePaths` literal-sweep clean; full build
  compiles with zero warnings. The spike's purpose was to make the 1.7 contract concrete from
  exercised types rather than prose — done; `forge-design-1.7.md` cites these types directly.
  One incident worth recording: `scalafmt` 3.8.3's `RedundantBraces` rewrite crashed
  (`next on empty iterator`) on a doc-comment backtick span containing parens/`--`; reworded the
  comment (the code structure was a red herring). Captured as a reference memory.

---

## 4. Carry-forward / decisions opened

### T1 — profiles are hand-authored fixtures until Task 3.0.3 — ✅ resolved 2026-06-02

Tier 1 ships the `RepoProfile` *model* and consumes a committed `.forge/profile.json`; the LLM
`RepoProfiler` that *produces* one landed in **Task 3.0.3** (`Connector.profileRepo` + `forge profile`).
The dogfood re-run (3.1.2 / §0 exit) still uses a hand-authored profile — that is deliberate per A5's
"determinism before any LLM": prove the routing collapse with a known-good profile before trusting a
sensor's output. Now that the sensor exists, the residual is purely *validating a live-generated profile*:
the deterministic decode is proven against the `szork`/`forge` fixtures, and a live Claude/Codex capture is
the opt-in `forge-it` `RepoProfilerSmokeSuite` (it asserts the live structured output decodes into a
plausible `RepoProfile`, not byte-equality with a fixture — the model's perception of a real repo is its
own).

### T2 — `profile.snapshot` records hash + schemaVersion only, not the full profile — open (1.7 §19)

The snapshot fingerprints the profile by `contentHash` and relies on the committed `.forge/profile.json`
(in git history) as the content. Alternative: embed the full profile JSON per run for self-contained
replay without git. Hash-only is leaner and matches A5's wording ("snapshotted/hashed"); revisit only
if a replay needs the profile content when the committed file has since changed and the old commit is
unreachable. Flagged in `forge-design-1.7.md` §19.

### T3 — sensor placement: core vs forge-agents — resolved (1.7 §3.1 / §7.11)

The spike co-located everything in `forge-core` so the runnable proof needed no cross-module wiring.
1.7 resolves the final split: the deterministic types + `RuleBasedFailureClassifier` + `FailureRouting`
stay in `forge-core` (the spine consumes them); the LLM sensors become `forge-agents` `Connector`
methods invoked reviewer-side. Tasks 3.0.3 / 3.1.4 / 3.2 implement the `forge-agents` side; no move of
the spiked core types is needed.

### T4 — `RunLocalCommand` makes a fresh `style(...)` commit, not a `git commit --amend` — open

§8.2 row 1 / §8.3 say the autofix result is *amended* into the piece commit. The landed
`RealSideEffects.runLocalAutofixAndPush` instead makes a **fresh** `style(<feature>): <argv>` commit
(reusing the existing `git.stage` + `git.commit` + `pushCurrentBranch` seams) rather than adding a
`git commit --amend` + force-push-with-lease. Rationale: szork-style **squash-merge** collapses the
extra commit on merge (so main history is identical to the amend outcome), it needs no force-push and
so no lease race, and it is an honest auditable record of the autofix. The behavioural contract is
met exactly — no driver turn, no LLM, no `attempts` increment. Revisit if a non-squash (merge/rebase)
repo wants the single-commit history; that would add a `GitClient.amendNoEdit` seam + force-push.

### T5 — live `szork` dogfood re-run — ✅ performed 2026-06-03 (dogfood #3); §8.2 trigger → T6

The **live** re-run was driven 2026-06-03 (dogfood #3, `music-poll-config`, Mode A) and validated the
spine live (`profile.snapshot` load+hash, full §11 lifecycle on a profiled run, Slice-2.0 cost/session
observability). The §8.2 *trigger* itself did **not** fire — the driver formatted correctly — so the
collapse wasn't measured live; that residual is **T6** below. Two friction findings noted while
deferring (below) were also resolved in the drive: finding #1 (the spec cap) did not bite (szork's
implement cap was raised), and finding #2 (interactive `forge spec` from non-TTY) was worked around by
driving the REPL via a **direct `java` launch** (not `sbt -batch run`, which closes the forked app's
stdin → `/done` aborts) and seeding a complete `design.md` brief so the driver wrote all spec files in
one turn. Full write-up: [`dogfood/music-poll-config.md`](dogfood/music-poll-config.md). Original
deferral findings, retained for the record:

1. **Headless `forge run` from `Drafting` enters the spec phase and can hit the 300s `specTimeoutSec`
   cap** (`SettleTimeout(Spec)` → NHI) for a non-trivial decomposition. The `SpecRepl` docstring
   claims headless `run` "never enters the spec phase", but `Orchestrator`'s `Drafting` entry hook
   does `launchSpec`; either the spec cap needs a headless-friendly default or the docstring/contract
   needs reconciling. (szork carried no `specTimeoutSec`, so it took the 300s default.)
2. **`forge spec` (the interactive REPL) can't be driven from a non-TTY** — the spec driver issues an
   `AskUserQuestion` mid-decomposition, so piped stdin mis-routes. The live re-run therefore needs a
   human at the REPL (as the prior dogfoods did), or a longer headless spec cap with the driver
   defaulting its own clarifying answers. The `RunLocalCommand` collapse itself is downstream of all
   this and unaffected.

The Task 3.1.2 box is now ticked (live re-run performed + spine validated + §8.2 unit-proven against
the real failing log); the roadmap §4 bullet stays **unticked** until T6 + the whole-section review.

### T6 — live §8.2 *trigger* — ✅ demonstrated 2026-06-05 (dogfood #4, `adventure-gen-retry-config`)

**Resolved by driving option (a): a feature engineered to force a *guaranteed* reflow.** dogfood #3
(T5) never triggered §8.2 because a natural mis-format is **stochastic** — modern Claude/Codex usually
format correctly. dogfood #4 ([`dogfood/adventure-gen-retry-config.md`](dogfood/adventure-gen-retry-config.md))
made it **deterministic** by exploiting a config-specific scalafmt rule the driver cannot pre-empt and
would not naturally satisfy: a piece acceptance criterion mandating ScalaDoc with **inline
`@param`/`@return` tags**. scalafmt 3.7.17 (szork's pinned version, `maxColumn = 120`) *always* rewrites
an inline tag description onto its own continuation line — proven against szork's real `.scalafmt.conf`
before the run, not invented. Because scaladoc is a comment, it does not affect compile/test, so szork's
`backend` job (compile → test → **Check formatting**) fails *only* on `scalafmtCheckAll` — the clean
format-only failure the rules classifier needs.

**The §8.2 collapse fired live and passed every criterion** (full evidence:
[`dogfood/t6-run/adventure-gen-retry-config/`](dogfood/t6-run/adventure-gen-retry-config/)):
real scalafmt CI failure on piece PR #19 → `profile.failure_classified {gate:ci, kind:deterministic_fix,
confidence:0.97, route:RunLocalCommand, source:rules}` (no LLM tail) → `RunLocalCommand(sbt scalafmtAll)`
→ `style(...)` autofix commit `6b5433ed1` → push → CI re-ran **green** → `PieceAwaitingCi →
PieceAwaitingReview` with **no `PieceCiFailed` and `attempts` unchanged at 0**. `forge stats` records
*"1 fix-up round avoided — a CI failure was remedied by the repo's own deterministic autofix (Phase 3
§8.2), with no driver fix-up turn."* This collapses dogfood-#2's **$1.78 / ~12 min / 2 driver fix-up
rounds** to a ~few-second local step at **$0 driver/LLM cost** — the §0 exit criterion, live and measured.
**Phase 3 now has teeth on a live run, not only against the unit fixture.**

One honest caveat (dogfood #4 finding #1, *by design of the test*): the engineered "inline `@param`"
criterion is self-contradictory with what scalafmt enforces (the split form), so after the §8.2 CI heal,
Forge's own reviewer correctly requested changes (gate=`code`) and the run entered a review↔CI fix-up
loop. That contradiction was deliberate — it is the cleanest way to *guarantee* the reflow — and the run
was stopped once the §8.2 assertion was captured. It is a spec-design lesson (don't mandate an
anti-formatter style), **not** a §8.2 or Forge defect; the §8.2 routing itself behaved exactly as
specified. Two **runnable findings** from dogfood #3 also belonged to the next Forge maintenance pass:

- **finding #5 recurrence — ✅ resolved 2026-06-04.** The transient GitHub 503 on the §9 `PRWatcher`
  poll no longer hard-NHIs. `pollOnce` now maps a `GhError.Transient` (the 503/5xx/network-blip bucket)
  to the new soft **`PollResult.TransientError`**, which the orchestrator absorbs (keep polling) exactly
  like a `RateLimited` — promoted to `Failed` → NHI only after `consecutiveTransientFailuresBeforeFailing`
  (default 3, the S3-4 rate-limit twin). The §8.2 `RateLimit→BackOff` arm was the wrong lever (it
  classifies CI *check-failure logs*, not a `gh` subprocess HTTP error on the poll); the fix lives at the
  watcher/poll-consumer seam where the rate-limit soft-cliff already lives. Contract + rejected
  alternatives: design-rationale **S3-4b**. Tests: `PRWatcherTransientSuite`,
  `OrchestratorTransientPollSuite`, `GhErrorClassifierSuite` (503→Transient).
- **pre-commit `HEAD` assertion — ✅ resolved 2026-06-04.** `RealSideEffects.assertHeadIs(expected)` runs
  immediately before **every** `git.commit` (design / piece-open / fix-up / §8.2 CI autofix / conventions
  PR), refusing with a `Left` → `HarnessError` → NHI when `HEAD` is not the branch Forge expects — so a
  concurrent-git race in the shared driving worktree (the dogfood-#3 excursion that left `HEAD` on `main`
  and pushed a piece commit straight to the remote, bypassing the PR) can no longer corrupt the base
  branch. The expected branch is `feature.manifest.designBranch` / `pieceBranch(piece)` (or the explicit
  conventions branch); a detached `HEAD` is refused too (via `git.currentBranch`'s `ParseFailure`).
  Guarding *all* commit sites, not just the cited piece one, follows the invariant-enforcement discipline.
  Test: `RealSideEffectsSuite` ("HEAD on the wrong branch → Left, no commit / push").

### D1 — the local Format gate runs format-before-commit, not commit-then-`git commit --amend` — open (1.7 §8.3/§11.4)

§8.3 / §11.4-step-6 word the Format autofix as "**amends** the commit in place". The landed Task 3.1.3 instead runs
the formatter on the working tree **before** the piece commit (in `runSettleEffect`'s `ClassifyCommitOpenPr` case,
ahead of `sideEffects.classifyCommitOpenPr`), so the existing classify → stage → commit naturally folds the
formatter's rewrites into the single piece commit. Result is identical to the amend outcome (one format-clean piece
commit) with **less machinery**: no new `GitClient.amendNoEdit` seam, no second `style` commit (contrast the §8.2 CI
path's T4 fresh-commit, which *must* add a commit because its target is already pushed — that rationale does not apply
pre-push). Revisit only if a formatter that touches files *outside* the driver's change set (a whole-repo reformat in a
repo with pre-existing violations) must be confined to the piece commit; today such stray changes enter the change set
exactly as the §8.2 CI autofix already accepts via `git status`.

### D2 — the §8.3 local **Build** gate is deferred; needs a pre-PR fix-up FSM path — ✅ resolved 2026-06-03 ([`design-3.1-build-gate.md`](design-3.1-build-gate.md))

§8.3 says a local `Build` `CodeFix` "routes straight to a **pre-PR driver fix-up**". The frozen §11 FSM had no such
path: the only fix-up states (`PieceCiFailed` / `PieceFixingUp`) both carry a PR number, and `classifyCommitOpenPr`'s
`Left` routes to NHI (`ResolveLocalImplementationChanges`), not a fix-up loop. Building a true pre-PR fix-up is a §11
contract change (new state/events + a 1.7→1.8 revision), beyond a Tier-2 task; and the shortcut (gate a build failure →
NHI pre-PR) would **regress** vs 1.6, where the same failure reaches CI and auto-routes to a fix-up with the full log
(§8.2). So Task 3.1.3 shipped the Format gate only (the dogfood-#3 headline); Build failures kept the 1.6 path. Decided
via `AskUserQuestion` (2026-06-02). **Resolved 2026-06-03** as its own slice, [`design-3.1-build-gate.md`](design-3.1-build-gate.md):
two new pre-PR FSM states (`PieceBuildFailed` / `PieceBuildFixingUp`) + a `LocalBuildFailed` event carry the §8.3 Build
`CodeFix` to a pre-PR driver fix-up that re-gates before opening the PR — with the fix-up budget in-state (never
`manifest.attempts`) and a fall-through to the 1.6 PR-open for any non-`CodeFix` route (decision D2a there). Contract:
[`forge-design-1.8.md`](forge-design-1.8.md).

### D3 — `profile.local_gate` is a new §19 `profile.*` kind not yet enumerated in the 1.7 contract — open (1.7 §19)

Task 3.1.3 emits a `profile.local_gate` action `{ gate: "local", kind: "format", commands: [...] }` to keep the local
gate observable (TUI / `forge stats`), but the 1.7 §19 table lists only `profile.snapshot` / `profile.failure_classified`.
It is a no-op `Replay` projection (the default branch; proven inert in `ProfileReplayInvarianceSuite` R1) and `forge
stats` ignores unrecognised kinds, so it is safe today. Enumerate it in §19 in the next contract revision (and decide
whether `forge stats` should fold it into the "fix-ups avoided" row — a pre-PR format fix is an even larger save than the
§8.2 CI collapse, since the round never starts).

---

### D4 — `repo-profile.json` omits `schemaVersion` (decoder stamps it); `commitIdentity` is in-schema with a default — open (1.7 §6/§7.11)

The §6 `RepoProfile` model carries `schemaVersion`, but the **`repo-profile.json` Native schema deliberately does not** —
it is a Forge-internal versioning concern, not something the LLM should decide, so `ReviewDecoders.repoProfile` stamps
`RepoProfile.CurrentSchemaVersion` regardless of (and ignoring) any `schemaVersion` the model emits. This keeps the sensor
out of versioning and means a future schema bump never requires re-prompting; it is also why feeding a committed fixture
(which has `schemaVersion`) back through the decoder round-trips. `commitIdentity` *is* in the schema (the model can read a
repo-declared bot identity from CONTRIBUTING/workflows), but the prompt directs it to default to
`forge[bot]`/`forge@users.noreply.github.com` when the repo is silent — matching the `szork` fixture. Revisit if a later
revision wants the identity sourced from `config.json`/git config instead of perceived.

### D5 — `forge profile` is a feature-less state-changing command that still takes the process lock — open (1.7 §15)

`forge profile` writes the repo-level `.forge/profile.json`, so it is classed **state-changing** (it acquires the §13
process lock for the write) but binds to **no feature** (`CliParser.featureOf(Profile) == None`, lock metadata carries no
feature — the same shape `ReadOnly`/`UnlockForce` already use). It is the first state-changing command without a feature
id; the existing `(paths, config, args)` `StateChangingContext` covers it with no new plumbing. The reviewer-side connector
is selected from `config.mode` via `Role.pairFor` (there is no per-feature `Mode` at profile time). Revisit only if a repo
wants to profile under a non-default mode without editing `config.json` (a `--mode` flag), or if concurrent `forge profile`
+ `forge run` should *not* contend on the one lock.

### D6 — the LLM `classifyFailure` degrades to the rules `Escalate` on timeout/failure, and is consulted only on `Unknown` — open (1.7 §7.11/§8.2)

§7.11 frames `classifyFailure` as the cost lever consulted when the rules baseline is low-confidence. Task 3.1.4 wires
it on exactly the `Unknown` kind (which the rules baseline routes to `Escalate`) rather than on a confidence threshold —
`Unknown` *is* the rules classifier's low-confidence sentinel (it lands there at 0.2), so the two are equivalent today
and the kind-match is simpler/total. On the sensor side, a `ReviewerOutcome.Timeout` / adapter / process failure makes
the orchestrator keep the **rules** `Escalate` (recording `source = "rules"`) rather than block the feature on a stalled
sensor — the same "never block on a stall" stance the reviewer one-shots take. When the LLM *itself* returns `Unknown`,
that routes to `Escalate` too, now stamped `source = "llm"` so the §19 audit shows the sensor was consulted. Revisit if
a future rules classifier emits a genuine `confidence` band (not just `Unknown`) worth consulting the LLM on — that would
make the gate a threshold rather than a kind-match. The `FailureClassifierInput` carries the full `RepoProfile` (so the
prompt can list Known commands); revisit if that proves too large a prompt for a big multi-command profile.

### D7 — `profile.conventions_learned` is a new §19 `profile.*` kind not yet enumerated in the contract — ✅ resolved 2026-06-04 ([`forge-design-1.12.md`](forge-design-1.12.md))

Task 3.2 emits a `profile.conventions_learned` action `{ addedCommands: [string…], hasClaudeMdProposal: bool,
claudeMdPrNumber: int|null, summary }` after the learner settles, to keep the learning observable (TUI / `forge stats`).
The 1.7 §19 table listed only `profile.snapshot` / `profile.failure_classified`; like `profile.local_gate` (D3) this is
an additive `profile.*` audit kind. It is a no-op `Replay` projection (the default branch; the replayability invariant
holds — the FSM never reads it, the profile delta reaches a later run only as the committed `.forge/profile.json` input)
and `forge stats` ignores unrecognised kinds, so it was safe-but-undocumented. **Resolved 2026-06-04** by
[`forge-design-1.12.md`](forge-design-1.12.md), a focused standalone-by-freeze revision restating only §19 to enumerate
the kind (a pure spec-text reconciliation — no code change; the kind has been emitted since Task 3.2). D3's
`profile.local_gate` was already enumerated in 1.8 §19, so with 1.12 the full Phase-3 `profile.*` set
(`snapshot` / `failure_classified` / `local_gate` / `conventions_learned`) is in the contract. The "should `forge stats`
fold a conventions-learned row" question is left to the consuming pass, not the contract (1.12 §19 notes it as a
candidate future fold).

### D8 — the `ConventionLearner` mines classified failures + reviewer-comment text — ✅ resolved 2026-06-04 ([`forge-design-1.13.md`](forge-design-1.13.md))

§7.11/§11.7 frame the learner as mining "failure→remedy patterns **+ recurring reviewer comments**". Task 3.2 grounded it
on the failure→remedy half only: `Orchestrator.observedFailures` distils the §19 `profile.failure_classified` actions
(`gate`/`kind`/`route`/`evidence`) from the feature's action log. The action log did **not** capture reviewer **comment
text** then (reviewer `request_changes` drives a fix-up but the blocker prose was not logged as a structured action), so
feeding "recurring reviewer comments" would have meant inventing a field with no backing data — deliberately avoided per
the "capture real shapes, don't invent" discipline; the §7.11 cost lever therefore gated on `failures.nonEmpty` alone.

**Resolved 2026-06-04** by adding the backing signal first, then widening the learner. A new §19
`review.request_changes` audit kind (`io.forge.core.review.ReviewRequestedChangesAction`, a no-op `Replay` projection like
the `profile.*` kinds) is appended by `Orchestrator.logReviewerRequestChanges` whenever Forge's own reviewer one-shot
returns `RequestChanges` (design or code) with a non-empty blocker list — capturing the blocker prose with `{ gate, round,
blockers }`. `Orchestrator.observedReviewerComments` mines it back into `Vector[ObservedReviewerComment]`; the
`ConventionLearnerInput` gained a `reviewerComments` channel (rendered into `ReviewerPrompts.learnConventionsBody` + the
`learn-conventions.{claude,codex}.md` prompts); and the §7.11 cost lever in `maybeLearnConventions` widened to
`failures.nonEmpty || reviewerComments.nonEmpty`. Contract: [`forge-design-1.13.md`](forge-design-1.13.md)
(§7.11/§11.7/§19). Tests: `ReviewRequestedChangesActionSuite` (payload shape), `ProfileReplayInvarianceSuite` R1
(replay inertness), `OrchestratorConventionLearnerSuite` (the D8 e2e — a reviewer comment alone now consults the learner
with the blocker threaded into the input — plus the `logReviewerRequestChanges` append/skip-per-verdict seam). `forge-core`
+ `forge-agents` + `forge-app` green; `scalafmtCheckAll` clean.

### D9 — the `ConventionLearner`'s proposed CLAUDE.md edit is opened as a PR — ✅ resolved 2026-06-03

§11.7 says the learner's proposed CLAUDE.md edit "is opened as a normal PR for human approval". Task 3.2 first shipped the
in-repo half (persist the proposal to the audit dir) and deferred the PR-open as the heaviest, least-reversible part; this
follow-up landed it. `SideEffects.openConventionsPr` (`RealSideEffects`) branches from base, appends the convention to
CLAUDE.md (creating it if absent), commits, pushes, and `gh pr create`s — mirroring `commitDesignAndOpenPr`'s
branch/stage/commit/push/`createPr` seam — and is **idempotent** (an already-open conventions PR for the branch is
reused, never re-`createPr`'d). It is wired into `Orchestrator.applyConventionDeltas`: on a `claudeMdProposal` it opens
the PR and records the number in `profile.conventions_learned`; on a `Left` (dirty tree / push rejected / gh down) it
**falls back** to persisting the proposal to `.forge/specs/<feature>/audit/learned-conventions.md` so the proposal is
never lost (the original deferred behaviour, now the failure path). The whole thing stays advisory/never-blocking
(`handleErrorWith` in `maybeLearnConventions`). **`no autonomous doc mutation`** holds: Forge opens a PR, never merges it.
Decided + landed via the `AskUserQuestion` follow-up (2026-06-03). Revisit only if a repo wants the commit authored under
the profile's `commitIdentity` rather than the ambient git identity (a `GitClient` identity seam — out of scope here).

## 5. Cross-references

- [`roadmap.md`](roadmap.md) §4 — Phase 3 plan; tick its bullet only at slice close.
- [`forge-design-1.7.md`](forge-design-1.7.md) — the contract these Tasks implement (§3/§6/§7/§8/§11/§18/§19).
- [`design-rationale.md`](design-rationale.md) **A5** — spine/senses + replayability rationale.
- [`dogfood/extract-media-network-config.md`](dogfood/extract-media-network-config.md) — findings #3/#4/#5, the evidence and the re-run target.
- Spike: `modules/forge-core/src/main/scala/io/forge/core/profile/` + `RepoProfileSpikeSuite` (commit `16396d2`).
