# design-3.5 — §3.5 deferred Phase-2 follow-ups (driver-respawn-avoidance + §18 tuning)

> **Maps to:** [`roadmap.md`](roadmap.md) §3.5 ("Deferred to a later
> Phase-2 slice"), the two still-open bullets: **driver-respawn-avoidance
> (the D3 large half)** and **reviewer/driver §18 tuning (S4-3 / S4-5)**.
> Source context: [`design-2.0.md`](design-2.0.md) §4 (D3 disposition) and
> [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md) gap #10.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. §3.5 was worked inline through Slice 2.0 close-out (the small
> durability fixes + driver-respawn-avoidance Units A/B), but the remaining
> **D3 large half** is big enough to warrant the per-task breakdown this
> file owns. The roadmap §3.5 bullets stay terse; chunks get ticked here as
> they land, and the roadmap bullet flips only after a section-level review.
>
> **Status:** 🟡 open — 2026-06-01. Active focus: the **D3 large half**
> (driver-respawn-avoidance on resume-from-NHI), decomposed into five
> riskiest-first chunks (D3-0 … D3-4). The §18 tuning track (S4-3 / S4-5)
> is parked here too but not yet started — it wants more lived
> cost-attribution data first.

## 0. Background — what Units A/B already closed, and what D3 leaves open

The driver-respawn-avoidance bullet (roadmap §3.5) has three layers; the
first two are done:

- **Unit A** (idempotent `classifyCommitOpenPr` via `GhClient.prForBranch`,
  landed 2026-06-01) — a resume that re-runs the post-settle side effect no
  longer fails on "a pull request already exists for branch …".
- **Unit B** (`monitor.outcome` writer + `postSettleRecover`, landed
  2026-06-01) — the **post-settle crash window** (driver settled clean,
  crashed before the `PrOpened`/settle transition persisted) now recovers
  via the idempotent side effect **without re-spawning the driver**.
- **D3 large half** (this plan) — the **mid-exploration crash window**: the
  driver was killed/crashed *while still exploring* (uncommitted edits on
  disk, no settle reached). Today this projects to
  `RebuildState.inFlightSessions` → `RestartRecovery` → `NHI`, and the
  operator's resume **re-spawns the driver from scratch**, re-paying the
  full exploration (~\$10 / 2.18M tokens in the szork run, gap #10).

The constraint that makes D3 the "large half" is real and stated in code
([`RestartRecovery.scala:11-15`](../modules/forge-app/src/main/scala/io/forge/app/orchestrator/RestartRecovery.scala)):
transparent resume is deliberately refused because (a) streaming sessions
have no in-flight message to re-issue, and (b) **headless worktrees may
carry partial uncommitted changes**. Closing D3 means making (b) a
*checkable* condition rather than a blanket refusal, and giving the
headless implement/fix-up drivers a `--resume` capability they do not have
today (only the spec phase has `resumeStreamingSpec`;
[`ClaudeConnector.scala:80`](../modules/forge-agents/src/main/scala/io/forge/agents/ClaudeConnector.scala)).

## 1. Exit criterion for the D3 large half

A resume from an implement/fix-up `NeedsHumanIntervention` (mid-exploration
crash) **continues the existing driver session via `--resume` rather than
re-spawning it from scratch**, whenever the piece worktree is in the
expected driver-uncommitted state — and `forge stats` shows the avoided
re-exploration as a measured saving. Concretely:

1. A real-CLI capture proves headless `--resume` continues a killed
   exploration in a fresh process with prior context intact and the first
   run's uncommitted edits preserved (D3-0) — or the connector(s) for which
   it does **not** hold are documented and fall back to fresh spawn.
2. The headless implement/fix-up driver path can be resumed with a durable
   session id (D3-1).
3. A worktree-safety classifier distinguishes the expected
   driver-uncommitted state from unexpected divergence (operator edits /
   committed work) so resume is gated, not blind (D3-2).
4. The orchestrator resumes-instead-of-respawns automatically once the
   classifier reports safe (D3-3, **default-on** per the 2026-06-01
   decision); an unsafe worktree still routes to NHI.
5. The saving is measurable: a resumed turn is distinguishable from a fresh
   spawn in the log/stats (D3-4).

## 2. Chunk breakdown — D3 large half

Riskiest-first (CLAUDE.md "run code earlier"). Each chunk is an
independently committable PR with its own tests, in the Units-A/B rhythm.

### [x] D3-0 — De-risking spike (forge-it, real CLI) ✅ **GO (both connectors), 2026-06-01**

Prove the central assumption before building anything on it. Kill a
headless `claude` / `codex` run mid-exploration, then `--resume <sessionId>`
from a **fresh process** with a continuation prompt, and verify:

- (a) the session id is honoured across process death;
- (b) the resumed run continues with prior context (does **not** re-explore
  from scratch — measure tokens / turn count to show the delta);
- (c) the first run's uncommitted file edits are intact on disk and not
  clobbered by the resume.

Capture transcripts under `docs/slice-?/fixtures/` (or a `design-3.5/`
capture dir) and pin a go/no-go note + the **connector support matrix** in
[`design-rationale.md`](design-rationale.md). Codex `exec resume` is
stateless and re-prepends the system block
([`CodexConnector.scala`](../modules/forge-agents/src/main/scala/io/forge/agents/CodexConnector.scala)) —
it may not preserve exploration; Claude `--resume` likely does. This chunk
decides which connectors get D3-1 and which fall back to fresh spawn.

- *Deliverable:* opt-in `forge-it` spike suite (`FORGE_IT_RUN_*` gated per
  the <60s default-on rule) + a rationale note. **No production code.**
- *Gate:* if `--resume` does not recover exploration on either connector,
  the whole large half is reframed (e.g. to "commit-and-continue from the
  on-disk diff" rather than session resume) before D3-1 starts — surface
  that via `AskUserQuestion`, do not silently pivot.

### [x] D3-1 — Headless driver resume connector seam (forge-agents) ✅ **2026-06-01 (real-CLI run GREEN; C19#1 resolved)**

Add resume variants of the headless implement/fix-up driver methods (a
`resume: Option[String]` arg threading `--resume <sessionId>` for Claude /
`exec resume <thread-id>` for Codex), mirroring `resumeStreamingSpec`'s argv
shape. D3-0 proved **both** connectors restore context, so both get the seam
(no fall-back needed). The D3-0 spike's inline resume argv is the prototype.

- *Deliverable:* fake-CLI unit tests asserting the `--resume` flag lands in
  the argv + the session-id invariant, **paired with a forge-it IT** (the
  "fake-CLI must mirror real-CLI" discipline). Dead code until D3-3 — a
  clean tested seam, same as other connector seams shipped ahead of their
  caller.
- **Watch items from D3-0 (design-rationale C19) to resolve here:** (1)
  **Codex write-on-resume is unverified** — `execResumeArgv` passes no
  sandbox flag (§7.10(c)), and the spike's resumed turn was read-only; D3-1
  must exercise a *resumed Codex turn that edits files* and decide the
  sandbox story if it can't write. (2) Resumed Codex needs a git-repo/trusted
  cwd (satisfied by the real worktree). (3) Use absolute worktree paths in
  prompts (cheap models mis-resolve relative paths).

### [x] D3-2 — Worktree-safety classifier (forge-git) ✅ **2026-06-01**

A pure classifier over `git status` (+ branch state) →
`Clean | DriverUncommittedOnly (safe) | UnexpectedDivergence (unsafe)`.
This turns `RestartRecovery`'s blanket "worktree may have uncommitted
changes" refusal into a checkable gate: the *expected* mid-exploration
state (driver-authored uncommitted edits, no operator commits) is safe to
resume onto; anything else (committed work, operator edits, detached state)
stays NHI.

- *Deliverable:* unit tests with `FakeGitClient` across the cases.
- *Landed:* `io.forge.git.worktree.{WorktreeSafety, WorktreeSafetyClassifier}`
  (forge-git, not forge-app — the classification is a git-domain query over
  `GitClient`; D3-3 in forge-app supplies the policy of what to do with the
  verdict). `classify` is pure and table-tested; `classifyWorktree` gathers
  the three reads (`status` / `currentBranch` / `currentSha`) through a
  `GitClient` and applies it. Conservative-by-default: branch ≠ expected,
  HEAD ≠ expected (`Piece.baseSha`), any unmerged row, or an unresolvable
  `currentBranch` / `currentSha` read all resolve to `UnexpectedDivergence`;
  only a failed `status` read propagates as `Left`. `WorktreeSafetyClassifierSuite`
  (14 cases) covers the table + the `FakeGitClient` gather path.

### [ ] D3-3 — Orchestrator resume-instead-of-respawn (forge-app)

On a resume from an implement/fix-up NHI where `currentPieceSessionId` is
present (already durable post the §3.5 piece-spawn-durability fix), the
D3-2 classifier reports **safe**, and the connector supports resume (D3-0 /
D3-1): call the D3-1 resume seam with the durable session id instead of a
fresh `launchImplement` / `launchFixup`. Revisit `RestartRecovery` to offer
a gated "resumable" route in place of the unconditional NHI.

- **Default-on once safe** (2026-06-01 decision): resume happens
  automatically whenever the classifier reports safe — no opt-in flag. An
  unsafe worktree, a missing session id, or an unsupported connector falls
  back to the existing NHI/fresh-spawn behaviour. This revisits the "no
  transparent resume" stance more aggressively than an opt-in flag, so the
  D3-2 classifier carries the safety burden — it must be conservative
  (default to *unsafe* on any ambiguity).
- *Deliverable:* e2e orchestrator suite — a safe resume reuses the session
  id with no fresh spawn (the empty-pass monitor raises on any stray
  re-spawn, as in `OrchestratorPostSettleRecoverySuite`); a
  classifier-unsafe worktree still routes to NHI.

### [ ] D3-4 — Cost/stats proof + close-out

Emit a marker distinguishing a driver-*resumed* turn from a fresh spawn so
`forge stats` turns the gap #10 watch item into a measured saving.
Reconcile the new connector flag / marker into the next-revision spec
(`forge-design-1.x.md`, not the live 1.4 in place), walk the §4
carry-forward, flip the roadmap §3.5 bullet (after a section-level review),
and optionally re-validate on a real feature.

## 3. Parallel track — §18 reviewer/driver tuning (S4-3 / S4-5)

The other open §3.5 bullet. Not started; tracked here for visibility.

- [ ] Tune the hard-pinned C15 defaults (`reviewer.model` /
  `reviewer.wallClockCapSec` / `reviewer.processRetries`, driver settle
  caps) against attributed cost data now that Slice 2.0 makes it
  measurable. The szork run already flagged the implement settle cap as too
  tight (`maxTurnCostUsd = $2.0` vs an actual \$9.56 turn). Needs a §18
  schema extension → lands via a `forge-design-1.x.md` revision. **Wants
  more lived cost-attribution data before it starts** (the D3 work and a
  few real features will produce it).

## 4. Order of work

D3-0 first and gating. Then D3-1 and D3-2 can land in either order (both
feed D3-3); D3-1 is the bigger of the two. D3-3 depends on D3-0/D3-1/D3-2.
D3-4 closes. The §18 tuning track is independent and deferred until there
is attributed cost data to tune against.

## 5. Status log

- 2026-06-01 — Plan opened. Decomposed the D3 large half into D3-0 … D3-4
  (riskiest-first) after mapping the real code surface
  (`RestartRecovery`, `RebuildState.{inFlightSessions,settledButUnadvanced}`,
  the headless-vs-streaming connector resume asymmetry, `BranchManager` /
  `GhClient.prForBranch`, Units A/B). Two decisions recorded: companion doc
  created per the convention (rather than inline-in-roadmap as Units A/B
  were, because D3 is multi-chunk); and **D3-3 resume is default-on once the
  D3-2 worktree classifier reports safe** (not opt-in), pushing the safety
  burden onto a conservative classifier. Starting D3-0.
- 2026-06-01 — **D3-0 spike harness landed** (`forge-it`
  `DriverResumeSpikeSuite`); the analytical half (connector matrix +
  go/no-go note) is **pending a real-CLI run**, so the D3-0 box stays open.
  The suite captures the untested wire combination directly — a headless
  driver turn (`claude -p …` / `codex exec …`) interrupted, then resumed
  from a *fresh connector instance* via `claude -p … --resume <sid>` /
  `codex exec resume … <thread-id>` (built inline from the public
  `ClaudeConnector.{IsolationFlags,OutputFlags,driverPermissionFlags}` /
  `CodexConnector.execResumeArgv` + `StreamingDriver.fromSubprocess`
  seams, since no headless-resume *method* exists yet — that is D3-1).
  Three tests: **Claude codeword-recall** (decisive — turn 1 is told a unique
  codeword + writes `plan.md`; turn 2 resumes and is asked only for the
  codeword → recall ⇒ headless `--resume` restored context ⇒ a real resume
  saves the re-exploration), **Claude killed-mid-turn** (the realistic D3
  crash shape — kill after 8s, resume to a clean `Result`, partial on-disk
  edit survives), **Codex codeword-recall** (fills the matrix; surfaces
  whether stateless `exec resume` restores context and whether it can still
  write under no-sandbox-flag resume). Opt-in via `FORGE_IT_RUN_RESUME_SPIKE=1`
  (multi-minute, real token spend — per the "<60s default-on" rule) atop the
  usual PATH + `FORGE_IT_SKIP_{CLAUDE,CODEX}` gates; raw event vectors dumped
  under `FORGE_IT_RESUME_SPIKE_DUMP_DIR` for offline inspection. `forge-it`
  `Test/compile` green; the three tests **skip cleanly** with the gate off
  (no real-CLI calls). **Next:** run the spike against real CLIs, record the
  per-connector result + go/no-go in `design-rationale.md`, then tick D3-0.
- 2026-06-01 — **D3-0 run ✅ GO (both connectors); D3-0 closed.** Ran
  `FORGE_IT_RUN_RESUME_SPIKE=1` against real `claude` (2.1.x) + `codex`
  (gpt-5.3-codex); all three tests pass. **Claude**: fresh-process
  `claude -p … --resume <sid>` recalled the planted codeword exactly, preserved
  the session id, and the *killed-mid-turn* variant resumed and **continued the
  in-flight task to a clean `Result`** (the realistic D3 crash shape works).
  **Codex**: `codex exec resume` shared the identical `thread_id` across turns
  and recalled the codeword — context restored despite the CLI being stateless.
  Two harness fixes were needed and made (not findings): the codeword recall was
  decoupled from a flaky file-write check (a cheap model wrote to `$HOME`), and
  the Codex workdir is now git-init'd (codex refuses an untrusted non-git dir).
  Full go/no-go + **three watch items** (Codex write-on-resume unverified; codex
  git-repo/trust requirement; absolute-path prompting) filed as
  **design-rationale C19** and folded into D3-1's deliverables. **Next: D3-1**
  (headless driver resume connector seam, both connectors).

- 2026-06-01 — **D3-1 closed ✅ — seam landed + real-CLI IT GREEN; C19#1 resolved.** The
  `DriverResumeSeamSuite` ran `FORGE_IT_RUN_RESUME_SEAM=1` against real `claude` (2.1.159) + `codex` (0.133.0): both
  tests pass. **Claude** `resumeHeadlessDriver` recalled the planted codeword and the resumed turn **wrote the file**
  (15.6s). **Codex** `resumeHeadlessDriver` resumed the same `thread_id` and the resumed turn **wrote the file despite
  `execResumeArgv` passing no `--sandbox`** (24.5s) — confirming Codex resolves the sandbox from the sticky thread
  settings on resume (the original `exec` ran under `workspace-write`). This **resolves design-rationale C19 watch item
  (1)**: a resumed Codex implement/fix-up turn can edit files, so no sandbox-on-resume workaround is needed; watch items
  (2) git-repo/trust and (3) absolute-path prompting were already satisfied (the suite git-inits the workdir and uses
  absolute paths). **Next: D3-2** (worktree-safety classifier) — D3-1/D3-2 both feed D3-3.

- 2026-06-01 — **D3-2 closed ✅ — worktree-safety classifier landed (forge-git, pure + unit-tested).**
  Added `io.forge.git.worktree.{WorktreeSafety, WorktreeSafetyClassifier}`. `WorktreeSafety` is the
  three-way verdict (`Clean` / `DriverUncommittedOnly` — both `safeToResume` — / `UnexpectedDivergence`);
  `WorktreeSafetyClassifier.classify` is the pure decision tree over the durable expected state
  (`expectedBranch` + `expectedHead` = the manifest's `Piece.baseSha`) and the three git reads, and
  `classifyWorktree` gathers `status` / `currentBranch` / `currentSha` through a `GitClient` and applies it.
  **Conservative by construction** (it carries the safety burden for default-on D3-3): a different branch,
  detached HEAD, HEAD beyond `expectedHead` (operator/other commits), any unmerged porcelain row (`U*` / `*U`
  / `AA` / `DD`), or an unresolvable `currentBranch`/`currentSha` read all resolve to `UnexpectedDivergence`;
  only a failed `status` read propagates as `Left` (worktree shape genuinely unknown). Landed in **forge-git**
  rather than forge-app — the classification is a git-domain query; forge-app's D3-3 owns the resume-vs-NHI
  policy. `WorktreeSafetyClassifierSuite` (14 cases: pure table + `FakeGitClient` gather path) green;
  forge-git 213 unit tests, full `sbt compile` clean. **Dead code until D3-3.** D3-1 and D3-2 now both feed
  **D3-3** (orchestrator resume-instead-of-respawn, default-on once the classifier reports safe).

- 2026-06-01 — **D3-1 seam + unit/IT harness landed; real-CLI run pending.** Added
  `Connector.resumeHeadlessDriver(sessionId, systemPromptPath, message): IO[AgentSession]` — the headless driver-side
  analogue of `resumeStreamingSpec`, phase-agnostic (one method serves both implement- and fix-up-phase resume; the
  orchestrator tracks which). `ClaudeConnector` routes through the existing `spawnHeadless` plumbing with a new
  `ClaudeConnector.headlessResumeArgv` (`-p <message> … --resume <sid>`, **no** `--system-prompt-file` — server-side
  restore, mirroring `resumeStreamingSpecArgv`); `CodexConnector` re-prepends the §7.10(a) system block (C14) and reuses
  the existing `execResumeArgv` (no session-scoped flags per §7.10(c)). This promotes the D3-0 spike's inline resume
  argv into a first-class method. Both pinned CLIs get the seam (C19 GO). **Dead code until D3-3.** Tests: a Claude
  `headlessResumeArgv` argv unit test + a Codex `resumeHeadlessDriver` fake-CLI E2E (thread-id echo + §7.10(a) reprepend)
  in `forge-agents`, the four `Connector` test-fakes updated for the widened trait, and a paired opt-in `forge-it`
  `DriverResumeSeamSuite` (gated `FORGE_IT_RUN_RESUME_SEAM=1`) that drives the **real** method end to end — codeword
  recall + a resumed turn that **writes a file** on each connector. The Codex write-on-resume test directly resolves
  **C19 watch item (1)** (resume passes no `--sandbox`; does a resumed turn still write?). Unit suites green
  (`forge-agents` 205, `forge-app` 364). The box stays `[ ]` (D3-0 rhythm) until the real-CLI IT run confirms write-on-
  resume; on confirmation, tick D3-1 + fold the C19 resolution into design-rationale, then **D3-2** (worktree-safety
  classifier).

## 6. Cross-references

- Roadmap: [`roadmap.md`](roadmap.md) §3.5 (the two open bullets).
- D3 disposition + Units A/B history: [`design-2.0.md`](design-2.0.md) §4
  (D3) and the 2026-06-01 status-log entries.
- Friction source: [`slice-4/mvp-friction.md`](slice-4/mvp-friction.md)
  gap #10 (the 2.18M-token / \$9.56 implement turn that resume re-pays).
- Spec: [`forge-design-1.4.md`](forge-design-1.4.md) §7.1 / §7.10 (connector
  resume seams), §11.3 (restart recovery), §19 (action-log kinds).
- Code anchors: `RestartRecovery`
  (`forge-app/.../orchestrator/RestartRecovery.scala:11`), `RebuildState`
  (`forge-core/.../state/RebuildState.scala`), `ClaudeConnector` /
  `CodexConnector` (`forge-agents/.../`), `RealSideEffects.launch{Implement,Fixup}`
  (`forge-app/.../orchestrator/RealSideEffects.scala:77`), `GhClient.prForBranch`
  (`forge-git/.../cli/GhClient.scala:52`).
</content>
</invoke>
