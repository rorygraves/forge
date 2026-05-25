# Forge — design doc v1.1

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.1 — post-Slice-0 spec corrections  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → 0.9 → 1.0) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document.

The 0.x series captured design evolution; 1.0 was the release candidate; 1.1 folds in the three corrections Slice 0 surfaced (§16): Claude has native schema enforcement (SchemaFallback is no longer required in v1 — parked as a v2 option, §20); both CLIs preserve session id on resume by default; and three small Codex-adapter notes (system-prompt injection, sticky session-scoped settings, USD cost computed from a per-model price table). Future revisions continue to be fully standalone per §23.

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
6. **Both `claude-driver` and `codex-driver` modes are v1 first-class.** Slice 0 confirmed the pinned CLI versions satisfy the required capabilities for both: native questions on Claude / `HaltWithQuestion` convention on Codex (§7.3), and native schema enforcement on both reviewers (§7.4). A future capability gap that no in-protocol convention can close is a design-scope decision (wait / narrow scope / treat as v1-blocking), not silent triage.
7. **Action log per feature.** Local canonical runtime log + sanitized committed audit snapshots.
8. **De-risked build order.** Connectors built and validated first; orchestrator on top.
9. **No silent proceed past uncertainty.** Driver question events always reach the human. Runaway turns are aborted by *both* settle timeout and per-turn cost cap.
10. **No dead-end states.** Non-success that isn't explicit abandonment lands in resumable `NeedsHumanIntervention`. `Abandoned` is reachable only via `forge abandon`.
11. **No implicit state mutations.** Every manifest annotation tied to an FSM transition is named explicitly in the lifecycle. Every session-id invariant is stated once and referenced where needed. Missing required state never produces a NullPointerException — it produces a `NeedsHumanIntervention` with a typed `ResumeHint`.

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
- Capability emulation of any kind. Both v1 driver capabilities (questions) and v1 reviewer capabilities (schema-constrained output) are required to be present natively or via an in-protocol convention (`HaltWithQuestion` for questions). Slice 0 confirmed both pinned CLIs have native schema enforcement, so no emulation layer is needed. A `SchemaFallback`-style emulation is parked as a v2 option (§20) in case a future CLI lacks native schema.
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
| Failure leaves feature in limbo | `NeedsHumanIntervention(reason, resumeHint)` is non-terminal; six typed resume paths |
| Plan drifts from reality | Refinery proposes manifest patches; `PlanningUpdate` is an explicit FSM transition |
| Runaway agent cost | Per-feature, per-piece, and per-turn cost ceilings; settle timeouts; killable handles for every driver session |
| `gh` rate limits | Cache branch protection (epoch-scoped) and diffs; back off on 403/429 |
| Locked into one agent vendor | Two first-class modes: `claude-driver` and `codex-driver` |
| Manifest annotations forgotten on state transitions | Every transition that annotates the manifest names the mutation explicitly in §11 |
| Missing required session ids producing NPEs | `requireSessionId(...)` precondition; missing → `NeedsHumanIntervention` with `ReopenDesign` hint |

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
│           ├── ChangeCollector (staging policy)                     │
│           └── SessionMonitor  (settle timeout + cost-cap killer)   │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────────────┐
│  Agent connectors  (Mode dispatches driver / reviewer)             │
│                                                                    │
│   ┌─────────────────────┐         ┌─────────────────────┐          │
│   │ ClaudeConnector     │         │ CodexConnector      │          │
│   │  - streaming        │         │  - exec --json      │          │
│   │  - headless (-p)    │         │  - native schema    │          │
│   │  - stream-json I/O  │         │     (--output-      │          │
│   │  - tool intercept   │         │      schema)        │          │
│   │  - AskUserQuestion  │         │  - sandbox modes    │          │
│   │  - native schema    │         │  - halt-with-q.     │          │
│   │     (--json-schema) │         │  - prepends         │          │
│   │                     │         │     system prompt   │          │
│   │                     │         │  - cost via price   │          │
│   │                     │         │     table           │          │
│   └─────────────────────┘         └─────────────────────┘          │
│                                                                    │
│  All sessions implement AgentSession (kill/close);                 │
│  StreamingSession adds send.                                       │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 Module layout (sbt)

```
forge/
  build.sbt
  modules/
    forge-core/          ← FSM, Feature, ActionLog, StateCache, domain model, PrSnapshot ADT
    forge-agents/        ← Connector, AgentSession, StreamingSession, Claude/Codex, stream-json
    forge-git/           ← BranchManager, PRWatcher (gh CLI, returns PrSnapshot)
    forge-specs/         ← SpecStore, DocSync, Manifest, ChangeCollector
    forge-tui/           ← termflow app, panes, key bindings
    forge-app/           ← main entry, wiring, config, ProcessLock, SessionMonitor
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
| `.forge/specs/<feature>/audit/*.md` | Sanitized milestone summaries (design review, PR feedback, refinery, phase-specific Q&A answers) | Yes |
| `.forge/specs/<feature>/audit/*.jsonl` | Optional sanitized action snapshots | Per `auditMode` |
| `.forge/log/<feature>.jsonl` | **Local canonical runtime log** | No (gitignored) |
| `.forge/state/<feature>.json` | Local rebuildable state cache (includes `designSessionId`, `currentPieceSessionId` projections, `branchProtectionCacheEpoch`) | No (gitignored) |
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
- **`baseSha` is nullable.** It is `null` for `status: "pending"` pieces. Set by `BranchManager.createPieceBranch` and persisted before the FSM transitions out of branch creation (§11.4 step 1). Validator rule: a piece with `status != "pending"` must have a non-null `baseSha`.
- **`branch` is not stored.** Derive: `s"${branchPrefix}/${featureId}/${pieceId}"`. Design branch: `s"${branchPrefix}/${featureId}/design"`. Snapshot tags: `s"${branchPrefix}/_snapshots/${featureId}/design-r${n}"`.
- Piece IDs (`p1`, `p2`, …) are stable once created.
- Already-merged pieces are immutable except for annotations recorded at the moment of merge detection (§11.5).
- `attempts` is **the total number of fix-up rounds for the piece, regardless of cause**. One counter for all causes. See §11.5.
- `status` transitions: `"pending"` → (branch created) → `"in_progress"` → (merge detected) → `"merged"`. The `"in_progress"` value is persisted as soon as `baseSha` is set. `"merged"` is persisted with `prNumber`, `mergeCommit`, `mergedAt` in the same atomic write (§11.5).

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
opaque type FeatureId  = String  // slugged per §5.2
opaque type PieceId    = String
opaque type PrNumber   = Int
opaque type BranchName = String
opaque type Sha        = String

enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fix-up. Codex: design/code/refinery review.
  case CodexDriver    // mirror.

enum QuestionMechanism:
  case Native            // CLI mid-turn suspension primitive (Claude's AskUserQuestion)
  case HaltWithQuestion  // driver halts with structured output; Forge re-spawns

enum SchemaMechanism:
  case Native            // CLI-enforced schema. v1 connectors all use this:
                          //   Claude  via --json-schema    (Slice 0 §2 confirmed)
                          //   Codex   via --output-schema  (Slice 0 §2 confirmed)
  case SchemaFallback    // bounded prompt+validate+1 retry. Defined but dormant in v1
                          //   (no connector returns it). Reserved for a future CLI
                          //   that lacks native schema enforcement; see §20.

case class Feature(
  id: FeatureId,
  manifest: Manifest,                          // loaded from manifest.json
  state: FsmState,
  cost: CostTotals,                            // projection of cost.update events

  // Session id projections (rebuilt from <actor>.spawn / <actor>.resume action-log events).
  designSessionId: Option[String],             // populated on InteractiveSpec spawn / design-revision resume.
                                                // Cleared on entering DesignReady.
  currentPieceSessionId: Option[String],       // populated on PieceImplementing or PieceFixingUp spawn.
                                                // Retained through PieceAwaitingCi, PieceAwaitingReview,
                                                //   PieceCiFailed, PieceReviewFailed, PieceAwaitingMerge, Refining.
                                                // Cleared at the moment of advancing past the piece
                                                //   (to next PieceImplementing, FeatureDone, PlanningUpdate,
                                                //   or NeedsHumanIntervention).

  branchProtectionCacheEpoch: Long             // bumped on every forge resume; §8.1
)

enum FsmState:
  // Spec phase
  case Drafting
  case InteractiveSpec                         // sessionId in feature.designSessionId
  case DesignReviewing(round: Int)             // sessionId in feature.designSessionId
  case DesignNeedsHumanInput(round: Int, questions: Vector[Question])
  case DesignAwaitingMerge(prNumber: PrNumber)
  case DesignPrFeedback(prNumber: PrNumber, round: Int)
  case DesignReady                             // feature.designSessionId cleared on entry

  // Implementation phase
  case PieceImplementing(p: PieceId)           // sessionId in feature.currentPieceSessionId
  case PieceAwaitingCi(p: PieceId, prNumber: PrNumber)
  case PieceAwaitingReview(p: PieceId, prNumber: PrNumber)
  case PieceCiFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceReviewFailed(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceFixingUp(p: PieceId, prNumber: PrNumber, attempt: Int)
  case PieceAwaitingMerge(p: PieceId, prNumber: PrNumber)
  case Refining(p: PieceId, prNumber: PrNumber, startedAt: Instant)
  case PlanningUpdate(reason: String, patch: ManifestPatch)

  // Recovery / terminal
  case NeedsHumanIntervention(reason: String, resumeHint: ResumeHint)
  case FeatureDone
  case Abandoned(reason: String)

// There is no PieceMerged state. The "piece merged" event flow:
//   PieceAwaitingMerge -> [atomic manifest mutation: status="merged", prNumber, mergeCommit, mergedAt]
//                      -> Refining(p, prNumber, now)
//                      -> next PieceImplementing | FeatureDone | PlanningUpdate | NeedsHumanIntervention
// The "piece merged" milestone is recorded by audit.piece_merged in the action log.

enum ResumeHint:
  case ResumeAfterHumanPush(p: PieceId, prNumber: PrNumber)
  case CommitAndPushHumanFix(p: PieceId, prNumber: PrNumber)
  case RunAnotherFixup(p: PieceId, prNumber: PrNumber)
  case ResolveLocalImplementationChanges(p: PieceId, branch: BranchName)   // pre-PR
  case ReopenDesign(prNumber: Option[PrNumber])                            // also for missing designSessionId
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

### 6.1 Session-id invariants (referenced from §11)

**`feature.designSessionId`** is `Some(sessionId)` from the first `InteractiveSpec` spawn through every design-phase state (`DesignReviewing`, `DesignNeedsHumanInput`, `DesignAwaitingMerge`, `DesignPrFeedback`). Cleared on entering `DesignReady`.

**`feature.currentPieceSessionId`** is `Some(sessionId)` from `PieceImplementing` (or `PieceFixingUp`) spawn through every subsequent state for that same piece — `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceCiFailed`, `PieceReviewFailed`, `PieceFixingUp`, `PieceAwaitingMerge`, `Refining`. Cleared at the moment of advancing past the piece (next `PieceImplementing`, `FeatureDone`, `PlanningUpdate`, or `NeedsHumanIntervention`).

Both fields are projections of `<actor>.spawn` and `<actor>.resume` events from the action log. `forge rebuild-state` reconstructs them from log replay.

**Resume event semantics.** The `<actor>.resume` event carries `{ oldSessionId, newSessionId }`. With the pinned CLIs (Slice 0 §2), **both Claude `--resume <id>` and `codex exec resume <id>` preserve the original id**, so `newSessionId == oldSessionId` in normal operation. The two-field shape is retained so that:

- A future CLI that mints a new id on resume (or a Claude invocation with `--fork-session`, which is unused in v1) drops in without an event-schema change.
- Replay logic projects `newSessionId` into `feature.designSessionId` / `feature.currentPieceSessionId` regardless of whether the value changed.

No orchestrator behaviour depends on the id changing.

### 6.2 `requireSessionId` helper

```scala
def requireSessionId[A](
  sessionId: Option[String],
  reason: String,
  hint: ResumeHint
): Either[FsmTransition, String] = sessionId match
  case Some(id) => Right(id)
  case None     => Left(FsmTransition(NeedsHumanIntervention(reason, hint)))
```

Used wherever the spec or orchestrator code would otherwise call `.get` on a required session id. Missing session id → `NeedsHumanIntervention` with the appropriate `ResumeHint`. No `.get` calls in production code.

---

## 7. Agent connectors, Mode, and the `HaltWithQuestion` protocol

v1 supports two modes:

```scala
enum Mode:
  case ClaudeDriver   // Claude: spec/implementation/fix-up. Codex: design/code/refinery review.
  case CodexDriver    // mirror.
```

Mode is set at feature creation (`forge new --mode ...` or `config.mode`) and persisted in the manifest. Mid-feature mode switching is unsupported.

### 7.1 `AgentSession`, `StreamingSession`, and `Connector` traits

Every driver subprocess (streaming or headless) is wrapped in an `AgentSession`. Streaming sessions extend it with `send`.

```scala
trait AgentSession:
  def sessionId: String                              // captured from CLI's init event
  def events: Stream[IO, AgentEvent]                 // stdout → events
  def close(): IO[Unit]                              // graceful shutdown after settle
  def kill(): IO[Unit]                               // SIGTERM, 5s grace, SIGKILL

trait StreamingSession extends AgentSession:
  def send(input: String): IO[Unit]                  // stdin → user message

trait Connector:
  def name: String                                                            // "claude" | "codex"

  // Driver methods
  def runStreamingSpec(systemPrompt: Path): IO[StreamingSession]
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession]
  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession]
  def runFixup(prompt: FixupPrompt): IO[AgentSession]
  def questionMechanism: QuestionMechanism

  // Reviewer methods
  def reviewDesign(input: DesignReviewInput): IO[DesignReview]
  def reviewPr(input: PrReviewInput): IO[PrReview]
  def refine(input: RefineInput): IO[RefineResult]
  def schemaMechanism: SchemaMechanism

  // Telemetry
  def costFrom(event: AgentEvent): Option[Cost]
```

The orchestrator's `SessionMonitor` calls `session.kill()` uniformly when either a settle timeout or a per-turn cost cap is breached. Streaming and headless are equally killable.

Mode-aware assets:
- System prompts per driver: `~/.forge/prompts/{specify,implement,fixup}.<driver>.md`.
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
3. Writes question + answer to the phase-specific answer file (§7.7).
4. Sends the answer back on stdin as the `tool_result`.
5. Driver continues mid-turn.

Action log entries carry `questionMechanism: "Native"`, full question + answer, and the originating `phase`.

### 7.3 `QuestionMechanism = HaltWithQuestion` (Codex default — pending Slice 0 confirmation)

Driver lacks a mid-turn interactive primitive. The system prompt establishes a convention: *"If you are blocked on a decision that requires human judgement, do not guess. Stop execution and output a single JSON object matching this schema: `{ status: 'needs_human', question: string, options: string[], allowFreeText: boolean, severity: 'blocking' | 'clarifying' | 'optional' }`. Then terminate."*

Orchestrator's event loop watches for a final output matching the schema. On match:
1. Pop the Q&A pane (same UI as the `Native` path).
2. Record question + answer in the action log; write to the phase-specific answer file (§7.7).
3. Re-spawn the driver with the original session prompt **plus** the previous-answer block prepended.

Action log entries carry `questionMechanism: "HaltWithQuestion"`, `respawn_count: <n>`, and the originating `phase`.

**Hard limits:**
- Maximum `config.maxHaltRespawns` re-spawns per driver session (default 5). Exceeded → `NeedsHumanIntervention("halt-with-question respawn limit exceeded", <phase-appropriate hint>)`.
- Each re-spawn counts as a fresh turn against the per-turn cost cap.

### 7.4 `SchemaMechanism = Native` — both connectors in v1

Both pinned CLIs accept an output schema and enforce it (Slice 0 §3 confirmed first-attempt structured output on the one-shot probes for both):

| Connector | Flag | Where the structured output lands |
|---|---|---|
| `ClaudeConnector` | `claude -p --output-format json --json-schema '<schema>'` | top-level `structured_output` field on the result envelope |
| `CodexConnector`  | `codex exec --json --output-schema <path>` | `agent_message.text` (JSON string conforming to the schema) |

Connector implementations parse the schema-shaped field directly. Both declare `schemaMechanism = Native`. Action log: `schemaMechanism: "Native"`.

Slice 1 integration tests run a regression sample (per schema, per method) against both connectors to catch model-induced edge cases. The Slice 0 ≥19/20 fallback threshold (§16) is no longer the gating bar — it's regression coverage, not feasibility validation.

### 7.5 Schema validation failures — adapter error path

A Native schema enforcement that nevertheless returns malformed JSON (CLI bug, model regression) surfaces as an adapter error from the connector. Outer-layer process retries (§7.6) do **not** retry adapter errors. For refinery: advisory-failure path (§14.2). For design/code review: `NeedsHumanIntervention("reviewer returned unparseable schema output", <hint>)`.

In v1 this path is expected to be vanishingly rare given the Native-on-both-sides Slice 0 finding. A `SchemaFallback`-style bounded retry protocol is parked as a v2 candidate (§20) for the case where a future CLI lacks native schema enforcement.

### 7.6 Process retries

- **`reviewProcessRetries` / `refineProcessRetries`** (`config.<reviewer>.*`) — wrap the *whole* reviewer call. Retries only on **process-level failures**: network timeouts, sandbox launch errors, CLI crashes. Default: 2.
- Adapter errors (schema validation failure, §7.5) are **not** retried by this layer. A schema-validation failure inside an otherwise-successful CLI invocation is a content-level failure that retry won't fix.

**Layering:** `RetryOnProcessFailure(processRetries) { connector.reviewX(...) }`. A single layer; no nested fallback inside the connector in v1.

### 7.7 Phase-specific answer paths

Both `Native` and `HaltWithQuestion` questions get appended to phase-appropriate Markdown files for human audit:

| Phase | Path |
|---|---|
| Spec consolidation (`InteractiveSpec`) | `.forge/specs/<feature>/audit/spec-answers.md` |
| Design review revision (`DesignReviewing` → revision) | `.forge/specs/<feature>/audit/design-review-r<n>-answers.md` |
| Design PR feedback (`DesignPrFeedback`) | `.forge/specs/<feature>/audit/design-pr-feedback-r<n>-answers.md` |
| Implementation (`PieceImplementing`) | `.forge/specs/<feature>/pieces/<p.id>.impl-answers.md` |
| Fix-up (`PieceFixingUp`) | `.forge/specs/<feature>/pieces/<p.id>.fixup-r<attempt>-answers.md` |

The orchestrator dispatches by current FSM state when writing.

### 7.8 Driver-question handling applies to *every* driver session

The mechanisms in §7.2–§7.3 apply to spec consolidation, design revision, implementation, and fix-up. For Claude-driver, all sessions use `Native`. For Codex-driver, all sessions use `HaltWithQuestion`.

### 7.9 Runaway detection — two bounds, every session

Every driver session (streaming or headless) is bounded by **both**:

1. **Settle timeout** (`settle.<phase>TimeoutSec`) — wall-clock cap. On expiry: `session.kill()`. Transition to `NeedsHumanIntervention("<phase> settle timeout", <hint>)`.
2. **Per-turn cost cap** (`maxTurnCostUsd`, default \$2) — checked after every `cost.update`. On breach: `session.kill()`. Transition to `NeedsHumanIntervention("turn budget exceeded", <hint>)`.

Per-phase timeouts:
- `settle.specTimeoutSec` — 300s.
- `settle.designRevisionTimeoutSec` — 600s.
- `settle.implementTimeoutSec` — 1800s.
- `settle.fixupTimeoutSec` — 900s.

### 7.10 Codex connector — adapter-internal notes

Slice 0 surfaced three Codex specifics that don't change the §7.1 connector contract but every implementer of `CodexConnector` will hit. They are recorded here so the contract is closed.

**(a) System prompt is prepended to the user prompt.** The Codex CLI exposes no `--system-prompt` / `--system-prompt-file` flag. `CodexConnector.runStreamingSpec(systemPromptPath: os.Path)` keeps the trait signature; the adapter reads the file and prepends it to the user prompt as a `## System` block. The same convention applies to `runHeadlessImplementation` and `runFixup`. The `~/.forge/prompts/{specify,implement,fixup}.codex.md` files therefore contain a self-describing system block that reads naturally when concatenated.

**(b) USD cost is computed from a per-model price table.** Codex's `turn.completed.usage` emits `{ input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens }` only — no `total_cost_usd`. `CodexConnector.costFrom` reads `~/.forge/prices.json` (per-user, optional per-repo override at `.forge/prices.json`) and computes USD per turn. The price-table format:

```json
{
  "schemaVersion": 1,
  "models": {
    "gpt-5-codex": {
      "inputPerMillionUsd": 1.25,
      "cachedInputPerMillionUsd": 0.125,
      "outputPerMillionUsd": 10.00,
      "reasoningOutputPerMillionUsd": 10.00
    }
  }
}
```

- **Missing model entry** → `costFrom` returns `None`; orchestrator logs `harness.price_missing` once per `(feature, model)` pair and proceeds with `usd = 0` for budget accounting. The TUI shows `$?` instead of a number.
- **Missing file** → all entries miss; same effect plus a one-time startup warning.
- Cost-cap enforcement (§12) still applies when prices are present. With prices missing, only token caps would protect against runaway turns — v1 documents the gap and lets the user supply prices when they want enforcement.

Building and maintaining `~/.forge/prices.json` is a Slice 1 deliverable (§17). A ship-with-defaults file covering current OpenAI models lives at `~/.forge/prices.example.json`.

ClaudeConnector reads `total_cost_usd` directly from the `result` event (Slice 0 §2) and ignores the price table.

**(c) Sticky session-scoped settings on `codex exec resume`.** `codex exec resume <id>` rejects `--sandbox`, `--output-schema`, `--add-dir`, `-a/--ask-for-approval`, `-C/--cd`. Settings supplied to the original `codex exec` invocation are sticky for the life of the session. This affects two places:

1. **Fix-up** (§11.6) — already spawns a fresh driver session, so a changed sandbox between implementation and fix-up "just works".
2. **Schema enforcement during design revision** — reviewer calls are independent one-shots (each `codex exec --output-schema ...`), not resumes, so they're unaffected.

In general: **any phase that needs different session-scoped settings than the original spawn must spawn a fresh session**. This rule is already implied by §11.6's fresh-driver rule for fix-up; §7.10 generalises it.

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

Required set = union of branch-protection required-checks and `requiredChecksOverlay`.

Discovery & timeout rules:

1. **No checks discovered at all** after `checkDiscoveryTimeoutSec`:
   - `BranchProtectionThenObserved` with empty protection set + zero observed → `NeedsHumanIntervention("no CI checks discovered", ResumeAfterHumanPush(p, prNumber))`.
   - `None` → straight to readiness.

2. **Any required check missing** (from branch-protection union overlay):
   ```
   NeedsHumanIntervention(
     s"required check '${name}' never appeared (source: ${source})",
     ResumeAfterHumanPush(p, prNumber)
   )
   ```

3. **Fewer than `minimumExpectedChecks` observed**:
   ```
   NeedsHumanIntervention(
     s"only ${observed} CI checks observed, expected at least ${minimumExpectedChecks}",
     ResumeAfterHumanPush(p, prNumber)
   )
   ```

4. Readiness: `stableGreenPolls` (default 2) consecutive green polls + base freshness (§9).

### 8.1 Branch-protection cache scoping

Cache key: `(featureId, baseBranch, cacheEpoch)`.

`cacheEpoch` increments on:
- Every `forge resume` (any variant).
- Explicit `forge refresh-cache <feature>`.
- TTL expiry (default 1 hour, `config.github.branchProtectionTtlSec`).

Older epochs are evicted lazily. Diff cache is keyed `(prNumber, headSha)` and naturally invalidates on push.

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
  def updatePrBranch(pr: PrNumber): IO[Unit]
  def tagSnapshot(name: String): IO[Unit]
  def pushTag(name: String): IO[Unit]
  def deleteRemoteTag(name: String): IO[Unit]
```

Rules:
- Before any branch creation: `git fetch` and fast-forward the configured base. Local divergence → `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)`.
- `createPieceBranch` returns `(branchName, baseSha)`. The FSM persists `baseSha` AND sets `pieces[i].status = "in_progress"` in the manifest **before transitioning out of the branch-creation step**.
- On CI readiness, `baseFreshness(pr, manifest.pieces[i].baseSha)`:
  - Branch protection requires up-to-date → trust protection.
  - Not required + `config.baseFreshness.autoUpdate: true` (default) → `updatePrBranch(pr)`, re-enter `PieceAwaitingCi`.
  - `autoUpdate: false` → `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(p, prNumber))`.

`PRWatcher`:

```scala
trait PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot]
```

`gh pr view --json state,statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments,commits,mergedAt,mergeCommit` is polled every `pollIntervalMs` (default 30s). Merged interpretation (`state == "MERGED"`, confirmed by `mergedAt`) lives in `forge-git`. Polling active only in `DesignAwaitingMerge | PieceAwaitingCi | PieceAwaitingReview | PieceAwaitingMerge`.

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
4. Path is ignored by `.gitignore` → `Deny` unless under `.forge/specs/...`.
5. Otherwise → `Allow`.

Strict mode (`staging.requireExplicitAllow: true`) flips rule 5: `Allow` only if path matches `staging.allowPatterns`; else `Ask`.

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

**Denial handling is phase-aware:**

- **Pre-PR denial** (initial implementation, §11.4): `NeedsHumanIntervention("change collector denied <path> in initial implementation", ResolveLocalImplementationChanges(p, branch))`. Resume options: `--run-fixup` (re-spawn driver from scratch) or `--commit-human-fix` (run ChangeCollector again on manual fix, commit, push, open PR).
- **Post-PR denial** (fix-up, §11.6): `NeedsHumanIntervention("change collector denied <path>", RunAnotherFixup(p, prNumber))`.

`Ask` → Q&A pane, default option `Deny`. `Allow` → staged. Stage plan recorded in action log and rendered into PR body.

### 10.2 Reviewer posting (Forge owns the diff)

Flow:
1. Forge fetches PR diff with `gh pr diff <n>`.
2. Forge fetches changed-file metadata with `gh api repos/.../pulls/<n>/files`.
3. Prompt reviewer with piece spec, acceptance, design link, full diff, file metadata.
4. Validate review JSON against `~/.forge/schemas/code-review.json`.
5. **`verdict: request_changes` with empty blockers → adapter bug.** Log `review.invalid_verdict`. Retry once with corrective prompt. Second failure → `NeedsHumanIntervention("review adapter produced invalid verdict", RunAnotherFixup(p, prNumber))`.
6. Post inline comments (anchorText algorithm below) + summary review.

### 10.3 `anchorText` matching

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

Blockers with `path: null` always go into the summary comment. After per-blocker inline posts: one `gh pr review --approve | --request-changes` with the summary body. `--request-changes` always carries a non-empty body.

API: **line-based** (`POST /pulls/{n}/comments` with `path/side/line/commit_id`). Pinned in Slice 0.

### 10.4 Driver-question routing

All driver-question events (any mechanism, any phase) route to the **Forge Q&A pane**. Never posted back to GitHub PR threads. Answers written to phase-specific files in §7.7.

---

## 11. Lifecycle

Throughout: **driver** = the CLI selected by `Mode`. **reviewer** = the other CLI.

### 11.0 Preconditions (every state-changing command)

1. Acquire `.forge/state/.lock` (§13).
2. `BranchManager.preflight(command)` — command-aware (§15).
3. Manifest / `decomposition.md` reconcile check (§5.4).
4. State cache verified against log replay; rewritten if divergent. `feature.designSessionId`, `feature.currentPieceSessionId`, `branchProtectionCacheEpoch` recovered as projections.
5. **Required session-id check.** Any transition about to call `driver.resumeStreamingSpec(...)` validates that the required session id is present via `requireSessionId(...)` (§6.2). On missing:
   - Design phase resume (`DesignReviewing` revision step, `DesignPrFeedback`): → `NeedsHumanIntervention("missing design session id, cannot resume", ReopenDesign(currentDesignPr))`.
   - (No other resume calls in v1; this rule covers all current cases.)

### 11.1 Spec phase

1. `forge new "title"` with optional `--mode claude-driver|codex-driver`. FeatureId slugged per §5.2. BranchManager creates `<branchPrefix>/<feature>/design`.
2. Spawn driver: `session <- driver.runStreamingSpec(~/.forge/prompts/specify.<driver>.md)`. **Update `feature.designSessionId = Some(session.sessionId)`.** Log `<driver>.spawn`.
3. Action log records `<driver>.user_message` and `<driver>.assistant_text` (≤200 chars).
4. User types `/done`. Forge sends a final message asking the driver to write `design.md`, `manifest.json` (each piece `baseSha: null`, `status: "pending"`, `attempts: 0`), one `pieces/<p>.md` per manifest entry, and to render `decomposition.md` using `~/.forge/templates/decomposition.md.hbs`.
5. Driver questions handled per §7.8. Answers to `.forge/specs/<feature>/audit/spec-answers.md`.
6. **Settle** = next `result` event after Forge's final message, bounded by `settle.specTimeoutSec` (default 300s) AND `maxTurnCostUsd`. On either breach: `session.kill()`, transition to `NeedsHumanIntervention("spec settle timeout" or "spec turn budget exceeded", AbortOrAbandon)`.
7. Post-check: every piece in manifest has a `pieces/<p>.md` file; `decomposition.md` renders identically. On mismatch: one corrective message, retry settle (max 2 corrections, then `NeedsHumanIntervention`).
8. FSM → `DesignReviewing(round = 1)`. `feature.designSessionId` retained.

### 11.2 Design review

9. Reviewer reviews `design.md` via `reviewer.reviewDesign(...)`. Uses `Native` schema enforcement (§7.4). Wrapped by `config.<reviewer>.reviewProcessRetries`.
10. Output appended to `design.md` under `## Reviewer Review (round <n>)`.
11. `severity: blocking` questions → `DesignNeedsHumanInput(round, questions)` → Q&A pane → answers appended to `design.md` under `## Clarifications (round <n>)` → return to `DesignReviewing(round + 1)`. **`feature.designSessionId` persists.**
12. `verdict: request_changes` → validate `feature.designSessionId` is present (§11.0 step 5); on missing → `NeedsHumanIntervention("missing design session id, cannot resume", ReopenDesign(None))`. On present: `session <- driver.resumeStreamingSpec(feature.designSessionId.get)`. Log `<driver>.resume` with `{ oldSessionId = feature.designSessionId.get, newSessionId = session.sessionId }`; with the pinned CLIs the two are equal (§6.1). Update `feature.designSessionId = Some(session.sessionId)` regardless — idempotent in the equal case, correct in the (currently hypothetical) unequal case. Forge sends a revision message addressing the blockers. Driver questions handled per §7.8; answers to `.forge/specs/<feature>/audit/design-review-r<n>-answers.md`. Bounded by `settle.designRevisionTimeoutSec` (600s). Loop to step 9. Max `config.maxDesignReviewRounds` (default 3) → `NeedsHumanIntervention("design did not converge", ReopenDesign(None))`.
13. `verdict: approve` → commit design assets and design-phase audit snapshot. Open PR `[design] <title>` via `gh pr create --json url -q .url`. Persist `manifest.designPr = prNumber`. FSM → `DesignAwaitingMerge(prNumber)`. `feature.designSessionId` retained.

### 11.3 Design PR gate

From `DesignAwaitingMerge(prNumber)`:
- `state == MERGED` + `mergedAt != null` → `DesignReady`. **`feature.designSessionId` cleared.**
- New human comment (id > baseline) or `CHANGES_REQUESTED` → `DesignPrFeedback(prNumber, round + 1)`.
- PR closed without merge → `NeedsHumanIntervention("design PR closed without merge", ReopenDesign(Some(prNumber)))`.

From `DesignPrFeedback(prNumber, round)`:
1. Write feedback bundle to `.forge/specs/<feature>/audit/design-pr-feedback-round-<n>.md`.
2. Validate `feature.designSessionId` per §11.0 step 5. `session <- driver.resumeStreamingSpec(feature.designSessionId.get)`. Log `<driver>.resume` with `{ oldSessionId, newSessionId = session.sessionId }`; the two are equal under the pinned CLIs (§6.1). Update `feature.designSessionId = Some(session.sessionId)` (idempotent in the equal case). Forge sends a message referencing the feedback file. Driver questions handled per §7.8; answers to `.forge/specs/<feature>/audit/design-pr-feedback-r<n>-answers.md`. Bounded by `settle.designRevisionTimeoutSec`.
3. Update design assets.
4. **Snapshot tag:** `git tag <branchPrefix>/_snapshots/<feature>/design-r<n>` on current design branch HEAD before force-push.
   - Default: local-only. If `config.github.pushSnapshotTags: true`, push and prune to last 3.
5. **Force-push-with-lease** to the design branch. On refuse → `NeedsHumanIntervention("design branch updated externally", ReopenDesign(Some(prNumber)))`.
6. Return to `DesignAwaitingMerge(prNumber)`.

### 11.4 Implementation phase

For each piece `p` in manifest order (selected by §11.7 algorithm):

1. `syncBase()`, then `createPieceBranch(feature, p.id)` returns `(branchName, baseSha)`. **Atomically persist `manifest.pieces[i].baseSha = baseSha` AND `manifest.pieces[i].status = "in_progress"` before transitioning.**
2. Spawn driver: `session <- driver.runHeadlessImplementation(...)` with `~/.forge/prompts/implement.<driver>.md`. **Update `feature.currentPieceSessionId = Some(session.sessionId)`.** Log `<driver>.spawn`. Prompt: implement piece `<p.id>` to acceptance criteria; **do not commit — Forge will commit**.
3. Action log records every tool use.
4. Driver questions handled per §7.8. Answers to `.forge/specs/<feature>/pieces/<p.id>.impl-answers.md`.
5. Settle bounded by **both** `settle.implementTimeoutSec` (1800s) and `maxTurnCostUsd`. `SessionMonitor` calls `session.kill()` on either breach.
6. On settle:
   - ChangeCollector (§10.1) classifies changes. **On `Deny` here (pre-PR):** transition to `NeedsHumanIntervention(..., ResolveLocalImplementationChanges(p, branch))`. Do not proceed to commit.
   - On clean classification: Forge commits with `feat(<feature>): <piece title>`.
   - DocSync: if a prior piece merged since this branch was cut, update `[x]` and status badge in `decomposition.md` and the manifest entry.
   - Push, then `createPr` (PR body from `~/.forge/templates/pr-body.md.hbs`).
   - **Atomically persist `manifest.pieces[i].prNumber = prNumber`** alongside the baseline comment/review IDs.
7. FSM → `PieceAwaitingCi(p, prNumber)`. `feature.currentPieceSessionId` retained (per §6.1 invariant).

### 11.5 CI & review polling

**`piece.attempts` semantics:** one counter, all causes. Increment on every fix-up entry regardless of source.

From `PieceAwaitingCi(p, prNumber)`:
- CI readiness met (§8 + §9) → trigger reviewer code review (§10.2) → `PieceAwaitingReview(p, prNumber)`.
- Any required check failed: **atomically persist `manifest.pieces[i].attempts += 1`**. If new value `<= config.maxFixupRounds`: write `<p>.failures.md`, FSM → `PieceCiFailed(p, prNumber, attempt = manifest.pieces[i].attempts)` → fix-up (§11.6). Else → `NeedsHumanIntervention("piece <p> fix-up exhausted after CI failure", RunAnotherFixup(p, prNumber))`.

From `PieceAwaitingReview(p, prNumber)`:
- Reviewer `approve`, no unresolved human `CHANGES_REQUESTED` → `PieceAwaitingMerge(p, prNumber)`.
- Reviewer `request_changes`: **atomically persist `attempts += 1`**. If `<= maxFixupRounds`: → `PieceReviewFailed(p, prNumber, attempt)` → fix-up. Else → `NeedsHumanIntervention(..., RunAnotherFixup(p, prNumber))`.
- Human comment (id > baseline) or `CHANGES_REQUESTED`: **atomically persist `attempts += 1`**. Same gate.

From `PieceAwaitingMerge(p, prNumber)`:
- **`state == MERGED` + `mergedAt != null`:**
  1. **Atomically persist `manifest.pieces[i].status = "merged"`, `manifest.pieces[i].prNumber = prNumber`, `manifest.pieces[i].mergeCommit = snapshot.mergeCommit`, `manifest.pieces[i].mergedAt = snapshot.mergedAt`.** Write via temp file + `os.move`.
  2. Append `audit.piece_merged` to the action log with the same fields.
  3. FSM → `Refining(p, prNumber, now)`.

  A crash between (1) and (3) leaves the piece correctly marked merged; the next `forge resume` reads the merged status from the manifest and advances through `Refining` normally.

- New human comment or `CHANGES_REQUESTED` before merge: **atomically persist `attempts += 1`**. Same fix-up gate.
- PR closed without merge → `NeedsHumanIntervention("piece PR closed without merge", RunAnotherFixup(p, prNumber))`.

**v1 rule: piece PRs are merged by the human, not by Forge.**

### 11.6 Fix-up

Fresh driver session: `session <- driver.runFixup(...)`. **Update `feature.currentPieceSessionId = Some(session.sessionId)`** (the fix-up session is now the active session for this piece). No resume. Prompt references `<p>.failures.md` and the unchanged piece spec. Driver questions handled per §7.8; answers to `.forge/specs/<feature>/pieces/<p.id>.fixup-r<attempt>-answers.md`. Settle bounded by `settle.fixupTimeoutSec` (900s) and `maxTurnCostUsd`. Forge commits on settle via the same ChangeCollector path (§10.1 post-PR rules).

After fix-up commit/push, FSM → `PieceAwaitingCi(p, prNumber)`. `feature.currentPieceSessionId` retained.

### 11.7 Post-merge — Refining and advance

From `Refining(p, prNumber, startedAt)`, Forge calls `reviewer.refine(...)`. UI surfacing per §14.1.

Outcomes (all transitions exit `Refining` directly):

- **`no_change`** or **refinery failure (advisory, §14.2)** → compute `nextPiece = manifest.pieces.find(_.status == "pending").map(_.id)`. By §11.5 step 1, the just-merged piece's status is already `"merged"`, so it cannot be selected.
  - `nextPiece` is defined → `PieceImplementing(nextPieceId)`. **`feature.currentPieceSessionId` cleared.**
  - `nextPiece` is empty → `FeatureDone`. **`feature.currentPieceSessionId` cleared.**
- **`update_plan`** → build `ManifestPatch`, validate, FSM → `PlanningUpdate(reason, patch)`. **`feature.currentPieceSessionId` cleared.**
- **`reopen_design`** → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(None))`. **`feature.currentPieceSessionId` cleared.**

The action log captures the piece-merged milestone via the `audit.piece_merged` event written in §11.5 step 2.

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

1. **Before spawning any agent:** if `feature.cost + estimatedSpawnCost > maxFeatureCostUsd`, refuse → `NeedsHumanIntervention("feature budget would be exceeded", AbortOrAbandon)`. Same for piece cap. Estimated spawn cost is conservative (`maxTurnCostUsd`).
2. **After every `cost.update`:** re-evaluate all three caps. Per-feature/per-piece breach → let current turn complete, no new spawns, transition to `NeedsHumanIntervention("budget exceeded", <hint>)`.
3. **Per-turn breach (mid-turn):** `session.kill()` (SIGTERM, 5s grace, SIGKILL). Transition to `NeedsHumanIntervention("turn budget exceeded", <hint>)`. Catches runaway tool-use loops.

`SessionMonitor` is the orchestrator component responsible for both settle-timeout and cost-cap enforcement. TUI status pane shows running totals against caps.

---

## 13. Locking

Files: `.forge/state/.lock` (OS lock target), `.forge/state/.lock.json` (metadata: `{ pid, hostname, startedAt, command, feature }`).

Startup behaviour:
1. Try `FileChannel.tryLock` on `.lock`.
2. **Lock acquired, no metadata** → write metadata, proceed.
3. **Lock acquired, stale metadata exists**:
   - TUI: prompt "Looks like a previous Forge run crashed. Clear the stale lock and continue? (Y/n)".
   - CLI: refuses unless `--yes` or `FORGE_AUTO_UNLOCK_STALE=1`. Suggests `forge unlock --force`.
4. **Lock NOT acquired**: read `.lock.json`, print holder info, exit 2.

`forge unlock --force`:
- Live OS lock by another process → refuses, prints holder.
- Stale metadata only → removes metadata, succeeds.

---

## 14. Refinery and `PlanningUpdate`

After each piece merge (entering `Refining`), Forge calls `reviewer.refine(...)`.

### 14.1 UI surfacing

- TUI: status pane shows `Refining: checking design against piece <p.id> (<elapsed>s)`.
- CLI: prints `Refining piece <p.id>...` and elapsed-time tick every 10s.

### 14.2 Failure path (advisory)

`reviewer.refine(...)` is wrapped by `config.<reviewer>.refineProcessRetries`. If those retries exhaust, OR if the reviewer returns an adapter error from a schema-validation failure (§7.5):
- Log `harness.refinery_failed`.
- Advance directly per §11.7 (treated as `no_change`).

### 14.3 Verdict handling

Refine schema (`~/.forge/schemas/refine.json`) — outcomes per §11.7.

For `update_plan`:
- Build `ManifestPatch` from refine output, validate against current manifest, FSM → `PlanningUpdate(reason, patch)`. Patch is also written to `.forge/specs/<feature>/audit/refine-after-<p>.json` for human review.

From `PlanningUpdate(reason, patch)` (patch is **inline** in FSM state):
- User chooses apply / defer / reopen / ignore via Q&A pane.
- **Apply:** mutate manifest atomically (temp file → validate → `os.move`). Regenerate `decomposition.md`. Write/edit affected `pieces/*.md`. Changes ride on the next piece PR; for the final piece, open `chore(<feature>): apply planning update` PR. Already-merged piece IDs immutable.
- **Defer:** snooze until after the next piece; FSM remembers via a deferred-patches list. Advance per §11.7.
- **Reopen:** `NeedsHumanIntervention("planning update deferred to design", ReopenDesign(None))`.
- **Ignore:** record `planning.ignored`; advance per §11.7.

---

## 15. Command-aware preflight

| Command | Clean worktree required? | Other checks |
|---|---|---|
| `forge new` | Yes | Base fast-forwardable; FeatureId slug computed and shown |
| `forge spec` | Yes | On the design branch |
| `forge run` | Yes | Manifest reconcile passes |
| `forge resume --after-human-push` | Yes | On the piece branch; PR head == local HEAD; bumps `branchProtectionCacheEpoch` |
| `forge resume --commit-human-fix` | No | **Current branch == derived piece branch**; bumps `branchProtectionCacheEpoch` |
| `forge resume --run-fixup` | Yes | On the piece branch; bumps `branchProtectionCacheEpoch` |
| `forge reconcile` | No | — |
| `forge refresh-cache` | No | Bumps `branchProtectionCacheEpoch` only |
| `forge status` / `replay` / `rebuild-state` | No | Read-only |
| `forge unlock --force` | No | Lock-specific |
| `forge abandon` | No | Transitions to `Abandoned` |

For `--commit-human-fix`: Forge computes the expected piece branch from `manifest.json` and compares to `git branch --show-current`. Mismatch → refuse with: "You are on `<X>`, the active piece branch is `<Y>`. Switch branches and retry, or use `--after-human-push` if your fix is already pushed."

`--force` available everywhere; usage logged as `harness.preflight_bypassed`.

---

## 16. Slice 0 — 2×2 validation (complete)

Slice 0 ran on 2026-05-25 against Claude Code CLI `2.1.150`, Codex CLI `0.130.0`, GitHub CLI `2.83.1`. Output: `docs/slice-0/slice-0-report.md` with pinned versions, per-cell findings, and the scope decision. This section is preserved as the contract Slice 0 validated; the corrections it surfaced are folded into the rest of v1.1.

The matrix is 2 CLIs × 2 roles.

**Driver role (both CLIs):**

1. Long-lived streaming subprocess.
2. Isolation flag suppressing user-level config but preserving repo-level.
3. **Session resume** via `resumeStreamingSpec(sessionId)`.
4. System-prompt injection from a file (Codex: prepended to the user prompt — §7.10(a)).
5. **Killable handle:** SIGTERM/SIGKILL cleanly terminates both streaming and headless subprocesses without orphaning child processes.
6. **Question mechanism:** at least one of:
   - **`Native`** — mid-turn suspension primitive.
   - **`HaltWithQuestion`** — ≥19/20 reliable structured halt + re-spawn (measurement deferred to Slice 1; falling below this bar narrows scope per §16.1 option 2).

**Reviewer role (both CLIs):**

7. **`Native` schema-constrained output** — CLI accepts and enforces an output schema. Slice 0 confirmed first-attempt success on the one-shot probes for both CLIs. A ≥19/20 sample suite runs in Slice 1 as regression coverage, not feasibility validation.
8. Read-only sandbox mode.

**Other:**

9. `gh pr view --json state,mergedAt,mergeCommit,...` on merged PRs.
10. `gh api repos/.../branches/<base>/protection/required_status_checks` on protected, unprotected, inaccessible repos.
11. Line-based comment API.
12. Rate-limit baseline per piece for one fix-up cycle.

### 16.1 Per-cell outcome and design-scope decisions

| Cell | Capabilities required | Threshold | Slice 0 outcome |
|---|---|---|---|
| Claude driver | streaming, isolation, resume, system-prompt, killable, questions (`Native` `AskUserQuestion`) | All work | ✅ Met |
| Codex driver | streaming, isolation, resume, system-prompt (prepended, §7.10(a)), killable, questions (`HaltWithQuestion`) | All work; halt protocol ≥19/20 | ✅ CLI mechanics met; reliability ≥19/20 measurement deferred to Slice 1 |
| Claude reviewer | `Native` schema output (`--json-schema`), read-only sandbox | All work | ✅ Met — first-attempt success on probe (§3.1 of the Slice 0 report) |
| Codex reviewer | `Native` schema output (`--output-schema`), read-only sandbox | All work | ✅ Met |

If a future cell **doesn't qualify** (e.g., the deferred Codex halt-reliability measurement falls below 19/20), the response is a design-scope decision, **not silent triage:**

1. **Wait** for a future CLI version.
2. **Narrow scope explicitly** — update §1 Goal 6 in a 1.x revision.
3. **Treat as v1-blocking.**

---

## 17. Build order (de-risked)

### Slice 0 — Validate CLI assumptions (complete)

Ran 2026-05-25. Output: `docs/slice-0/slice-0-report.md`. Findings folded into this revision (v1.1).

### Slice 1 — Agent connectors (week 1)

Build `forge-agents` standalone with a CLI demo and integration tests.

- `AgentSession`, `StreamingSession`, `Connector` traits per §7.1.
- `ClaudeConnector` and `CodexConnector`. Each declares `questionMechanism` and `schemaMechanism = Native` (§7.4).
- `HaltWithQuestion` parsing + re-spawn loop (§7.3) in the orchestrator scaffolding. Reliability sample (≥19/20 target per §16) measured during integration tests; failure narrows scope per §16.1.
- **Codex price table** (§7.10(b)). Define the `~/.forge/prices.json` schema, ship `~/.forge/prices.example.json` with current OpenAI model entries, implement `CodexConnector.costFrom` to look up by model and compute USD. Handle missing-model / missing-file by returning `None` (orchestrator logs `harness.price_missing` once per `(feature, model)`).
- **Codex system-prompt prepending** (§7.10(a)) implemented in `CodexConnector.runStreamingSpec` / `runHeadlessImplementation` / `runFixup`.
- **Codex sticky-settings rule** (§7.10(c)): no `resumeStreamingSpec` call attempts to change `--sandbox`, `--output-schema`, `--add-dir`, etc.; any settings change spawns a fresh session.
- Integration tests:
  - Claude driver runs headless on "create a hello world Scala file in /tmp/forge-test".
  - Codex reviewer reviews a known-good and known-bad markdown file.
  - Cross-mode fixtures: Codex driver, Claude reviewer.
  - **Native schema regression suite** — ≥19/20 valid outputs per schema (`design-review`, `code-review`, `refine`) for each reviewer. A failure surfaces as an adapter error (§7.5) rather than retry.
  - HaltWithQuestion exercised with a contrived ambiguous prompt; record reliability against the ≥19/20 target.
  - `resumeStreamingSpec` exercised end-to-end. Assert `oldSessionId == newSessionId` on the `<actor>.resume` event under the pinned CLIs (§6.1).
  - `kill()` exercised on both streaming and headless; verify no zombie processes.
  - `CodexConnector.costFrom` with present, missing, and partial price tables.

### Slice 2 — FSM, Feature, ActionLog, StateCache (week 2)

- `forge-core`: FSM as `(FsmState, FsmEvent) => (FsmState, List[ActionLogEntry])` plus `Feature` aggregate with the projections in §6.
- `requireSessionId` helper (§6.2).
- `StateCache` with atomic writes.
- `ActionLog`.
- `forge rebuild-state <feature>` replays log → state cache.
- Property tests:
  - Replaying the action log reproduces the final state, including session-id projections.
  - `NeedsHumanIntervention` always carries a `ResumeHint` and `forge resume` produces a legal next state.
  - No success path reaches implementation before design is merged.
  - **No piece is selected by `nextPiece` after it has been marked merged via §11.5 step 1.**
  - **`feature.currentPieceSessionId` lifecycle per §6.1:** populated on `PieceImplementing`/`PieceFixingUp` spawn, retained through all subsequent same-piece states (CI/review/merge/refining), cleared exactly at the advance transition.
  - **`feature.designSessionId` lifecycle per §6.1:** populated on `InteractiveSpec` spawn / design-revision resume, retained through every design-phase state, cleared on `DesignReady`. Updating with `newSessionId` from a `<actor>.resume` event is idempotent when `newSessionId == oldSessionId` (the pinned-CLI case).
  - **`requireSessionId` always returns `Left(NeedsHumanIntervention(...))` rather than throwing** when the required session id is absent.
  - Human feedback before merge always returns to design revision or fix-up.
  - CI cannot become green before check discovery finishes (unless `CiPolicy.None`).
  - Already-merged piece IDs cannot be removed by planning updates.
  - Reorder invariant (§5.5) holds across arbitrary patch sequences.
  - Manifest invariant: any piece with `status != "pending"` has non-null `baseSha`.
  - **Atomic merge mutation (§11.5 step 1) is committed before the FSM transition to `Refining`:** crashing between the manifest write and the state-cache write leaves the manifest correctly marked, and `forge resume` reads merged status and advances.

### Slice 3 — BranchManager + PRWatcher + ProcessLock + SessionMonitor (week 3)

- BranchManager: branch creation (returning `(branchName, baseSha)`), push, PR creation, preflight, snapshot tags.
- PRWatcher: 30s poller, `PrSnapshot` ADT. Branch-protection cache scoped by `(featureId, baseBranch, cacheEpoch)`.
- ProcessLock: `FileChannel.tryLock` + `.lock.json` metadata.
- SessionMonitor: watches events stream, tracks elapsed time, invokes `session.kill()` on settle timeout or per-turn cost breach.
- Integration test against sacrificial GitHub repo: create branch, push, open PR, observe watcher emit transitions through merge.

### Slice 4 — Headless feature loop with line-mode REPL (week 4)

Wire Slices 1–3 together. **No TUI yet.**

```
forge new "feature title" [--mode claude-driver|codex-driver] [--id <slug>]
forge spec <feature>            ← line-mode REPL
forge run <feature>             ← headless from DesignReady through to FeatureDone
forge status [<feature>]
forge resume <feature> --after-human-push | --commit-human-fix | --run-fixup
forge reconcile <feature>
forge refresh-cache <feature>
forge abandon <feature>
forge rebuild-state <feature>
forge unlock --force
```

End of week 4: drive a real small feature through Forge from the command line.

### Slice 5 — TUI (week 5)

Build the termflow UI as a richer view over Slice 4's hooks. TUI last because it's the most subjective.

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
  "settle": {
    "specTimeoutSec": 300,
    "designRevisionTimeoutSec": 600,
    "implementTimeoutSec": 1800,
    "fixupTimeoutSec": 900
  },
  "github": {
    "commentApi": "line-based",
    "cacheBranchProtection": true,
    "branchProtectionTtlSec": 3600,
    "cacheDiff": true,
    "rateLimitBackoffMs": 60000,
    "pushSnapshotTags": false,
    "snapshotTagRetention": 3
  }
}
```

Notes:
- `reviewProcessRetries` and `refineProcessRetries` cover **process-level failures only** (§7.6). Schema-validation failures inside an otherwise-successful CLI invocation surface as adapter errors and are not retried.
- Branch-protection cache TTL is one of three invalidation triggers (`forge resume`, `forge refresh-cache`, TTL); see §8.1.
- Global config at `~/.config/forge/config.json`. Templates and prompts under `~/.forge/`; per-repo overrides under `.forge/overrides/`.
- **Codex prices** live at `~/.forge/prices.json` (per-user) with optional per-repo override at `.forge/prices.json`. Schema and missing-file behaviour per §7.10(b). A `~/.forge/prices.example.json` ships with default OpenAI model entries.

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

`seq` is monotonic per feature.

`kind` values:

- `fsm.transition`
- `<actor>.spawn` — projects `feature.designSessionId` / `feature.currentPieceSessionId`.
- `<actor>.resume` — `{ oldSessionId, newSessionId }`. Updates the projection from `newSessionId`. Under the pinned CLIs the two values are equal (§6.1); the two-field shape is forward-compatible with a future CLI that mints a new id on resume.
- `<actor>.user_message` — summary only.
- `<actor>.assistant_text` — summary only + token count.
- `<actor>.tool_use` — paths/command summaries only.
- `<actor>.ask_user_question` — full Q+A, `questionMechanism`, `phase`, `answerFile`.
- `<actor>.halt_respawn` — `{ respawnCount, phase }`.
- `<actor>.schema_invalid` — `{ method, validatorError }`. Emitted when a Native schema enforcement returns malformed JSON (§7.5); always paired with an adapter error to the caller.
- `<actor>.process_retry` — `{ method, attempt, reason }`.
- `audit.piece_merged` — `{ p, prNumber, mergeCommit, mergedAt }`. Written on entering `Refining` per §11.5 step 2.
- `gh.poll` — sampled (every 5th); transitions always logged in full.
- `gh.action` — `{ verb, args }`.
- `review.anchor_demoted`.
- `review.invalid_verdict`.
- `user.command`.
- `harness.error` — exceptions with stack trace.
- `harness.preflight_bypassed` — `--force` usage.
- `harness.refinery_failed` — advisory.
- `harness.session_killed` — `{ reason: "settle_timeout" | "turn_budget", phase, sessionId }`.
- `harness.cache_invalidated` — `{ cache: "branch-protection", trigger: "resume" | "refresh" | "ttl" }`.
- `harness.missing_session_id` — `{ phase, hint }` when `requireSessionId` triggered a `NeedsHumanIntervention`.
- `harness.rate_limited` — GitHub 403/429 with `Retry-After`.
- `harness.price_missing` — `{ provider, model }`. Emitted once per `(feature, model)` when `CodexConnector.costFrom` can't find a price-table entry (§7.10(b)); subsequent turns log `usd = 0` for budget accounting without re-emitting this event.
- `cost.update` — `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd, pieceTotalUsd, turnTotalUsd }`. For Codex with a missing price entry, `usd = 0` and `featureTotalUsd`/`pieceTotalUsd`/`turnTotalUsd` reflect that — cost-cap enforcement (§12) effectively degrades to "off" for that model. The TUI surfaces this as `$?`.

TUI: "tail action log" pane. `forge replay <feature>` re-prints as narrative.

---

## 20. v2 candidates

Decide after lived experience. None are mutually exclusive.

| Candidate | Pick when |
|---|---|
| **Auto-merge on green + reviewer approval** | v1 friction is "clicking Merge on PRs that are clearly fine". |
| **Stacked PRs onto a per-feature integration branch with composite CI** | v1 friction is "each piece PR rerunning full CI". |
| **Parallel features across checkout slots** | Serial throughput becomes the bottleneck. |
| **GitLab adapter** | A non-GitHub repo. Parallel parser into the existing `PrSnapshot` ADT. |
| **Third-party agents / arbitrary role pairings** | A real third agent becomes interesting. Generalise `Mode` into pluggable role traits. |
| **Webhook mode** | Sub-second reactivity demanded. |
| **Langfuse / structured tracing** | Action log alone stops being enough. |
| **Process-tuning mode** | Feed historical action logs to an LLM to suggest prompt/FSM tweaks. |
| **`SchemaFallback` emulation** | A new connector targets a CLI without native schema enforcement. The `SchemaMechanism.SchemaFallback` enum case (§6) is reserved for this; the bounded prompt+validate+1 retry protocol is sketched in earlier design revisions and can be revived if needed. v1 doesn't need it — Slice 0 confirmed both pinned CLIs are Native. |
| **Per-model cost telemetry for Claude** | Claude grows multiple price tiers / a `usage.usd` field disappears. Today Claude reports `total_cost_usd` directly; the price-table mechanism built for Codex (§7.10(b)) would extend naturally. |

---

## 21. Decision summary

| Question | Decision |
|---|---|
| Adopt or build? | Build. Scala harness. |
| Drive Claude/Codex how? | Subprocess + stream-json / `exec --json`. |
| TUI library? | termflow (llm4s/termflow), Elm-architecture, Scala 3. |
| Effect system? | cats-effect 3 + fs2. |
| Branch model v1? | One branch per piece off main; design branch+PR merged first. |
| Merge gates v1? | Human-merged at both gates. |
| GitHub integration? | Polling `gh` every 30s only when FSM cares. Line-based comment API. |
| Required vs optional checks? | Branch-protection required + overlay; any missing required check after discovery → `NeedsHumanIntervention`. Cache scoped by epoch. |
| Failure recovery? | `NeedsHumanIntervention(reason, resumeHint)` is non-terminal; six typed resume hints. `Abandoned` only via `forge abandon`. |
| Work breakdown SoT? | `manifest.json` (machine); `decomposition.md` rendered with editable-region markers; `forge reconcile` imports edits. `baseSha` nullable until branch creation. |
| Manifest mutation timing? | Every manifest annotation tied to an FSM transition is named explicitly in §11 (baseSha+status="in_progress" at branch creation, prNumber at PR creation, attempts at fix-up entry, status="merged"+prNumber+mergeCommit+mergedAt at merge detection). |
| Autonomy between pieces? | Headless implementation. Forge writes code, opens PR, gets reviewer approval; human clicks Merge. |
| Who commits? | Forge, not the driver. |
| Refinery step? | In v1, advisory; reviewer-only check. Failure → advance directly. |
| Post-merge state? | No `PieceMerged` state. Flow is `PieceAwaitingMerge → (atomic merge mutation) → Refining → (next PieceImplementing | FeatureDone | PlanningUpdate | NeedsHumanIntervention)`. `audit.piece_merged` event for consumers. |
| Observability? | Per-feature action log (`.forge/log/<feature>.jsonl`, local canonical, monotonic `seq`). |
| Audit committed? | Per `auditMode` (`summary` default). Phase-specific Q&A answer files. |
| Concurrency? | Single Forge process per repo via `flock` + metadata. |
| Pre-flight? | Hard precondition: clean working copy (command-aware exceptions). |
| Cost cap? | Per-feature, per-piece, per-turn. Per-turn enforced mid-turn via `session.kill()`. |
| Killable sessions? | Yes — `AgentSession.kill()` on both streaming and headless. |
| Settle bound? | Every driver session bounded by both settle timeout (per-phase) and per-turn cost cap. |
| Driver questions? | Required capability via `Native` or `HaltWithQuestion`. Silent proceed never permitted. Answers to phase-specific files. |
| Reviewer schema? | `Native` only in v1 — both pinned CLIs enforce schemas (Claude `--json-schema`, Codex `--output-schema`); Slice 0 confirmed. `SchemaFallback` parked as a v2 option (§20). |
| Schema validation failure? | Adapter error (§7.5). Not retried by `reviewProcessRetries`. Refinery: advisory failure (§14.2). Design/code review: `NeedsHumanIntervention`. |
| Process retries vs schema attempts? | Single layer. `reviewProcessRetries` / `refineProcessRetries` wrap the whole call; no nested per-attempt fallback inside the connector. |
| Capability emulation? | None in v1. `HaltWithQuestion` is a question-mechanism *convention*, not capability emulation. |
| Mode pluggability? | Two modes (`ClaudeDriver`, `CodexDriver`). |
| Slice 0 outcome? | ✅ Green light. All four cells met thresholds. Three spec corrections folded into v1.1 (Native Claude schema, session-id preserved on resume, Codex adapter notes). |
| Session id on resume? | Preserved by both pinned CLIs (`newSessionId == oldSessionId`). `<actor>.resume` event carries `{ oldSessionId, newSessionId }` for forward-compatibility. |
| Codex cost telemetry? | Per-model price table at `~/.forge/prices.json` (§7.10(b)). `CodexConnector.costFrom` looks up by model and computes USD. Missing entry → `usd=0` + `harness.price_missing` once per `(feature, model)`. |
| Codex system prompt? | Prepended to the user prompt as a `## System` block by the adapter (§7.10(a)) — no `--system-prompt` flag on Codex CLI. |
| Codex sticky settings? | `codex exec resume` inherits original `--sandbox`, `--output-schema`, `--add-dir`. Any change requires a fresh session (§7.10(c)). |
| ChangeCollector denial? | Phase-aware: pre-PR → `ResolveLocalImplementationChanges`; post-PR → `RunAnotherFixup`. |
| `piece.attempts` counter? | One counter for all fix-up rounds regardless of source. |
| Session resume contract? | `Connector.resumeStreamingSpec(sessionId)` returns a fresh `StreamingSession` attached to prior conversation. |
| Where do session ids live? | Feature-scoped durable fields (`feature.designSessionId`, `feature.currentPieceSessionId`), projected from action log. Invariants in §6.1. |
| Missing session id? | `requireSessionId(...)` → `NeedsHumanIntervention(..., ReopenDesign(...))`. No `.get` calls in production. |
| First thing to build? | Slice 0 (done — see `docs/slice-0/slice-0-report.md`). Now Slice 1 (connectors). |
| LLM4S role in v1? | None. Direct CLI integration. |

---

## 22. What this design explicitly rejects

- Real-time webhooks. 15–60s polling.
- Worktrees. Devcontainer-incompatible.
- LLM4S in the orchestrator.
- A "manager LLM" choosing which agent does what.
- Per-session Langfuse traces.
- Direct DocSync pushes to main.
- Committing the state cache.
- Terminal `Failed` state.
- Silent proceed past uncertainty.
- Capability emulation of any kind in v1. With Slice 0 confirming both CLIs natively enforce schema, there is no v1 emulation layer. A `SchemaFallback`-shaped emulation is parked as a v2 candidate (§20) for a future non-Native CLI, and the `SchemaMechanism.SchemaFallback` enum case (§6) is reserved for it.
- Fake config knobs.
- Split per-source attempt counters.
- Pre-PR PR-number hints.
- Session ids on every FSM case.
- Bare streams for headless runs.
- A `PieceMerged` state.
- Process-lifetime branch-protection cache.
- **Implicit manifest mutations.** Every transition that annotates the manifest is named in §11.
- **`.get` on required session ids.** `requireSessionId` is the single entry point; missing → `NeedsHumanIntervention`.

Stacked PRs, auto-merge, role pluggability for arbitrary agents, GitLab — explicit v2 candidates.

---

## 23. Document conventions

- This spec is **standalone**. No section references prior version files.
- The 0.1 → 0.9 → 1.0 documents and their per-version commentaries have been retired from the tree; consolidated non-obvious rationale from that evolution lives in [`design-rationale.md`](design-rationale.md), categorised with cross-references back into this spec.
- This revision (v1.1) is the first post-1.0 update — prompted by Slice 0 findings. Future revisions (1.2, 1.3, ...) prompted by Slice 0 / Slice 1 findings or production lessons will also be standalone, sitting alongside this file as `docs/forge-design-1.x.md`.
- When a revision introduces a decision worth preserving for future contributors (e.g., a non-obvious tradeoff or a now-settled debate), add an entry to `design-rationale.md` rather than retaining a revision-specific commentary in the tree.

---

## 24. Acknowledgements

This design went through nine rounds of pre-implementation review (0.1 → 0.9 → 1.0) and one post-Slice-0 revision (1.0 → 1.1). The reviewer was consistent across rounds. The pattern that worked:

- Each commentary cited line ranges in the prior version.
- Each new version applied decisions and stated invariants once.
- The standalone-spec policy (from 0.7 onward) forced every issue to surface in one document rather than across deltas.
- Slice 0 was scoped to *validate*, not to design — any capability surprise it found became a 1.x revision, not silent triage. v1.1 is the first such revision; it folded in three corrections (Native schema on Claude, session-id preservation on resume, Codex adapter notes) without narrowing scope.

The architecture stabilised at 0.5 and has remained unchanged through 1.1. Six revisions without architectural movement is the signal that the work is done — implementation can proceed on Slice 1.
