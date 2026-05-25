# Forge — design doc v0.6

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v0.6  •  **Target:** personal tool, OSS later

**Basis:** `forge-design-0.5.md` plus the review in `forge-design-0.5-commentary.md`. v0.6 sharpens the two-mode promise into something specifiable in code: the connector's emulation budget is named and bounded ("schema fallback"), the driver-question requirement has two acceptable mechanisms (interactive primitive or halt-with-question protocol), Slice 0 failure is reframed as a design-scope decision rather than silent triage, and four spec tightenings turn ambiguous rules into concrete invariants.

---

## 0. Summary of changes from 0.5

1. **"Schema fallback" is a named, bounded form of emulation.** Applies only to schema-constrained reviewer output. Max 2 attempts (1 retry with corrective prompt). Connectors declare per-method `Mechanism = Native | SchemaFallback | HaltWithQuestion` and the action log records which. Other capability gaps are *not* emulated. (§14)
2. **Driver question capability is required**, satisfiable by *either* an interactive primitive (mid-turn suspension; `AskUserQuestion` for Claude) *or* the halt-with-question protocol (driver halts with a structured `{ status: "needs_human", question, options }` output; Forge surfaces via Q&A; re-spawns with the answer prepended). Silent proceed is never permitted. (§14, §7.4)
3. **Slice 0 outcome is a design-scope decision, not silent triage.** v1 targets both modes as first-class; a Slice 0 capability gap that the schema-fallback / halt-with-question protocols can't close becomes an explicit doc change (wait, narrow scope, or treat as v1-blocking). (§1, §15)
4. **`minimumExpectedChecks` timeout gets an explicit `NeedsHumanIntervention` transition.** (§5)
5. **Reorder invariant stated precisely:** merged pieces form an immutable prefix in their original order; reorder only permutes the pending tail. (§3.1)
6. **`FeatureId` slugging algorithm + collision rule specified.** (§3.2)

Nothing else changes. ChangeCollector, CI policy, manifest schema (except for the slug rule), audit split, preflight, locking, budget enforcement, snapshot tag rules, lifecycle, v2 candidates — all carry forward from 0.5 unchanged.

---

## 1. Goals and non-goals

### Goals

1. One feature at a time, one fresh context per piece.
2. Interactive spec phase terminated by `/done`.
3. Headless implementation phase — escalate only on a driver-question event, red required CI, or budget breach.
4. Incremental merge — one piece, one branch, one PR, one CI run.
5. Cross-model review — configured reviewer reviews every design and every PR.
6. **Both `claude-driver` and `codex-driver` modes are v1 first-class.** Slice 0 confirms the pinned Claude and Codex CLI versions satisfy the required capabilities for both. A discovered gap that the bounded fallback protocols (§14) can't close is a design-scope issue, resolved by changing the doc, not by silently picking one mode.
7. Action log per feature — local canonical + sanitized committed audit.
8. De-risked build order.
9. **No silent proceed past uncertainty.** Driver question events always reach the human; runaway turns are aborted, not allowed to settle.
10. No dead-end states.

### Non-goals (v1)

- Parallel features, multi-repo, daemon, Langfuse, worktrees, auto-merge, stacked PRs, GitLab.
- Driver/reviewer pairings other than the two supported modes.
- Mid-feature mode switching.
- Emulation of capabilities other than schema-constrained reviewer output. (See §14.)

---

## 2. Source of truth and logging

Carried forward from 0.5 §2 unchanged.

| Path | Role | Committed? |
|---|---|---|
| `.forge/specs/<feature>/design.md` | Human-readable design intent | Yes |
| `.forge/specs/<feature>/manifest.json` | Machine source for pieces | Yes |
| `.forge/specs/<feature>/decomposition.md` | Human view, rendered from manifest with editable-region markers | Yes |
| `.forge/specs/<feature>/pieces/<p>.md` | Human-readable detail spec | Yes |
| `.forge/specs/<feature>/audit/*.md` | Sanitized milestone summaries | Yes |
| `.forge/specs/<feature>/audit/*.jsonl` | Optional sanitized action snapshots | Per `auditMode` |
| `.forge/log/<feature>.jsonl` | Local canonical runtime log | No (gitignored) |
| `.forge/state/<feature>.json` | Local rebuildable state cache | No (gitignored) |
| `.forge/state/.lock` | OS lock target | No (gitignored) |
| `.forge/state/.lock.json` | Lock metadata | No (gitignored) |

`auditMode` values: `full | summary (default) | local-only`.

---

## 3. Manifest-backed work breakdown

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

Carried forward: `mode` set at feature creation; `baseSha` captured at branch creation; `branch` derived (`<branchPrefix>/<featureId>/<pieceId>`); piece IDs stable once created.

### 3.1 `forge reconcile` and the reorder invariant

Editable regions (HTML-comment markers in `decomposition.md`): `forge:editable-summary <pid>` and `forge:order-start ... forge:order-end`. Edits outside markers → refuse. Edits inside markers → mapped deterministically to `EditPiece(summary=...)` or `ReorderPieces(newOrder)`.

**Reorder invariant (sharpened in 0.6):** merged pieces form an immutable prefix of the piece list, in their original relative order. Reorder operations affect only the pending tail.

The `ReorderPieces(newOrder)` validator enforces:
1. `newOrder` is a permutation of the current piece IDs (no add/remove via reorder).
2. `newOrder.take(mergedCount) == currentOrder.take(mergedCount)`, where `mergedCount = pieces.count(_.status == "merged")`. I.e., the merged prefix is identical.
3. Only `newOrder.drop(mergedCount)` (the pending tail) may differ from `currentOrder.drop(mergedCount)`.

Violations: validator rejects with the specific rule that failed; `forge reconcile` refuses and prints which constraint was broken.

### 3.2 `FeatureId` slugging and collision (new in 0.6)

`forge new "Add Stripe webhook receiver"` produces a `FeatureId` by:

1. Lowercase.
2. Replace any run of non-`[a-z0-9]` characters with a single `-`.
3. Trim leading/trailing `-`.
4. Truncate to 40 characters at the last hyphen boundary (no mid-word truncation; if no hyphen exists in the first 40 chars, hard-truncate to 40).
5. If the result is empty or starts with a digit, prefix with `f-`.

**Collision rule:**

- Check `.forge/specs/<slug>/` for existence.
- On collision, append `-2`, then `-3`, etc., until a free slug is found.
- The chosen slug is shown to the user before `forge new` proceeds and recorded in the manifest.
- `forge new --id <explicit-id> "..."` bypasses the algorithm; the explicit id is validated against `^[a-z][a-z0-9-]{0,49}$`.

Examples:

| Input | Slug |
|---|---|
| `"Add Stripe webhook receiver"` | `add-stripe-webhook-receiver` |
| `"Fix bug #1234"` | `fix-bug-1234` |
| `"🚀 Launch"` | `launch` |
| `"42-line-issue"` | `f-42-line-issue` |
| Second `"Add Stripe webhook receiver"` | `add-stripe-webhook-receiver-2` |

Branch names, paths, audit dirs, snapshot tags, and manifest identity all derive from this slug.

---

## 4. Domain model

```scala
opaque type FeatureId = String  // slugged per §3.2
opaque type PieceId   = String
opaque type PrNumber  = Int

enum Mode:
  case ClaudeDriver
  case CodexDriver

enum Mechanism:
  case Native            // CLI-enforced capability
  case SchemaFallback    // bounded prompt+validate+retry; §14
  case HaltWithQuestion  // driver-question fallback; §14
```

`FsmState`, `ResumeHint`, `ManifestPatchOp`, `ManifestPatch`, `CiPolicy`, `Question`, `QuestionSeverity` — unchanged from 0.5 §4.

---

## 5. CI readiness policy

Two variants (unchanged from 0.5): `BranchProtectionThenObserved` (default), `None`.

Required set = union of branch-protection required-checks and `requiredChecksOverlay`.

Discovery & timeout rules:

1. **No checks discovered at all** after `checkDiscoveryTimeoutSec`:
   - `BranchProtectionThenObserved` with empty protection set → `NeedsHumanIntervention("no CI checks discovered", ResumeAfterHumanPush(p, prNumber))`.
   - `None` → straight to readiness.

2. **Missing overlay check** after the discovery window — any name in `requiredChecksOverlay` not present in the rollup → `NeedsHumanIntervention("required overlay check '<name>' never appeared", ResumeAfterHumanPush(p, prNumber))`.

3. **(new in 0.6)** **Fewer than `minimumExpectedChecks` observed** after `checkDiscoveryTimeoutSec`:
   ```
   NeedsHumanIntervention(
     s"only ${observed} CI checks observed, expected at least ${minimumExpectedChecks}",
     ResumeAfterHumanPush(p, prNumber)
   )
   ```
   Resume after the human either fixes the CI config so more checks appear or lowers `minimumExpectedChecks` for this repo.

4. Readiness: `stableGreenPolls` (default 2) consecutive green polls + base freshness (§6).

Branch-protection lookup is cached per feature for the Forge process lifetime.

---

## 6. BranchManager and base freshness

Carried forward from 0.5 §6 unchanged.

---

## 7. Lifecycle

Carried forward from 0.5 §7 with the additions for the halt-with-question protocol noted in §7.4 below.

### 7.0 Preconditions, 7.1 Spec phase, 7.2 Design review, 7.3 Design PR gate

Unchanged from 0.5. (Refer to forge-design-0.5.md §7.0–7.3.)

### 7.4 Implementation phase — driver-question handling

For each piece `p` in manifest order:

1. `syncBase()`, `createPieceBranch(...)`, persist `baseSha`.
2. Spawn driver headless with `~/.forge/prompts/implement.<driver>.md`. Prompt: implement piece `<p.id>` to acceptance criteria; do not commit.
3. Action log records every tool use, carrying `actor`, `role`, and `mechanism`.
4. **Driver question handling (sharpened in 0.6):**
   - If the driver's question `mechanism == Native`: Claude's `AskUserQuestion` interception path applies. Forge defers the `tool_result`, pops the Q&A pane, sends the answer back on stdin. Driver continues mid-turn.
   - If the driver's question `mechanism == HaltWithQuestion`: the driver halts with structured output (validated against `~/.forge/schemas/halt-with-question.json` — `{ status: "needs_human", question: string, options: string[], allowFreeText: bool, severity: enum }`). Forge:
     1. Pops the Q&A pane with the structured question.
     2. Records the question + answer in the action log.
     3. Writes the answer to `.forge/specs/<feature>/pieces/<p.id>.answers.md`.
     4. Re-spawns the driver with the original implementation prompt **plus** an appended `"# Previous answer\n\nQ: <question>\nA: <answer>\n"` block. The driver resumes from a fresh subprocess.
   - **Silent proceed is never permitted.** A driver that produces neither a question nor a completion within its turn budget is treated as a runaway (§11 per-turn cap kicks in).
5. On settle (`settle.implementTimeoutSec`, 1800s):
   - ChangeCollector classifies changes.
   - Forge commits with `feat(<feature>): <piece title>`.
   - DocSync rides along on `decomposition.md` and the manifest.
   - Push, then `createPr`. Record baseline comment + review IDs.
6. FSM → `PieceAwaitingCi(p, prNumber)`.

Trade-off note: `HaltWithQuestion` costs a subprocess re-spawn per question, which means re-establishing context. For Codex-driver mode this is the expected behaviour (Codex's `exec` is short-lived by design). For Claude-driver mode the `Native` path is always used.

### 7.5 CI & review polling, 7.6 Fix-up

Unchanged from 0.5.

---

## 8. ChangeCollector and staging

Carried forward from 0.5 §8 unchanged.

---

## 9. Reviewer posting (Forge owns the diff)

Carried forward from 0.5 §9 unchanged.

---

## 10. Command-aware preflight

Carried forward from 0.5 §10 unchanged.

---

## 11. Budget enforcement

Carried forward from 0.5 §11 unchanged.

---

## 12. Locking

Carried forward from 0.5 §12 unchanged.

---

## 13. Refinery and `PlanningUpdate`

Carried forward from 0.5 §13 unchanged.

---

## 14. Agent connectors, Mode, and the bounded fallback protocols

v1 supports two modes:

```scala
enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fixup. Codex: design/code/refinery review.
  case CodexDriver    // mirror.
```

### 14.1 `Connector` trait

Both connectors implement the same surface:

```scala
trait Connector:
  def name: String                                                   // "claude" | "codex"

  // Driver methods
  def runStreamingSpec(systemPrompt: Path): StreamingSession
  def runHeadlessImplementation(prompt: ImplementationPrompt): Stream[IO, AgentEvent]
  def runFixup(prompt: FixupPrompt): Stream[IO, AgentEvent]
  def questionMechanism: Mechanism                                    // Native or HaltWithQuestion

  // Reviewer methods
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]
  def reviewPr(input: PrReviewInput): IO[PrReview]
  def refine(input: RefineInput): IO[RefineResult]
  def schemaMechanism: Mechanism                                      // Native or SchemaFallback

  // Telemetry
  def costFrom(event: AgentEvent): Option[Cost]
```

`questionMechanism` and `schemaMechanism` are *declared by the connector* and *enforced by the orchestrator*. They flow into the action log so any run can be audited: "this design review used Codex Native schema output" vs "this design review used Claude SchemaFallback (retry-count: 1)".

### 14.2 Schema fallback — the only permitted form of emulation

**Scope:** *only* schema-constrained reviewer output. Not streaming, not questions, not sandboxing, not tool use, not anything else.

**Protocol:**

1. Connector calls the underlying CLI with a system-prompt instruction to produce JSON matching `<schema>`. (No `--output-schema` flag; the CLI is being asked to follow instructions.)
2. On response, the connector validates the output locally against the JSON schema.
3. **Validation succeeds** → return the parsed result. Log `<actor>.schema_fallback_ok`.
4. **Validation fails** → send one corrective follow-up: `"Your last output did not match the required schema. Error: <validator error>. Please produce a corrected JSON object matching this schema: <schema>."` Validate the response.
5. **Second validation fails** → connector returns an adapter error. The orchestrator transitions to `NeedsHumanIntervention("reviewer schema fallback exhausted", ...)` with the schema validation errors in the audit file.

**Hard limits:**
- Maximum 2 attempts total (1 retry).
- Each attempt counts toward the per-turn budget.
- The action log records `attempt`, `validator_error` on failure, and `mechanism: "SchemaFallback"`.

**Why this is the *only* permitted emulation:** schema-constrained output is a well-defined, locally-checkable contract — the validator either accepts or rejects, deterministically. Other capabilities (streaming, question-asking, sandboxing) don't have that local checkability — emulating them invites silent semantic drift. By restricting emulation to the one capability where the contract is mechanically verifiable, the system stays predictable.

### 14.3 Halt-with-question — the driver-question fallback

**Scope:** *only* driver question events when the CLI lacks a mid-turn interactive primitive.

**Protocol:**

1. Driver system prompt (in `~/.forge/prompts/implement.<driver>.md`) establishes the convention: *"If you are blocked on a decision that requires human judgement, do not guess. Stop execution and output a single JSON object matching this schema: `{ status: 'needs_human', question: string, options: string[], allowFreeText: boolean, severity: 'blocking' | 'clarifying' | 'optional' }`. Then terminate."*
2. Orchestrator's event loop watches for a final output matching the schema.
3. On match:
   - Pop the Q&A pane (same UI as the `Native` path).
   - Record question + answer in the action log; write to `<p.id>.answers.md`.
   - Re-spawn the driver with the original prompt + the previous-answer block prepended.
4. Action log entries carry `mechanism: "HaltWithQuestion"`, `respawn_count: <n>`.

**Hard limits:**
- Maximum `config.maxHaltRespawns` re-spawns per piece (default 5). Exceeded → `NeedsHumanIntervention("halt-with-question respawn limit exceeded", RunAnotherFixup(p, prNumber))`.
- Each re-spawn counts as a fresh turn against the per-turn cost cap.

**Why bounded:** without a respawn limit, a driver that keeps asking small questions can spin indefinitely. The cap surfaces "this piece is too ambiguous for the model" rather than burning tokens on it.

### 14.4 What is *not* a fallback

Anything not listed in §14.2 or §14.3. If a CLI lacks streaming, lacks isolation flags, lacks read-only sandboxing, lacks tool use, lacks any other required capability — Slice 0 records it and §1 Goal 6 kicks in (design-scope decision).

The action log uses `mechanism` only with the three values: `Native`, `SchemaFallback`, `HaltWithQuestion`. Anything else is a bug.

### 14.5 Mode selection and orchestration

```scala
class Orchestrator(claude: ClaudeConnector, codex: CodexConnector, mode: Mode):
  private val driver: Connector = mode match
    case ClaudeDriver => claude
    case CodexDriver  => codex
  private val reviewer: Connector = mode match
    case ClaudeDriver => codex
    case CodexDriver  => claude
```

Mode is set at feature creation (`forge new --mode ...` or `config.mode`), persisted in the manifest. Mid-feature mode switching is unsupported.

Mode-aware assets: system prompts per driver (`~/.forge/prompts/{specify,implement,fixup}.<driver>.md`); schemas shared.

---

## 15. Slice 0 — 2×2 validation + the fallback protocols

Validate before any Scala is written. The matrix is the same 2×2 from 0.5, with two additional checks for the fallback protocols.

**Driver role (both CLIs):**

1. Long-lived streaming subprocess.
2. Isolation flag suppressing user-level config but preserving repo-level.
3. Session resume on a streaming session.
4. System-prompt injection from a file.
5. **Question mechanism:** either (a) native mid-turn suspension primitive (Claude's `AskUserQuestion`) **or** (b) reliable halt-with-question per §14.3. *At least one must work.*

**Reviewer role (both CLIs):**

6. **Schema-constrained output:** either (a) native (`exec --output-schema` for Codex) **or** (b) reliable schema fallback per §14.2 (prompt+validate+1 retry, success rate measured on the design and code review schemas across N=20 representative inputs).
7. Read-only sandbox mode.

**Other:**

8. `gh pr view --json state,mergedAt,mergeCommit,...` on merged PRs.
9. `gh api repos/.../branches/<base>/protection/required_status_checks` on protected/unprotected/inaccessible repos.
10. Line-based comment API: `gh api --method POST /repos/.../pulls/.../comments` with `path/side/line/commit_id`.
11. Rate-limit baseline.

### 15.1 Slice 0 outcome handling

For each of the four matrix cells, Slice 0 records:

| Cell | `questionMechanism` declared | `schemaMechanism` declared | Status |
|---|---|---|---|
| Claude driver | `Native` (`AskUserQuestion`) | — | Supported |
| Codex driver | `HaltWithQuestion` (assumed; validate) | — | Supported iff halt-with-question protocol works reliably |
| Claude reviewer | — | `SchemaFallback` (assumed; validate) | Supported iff fallback success rate ≥ threshold |
| Codex reviewer | — | `Native` (`--output-schema`) | Supported |

If a cell is **unsupported** after Slice 0, the response is a **design-scope decision**, not silent triage:

1. **Wait** for a future CLI version that closes the gap.
2. **Narrow scope explicitly** — update §1 Goal 6 to say e.g. *"v1 ships `claude-driver` only; `codex-driver` is v2 pending Codex CLI halt-with-question reliability"*. The doc change is the contract.
3. **Treat as v1-blocking** — Forge doesn't ship until the gap closes.

Slice 0 does not silently pick a supported mode and proceed.

Output: `slice-0-report.md` with pinned CLI versions, per-cell capability findings, fallback success-rate measurements, and the scope decision.

---

## 16. Configuration

`.forge/config.json` — additions from 0.5:

```json
{
  "maxHaltRespawns": 5,
  "schemaFallback": {
    "maxAttempts": 2,
    "logValidatorErrors": true
  }
}
```

Everything else carried forward from 0.5 §16 unchanged: `mode`, `baseBranch`, `branchPrefix`, `pollIntervalMs`, fix-up/review/budget caps, `auditMode`, `baseFreshness`, `ci`, `staging`, per-CLI `claude`/`codex` blocks, `settle`, `github`.

---

## 17. v2 candidates

Unchanged from 0.5 §17. Notable: "third-party agents / arbitrary role pairings" remains v2; the `Connector` trait is most of the work, but generalising `Mode` into pluggable role traits is the remaining lift.

---

## 18. Decision summary (deltas from 0.5)

| Question | v0.6 decision |
|---|---|
| Is the "no emulation" claim absolute? | **No — one named exception.** `SchemaFallback` is permitted only for schema-constrained reviewer output, bounded to 2 attempts. All other capabilities must be `Native`. |
| Can a driver proceed silently when it can't ask? | **No.** Driver question capability is required: either `Native` (mid-turn suspension) or `HaltWithQuestion` (structured halt + re-spawn). A driver that produces neither a question nor a completion is treated as runaway and aborted by the per-turn cap. |
| What if Slice 0 finds a capability gap? | **Design-scope decision.** Wait, narrow scope explicitly (doc change), or treat as v1-blocking. Not silent triage. |
| `minimumExpectedChecks` timeout outcome? | **`NeedsHumanIntervention("only N checks observed, expected ≥M", ResumeAfterHumanPush(...))`.** |
| Reorder validator rule? | **Merged pieces form an immutable prefix in original order.** Reorder permutes only the pending tail. Validator checks the three conditions in §3.1. |
| `FeatureId` slugging? | **Specified algorithm:** lowercase → non-alnum runs → `-`, trim, 40-char hyphen-boundary truncate, `f-` prefix if empty/digit-leading. Collision: append `-2`, `-3`, …. `--id` override validated against `^[a-z][a-z0-9-]{0,49}$`. |
| Per-method capability declaration? | **`Connector` exposes `questionMechanism` and `schemaMechanism`**; action log records `mechanism` per event. |
| Halt-with-question re-spawn cap? | **`config.maxHaltRespawns` (default 5)** per piece. Exceeded → `NeedsHumanIntervention`. |
| Schema fallback retry cap? | **`config.schemaFallback.maxAttempts` (default 2)**. Exceeded → `NeedsHumanIntervention`. |

---

## 19. What 0.6 does *not* change from 0.5

- Two-mode `Mode` enum, exactly two variants.
- Connector module separation (`ClaudeConnector`, `CodexConnector` under `forge-agents/`).
- ChangeCollector classes (`Allow` / `Deny` / `Ask`) and allow-anywhere-not-denied default.
- CI policy variants (`BranchProtectionThenObserved`, `None`).
- `PlanningUpdate` carrying inline `ManifestPatch`.
- `Refining` failure → `PieceMerged` (advisory).
- Per-turn cost ceiling.
- AskUserQuestion always to Forge Q&A pane (extended to the halt-with-question case — both routes hit the same pane).
- Line-based GitHub comment API.
- Command-aware preflight.
- Stale-lock metadata UX.
- Snapshot tags local-only by default, `<branchPrefix>/_snapshots/<feature>/design-r<n>` namespace.
- Per-feature, per-piece, per-turn budget enforcement.

The shape is stable across 0.5 → 0.6. The work was naming and bounding the connector's emulation budget so the two-mode promise is specifiable in code.
