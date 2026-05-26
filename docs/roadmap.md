# Forge — product roadmap

> Companion to [`forge-design-1.2.md`](forge-design-1.2.md). The design doc is
> the implementation contract for v1; this document is the multi-horizon plan
> the design lives inside. Early phases are concrete (and trace directly into
> §17 of the design); later phases capture direction and have not yet been
> turned into specs.
>
> **Status:** draft v0.6 — 2026-05-26. **Slices 1 and 2 closed.**
> Slice 1 (PR-A → PR-E in [`design-2.1.md`](design-2.1.md)) ships
> both connectors against the v1.2 §7.1 streaming-spec trait with
> real-CLI integration tests in `forge-it`. Slice 2 (PR-A → PR-G in
> [`design-2.2.md`](design-2.2.md)) ships `forge-core` — `ForgePaths`
> + relocated manifest types, `FsmState`/`FsmEvent`/`Feature`,
> `Fsm.transition` per §11, `FileActionLog` + `Feature.foldEvents`,
> `FileStateCache` + `RebuildState.run`, and a property-test suite
> covering the §17 slice-2 invariants. Carry-forwards to v1.3 /
> Slice 4: **C14**, **C15**, and **S2-1** through **S2-10** (each
> with a durable home in [`design-rationale.md`](design-rationale.md)
> or §7.2 below). Next active slice: 3 (`BranchManager`,
> `PRWatcher`, `ProcessLock`, `SessionMonitor`).

## 0. How to read this

| Phase | Outcome | Source of detail |
|---|---|---|
| 0 — Slice 0 | CLI capabilities validated | [`slice-0/slice-0-report.md`](slice-0/slice-0-report.md) |
| 1 — Testability MVP | Forge ships its own next slice | `forge-design-1.2.md` §17 slices 1–4 |
| 2 — MLP | Pleasant single-repo daily-driver | §17 slice 5 + polish |
| 3 — v1.0 | Single-repo, OSS-ready, role-pluggable | §20 v2 candidates + role-trait refactor |
| 4 — v2.0 | Forge-instance pivot (multi-repo, daemon, parallel, containerised) | Needs its own design doc (`forge-design-2.0.md`) before work starts |
| 5 — v3.0+ | Agentic-dev cockpit (knowledge base, reactive review, triggers) | Concept notes only |

Phases are gates, not calendar quarters. Each gate has an explicit exit
criterion; we don't move on until the prior phase actually passes it.

---

## 1. Phase 0 — Slice 0 (complete)

Done 2026-05-25. Findings folded into design v1.1: Native schema on both
reviewers, session-id preserved across resume on both CLIs, three small
Codex-adapter notes. No scope narrowing.

---

## 2. Phase 1 — Testability MVP

**Exit criterion:** Rory drives one real, small feature on the Forge repo
itself through Forge end-to-end from the command line. This is the
self-hosting moment — not "all the commands compile."

Maps 1:1 to design §17 slices 1–4. Nothing to invent at the spec level;
risks are integration-shaped.

### 2.1 Slice 1 — Agent connectors (≈ week 1)

`forge-agents` standalone with CLI demo + integration tests.

- [x] `AgentSession`, `StreamingSession`, `Connector` traits per §7.1.
- [x] `Role` indirection seam (Phase 4/5 enabler — see §2.6 below).
- [x] Codex price-table (`PriceTable` + `ModelPrice` + `CodexTokens`) and
  shipped `prices.example.json` resource covering the current Codex
  lineup (`gpt-5-codex`, `gpt-5.1-codex{,-max,-mini}`, `gpt-5.2-codex`,
  `gpt-5.3-codex`, `codex-mini-latest`). Formula follows OpenAI's usage
  shape (cached as subset of input, reasoning as subset of output).
- [x] Codex system-prompt prepending (`CodexPrompt.withSystemBlock`,
  §7.10(a)).
- [x] Codex sticky-settings rule (`CodexSessionSettings` value type +
  `isCompatibleForResume`, §7.10(c)).
- [x] `ClaudeConnector` and `CodexConnector`, both
  `schemaMechanism = Native`. Slice 1 covered: event parsers,
  `Subprocess` + `StreamingDriver` plumbing (`send` JSON-encoder hook,
  `UserMessage` mirror event, `initialUserInput`, `encodeAnswer` hooks
  per v1.2 §7.1), Claude + Codex headless driver methods, Layer 5
  reviewer one-shots (`reviewDesign` / `reviewPr` / `refine`) with
  shared `ReviewDecoders` + `ReviewerPrompts` and typed retryable vs
  non-retryable adapter errors, `AgentEvent.AskUserQuestion` carrying
  `toolUseId: Option[String]`,
  `StreamingSession.answerQuestion(toolUseId, answer)` plumbed through
  `StreamingDriver` with a connector-supplied `encodeAnswer` hook,
  `ClaudeConnector.runStreamingSpec` / `resumeStreamingSpec` against
  the §7.2 `tool_result` frame, `CodexConnector` streaming via the
  multi-process `CodexStreamingSession` facade (one `codex exec
  [resume] --json` subprocess per turn under
  `cats.effect.std.Mutex`, single shared events Channel with
  resume-turn Init filtered, thread-id mismatch raises, per-turn
  failure surfaces non-zero exit / missing Result to the caller).
  Closed 2026-05-26 by [`design-2.1.md`](design-2.1.md) PR-A → PR-E.
- [x] `HaltWithQuestion` parsing + re-spawn loop for Codex. Envelope
  decoder (`HaltWithQuestion.detect` / `tryParse`) lands in this
  slice; the orchestrator-side re-spawn loop lands with slice 2
  (FSM) — that's an orchestrator concern, not a connector one.
- [x] Integration tests on real CLIs. Claude headless hello-world
  smoke, `ClaudeStreamingSpecSuite` (resume preserves session id,
  kill mid-stream, `answerQuestion` end-to-end against a contrived
  `AskUserQuestion`), Codex headless smoke, `CodexStreamingSpecSuite`,
  and `CodexHaltWithQuestionReliabilitySuite` (opt-in via
  `FORGE_IT_RUN_RELIABILITY=1`) land in `forge-it`. The reviewer
  ≥19/20 native schema regression suite (PR-D) is deferred to the
  reviewer-asset PR per design-rationale **C15**; fake-CLI
  end-to-end reviewer coverage in `*ConnectorSuite` is the Slice-1
  bar.

✅ **Slice 1 closed 2026-05-26.** Detailed history of how it got
there (PR-A through PR-E) lives in
[`design-2.1.md`](design-2.1.md) §3 (status log) and §4
(carry-forward to v1.3). Carry-forward bullets — **C14** (Codex
`resumeStreamingSpec` system-prompt prepending) and **C15** (PR-D
deferred to Slice 4 reviewer-asset PR) — have durable homes in
[`design-rationale.md`](design-rationale.md) and §7.2 below.

### 2.2 Slice 2 — FSM, Feature, ActionLog, StateCache (≈ week 2)

- [x] `ForgePaths(repoRoot)` owns every `.forge/` location; build-gated
  smell test (`ForgePathsSuite` `os.walk` sweep) blocks new
  `".forge` literals outside the helper.
- [x] Manifest data types relocated from `forge-specs` to `forge-core`
  (`io.forge.core.manifest`); spec deviation tracked as **S2-1** in
  [`design-rationale.md`](design-rationale.md).
- [x] `FsmState` / `FsmEvent` / `Feature` / `ResumeHint` / `Action`
  domain model with codecs (PR-B). `PrSnapshot` ADT and core-side
  reviewer-verdict projections live in `forge-core` per v1.2 §3.2.
- [x] `Fsm.transition(feature, event, config): (Feature,
  Vector[ActionDraft])` — pure, covers every §11 lifecycle rule.
- [x] `FileActionLog` (NDJSON append-only, `APPEND | SYNC`, replay
  truncate-and-recover on partial trailing line) and
  `Feature.foldEvents` projecting every §6.1 field plus
  `observedTransitions` / `observedPieceMerges` for reconcile.
- [x] `FileStateCache` (atomic temp + `Files.move(ATOMIC_MOVE)` + parent
  fsync) and `StateCache.verifyAgainstLog` per §11.0 step 4.
- [x] `RebuildState.run(featureId, paths, manifestStore, log, cache)`
  with pure `reconcile` over the four §11.5 partial-merge sub-cases.
- [x] Property-test suite covers §17 slice-2 invariants 1–13; invariant
  14's writer-side test is deferred to Slice 4 (S2-5).

✅ **Slice 2 closed 2026-05-26.** Detailed history of how it got
there (PR-A through PR-G) lives in
[`design-2.2.md`](design-2.2.md) §3 (status log) and §4
(carry-forward to v1.3). Carry-forward bullets — **S2-1** (manifest
relocation), **S2-2** (FsmEvent ADT shape), **S2-3** (ActionLog
durability), **S2-4** (PrSnapshot ownership in `AGENTS.md`), **S2-5**
(writer-side atomic-merge test, Slice 4), **S2-6**
(`designPrFeedbackRound` projection), **S2-7** (`fsm.transition`
payload encoding), **S2-8** (`SettleTimeout` reviewer/refine phase
coverage), **S2-9** (`verifyAgainstLog` cache-write skip on
Consistent), **S2-10** (`audit.piece_merged` payload key tightened
to `"p"` only) — have durable homes in
[`design-rationale.md`](design-rationale.md) and §7.2 below.

### 2.3 Slice 3 — BranchManager, PRWatcher, ProcessLock, SessionMonitor (≈ week 3)

Per §17 slice 3. End-to-end test against a sacrificial GitHub repo: branch,
push, PR, observe watcher → merge transitions.

### 2.4 Slice 4 — Headless loop + line-mode REPL (≈ week 4)

Wire 1–3 together. Commands per §17 slice 4. **TUI deferred.**

**MVP gate:** drive a small feature through Forge — recommendation: have
Forge build its own Slice 5 starting branch (manifest a single piece,
`forge spec` interactively, then `forge run`). If that round-trips cleanly,
the MVP holds.

### 2.5 Targeted polish in Phase 1

These are MVP-gate enablers, not "nice to haves" — Forge is unusable
without them:

- Clear human-readable rendering of every `NeedsHumanIntervention` reason +
  its `ResumeHint` (six paths). The CLI says exactly what to run next.
- `forge status` outputs something a human can read at a glance: current
  state, current piece, last action, budget remaining.
- `.forge/log/<feature>.jsonl` tailable via `forge tail <feature>`.
- `forge rebuild-state` proven on a corrupted cache.

### 2.6 Architectural seams to leave open in Phase 1

We know Phase 4 is coming; leaving seams costs almost nothing now. Both
of the explicit seams below are folded into design §17 (Slice 1
role-trait, Slice 2 paths helper) so they ship as part of the v1 work
rather than as a separate "Phase 4 prep" pass:

- **Paths helper (design §17 Slice 2).** Every `.forge/` location
  resolved through a single `ForgePaths` object; no caller hardcodes a
  `.forge/...` literal. Phase 4 swaps the constructor to re-root
  state/log/lock at `~/.forge/instances/<name>/` while leaving
  in-repo specs/audit alone. Test rule: `grep '"\.forge/'` outside the
  helper is a smell.
- **Role-trait stub (design §17 Slice 1).** Connectors and orchestrator
  callers route through a thin `Role` indirection (`Role.Driver`,
  `Role.Reviewer`) instead of pattern-matching on `Mode`. v1 keeps the
  two-case `Mode` ADT; the seam is purely about call-site discipline so
  the Phase 3 *full* role-trait refactor (§4.2) has nothing to
  disentangle. Caller-side rule: `match m: Mode` outside `Mode` itself
  and connector construction is a smell.
- **`.forge/state/.lock` scope is "this checkout".** Don't assume it's
  the only lock in the world. Phase 4 introduces an instance-level lock
  above it.
- **No global singletons.** Pass config/paths through; don't reach for
  process-wide statics.

That's it. Don't invent daemon hooks, container hooks, or workstream
abstractions in Phase 1 — the role-trait *stub* is the only Phase 3/4
seam that lands earlier than its phase, and only because the cost of not
doing it grows with every connector caller written against `Mode`
directly.

---

## 3. Phase 2 — MLP

**Exit criterion:** you'd choose Forge over running Claude Code directly
for any new feature on this repo.

Maps to design §17 slice 5 plus the polish that only real use surfaces.

### 3.1 Slice 5 — TUI

Termflow + Elm architecture. Panes per §3.1: status, active (streaming /
log tail / Q&A / idle). Subjective; iterate based on what feels wrong
during real use.

### 3.2 Prompt iteration

The four role prompts (driver-spec, driver-implement, reviewer-design,
reviewer-code) ship with v1 placeholders. After ~5–10 real features:
revise based on observed failure modes, not on lab fixtures. Track
prompt diffs in git; they're load-bearing for behaviour.

### 3.3 OSS-readiness scaffolding

- README that's actually useful to a stranger.
- Config templates committed (`.forge/config.example.json`).
- `prices.example.json` kept current with OpenAI model list.
- Pointer to design doc + rationale from README.
- LICENSE already in place.

---

## 4. Phase 3 — v1.0

**Exit criterion:** you'd recommend it to a friend with a Claude/Codex
license, on their single repo, with a straight face.

### 4.1 v2 candidates from design §20, picked by lived experience

Pick the ones the bug log and prompt log actually justify; don't carry
the whole list. My prior on most likely picks:

- **Process-tuning loop** — replay action logs to suggest prompt/FSM
  tweaks. Highest leverage if Phase 2 prompt iteration becomes a chore.
- **Stacked PRs onto a per-feature integration branch with composite CI**
  — pick this if rerunning full CI per piece becomes the dominant cost.
- **GitLab adapter** — pick this when you actually need it (likely yes,
  given the work projects). The `PrSnapshot` ADT already keeps the seam
  clean.

Probably skip in Phase 3 (re-evaluate in Phase 4):

- Auto-merge — value only if Phase 2 surfaced "clicking Merge on clearly
  fine PRs" as friction.
- Parallel features — wait for the Phase 4 pivot; bolting it onto v1's
  single-feature model is wasted work.

### 4.2 Role-trait refactor (architectural seam for Phase 4–5)

This is the one architectural change Phase 3 should fund even if no
feature visibly requires it. Phase 1's role-trait *stub* (§2.6) means
this is a refactor of `Mode`'s implementation and the connector
factories, not a sweep through every caller — call sites already speak
to `Role`, not `Mode`.

- Generalise `Mode` (sealed 2-case ADT) into role traits — `Driver`,
  `Reviewer`, and a base `Agent` for future roles (knowledge-base
  consultant, PR-watcher, etc.).
- The two concrete modes (`ClaudeDriver`, `CodexDriver`) become
  configurations of those traits, not enum cases.
- Phase 4's daemon + Phase 5's reactive review and knowledge-base agent
  all depend on this. Doing it in Phase 3 (when there are still only two
  concrete pairings) is much cheaper than doing it during the Phase 4
  pivot.

### 4.3 Hardening

- Documented escape hatch for every `NeedsHumanIntervention` reason.
- Bug log triaged; recurring failure modes covered by tests.
- Prompt-injection / sandbox-escape audit of both driver paths.

---

## 5. Phase 4 — v2.0: Forge-instance pivot

**This is the big architectural change**, and it should land as a single
phase because the four sub-pieces unlock each other:

- You can't do parallel workstreams cleanly without per-checkout
  isolation → containers.
- Containers are wasted overhead unless something supervises and
  observes them → daemon.
- A daemon owning multiple containers needs to know what set of repos
  and workstreams it speaks for → instance scope.
- An instance scope only earns its weight if more than one workstream
  runs at a time → parallel.

**Treat this phase as needing its own design doc** — `forge-design-2.0.md`
— before any code lands. The v1 spec (1.1 / 1.2) explicitly rejects
pieces of this (`Multi-repo / monorepo split work`, `Long-running
daemon`, `Parallel features`, `Worktrees devcontainer-incompatible`)
and those rejections are correct *for v1*; v2 revisits them with a
different set of constraints.

**Exit criterion:** one Forge instance manages the llm4s family (≥2
repos), running ≥2 workstreams concurrently in containers, with the TUI
attaching to / detaching from the daemon cleanly.

### 5.1 Forge instance

- New top-level concept: an instance owns N repos, M workstreams, its
  own config, prompts, and (Phase 5) knowledge base.
- Likely layout: `~/.forge/instances/<instance-name>/` with `repos/`,
  `workstreams/`, `config.json`, `prompts/`, `state/`, `log/`.
- Per-repo `.forge/` (committed: specs, manifests, audit) remains
  inside each repo — that's the right home for review history.
  Per-instance `.forge/state/` and `.forge/log/` move out of the repo
  into the instance directory.
- `forge init-instance <name>` / `forge add-repo <path>` / `forge
  list-repos`.

### 5.2 Workstream

A workstream is *the unit a developer thinks about*; a feature is the
unit Forge implements. A workstream can span:

- One feature in one repo (the common case, isomorphic to today's
  Feature).
- Multiple features in one repo (parallel work on different pieces of
  one project).
- Coordinated features across multiple repos (e.g. an llm4s-core
  change needs a matching termflow update).

The workstream object tracks goal, current state, active feature(s),
next feature(s), and (Phase 5) a backing issue.

### 5.3 Daemon mode

- Long-running supervisor; TUI is one client. CLI commands remain the
  primary scripting interface; both talk to the same daemon via Unix
  socket.
- Polling cadence per-feature (the existing 30s rule) becomes
  per-workstream; the daemon can multiplex.
- Daemon owns the instance lock; per-workstream locks live below it.

### 5.4 Containerised execution

The user's stated goals — parallelism, observability, reproducibility,
isolation, broad-permission agent runs without cross-contamination — all
point at "every workstream runs in its own container with a clean
checkout, pinned tool versions, and host-isolated permissions."

- Each workstream is one container (not one-per-piece) — the workstream
  is the unit that has a coherent checkout.
- Container has: claude, codex, gh, scala/sbt/node/etc. pinned per
  repo's `Forgefile` or equivalent.
- Logs, processes, ports are inspectable from the daemon's status API
  and surfaced in the TUI — this is the CMUX-style visibility layer.
- Worktrees stay rejected (v1 spec §1); containers are *not* worktrees,
  they're isolated checkouts of full clones. Different mechanism, same
  goal of "concurrent work without colliding."
- CMUX integration, if it happens, is a viewer over the container
  status feed, not a replacement for the daemon.

### 5.5 Parallel features

- Drop the v1 non-goal.
- Concurrency unit is the workstream; budgets become per-instance and
  per-workstream.
- Cost-cap enforcement still happens per session (unchanged) but
  aggregate budgets need a fan-in.

---

## 6. Phase 5 — v3.0+: agentic-dev cockpit

These are directionally clear but conceptually fluid. Each will earn its
own mini-design when its phase starts. Ordered by leverage:

### 6.1 Reactive PR review (≈ first Phase 5 capability)

Watch GitHub/GitLab for incoming PRs Forge didn't create; review against
project guidelines + project state; post inline comments.

- Reuses the reviewer connector and the §10.2 line-based comment posting
  path. Most of the code already exists.
- Triggering: poll the org/repo PR list on a slow cadence (5–15 min);
  webhooks remain rejected (§22) — polling stays the model.
- This is the first capability that *exercises* the Phase 3 role-trait
  refactor; without it, a reactive reviewer is an awkward special case.
- Optional second-order: configurable "custom action triggers" (e.g.,
  on PR with label `hal`, post a `/hal` comment to fire an internal
  workflow). Cheap once the watcher exists.

### 6.2 Workstream ↔ issue tracker

- Source of "goal, progress, active/next" for each workstream.
- Adapters for Jira / Linear / GH Issues. Read-mostly initially; write
  on milestone events (PR opened, merged).
- This is what makes the TUI workstream pane actually useful instead of
  a glorified `git log`.

### 6.3 Queriable knowledge base

- RAG over: in-repo design docs, audit logs, issue-tracker history,
  optional external sources (Slack channels, Confluence).
- Two interfaces:
  - TUI query pane for the developer.
  - **MCP server** for the driver/reviewer to consult during normal
    work. The MCP path is probably the higher-leverage one — it lets
    the agent answer "what was decided about X" without a human in
    the loop.
- Cleanest to scope per-instance, not per-repo.

### 6.4 Container support/debug tooling

- Log tails, process lists, port maps, restart, attach — all per
  workstream container, surfaced through the daemon's status API and
  the TUI.
- CMUX integration likely fits here as the visualisation layer.
- Lowest priority of the Phase 5 set: useful, but only after 6.1–6.3
  prove the cockpit framing is actually right.

---

## 7. Divergences from the v1 spec

Tracked here so they don't surprise anyone mid-implementation. None
require changes to the v1 contract (1.1 / 1.2); all are deliberately
deferred to Phase 3+.

| Long-term direction | v1 spec stance | Phase that resolves it |
|---|---|---|
| Forge instance per project group | §1 non-goal: "Multi-repo / monorepo split work" | 4 — promote instance to first-class concept |
| Containerised execution | §1 rejection refers to *worktrees + devcontainers as working tree*; containers as *runtime* are a different design point | 4 — re-decide explicitly in `forge-design-2.0.md` |
| Long-running daemon | §1 non-goal: "Forge runs on the user's laptop, lifetime = TUI session" | 4 — daemon mode lands with instance pivot |
| Parallel workstreams | §1 non-goal: "Parallel features" | 4 — workstream replaces feature as concurrency unit |
| Role-pluggability | §20 v2 candidate: "Third-party agents / arbitrary role pairings" | 3 — generalise `Mode` to role traits before Phase 4 |
| Reactive PR review | Not in spec; §22 explicitly rejects webhooks (polling stays acceptable) | 5 — reuses reviewer + comment-posting path |
| Knowledge base / RAG | Not in spec | 5 — new module, MCP-exposed |
| Custom action triggers (`/hal`-style) | Not in spec | 5 — cheap addition once reactive watch exists |
| GitLab support | §20 v2 candidate | 3 if needed by work projects, otherwise 4 |

### 7.1 Two tensions worth resolving in Phase 3, not later

1. **`Mode` as sealed 2-case ADT vs. role-traits.** The longer code is
   written against the 2-case shape, the more it costs to generalise.
   Phase 3 funds the refactor while there are still only two concrete
   pairings.
2. **`.forge/state/` and `.forge/log/` location.** Committed audit/specs
   stay in-repo (correct, §4). Local state/log paths get routed through
   a helper from Slice 2 onward (see §2.6 and design §17 Slice 2) so
   Phase 4 can re-root them at an instance directory without touching
   every callsite.

### 7.2 Known v1.2 spec/code gaps deferred to v1.3 (or next-revision spec)

Surface here so they don't get lost between sub-PRs. Each one has an
explicit deferred-decision entry in
[`design-rationale.md`](design-rationale.md); the durable home for
"what v1.3 must close" lives there.

- **C14 — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a)
  system-prompt prepending.** Shared trait signature carries no
  `systemPromptPath`. v1.3 must either widen the trait (drags Claude
  through a parameter it doesn't need) or drop the §7.10(a) "applies
  to resume" claim. Surfaced by Slice 1 PR-C; the orchestrator's
  resume code path (Slice 2 FSM) must be written aware of it.
- **C15 — Native schema regression suite (PR-D) deferred from Slice 1
  to the reviewer-asset PR (Slice 4).** v1.2 §17 names the ≥19/20
  regression suite as Slice-1 integration coverage; real-CLI
  measurement requires shipped reviewer schemas + system prompts
  which land alongside `ForgePaths` in Slice 4. PR-D becomes a gating
  check on that PR; v1.3 §17 reorganisation should absorb the move.
- **S2-1 — Manifest data types live in `forge-core`, not `forge-specs`.**
  v1.2 §3.2 names "Manifest" under `forge-specs`; the implementation
  places `Manifest` / `ManifestPatch` / `Piece` / `PieceStatus` under
  `io.forge.core.manifest` to break a cyclic dependency. v1.3 §3.2
  needs the re-attribution; full rationale in `design-rationale.md`
  S2-1.
- **S2-2 — `FsmEvent` ADT shape not enumerated in v1.2.** v1.2 §17 names
  `FsmEvent` but doesn't spell out the variants. Slice-2 PR-B settled
  the 20-variant list; the chosen variants are the de-facto contract
  for Slice 3 (`PRWatcher` / `BranchManager` / `SessionMonitor`) and
  Slice 4 (orchestrator loop). v1.3 should lift the variants into §6
  or §11 as appropriate; full rationale in `design-rationale.md` S2-2.
- **S2-3 — `ActionLog` write durability vs. throughput.** v1.2 §19 says
  "append-only" without specifying fsync. The Slice-2 impl uses
  `APPEND + SYNC`; if Slice 4 surfaces a perf cliff under high write
  rates, the fallback is per-batch `force()` and the trade-off needs
  documenting. Full rationale in `design-rationale.md` S2-3 (only
  opens as a v1.3 spec gap if the cliff actually fires).
- **S2-4 — `PrSnapshot` ownership doc mismatch in `AGENTS.md`.** v1.2
  §3.2 places `PrSnapshot` in `forge-core`; the Slice-2 implementation
  matches the spec. The pre-Slice-2 `AGENTS.md` module-layout table
  placed it in `forge-git`; PR-G corrected the table. No v1.3 spec
  change needed (§3.2 already correct); full note in `design-rationale.md`
  S2-4.
- **S2-5 — Writer-side atomic-merge ordering test deferred to Slice 4.**
  Slice 2 F13 covers the *reader* side of §11.5 step 1 (replay
  correctly recovers from a crash between manifest write and FSM
  transition). The *writer* side (assert the orchestrator atomically
  persists `manifest.json` before the state-cache write) needs an
  orchestrator under test, which lands with Slice 4's headless feature
  loop. Tracked in `design-rationale.md` S2-5.
- **S2-6 — `Feature.designPrFeedbackRound: Int` projection not in v1.2
  §6.** v1.2 §11.3 says `DesignPrFeedback(prNumber, round + 1)` but the
  §6 `Feature` case class as written doesn't carry the counter the
  "+1" reads from. The Slice-2 implementation adds the field with a
  default of 0; v1.3 §6 needs the field added to `Feature` (and
  optionally a §11.3 sentence naming the counter source). Full
  rationale in `design-rationale.md` S2-6.
- **S2-7 — `fsm.transition` payload encodes full `FsmState`, not the
  class-name tag.** v1.2 §19's worked example shows `"from":
  "PieceImplementing"` as a bare string. The Slice-2 implementation
  emits the full `FsmState` JSON via uPickle's enum encoding so
  parameterised states (with piece id, prNumber, round, startedAt) can
  round-trip; singleton cases still serialise as bare strings (so the
  §19 example is the singleton-case form of the encoding). v1.3 §19
  needs the example lifted to a parameterised case; full rationale in
  `design-rationale.md` S2-7.
- **S2-8 — `Fsm.transition` doesn't handle `SettleTimeout` for three
  `SessionPhase` variants.** `Spec` / `DesignRevision` / `Implement` /
  `Fixup` route to `NeedsHumanIntervention` with a phase-appropriate
  hint; `DesignReview` / `CodeReview` / `Refine` fall through to a
  silent no-op. The orchestrator can route around this via
  `HarnessError`, but the contract isn't documented. v1.3 §11 should
  either add explicit reviewer/refine settle-timeout handlers in the
  FSM or document that the orchestrator must convert them to
  `HarnessError`. Tracked in `design-rationale.md` S2-8.
- **S2-9 — `StateCache.verifyAgainstLog` always writes the cache, even
  on Consistent.** The current implementation calls `RebuildState.run`
  unconditionally, which always calls `cache.save` at pipeline step 6
  — so every consistency check writes a temp file, renames it over the
  target, and fsyncs the parent. Fine in tests; potentially a perf
  cliff in long-running Slice-4 orchestrator sessions. Slice 4 needs
  to either compare to a cheap fingerprint (manifest mtime + last log
  seq) before running the full rebuild, or have `RebuildState.run`
  skip `save` on byte-equal match. Tracked in `design-rationale.md`
  S2-9.
- **S2-10 — `audit.piece_merged` payload key tightened to `"p"`
  only.** PR-D `Replay.applyAuditPieceMerged` originally accepted both
  `"p"` and `"piece"` as the piece-id key, but writers only ever emit
  `"p"`. The fallback was dead and risked silently rewriting a
  hand-edited log on next repair. PR-G removed the alias. v1.3 §19
  should keep `"p"` as the canonical wire form (matching the §11.5
  step 1 wording). Tracked in `design-rationale.md` S2-10.

---

## 8. What this roadmap deliberately does *not* do

- Lock calendar dates. Phases are gates.
- Promise specific v2 candidates from §20. Pick by lived experience.
- Pre-design Phase 4. It needs `forge-design-2.0.md` before any code,
  written when Phase 3 is close to shipping (so the constraints are
  fresh).
- Commit to CMUX. CMUX is one possible viewer for the Phase 4
  container status feed; the daemon's status API is the actual
  contract, and any viewer (CMUX, a web dashboard, the TUI itself) can
  consume it.
