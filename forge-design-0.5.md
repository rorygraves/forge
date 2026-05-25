# Forge — design doc v0.5

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v0.5  •  **Target:** personal tool, OSS later

**Basis:** `forge-design-0.4.md` plus the review in `forge-design-0.4-commentary.md`. The dominant change is reversing 0.4's deferral of role configurability — v1 ships with two supported modes (Claude-driver/Codex-reviewer or Codex-driver/Claude-reviewer), bounded and minimal, with no capability matrix or emulation layer. Six smaller findings are also applied.

---

## 0. Summary of changes from 0.4

**Reversed (0.4 was wrong to defer this):**
- **Role configurability is in v1.** Two supported modes only: `claude-driver` (Claude is spec/implementation/fix-up, Codex is design/code/refinery review) and `codex-driver` (mirror). No third combination. No capability matrix. The orchestrator dispatches on `Mode`. (§14)

**Tightened from 0.4:**
- Source-of-truth / logging table restored in §2 (it was claimed-but-missing in 0.4). (§2)
- Role terminology used consistently — "driver" and "reviewer" are concrete identifiers tied to `Mode`, not residue. (Throughout)
- Manifest schema adds `baseSha`; `branch` is documented as derived (`branchPrefix + featureId + pieceId`). (§3)
- `forge reconcile` constrained to HTML-comment marker regions — edits outside the markers are refused, not silently mishandled. (§3.1)
- Required-overlay-check discovery has an explicit timeout transition to `NeedsHumanIntervention`. (§5)
- Design revision snapshot tags are local-only by default, in a `forge/_snapshots/...` namespace, retained to last 3 if pushed. (§7.3)

**Kept from 0.4:**
- ChangeCollector with three classes (`Allow`/`Deny`/`Ask`) and allow-anywhere-not-denied default. (§8)
- CI policy with two variants. (§5)
- `PlanningUpdate` carrying inline `ManifestPatch`. (§4, §13)
- `Refining` failure → `PieceMerged` (advisory). (§13)
- Per-turn cost ceiling. (§11)
- AskUserQuestion always to Forge Q&A pane. (§7, §10)
- Line-based GitHub comment API. (§9, §15)
- Command-aware preflight. (§10)
- Stale-lock metadata UX. (§12)

---

## 1. Goals and non-goals

### Goals

1. **One feature at a time, one fresh context per piece.**
2. **Interactive spec phase** terminated by `/done`.
3. **Headless implementation phase** — escalate only on `AskUserQuestion`, red required CI, or budget breach.
4. **Incremental merge** — one piece, one branch, one PR, one CI run.
5. **Cross-model review** — the configured reviewer reviews every design and every PR.
6. **Configurable driver/reviewer pair** — Claude-driver/Codex-reviewer or Codex-driver/Claude-reviewer, both first-class. (§14)
7. **Action log per feature** — local canonical + sanitized committed audit.
8. **De-risked build order.**
9. **No dead-end states** — non-success that isn't explicit abandonment lands in resumable `NeedsHumanIntervention`.
10. **No runaway costs** — per-feature, per-piece, and per-turn ceilings.

### Non-goals (v1)

- Parallel features.
- Multi-repo / monorepo split work.
- Long-running daemon.
- Langfuse integration.
- Worktrees.
- Auto-merge.
- Stacked PRs.
- Driver/reviewer pairings other than the two supported modes (e.g., same-CLI-both-roles, third-party agents).
- GitLab support.

---

## 2. Source of truth and logging

| Path | Role | Committed? |
|---|---|---|
| `.forge/specs/<feature>/design.md` | Human-readable design intent | Yes |
| `.forge/specs/<feature>/manifest.json` | **Machine source for pieces** (IDs, order, status, PRs, baseSha, hashes) | Yes |
| `.forge/specs/<feature>/decomposition.md` | Human-readable piece plan, **rendered from manifest** with editable-region markers | Yes |
| `.forge/specs/<feature>/pieces/<p>.md` | Human-readable detail spec for piece `<p>` | Yes |
| `.forge/specs/<feature>/audit/*.md` | Sanitized milestone summaries (design review, PR feedback rounds, refinery outcomes) | Yes |
| `.forge/specs/<feature>/audit/*.jsonl` | Optional sanitized action snapshots | Per `auditMode` |
| `.forge/log/<feature>.jsonl` | **Local canonical runtime log** (full action log) | No (gitignored) |
| `.forge/state/<feature>.json` | Local rebuildable state cache | No (gitignored) |
| `.forge/state/.lock` | OS lock target | No (gitignored) |
| `.forge/state/.lock.json` | Lock metadata: PID, host, command, feature, startedAt | No (gitignored) |

**Invariant:** the local runtime log is canonical for the current machine. The state cache is rebuilt from that log via `forge rebuild-state`. Committed audit files are for code review and historical understanding, not the primary FSM replay source.

`auditMode` values:
- `full` — commit sanitized JSONL snapshots at every milestone.
- `summary` (default) — commit Markdown summaries only.
- `local-only` — commit no audit artifacts beyond design/decomposition/manifest/piece specs.

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
  "mode": "claude-driver",
  "designPr": 4290,
  "pieces": [
    {
      "id": "p1",
      "order": 1,
      "title": "Add webhook route and signature verification",
      "specPath": ".forge/specs/stripe-webhook/pieces/p1.md",
      "acceptanceHash": "sha256:...",
      "status": "pending",
      "baseSha": "a1b2c3d4...",
      "prNumber": null,
      "mergeCommit": null,
      "mergedAt": null,
      "attempts": 0
    }
  ]
}
```

Notes:
- `mode` is captured at feature creation. Mid-feature mode switching is unsupported in v1.
- `baseSha` is recorded by BranchManager at piece-branch creation time and used by the base-freshness check (§6).
- **`branch` is not stored.** It is always derived: `s"${branchPrefix}/${featureId}/${pieceId}"`. Design branch: `s"${branchPrefix}/${featureId}/design"`. Snapshot tags: `s"${branchPrefix}/_snapshots/${featureId}/design-r${n}"`.
- Piece IDs are stable once created (`p1`, `p2`, ...).
- Already-merged pieces are immutable except for annotations (PR number, merge SHA, status).

### 3.1 `forge reconcile` — constrained editable regions

The rendered `decomposition.md` includes HTML-comment markers around editable sections:

```markdown
## Pieces

<!-- forge:order-start -->
1. <!-- forge:piece p1 -->**p1: Add webhook route and signature verification**<!-- /forge:piece -->
   <!-- forge:editable-summary p1 -->
   Add `POST /stripe/webhook` route. Verify signature against `STRIPE_WEBHOOK_SECRET`.
   <!-- /forge:editable-summary -->
   <!-- forge:status p1 -->_pending_<!-- /forge:status -->

2. <!-- forge:piece p2 -->**p2: Persist webhook events**<!-- /forge:piece -->
   ...
<!-- forge:order-end -->
```

Editable regions:
- **`forge:editable-summary <pid>`** — free-text summary for a piece. Edits map to `EditPiece(id, title=None, summary=Some(newText))`.
- **`forge:order-start` / `forge:order-end`** — reordering pieces by reordering the list items. Detected as `ReorderPieces(newOrder)`.

Not editable:
- Piece IDs (`forge:piece <pid>` markers).
- Status badges (`forge:status <pid>` markers).
- Anything outside the editable regions (the title block, prose between regions, the H2 header itself).

Behaviour:
- On every `forge` command (except read-only ones and `forge reconcile` itself), Forge re-renders `decomposition.md` from the manifest and diffs against on-disk. **If anything outside editable markers has changed, the command refuses** with: "These edits aren't reconcilable from the rendered file. Edit `manifest.json` directly, or the underlying `pieces/<p>.md` file, then re-run." The offending hunks are shown.
- If only editable-region edits are present, the command refuses with: "Run `forge reconcile <feature>` to import these edits into the manifest."
- `forge reconcile <feature>` shows the changes that *will* be applied as `ManifestPatch` ops, asks `Apply? (y/N)`, and on `y` applies them as a `PlanningUpdate` (§13). Pieces already merged cannot be removed or have their order changed past the merge point — the validator rejects.

Rationale: this is rigid but predictable and zero-LLM. Free-form Markdown reconciliation requires model assistance and reintroduces non-determinism into what should be a deterministic path. Users who want richer edits use the manifest or piece files directly.

---

## 4. Domain model

```scala
opaque type FeatureId = String
opaque type PieceId   = String
opaque type PrNumber  = Int

enum Mode:
  case ClaudeDriver   // Claude: spec, implementation, fixup. Codex: design/code/refinery review.
  case CodexDriver    // mirror.

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
  case PlanningUpdate(reason: String, patch: ManifestPatch)
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
  case ApplyPlanningUpdate(patch: ManifestPatch)
  case AbortOrAbandon

enum ManifestPatchOp:
  case AddPiece(after: Option[PieceId], piece: Piece)
  case RemovePiece(id: PieceId)
  case EditPiece(id: PieceId, title: Option[String], summary: Option[String],
                 specPath: Option[String], acceptanceHash: Option[String])
  case ReorderPieces(newOrder: Vector[PieceId])

case class ManifestPatch(reason: String, ops: Vector[ManifestPatchOp]):
  def validate(manifest: Manifest): Either[Vector[ValidationError], ManifestPatch]

enum CiPolicy:
  case BranchProtectionThenObserved
  case None

case class Question(text: String, options: Vector[String],
                    allowFreeText: Boolean, severity: QuestionSeverity)

enum QuestionSeverity:
  case Blocking | Clarifying | Optional
```

---

## 5. CI readiness policy

Two variants:

| Policy | Behaviour |
|---|---|
| `BranchProtectionThenObserved` (default) | Use branch-protection required-check list if present. Otherwise wait `checkDiscoveryTimeoutSec` for at least one check, then require all observed checks green for `stableGreenPolls` consecutive polls. |
| `None` | Skip CI gating; log `ci.skipped`. |

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

Required set: union of branch-protection required-checks and `requiredChecksOverlay`. The overlay never reduces the required set.

Discovery & freshness rules:
1. After `gh pr view` first returns, if no checks have appeared and `checkDiscoveryTimeoutSec` elapsed:
   - `BranchProtectionThenObserved` with empty protection set + zero observed checks → `NeedsHumanIntervention("no CI checks discovered", ResumeAfterHumanPush(...))`.
   - `None` → straight to readiness.
2. **(new in 0.5)** After the discovery window, if any name in `requiredChecksOverlay` is **not** present in the rollup → `NeedsHumanIntervention("required overlay check '<name>' never appeared", ResumeAfterHumanPush(p, prNumber))`. Closes the "wait forever for a typo'd check name" hole.
3. If checks have appeared but fewer than `minimumExpectedChecks`, keep polling until met or timeout.
4. Readiness requires `stableGreenPolls` consecutive green polls (default 2 = ~60s margin).

Branch-protection lookup is cached per feature for the Forge process lifetime.

---

## 6. BranchManager and base freshness

```scala
trait BranchManager:
  def preflight(command: ForgeCommand): IO[PreflightReport]
  def syncBase(base: BranchName): IO[BaseSnapshot]
  def createDesignBranch(feature: FeatureId): IO[BranchName]
  def createPieceBranch(feature: FeatureId, piece: PieceId): IO[(BranchName, Sha)]
  def baseFreshness(pr: PrNumber, expectedBaseSha: Sha): IO[BaseFreshness]
  def pushCurrentBranch(forceWithLease: Boolean = false): IO[Unit]
  def createPr(title: String, body: String, base: BranchName): IO[PrNumber]
  def updatePrBranch(pr: PrNumber): IO[Unit]
  def tagSnapshot(name: String): IO[Unit]
  def pushTag(name: String): IO[Unit]                    // only used if pushSnapshotTags = true
  def deleteRemoteTag(name: String): IO[Unit]            // for snapshot retention
```

Rules:
- Before any branch creation: `git fetch` and fast-forward the configured base. Local divergence → `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)`.
- `createPieceBranch` returns the base SHA so the FSM can persist it into the manifest entry.
- On CI readiness, `baseFreshness(pr, manifest.pieces[i].baseSha)`:
  - Branch protection requires up-to-date → trust protection.
  - Not required + `config.baseFreshness.autoUpdate: true` (default) → `updatePrBranch(pr)`, re-enter `PieceAwaitingCi`.
  - `autoUpdate: false` → `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(p, prNumber))`.

---

## 7. Lifecycle

Throughout this section: **driver** = the CLI selected by `Mode` (Claude for `claude-driver`, Codex for `codex-driver`). **reviewer** = the other CLI.

### 7.0 Preconditions

Every state-changing command:
1. Acquire `.forge/state/.lock` (§12).
2. `BranchManager.preflight(command)` — command-aware (§10).
3. Manifest/decomposition.md reconcile check (§3.1).
4. State cache verified against log replay; rewritten if divergent.

### 7.1 Spec phase

1. `forge new "title"` with optional `--mode claude-driver|codex-driver` (defaults from config). BranchManager creates `<branchPrefix>/<feature>/design`.
2. Spawn driver in streaming spec mode with `~/.forge/prompts/specify.<driver>.md` (one prompt file per CLI — different CLIs warrant different system prompts).
3. Action log records `<driver>.user_message` and `<driver>.assistant_text` (≤200 chars each).
4. User types `/done`. Forge sends a final message asking the driver to write `design.md`, `manifest.json` (with piece IDs `p1, p2, ...` and `mode` field), one `pieces/<p>.md` per manifest entry, and to render `decomposition.md` from the manifest using `~/.forge/templates/decomposition.md.hbs` (with editable-region markers per §3.1).
5. Settle = next `result` event after Forge's final message, `settle.specTimeoutSec` (300s) timeout. On timeout: kill, `NeedsHumanIntervention("spec settle timeout", AbortOrAbandon)`.
6. Post-check: every piece in manifest has a `pieces/<p>.md` file; `decomposition.md` renders identically. Up to 2 corrective messages.
7. FSM → `DesignReviewing(sessionId, round = 1)`.

### 7.2 Design review

8. Reviewer reviews `design.md` via its schema-constrained exec mode (`~/.forge/schemas/design-review.json`).
9. Output appended to `design.md` under `## Reviewer Review (round <n>)`.
10. `severity: blocking` questions → `DesignNeedsHumanInput` → Q&A pane → appended → `DesignReviewing(round + 1)`. Non-blocking questions are recorded but don't gate.
11. `verdict: request_changes` → resume driver design session, ask it to revise design + manifest + affected piece files. Loop to step 8. Max `config.maxDesignReviewRounds` (default 3) → `NeedsHumanIntervention("design did not converge", ReopenDesign(None))`.
12. `verdict: approve` → commit design assets and design-phase audit snapshot. Open PR `[design] <title>`. FSM → `DesignAwaitingMerge(prNumber)`.

### 7.3 Design PR gate

From `DesignAwaitingMerge`:
- `state == MERGED` + `mergedAt != null` → `DesignReady`.
- New human comment (id > baseline) or `CHANGES_REQUESTED` → `DesignPrFeedback(prNumber, round + 1)`.
- PR closed without merge → `NeedsHumanIntervention("design PR closed without merge", ReopenDesign(Some(prNumber)))`.

From `DesignPrFeedback`:
1. Write feedback bundle to `.forge/specs/<feature>/audit/design-pr-feedback-round-<n>.md`.
2. Resume driver design session via `--resume`/equivalent.
3. Update design assets.
4. **Snapshot tag:** `git tag <branchPrefix>/_snapshots/<feature>/design-r<n>` on current design branch HEAD before force-push.
   - **Default: local-only** — tag is not pushed to the remote. Recovery is local-machine.
   - If `config.github.pushSnapshotTags: true`, push the tag, then prune: delete `design-r<n-3>` (keep last 3 per feature) via `deleteRemoteTag`.
5. Force-push-with-lease to the design branch.
6. Return to `DesignAwaitingMerge`.

If `--force-with-lease` refuses → `NeedsHumanIntervention("design branch updated externally", ReopenDesign(Some(prNumber)))`.

### 7.4 Implementation phase

For each piece `p` in manifest order:

1. `syncBase()`, then `createPieceBranch(feature, p.id)` — returns `(branchName, baseSha)`. Persist `baseSha` in `manifest.pieces[i]`.
2. Spawn driver headless with `~/.forge/prompts/implement.<driver>.md`. Prompt: implement piece `<p.id>` to acceptance criteria; **do not commit**.
3. Action log records every tool use.
4. **AskUserQuestion** (or the driver's equivalent question primitive): always to the Forge Q&A pane. Never posted to GitHub PR threads regardless of trigger origin.
5. On settle (`settle.implementTimeoutSec`, 1800s):
   - ChangeCollector (§8) classifies changes.
   - Forge commits with `feat(<feature>): <piece title>`.
   - DocSync: if a prior piece merged since the branch was cut, update `[x]` and status badge in `decomposition.md` and the manifest entry.
   - Push, then `createPr`. Record baseline comment + review IDs.
6. FSM → `PieceAwaitingCi(p, prNumber)`.

### 7.5 CI & review polling

Transitions as in 0.4 §7.5, with the driver/reviewer terminology applied:
- `PieceAwaitingCi`: required CI checks green + base fresh → reviewer code review → `PieceAwaitingReview`. Required failed → fix-up via driver.
- `PieceAwaitingReview`: reviewer `approve` + no human `CHANGES_REQUESTED` → `PieceAwaitingMerge`. Reviewer `request_changes` or human feedback → `PieceReviewFailed` → fix-up via driver.
- `PieceAwaitingMerge`: merged → `Refining(p, now)`. New human feedback before merge → `PieceReviewFailed` → fix-up. PR closed without merge → `NeedsHumanIntervention`.

### 7.6 Fix-up

Fresh driver session, no resume. Prompt references `<p>.failures.md`. Forge commits on settle. `attempts > maxFixupRounds` → `NeedsHumanIntervention("piece <p> fix-up exhausted", RunAnotherFixup(p, prNumber))`.

---

## 8. ChangeCollector and staging

Three classes: `Allow` / `Deny` / `Ask`.

Default decision rule:
1. Path matches `staging.denyPatterns` → `Deny`.
2. Path is outside the repo root → `Deny`.
3. Path is under `.git/` → `Deny`.
4. Path is ignored by `.gitignore` → `Deny` unless under `.forge/specs/...`.
5. Otherwise → `Allow`.

Strict mode (`staging.requireExplicitAllow: true`) flips rule 5: `Allow` only if path matches `staging.allowPatterns`; else `Ask`.

Default config: see §15.

`Deny` → `NeedsHumanIntervention("change collector denied <path>", RunAnotherFixup(p, prNumber))`. `Ask` → Q&A pane, default `Deny`. `Allow` → staged. Stage plan recorded in the action log and rendered into the PR body.

---

## 9. Reviewer posting (Forge owns the diff)

Flow:
1. Fetch PR diff with `gh pr diff <n>`.
2. Fetch changed-file metadata with `gh api repos/.../pulls/<n>/files`.
3. Prompt reviewer with piece spec, acceptance, design link, full diff, file metadata.
4. Validate review JSON against `~/.forge/schemas/code-review.json`.
5. **`verdict: request_changes` with empty blockers → adapter bug.** Log `review.invalid_verdict`. Retry once with corrective prompt. Second failure → `NeedsHumanIntervention("review adapter produced invalid verdict", RunAnotherFixup(p, prNumber))`.
6. Post inline comments (anchorText algorithm below) + a summary review.

`anchorText` matching:
- Attempt 1: post at `line`.
- On rejection: scan ±10 lines of `line` in the PR head file for exact substring of `anchorText`; retry.
- On no match: scan whole changed-file diff; retry.
- Still no match: demote to summary bullet. Log `review.anchor_demoted`.

API: **line-based** (`POST /pulls/{n}/comments` with `path/side/line/commit_id`). Classic position-API not used. Pinned in Slice 0.

---

## 10. Command-aware preflight

| Command | Clean worktree? | Other checks |
|---|---|---|
| `forge new` | Yes | Base fast-forwardable |
| `forge spec` | Yes | On design branch |
| `forge run` | Yes | Manifest reconcile passes |
| `forge resume --after-human-push` | Yes | On piece branch; PR head == local HEAD |
| `forge resume --commit-human-fix` | No | **Current branch == derived piece branch** (`<branchPrefix>/<feature>/<active piece id>`) |
| `forge resume --run-fixup` | Yes | On piece branch |
| `forge reconcile` | No | — |
| `forge status` / `replay` / `rebuild-state` | No | — |
| `forge unlock --force` | No | Lock-specific (§12) |
| `forge abandon` | No | — |

`--force` available everywhere; logged as `harness.preflight_bypassed`.

---

## 11. Budget enforcement

Three caps:
```json
{ "maxFeatureCostUsd": 25.00, "maxPieceCostUsd": 8.00, "maxTurnCostUsd": 2.00 }
```

Checks:
1. **Before spawning any agent** (driver or reviewer): `feature.cost + estimatedSpawnCost > cap` → refuse → `NeedsHumanIntervention("feature budget would be exceeded", AbortOrAbandon)`.
2. **After every `cost.update`:** re-evaluate all three caps. Per-feature/per-piece breach → let current turn complete, no new spawns, transition to `NeedsHumanIntervention("budget exceeded", ...)`.
3. **Per-turn breach:** if a single turn exceeds `maxTurnCostUsd` mid-turn, SIGTERM the subprocess, 5s grace, SIGKILL. Transition to `NeedsHumanIntervention("turn budget exceeded", ...)`. Catches runaway tool-use loops.

Estimated spawn cost is conservative (assume `maxTurnCostUsd`).

---

## 12. Locking

Files: `.forge/state/.lock` (OS lock target), `.forge/state/.lock.json` (metadata).

Metadata: `{ pid, hostname, startedAt, command, feature }`.

Startup behaviour:
1. Try `FileChannel.tryLock`.
2. Acquired + no metadata → write metadata, proceed.
3. Acquired + stale metadata:
   - TUI: prompt "previous Forge run crashed; clear stale lock? (Y/n)".
   - CLI: refuse unless `--yes` or `FORGE_AUTO_UNLOCK_STALE=1`. Suggest `forge unlock --force`.
4. Not acquired → read metadata, print holder, exit 2.

`forge unlock --force`:
- Live OS lock by another process → refuses, prints holder.
- Stale metadata only (no OS lock) → removes metadata, succeeds.

---

## 13. Refinery and `PlanningUpdate`

After each `PieceMerged`, Forge enters `Refining(p, startedAt)` and calls reviewer with the refine schema.

### 13.1 UI surfacing

- TUI: status pane `Refining: checking design against piece <p.id> (<elapsed>s)`.
- CLI: `Refining piece <p.id>...` + elapsed-time tick every 10s.

### 13.2 Failure path (advisory)

Reviewer `exec` errors (network, schema-invalid after `config.<reviewer>.refineRetries` retries, sandbox failure):
- Log `harness.refinery_failed`.
- FSM → `PieceMerged` (already merged; refinery is advisory, not a gate).

### 13.3 Verdict handling

- `no_change` → advance.
- `update_plan` → build `ManifestPatch`, validate, FSM → `PlanningUpdate(reason, patch)`. Audit copy also written to `.forge/specs/<feature>/audit/refine-after-<p>.json`.
- `reopen_design` → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(None))`.

From `PlanningUpdate`: user chooses apply/defer/reopen/ignore via Q&A. Apply: write to temp manifest, validate, `os.move`. Atomic. Changes ride on the next piece PR; for the final piece, open a `chore(<feature>): apply planning update` PR.

---

## 14. Agent connectors and Mode

v1 supports two modes:

```scala
enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fixup. Codex: design/code/refinery review.
  case CodexDriver    // Codex: spec/implementation/fixup. Claude: design/code/refinery review.
```

Config:
```json
{ "mode": "claude-driver" }
```

Selection rules:
- Set at feature creation (`forge new --mode ...` or `config.mode`); persisted in `manifest.json`.
- **Mid-feature mode switching is unsupported in v1.** The captured `mode` in the manifest is authoritative — if the user changes `config.mode` mid-feature, Forge ignores it and warns once.

Orchestrator wiring:
```scala
class Orchestrator(claude: ClaudeConnector, codex: CodexConnector, mode: Mode):
  private val driver: Connector = mode match
    case ClaudeDriver => claude
    case CodexDriver  => codex
  private val reviewer: Connector = mode match
    case ClaudeDriver => codex
    case CodexDriver  => claude
```

Both connectors implement the same `Connector` surface:
```scala
trait Connector:
  def name: String                                                     // "claude" | "codex"
  def runStreamingSpec(systemPrompt: Path): StreamingSession           // driver role
  def runHeadlessImplementation(prompt: ImplementationPrompt): Stream[IO, AgentEvent]  // driver role
  def runFixup(prompt: FixupPrompt): Stream[IO, AgentEvent]            // driver role
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]         // reviewer role
  def reviewPr(input: PrReviewInput): IO[PrReview]                     // reviewer role
  def refine(input: RefineInput): IO[RefineResult]                     // reviewer role
  def costFrom(event: AgentEvent): Option[Cost]
```

This is **not** an open abstraction for arbitrary agents — only `ClaudeConnector` and `CodexConnector` implement `Connector`, and only two pairings are supported. No third-party adapter, no "validate or emulate" layer, no capability matrix. If a CLI cannot satisfy a method in its assigned role, the corresponding mode is unsupported and Slice 0 records it.

**Mode-aware assets:**
- System prompts: `~/.forge/prompts/specify.<driver>.md`, `~/.forge/prompts/implement.<driver>.md`, `~/.forge/prompts/fixup.<driver>.md`. (Each driver may need a different system prompt; the file naming is explicit so the operator knows what's active.)
- Schemas are shared (`design-review.json`, `code-review.json`, `refine.json`) — both reviewers produce against the same output shape.

**Action log:** events carry `actor: "claude" | "codex"` and `role: "driver" | "reviewer"` so logs are unambiguous across modes.

---

## 15. Slice 0 — 2×2 validation

Validate before any Scala is written. Each Claude/Codex item must be checked for **both** the driver role and the reviewer role.

**Driver role validation (both CLIs):**
1. Long-lived streaming subprocess with stream-json (Claude) / equivalent (Codex). Stdin = user messages; result events per turn.
2. Isolation flag suppressing user-level config but preserving repo-level. (Claude: confirm or replace `--bare`. Codex: identify sandbox/profile equivalent.)
3. Session resume on a streaming session (for design revision loop).
4. System-prompt injection from a file alongside the above.
5. Question primitive: `AskUserQuestion` (Claude); identify Codex's equivalent or document its absence. (If Codex can't ask questions, `CodexDriver` mode degrades — driver just proceeds without asking; design review must be especially thorough to compensate.)

**Reviewer role validation (both CLIs):**
6. Schema-constrained JSON output. (Codex: `exec --json --output-schema`. Claude: identify equivalent — likely `claude -p --output-format json` plus prompt-side schema instruction with validator retry.)
7. Read-only sandbox mode.

**Other:**
8. `gh pr view --json state,mergedAt,mergeCommit,...` on merged PRs.
9. `gh api repos/.../branches/<base>/protection/required_status_checks` on protected, unprotected, inaccessible repos.
10. Line-based comment API: `gh api --method POST /repos/{owner}/{repo}/pulls/{n}/comments` with `path/side/line/commit_id` body.
11. Rate-limit baseline: API calls per piece for a representative feature with one fix-up.

The 2×2 matrix (Claude-driver, Claude-reviewer, Codex-driver, Codex-reviewer) produces a small validation table:

| Cell | Required capabilities | Slice 0 outcome |
|---|---|---|
| Claude driver | streaming, isolation, resume, system-prompt, questions | record what works |
| Codex driver | streaming, isolation, resume, system-prompt, questions | record what works |
| Claude reviewer | schema output, read-only | record what works |
| Codex reviewer | schema output, read-only | record what works |

If a cell fails on a required capability, its mode is **unsupported in v1** and the doc records the gap. No emulation is attempted.

Output: `slice-0-report.md` with pinned CLI versions, observed behaviour per cell, and any unsupported-mode declarations.

---

## 16. Configuration

`.forge/config.json`:

```json
{
  "mode": "claude-driver",
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
  "baseFreshness": { "autoUpdate": true },
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
    "isolationFlag": "auto",
    "refineRetries": 2
  },
  "codex": {
    "sandbox": "read-only",
    "driverSandbox": "workspace-write",
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
    "rateLimitBackoffMs": 60000,
    "pushSnapshotTags": false,
    "snapshotTagRetention": 3
  }
}
```

Per-CLI keys are `claude` and `codex`. Each carries the role-specific knobs (e.g., Codex needs a more permissive sandbox when acting as driver — `driverSandbox: workspace-write` — and a stricter one as reviewer — `sandbox: read-only`). Templates and prompts under `~/.forge/`; overrides under `.forge/overrides/`. Global config at `~/.config/forge/config.json`.

---

## 17. v2 candidates

| Candidate | Pick when |
|---|---|
| **Auto-merge on green + reviewer approval** | v1 friction is "clicking Merge on PRs that are clearly fine". |
| **Stacked PRs onto a per-feature integration branch with composite CI** | v1 friction is "each piece PR rerunning full CI". |
| **Parallel features across checkout slots** | Serial throughput becomes the bottleneck. |
| **GitLab adapter** | A non-GitHub repo. Parallel parser into the existing `PrSnapshot` ADT. |
| **Third-party agents / arbitrary role pairings** | A real third agent appears. Generalise the two-mode `Mode` enum into `DriverAgent`/`ReviewAgent` traits at that point. The 2-of-2 `Connector` interface is already most of the work. |
| **Webhook mode** | Sub-second reactivity demanded. |
| **Langfuse / structured tracing** | Action log alone stops being enough. |
| **Process-tuning mode** | Feed historical action logs to an LLM to suggest prompt/FSM tweaks. |

---

## 18. Decision summary (deltas from 0.4)

| Question | v0.5 decision |
|---|---|
| Role configurability in v1? | **Yes — two supported modes** (`claude-driver`, `codex-driver`). Set at feature creation, persisted in manifest. No mid-feature switch. |
| How many agent pairings? | **Exactly two.** No third combination (no same-CLI-both-roles, no third-party). Generalisation deferred to v2. |
| Capability matrix / emulation? | **No.** Slice 0 records 2×2; unsupported cells become "this mode is unsupported", not "we'll emulate it". |
| SoT / logging table? | **Restored in §2** (was claimed-but-missing in 0.4). |
| Role terminology? | **Used consistently:** "driver" = CLI selected by `Mode`, "reviewer" = the other. Action log carries `actor` + `role`. |
| Manifest `baseSha`? | **Added.** Captured at branch creation. |
| Manifest `branch`? | **Derived, not stored.** Documented derivation rule. |
| `forge reconcile` scope? | **Constrained to HTML-comment editable regions.** Edits outside markers are refused; user told to edit `manifest.json` or `pieces/<p>.md` directly. Zero LLM in the reconcile path. |
| Required-overlay-check timeout? | **Yes.** After discovery window, missing overlay name → `NeedsHumanIntervention`. |
| Design snapshot tags? | **Local-only by default**, namespaced under `<branchPrefix>/_snapshots/<feature>/design-r<n>`. Push opt-in (`pushSnapshotTags`), retention last 3. |

---

## 19. What's deliberately *not* in v0.5

Carried forward:
- Parallel features, GitLab, stacked PRs, auto-merge, Langfuse, webhooks, in-process LLM4S, third-party agents — explicit v2 candidates.
- Mid-feature mode switching — captured `mode` in manifest is authoritative.
- Model-assisted reconcile of arbitrary `decomposition.md` edits — constrained editable regions instead.
- Capability matrix / emulation — Slice 0 declares unsupported cells, not workarounds.
- Multi-machine state sync — one Forge per repo per machine.

The shape of the system is stable across 0.4 → 0.5. The dominant work has been restoring the role model 0.4 wrongly removed, while bounding its scope to exactly the two modes the product requires.
