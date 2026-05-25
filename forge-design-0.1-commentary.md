# Forge design v0.1 — review commentary

> Critical review of `forge-design.md` (v0.1). Findings are grouped by severity. Each item cites the original line range and proposes a direction. The companion document `forge-design-0.2.md` applies these decisions.

**Reviewer:** Claude  •  **Date:** 2026-05-24  •  **Target doc:** `forge-design.md` v0.1

---

## 1. Technical holes (likely to break in implementation)

### 1.1 FSM has no state for "design PR awaiting human merge"
**Where:** §5 (lines 160–178), §6.2 (lines 258–259), §9 (line 466)

§6.2 step 13–14 says Forge opens the design PR and waits for the human to merge before transitioning to `DesignReady`. But the FSM enum only has `DesignReviewing` and `DesignReady` — no waiting state. §9 then refers to "`DesignReady` waiting for design PR merge", which is a contradiction: by the time you're in `DesignReady` the merge has already happened.

**Decision for 0.2:** Add `DesignAwaitingMerge(prNumber: Int)` between `DesignReviewing` and `DesignReady`. The poller activates in this state.

### 1.2 `mergeStateStatus == "MERGED"` is wrong
**Where:** §6.4 (line 301)

`gh pr view --json mergeStateStatus` returns `CLEAN | DIRTY | BLOCKED | BEHIND | DRAFT | HAS_HOOKS | UNKNOWN | UNSTABLE` — never `MERGED`. Merged state lives on the `state` field (`OPEN | CLOSED | MERGED`) or on `mergedAt`. As written, the poll-parse will never advance pieces.

**Decision for 0.2:** Use `state == "MERGED"` (and check `mergedAt`/`mergeCommit` to confirm) in the parse logic; spell out the field choices in the doc.

### 1.3 Codex review schema can't produce inline comments
**Where:** §6.2 (lines 243–254), §6.6 (line 349)

The schema only has `where: string` and `issue: string` for blockers. §6.6 says "one inline comment per blocker" via `gh api`, which needs `path` and `line`/`position`. Free-text `where` won't reliably parse to a file:line pair.

**Decision for 0.2:** Tighten the schema to `path: string, startLine: integer, endLine: integer (optional), issue: string`. Allow `path: null` for cross-cutting blockers — those become summary-comment bullets, not inline.

### 1.4 `Failed` is terminal — no recovery path
**Where:** §5 (line 178), §6.2 (line 257), §6.5 (line 331)

Hit max review rounds or max fix-up rounds and the feature is dead. §6.5 says the PR is "left open" and the TUI "flags it for manual intervention", but the FSM has no way back. First flaky CI run nukes the feature.

**Decision for 0.2:** Split into `NeedsHumanIntervention(reason, resumeHint)` (non-terminal, resumable via `forge resume <feature>`) and `Abandoned(reason)` (terminal, only on explicit `forge abandon`).

### 1.5 DocSync requires direct push to `main`
**Where:** §7 (line 395)

Marking pieces `[x]` in `decomposition.md` as a "tiny commit on main" assumes you can push directly to main. Any repo with branch protection (which is most of the target) will reject. Opening a PR per checkbox update creates an absurd PR storm and an infinite Refinery loop.

**Decision for 0.2:** Don't sync on every piece merge. Update `decomposition.md` in the *next* piece's PR (which is opened off the new main anyway, so the change rides in cleanly). For the final piece, open one closing `chore(<feature>): mark feature complete` PR. Net: one or zero extra PRs per feature.

### 1.6 Piece-detail files appear from nowhere
**Where:** §6.1 (line 232), §6.3 (line 266), §7 (lines 387–390)

The implementation prompt reads `.forge/specs/<feature>/pieces/<p.id>.md`. §7 lists this file as authoritative. But the spec-phase consolidation only produces `design.md` and `decomposition.md`.

**Decision for 0.2:** The consolidation prompt also writes `pieces/<p.id>.md` per piece (one per heading in `decomposition.md`). Spell this out in the prompt and add a Forge-side post-check that every piece in the decomposition has a corresponding file before transitioning to `DesignReviewing`.

### 1.7 The Refinery blocks between pieces on full test runs
**Where:** §6.7 (lines 357–368)

`runShell("sbt compile test")` blocks; on a real Scala repo this can be 5–30 min. CI already runs this on the piece PR before merge. Running it locally again on main is dead time without new signal.

**Decision for 0.2:** Drop the local test run. Refinery becomes only the Codex "is the design still accurate?" check (cheap, schema-bounded). The CI-passing-on-the-PR gate already proves the code works on main-equivalent state.

### 1.8 Unverified Claude CLI flag combinations
**Where:** §3 (line 66), §6.1 (line 228), §6.3 (lines 270–276)

The doc relies on `--bare`, `--input-format stream-json`, `--permission-mode acceptEdits`, `--resume`, and `--append-system-prompt @file` working in combination. `--bare` in particular is not in the public Claude Code flag list under that name. Also: does `--bare` suppress *repo*-level `CLAUDE.md`/`.claude/` too, or only user-level? Material difference for project tooling.

**Decision for 0.2:** Treat all CLI flag claims as hypotheses until Slice 1 confirms them. Add an explicit "CLI assumptions to validate" subsection at the head of §10 Slice 1, and rename `--bare` usage to "the equivalent isolation flag (TBD in Slice 1)". The implementation prompt also explicitly opts *in* to repo-level CLAUDE.md so project hooks/lints continue to apply.

---

## 2. Implementation gaps (can't build from the doc as written)

### 2.1 No concurrency / re-entrancy story
Nothing prevents two `forge` processes from running on the same repo and racing the state DB. No lock file, no PID file, no detection of in-progress FSMs.

**Decision for 0.2:** `.forge/state/.lock` file with `flock(2)`. On startup, take an exclusive lock; if held, refuse to start and print the holder's PID. Add a `forge unlock --force` escape hatch with a "are you sure" prompt.

### 2.2 No pre-flight repo state checks
Forge switches branches in the user's working copy without checking for uncommitted changes, in-progress rebases, or whether `main` is current.

**Decision for 0.2:** BranchManager exposes `preflight(): IO[PreflightReport]` returning `clean | dirty(files) | rebasing | detached | behindMain(n)`. Forge refuses to start if not `clean`, with the same `--force` escape hatch. Document this as a hard precondition in §6.

### 2.3 PR number capture not specified
§6.3 step 18 just says "Opens a PR with `gh pr create ...`" then references `prNumber` in the next state. Where does the number come from?

**Decision for 0.2:** Use `gh pr create --json url -q .url` (or `gh pr view --json number`) and parse. Spell it out in the BranchManager interface.

### 2.4 "Session settle" is undefined
**Where:** §6.1 (line 234)

Stream-json emits a `result` event per *turn*, not per *session*. "Wait for settle" without a definition is unimplementable.

**Decision for 0.2:** Define settle as: "the next `result` event after the most recent user message Forge sent, with a 5-minute hard timeout". On timeout: abort the subprocess, log `harness.error`, transition to `NeedsHumanIntervention`.

### 2.5 "New comments since last seen" needs persistence
**Where:** §6.4 (line 300)

To detect new review comments, Forge must store last-seen comment IDs per PR. Not in the domain model.

**Decision for 0.2:** Extend `Piece` with `pollState: PollState` containing `lastSeenCommentId: Option[Long]`, `lastSeenReviewId: Option[Long]`, `lastSeenCheckRunIds: Set[Long]`.

### 2.6 Required vs optional checks not addressed
`statusCheckRollup` reports all checks. "Any check failed → fix-up" will trigger on flaky optional checks that don't gate merge.

**Decision for 0.2:** Use `mergeable` and `mergeStateStatus` as the primary signal; consult branch protection via `gh api repos/.../branches/main/protection/required_status_checks` to get the required set and fix-up only on failures within that set.

### 2.7 PR body generation is hand-waved
**Where:** §6.3 (line 286)

"`<body generated from pieces/<p.id>.md + acceptance>`" — no template.

**Decision for 0.2:** Define a `pr-body.md.hbs` template under `~/.forge/templates/` (or shipped with Forge install). Doc spells out the variables: `featureTitle`, `pieceTitle`, `pieceSummary`, `acceptanceCriteria`, `designLink`.

### 2.8 Path conventions are inconsistent
**Where:** §6.1 (lines 228, 232), §6.2 (line 241), §6.3 (line 275), §7 (lines 387–390), §11

Mix of `.forge/...` (dot-prefixed, in user repo) and `forge/templates`, `forge/prompts`, `forge/schemas` (no dot, ambiguous location).

**Decision for 0.2:** Two roots only:
- `.forge/` (in user repo): all per-feature data — `specs/`, `state/`, `log/`, `config.json`.
- `~/.forge/` (Forge install): all immutable templates — `templates/`, `prompts/`, `schemas/`. Overridable per-repo by mirroring path under `.forge/overrides/`.

Doc replaces every bare `forge/...` path with the appropriate one.

---

## 3. Architectural / clarity issues

### 3.1 Action log schema disagrees with the case class
**Where:** §5 (lines 201–209), §8 (lines 410–419)

Section 5's `Action` has `payload: ujson.Value`. Section 8's example shows top-level `from`/`to` keys.

**Decision for 0.2:** Top-level keys are `ts, feature, piece, kind`. Everything else lives in `payload`. Rewrite the §8 example accordingly.

### 3.2 State DB gitignore guidance contradicts itself
**Where:** §7 (lines 391, 401), §12 (line 585)

§7 says "commit state for crash recovery"; §12 says "state is a cached projection, replay the log". Both can't be true — committed state will conflict and drift.

**Decision for 0.2:** Commit the log (`.forge/log/`), gitignore the state (`.forge/state/`). State is always reconstructible via `forge rebuild-state <feature>` which replays the log. Document the invariant: "log is canonical, state is a cache".

### 3.3 "Interactive" Claude session isn't actually interactive
**Where:** §6.1 (lines 217, 228)

`claude -p` is non-interactive print mode. The TUI pumps stream-json over stdin/stdout to *simulate* interactivity. Calling it "interactive" will confuse future contributors reading the actual invocation.

**Decision for 0.2:** Call it "streaming subprocess driven by the TUI" throughout. Keep one sentence acknowledging it presents *as* interactive to the user.

### 3.4 GitLab abstraction is asserted but leaks
**Where:** §9 (lines 446–461), §12 (line 590)

Section 9's `GhPollingWatcher` hard-codes `gh` JSON fields into the FSM data path.

**Decision for 0.2:** Introduce `PrSnapshot` ADT in `forge-core` (provider-agnostic). The `gh` parsing lives entirely inside `forge-git`. The FSM only sees `PrSnapshot`.

### 3.5 No total-cost or budget cap
§8 logs `cost.update` events; nothing in §6 or §11 caps spend. A runaway fix-up loop can rack up real money silently.

**Decision for 0.2:** Add `maxFeatureCostUsd: number` and `maxPieceCostUsd: number` to config. FSM transitions to `NeedsHumanIntervention("budget exceeded", ...)` on breach. TUI shows running cost in the status pane.

### 3.6 Slice 4 "raw passthrough" is ambiguous
**Where:** §10 (lines 521–527)

Without a TUI, how does `forge spec` host an interactive stream-json session, intercept `AskUserQuestion`, and detect `/done`?

**Decision for 0.2:** Slice 4 ships a line-mode REPL that reads stdin lines, sends them as user messages, and pretty-prints assistant messages and tool uses. `AskUserQuestion` and `/done` are intercepted in the REPL, not the TUI. The TUI in Slice 5 reuses the same intercept hooks. Slice ordering preserved.

---

## 4. Smaller fixes folded into 0.2

- §6.3 line 283: clarify that `.answers.md` files are for *human* history, not Claude context (fresh sessions don't see them).
- §6.5 line 331: replace hard-coded "3 fix-up rounds" with `config.maxFixupRounds`.
- §6.3 line 285: stop asking Claude to commit. Forge commits deterministically with the canonical message after Claude's `result` event, having staged everything Claude wrote.
- §5 line 208: add `seq: Long` (monotonic per feature) to `Action` for replay determinism.
- §5 line 184: derive `Piece.branch` and `Feature.designBranch` from `branchPrefix` + ids instead of storing strings. One source of truth.
- §8 line 426: tighten privacy stance — `Bash` command logging gets the same redaction treatment as `claude.assistant_text` (first 200 chars, full content lives in Claude's JSONL).
- §11: surface `branchPrefix` use throughout §6 so the config knob actually controls branch names.

---

## 5. Things I deliberately left alone

These are real questions but don't need answering before v0.1 ships an implementation:

- GitLab adapter — explicit v2.
- Parallel features — explicit v2.
- Stacked PRs vs auto-merge — explicit v2 candidates, decided after lived experience.
- Langfuse — explicit v2.
- Webhook mode — explicit v2.
- LLM4S integration — deferred until a concrete need emerges.

---

## 6. Net assessment

The shape of the design is sound: FSM + connectors + action log + per-piece branches off main is the right backbone. Two areas need another pass before Slice 1 starts:

1. **The FSM enum and the §6 prose don't match.** Several states implied by the flow (design-awaiting-merge, needs-human-non-terminal) aren't in the enum. 0.2 rewrites §5 against §6 step-by-step.
2. **Several `gh`/Claude CLI claims are unverified or wrong.** 0.2 adds an explicit "validate before building" checklist at the head of Slice 1.

Everything else in 0.2 is incremental tightening.
