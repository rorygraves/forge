# Forge — design doc v0.3 proposal

> A proposal for tightening `forge-design-0.2.md` before implementation. This keeps the v0.2 architecture intact, but makes persistence, PR feedback, CI readiness, manual recovery, and planning metadata explicit enough to build.

**Author:** Rory  •  **Status:** draft v0.3 proposal  •  **Target:** personal tool, OSS later

**Basis:** `forge-design-0.2.md` plus the review in `forge-design-0.2-commentary.md`.

---

## 0. Summary of v0.3 changes

v0.3 keeps these v0.2 decisions:

- Scala harness with deterministic FSM.
- Claude and Codex connectors driven through their CLIs, assigned to configurable roles.
- Design PR before implementation.
- One piece branch and PR at a time, off the configured base branch.
- Human merge gates in v1.
- Cross-agent design/code/refinery review.
- State cache is rebuildable.
- No worktrees, no daemon, no webhooks in v1.

v0.3 changes or clarifies:

1. The canonical runtime log is local and gitignored; committed artifacts are sanitized milestone snapshots.
2. Human feedback is monitored at both design and piece gates until merge.
3. CI readiness uses an explicit policy so repos without branch protection do not go green too early.
4. Resume paths distinguish "human already pushed", "Forge should commit local human edits", and "run another driver-agent fix-up".
5. Forge stages changes through a `ChangeCollector`, not by blindly staging everything.
6. Piece metadata lives in a machine-readable manifest; Markdown remains the human view.
7. Refinery changes become manifest patches with an explicit planning-update flow.
8. Branch freshness and base SHA checks are BranchManager responsibilities.

---

## 1. Agent role model

Forge should not be Claude-primary by design. It should orchestrate roles, then map those roles to available agent adapters.

Roles:

| Role | Responsibility |
|---|---|
| `specDriver` | Runs the interactive design/specification session |
| `implementationDriver` | Implements each piece and performs automated fix-ups |
| `designReviewer` | Reviews the design before the design PR is opened |
| `codeReviewer` | Reviews piece PR diffs before human merge |
| `refineryReviewer` | Checks whether the plan still matches reality after each merge |

Default role assignment:

```json
{
  "roles": {
    "specDriver": "claude",
    "implementationDriver": "claude",
    "designReviewer": "codex",
    "codeReviewer": "codex",
    "refineryReviewer": "codex"
  }
}
```

Codex-driven mode:

```json
{
  "roles": {
    "specDriver": "codex",
    "implementationDriver": "codex",
    "designReviewer": "claude",
    "codeReviewer": "claude",
    "refineryReviewer": "claude"
  }
}
```

The FSM only talks to role interfaces:

```scala
trait DriverAgent:
  def runInteractiveSpec(prompt: SpecPrompt): StreamingSession
  def runImplementation(prompt: ImplementationPrompt): Stream[IO, AgentEvent]
  def runFixup(prompt: FixupPrompt): Stream[IO, AgentEvent]

trait ReviewAgent:
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]
  def reviewPr(input: PrReviewInput): IO[PrReview]
  def refine(input: RefineInput): IO[RefineResult]
```

Claude and Codex are adapters behind those interfaces. If an adapter lacks a native feature, it may emulate it with prompt + local JSON validation + retry, but it must satisfy the same contract.

Slice 0 validates the capability matrix:

| Capability | Claude adapter | Codex adapter |
|---|---|---|
| interactive streaming spec | Validate | Validate |
| headless code edits | Validate | Validate |
| fix-up from failure bundle | Validate | Validate |
| schema-constrained review output | Validate or emulate | Validate or emulate |
| inline-review-ready findings | Validate or emulate | Validate or emulate |
| cost reporting | Validate | Validate |

Default review should use a different adapter from the driver. Forge may allow same-agent review only with `allowSameAgentReview: true`, logged as a reduced-independence review.

---

## 2. Revised source-of-truth model

v0.2 tried to make the action log both canonical and committed. That fights the PR workflow. v0.3 splits runtime truth from committed review artifacts.

| Path | Role | Committed? |
|---|---|---|
| `.forge/specs/<feature>/design.md` | Human-readable design intent | Yes |
| `.forge/specs/<feature>/decomposition.md` | Human-readable piece plan rendered from manifest | Yes |
| `.forge/specs/<feature>/manifest.json` | Machine source for pieces, ordering, status, file paths, PRs, hashes | Yes |
| `.forge/specs/<feature>/pieces/<p>.md` | Human-readable detail spec for a piece | Yes |
| `.forge/specs/<feature>/audit/*.md` | Sanitized milestone summaries | Yes |
| `.forge/specs/<feature>/audit/*.jsonl` | Optional sanitized action snapshots | Config-dependent |
| `.forge/log/<feature>.jsonl` | Local canonical runtime action log | No, gitignored |
| `.forge/state/<feature>.json` | Local rebuildable state cache | No, gitignored |
| `.forge/state/.lock` | OS lock target | No, gitignored |
| `.forge/state/.lock.json` | Lock metadata: PID, host, command, feature, startedAt | No, gitignored |

**Invariant:** the local runtime log is canonical for the current machine. The state cache is rebuilt from that log. Committed audit files are for code review, historical understanding, and partial recovery, but they are not the primary FSM replay source.

Config:

```json
{
  "auditMode": "summary",
  "logRetention": "keep-local"
}
```

`auditMode` values:

- `full`: commit sanitized JSONL snapshots at milestones.
- `summary`: commit Markdown summaries only. Default.
- `local-only`: commit no audit artifacts beyond design/decomposition/piece specs.

---

## 3. Manifest-backed work breakdown

Markdown is pleasant for humans but brittle as a database. v0.3 adds a manifest.

`.forge/specs/<feature>/manifest.json`:

```json
{
  "schemaVersion": 1,
  "featureId": "stripe-webhook",
  "title": "Add Stripe webhook receiver",
  "baseBranch": "main",
  "branchPrefix": "forge",
  "designPr": 4290,
  "pieces": [
    {
      "id": "p1",
      "order": 1,
      "title": "Add webhook route and signature verification",
      "specPath": ".forge/specs/stripe-webhook/pieces/p1.md",
      "acceptanceHash": "sha256:...",
      "status": "pending",
      "prNumber": null,
      "mergeCommit": null,
      "mergedAt": null
    }
  ]
}
```

Rules:

- Piece IDs are stable once created.
- Renaming or reordering a piece updates the manifest but does not change the ID.
- Already-merged pieces are immutable except for annotations such as PR number, merge SHA, and completion status.
- `decomposition.md` is rendered from the manifest and piece files.
- Manual edits to `decomposition.md` are treated as a proposed import/reconcile, not silently accepted as state.

This removes the need to infer machine state from headings like `## Piece N: <title>`.

---

## 4. Revised domain model

The v0.2 model mostly holds. v0.3 adds typed resume hints, manifest-backed pieces, CI policy, and PR feedback transitions.

```scala
opaque type FeatureId = String
opaque type PieceId   = String
opaque type PrNumber  = Int

enum FsmState:
  // Spec phase
  case Drafting
  case InteractiveSpec(agentSessionId: String)
  case DesignReviewing(agentSessionId: String, round: Int)
  case DesignNeedsHumanInput(round: Int, questions: Vector[Question])
  case DesignAwaitingMerge(prNumber: PrNumber)
  case DesignPrFeedback(prNumber: PrNumber, round: Int)
  case DesignReady

  // Implementation phase
  case PieceImplementing(p: PieceId, agentSessionId: String)
  case PieceAwaitingCi(p: PieceId, prNumber: PrNumber)
  case PieceAwaitingReview(p: PieceId, prNumber: PrNumber)
  case PieceCiFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceReviewFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceFixingUp(p: PieceId, prNumber: PrNumber, attempt: Int, agentSessionId: String)
  case PieceAwaitingMerge(p: PieceId, prNumber: PrNumber)
  case Refining(p: PieceId)
  case PlanningUpdate(reason: String, patchPath: String)
  case PieceMerged(p: PieceId, prNumber: PrNumber)

  // Recovery / terminal
  case NeedsHumanIntervention(reason: String, resumeHint: ResumeHint)
  case FeatureDone
  case Abandoned(reason: String)

enum ResumeHint:
  case ResumeAfterHumanPush(p: PieceId, prNumber: PrNumber)
  case CommitAndPushHumanFix(p: PieceId, prNumber: PrNumber)
  case RunAnotherFixup(p: PieceId, prNumber: PrNumber)
  case ReopenDesign(prNumber: Option[PrNumber])
  case ApplyPlanningUpdate(patchPath: String)
  case AbortOrAbandon

enum CiPolicy:
  case BranchProtectionThenObserved
  case BranchProtectionOnly
  case ConfiguredRequiredChecks(names: Set[String])
  case ObservedChecks
  case None

case class Question(
  text: String,
  options: Vector[String],
  allowFreeText: Boolean,
  severity: QuestionSeverity
)

enum QuestionSeverity:
  case Blocking
  case Clarifying
  case Optional
```

`Piece` no longer needs to duplicate all machine metadata if that metadata lives in `manifest.json`. The runtime `Feature` can load its piece vector from the manifest on every command.

---

## 5. Command-specific preflight

v0.2 made a clean worktree a hard precondition for every state-changing command. v0.3 keeps that for automation but adds explicit manual-repair paths.

| Command | Clean worktree required? | Notes |
|---|---:|---|
| `forge new` | Yes | Refuses dirty, rebasing, detached, or divergent base |
| `forge spec` | Yes | The spec branch is Forge-owned |
| `forge run` | Yes | Automation must start from a known state |
| `forge resume` | Depends | Uses the resume hint |
| `forge resume --after-human-push` | Yes | Human already committed and pushed |
| `forge resume --commit-human-fix` | No | Allowed only on the active piece branch; invokes ChangeCollector |
| `forge status` | No | Read-only |
| `forge replay` | No | Read-only |
| `forge rebuild-state` | No | Rebuilds local cache from local log |
| `forge unlock --force` | No | Lock-specific rules apply |

`--force` remains available, but use is logged as `harness.preflight_bypassed`.

---

## 6. BranchManager responsibilities

BranchManager owns all assumptions about Git freshness:

```scala
trait BranchManager:
  def preflight(command: ForgeCommand): IO[PreflightReport]
  def syncBase(base: BranchName): IO[BaseSnapshot]
  def createDesignBranch(feature: FeatureId): IO[BranchName]
  def createPieceBranch(feature: FeatureId, piece: PieceId): IO[BranchName]
  def ensurePrContainsBase(pr: PrNumber, baseSha: Sha): IO[BaseFreshness]
  def pushCurrentBranch(): IO[Unit]
  def createPr(title: String, body: String, base: BranchName): IO[PrNumber]
```

Rules:

- Before branch creation, run `git fetch` and fast-forward the configured base branch.
- If local base diverges, transition to `NeedsHumanIntervention`.
- Store the base SHA used to cut each branch in the manifest or local log.
- Before treating CI as authoritative, verify either:
  - branch protection requires the branch to be up to date, or
  - the PR head contains the latest base SHA known to Forge.

This makes "CI passed on a main-equivalent branch" a checked condition rather than an assumption.

---

## 7. CI readiness policy

v0.3 replaces implicit required-check handling with an explicit policy.

Config:

```json
{
  "ci": {
    "policy": "branch_protection_then_observed",
    "requiredChecks": [],
    "minimumExpectedChecks": 1,
    "checkDiscoveryTimeoutSec": 180,
    "stableGreenPolls": 2
  }
}
```

Policy behavior:

- `branch_protection_then_observed`: use branch protection if configured; otherwise wait for observed checks.
- `branch_protection_only`: only branch-protection required checks matter. If none exist, CI is intentionally skipped.
- `configured`: require the exact configured check names.
- `observed`: after discovery timeout or first complete check suite, require all observed checks to pass.
- `none`: skip CI intentionally and log `ci.skipped`.

Readiness requires:

1. Check discovery has completed.
2. The relevant check set is non-empty unless policy is `none` or `branch_protection_only` with no required checks.
3. All relevant checks are green for `stableGreenPolls` consecutive polls.
4. The PR branch satisfies the base freshness rule.

This avoids accidental approval before CI starts.

---

## 8. Human feedback gates

v1 remains human-merged, but Forge keeps listening until the merge actually happens.

### Design PR

From `DesignAwaitingMerge(prNumber)`:

- `state == MERGED` and `mergedAt != null` → `DesignReady`.
- New human comment or `CHANGES_REQUESTED` → `DesignPrFeedback(prNumber, round + 1)`.
- PR closed without merge → `NeedsHumanIntervention("design PR closed without merge", ReopenDesign(Some(prNumber)))`.

From `DesignPrFeedback`:

1. Write comments and requested changes into `.forge/specs/<feature>/audit/design-pr-feedback-round-<n>.md`.
2. Resume or restart the configured `specDriver` design revision according to validated adapter behavior.
3. Update `design.md`, `decomposition.md`, `manifest.json`, and affected `pieces/*.md`.
4. Commit and push a new design PR revision.
5. Return to `DesignAwaitingMerge`.

### Piece PR

From `PieceAwaitingReview`:

- Review agent approves and no unresolved human requested changes → `PieceAwaitingMerge`.
- Review agent requests changes → `PieceReviewFailed`.
- Human comments or `CHANGES_REQUESTED` → `PieceReviewFailed`.

From `PieceAwaitingMerge`:

- Merge detected → `Refining(p)`.
- New comments or `CHANGES_REQUESTED` before merge → `PieceReviewFailed`.
- PR closed without merge → `NeedsHumanIntervention("piece PR closed without merge", RunAnotherFixup(p, prNumber))`.

At PR creation, Forge records the current highest comment/review IDs. Only later IDs count as new feedback.

---

## 9. Change collection and staging

Forge commits, but it should not stage blindly.

`ChangeCollector` responsibilities:

```scala
trait ChangeCollector:
  def collectTouchedPaths(events: Vector[AgentEvent]): Set[RepoPath]
  def gitStatus(): IO[Vector[GitStatusEntry]]
  def classify(entry: GitStatusEntry): ChangeClass
  def stage(plan: StagePlan): IO[Unit]
```

Change classes:

- `AllowedTrackedEdit`
- `AllowedNewFile`
- `GeneratedOrIgnored`
- `SecretOrSensitive`
- `OutsideRepo`
- `Unexpected`

Default behavior:

- Stage allowed tracked edits and allowed new files.
- Exclude generated/ignored files.
- Refuse and transition to `NeedsHumanIntervention` on sensitive, outside-repo, or unexpected changes unless the user explicitly approves.
- Record the stage plan in the local action log and the PR summary.

Config:

```json
{
  "staging": {
    "allowNewFilesUnder": ["app/", "src/", "test/", "docs/", ".forge/specs/"],
    "denyPatterns": [".env", "*.pem", "target/", "node_modules/", ".bloop/", ".metals/"]
  }
}
```

---

## 10. Review-agent PR posting

Forge owns the diff and posting mechanics.

Review flow:

1. Fetch PR diff with `gh pr diff <n>` or GitHub API.
2. Fetch changed file metadata so line anchors can be validated.
3. Prompt the configured review agent with the piece spec, acceptance criteria, design link, and diff.
4. Validate review JSON locally.
5. Post inline comments where anchors are valid.
6. Fall back to a summary bullet for any invalid anchor.
7. Submit one PR review: approve or request changes.

Schema additions:

```json
{
  "path": "src/main/scala/example/Foo.scala",
  "side": "RIGHT",
  "line": 42,
  "startLine": null,
  "anchorText": "def handleWebhook",
  "issue": "Signature verification accepts an empty header.",
  "severity": "blocker"
}
```

`path`, `side`, and `line` are required for inline comments. If the review agent cannot provide them, it sets `path: null` and the issue becomes a summary comment.

---

## 11. Fix-up and resume semantics

CI failures and review failures both write a failure bundle:

`.forge/specs/<feature>/pieces/<p>.failures.md`

Contents:

- Required CI failures and log excerpts.
- Review-agent blockers.
- Human review comments since last seen.
- Previous failure reasons for the piece.
- Current PR URL and branch.

Fix-up loop:

1. Increment attempt count in the local log and manifest.
2. If attempts exceed `maxFixupRounds`, transition to `NeedsHumanIntervention`.
3. Otherwise start a fresh `implementationDriver` fix-up session.
4. Run ChangeCollector.
5. Commit and push.
6. Return to `PieceAwaitingCi`.

Manual resume options:

- `forge resume --after-human-push <feature>`: assume the PR branch already contains the human fix; resume polling from `PieceAwaitingCi`.
- `forge resume --commit-human-fix <feature>`: collect local changes, stage, commit, push, then resume polling.
- `forge resume --run-fixup <feature>`: start another `implementationDriver` fix-up session.

This removes ambiguity around dead agent session IDs and human repair.

---

## 12. Budget enforcement

Budget is checked:

- before spawning any driver or review agent;
- after every `cost.update`;
- before sending any additional user/tool result message into a running agent;
- before starting a fix-up round.

If a cap is exceeded before spawn, Forge refuses to start the agent and transitions to `NeedsHumanIntervention`.

If a cap is exceeded during an active agent turn:

1. Let the current turn settle if it is already in progress.
2. Do not send further prompts.
3. Do not start new fix-up/review/refinery agents.
4. Log `budget.exceeded`.
5. Transition to `NeedsHumanIntervention("budget exceeded", resumeHint)`.

This avoids killing a process mid-write while still preventing runaway loops.

---

## 13. Locking

Forge uses both an OS lock and metadata.

Files:

- `.forge/state/.lock`
- `.forge/state/.lock.json`

Metadata:

```json
{
  "pid": 12345,
  "hostname": "rory-laptop",
  "startedAt": "2026-05-24T15:42:18Z",
  "command": "forge run stripe-webhook",
  "feature": "stripe-webhook"
}
```

Behavior:

- On startup, try to acquire the OS lock.
- If acquisition fails, read `.lock.json` and print the holder metadata.
- `forge unlock --force` succeeds only if no live OS lock is held.
- If metadata exists but no OS lock is held, Forge treats it as stale and can remove it after confirmation.

This makes "held by PID X" implementable.

---

## 14. Refinery and planning updates

The Refinery no longer directly edits planning files. It proposes manifest patches.

Refine result:

```json
{
  "verdict": "update_plan",
  "rationale": "Piece p2 made p3 unnecessary because the shared helper already covers it.",
  "manifestPatchPath": ".forge/specs/stripe-webhook/audit/refine-after-p2.patch.json"
}
```

FSM:

- `no_change` → advance to next piece.
- `update_plan` → `PlanningUpdate(reason, patchPath)`.
- `reopen_design` → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(...))`.

From `PlanningUpdate`, the user chooses:

- apply now;
- defer to after next piece;
- reopen design;
- ignore with audit note.

Applied updates:

- mutate `manifest.json`;
- regenerate `decomposition.md`;
- create/edit affected `pieces/*.md`;
- commit on the next piece PR, or open a standalone planning-update PR if there is no next piece.

Already-merged piece IDs remain immutable.

---

## 15. Revised configuration

`.forge/config.json`:

```json
{
  "baseBranch": "main",
  "branchPrefix": "forge",
  "pollIntervalMs": 30000,
  "maxFixupRounds": 3,
  "maxDesignReviewRounds": 3,
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd": 8.00,
  "auditMode": "summary",
  "logRetention": "keep-local",
  "roles": {
    "specDriver": "claude",
    "implementationDriver": "claude",
    "designReviewer": "codex",
    "codeReviewer": "codex",
    "refineryReviewer": "codex",
    "allowSameAgentReview": false
  },
  "ci": {
    "policy": "branch_protection_then_observed",
    "requiredChecks": [],
    "minimumExpectedChecks": 1,
    "checkDiscoveryTimeoutSec": 180,
    "stableGreenPolls": 2
  },
  "staging": {
    "allowNewFilesUnder": ["app/", "src/", "test/", "docs/", ".forge/specs/"],
    "denyPatterns": [".env", "*.pem", "target/", "node_modules/", ".bloop/", ".metals/"]
  },
  "agents": {
    "claude": {
      "model": "default",
      "permissionMode": "acceptEdits",
      "allowedTools": ["Read", "Write", "Edit", "Bash", "Glob", "Grep", "WebFetch", "AskUserQuestion"],
      "isolationFlag": "auto"
    },
    "codex": {
      "model": "default",
      "sandbox": "workspace-write",
      "reviewSandbox": "read-only"
    }
  },
  "settle": {
    "specTimeoutSec": 300,
    "implementTimeoutSec": 1800,
    "fixupTimeoutSec": 900
  }
}
```

Global config remains at `~/.config/forge/config.json`. Templates and prompts remain under `~/.forge/`, with repo overrides under `.forge/overrides/`.

---

## 16. Revised Slice 0 checklist

Keep the v0.2 CLI validation checklist and add:

1. Verify whether `gh pr create --json url -q .url` is available in the pinned `gh` version; otherwise use `gh pr view --json number`.
2. Verify how to fetch review threads, comment IDs, review IDs, and inline diff anchors.
3. Verify branch-protection API behavior for protected, unprotected, and inaccessible repos.
4. Verify `gh pr diff` output stability and whether GitHub API diff metadata is needed for reliable inline comments.
5. Verify schema-constrained output behavior for both Claude and Codex, including nullable fields and enum validation.
6. Verify both adapters expose enough edit/tool path data for ChangeCollector; if not, reconcile from `git status`.
7. Verify cost fields from both adapters are present and stable enough for budget enforcement.
8. Verify Codex-driven implementation mode can edit files under Forge control.
9. Verify Claude-reviewer mode can produce schema-valid design/code/refinery reviews through native schema support or prompt + validator retry.

Output remains `slice-0-report.md` with exact CLI versions and observed behavior.

---

## 17. Revised Slice 2 scope

Slice 2 should include the parts that make later automation safe:

- FSM pure transition tests.
- ActionLog append/replay tests.
- StateCache rebuild tests.
- Manifest read/write/render tests.
- CI policy decision tests.
- Resume-hint legality tests.
- Budget transition tests.

Property tests should assert:

- no success path reaches implementation before design is merged;
- no piece can be marked merged without PR merge evidence;
- human feedback before merge always returns to design revision or fix-up;
- CI cannot become green before check discovery finishes unless CI policy explicitly permits it;
- already-merged piece IDs cannot be removed by planning updates.

---

## 18. Revised lifecycle summary

1. `forge new` syncs base, creates the design branch, starts spec drafting.
2. The configured `specDriver` writes design, decomposition, manifest, and piece files.
3. The configured `designReviewer` reviews the design.
4. Forge opens a design PR.
5. Forge watches the design PR for merge and human feedback.
6. Once merged, Forge syncs base and starts the first piece branch.
7. The configured `implementationDriver` implements the piece.
8. ChangeCollector stages only approved changes.
9. Forge commits, pushes, and opens a piece PR.
10. CI readiness waits according to policy.
11. The configured `codeReviewer` reviews the PR diff and posts a review.
12. Forge watches for human feedback until merge.
13. On merge, Forge syncs base and runs Refinery.
14. Refinery either advances, proposes a planning update, or reopens design.
15. Repeat until all manifest pieces are merged.
16. Forge opens or includes a final completion update, depending on whether a final piece PR exists.
17. Feature reaches `FeatureDone`.

---

## 19. Decision summary

| Question | v0.3 decision |
|---|---|
| Is Forge Claude-primary? | No. Forge assigns configurable agent roles; Claude-primary/Codex-reviewer is only the default. |
| Is the action log committed? | Local canonical log is not committed by default; committed audit is summary/snapshot based. |
| What is machine source for pieces? | `manifest.json`; Markdown is the human view. |
| How are human comments handled? | Watched at design and piece gates until merge; comments can send work back to revision/fix-up. |
| How does CI avoid premature green? | Explicit CI policy with discovery timeout, required check source, and stable green polls. |
| Who stages files? | Forge, through ChangeCollector and a staging policy. |
| How does manual fix resume work? | Separate commands for already-pushed fixes, local human edits to commit, and another driver-agent fix-up. |
| How are branch freshness assumptions enforced? | BranchManager syncs base and checks PR base freshness before trusting CI. |
| How does Refinery edit the plan? | It proposes manifest patches; user chooses apply/defer/reopen/ignore. |
| How are budget caps enforced? | Checked before spawns and after cost updates; overage blocks further agent turns. |
| How does lock holder PID work? | OS lock plus `.lock.json` metadata. |

---

## 20. Open questions left for implementation

These remain intentionally open but are now bounded:

1. Exact Claude isolation/resume flags and event schema, pending Slice 0.
2. Exact Codex edit/exec/schema behavior, pending Slice 0.
3. Whether reliable inline comments require GraphQL review threads instead of REST-only calls.
4. How much of the local runtime log should be recoverable from committed audit snapshots.
5. Whether `auditMode: "summary"` is enough for OSS users or whether `local-only` should be the default for new repos.
