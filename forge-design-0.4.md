# Forge — design doc v0.4

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v0.4  •  **Target:** personal tool, OSS later

**Basis:** `forge-design-0.3.md` plus the review in `forge-design-0.3-commentary.md`. v0.4 keeps the bulk of 0.3's operational improvements and applies four classes of change: (a) fix the new bugs 0.3 introduced; (b) tighten 0.3's remaining hand-waves into concrete rules; (c) trim v1-unnecessary scope (notably the agent-role abstraction); (d) close three gaps that all prior revisions missed.

---

## 0. Summary of changes from 0.3

Kept from 0.3:
- Local canonical runtime log + sanitized committed audit (§2).
- `manifest.json` as machine source for pieces (§3).
- `DesignPrFeedback`, `PieceAwaitingMerge → PieceReviewFailed` on late human feedback (§7).
- Typed `ResumeHint` variants (§4).
- `BranchManager.syncBase()` + base freshness checks (§6).
- Explicit CI readiness policy (§5).
- `ChangeCollector` for staging (§8).
- Forge owns the PR diff for inline review (§9).
- OS lock + `.lock.json` metadata (§12).
- `Refining` + `PlanningUpdate` state for design drift (§13).
- Per-feature budget enforcement (§11).

Changed from 0.3:
- **No agent role abstraction in v1.** Claude is the driver, Codex is the reviewer, hard-coded. Connector modules stay separate. Pluggable roles documented as v2 candidate. (§14)
- **`PlanningUpdate` carries the patch inline**, not a file path. Patches are a typed ADT. (§4, §13)
- **`Refining` failure → `PieceMerged`** (refinery is advisory; merged is merged). (§13)
- **ChangeCollector default is allow-anywhere-not-denied.** Stricter posture is opt-in. (§8)
- **CI policy has two variants**, not five. (§5)
- **Three change classes** (`Allow`/`Deny`/`Ask`), not six. (§8)
- **Manifest reconcile is an explicit `forge reconcile` command** invoked when `decomposition.md` diverges from the manifest. (§3)
- **Force-push to design branch** for revisions, with a `pre-revision-snapshot` tag. (§7)
- **`anchorText` matching algorithm specified.** (§9)
- **`--commit-human-fix` checks current branch against manifest-derived piece branch.** (§10)
- **Stale-lock UX specified** with `--yes` / env var for automation. (§12)
- **Per-turn cost ceiling** (`maxTurnCostUsd`, default \$2) — runaway turns get killed, not allowed to "settle". (§11)
- **AskUserQuestion always goes to Forge Q&A pane**, never back to GitHub. (§7, §10)
- **GitHub line-based comment API pinned**; classic position-API not used. (§9, §16)
- **GitHub rate-limit handling** with cached branch protection and diff. (§5, §16)
- **`Refining` state surfaced in TUI/CLI** so it's not invisible. (§13)
- **`request_changes` verdict validated** to require at least one blocker before posting. (§9)
- **Slice 0 trimmed** back to original scope minus the capability matrix. (§15)

---

## 1. Goals and non-goals

### Goals

1. **One feature at a time, one fresh context per piece.**
2. **Interactive spec phase** with Claude, terminated by `/done`.
3. **Headless implementation phase** — Claude runs piece-by-piece, escalating only on `AskUserQuestion`, red required CI, or budget breach.
4. **Incremental merge** — each piece is its own branch off `main`, its own PR, its own CI run, merged before the next.
5. **Cross-model review** — Codex reviews every design and every PR by default.
6. **Action log per feature** for tuning and audit.
7. **De-risked build order** — connectors first.
8. **No dead-end states.** Any failure that doesn't reach the goal lands in `NeedsHumanIntervention(reason, resumeHint)`, which is resumable.
9. **No runaway costs.** Both per-feature and per-turn ceilings; agents are aborted, not allowed to settle, when the turn ceiling is breached.

### Non-goals (v1)

- Parallel features.
- Multi-repo / monorepo split work.
- Long-running daemon.
- Langfuse integration.
- Worktrees.
- Auto-merge.
- Stacked PRs.
- **Agent role pluggability** (added to non-goals in 0.4; see §14).
- GitLab support.

---

## 2. Pain points (carry over from 0.3, unchanged)

| Pain | Fix in Forge |
|---|---|
| Manual task decomposition | Design phase produces `manifest.json` + `decomposition.md` + per-piece `pieces/<p>.md` |
| Context bloat across features | New `claude` subprocess per piece; no `--resume` across pieces |
| 30-min CI feedback latency | Poll `gh` every 15–60s only when FSM cares |
| Inconsistent branches | BranchManager owns all git; deterministic naming, derived from config |
| Poor /loop ergonomics | Forge IS the loop |
| No cross-model review | Codex reviews every design and every PR |
| Hallucinated specs | `AskUserQuestion` always goes to the Forge Q&A pane |
| Process opacity | Per-feature action log (local canonical) + committed audit snapshots |
| Failure leaves feature in limbo | `NeedsHumanIntervention` is non-terminal; three typed resume paths |
| Plan drifts from reality | Refinery proposes manifest patches; `PlanningUpdate` is an explicit FSM transition |
| Runaway agent cost | Per-feature, per-piece, and per-turn cost ceilings |
| `gh` rate limits | Cache branch protection and diffs; back off on 403/429 |

---

## 3. Manifest-backed work breakdown

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
      "mergedAt": null,
      "attempts": 0
    }
  ]
}
```

Rules:
- Piece IDs are stable once created.
- Already-merged pieces are immutable except for annotations.
- `decomposition.md` is rendered from the manifest + piece files. Forge writes both at consolidation time (§7).

### 3.1 Reconcile UX (closes 0.3's hand-wave)

On every `forge` command (except `forge reconcile`, `forge status`, `forge replay`, `forge rebuild-state`, `forge unlock --force`):

1. Forge computes the canonical decomposition.md by rendering the manifest.
2. Hashes both the canonical and the on-disk file.
3. On mismatch: **refuse the command**. Print the unified diff (first 40 lines) and instruct: `Run 'forge reconcile <feature>' to apply your edits to the manifest.`

`forge reconcile <feature>`:
1. Shows the full diff in the TUI/CLI.
2. Prompts: `Import these edits as a manifest update? (y/N)`
3. On `y`: applies the diff as a `PlanningUpdate` (§13) — the same flow Refinery uses.
4. Hard rule: pieces already merged cannot be removed by reconcile. The user is told which edits are illegal and the reconcile aborts.

Rationale: the manifest is the source of truth; manual edits to the rendered file are intent the user must explicitly promote.

---

## 4. Domain model

```scala
opaque type FeatureId = String
opaque type PieceId   = String
opaque type PrNumber  = Int

enum FsmState:
  // Spec phase
  case Drafting
  case InteractiveSpec(sessionId: String)
  case DesignReviewing(sessionId: String, round: Int)
  case DesignNeedsHumanInput(round: Int, questions: Vector[Question])
  case DesignAwaitingMerge(prNumber: PrNumber)
  case DesignPrFeedback(prNumber: PrNumber, round: Int)
  case DesignReady

  // Implementation phase
  case PieceImplementing(p: PieceId, sessionId: String)
  case PieceAwaitingCi(p: PieceId, prNumber: PrNumber)
  case PieceAwaitingReview(p: PieceId, prNumber: PrNumber)
  case PieceCiFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceReviewFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceFixingUp(p: PieceId, prNumber: PrNumber, attempt: Int, sessionId: String)
  case PieceAwaitingMerge(p: PieceId, prNumber: PrNumber)
  case Refining(p: PieceId, startedAt: Instant)
  case PlanningUpdate(reason: String, patch: ManifestPatch)         // patch is inline, not a path
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
  case ApplyPlanningUpdate(patch: ManifestPatch)                    // patch inline
  case AbortOrAbandon

enum ManifestPatchOp:
  case AddPiece(after: Option[PieceId], piece: Piece)
  case RemovePiece(id: PieceId)                                     // rejected by validator if merged
  case EditPiece(id: PieceId, title: Option[String], specPath: Option[String], acceptanceHash: Option[String])
  case ReorderPieces(newOrder: Vector[PieceId])

case class ManifestPatch(reason: String, ops: Vector[ManifestPatchOp]):
  // validate() enforces: no edits to merged pieces, no removal of merged pieces,
  // reorder preserves all merged piece IDs in their original positions.
  def validate(manifest: Manifest): Either[Vector[ValidationError], ManifestPatch]

enum CiPolicy:
  case BranchProtectionThenObserved                                 // default
  case None                                                          // intentional skip

case class Question(
  text: String,
  options: Vector[String],
  allowFreeText: Boolean,
  severity: QuestionSeverity
)

enum QuestionSeverity:
  case Blocking | Clarifying | Optional
```

Notes:
- `ManifestPatch` lives in the FSM state, not on disk. Audit copy is *also* written to `.forge/specs/<feature>/audit/` for human review, but the FSM is robust to its loss.
- `Refining` carries `startedAt` so the TUI can render elapsed time (§13.1).
- `attempts` lives on the piece (in the manifest), not the FSM state — it persists across transitions.

---

## 5. CI readiness policy

Two variants. Anything more granular is reachable via configuration overlays.

| Policy | Behaviour |
|---|---|
| `BranchProtectionThenObserved` (default) | Use branch-protection's required-check list if it exists. Otherwise: wait `checkDiscoveryTimeoutSec` for at least one check to start, then require all observed checks to be green for `stableGreenPolls` consecutive polls. |
| `None` | Skip CI gating; log `ci.skipped`. Use for repos without CI by design. |

Config:

```json
{
  "ci": {
    "policy": "branch_protection_then_observed",
    "requiredChecksOverlay": [],
    "minimumExpectedChecks": 1,
    "checkDiscoveryTimeoutSec": 180,
    "stableGreenPolls": 2
  }
}
```

`requiredChecksOverlay` lets a user pin specific check names without changing policy (e.g., "I trust CI, but only after `integration-tests` passes"). The overlay is *added to* the required set — never reduces it.

Discovery + freshness rules:
1. After `gh pr view` first returns, if no checks have appeared and `checkDiscoveryTimeoutSec` has elapsed, transition based on policy: `BranchProtectionThenObserved` with empty protection set + zero observed checks → `NeedsHumanIntervention("no CI checks discovered", ResumeAfterHumanPush(...))`. `None` → straight to readiness.
2. If checks have appeared but fewer than `minimumExpectedChecks`, keep polling until met or timeout.
3. Required set is the union of branch-protection and `requiredChecksOverlay`.
4. Readiness requires `stableGreenPolls` consecutive green polls (default 2 = ~60s margin).

Branch-protection lookup is cached per feature for the lifetime of the Forge process.

---

## 6. BranchManager and base freshness

```scala
trait BranchManager:
  def preflight(command: ForgeCommand): IO[PreflightReport]
  def syncBase(base: BranchName): IO[BaseSnapshot]
  def createDesignBranch(feature: FeatureId): IO[BranchName]
  def createPieceBranch(feature: FeatureId, piece: PieceId): IO[BranchName]
  def baseFreshness(pr: PrNumber, expectedBaseSha: Sha): IO[BaseFreshness]
  def pushCurrentBranch(forceWithLease: Boolean = false): IO[Unit]
  def createPr(title: String, body: String, base: BranchName): IO[PrNumber]
  def updatePrBranch(pr: PrNumber): IO[Unit]                        // gh pr update-branch
  def tagSnapshot(name: String): IO[Unit]                            // for force-push safety
```

Rules:
- Before any branch creation: `git fetch` and fast-forward the configured base. If local diverges → `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)`.
- Store the base SHA used to cut each branch in `manifest.json`.
- On readiness check (§5), call `baseFreshness(pr, manifest.pieces[i].baseSha)`:
  - If branch protection requires up-to-date: trust protection; readiness is what the rollup says.
  - If protection doesn't require up-to-date and `config.baseFreshness.autoUpdate: true` (default): call `updatePrBranch(pr)`, re-enter `PieceAwaitingCi`.
  - If `autoUpdate: false`: transition to `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(p, prNumber))`.

This closes 0.3's "what does the FSM do on stale?" gap.

---

## 7. Lifecycle

### 7.0 Preconditions

Every state-changing command:
1. Acquire `.forge/state/.lock` (§12).
2. `BranchManager.preflight(command)` — command-aware (table in §10).
3. Manifest/decomposition.md reconcile check (§3.1).
4. State cache verified against log replay; rewritten if divergent.

### 7.1 Spec phase

1. `forge new "title"`. BranchManager creates `<branchPrefix>/<feature>/design` off the synced base.
2. Forge spawns streaming Claude with `--append-system-prompt @~/.forge/prompts/specify.md`. The TUI pane drives stdin/stdout.
3. Action log records `claude.user_message` and `claude.assistant_text` (≤200 chars each). Full transcript lives in Claude's JSONL.
4. User types `/done` (intercepted by Forge). Forge sends a final message asking Claude to write `design.md`, `manifest.json` (with piece IDs `p1, p2, ...`), one `pieces/<p>.md` per manifest entry, and to render `decomposition.md` from the manifest using the template at `~/.forge/templates/decomposition.md.hbs`.
5. **Settle** = next `result` event after Forge's final message, with `settle.specTimeoutSec` (default 300s) hard timeout. On timeout: kill subprocess, transition to `NeedsHumanIntervention("spec settle timeout", AbortOrAbandon)`.
6. Post-check: every piece in `manifest.json` has a `pieces/<p>.md` file, and `decomposition.md` renders identically from the manifest. On mismatch: one corrective message, retry settle (max 2 corrections, then `NeedsHumanIntervention`).
7. FSM → `DesignReviewing(sessionId, round = 1)`.

### 7.2 Design review

8. Codex reviews `design.md` via `codex exec --json --sandbox read-only --output-schema ~/.forge/schemas/design-review.json ...`. Schema as in 0.3 §6.2, with severity-tagged questions.
9. Codex output appended to `design.md` under `## Codex Review (round <n>)`.
10. Questions with `severity: blocking` → `DesignNeedsHumanInput` → Q&A pane → answers appended → `DesignReviewing(round + 1)`. Non-blocking questions are recorded but don't gate progress.
11. `verdict: request_changes` → resume Claude design session (`--resume <sessionId>`), ask it to revise `design.md` + manifest + affected `pieces/*.md`. Loop back to step 8. Max `config.maxDesignReviewRounds` (default 3) → `NeedsHumanIntervention("design did not converge", ReopenDesign(None))`.
12. `verdict: approve` → commit `design.md`, `manifest.json`, `decomposition.md`, `pieces/*.md`, and the design-phase audit snapshot. Open PR `[design] <feature title>`. FSM → `DesignAwaitingMerge(prNumber)`.

### 7.3 Design PR gate

From `DesignAwaitingMerge`:
- `state == MERGED` + `mergedAt != null` → `DesignReady`.
- New human comment (id > recorded baseline) or `CHANGES_REQUESTED` → `DesignPrFeedback(prNumber, round + 1)`.
- PR closed without merge → `NeedsHumanIntervention("design PR closed without merge", ReopenDesign(Some(prNumber)))`.

From `DesignPrFeedback`:
1. Write comments/changes-requested to `.forge/specs/<feature>/audit/design-pr-feedback-round-<n>.md`.
2. Resume `specDriver` design session via `--resume` with the feedback file as context.
3. Update `design.md`, `manifest.json`, affected `pieces/*.md`. Re-render `decomposition.md`.
4. `git tag forge/<feature>/design-r<n>` on the current PR head (snapshot for recoverability).
5. Commit and **force-push with lease** to the design branch (closes 0.3's "force-push vs new PR" hand-wave).
6. Return to `DesignAwaitingMerge`.

If `git push --force-with-lease` refuses (someone else pushed concurrently), transition to `NeedsHumanIntervention("design branch updated externally", ReopenDesign(Some(prNumber)))`.

### 7.4 Implementation phase

For each piece `p` in manifest order:

1. BranchManager: `syncBase()`, then `createPieceBranch(feature, p.id)`. Record the base SHA in the manifest.
2. Spawn Claude headless (flags as 0.3 §16). Prompt: implement piece `<p.id>` to its acceptance criteria; **do not commit — Forge will commit**.
3. Action log records every tool use (paths only for Read/Edit/Write; command summary + exit + duration for Bash; full question + answer for AskUserQuestion).
4. `AskUserQuestion` interception: defer the `tool_result`, pop the Forge Q&A pane (never the GitHub PR thread, regardless of trigger context), write the answer to `.forge/specs/<feature>/pieces/<p.id>.answers.md` (human audit only — fresh sessions don't see it), reply via stdin.
5. On settle (`settle.implementTimeoutSec`, default 1800s):
   - ChangeCollector (§8) classifies changes.
   - Forge commits with message `feat(<feature>): <piece title>` and signs it according to repo `commit.gpgsign`.
   - DocSync rides along: if a prior piece merged since this branch was cut, update `[x]` in `decomposition.md` and the corresponding manifest entry.
   - Push, then `createPr` (PR body rendered from `~/.forge/templates/pr-body.md.hbs`).
   - Record current highest comment id + review id as the feedback baseline.
6. FSM → `PieceAwaitingCi(p, prNumber)`.

### 7.5 CI & review polling

From `PieceAwaitingCi`:
- Readiness met (§5 + §6 freshness) → trigger code review (§9) → `PieceAwaitingReview`.
- Any required check failed: increment `piece.attempts` in the manifest. If `<= maxFixupRounds`: write `<p>.failures.md`, FSM → `PieceCiFailed(attempt)` → fix-up. Else → `NeedsHumanIntervention("piece <p> CI did not converge", RunAnotherFixup(p, prNumber))`.

From `PieceAwaitingReview`:
- Codex `approve` posted, no unresolved human `CHANGES_REQUESTED` → `PieceAwaitingMerge`.
- Codex `request_changes` posted → `PieceReviewFailed` → fix-up.
- Human comment (id > baseline) or `CHANGES_REQUESTED` → `PieceReviewFailed` → fix-up.

From `PieceAwaitingMerge`:
- `state == MERGED` + `mergedAt != null` → `Refining(p, now)`.
- New human comment or `CHANGES_REQUESTED` → `PieceReviewFailed` → fix-up. (Closes 0.2's "ignored after Codex approval" hole.)
- PR closed without merge → `NeedsHumanIntervention("piece PR closed without merge", RunAnotherFixup(p, prNumber))`.

Polling is active only in `DesignAwaitingMerge | PieceAwaitingCi | PieceAwaitingReview | PieceAwaitingMerge`.

### 7.6 Fix-up

Fresh Claude session, no `--resume`. Prompt references `<p>.failures.md` and the unchanged piece spec. Forge commits on settle (same ChangeCollector path). On `attempts > maxFixupRounds` → `NeedsHumanIntervention("piece <p> fix-up exhausted", RunAnotherFixup(p, prNumber))`.

---

## 8. ChangeCollector and staging

Three classes:

```scala
enum ChangeClass:
  case Allow | Deny | Ask
```

Default decision rule:
1. Path matches `staging.denyPatterns` → `Deny`.
2. Path is outside the repo root → `Deny`.
3. Path is under `.git/` → `Deny`.
4. Path is ignored by `.gitignore` → `Deny` *unless* it's the `.forge/specs/...` tree (Forge's own outputs).
5. Otherwise → `Allow`.

Strict mode (`staging.requireExplicitAllow: true`) flips rule 5 to: `Allow` only if path matches `staging.allowPatterns`; else `Ask`.

Default config:

```json
{
  "staging": {
    "requireExplicitAllow": false,
    "denyPatterns": [
      "**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/id_rsa*",
      "**/credentials.json", "**/.aws/**", "**/.ssh/**",
      "**/target/**", "**/build/**", "**/dist/**", "**/node_modules/**",
      "**/.bloop/**", "**/.metals/**", "**/.idea/**", "**/.vscode/**"
    ],
    "allowPatterns": []
  }
}
```

`Deny` → transition to `NeedsHumanIntervention("change collector denied <path>", RunAnotherFixup(p, prNumber))`. `Ask` → Q&A pane, default option is `Deny`. `Allow` → staged. Stage plan recorded in action log and rendered into the PR body.

Rationale for defaults: a Python or Go repo without `src/` would refuse every new file under 0.3's allow-list. Most repos have sensible `.gitignore` files that already encode their conventions; trusting that is safer than a hand-picked allow-list. Strict mode is available for repos that explicitly want it.

---

## 9. Review-agent posting (Forge owns the diff)

Flow:
1. Forge fetches PR diff: `gh pr diff <n>` (Slice 0 pins the version that supports `--patch`).
2. Forge fetches changed-file metadata: `gh api repos/.../pulls/<n>/files`.
3. Prompt Codex with: piece spec, acceptance criteria, design link, full diff, file metadata.
4. Validate review JSON against `~/.forge/schemas/code-review.json` (severity-tagged questions; `path/side/line` or `path: null` blockers).
5. **`verdict: request_changes` with empty blockers** → adapter bug. Log `review.invalid_verdict`. Retry once with corrective prompt: "your verdict was request_changes but you provided no blockers — either approve or provide at least one blocker". On second failure → `NeedsHumanIntervention("review adapter produced invalid verdict", RunAnotherFixup(p, prNumber))`.
6. Post inline comments and a summary review.

### 9.1 `anchorText` matching (closes 0.3's hand-wave)

For each blocker with `path != null`:

```
attempt 1: POST /repos/.../pulls/<n>/comments
            with {path, side, line, commit_id: <PR head SHA>, body}
            using the line-based API (pinned in §16).

on rejection (422 invalid line / not in diff):
attempt 2: scan ±10 lines of `line` in the PR head version of <path>
            for an exact substring of anchorText.
            If found, retry attempt 1 with the matched line.

on no match:
attempt 3: scan the full changed-file diff for anchorText.
            If found, retry attempt 1 with that line.

on still no match:
            demote to a summary-comment bullet.
            Log review.anchor_demoted.
```

Blockers with `path: null` always go into the summary comment.

After per-blocker inline posts: one `gh pr review --approve | --request-changes` with the summary body. `--request-changes` always carries a non-empty body even if all blockers were inlined.

---

## 10. Command-aware preflight

| Command | Clean worktree required? | Other checks |
|---|---|---|
| `forge new` | Yes | Base is fast-forwardable; manifest reconcile not applicable |
| `forge spec` | Yes | On the design branch |
| `forge run` | Yes | Manifest reconcile passes |
| `forge resume --after-human-push` | Yes | On the piece branch; PR head matches local HEAD |
| `forge resume --commit-human-fix` | No | **Current branch matches `manifest.pieces[i].branch`** for the active piece |
| `forge resume --run-fixup` | Yes | On the piece branch |
| `forge reconcile` | No | — |
| `forge status` / `forge replay` / `forge rebuild-state` | No | — |
| `forge unlock --force` | No | Lock-specific (§12) |
| `forge abandon` | No | Transitions to `Abandoned` |

For `--commit-human-fix`: Forge computes the expected piece branch as `<branchPrefix>/<feature>/<active piece id>` from `manifest.json`, compares to `git branch --show-current`. Mismatch → refuse with: "You are on `<X>`, the active piece branch is `<Y>`. Switch branches and retry, or use `--after-human-push` if your fix is already pushed."

`--force` remains for all commands; use is logged as `harness.preflight_bypassed`.

---

## 11. Budget enforcement

Three caps:

```json
{
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd": 8.00,
  "maxTurnCostUsd": 2.00
}
```

Checks:
1. **Before spawning any agent (driver or reviewer):** if `feature.cost + estimatedSpawnCostUsd > maxFeatureCostUsd`, refuse → `NeedsHumanIntervention("feature budget would be exceeded", AbortOrAbandon)`. Same logic for piece cap.
2. **After every `cost.update` event:** re-evaluate all three caps. Per-feature and per-piece breaches → let the current turn complete naturally, do not spawn anything new, transition to `NeedsHumanIntervention("budget exceeded", ...)`.
3. **Per-turn breach (new in 0.4):** if a single turn's accumulated cost exceeds `maxTurnCostUsd` mid-turn, Forge sends SIGTERM to the subprocess, waits 5s, then SIGKILL. Transition to `NeedsHumanIntervention("turn budget exceeded", ...)`. This catches runaway tool-use loops that would otherwise burn through a feature's budget in one turn.

Estimated spawn cost is conservative (assume spawn will use `maxTurnCostUsd`) — better to refuse a likely-fine spawn than to overrun.

---

## 12. Locking

Files:
- `.forge/state/.lock` — OS lock target.
- `.forge/state/.lock.json` — metadata.

Metadata schema:
```json
{
  "pid": 12345,
  "hostname": "rory-laptop",
  "startedAt": "2026-05-25T15:42:18Z",
  "command": "forge run stripe-webhook",
  "feature": "stripe-webhook"
}
```

Behaviour on startup:
1. Try `FileChannel.tryLock` on `.lock`.
2. **Lock acquired, no `.lock.json`** → write metadata, proceed.
3. **Lock acquired, stale `.lock.json` exists**:
   - TUI mode: show metadata, prompt "Looks like a previous Forge run crashed. Clear the stale lock and continue? (Y/n)".
   - CLI mode: refuse unless `--yes` or `FORGE_AUTO_UNLOCK_STALE=1`. Suggest `forge unlock --force`.
   - On confirm: overwrite metadata, proceed.
4. **Lock NOT acquired**: read `.lock.json`, print holder info, refuse with exit code 2.

`forge unlock --force`:
- If OS lock is held by another live process → refuses, prints holder.
- If `.lock.json` exists but no OS lock is held → removes the metadata file, succeeds.

---

## 13. Refinery and `PlanningUpdate`

After each `PieceMerged`, Forge enters `Refining(p, startedAt)` and calls Codex with the refine schema (as 0.3 §14, severity-tagged questions).

### 13.1 UI surfacing (closes 0.3's "invisible state" gap)

- TUI: status pane shows `Refining: checking design against piece <p.id> (<elapsed>s)`.
- CLI mode: prints a single line `Refining piece <p.id>...` and an elapsed-time indicator every 10s.

### 13.2 Failure path (new in 0.4)

If the refinery `codex exec` errors (network, schema-invalid after `config.codex.refineRetries` retries, sandbox failure):
- Log `harness.refinery_failed` with the error.
- FSM → `PieceMerged` (advisory failure does not block; the piece is merged).

Rationale: refinery is advisory. The piece merged with required CI green; that's the load-bearing signal. Refinery flagging drift is valuable when it works; failing because Codex was unreachable should not strand the feature.

### 13.3 Verdict handling

- `no_change` → advance to next piece.
- `update_plan` → build a `ManifestPatch` from the refine output, validate against current manifest, FSM → `PlanningUpdate(reason, patch)`. Patch is *also* written to `.forge/specs/<feature>/audit/refine-after-<p>.json` for human review.
- `reopen_design` → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(None))`.

From `PlanningUpdate`:
- User chooses apply / defer / reopen / ignore via Q&A pane.
- Apply: mutate manifest, regenerate `decomposition.md`, write/edit affected `pieces/*.md`. Changes ride on the next piece PR. For the final piece, open a `chore(<feature>): apply planning update` PR. Atomic: write to temp manifest, validate, then `os.move`. On crash mid-apply, restart re-detects the partial state via the action log.
- Defer: snooze until after the next piece; FSM remembers via a deferred-patches list.
- Reopen / ignore: as in 0.3.

Already-merged piece IDs cannot be edited or removed by any patch — the validator rejects.

---

## 14. Agent connectors — Claude is the driver, Codex the reviewer

v1 hard-codes the roles. No `DriverAgent` / `ReviewAgent` trait, no capability matrix, no emulation layer.

`ClaudeConnector` and `CodexConnector` remain in `forge-agents/` as separate connectors, each with the surface area their role needs. The FSM calls them directly:

```scala
class Orchestrator(claude: ClaudeConnector, codex: CodexConnector, ...) {
  def specPhase(feature: Feature): IO[Unit] =
    claude.runStreamingSpec(...).flatMap { sessionId =>
      // ...
      codex.reviewDesign(designPath = ...)
    }
}
```

**Why deferred from 0.3:** the role abstraction's benefit is swappability (Codex-driven mode, Claude-reviewed mode). For a personal tool with chosen Claude + Codex, the benefit is hypothetical and the cost is real: trait + capability matrix in Slice 0 + "validate or emulate" being an open-ended sub-project (prompt engineering + JSON Schema validation + retry loops + system-prompt schema injection).

When a second user or a second agent pair appears, the refactor is well-bounded because the modules are already separate.

**Acknowledged seam:** §17 (v2 candidates) lists "agent role pluggability" with a one-paragraph migration sketch.

---

## 15. Slice 0 (trimmed from 0.3)

Validate before any Scala is written:

1. `claude -p --input-format stream-json --output-format stream-json --verbose` as a long-lived streaming subprocess.
2. Identify the isolation flag that suppresses user-level `CLAUDE.md`/skills/hooks but preserves repo-level. (0.3 hypothesised `--bare`; confirm or replace.)
3. `--resume <sessionId>` on a stream-json-started session.
4. `--append-system-prompt @file` alongside the above.
5. `AskUserQuestion` tool-use suspension behaviour and timeout.
6. `codex exec --json --output-schema --sandbox read-only` schema conformance on approve and request_changes paths.
7. `gh pr view --json state,mergedAt,mergeCommit,...` returns the expected values on merged PRs.
8. `gh api repos/.../branches/<base>/protection/required_status_checks` on protected, unprotected, and inaccessible repos.
9. **(new in 0.4)** Line-based comment API: `gh api --method POST /repos/{owner}/{repo}/pulls/{n}/comments` with `path/side/line/commit_id` body.
10. **(new in 0.4)** Rate-limit baseline: measure API calls per piece for a representative feature with one fix-up cycle.

**Removed from 0.3:** the capability matrix and "validate or emulate" emulation paths (no longer needed without the role abstraction).

Output: `slice-0-report.md` with pinned CLI versions and observed behaviour.

---

## 16. Configuration

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
  "maxTurnCostUsd": 2.00,
  "auditMode": "summary",
  "logRetention": "keep-local",
  "baseFreshness": {
    "autoUpdate": true
  },
  "ci": {
    "policy": "branch_protection_then_observed",
    "requiredChecksOverlay": [],
    "minimumExpectedChecks": 1,
    "checkDiscoveryTimeoutSec": 180,
    "stableGreenPolls": 2
  },
  "staging": {
    "requireExplicitAllow": false,
    "denyPatterns": [
      "**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/id_rsa*",
      "**/credentials.json", "**/.aws/**", "**/.ssh/**",
      "**/target/**", "**/build/**", "**/dist/**", "**/node_modules/**",
      "**/.bloop/**", "**/.metals/**", "**/.idea/**", "**/.vscode/**"
    ],
    "allowPatterns": []
  },
  "claude": {
    "model": "default",
    "permissionMode": "acceptEdits",
    "allowedTools": ["Read","Write","Edit","Bash","Glob","Grep","WebFetch","AskUserQuestion"],
    "isolationFlag": "auto"
  },
  "codex": {
    "sandbox": "read-only",
    "review": { "design": true, "code": true, "refine": true },
    "refineRetries": 2
  },
  "settle": {
    "specTimeoutSec": 300,
    "implementTimeoutSec": 1800,
    "fixupTimeoutSec": 900
  },
  "github": {
    "commentApi": "line-based",
    "cacheBranchProtection": true,
    "cacheDiff": true,
    "rateLimitBackoffMs": 60000
  }
}
```

Templates/prompts under `~/.forge/` (install); overrides under `.forge/overrides/`. Global config at `~/.config/forge/config.json`.

---

## 17. v2 candidates

Decide after lived experience. None are mutually exclusive.

| Candidate | Pick when |
|---|---|
| **Agent role pluggability** (DriverAgent/ReviewAgent traits, capability matrix, optional emulation) | A second agent pair becomes interesting (Gemini driver? Claude reviewer?) or you want OSS users to bring their own agents. Refactor scope is bounded because connectors are already in separate modules. |
| **Auto-merge on green + Codex approval** | v1 friction is "clicking Merge on PRs that are clearly fine". |
| **Stacked PRs onto a per-feature integration branch with composite CI** | v1 friction is "each piece PR rerunning the full CI from scratch". |
| **Parallel features across checkout slots** | Serial throughput becomes the bottleneck. |
| **GitLab adapter** | Adding a non-GitHub repo. Parallel parser feeding the existing `PrSnapshot` ADT. |
| **Webhook mode** | A future use case demands sub-second reactivity. |
| **Langfuse / structured tracing** | The action log alone stops being enough for tuning. |
| **Process-tuning mode** | Feed historical action logs to an LLM to suggest prompt/FSM tweaks. |

---

## 18. Decision summary (deltas from 0.3)

| Question | v0.4 decision |
|---|---|
| Agent role abstraction in v1? | **No.** Claude driver / Codex reviewer hard-coded. Connector modules separate. Role pluggability is a v2 candidate. |
| Where does `PlanningUpdate` carry its patch? | **Inline in the FSM state**, as a typed `ManifestPatch` ADT. Audit copy also written to disk. |
| What format are manifest patches? | **Typed ops** (`AddPiece`, `RemovePiece`, `EditPiece`, `ReorderPieces`). Validator rejects edits to merged pieces. |
| `Refining` failure handling? | **Treated as advisory.** Failure → `PieceMerged` + log entry. Piece is merged; refinery is not a gate. |
| ChangeCollector default policy? | **Allow anywhere not denied.** Strict-allow-list mode is opt-in. |
| ChangeCollector classes? | **Three: `Allow` / `Deny` / `Ask`.** |
| CI policy variants? | **Two: `BranchProtectionThenObserved` (default) and `None`.** Configured/observed reachable via overlay. |
| Decomposition.md manual edits? | **`forge reconcile` command** is the only way to import them into the manifest. Other commands refuse until reconciled. |
| Design PR revision? | **Force-push-with-lease to design branch** + `pre-revision-snapshot` tag. |
| anchorText matching? | **±10 lines → full file → demote to summary bullet.** Pinned algorithm. |
| `--commit-human-fix` branch check? | **Explicit:** current branch must match `manifest.pieces[i].branch` for the active piece. |
| Stale-lock UX? | **TUI prompt or `--yes`/env-var** for automation. `forge unlock --force` only works if no live OS lock. |
| Per-turn cost cap? | **Yes — `maxTurnCostUsd` (default \$2).** Runaway turns are killed, not allowed to settle. |
| AskUserQuestion routing? | **Always Forge Q&A pane**, never back to GitHub PR threads, regardless of trigger origin. |
| GitHub comment API? | **Line-based** (`POST /pulls/{n}/comments` with `path/side/line/commit_id`). Pinned in Slice 0. |
| Rate limits? | **Cache branch protection per-feature; cache diff per (PR, head SHA); back off on 403/429 with `Retry-After`.** |
| `Refining` UI? | **Surfaced in TUI status pane and CLI elapsed-time line.** |
| `request_changes` validation? | **Empty blockers → adapter bug, retry once with corrective prompt.** |
| Slice 0 scope? | **Original 8 v0.2 items + line-based comment API + rate-limit baseline.** No capability matrix. |

---

## 19. What's deliberately *not* in v0.4

Carried forward from earlier non-goals:
- Parallel features, GitLab, stacked PRs, auto-merge, Langfuse, webhooks, in-process LLM4S — explicit v2 candidates.
- Agent role pluggability — promoted from "v0.3 design choice" back to v2 candidate (§14, §17).
- Capability-matrix validation / emulation layer — only needed if/when the role abstraction is taken up.
- Multi-machine state synchronisation — v1 assumes one Forge per repo per machine; ProcessLock enforces.

Carried forward from 0.3:
- Local canonical log + sanitized audit split — kept.
- Manifest-backed pieces — kept, with reconcile UX nailed down.
- Typed resume hints — kept, all variants preserved.
- `DesignPrFeedback` + late-stage piece feedback — kept.
- Base freshness via BranchManager — kept, with `autoUpdate` decision rule added.

The shape of the system is stable across 0.3 → 0.4. The work has been removing hand-waves, fixing the new bugs 0.3 introduced, and resisting the temptation to abstract before v1 ships.
