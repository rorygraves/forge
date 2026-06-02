# Forge — design doc v1.7

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.7 — Phase 3 (Repo Adaptation): deterministic spine + agentic senses  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.4 → 1.5 → 1.6) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.6.

**Standalone-by-freeze (this revision only).** 1.7 is the first *architectural* revision — it adds the repo-adaptation layer (design-rationale **A5**) rather than folding a few method or schema deltas into an already-standalone base. Reproducing all of §0–§24 verbatim would be ~1500 lines of mostly-unchanged copy. So 1.7 **restates in full only the sections it changes — §3, §6, §7, §8, §11, §18, §19** — and explicitly **freezes every other section at its 1.6 text** (§0–§2, §4–§5, §9–§10, §12–§17, §20–§24 carry over unchanged and unreferenced). Section numbering is preserved 1:1 with 1.6, so any "v1.6 §N" / "v1.4 §N" reference resolves to the same §N here. This is a one-time, declared exception to the §23 standalone rule for the size of the change; the next non-architectural revision returns to the full-restatement convention. The runnable contract these sections describe already exists: the Phase-3 spike (`modules/forge-core/.../profile/`, commit `16396d2`) ships the deterministic types and routing exercised against the real dogfood-#2/#5 logs — this spec is the contract those types satisfy, plus the agentic sensors and lifecycle seams that wrap them.

**Changed in 1.7:** §3 (spine/senses architecture), §6 (`RepoProfile` / `WorkflowProfile` / `Classification` domain types + `ProfileStore`), §7 (sensor roles via the Role indirection), §8 (FailureClassifier at the CI gate + a new local format/build gate), §11 (profile-load+hash at feature start; classified CI-fail routing; ConventionLearner at `FeatureDone`), §18 (`adapt` config), §19 (`profile.*` actions).

---

## 3. Architecture

### 3.0 Deterministic spine + agentic senses (Phase 3)

The Phase-1/2 architecture (§3.1) is a **deterministic spine**: `Fsm.transition`, the action log, replay, restart recovery, budget caps, and push/PR/merge are pure-given-inputs. That determinism is *why* the dogfood-#1 projection bug was fixable in one session and why a run is replayable and cost-reconstructable. Phase 3 keeps the spine deterministic and adds **agentic sensors** at well-defined seams that *perceive and propose*; only the core *decides, records, and touches irreversible things*.

| Stays deterministic (the spine) | Becomes agentic (the senses) |
|---|---|
| §11 lifecycle, `Fsm.transition`, merge/budget/push gates | Profiling a repo: build tool, gate commands, conventions (`RepoProfiler`) |
| Action log, state cache, replay, restart recovery | Interpreting a CI/build/format failure → `{deterministic-fix, code-fix, flaky, env, rate-limit}` (`FailureClassifier`) |
| **Routing** a classified failure to a `FixupRoute` (§8, §11.5) | Distilling recurring review feedback + failure→remedy patterns into conventions (`ConventionLearner`) |

**How learning coexists with determinism.** Learning threatens replayability only if it lives *inside* `transition`. It does not. The `RepoProfile` is a **versioned input** to the core (like config), snapshotted + hashed into the action log per run (new §19 `profile.snapshot`). A replay uses the *profile-as-of-that-run* and the *classification-as-recorded* (§19 `profile.failure_classified`), so `Fsm.transition` stays pure-given-inputs and fully replayable. Sensors mutate the profile *between* runs (profiling on first encounter / config change; post-run distillation), never mid-transition.

**The motivating waste.** Dogfood #2's `forge stats` recorded **\$0.73 of real implement work, then \$1.78 / 12 min / 2 fix-up rounds** to fix a scalafmt-formatting issue the driver could have avoided — pure repo-blindness. No procedural code can know every repo's formatter/lint/test/build commands across Scala/sbt, Node/npm, Python, Go without accreting brittle, combinatorial CI-YAML heuristics. That is perception, and it belongs in an agentic sensor whose *output* the deterministic core consumes.

### 3.1 Component diagram

Unchanged from 1.6 §3.1, with two additions to the Orchestrator block — a `ProfileStore` read-model and a `FailureRouter` (deterministic) consulting the sensors:

```
┌──────────────────────────────┴─────────────────────────────────────┐
│  Orchestrator (pure Scala)                                         │
│   ┌─────────────┐    ┌─────────────┐    ┌────────────────────┐     │
│   │ FSM         │───▶│ ActionLog   │    │ StateCache         │     │
│   └─────────────┘    └─────────────┘    └────────────────────┘     │
│           ├── … (BranchManager, PRWatcher, …, SessionMonitor)      │
│           ├── ProfileStore    (.forge/profile.json, committed)     │  ← new (§6)
│           └── FailureRouter    (deterministic: Classification →    │  ← new (§8)
│                                 FixupRoute; consults sensors)      │
└──────────────────────────────┬─────────────────────────────────────┘
                               │  (sensors run reviewer-side, §7)
                  ┌────────────┴────────────┐
                  │ RepoProfiler /          │  perceive-and-propose roles
                  │ FailureClassifier /     │  (native schema + decoders,
                  │ ConventionLearner       │   §7.4-style; reviewer connector)
                  └─────────────────────────┘
```

Module placement (unchanged tree, additive contents):

```
    forge-core/          ← + io.forge.core.profile: RepoProfile/WorkflowProfile/RepoCommand,
                            Classification/FailureKind, FixupRoute + FailureRouting (deterministic),
                            ProfileStore/FileProfileStore, ProfileSnapshot   ← spiked, committed
    forge-agents/        ← + sensor Connector methods (profileRepo / classifyFailure /
                            learnConventions) + decoders, mirroring the reviewer pattern (§7.4)
    forge-app/           ← + FailureRouter wiring; profile load+hash at feature start (§11.0)
```

The `RepoProfile` model, the deterministic `RuleBasedFailureClassifier`, `FailureRouting.route`, `ProfileStore`, and `ProfileSnapshot` are the **deterministic spine** and live in `forge-core` (the core consumes them). The **LLM sensors** are `forge-agents` `Connector` methods invoked through the reviewer-side Role (§7). This split is what keeps the routing replayable while the perception is agentic.

*(§3.3 Dependencies and §3.3.1 Scala floor: unchanged from 1.6.)*

---

## 6. Domain model

*(All 1.6 §6 types — `Feature`, `FsmState`, `ResumeHint`, `Manifest`, etc. — unchanged. Phase 3 adds the `io.forge.core.profile` package, committed as the spike and reproduced here as the contract.)*

### 6.3 RepoProfile (the versioned input)

```scala
enum CommandKind:        // which gate a command serves
  case Format, Lint, Build, Test, Typecheck

enum Determinism:        // A5 "commands tiered by determinism"
  case Deterministic     // re-running the tool is a pure function of the tree (a formatter, a codegen step)
  case Heuristic         // interpreting the outcome needs judgment (a failing test: bug? flake? env?)

final case class RepoCommand(
    kind: CommandKind,
    argv: Vector[String],
    determinism: Determinism,
    required: Boolean,
    autofix: Boolean     // true ⇒ running it MUTATES the tree to remediate (a formatter rewrites files)
) derives ReadWriter

final case class CommitIdentity(name: String, email: String) derives ReadWriter

enum MergeStrategy: case Squash, Merge, Rebase
enum BranchModel:   case TrunkBased, GitFlow

final case class WorkflowProfile(            // review / CI / merge-strategy / branch-model
    reviewRequired: Boolean,
    ciRequiredChecks: Vector[String],
    branchModel: BranchModel,
    mergeStrategy: MergeStrategy
) derives ReadWriter

final case class RepoProfile(
    schemaVersion: Int,                      // RepoProfile.CurrentSchemaVersion = 1
    buildTool: String,
    commands: Vector[RepoCommand],
    commitIdentity: CommitIdentity,
    workflow: WorkflowProfile
) derives ReadWriter:
  def command(kind: CommandKind): Option[RepoCommand]
  def contentHash: String                    // stable 16-hex SHA-256 over canonical compact JSON
```

`autofix && determinism == Deterministic` is the single predicate that makes a failure *routable to a no-driver local run* (§8). It is a property of the **profile** (perception), not of the classifier — the classifier only names the failure; the profile says whether the repo owns a safe remedy. The committed `szork` / `forge` fixtures (`modules/forge-core/src/test/resources/profiles/`) prove the model expresses both real repos: both declare `Format = sbt scalafmtAll` (`Deterministic`, `autofix`), and `szork` carries `ciRequiredChecks = [backend, frontend]`, `Squash`, `TrunkBased`.

### 6.4 Classification + FixupRoute (perceive vs decide)

```scala
enum FailureKind:                            // what a gate failure IS, as perceived by a sensor
  case DeterministicFix                      // formatter/codegen the repo's own tool fixes in place
  case CodeFix                               // real code defect — needs a driver fix-up turn
  case Flaky                                 // transient test failure — retry
  case Env                                   // toolchain/infra (missing binary, OOM) — back off
  case RateLimit                             // provider/API rate limit — back off and keep polling
  case Unknown                               // could not classify — escalate

final case class Classification(
    kind: FailureKind,
    confidence: Double,
    suggested: Option[CommandKind],          // for DeterministicFix: which repo command remediates
    evidence: String                         // the log fragment keyed on (audit trail)
) derives ReadWriter

enum FixupRoute:                             // what the deterministic spine DOES with a Classification
  case RunLocalCommand(command: RepoCommand) // run the repo's own tool, commit — NO driver turn, NO LLM cost
  case DriverFixup(failureLog: String)       // driver fix-up turn WITH the full failing log piped in
  case Retry                                 // re-run the gate
  case BackOff(reason: String)               // wait and keep polling
  case Escalate(reason: String)              // NeedsHumanIntervention
```

The classification (perception, agentic) and the route (decision, deterministic) are separate types on purpose. `FailureRouting.route(classification, profile, failureLog): FixupRoute` is **pure and total** (lives in `forge-core`): `DeterministicFix` collapses to `RunLocalCommand` *only if* the profile declares a deterministic, in-place autofix command for `suggested` — otherwise it falls back to `DriverFixup` with the real log; `CodeFix → DriverFixup`; `Flaky → Retry`; `RateLimit`/`Env → BackOff`; `Unknown → Escalate`.

### 6.5 ProfileStore (the seam)

```scala
trait ProfileStore:
  def load(): IO[Option[RepoProfile]]        // None ⇒ repo not profiled yet
  def save(profile: RepoProfile): IO[Unit]   // atomic write to .forge/profile.json

final class FileProfileStore(paths: ForgePaths) extends ProfileStore
```

Repo-level (one profile per repo), not per-feature. Atomic + durable exactly as `FileStateCache` (sibling temp + `SYNC`, `ATOMIC_MOVE`, parent-dir fsync). **Read policy differs from the state cache on purpose:** the state cache is *rebuildable* from log + manifest, so it swallows a decode failure as `None`; `profile.json` is **committed source of truth**, so a malformed profile propagates as an error rather than masquerading as "not profiled yet". `ForgePaths.profileFile = .forge/profile.json` (committed, not gitignored — §4 adds the row).

---

## 7. Agent connectors, Mode, and sensor roles

*(§7.1–§7.10 connector/Mode/HaltWithQuestion contract unchanged from 1.6. Phase 3 adds three sensor roles that reuse the §7.4 Native-schema mechanism and the §10.2 reviewer decoder pattern.)*

### 7.11 Sensor roles (RepoProfiler / FailureClassifier / ConventionLearner)

The three sensors are **perceive-and-propose** roles. They reuse, not re-invent, the reviewer machinery:

- **Routed through the Role indirection.** No new `match m: Mode` site. Sensors run on the **reviewer-side connector** (the non-driver of the §6 `Mode` pair, selected by `Role.pairFor`): it is already the cross-model "reading/judging" side, and routing sensors there preserves the driver≠observer separation. `Connector` gains three methods alongside `reviewDesign`/`reviewPr`/`refine`:

  ```scala
  def profileRepo(input: RepoProfilerInput): IO[RepoProfile]
  def classifyFailure(input: FailureClassifierInput): IO[Classification]
  def learnConventions(input: ConventionLearnerInput): IO[ConventionDeltas]
  ```

  Each is invoked exactly like a reviewer one-shot: `~/.forge/schemas/<sensor>.json` Native schema (`--json-schema` / `--output-schema`, §7.4), a `~/.forge/prompts/<sensor>.<connector>.md` system prompt, a structured user body, and a `ReviewDecoders`-style decoder (`Either[String, A]`; malformed ⇒ non-retried `StructuredOutputMalformed`, §7.5). Wall-clock-capped via the existing `ReviewerCall` / `ReviewerOutcome` boundary.

- **Rules first, LLM only on `Unknown` (the spike's cost lever).** The deterministic `RuleBasedFailureClassifier` (`forge-core`, free, instant) runs **first** on every failure. The LLM `classifyFailure` is consulted **only** when the rules return low-confidence `Unknown` — so the common cases (scalafmt, rate-limit, compile error) never spend a sensor call, and the LLM is reserved for the genuinely ambiguous tail. The chosen `Classification` (and its `source ∈ {rules, llm}`) is recorded (§19 `profile.failure_classified`) so replay reuses it rather than re-invoking the sensor.

- **`RepoProfiler`** runs on first encounter (no `.forge/profile.json`) or on a CLAUDE.md / AGENTS.md / `.github/workflows` / build-file change: reads those inputs → a `RepoProfile`, written via `ProfileStore.save`. Human-reviewable before first use (it is committed, so it lands in a normal diff).

- **`ConventionLearner`** runs post-run (§11.7 `FeatureDone`): mines failure→remedy patterns + recurring reviewer comments → `RepoProfile` deltas **and a proposed PR to the repo's own CLAUDE.md** ("implement driver must run `sbt scalafmtAll` before settling"). Human-approved; **no autonomous doc mutation** — it proposes, the human merges.

---

## 8. CI readiness policy + failure classification

*(§8 discovery/timeout/readiness rules and §8.1 cache scoping unchanged from 1.6. Phase 3 inserts a classification step on the failure edge and adds a local pre-PR gate.)*

### 8.2 Classified failure routing (replaces "any required check failed → fix-up")

When a required check fails (§11.5) or a local gate fails (§8.3), Forge no longer routes blindly to a fix-up round. It:

1. Pulls the **real failing log** — `gh run view <runId> --log-failed` for CI; the captured tool stdout/stderr for a local gate (dogfood #4: the prior fix-up driver saw only the `gh pr checks` *summary*, never `scalafmt: 1 file must be formatted`).
2. Classifies it — `RuleBasedFailureClassifier` first; LLM `classifyFailure` only on `Unknown` (§7.11).
3. Routes it deterministically — `FailureRouting.route(classification, profile, failureLog)`:

| Route | Spine action | `attempts` (§11.5) |
|---|---|---|
| `RunLocalCommand(cmd)` | Run `cmd.argv` (e.g. `sbt scalafmtAll`), amend the piece commit with the result, push. **No driver turn, no LLM.** | **not incremented** — it is not a fix-up round |
| `DriverFixup(log)` | Existing §11.6 fix-up, but `<p>.failures.md` now carries the **full failing log**, not the summary. | `+= 1` |
| `Retry` | Re-poll / re-run the gate without a new driver session. | not incremented |
| `BackOff(reason)` | Wait and keep polling (dogfood #5: a transient rate-limit must not hard-NHI with a misleading fix-up hint). | not incremented |
| `Escalate(reason)` | `NeedsHumanIntervention(reason, RunAnotherFixup(p, prNumber))`. | not incremented |

The **load-bearing change** is row 1: the dogfood-#2 \$1.78 / 12-min / 2-round scalafmt fix-up collapses to one ~2s local `sbt scalafmtAll`, and — because it is not a fix-up round — it does not consume the `maxFixupRounds` budget. The spike proves this end-to-end against the real scalafmt log (`RepoProfileSpikeSuite`).

**`adapt.autofix` opt-out.** When `adapt.autofix = false` (§18), `RunLocalCommand` degrades to `DriverFixup(log)` with the autofix command named in the prompt — Forge proposes the local fix instead of applying+committing it. The classification and the route are still recorded.

### 8.3 Local format/build gate (shift-left, pre-PR)

After Forge commits a piece (§11.4 step 6, **before** push/PR), it runs the profile's `required` deterministic gates **locally**:

- `Format` (`autofix`): run `cmd.argv`; if it rewrote files, amend the commit (so the driver's non-conformant output never reaches CI — kills dogfood #3 at the source, with **zero** round-trip).
- `Build` (non-`autofix`, `Deterministic`): run `cmd.argv`; on failure, feed the output through §8.2 — a `CodeFix` routes straight to a local driver fix-up *before* the PR exists (cheaper than a CI round-trip), an `Env` escalates.

The local gate is **best-effort and additive**: if the profile declares no such command, or `adapt.localGate = false`, the phase proceeds exactly as 1.6. It never blocks on a `Heuristic` command (a test suite) — those stay on the CI side where flakiness is observable across polls.

---

## 11. Lifecycle

*(§11.0–§11.7 unchanged from 1.6 except the four seams below. The seams are additive: a repo with no `.forge/profile.json` runs exactly as 1.6 — Phase 3 never blocks an unprofiled repo.)*

### 11.0 Profile load + snapshot (feature start)

At orchestrator startup for a feature — after the §13 lock is acquired, **before** the first `Fsm.transition` — Forge loads the profile and binds the run to it:

1. `profile <- ProfileStore.load()`.
2. If `Some(profile)`: append `profile.snapshot { hash = profile.contentHash, schemaVersion }` (§19) and thread the `RepoProfile` as a **read-only input** to the gate-routing logic (§8) for the rest of the run.
3. If `None`: the run proceeds **unprofiled** (1.6 behaviour). `RepoProfiler` (§7.11) may be invoked out-of-band to populate `.forge/profile.json`; it is never auto-run mid-feature.

The snapshot is emitted **once per run**, not per transition: it fingerprints the profile-as-of-this-run so a replay binds to the same input even if the profile changed between runs. `Fsm.transition` never reads `ProfileStore`; the profile reaches it only as already-loaded input, preserving purity.

### 11.4 Implementation phase — step 6 addendum (local gate)

Step 6 (post-settle), after "Forge commits with `feat(<feature>): <piece title>`" and **before** "Push, then `createPr`": run the §8.3 local format/build gate. A `Format` autofix amends the commit in place; a local `Build` `CodeFix` routes to a pre-PR driver fix-up via §8.2 (no `attempts` increment until a *PR-side* failure, per §11.5). Then push/PR as in 1.6.

### 11.5 CI & review polling — failed-check routing

The 1.6 rule "Any required check failed → atomically persist `attempts += 1` → `PieceCiFailed` → fix-up" is replaced by §8.2 classified routing:

- `RunLocalCommand` / `Retry` / `BackOff`: **no `attempts` increment**, re-enter `PieceAwaitingCi` after the local action / backoff. (A `RunLocalCommand` amends + pushes, then re-polls.)
- `DriverFixup`: persist `attempts += 1`; if `<= maxFixupRounds` → write the full-log `<p>.failures.md`, `PieceCiFailed(p, prNumber, attempt)` → fix-up (§11.6). Else → `NeedsHumanIntervention(..., RunAnotherFixup(p, prNumber))`.
- `Escalate`: `NeedsHumanIntervention` directly.

Reviewer `request_changes` and human `CHANGES_REQUESTED` paths are unchanged (those are review feedback, not gate failures — they always route to fix-up with `attempts += 1`).

### 11.7 Post-merge — ConventionLearner at FeatureDone

On the transition to `FeatureDone` (§11.7), if `adapt.enabled`, Forge invokes `ConventionLearner` (§7.11) **out of band** (it does not gate the transition; `FeatureDone` is reached deterministically first). Its proposed `RepoProfile` deltas are written via `ProfileStore.save` (a committed diff, human-reviewable); its proposed CLAUDE.md edit is opened as a normal PR for human approval. A learner failure is advisory (logged, never blocking) — same posture as the §14.2 refinery.

---

## 18. Configuration

*(All 1.6 §18 keys unchanged. Phase 3 adds one block; absence ⇒ the defaults below, all chosen so an unprofiled or default-config repo behaves exactly as 1.6 plus the safe, free local autofix.)*

```json
{
  "adapt": {
    "enabled": true,
    "localGate": true,              // §8.3 run required deterministic gates locally pre-PR
    "autofix": true,                // §8.2 row 1 / §8.3: Forge runs autofix commands and amends; false ⇒ propose-only
    "llmClassifierOnUnknown": true, // §7.11 consult LLM classifyFailure only when rules return Unknown
    "conventionLearner": true       // §11.7 run ConventionLearner at FeatureDone (proposes; never auto-mutates)
  }
}
```

`adapt.enabled = false` disables every Phase-3 seam (pure 1.6 behaviour). The `RepoProfile` itself is **not** in `config.json` — it lives in its own committed `.forge/profile.json` (§6.5) because it is sensor-produced, versioned, and hashed per run, unlike the hand-edited config.

---

## 19. Action log schema

*(All 1.6 §19 `kind` values unchanged. Phase 3 adds a new `profile.*` category — repo-adaptation events, parallel to `cost.*` / `session.*`, deliberately not under `audit.*` because these are versioned-input bindings and routing decisions, not sanitized milestone summaries.)*

- `profile.snapshot` — `{ hash, schemaVersion }`. Emitted once per run at feature start (§11.0) when a `.forge/profile.json` is present, binding the run to the profile-as-of-then. The full profile content is the committed file (recoverable from git by `hash`); the log records *which* version was in force so a replay is deterministic against it. (Spiked: `ProfileSnapshot.draft`.)
- `profile.failure_classified` — `{ gate: "ci" | "local", kind, confidence, suggested, route, source: "rules" | "llm", evidence }`. Emitted on every classified gate failure (§8.2) **before** the route is acted on. Records the perception so replay reuses the recorded `Classification`/`route` rather than re-invoking the (non-deterministic) sensor — the mechanism that keeps `Fsm.transition` replayable while classification is agentic. `forge stats` folds these into a "fix-ups avoided / dollars saved" row: each `route: "RunLocalCommand"` is a fix-up round that did **not** happen (the dogfood-#2 collapse, measured).

*(§19's `FORGE_DRIVER_RAW_DUMP_DIR` debug-sink note: unchanged.)*

---

## Cross-references

- [`roadmap.md`](roadmap.md) §4 — Phase 3 (Repo Adaptation): the slice plan this contract opens.
- [`design-rationale.md`](design-rationale.md) **A5** — the spine/senses decision and the replayability argument (versioned-input-not-mid-transition).
- [`design-3.0.md`](design-3.0.md) — the Task breakdown implementing this contract (runnable-first; 3.0 RepoProfile/store/hash, 3.1 FailureClassifier + routing).
- [`dogfood/extract-media-network-config.md`](dogfood/extract-media-network-config.md) — findings #3/#4/#5, the evidence this spec answers.
- [`forge-design-1.6.md`](forge-design-1.6.md) — the frozen base; §0–§2, §4–§5, §9–§10, §12–§17, §20–§24 carry over unchanged.
- Spike: `modules/forge-core/src/main/scala/io/forge/core/profile/` + `RepoProfileSpikeSuite` (commit `16396d2`) — the committed deterministic types this contract describes.
