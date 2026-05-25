# Forge — design doc v0.8

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v0.8  •  **Target:** personal tool, OSS later

**This is a fully standalone spec.** Earlier versions (0.1 → 0.7) and their commentaries remain in the workspace as a record of how the design evolved. Implementers should read only this document — nothing here references prior versions.

---

## 0. Why "Forge"

Forge is what sits between you and the agents. You bring raw intent; the harness shapes it into a design, breaks it into pieces, hammers each piece through review and CI until it's mergeable, and remembers everything it did so the process itself can be tuned.

---

## 1. Goals and non-goals

### Goals

1. **One feature at a time, one fresh context per piece.** No long-running session accumulating 200k tokens of irrelevant history.
2. **Interactive spec phase.** Sit with the configured *driver* CLI as it explores the codebase, refine the design in dialogue, then commit a design doc when satisfied.
3. **Headless implementation phase.** Once design is approved, the harness runs the driver piece-by-piece, escalating only on (a) a driver-question event, (b) red required CI, or (c) a budget breach.
4. **Incremental merge.** Each piece is its own branch off `main`, its own PR, its own CI run, merged before the next piece starts. No stacked PRs in v1.
5. **Cross-model review.** The configured *reviewer* CLI reviews every design and every PR.
6. **Both `claude-driver` and `codex-driver` modes are v1 first-class.** Slice 0 confirms the pinned CLI versions satisfy the required capabilities for both. A gap that the bounded fallback protocols (§7) can't close is a design-scope decision (wait / narrow scope / treat as v1-blocking), not silent triage.
7. **Action log per feature.** Local canonical runtime log + sanitized committed audit snapshots.
8. **De-risked build order.** Connectors built and validated first; orchestrator on top.
9. **No silent proceed past uncertainty.** Driver question events always reach the human. Runaway turns are aborted by *both* settle timeout and per-turn cost cap.
10. **No dead-end states.** Non-success that isn't explicit abandonment lands in resumable `NeedsHumanIntervention`. `Abandoned` is reachable only via `forge abandon`.

### Non-goals (v1)

- Parallel features.
- Multi-repo / monorepo split work.
- Long-running daemon on a VPS. Forge runs on the user's laptop, lifetime = TUI session.
- Langfuse integration.
- Worktrees (devcontainer-incompatible per prior experience).
- Auto-merge of design or piece PRs (v2 candidate).
- Stacked PRs (v2 candidate).
- Driver/reviewer pairings other than the two supported modes (no same-CLI-both-roles, no third-party agents).
- Mid-feature mode switching.
- Emulation of capabilities other than schema-constrained reviewer output (§7).
- GitLab support (v2 candidate; the `PrSnapshot` ADT keeps the seam clean).

---

## 2. Pain points and fixes

| Pain | Fix in Forge |
|---|---|
| Manual task decomposition | Design phase produces `manifest.json` (machine source) + `decomposition.md` (rendered human view) + per-piece `pieces/<p>.md` |
| Context bloat across features | New driver subprocess per piece; no session resume across pieces |
| 30-min CI feedback latency | Poll `gh` every 15–60s only when FSM is in a state that cares |
| Inconsistent branches | BranchManager owns all git; deterministic naming derived from config |
| Poor /loop ergonomics | Forge IS the loop |
| No cross-model review | Configured reviewer reviews every design and every PR |
| Hallucinated specs | Driver-question events always to the Forge Q&A pane; never silent proceed |
| Process opacity | Per-feature action log (local canonical) + committed audit snapshots |
| Failure leaves feature in limbo | `NeedsHumanIntervention(reason, resumeHint)` is non-terminal; four typed resume paths |
| Plan drifts from reality | Refinery proposes manifest patches; `PlanningUpdate` is an explicit FSM transition |
| Runaway agent cost | Per-feature, per-piece, and per-turn cost ceilings; settle timeouts |
| `gh` rate limits | Cache branch protection and diffs; back off on 403/429 |
| Locked into one agent vendor | Two first-class modes: `claude-driver` and `codex-driver` |

---

## 3. Architecture

### 3.1 Component diagram

```
┌────────────────────────────────────────────────────────────────────┐
│  Forge TUI  (termflow, Elm-architecture)                           │
│                                                                    │
│  ┌──────────────────┐  ┌──────────────────────────────────────┐    │
│  │ Status pane      │  │ Active pane (one of):                │    │
│  │ • feature        │  │  - Streaming driver chat             │    │
│  │ • current piece  │  │  - Action log tail                   │    │
│  │ • FSM state      │  │  - Q&A prompt (driver question)      │    │
│  │ • tokens / $     │  │  - Idle / awaiting CI                │    │
│  │ • budget left    │  │                                      │    │
│  │ • last action    │  │                                      │    │
│  └──────────────────┘  └──────────────────────────────────────┘    │
└──────────────────────────────┬─────────────────────────────────────┘
                               │  Sub[Msg] / Cmd[Msg]
┌──────────────────────────────┴─────────────────────────────────────┐
│  Orchestrator (pure Scala)                                         │
│                                                                    │
│   ┌─────────────┐    ┌─────────────┐    ┌────────────────────┐     │
│   │ FSM         │    │ ActionLog   │    │ StateCache         │     │
│   │ (per-feat.) │───▶│ (.jsonl,    │    │ (.forge/state/,    │     │
│   │             │    │  local      │    │  rebuildable)      │     │
│   │             │    │  canonical) │    │                    │     │
│   └─────────────┘    └─────────────┘    └────────────────────┘     │
│           │                                                        │
│           ├── BranchManager   (git + gh, returns PrSnapshot)       │
│           ├── PRWatcher       (15–60s gh poll, only when active)   │
│           ├── SpecStore       (markdown + manifest in repo)        │
│           ├── DocSync         (rides on next piece PR)             │
│           ├── ProcessLock     (flock on .forge/state/.lock)        │
│           ├── BudgetTracker   (per-feature/piece/turn caps)        │
│           └── ChangeCollector (staging policy)                     │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────────────┐
│  Agent connectors  (Mode dispatches driver / reviewer)             │
│                                                                    │
│   ┌─────────────────────┐         ┌─────────────────────┐          │
│   │ ClaudeConnector     │         │ CodexConnector      │          │
│   │  - streaming        │         │  - exec --json      │          │
│   │  - headless (-p)    │         │  - schema-validated │          │
│   │  - stream-json I/O  │         │  - sandbox modes    │          │
│   │  - tool intercept   │         │  - halt-with-q.     │          │
│   │  - AskUserQuestion  │         │  - schema fallback  │          │
│   └─────────────────────┘         └─────────────────────┘          │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 Module layout (sbt)

```
forge/
  build.sbt
  modules/
    forge-core/          ← FSM, ActionLog, StateCache, domain model, PrSnapshot ADT
    forge-agents/        ← Connector trait, ClaudeConnector, CodexConnector, StreamingSession, stream-json
    forge-git/           ← BranchManager, PRWatcher (gh CLI, returns PrSnapshot)
    forge-specs/         ← SpecStore, DocSync, Manifest, ChangeCollector
    forge-tui/           ← termflow app, panes, key bindings
    forge-app/           ← main entry, wiring, config, ProcessLock
    forge-it/            ← integration tests with real claude/codex
```

### 3.3 Dependencies

| Purpose | Library |
|---|---|
| TUI | `org.llm4s::termflow:0.0.1` (the published release; depend on a `0.1.0-SNAPSHOT` from `publishLocal` until 0.1.0 ships) |
| Effects | `cats-effect` 3.x |
| Streams | `fs2` |
| Process spawn / files | `com.lihaoyi::os-lib` |
| JSON | `com.lihaoyi::upickle` |
| JSON Schema validation | `com.networknt:json-schema-validator` (for SchemaFallback) |
| HTTP (future webhook mode) | `org.http4s::http4s-ember-server` (not used in v1) |
| Git status reads | shell-out to `git` + `gh`; consider `jgit` only if shell-out becomes painful |
| File locking | JDK `FileChannel.tryLock` (no extra dep) |
| Testing | `munit`, `munit-cats-effect` |

Deliberately **not** depending on `llm4s` core in v1. Forge talks to Claude/Codex via their CLIs, not via APIs directly.

---

## 4. Source of truth and logging

| Path | Role | Committed? |
|---|---|---|
| `.forge/specs/<feature>/design.md` | Human-readable design intent | Yes |
| `.forge/specs/<feature>/manifest.json` | **Machine source for pieces** (IDs, order, status, PRs, baseSha, hashes, mode) | Yes |
| `.forge/specs/<feature>/decomposition.md` | Human view, rendered from manifest with editable-region markers | Yes |
| `.forge/specs/<feature>/pieces/<p>.md` | Human-readable detail spec for piece `<p>` | Yes |
| `.forge/specs/<feature>/audit/*.md` | Sanitized milestone summaries (design review, PR feedback, refinery) | Yes |
| `.forge/specs/<feature>/audit/*.jsonl` | Optional sanitized action snapshots | Per `auditMode` |
| `.forge/log/<feature>.jsonl` | **Local canonical runtime log** | No (gitignored) |
| `.forge/state/<feature>.json` | Local rebuildable state cache | No (gitignored) |
| `.forge/state/.lock` | OS lock target | No (gitignored) |
| `.forge/state/.lock.json` | Lock metadata: PID, host, command, feature, startedAt | No (gitignored) |

**Invariant:** the local runtime log is canonical for the current machine. The state cache is rebuilt from that log via `forge rebuild-state`. Committed audit files exist for code review and historical understanding, not as the FSM replay source.

`auditMode` values:
- `full` — commit sanitized JSONL snapshots at every milestone.
- `summary` (default) — commit Markdown summaries only.
- `local-only` — commit no audit artifacts beyond design/decomposition/manifest/piece specs.

---

## 5. Manifest-backed work breakdown

### 5.1 Manifest schema

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
      "summary": "Add POST /stripe/webhook. Verify signature against STRIPE_WEBHOOK_SECRET.",
      "specPath": ".forge/specs/stripe-webhook/pieces/p1.md",
      "acceptanceHash": "sha256:...",
      "status": "pending",
      "baseSha": null,
      "prNumber": null,
      "mergeCommit": null,
      "mergedAt": null,
      "attempts": 0
    }
  ]
}
```

Rules:
- `mode` is captured at feature creation and is authoritative. Mid-feature changes to `config.mode` are ignored with a one-time warning.
- **`baseSha` is nullable.** It is `null` for `status: "pending"` pieces (no branch yet exists). It is set by `BranchManager.createPieceBranch` to the base SHA at branch creation and persisted before the FSM transitions out of branch creation. Validator rule: a piece with `status != "pending"` must have a non-null `baseSha`.
- **`branch` is not stored.** Derive: `s"${branchPrefix}/${featureId}/${pieceId}"`. Design branch: `s"${branchPrefix}/${featureId}/design"`. Snapshot tags: `s"${branchPrefix}/_snapshots/${featureId}/design-r${n}"`.
- Piece IDs (`p1`, `p2`, …) are stable once created.
- Already-merged pieces are immutable except for annotations (PR number, merge SHA, status).
- `attempts` is **the total number of fix-up rounds for the piece, regardless of cause** (CI failure, reviewer-requested changes, human-requested changes, late human feedback). It is *not* split per-cause. See §11.5.

### 5.2 `FeatureId` slugging and collision

`forge new "Add Stripe webhook receiver"` produces a `FeatureId` by:

1. Lowercase.
2. Replace any run of non-`[a-z0-9]` characters with a single `-`.
3. Trim leading/trailing `-`.
4. Truncate to 40 characters at the last hyphen boundary; if no hyphen exists in the first 40 chars, hard-truncate to 40.
5. If the result is empty or starts with a digit, prefix with `f-`.

**Collision rule:**
- Check `.forge/specs/<slug>/` for existence.
- On collision, append `-2`, then `-3`, etc.
- The chosen slug is shown to the user before `forge new` proceeds.
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

### 5.3 `decomposition.md` rendering and editable regions

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
- **`forge:editable-summary <pid>`** — free-text summary for a piece. Edits map to `EditPiece(summary = Some(newText))`.
- **`forge:order-start` / `forge:order-end`** — reordering pieces by reordering the list items. Detected as `ReorderPieces(newOrder)`.

Not editable:
- Piece IDs (`forge:piece <pid>` markers).
- Status badges (`forge:status <pid>` markers).
- Anything outside the editable regions.

### 5.4 `forge reconcile`

On every `forge` command (except read-only ones and `forge reconcile` itself), Forge re-renders `decomposition.md` from the manifest and diffs against on-disk:

- **Edits outside editable markers** → refuse the command with: "These edits aren't reconcilable from the rendered file. Edit `manifest.json` directly, or the underlying `pieces/<p>.md` file, then re-run." Offending hunks shown.
- **Only editable-region edits present** → refuse with: "Run `forge reconcile <feature>` to import these edits into the manifest."
- `forge reconcile <feature>` shows the changes that *will* be applied as `ManifestPatch` ops, asks `Apply? (y/N)`, and on `y` applies them as a `PlanningUpdate` (§14).

### 5.5 Reorder invariant

**Merged pieces form an immutable prefix of the piece list, in their original relative order. Reorder operations affect only the pending tail.**

The `ReorderPieces(newOrder)` validator enforces:
1. `newOrder` is a permutation of the current piece IDs (no add/remove via reorder).
2. `newOrder.take(mergedCount) == currentOrder.take(mergedCount)`, where `mergedCount = pieces.count(_.status == "merged")`.
3. Only `newOrder.drop(mergedCount)` (the pending tail) may differ from `currentOrder.drop(mergedCount)`.

Violations: validator rejects with the specific rule that failed; `forge reconcile` refuses and prints which constraint was broken.

---

## 6. Domain model

```scala
opaque type FeatureId = String  // slugged per §5.2
opaque type PieceId   = String
opaque type PrNumber  = Int
opaque type BranchName = String
opaque type Sha       = String

enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fix-up. Codex: design/code/refinery review.
  case CodexDriver    // mirror.

enum QuestionMechanism:
  case Native            // CLI mid-turn suspension primitive (Claude's AskUserQuestion)
  case HaltWithQuestion  // driver halts with structured output; Forge re-spawns

enum SchemaMechanism:
  case Native            // CLI-enforced schema (Codex --output-schema)
  case SchemaFallback    // bounded prompt+validate+1 retry

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
  case ResolveLocalImplementationChanges(p: PieceId, branch: BranchName)   // pre-PR
  case ReopenDesign(prNumber: Option[PrNumber])
  case ApplyPlanningUpdate(patch: ManifestPatch)
  case AbortOrAbandon

enum ManifestPatchOp:
  case AddPiece(after: Option[PieceId], piece: Piece)
  case RemovePiece(id: PieceId)
  case EditPiece(id: PieceId,
                 title: Option[String], summary: Option[String],
                 specPath: Option[String], acceptanceHash: Option[String])
  case ReorderPieces(newOrder: Vector[PieceId])

case class ManifestPatch(reason: String, ops: Vector[ManifestPatchOp]):
  def validate(manifest: Manifest): Either[Vector[ValidationError], ManifestPatch]
  // enforces §5.5 reorder invariant, immutability of merged pieces, ID stability,
  // baseSha invariant (non-pending pieces must have non-null baseSha)

enum CiPolicy:
  case BranchProtectionThenObserved   // default
  case None                            // intentional skip

case class Question(text: String, options: Vector[String],
                    allowFreeText: Boolean, severity: QuestionSeverity)

enum QuestionSeverity:
  case Blocking | Clarifying | Optional

// Provider-agnostic; gh-parsing lives behind BranchManager / PRWatcher.
case class PrSnapshot(
  number: PrNumber,
  state: PrState,                      // OPEN | CLOSED | MERGED
  mergedAt: Option[Instant],
  mergeCommit: Option[Sha],
  requiredChecks: CheckRollup,
  reviewDecision: Option[ReviewDecision],
  unseenComments: Vector[PrComment],
  mergeable: Option[Boolean]
)

case class Action(
  seq: Long,                           // monotonic per feature
  at: Instant,
  feature: FeatureId,
  piece: Option[PieceId],
  actor: Option[String],               // "claude" | "codex" | None for harness/user
  role: Option[String],                // "driver" | "reviewer" | None
  kind: String,
  payload: ujson.Value
)
```

---

## 7. Agent connectors, Mode, and bounded fallback protocols

v1 supports two modes:

```scala
enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fix-up. Codex: design/code/refinery review.
  case CodexDriver    // mirror.
```

Mode is set at feature creation (`forge new --mode ...` or `config.mode`) and persisted in the manifest. Mid-feature mode switching is unsupported.

### 7.1 `StreamingSession` and `Connector` traits

```scala
trait StreamingSession:
  def sessionId: String                              // captured from CLI's init event
  def send(input: String): IO[Unit]                  // stdin → user message
  def events: Stream[IO, AgentEvent]                 // stdout → events
  def close(): IO[Unit]                              // graceful shutdown
  def kill(): IO[Unit]                               // SIGTERM/SIGKILL for runaway bounds

trait Connector:
  def name: String                                                  // "claude" | "codex"

  // Driver methods
  def runStreamingSpec(systemPrompt: Path): IO[StreamingSession]
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession]  // for design revision
  def runHeadlessImplementation(prompt: ImplementationPrompt): Stream[IO, AgentEvent]
  def runFixup(prompt: FixupPrompt): Stream[IO, AgentEvent]
  def questionMechanism: QuestionMechanism                          // Native | HaltWithQuestion

  // Reviewer methods
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]
  def reviewPr(input: PrReviewInput): IO[PrReview]
  def refine(input: RefineInput): IO[RefineResult]
  def schemaMechanism: SchemaMechanism                              // Native | SchemaFallback

  // Telemetry
  def costFrom(event: AgentEvent): Option[Cost]
```

`questionMechanism` and `schemaMechanism` are declared by the connector and enforced by the orchestrator. They flow into the action log so any run can be audited.

Mode-aware assets:
- System prompts per driver: `~/.forge/prompts/{specify,implement,fixup}.<driver>.md`. Different CLIs warrant different system prompts; the file naming makes what's active explicit.
- Schemas are shared (`design-review.json`, `code-review.json`, `refine.json`).

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

### 7.2 `QuestionMechanism = Native` (Claude default)

Driver suspends mid-turn via a tool-use primitive (Claude's `AskUserQuestion`). Forge:
1. Defers the `tool_result`.
2. Pops the Q&A pane.
3. Sends the answer back on stdin as the `tool_result`.
4. Driver continues mid-turn.

Action log entries carry `questionMechanism: "Native"`, full question + answer.

### 7.3 `QuestionMechanism = HaltWithQuestion` (Codex default — pending Slice 0 confirmation)

Driver lacks a mid-turn interactive primitive. The system prompt establishes a convention: *"If you are blocked on a decision that requires human judgement, do not guess. Stop execution and output a single JSON object matching this schema: `{ status: 'needs_human', question: string, options: string[], allowFreeText: boolean, severity: 'blocking' | 'clarifying' | 'optional' }`. Then terminate."*

Orchestrator's event loop watches for a final output matching the schema. On match:
1. Pop the Q&A pane (same UI as the `Native` path).
2. Record question + answer in the action log; write to `<p.id>.answers.md`.
3. Re-spawn the driver with the original session prompt **plus** the previous-answer block prepended. Prior session context (design + decomposition + relevant piece spec + prior turn outputs from that session) is included so the re-spawned subprocess doesn't lose state.

Action log entries carry `questionMechanism: "HaltWithQuestion"`, `respawn_count: <n>`.

**Hard limits:**
- Maximum `config.maxHaltRespawns` re-spawns per driver session (default 5). Exceeded → `NeedsHumanIntervention("halt-with-question respawn limit exceeded", <phase-appropriate hint>)`.
- Each re-spawn counts as a fresh turn against the per-turn cost cap.

### 7.4 `SchemaMechanism = Native` (Codex default)

Reviewer CLI accepts an output schema and enforces it (Codex `exec --output-schema ...`). Output is parsed directly. Action log: `schemaMechanism: "Native"`.

### 7.5 `SchemaMechanism = SchemaFallback` (Claude default — pending Slice 0 confirmation)

The only permitted form of capability emulation. Scope: schema-constrained reviewer output only.

**Protocol:**

1. Connector calls the underlying CLI with a system-prompt instruction to produce JSON matching `<schema>`. (No native schema flag.)
2. On response, the connector validates the output locally against the JSON schema.
3. **Validation succeeds** → return the parsed result. Log `<actor>.schema_fallback_ok`.
4. **Validation fails** → send one corrective follow-up: *"Your last output did not match the required schema. Error: `<validator error>`. Please produce a corrected JSON object matching this schema: `<schema>`."* Validate the response.
5. **Second validation fails** → connector returns an adapter error. Caller decides how to react; see §7.7.

**Hard limits — protocol invariants, not configurable:**
- Maximum 2 attempts total (1 retry).
- Each attempt counts toward the per-turn budget.

`config.schemaFallback.logValidatorErrors: true` (default) controls whether validator errors land in audit files. The 2-attempt cap is not exposed in config; the connector hard-codes it.

### 7.6 What is *not* a fallback

Any capability not listed in §7.2–§7.5. If a CLI lacks streaming, lacks isolation flags, lacks read-only sandboxing, lacks tool use, or lacks any required capability — Slice 0 records it and §1 Goal 6 kicks in (design-scope decision).

The action log uses `questionMechanism` only with `Native | HaltWithQuestion`, and `schemaMechanism` only with `Native | SchemaFallback`. Anything else is a bug. The two enums are deliberately separate — mixing is a type error, not a runtime check.

### 7.7 Process retries vs SchemaFallback attempts — distinct layers

Two different retry concepts at different levels. They must not be conflated:

- **`refineProcessRetries`** (and the equivalent for other reviewer calls) — wraps around the *whole* reviewer `exec` call. Retries only on **process-level failures**: network timeouts, sandbox launch errors, the CLI subprocess crashing, OS-level errors. Default: 2 per reviewer per call type. Configured as `config.<reviewer>.refineProcessRetries`.
- **SchemaFallback 2-attempt cap** — *inside* the connector when `schemaMechanism == SchemaFallback`. Retries only on schema-validation failures. Hard invariant, not configurable.

**Layering:** `RetryOnProcessFailure(refineProcessRetries) { connector.refine(...) /* may use SchemaFallback internally */ }`.

A schema-validation failure that exhausts SchemaFallback returns an adapter error to the caller. The caller does **not** retry on adapter errors — those are content failures, not transport failures. For refinery: the advisory-failure path (§14.2) applies. For design or code review: transition to `NeedsHumanIntervention("reviewer schema fallback exhausted", <phase-appropriate hint>)`.

### 7.8 Driver-question handling applies to *every* driver session

The mechanisms in §7.2–§7.3 apply to:
- **Spec consolidation** (§11.1) — driver writes design/manifest/piece files.
- **Design revision** (§11.2, §11.3) — driver revises after reviewer or PR feedback.
- **Implementation** (§11.4) — driver implements a piece.
- **Fix-up** (§11.6) — driver addresses CI/review failures.

For Claude-driver, all sessions use `Native`. For Codex-driver, all sessions use `HaltWithQuestion`. The re-spawn protocol carries the relevant prior context for each session type.

### 7.9 Runaway detection — two bounds, every session

Every driver session is bounded by **both**:

1. **Settle timeout** (`settle.<phase>TimeoutSec`) — wall-clock cap on the whole session. On expiry: `session.kill()` (SIGTERM, 5s grace, SIGKILL). Transition to `NeedsHumanIntervention("<phase> settle timeout", <hint>)`. This catches subprocesses that produce no output at all (stuck on stdin, hung in a tool call).
2. **Per-turn cost cap** (`maxTurnCostUsd`, default \$2) — checked after every `cost.update`. On breach: `session.kill()`. Transition to `NeedsHumanIntervention("turn budget exceeded", <hint>)`. This catches subprocesses that produce output but run away in cost (tool-use loops).

Per-phase timeouts:
- `settle.specTimeoutSec` — spec consolidation (default 300s).
- `settle.designRevisionTimeoutSec` — design revision after review or PR feedback (default 600s).
- `settle.implementTimeoutSec` — initial piece implementation (default 1800s).
- `settle.fixupTimeoutSec` — fix-up sessions (default 900s).

A driver that produces neither a question nor a completion is bounded by (1). A driver that produces output but runs away cost-wise is bounded by (2). A driver that produces lots of output cheaply is bounded by neither and may continue — that's correct: it's making progress.

---

## 8. CI readiness policy

Two variants:

| Policy | Behaviour |
|---|---|
| `BranchProtectionThenObserved` (default) | Use branch-protection's required-check list if it exists. Otherwise wait `checkDiscoveryTimeoutSec` for at least one check, then require all observed checks green for `stableGreenPolls` consecutive polls. |
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

Required set = union of branch-protection required-checks and `requiredChecksOverlay`. The overlay never reduces the required set.

Discovery & timeout rules:

1. **No checks discovered at all** after `checkDiscoveryTimeoutSec`:
   - `BranchProtectionThenObserved` with empty protection set + zero observed checks → `NeedsHumanIntervention("no CI checks discovered", ResumeAfterHumanPush(p, prNumber))`.
   - `None` → straight to readiness.

2. **Any required check missing** (from branch-protection union overlay) after the discovery window:
   ```
   NeedsHumanIntervention(
     s"required check '${name}' never appeared (source: ${source})",
     ResumeAfterHumanPush(p, prNumber)
   )
   ```
   where `source` is `branch-protection` or `overlay`. Catches both (a) workflow file deleted but protection still references the check name, and (b) overlay specifying a typo'd or deprecated check name. Resume after the reviewer fixes the CI config (so the check appears) or fixes the protection/overlay (so the check is no longer required).

3. **Fewer than `minimumExpectedChecks` observed** after `checkDiscoveryTimeoutSec`:
   ```
   NeedsHumanIntervention(
     s"only ${observed} CI checks observed, expected at least ${minimumExpectedChecks}",
     ResumeAfterHumanPush(p, prNumber)
   )
   ```

4. Readiness: `stableGreenPolls` (default 2) consecutive green polls + base freshness (§9).

Branch-protection lookup is cached per feature for the Forge process lifetime.

---

## 9. BranchManager and base freshness

```scala
trait BranchManager:
  def preflight(command: ForgeCommand): IO[PreflightReport]
  def syncBase(base: BranchName): IO[BaseSnapshot]
  def createDesignBranch(feature: FeatureId): IO[BranchName]
  def createPieceBranch(feature: FeatureId, piece: PieceId): IO[(BranchName, Sha)]
  def baseFreshness(pr: PrNumber, expectedBaseSha: Sha): IO[BaseFreshness]
  def pushCurrentBranch(forceWithLease: Boolean = false): IO[Unit]
  def createPr(title: String, body: String, base: BranchName): IO[PrNumber]
  def updatePrBranch(pr: PrNumber): IO[Unit]                    // gh pr update-branch
  def tagSnapshot(name: String): IO[Unit]
  def pushTag(name: String): IO[Unit]                            // only if pushSnapshotTags
  def deleteRemoteTag(name: String): IO[Unit]                    // for snapshot retention
```

Rules:
- Before any branch creation: `git fetch` and fast-forward the configured base. Local divergence → `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)`.
- `createPieceBranch` returns `(branchName, baseSha)`. The FSM persists `baseSha` into `manifest.pieces[i].baseSha` **before transitioning out of the branch-creation step** (otherwise a crash leaves a started piece with `baseSha: null`, which the validator rejects).
- On CI readiness, `baseFreshness(pr, manifest.pieces[i].baseSha)`:
  - Branch protection requires up-to-date → trust protection.
  - Not required + `config.baseFreshness.autoUpdate: true` (default) → call `updatePrBranch(pr)`, re-enter `PieceAwaitingCi`.
  - `autoUpdate: false` → `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(p, prNumber))`.

`PRWatcher` (provider-agnostic at the type boundary):

```scala
trait PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot]
```

`gh pr view --json state,statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments,commits,mergedAt,mergeCommit` is polled every `pollIntervalMs` (default 30s). The `gh` JSON parse and merged interpretation (`state == "MERGED"`, confirmed by `mergedAt`) live entirely inside `forge-git`. A future GitLab adapter is a parallel parser feeding the same `PrSnapshot` ADT.

Polling is active only in `DesignAwaitingMerge | PieceAwaitingCi | PieceAwaitingReview | PieceAwaitingMerge`. `.changes` on the stream means the FSM only sees events when something actually changed.

---

## 10. ChangeCollector, staging, and review posting

### 10.1 ChangeCollector

Three classes:

```scala
enum ChangeClass:
  case Allow | Deny | Ask
```

Default decision rule:
1. Path matches `staging.denyPatterns` → `Deny`.
2. Path is outside the repo root → `Deny`.
3. Path is under `.git/` → `Deny`.
4. Path is ignored by `.gitignore` → `Deny` unless it's under `.forge/specs/...` (Forge's own outputs).
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

**Denial handling is phase-aware** (since PRs don't exist until after initial implementation settles):

- **Pre-PR denial** (initial implementation, §11.4 — before `createPr`): transition to `NeedsHumanIntervention("change collector denied <path> in initial implementation", ResolveLocalImplementationChanges(p, branch))`. The hint tells the user the changes are on `branch` locally and not yet pushed. Resume options:
  - `forge resume --run-fixup <feature>` — clean up local changes (or revert them), then re-spawn the driver from scratch.
  - `forge resume --commit-human-fix <feature>` — keep the user's manual fix, run ChangeCollector again (still subject to deny rules), commit, push, open PR.
- **Post-PR denial** (fix-up, §11.6 — PR already exists): transition to `NeedsHumanIntervention("change collector denied <path>", RunAnotherFixup(p, prNumber))`. Resume via `forge resume --run-fixup` or `--commit-human-fix`.

`Ask` → Q&A pane, default option `Deny`. `Allow` → staged. The stage plan is recorded in the action log and rendered into the PR body.

### 10.2 Reviewer posting (Forge owns the diff)

Flow:
1. Forge fetches PR diff with `gh pr diff <n>`.
2. Forge fetches changed-file metadata with `gh api repos/.../pulls/<n>/files`.
3. Prompt reviewer with: piece spec, acceptance criteria, design link, full diff, file metadata.
4. Validate review JSON against `~/.forge/schemas/code-review.json` (severity-tagged questions; blockers carry `path/side/line` or `path: null`).
5. **`verdict: request_changes` with empty blockers → adapter bug.** Log `review.invalid_verdict`. Retry once with corrective prompt. Second failure → `NeedsHumanIntervention("review adapter produced invalid verdict", RunAnotherFixup(p, prNumber))`.
6. Post inline comments (anchorText algorithm below) + a summary review.

### 10.3 `anchorText` matching

For each blocker with `path != null`:

```
attempt 1: POST /repos/.../pulls/<n>/comments
            with {path, side, line, commit_id: <PR head SHA>, body}
            using the line-based API.

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

API: **line-based** (`POST /pulls/{n}/comments` with `path/side/line/commit_id`). The classic position-in-diff-hunk API is not used. Pinned in Slice 0.

### 10.4 Driver-question routing

All driver-question events (whether `Native` or `HaltWithQuestion`, whether from spec, design revision, implementation, or fix-up sessions) route to the **Forge Q&A pane**. Questions are never posted back to GitHub PR threads. The action log records the originating phase so the audit trail is clear.

---

## 11. Lifecycle

Throughout: **driver** = the CLI selected by `Mode`. **reviewer** = the other CLI.

### 11.0 Preconditions (every state-changing command)

1. Acquire `.forge/state/.lock` (§13).
2. `BranchManager.preflight(command)` — command-aware (§15).
3. Manifest / `decomposition.md` reconcile check (§5.4).
4. State cache verified against log replay; rewritten if divergent.

### 11.1 Spec phase

1. `forge new "title"` with optional `--mode claude-driver|codex-driver` (defaults from config). FeatureId slugged per §5.2. BranchManager creates `<branchPrefix>/<feature>/design`.
2. Spawn driver via `driver.runStreamingSpec(~/.forge/prompts/specify.<driver>.md)` → `StreamingSession`.
3. Action log records `<driver>.user_message` and `<driver>.assistant_text` (≤200 chars each). Full transcript in the driver's own JSONL (cross-reference via `sessionId`).
4. User types `/done` (intercepted by Forge, not sent to driver). Forge sends a final message asking the driver to write `design.md`, `manifest.json` (with piece IDs `p1, p2, ...` and `mode` field; each piece has `baseSha: null`), one `pieces/<p>.md` per manifest entry, and to render `decomposition.md` from the manifest using `~/.forge/templates/decomposition.md.hbs` (with editable-region markers per §5.3).
5. Driver questions handled per §7.8 (Native or HaltWithQuestion depending on driver).
6. **Settle** = next `result` event after Forge's final message, bounded by `settle.specTimeoutSec` (default 300s) AND `maxTurnCostUsd` (§7.9). On either expiry: `session.kill()`, transition to `NeedsHumanIntervention("spec settle timeout" or "spec turn budget exceeded", AbortOrAbandon)`.
7. Post-check: every piece in manifest has a `pieces/<p>.md` file; `decomposition.md` renders identically from the manifest. On mismatch: one corrective message, retry settle (max 2 corrections, then `NeedsHumanIntervention`).
8. FSM → `DesignReviewing(sessionId, round = 1)`.

### 11.2 Design review

9. Reviewer reviews `design.md` via `reviewer.reviewDesign(...)`. Uses `Native` or `SchemaFallback` per the reviewer's declared `schemaMechanism`. Wrapped by `config.<reviewer>.reviewProcessRetries` (process-level retries only, per §7.7).
10. Output appended to `design.md` under `## Reviewer Review (round <n>)`.
11. `severity: blocking` questions → `DesignNeedsHumanInput(round, questions)` → Q&A pane → answers appended under `## Clarifications (round <n>)` → `DesignReviewing(round + 1)`. Non-blocking questions are recorded but don't gate.
12. `verdict: request_changes` → `driver.resumeStreamingSpec(sessionId)` → new `StreamingSession`. Forge sends a message asking the driver to revise `design.md` + manifest + affected `pieces/*.md` addressing the blockers. Driver questions handled per §7.8. Bounded by `settle.designRevisionTimeoutSec` (default 600s). Loop to step 9. Max `config.maxDesignReviewRounds` (default 3) → `NeedsHumanIntervention("design did not converge", ReopenDesign(None))`.
13. `verdict: approve` → commit design assets and design-phase audit snapshot. Open PR `[design] <title>` via `gh pr create --json url -q .url`; parse PR number. FSM → `DesignAwaitingMerge(prNumber)`.

### 11.3 Design PR gate

From `DesignAwaitingMerge`:
- `state == MERGED` + `mergedAt != null` → `DesignReady`.
- New human comment (id > recorded baseline) or `CHANGES_REQUESTED` → `DesignPrFeedback(prNumber, round + 1)`.
- PR closed without merge → `NeedsHumanIntervention("design PR closed without merge", ReopenDesign(Some(prNumber)))`.

From `DesignPrFeedback`:
1. Write feedback bundle to `.forge/specs/<feature>/audit/design-pr-feedback-round-<n>.md`.
2. `driver.resumeStreamingSpec(sessionId)`. Forge sends a message referencing the feedback file. Driver questions handled per §7.8. Bounded by `settle.designRevisionTimeoutSec`.
3. Update design assets (design.md, manifest.json, affected pieces/*.md, re-rendered decomposition.md).
4. **Snapshot tag:** `git tag <branchPrefix>/_snapshots/<feature>/design-r<n>` on current design branch HEAD before force-push.
   - **Default: local-only.** Tag not pushed; recovery is local-machine.
   - If `config.github.pushSnapshotTags: true`, push the tag and prune (delete `design-r<n-3>`, keeping last 3) via `deleteRemoteTag`.
5. **Force-push-with-lease** to the design branch. On refuse (someone else pushed concurrently) → `NeedsHumanIntervention("design branch updated externally", ReopenDesign(Some(prNumber)))`.
6. Return to `DesignAwaitingMerge`.

### 11.4 Implementation phase

For each piece `p` in manifest order:

1. `syncBase()`, then `createPieceBranch(feature, p.id)` returns `(branchName, baseSha)`. **Persist `baseSha` into `manifest.pieces[i].baseSha` before transitioning.**
2. Spawn driver via `driver.runHeadlessImplementation(...)` with `~/.forge/prompts/implement.<driver>.md`. Prompt: implement piece `<p.id>` to acceptance criteria; **do not commit — Forge will commit**.
3. Action log records every tool use. `Read`/`Edit`/`Write`: paths only. `Bash`: command summary (≤200 chars) + exit + duration. `<driver>.assistant_text`: summary only (≤200 chars + token count).
4. Driver questions handled per §7.8. Always route to Forge Q&A pane regardless of mechanism.
5. Settle bounded by **both** `settle.implementTimeoutSec` (default 1800s) and `maxTurnCostUsd` (§7.9).
6. On settle:
   - ChangeCollector (§10.1) classifies changes. **On `Deny` here (pre-PR), transition per §10.1 to `NeedsHumanIntervention(..., ResolveLocalImplementationChanges(p, branch))`.** Do not proceed to commit.
   - On clean classification: Forge commits with `feat(<feature>): <piece title>`. Respects repo `commit.gpgsign`.
   - DocSync rides along: if a prior piece merged since this branch was cut, update `[x]` and status badge in `decomposition.md` and the corresponding manifest entry.
   - Push the branch, then `createPr` (PR body rendered from `~/.forge/templates/pr-body.md.hbs`).
   - Record current highest comment id + review id as the feedback baseline (per piece).
7. FSM → `PieceAwaitingCi(p, prNumber)`.

### 11.5 CI & review polling

**`piece.attempts` semantics (recap from §5.1):** total number of fix-up rounds for the piece, regardless of cause. Both CI-failure and review-failure paths increment the same counter; `maxFixupRounds` gates the combined count.

From `PieceAwaitingCi`:
- CI readiness met (§8 + §9 base freshness) → trigger reviewer code review (§10.2) → `PieceAwaitingReview`.
- Any required check failed: **`piece.attempts += 1`** in the manifest. If `piece.attempts <= config.maxFixupRounds`: write `<p>.failures.md`, FSM → `PieceCiFailed(attempt = piece.attempts)` → fix-up (§11.6). Else → `NeedsHumanIntervention("piece <p> fix-up exhausted after CI failure", RunAnotherFixup(p, prNumber))`.

From `PieceAwaitingReview`:
- Reviewer `approve` posted, no unresolved human `CHANGES_REQUESTED` → `PieceAwaitingMerge`.
- Reviewer `request_changes` posted: **`piece.attempts += 1`**. If `<= maxFixupRounds`: → `PieceReviewFailed(attempt = piece.attempts)` → fix-up. Else → `NeedsHumanIntervention("piece <p> fix-up exhausted after reviewer changes", RunAnotherFixup(p, prNumber))`.
- Human comment (id > baseline) or `CHANGES_REQUESTED`: **`piece.attempts += 1`**. If `<= maxFixupRounds`: → `PieceReviewFailed(attempt = piece.attempts)` → fix-up. Else → `NeedsHumanIntervention(..., RunAnotherFixup(p, prNumber))`.

From `PieceAwaitingMerge`:
- `state == MERGED` + `mergedAt != null` → `Refining(p, now)`.
- New human comment or `CHANGES_REQUESTED` before merge: **`piece.attempts += 1`**. If `<= maxFixupRounds`: → `PieceReviewFailed(attempt = piece.attempts)` → fix-up. Else → `NeedsHumanIntervention(..., RunAnotherFixup(p, prNumber))`.
- PR closed without merge → `NeedsHumanIntervention("piece PR closed without merge", RunAnotherFixup(p, prNumber))`.

**v1 rule: piece PRs are merged by the human, not by Forge.** Forge gets the PR green, posts the reviewer's approving review, and waits. Once the human clicks Merge, the next poll sees the merge and the FSM advances. This mirrors the design PR gate.

### 11.6 Fix-up

Fresh driver session via `driver.runFixup(...)`, no resume. Prompt references `<p>.failures.md` (CI failure logs + unresolved review comments + previous failure reasons + PR URL/branch) and the unchanged piece spec. Driver questions handled per §7.8. Settle bounded by `settle.fixupTimeoutSec` (default 900s) and `maxTurnCostUsd`. Forge commits on settle via the same ChangeCollector path (§10.1 post-PR rules apply).

After fix-up commit/push, FSM → `PieceAwaitingCi(p, prNumber)`.

---

## 12. Budget enforcement

Three caps:

```json
{
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd": 8.00,
  "maxTurnCostUsd": 2.00
}
```

Checks:

1. **Before spawning any agent** (driver or reviewer): if `feature.cost + estimatedSpawnCost > maxFeatureCostUsd`, refuse → `NeedsHumanIntervention("feature budget would be exceeded", AbortOrAbandon)`. Same logic for piece cap. Estimated spawn cost is conservative (assume `maxTurnCostUsd`).
2. **After every `cost.update` event:** re-evaluate all three caps. Per-feature/per-piece breach → let current turn complete, do not spawn anything new, transition to `NeedsHumanIntervention("budget exceeded", <hint>)`.
3. **Per-turn breach (mid-turn):** if a single turn's accumulated cost exceeds `maxTurnCostUsd`, `session.kill()` (SIGTERM, 5s grace, SIGKILL). Transition to `NeedsHumanIntervention("turn budget exceeded", <hint>)`. Catches runaway tool-use loops.

Together with the settle timeout (§7.9), every driver session is bounded by two independent guards.

TUI status pane shows running totals against caps; budget exhaustion is never silent.

---

## 13. Locking

Files:
- `.forge/state/.lock` — OS lock target.
- `.forge/state/.lock.json` — metadata: `{ pid, hostname, startedAt, command, feature }`.

Startup behaviour:

1. Try `FileChannel.tryLock` on `.lock`.
2. **Lock acquired, no metadata** → write metadata, proceed.
3. **Lock acquired, stale metadata exists**:
   - TUI mode: prompt "Looks like a previous Forge run crashed. Clear the stale lock and continue? (Y/n)".
   - CLI mode: refuses unless `--yes` or `FORGE_AUTO_UNLOCK_STALE=1` env var. Suggests `forge unlock --force`.
   - On confirm: overwrite metadata, proceed.
4. **Lock NOT acquired**: read `.lock.json`, print holder info, refuse with exit code 2.

`forge unlock --force`:
- Live OS lock by another process → refuses, prints holder metadata.
- Stale metadata only (no OS lock) → removes metadata file, succeeds.

---

## 14. Refinery and `PlanningUpdate`

After each `PieceMerged`, Forge enters `Refining(p, startedAt)` and calls the reviewer with the refine schema.

### 14.1 UI surfacing

- TUI: status pane shows `Refining: checking design against piece <p.id> (<elapsed>s)`.
- CLI mode: prints `Refining piece <p.id>...` and an elapsed-time tick every 10s.

### 14.2 Failure path (advisory)

`reviewer.refine(...)` wrapped by `config.<reviewer>.refineProcessRetries` (process-level failures only — network timeouts, sandbox launch errors, CLI subprocess crash). If those retries exhaust, OR if the reviewer returns an adapter error from exhausted SchemaFallback (§7.7), the refinery result is unavailable:

- Log `harness.refinery_failed` with the failure category.
- FSM → `PieceMerged`. Refinery is advisory; the piece is already merged with required CI green, that's the load-bearing signal. Don't strand the feature on advisory tooling.

### 14.3 Verdict handling

Refine schema (`~/.forge/schemas/refine.json`) — outcomes:
- `no_change` → advance to next piece.
- `update_plan` → build a `ManifestPatch` from the refine output, validate against the current manifest, FSM → `PlanningUpdate(reason, patch)`. Patch is *also* written to `.forge/specs/<feature>/audit/refine-after-<p>.json` for human review.
- `reopen_design` → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(None))`.

From `PlanningUpdate(reason, patch)` (patch is **inline** in FSM state, not a file reference):
- User chooses apply / defer / reopen / ignore via Q&A pane.
- **Apply:** mutate manifest atomically (write to temp manifest, validate, then `os.move`). Regenerate `decomposition.md`. Write/edit affected `pieces/*.md`. Changes ride on the next piece PR; for the final piece, open a `chore(<feature>): apply planning update` PR. Already-merged piece IDs are immutable — the validator rejects.
- **Defer:** snooze until after the next piece; FSM remembers via a deferred-patches list.
- **Reopen:** transition to `NeedsHumanIntervention("planning update deferred to design", ReopenDesign(None))`.
- **Ignore:** record `planning.ignored` in the audit; continue.

---

## 15. Command-aware preflight

| Command | Clean worktree required? | Other checks |
|---|---|---|
| `forge new` | Yes | Base fast-forwardable; FeatureId slug computed and shown |
| `forge spec` | Yes | On the design branch |
| `forge run` | Yes | Manifest reconcile passes |
| `forge resume --after-human-push` | Yes | On the piece branch; PR head == local HEAD |
| `forge resume --commit-human-fix` | No | **Current branch == derived piece branch** (`<branchPrefix>/<feature>/<active piece id>`) |
| `forge resume --run-fixup` | Yes | On the piece branch |
| `forge reconcile` | No | — |
| `forge status` / `replay` / `rebuild-state` | No | Read-only |
| `forge unlock --force` | No | Lock-specific |
| `forge abandon` | No | Transitions to `Abandoned` |

For `--commit-human-fix`: Forge computes the expected piece branch from `manifest.json` and compares to `git branch --show-current`. Mismatch → refuse with: "You are on `<X>`, the active piece branch is `<Y>`. Switch branches and retry, or use `--after-human-push` if your fix is already pushed."

The same command works for both pre-PR (`ResolveLocalImplementationChanges` hint, §10.1) and post-PR (`RunAnotherFixup` hint) recovery, since both leave the user on the piece branch with local changes.

`--force` available for all commands; usage logged as `harness.preflight_bypassed`.

---

## 16. Slice 0 — 2×2 validation with fallback success thresholds

Validate before any Scala is written. The matrix is 2 CLIs × 2 roles. Each combination's required capabilities must be `Native` or covered by the bounded fallback protocols (§7).

**Driver role (both CLIs):**

1. Long-lived streaming subprocess (Claude `-p --input-format stream-json` / Codex equivalent).
2. Isolation flag suppressing user-level config but preserving repo-level (Claude: confirm or replace the `--bare` hypothesis. Codex: identify sandbox/profile equivalent).
3. **Session resume** via `resumeStreamingSpec(sessionId)`: spawn a fresh subprocess attached to the prior session's conversation history. Validate the resumed session sees prior turns. Both CLIs.
4. System-prompt injection from a file alongside the above.
5. **Question mechanism:** at least one of:
   - **`Native`** — mid-turn suspension primitive (Claude's `AskUserQuestion`), confirmed working.
   - **`HaltWithQuestion`** — the protocol in §7.3 produces a parseable structured halt on ≥19/20 representative ambiguous-input cases, with reliable re-spawn.

**Reviewer role (both CLIs):**

6. **Schema-constrained output:** at least one of:
   - **`Native`** — CLI accepts and enforces an output schema (Codex `--output-schema`), confirmed valid output on ≥19/20 inputs across the three schemas (`design-review`, `code-review`, `refine`).
   - **`SchemaFallback`** — the protocol in §7.5 produces schema-valid output (after at most 1 retry) on **≥19/20 (95%) representative inputs** per schema. Measured **per method**, not aggregate — a connector might pass `reviewPr` and fail `refine`.
7. Read-only sandbox mode.

**Other:**

8. `gh pr view --json state,mergedAt,mergeCommit,...` returns the expected values on merged PRs.
9. `gh api repos/.../branches/<base>/protection/required_status_checks` on protected, unprotected, inaccessible repos.
10. Line-based comment API: `gh api --method POST /repos/{owner}/{repo}/pulls/{n}/comments` with `path/side/line/commit_id` body.
11. Rate-limit baseline: measure API calls per piece for a representative feature with one fix-up cycle.

### 16.1 Per-cell outcome and design-scope decisions

| Cell | Capabilities required | Threshold |
|---|---|---|
| Claude driver | streaming, isolation, resume, system-prompt, questions (`Native` `AskUserQuestion`) | All work |
| Codex driver | streaming, isolation, resume, system-prompt, questions (`HaltWithQuestion`) | All work; halt protocol ≥19/20 |
| Claude reviewer | schema output (`SchemaFallback`), read-only sandbox | Fallback success ≥19/20 **per method** |
| Codex reviewer | schema output (`Native` `--output-schema`), read-only sandbox | All work |

If a cell **doesn't qualify**, the response is a design-scope decision, **not silent triage:**

1. **Wait** for a future CLI version that closes the gap.
2. **Narrow scope explicitly** — update §1 Goal 6 to e.g. *"v1 ships `claude-driver` only; `codex-driver` is v2 pending Codex halt-with-question reliability"*. The doc change is the contract.
3. **Treat as v1-blocking** — Forge doesn't ship until the gap closes.

Slice 0 does not silently pick a supported mode and proceed. Mode support is a spec property, not a runtime fallback.

Output: `slice-0-report.md` with pinned CLI versions, per-cell capability findings, fallback success-rate measurements (numerator/denominator per method), and the scope decision.

---

## 17. Build order (de-risked)

### Slice 0 — Validate CLI assumptions (days)

Per §16 above. Output is `slice-0-report.md` and any required scope changes to this doc.

### Slice 1 — Agent connectors (week 1)

Build `forge-agents` standalone with a CLI demo and integration tests.

- `StreamingSession` and `Connector` traits per §7.1.
- `ClaudeConnector` and `CodexConnector` implementing them. Each declares its `questionMechanism` and `schemaMechanism`.
- `SchemaFallback` protocol (§7.5) implemented once in `forge-agents`, reused by either connector.
- `HaltWithQuestion` parsing + re-spawn loop (§7.3) implemented once in the orchestrator, called by either driver session.
- Integration tests:
  - Claude driver runs headless on "create a hello world Scala file in /tmp/forge-test"; asserts file exists, action log emitted >0 events, session JSONL exists.
  - Codex reviewer reviews a known-good and known-bad markdown file; asserts schema-conformant verdict in both directions.
  - Cross-mode: Codex driver and Claude reviewer exercised on the same fixtures.
  - SchemaFallback exercised with deliberately schema-violating first responses; asserts retry behaviour and 2-attempt cap.
  - HaltWithQuestion exercised with a contrived ambiguous prompt; asserts halt parsing and re-spawn with prior context.
  - `resumeStreamingSpec` exercised: spawn a session, send a couple of turns, close, then resume; assert resumed session has the prior turns in its visible context.

**Why first:** if any of the connector primitives don't behave as Slice 0 expects, everything downstream is dead.

### Slice 2 — FSM, ActionLog, StateCache (week 2)

Plain Scala, no external agents, no git.

- `forge-core`: FSM as `(FsmState, FsmEvent) => (FsmState, List[ActionLogEntry])`.
- `StateCache` using atomic file writes (`os.write.over` to temp then `os.move`).
- `ActionLog` appending to `.forge/log/<feature>.jsonl`.
- `forge rebuild-state <feature>` replays log → state cache.
- Property tests:
  - Any sequence of events produces a valid state.
  - Replaying the action log reproduces the final state.
  - `NeedsHumanIntervention` always carries a `ResumeHint` and `forge resume` produces a legal next state.
  - No success path reaches implementation before design is merged.
  - No piece can be marked merged without PR-merge evidence.
  - Human feedback before merge always returns to design revision or fix-up.
  - CI cannot become green before check discovery finishes (unless `CiPolicy.None`).
  - Already-merged piece IDs cannot be removed by planning updates.
  - Reorder invariant (§5.5) holds across arbitrary patch sequences.
  - Manifest invariant: any piece with `status != "pending"` has non-null `baseSha`.
  - `piece.attempts` increments on every fix-up entry, regardless of source (CI, reviewer, human).

### Slice 3 — BranchManager + PRWatcher + ProcessLock (week 3)

- Shell out to `git` and `gh`. No magic.
- BranchManager: create design branch, create piece branch (returning `(branchName, baseSha)`), push, create PR (parsing number from `gh pr create --json url`), preflight checks, snapshot tags.
- PRWatcher: 30s poller, fs2 stream, returns `PrSnapshot`.
- ProcessLock: `FileChannel.tryLock` on `.forge/state/.lock` + `.lock.json` metadata.
- Integration test against a sacrificial GitHub repo: create branch, push, open PR, observe watcher emit right transitions through merge.

### Slice 4 — Headless feature loop with line-mode REPL (week 4)

Wire Slices 1–3 together. **No TUI yet** — drive from a CLI:

```
forge new "feature title" [--mode claude-driver|codex-driver] [--id <slug>]
forge spec <feature>            ← line-mode REPL: stdin lines → driver user messages,
                                  stdout pretty-prints assistant text + tool uses,
                                  /done and driver questions intercepted here
forge run <feature>             ← headless from DesignReady through to FeatureDone
forge status [<feature>]
forge resume <feature> --after-human-push | --commit-human-fix | --run-fixup
forge reconcile <feature>
forge abandon <feature>
forge rebuild-state <feature>
forge unlock --force
```

The line-mode REPL is the reusable intercept layer that Slice 5's TUI also drives. Building it first means the TUI in Slice 5 is a *view*, not a coupling.

By end of week 4, drive a real small feature through Forge from the command line. **First version that's actually useful.**

### Slice 5 — TUI (week 5)

Build the termflow UI as a richer view over Slice 4's hooks:

- Action log tail pane.
- Streaming driver pane (replacing the line-mode REPL).
- Q&A pane (one UI for both `Native` and `HaltWithQuestion` questions).
- Status pane: feature, current piece, FSM state, tokens / cost / budget remaining, Refinery elapsed.
- Keyboard shortcuts: pause/resume/cancel.

TUI last because it's the most subjective. Iterate UX against a working system.

---

## 18. Configuration

`.forge/config.json` per repo:

```json
{
  "mode": "claude-driver",
  "baseBranch": "main",
  "branchPrefix": "forge",
  "pollIntervalMs": 30000,
  "maxFixupRounds": 3,
  "maxDesignReviewRounds": 3,
  "maxHaltRespawns": 5,
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
    "reviewProcessRetries": 2,
    "refineProcessRetries": 2
  },
  "codex": {
    "sandbox": "read-only",
    "driverSandbox": "workspace-write",
    "reviewProcessRetries": 2,
    "refineProcessRetries": 2
  },
  "schemaFallback": {
    "logValidatorErrors": true
  },
  "settle": {
    "specTimeoutSec": 300,
    "designRevisionTimeoutSec": 600,
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

Notes:
- The SchemaFallback 2-attempt cap is a **protocol invariant** (not exposed in config). Only `logValidatorErrors` is tunable.
- Per-CLI `reviewProcessRetries` and `refineProcessRetries` cover **process-level failures only** (network, sandbox launch, subprocess crash). They do *not* extend the SchemaFallback cap; see §7.7.
- Per-CLI keys are `claude` and `codex`; each carries its role-specific knobs (e.g., Codex needs a permissive sandbox as driver, strict as reviewer).
- Global config at `~/.config/forge/config.json` for per-user overrides.
- Templates and prompts under `~/.forge/` (install); per-repo overrides under `.forge/overrides/`.

---

## 19. Action log schema

Newline-delimited JSON per feature, append-only. The local log at `.forge/log/<feature>.jsonl` is canonical.

```json
{
  "seq": 142,
  "ts": "2026-05-25T15:42:18.341Z",
  "feature": "stripe-webhook",
  "piece": "p2",
  "actor": "claude",
  "role": "driver",
  "kind": "fsm.transition",
  "payload": {
    "from": "PieceImplementing",
    "to": "PieceAwaitingCi",
    "prNumber": 4291,
    "branch": "forge/stripe-webhook/p2"
  }
}
```

`seq` is monotonic per feature, used for replay-deterministic state reconstruction.

`kind` values (initial set):

- `fsm.transition` — every state change.
- `<actor>.spawn` — `{ argv, sessionId, mode: "streaming" | "headless", questionMechanism, schemaMechanism }`.
- `<actor>.resume` — `{ sessionId }` for `resumeStreamingSpec`.
- `<actor>.user_message` — summary only (≤200 chars).
- `<actor>.assistant_text` — summary only (≤200 chars + token count).
- `<actor>.tool_use` — `{ tool, target }`. For `Bash`: `{ tool: "Bash", commandSummary, exitCode, durationMs }`.
- `<actor>.ask_user_question` — full question + answer. Carries `questionMechanism: "Native" | "HaltWithQuestion"` and `phase`.
- `<actor>.halt_respawn` — `{ respawnCount }` when `HaltWithQuestion` triggers a re-spawn.
- `<actor>.schema_fallback_ok` — `{ method, attempt }`.
- `<actor>.schema_fallback_error` — `{ method, attempt, validatorError }`.
- `<actor>.process_retry` — `{ method, attempt, reason }` for `reviewProcessRetries` / `refineProcessRetries`.
- `gh.poll` — sampled (every 5th poll); transitions always logged in full.
- `gh.action` — `{ verb, args }` for mutating `gh` calls.
- `review.anchor_demoted` — when an inline comment falls back to summary.
- `review.invalid_verdict` — when reviewer returns `request_changes` with empty blockers.
- `user.command` — TUI/CLI commands the user invoked.
- `harness.error` — exceptions with stack trace.
- `harness.preflight_bypassed` — `--force` usage with reason.
- `harness.refinery_failed` — refinery exec errors (advisory).
- `harness.rate_limited` — GitHub 403/429 with `Retry-After`.
- `cost.update` — `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd, pieceTotalUsd, turnTotalUsd }`.

TUI has a "tail action log" pane that pretty-prints the last N entries with colour. `forge replay <feature>` re-prints the log as a narrative.

---

## 20. v2 candidates

Decide after lived experience. None are mutually exclusive.

| Candidate | Pick when |
|---|---|
| **Auto-merge on green + reviewer approval** | v1 friction is "clicking Merge on PRs that are clearly fine". |
| **Stacked PRs onto a per-feature integration branch with composite CI** | v1 friction is "each piece PR rerunning full CI". |
| **Parallel features across checkout slots** | Serial throughput becomes the bottleneck. |
| **GitLab adapter** | A non-GitHub repo. Parallel parser into the existing `PrSnapshot` ADT. |
| **Third-party agents / arbitrary role pairings** | A real third agent becomes interesting. Generalise `Mode` into pluggable role traits at that point. The `Connector` interface is already most of the work. |
| **Webhook mode** | Sub-second reactivity demanded. |
| **Langfuse / structured tracing** | Action log alone stops being enough for tuning. |
| **Process-tuning mode** | Feed historical action logs to an LLM to suggest prompt/FSM tweaks. |

---

## 21. Decision summary

| Question | Decision |
|---|---|
| Adopt or build? | Build. Scala harness. |
| Drive Claude/Codex how? | Subprocess + stream-json / `exec --json`, not in-process SDKs. |
| TUI library? | termflow (llm4s/termflow), Elm-architecture, Scala 3. |
| Effect system? | cats-effect 3 + fs2. |
| Branch model v1? | One branch per piece off main; design has its own branch+PR merged first. |
| Merge gates v1? | Human-merged at both gates. |
| GitHub integration? | Polling `gh` every 30s only when FSM cares. Line-based comment API. |
| Required vs optional checks? | Branch-protection required + overlay; any missing required check after discovery → `NeedsHumanIntervention`. |
| Failure recovery? | `NeedsHumanIntervention(reason, resumeHint)` is non-terminal; `forge resume` re-enters. `Abandoned` only via `forge abandon`. Four typed resume hints incl. pre-PR. |
| Work breakdown SoT? | `manifest.json` (machine); `decomposition.md` rendered with editable-region markers; `forge reconcile` imports edits. `baseSha` nullable until branch creation. |
| Autonomy between pieces? | Headless implementation. Forge writes code, opens PR, gets reviewer approval; human clicks Merge. |
| Who commits? | Forge, not the driver. |
| Refinery step? | In v1, advisory; reviewer-only check, no local test re-run. Failure → `PieceMerged`. |
| Observability? | Per-feature action log (`.forge/log/<feature>.jsonl`, local canonical, monotonic `seq`). |
| Audit committed? | Per `auditMode` (`summary` default). |
| Concurrency? | Single Forge process per repo via `flock` on `.forge/state/.lock` + metadata. |
| Pre-flight? | Hard precondition: clean working copy (command-aware exceptions for resume/reconcile/status). |
| Cost cap? | Per-feature, per-piece, per-turn. Per-turn enforced mid-turn via SIGTERM/SIGKILL. |
| Settle bound? | Every driver session bounded by both settle timeout (per-phase) and per-turn cost cap. Design revision has its own `designRevisionTimeoutSec`. |
| Driver questions? | Required capability via `Native` (mid-turn suspension) or `HaltWithQuestion` (structured halt + re-spawn). Silent proceed never permitted. |
| Reviewer schema? | `Native` (CLI-enforced) or `SchemaFallback` (prompt+validate+1 retry, hard 2-attempt cap). |
| Process retries vs schema attempts? | Distinct layers: `<reviewer>.reviewProcessRetries` / `refineProcessRetries` wrap the whole call (process-level); SchemaFallback 2-attempt cap is internal to the connector. Adapter errors are not retried at the outer level. |
| Capability emulation? | Only `SchemaFallback`. Nothing else. |
| Mode pluggability? | Two modes (`ClaudeDriver`, `CodexDriver`). No third combination, no arbitrary agents. |
| Slice 0 fallback bar? | ≥19/20 per method, per schema, for `SchemaFallback`; halt protocol ≥19/20 for `HaltWithQuestion`. |
| Slice 0 capability gap? | Design-scope decision (wait / narrow scope / v1-blocking), not silent triage. |
| ChangeCollector denial? | Phase-aware: pre-PR → `ResolveLocalImplementationChanges(p, branch)`; post-PR → `RunAnotherFixup(p, prNumber)`. |
| `piece.attempts` counter? | One counter for all fix-up rounds regardless of source (CI failure, reviewer changes, human changes, late human feedback). |
| Session resume contract? | `Connector.resumeStreamingSpec(sessionId)` returns a fresh `StreamingSession` attached to prior conversation. Validated in Slice 0. |
| First thing to build? | Slice 0 (validate CLI assumptions). Then Slice 1 (connectors). |
| LLM4S role in v1? | None. Direct CLI integration. Revisit post-v1. |

---

## 22. What this design explicitly rejects

- **Real-time webhooks.** 15–60s polling is fine and removes a deployment dependency.
- **Worktrees.** Devcontainer-incompatible.
- **LLM4S in the orchestrator.** Forge talks to Claude/Codex via their CLIs.
- **A "manager LLM" choosing which agent does what.** Deterministic Scala.
- **Per-session Langfuse traces.** Action log replaces this for v1.
- **Direct DocSync pushes to main.** DocSync rides on the next piece PR.
- **Committing the state cache.** Log is canonical; state is rebuildable.
- **Terminal `Failed` state.** All non-success lands in resumable `NeedsHumanIntervention`.
- **Silent proceed past uncertainty.** Driver questions always reach the human.
- **Capability emulation beyond `SchemaFallback`.** One named exception; everything else is `Native` or unsupported.
- **Fake config knobs.** SchemaFallback's 2-attempt cap is not exposed; process retries are clearly named for their layer.
- **Split per-source attempt counters.** One `piece.attempts` covers all fix-up causes.
- **Pre-PR PR-number hints.** ChangeCollector denial pre-PR uses `ResolveLocalImplementationChanges`, not `RunAnotherFixup`.

Stacked PRs, auto-merge, role pluggability for arbitrary agents, GitLab — all explicit v2 candidates.

---

## 23. Document conventions

- This spec is **standalone**. No section references prior version files. The 0.1 → 0.7 documents and their commentaries remain as a record of design evolution but are not required reading.
- Subsequent versions will also be standalone. Delta-only docs are forbidden from 0.7 onward.
- Commentaries continue to be delta-only — they're inherently about what *changed* between versions.
