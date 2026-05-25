# Forge design v0.2 — review commentary

> Critical review of `forge-design-0.2.md`. Findings are grouped by severity. Each item cites the v0.2 line range and proposes a concrete decision for v0.3.

**Reviewer:** Codex  •  **Date:** 2026-05-24  •  **Target doc:** `forge-design-0.2.md`

---

## 1. Technical holes likely to break implementation

### 1.1 The committed canonical action log conflicts with PR-based workflow

**Where:** §6.2 lines 339-341, §7 lines 497-504, §7 lines 508-512

v0.2 says `.forge/log/<feature>.jsonl` is canonical and committed. But many important events happen after the relevant PR commit is already open: polling, Codex review, human feedback, merge detection, fix-up decisions, and budget transitions. Keeping the canonical log committed would require constant extra commits to open PRs or follow-up sync PRs, which reintroduces the churn v0.2 removed from DocSync.

**Decision for 0.3:** Split runtime truth from committed audit artifacts:

- `.forge/log/<feature>.jsonl` is the local canonical runtime log and is gitignored by default.
- `.forge/state/<feature>.json` remains a gitignored cache rebuilt from the local log.
- Forge commits sanitized milestone snapshots under `.forge/specs/<feature>/audit/`, such as `design-review.md`, `piece-p1-summary.md`, or `actions-snapshot.jsonl`.
- Rebuild is local-log based. If the local log is missing, Forge can recover partially from committed audit snapshots and GitHub PR state, but that is best-effort.

### 1.2 Human feedback is only partly modeled

**Where:** §6.2 lines 339-343, §6.4 lines 387-393, §6.6 lines 436-438

Design PRs only wait for merge. Piece PRs handle human comments while in `PieceAwaitingReview`, but after Codex approval the FSM moves to `PieceAwaitingMerge`, where new comments or requested changes are ignored until merge.

**Decision for 0.3:** Treat both design and piece PRs as live human feedback gates until merge:

- Add `DesignPrFeedback(prNumber, round)` or equivalent transition back into design revision when the design PR receives comments or `CHANGES_REQUESTED`.
- In `PieceAwaitingMerge`, new human comments or `CHANGES_REQUESTED` transition back to `PieceReviewFailed`.
- Store first-seen comment/review IDs at PR creation so Forge does not treat old comments as new.

### 1.3 Required-check logic can approve too early

**Where:** §6.4 lines 377-385, §9 lines 587-591

If a repo has no branch protection, the required-check set may be empty. Then "all required checks green" is true before any CI has appeared. This can trigger Codex review and human merge readiness too early.

**Decision for 0.3:** Add an explicit CI policy:

- `branch_protection`: use required checks from branch protection.
- `configured`: use a repo-configured list of required check names.
- `observed`: wait for checks to appear, then require every observed check to pass.
- `none`: skip CI intentionally.

Default policy: `branch_protection_then_observed`, with `checkDiscoveryTimeoutSec` and `minimumExpectedChecks` to avoid premature green.

### 1.4 Resume and manual-fix semantics are inconsistent

**Where:** §5 lines 195-198, §6.4 lines 383-385, §6.5 lines 403-418

`RetryFromState(PieceFixingUp(...sessionId...))` can point to a dead driver-agent session. The prose says a human can fix the PR and `forge resume` re-enters from `PieceAwaitingCi`, but it does not specify whether Forge commits/pushes human changes, expects the human to have pushed, or starts another automated fix-up.

**Decision for 0.3:** Replace generic retry hints with operation-specific resume hints:

- `ResumeAfterHumanCommit(p, prNumber)` means the human has already pushed; resume polling.
- `CommitAndPushHumanFix(p, prNumber)` means Forge should inspect, stage according to the staging policy, commit, push, then poll.
- `RunAnotherFixup(p, prNumber)` means start a fresh driver-agent fix-up session.

### 1.5 Strict preflight blocks the manual recovery path

**Where:** §6.0 lines 263-269, §6.5 line 418

Every state-changing command requires a clean worktree unless `--force` is used. That makes sense for `forge new` and automated runs, but it conflicts with `forge resume` after a human has edited files locally.

**Decision for 0.3:** Make preflight command- and state-aware:

- Automated commands require a clean worktree.
- `forge resume --commit-human-fix <feature>` explicitly permits local modifications on the active piece branch and runs the staging policy.
- `forge status`, `forge replay`, and `forge rebuild-state` do not require a clean worktree.

### 1.6 "Stage everything the agent wrote" is too broad

**Where:** §6.3 lines 364-372

The driver agent may create cache files, modify ignored/generated files, or write unwanted files through shell commands. Staging every changed path is unsafe and will produce noisy PRs.

**Decision for 0.3:** Add a `ChangeCollector` and staging policy:

- Capture paths touched by `Write`/`Edit`.
- Reconcile with `git status --porcelain`.
- Exclude ignored files, generated paths, secrets, build outputs, and paths outside the repo.
- Present ambiguous/unexpected paths to the user or transition to `NeedsHumanIntervention`.

### 1.7 Review-agent inline comment mechanics are underspecified

**Where:** §6.6 lines 424-438

The prompt says "The diff is below", but the design does not say how Forge fetches and injects the diff. Inline comments need valid diff anchors, not just file path and absolute line number. This must be a Forge responsibility, independent of whether the reviewer is Codex, Claude, or another adapter.

**Decision for 0.3:** Make Forge own the diff:

- Fetch PR diff through `gh pr diff` or GitHub API.
- Pass the diff plus piece spec to the configured review agent.
- Require blockers to include `path`, `side`, `line`, and a fallback `anchorText`.
- If an inline anchor cannot be posted, fall back to a summary bullet rather than failing the review.

### 1.8 Agent roles are hard-coded as Claude-primary / Codex-reviewer

**Where:** §1 lines 21-25, §4 lines 112-121, §6.1-§6.7

v0.2 describes the architecture as "Claude implements, Codex reviews". That is too narrow. The useful product is an orchestrator where one agent drives design/implementation and another agent reviews, with Claude and Codex as interchangeable adapters when their capabilities support the role.

**Decision for 0.3:** Introduce explicit agent roles:

- `specDriver`
- `implementationDriver`
- `designReviewer`
- `codeReviewer`
- `refineryReviewer`

Default remains Claude as driver and Codex as reviewer, but config can flip to Codex as driver and Claude as reviewer. Forge validates the selected agents against a capability matrix in Slice 0. The FSM talks to role interfaces (`DriverAgent`, `ReviewAgent`) rather than Claude/Codex directly.

---

## 2. Implementation gaps

### 2.1 Budget caps are configured but not wired into transitions

**Where:** §5 lines 200-207, §8 lines 541-556, §12 lines 736-749

The doc says BudgetTracker enforces caps, but not when checks happen, whether running processes are killed, or how partial overages are handled.

**Decision for 0.3:** Define budget checks at every `cost.update` and before spawning any agent. If a cap is breached during an active process, Forge lets the current turn settle, refuses additional prompts, logs the overage, and transitions to `NeedsHumanIntervention("budget exceeded", ...)`.

### 2.2 Lock-file PID semantics need detail

**Where:** §6.0 line 267, §10.3 line 656

`FileChannel.tryLock` prevents concurrent access, but it does not identify the holder PID by itself. `unlock --force` cannot release another live process's OS lock.

**Decision for 0.3:** Pair the OS lock with metadata:

- `.forge/state/.lock` remains the lock target.
- `.forge/state/.lock.json` stores PID, hostname, startedAt, command, and feature.
- `forge unlock --force` removes stale metadata only when no OS lock is held; if a live process still holds the lock, it refuses and prints the holder metadata.

### 2.3 Markdown decomposition needs machine-stable IDs

**Where:** §6.1 lines 273-275, §6.3 lines 347-351, §7 lines 497-513

Parsing `## Piece N: <title>` into `p.id` files is fragile. Reordering or renaming headings can break state replay and DocSync.

**Decision for 0.3:** Add a generated manifest:

- `.forge/specs/<feature>/manifest.json` is the machine source for piece IDs, ordering, status, file paths, PR numbers, and acceptance hashes.
- `decomposition.md` remains the human-readable view.
- Forge rewrites `decomposition.md` from the manifest when needed, not the other way around, except through an explicit import/reconcile command.

### 2.4 Refinery updates do not say how state and docs are reconciled

**Where:** §6.7 lines 461-487, §7 lines 508-513

If Refinery edits, adds, or removes pieces, the design does not specify what updates `Feature.pieces`, which branch carries the doc change, or how already-merged pieces are protected.

**Decision for 0.3:** Introduce a `PlanningUpdate` transition:

- Refinery emits a proposed manifest patch.
- The user chooses apply/defer/reopen.
- Applied updates are committed at the start of the next piece PR, or in a standalone planning-update PR if no next piece exists.
- Already-merged piece IDs are immutable; they can be annotated but not removed.

### 2.5 Main freshness and merge-base assumptions are not enforced

**Where:** §6.3 line 351, §6.7 lines 444-459, §10.3 lines 651-657

v0.2 says branches are cut from "current main" and CI is main-equivalent. That is only true if local main is fetched and fast-forwarded, and if PR branches are up to date with base.

**Decision for 0.3:** Make BranchManager explicit:

- `syncMain()` runs `git fetch` and fast-forwards the configured base branch before branch creation and after PR merge detection.
- If local base diverges, Forge refuses and asks for human intervention.
- Before treating CI as authoritative, Forge verifies the PR head includes the latest base SHA unless branch protection enforces strict up-to-date checks.

---

## 3. Clarity / consistency issues

### 3.1 `actions.jsonl` vs `.forge/log/<feature>.jsonl`

**Where:** §2 line 55, §6.2 line 340, §8 lines 519-556

The doc uses both `actions.jsonl` and `.forge/log/<feature>.jsonl`. This becomes more important once runtime logs and committed audit snapshots are split.

**Decision for 0.3:** Reserve `.forge/log/<feature>.jsonl` for the local canonical runtime log. Use `.forge/specs/<feature>/audit/*.jsonl` for committed snapshots.

### 3.2 Design and code review schemas are too loose around questions

**Where:** §6.2 lines 321-329, §5 line 228

The Scala `Question` includes `allowFreeText`, but the JSON schema omits it. The schema also does not distinguish blocking questions from optional suggestions.

**Decision for 0.3:** Add `severity: "blocking" | "clarifying" | "optional"` and `allowFreeText` to review questions. Only blocking questions stop progress.

### 3.3 Sensitive-repo logging needs a first-class mode

**Where:** §8 lines 541-556, §12 lines 753-755

v0.2 says the log is committed by default and can become gitignore opt-out. With v0.3's local-log decision, privacy is better, but committed audit snapshots still need a policy.

**Decision for 0.3:** Add `auditMode: "full" | "summary" | "local-only"` to config. Default to `summary`.

---

## 4. Net assessment

v0.2 substantially improves the v0.1 design. The remaining work is mostly about making the operational contract precise:

1. Runtime state/logging must line up with PR branch boundaries.
2. Human feedback must be handled until the exact moment of merge.
3. CI readiness must be explicit when branch protection is absent.
4. Resume/manual-repair paths need typed semantics.
5. Machine-readable planning metadata should back the Markdown docs.

The v0.3 proposal applies those decisions without changing the overall architecture: Scala FSM, Claude/Codex CLI connectors behind role interfaces, one piece PR at a time, human merge gates, and local deterministic orchestration.
