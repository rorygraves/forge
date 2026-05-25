# Forge — design doc

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v0.2  •  **Target:** personal tool, OSS later

**Changes from v0.1:** FSM aligned with the §6 prose (adds `DesignAwaitingMerge`, `NeedsHumanIntervention`). Several `gh`/Claude CLI claims marked as hypotheses for Slice 1 to confirm. DocSync no longer pushes to main. Path conventions unified into `.forge/` (in-repo) and `~/.forge/` (install). State DB is no longer committed; log is canonical. Budget caps added. Codex review schema tightened so inline comments are producible. Recovery path added so a single failure doesn't make the feature unrecoverable. See `forge-design-0.1-commentary.md` for the rationale on each change.

---

## 0. Why "Forge"

Forge is what sits between you and the agents. You bring raw intent; the harness shapes it into a design, breaks it into pieces, hammers each piece through review and CI until it's mergeable, and remembers everything it did so the process itself can be tuned.

---

## 1. Goals and non-goals

### Goals

1. **One feature at a time, one fresh context per piece.** No long-running session accumulating 200k tokens of irrelevant history.
2. **Interactive spec phase** — sit with Claude as it explores the codebase, refine the design in dialogue, then commit a design doc when satisfied.
3. **Headless implementation phase** — once design is approved, the harness runs Claude piece-by-piece without supervision, only escalating on `AskUserQuestion` or red CI.
4. **Incremental merge** — each piece is its own branch off `main`, its own PR, its own full CI run, merged before the next piece starts. No stacked PRs.
5. **Cross-model review** — Codex reviews Claude's design and code; comments flow back into the next iteration automatically.
6. **Action log per work item** — every decision the harness or an LLM made is recorded for later review and process tuning.
7. **De-risked build order** — Codex/Claude connectors are built and validated first, the orchestrator on top.
8. **No dead-end states.** Any failure that doesn't reach the goal lands in `NeedsHumanIntervention`, which is resumable — never silently abandoned.

### Non-goals (v1)

- Parallel features. Architecture must permit it later; v1 ships serial.
- Multi-repo / monorepo split work.
- Long-running daemon on a VPS. Forge runs on Rory's laptop, lifetime = TUI session.
- Langfuse integration. The action log replaces it for now.
- Worktrees. Devcontainer-incompatible per prior experience.
- Auto-merge of design or piece PRs. v1 is human-merged at both gates; auto-merge is a v2 candidate.
- Stacked PRs. v1 is per-piece branches off main; stacked PRs are a v2 candidate (see §14).

---

## 2. Pain points being addressed

Mapped from the original problem statement:

| Pain | Fix in Forge |
|---|---|
| Manual task decomposition | Design phase produces `decomposition.md` with N pieces + a `pieces/<p>.md` detail file each; the harness drives one piece at a time |
| Context bloat across features | New `claude` subprocess per piece; no `--resume` across pieces |
| 30-min CI feedback latency | Poll `gh` every 15–60s during `AwaitingCi`/`AwaitingReview`/`DesignAwaitingMerge`; idle otherwise |
| Inconsistent branches | Branch Manager owns all git; deterministic naming `<branchPrefix>/<feature>/<piece-n>` off `main` |
| Poor /loop ergonomics | Forge IS the loop; no model is asked to monitor anything |
| No cross-model review | Codex reviews every design and every PR by default |
| Hallucinated specs | `AskUserQuestion` interception escalates to the TUI Q&A pane |
| Process opacity | Per-feature action log (`actions.jsonl`) records every decision |
| Failure leaves feature in limbo | `NeedsHumanIntervention(reason, resumeHint)` is non-terminal; `forge resume <feature>` re-enters from where it stopped |

---

## 3. Key features from the landscape worth borrowing

Reduced from the wider survey to the bits that materially shape Forge:

- **Spec Kit's slash-command structure** (`/specify`, `/plan`, `/tasks`, `/implement`) — good template for the prompts Forge gives Claude during the interactive spec phase.
- **BMAD's PRD/architecture templates** — borrow the structure of a good design doc (problem, constraints, interfaces, test strategy, decomposition).
- **Gas Town's "Refinery" role** — the conceptual idea that there's a distinct merge/integration step where someone (a model or rules) checks that pieces compose correctly. In Forge v1 this is a cheap Codex "is the design still accurate?" check after each merge — no local test re-run, since CI already validated the diff on main-equivalent state.
- **hub-team's phase model** — explicit Planning → Implementation → Testing → Review → PR phases. Forge adopts this as the FSM.
- **Devin's clarifying-question gate** — never start implementation with open spec questions. Forge enforces this: any `AskUserQuestion` raised during design must be answered and recorded in `design.md` before the FSM leaves `DesignReady`.
- **OpenAI cookbook's `codex exec --output-schema` pattern** — structured review output, no regex parsing of LLM prose.
- **Anthropic's headless isolation flag** — for headless implementation runs, suppress user-level `CLAUDE.md`/skills/hooks so behaviour is reproducible from the design doc alone. Repo-level `CLAUDE.md` is still loaded so project-specific guardrails apply. (Exact flag name TBD in Slice 1 — see §10.0.)
- **Claude Squad's permanent worktrees-as-slots idea** — adopted as "checkout slots" for the future parallel mode, but v1 uses the repo root directly.

---

## 4. Architecture

### 4.1 Component diagram

```
┌────────────────────────────────────────────────────────────────────┐
│  Forge TUI  (termflow, Elm-architecture)                           │
│                                                                    │
│  ┌──────────────────┐  ┌──────────────────────────────────────┐    │
│  │ Status pane      │  │ Active pane (one of):                │    │
│  │ • feature        │  │  - Streaming spec chat (Claude)      │    │
│  │ • current piece  │  │  - Action log tail                   │    │
│  │ • FSM state      │  │  - Q&A prompt (AskUserQuestion)      │    │
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
│   │             │    │  canonical) │    │  rebuildable)      │     │
│   └─────────────┘    └─────────────┘    └────────────────────┘     │
│           │                                                        │
│           ├── BranchManager   (git + gh, returns PrSnapshot)       │
│           ├── PRWatcher       (15–60s gh poll, only when active)   │
│           ├── SpecStore       (markdown in repo)                   │
│           ├── DocSync         (bundles decomposition updates       │
│           │                    into the next piece PR)             │
│           ├── ProcessLock     (flock on .forge/state/.lock)        │
│           └── BudgetTracker   (sums cost.update events)            │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────────────┐
│  Agent connectors                                                  │
│                                                                    │
│   ┌─────────────────────┐         ┌─────────────────────┐          │
│   │ ClaudeConnector     │         │ CodexConnector      │          │
│   │  - streaming        │         │  - exec --json      │          │
│   │  - headless (-p)    │         │  - schema-validated │          │
│   │  - stream-json I/O  │         │  - read-only sandbox│          │
│   │  - tool intercept   │         │                     │          │
│   └─────────────────────┘         └─────────────────────┘          │
└────────────────────────────────────────────────────────────────────┘
```

### 4.2 Module layout (sbt)

```
forge/
  build.sbt
  modules/
    forge-core/          ← FSM, ActionLog, StateCache, domain model, PrSnapshot ADT
    forge-agents/        ← ClaudeConnector, CodexConnector, stream-json
    forge-git/           ← BranchManager, PRWatcher (uses gh CLI, returns PrSnapshot)
    forge-specs/         ← SpecStore, DocSync
    forge-tui/           ← termflow app, panes, key bindings
    forge-app/           ← main entry point, wiring, config, ProcessLock
    forge-it/            ← integration tests with real claude/codex
```

`forge-agents` and the integration tests are the **first vertical slice** (see §10).

### 4.3 Dependencies

| Purpose | Library |
|---|---|
| TUI | `org.llm4s::termflow:0.0.1` (the published release; depend on a `0.1.0-SNAPSHOT` from `publishLocal` until 0.1.0 ships) |
| Effects | `cats-effect` 3.x |
| Streams | `fs2` |
| Process spawn / files | `com.lihaoyi::os-lib` |
| JSON | `com.lihaoyi::upickle` (or `circe` if you prefer) |
| HTTP (for future webhook mode) | `org.http4s::http4s-ember-server` (not used in v1) |
| Git status reads | shell-out to `git` + `gh`; consider `jgit` only if shell-out becomes painful |
| File locking | JDK `FileChannel.tryLock` (no extra dep) |
| Testing | `munit`, `munit-cats-effect` |

Deliberately **not** depending on `llm4s` core in v1. Forge talks to Claude/Codex via their CLIs, not via the Anthropic/OpenAI APIs directly. If a future need arises for in-process LLM calls, add `llm4s` then.

---

## 5. Domain model

```scala
opaque type FeatureId = String
opaque type PieceId   = String  // e.g. "p1", "p2"

enum BranchModel:
  case PerPieceOffMain   // v1 default
  case Stacked           // future, opt-in

enum FsmState:
  // Spec phase
  case Drafting                                              // user has named the feature, nothing written yet
  case InteractiveSpec(sessionId: String)                    // claude streaming session running, user driving
  case DesignReviewing(sessionId: String, round: Int)        // Codex reviewing design.md
  case DesignNeedsHumanInput(round: Int, questions: Vector[Question])
  case DesignAwaitingMerge(prNumber: Int)                    // design PR open, human gate
  case DesignReady                                           // design merged to main

  // Implementation phase, parameterised by piece index
  case PieceImplementing(p: PieceId, sessionId: String)
  case PieceAwaitingCi(p: PieceId, prNumber: Int)
  case PieceAwaitingReview(p: PieceId, prNumber: Int)        // CI green; awaiting Codex review + human merge
  case PieceCiFailed(p: PieceId, prNumber: Int, attempt: Int)
  case PieceReviewFailed(p: PieceId, prNumber: Int, attempt: Int)
  case PieceFixingUp(p: PieceId, prNumber: Int, attempt: Int, sessionId: String)
  case PieceAwaitingMerge(p: PieceId, prNumber: Int)         // CI green + Codex approved; human gate
  case PieceMerged(p: PieceId, prNumber: Int)
  case Refining(p: PieceId)                                  // post-merge design-validity check

  // Recovery / terminal
  case NeedsHumanIntervention(reason: String, resumeHint: ResumeHint)  // resumable
  case FeatureDone
  case Abandoned(reason: String)                                       // terminal, only on explicit user command

enum ResumeHint:
  case RetryFromState(target: FsmState)
  case AdvanceToNextPiece
  case ReopenDesign

case class Feature(
  id: FeatureId,
  title: String,
  branchPrefix: String,                  // from config; e.g. "forge"
  pieces: Vector[Piece],
  state: FsmState,
  createdAt: Instant,
  cost: CostTotals                       // running totals; budget enforcement reads this
):
  def designBranch: String = s"$branchPrefix/$id/design"

case class Piece(
  id: PieceId,
  title: String,
  acceptance: Vector[String],
  prNumber: Option[Int],
  mergedAt: Option[Instant],
  attempts: Int,                         // fix-up rounds used so far
  pollState: PollState                   // last-seen comment/check ids
):
  def branch(prefix: String, feature: FeatureId): String = s"$prefix/$feature/$id"

case class PollState(
  lastSeenCommentId: Option[Long],
  lastSeenReviewId: Option[Long],
  lastSeenCheckRunIds: Set[Long]
)

case class Question(text: String, options: Vector[String], allowFreeText: Boolean)

case class CostTotals(inputTokens: Long, outputTokens: Long, usd: Double)

case class Action(
  seq: Long,                             // monotonic per feature; replay-deterministic
  at: Instant,
  feature: FeatureId,
  piece: Option[PieceId],
  kind: String,                          // "fsm.transition", "claude.spawn", etc.
  payload: ujson.Value                   // free-form structured payload
)

// Provider-agnostic; gh-parsing lives behind BranchManager / PRWatcher.
case class PrSnapshot(
  number: Int,
  state: PrState,                        // OPEN | CLOSED | MERGED
  mergedAt: Option[Instant],
  mergeCommit: Option[String],
  requiredChecks: CheckRollup,           // narrowed to checks gating merge
  reviewDecision: Option[ReviewDecision],
  unseenComments: Vector[PrComment],     // diff'd against PollState.lastSeenCommentId
  mergeable: Option[Boolean]
)
```

Notes:
- `Piece.branch` and `Feature.designBranch` are *derived* from `branchPrefix` + ids, not stored — one source of truth.
- `attempts` lives on `Piece`, not the FSM state, so the count survives transitions in and out of `PieceFixingUp`.
- `Abandoned` is the only terminal failure state, reached only via explicit `forge abandon`.

---

## 6. The feature lifecycle in detail

### 6.0 Preconditions (every Forge command)

Before any state-changing command runs:

1. Acquire `.forge/state/.lock` via `FileChannel.tryLock`. If held, refuse with the holder's PID. `forge unlock --force` releases stale locks.
2. `BranchManager.preflight()` returns one of `clean | dirty(files) | rebasing | detached | behindMain(n)`. Forge refuses to start unless `clean`. `--force` bypasses (logged as `harness.preflight_bypassed`).
3. Replay the action log to confirm `StateCache` matches. On mismatch, prefer the log; rewrite the cache.

### 6.1 Spec phase — streaming subprocess driven by the TUI

The harness launches a streaming Claude subprocess (`claude -p --input-format stream-json --output-format stream-json --verbose`) and pipes its stdin/stdout into the TUI's spec pane. The user *experiences* this as an interactive conversation, but the underlying invocation is non-TTY streaming JSON — this matters when reading the actual command line.

When the user is satisfied, they type `/done` (intercepted by the TUI, not sent to Claude). The harness then sends one final message asking Claude to write `design.md`, `decomposition.md`, **and one `pieces/<p.id>.md` per piece** in the same turn, using `Write`. The post-check verifies that every `## Piece N: <title>` heading in `decomposition.md` has a corresponding `pieces/<p.id>.md` file before transitioning.

Why this and not the alternatives:

- A pure `AskUserQuestion`-driven structured flow is too rigid for real spec conversations where exploration of the codebase generates the right questions.
- Running `claude` fully foregrounded (taking over the terminal) loses the action-log integration and the Q&A pane.

Concretely:

1. User: `forge new "add Stripe webhook receiver"`.
2. Forge creates branch `<branchPrefix>/stripe-webhook/design` off `main`, switches the repo to it.
3. Forge spawns Claude streaming with `--append-system-prompt @~/.forge/prompts/specify.md`.
4. The pane shows Claude's streamed output and a prompt for the user. User typing goes to Claude's stdin as a user message.
5. The action log records `claude.user_message`, `claude.assistant_message` (summary only, ≤200 chars; full transcript lives in Claude's own JSONL under `~/.claude/projects/`; the action log records `sessionId` for cross-reference).
6. When the user types `/done`, Forge:
   - Sends Claude one final message: *"Produce a complete `design.md` at `.forge/specs/<feature>/design.md`, `decomposition.md` at the same dir, and one `pieces/<pn>.md` per piece referenced in `decomposition.md`. Templates: `~/.forge/templates/design.md`, `~/.forge/templates/decomposition.md`, `~/.forge/templates/piece.md`. Use `Write`, do not output content as chat."*
   - **Settle** = the next `result` event after Forge's most recent user message, with a 5-minute hard timeout. On timeout: kill the subprocess, log `harness.error`, transition to `NeedsHumanIntervention("spec settle timeout", RetryFromState(InteractiveSpec(...)))`.
   - Post-check: every piece in `decomposition.md` has a `pieces/<p.id>.md`. On failure: send one corrective message asking Claude to write the missing files; retry settle. Max 2 corrections, then `NeedsHumanIntervention`.
7. FSM → `DesignReviewing(sessionId, round = 1)`.

The streaming pane uses termflow's `Cmd`/`Sub` pattern: `Sub.fromIterator` over the stream-json output, `Cmd.send` to stdin.

### 6.2 Design review

8. Forge spawns `codex exec --json --sandbox read-only --output-schema ~/.forge/schemas/design-review.json "Review the design at .forge/specs/<feature>/design.md against the codebase. Flag blockers, suggestions, open questions."`
9. The schema is:
   ```json
   {
     "type": "object",
     "required": ["verdict", "blockers", "suggestions", "questions"],
     "properties": {
       "verdict": { "enum": ["approve", "request_changes"] },
       "blockers": {
         "type": "array",
         "items": {
           "type": "object",
           "required": ["issue"],
           "properties": {
             "path":       { "type": ["string","null"] },
             "startLine":  { "type": ["integer","null"], "minimum": 1 },
             "endLine":    { "type": ["integer","null"], "minimum": 1 },
             "issue":      { "type": "string" }
           }
         }
       },
       "suggestions": { "type": "array", "items": { "type": "string" } },
       "questions": {
         "type": "array",
         "items": {
           "type": "object",
           "required": ["text", "options"],
           "properties": {
             "text":    { "type": "string" },
             "options": { "type": "array", "items": { "type": "string" } }
           }
         }
       }
     }
   }
   ```
   Blockers with a `path` (and optional line range) become inline PR comments in §6.6. Blockers without a `path` become summary-comment bullets.
10. Codex's output is appended to `design.md` under `## Codex Review (round <n>)`.
11. If `questions` is non-empty, FSM → `DesignNeedsHumanInput(round, questions)`. The TUI's Q&A pane pops up. Answers are appended to `design.md` under `## Clarifications (round <n>)`. FSM returns to `DesignReviewing(round + 1)` for another pass.
12. If `verdict == request_changes` and `blockers` is non-empty, Forge resumes the design Claude session via `--resume <designSessionId>` (one of the Slice 1 hypotheses to confirm — see §10.0) and asks it to revise `design.md`, `decomposition.md`, and any affected `pieces/*.md` addressing the blockers. Loop back to step 8. Max `config.maxDesignReviewRounds` (default 3), then `NeedsHumanIntervention("design did not converge", ReopenDesign)`.
13. When `verdict == approve`:
    - Forge commits `design.md`, `decomposition.md`, all `pieces/*.md`, and the spec-phase slice of `actions.jsonl` to the design branch.
    - Opens a PR `[design] <feature title>` against `main` via `gh pr create --json url -q .url` (PR number parsed from the returned URL).
    - FSM → `DesignAwaitingMerge(prNumber)`.
14. **Design PR is merged by the user, not Forge.** This is the first human gate. PR-watcher polls `state == "MERGED"` (with `mergedAt` confirmation). On merge → FSM → `DesignReady`.

The design branch and design PR are deliberate: they make the design reviewable in GitHub, give CI a chance to run any linters/spell-checks/spec-checks you have on docs, and create a clean point in history before any code touches `main`.

### 6.3 Implementation phase — one piece at a time, headless

For each piece `p` in `decomposition.md` order:

15. Forge creates branch `<branchPrefix>/<feature>/<p.id>` off current `main` (which now contains the merged design).
16. Forge spawns Claude headless:
    ```
    claude -p \
      --output-format stream-json --input-format stream-json --verbose \
      <isolation-flag>                                   # see §10.0 hypothesis list
      --permission-mode acceptEdits \
      --allowedTools Read,Write,Edit,Bash,Glob,Grep,WebFetch,AskUserQuestion \
      --append-system-prompt @~/.forge/prompts/implement.md \
      <implementation prompt>
    ```
    Implementation prompt:
    > Implement piece `<p.id>` from the design at `.forge/specs/<feature>/design.md`. The piece spec is at `.forge/specs/<feature>/pieces/<p.id>.md`. Acceptance criteria are at the bottom of that file. Work to those acceptance criteria. **Do not commit — Forge will commit on your behalf when you stop.** Stop when the acceptance criteria are met.
17. Forge tails the stream-json output, recording every tool use into the action log:
    - `Read`/`Edit`/`Write`: paths only, not content.
    - `Bash`: command (first 200 chars), exit code, durationMs. Full command in Claude's JSONL.
    - `AskUserQuestion`: suspend Claude (defer the `tool_result` reply), pop the TUI Q&A pane, write the answer to `.forge/specs/<feature>/pieces/<p.id>.answers.md` (for *human* audit — fresh sessions don't see this file), then send the `tool_result` back over stdin.
18. On settle (next `result` after the last Forge message, 30-min hard timeout for implementation):
    - Forge stages everything Claude wrote and commits deterministically with message `feat(<feature>): <piece title>`. (Claude is told not to commit, removing a class of "Claude did/didn't commit" ambiguity.)
    - **DocSync** rides along: if a previous piece merged since the branch was cut, this commit also includes the `[x]` checkbox update for that previous piece in `decomposition.md`. No separate sync commit on main.
    - Pushes the branch.
    - Opens a PR via `gh pr create --json url -q .url`, parses the PR number, body rendered from `~/.forge/templates/pr-body.md.hbs` with `featureTitle, pieceTitle, pieceSummary, acceptanceCriteria, designLink`.
    - FSM → `PieceAwaitingCi(p, prNumber)`.

### 6.4 CI and review polling

19. Forge enters a poll loop (default 30s, range 15–60s) calling:
    ```
    gh pr view <n> --json state,statusCheckRollup,reviews,reviewDecision,
                            mergeable,mergeStateStatus,comments,commits,mergedAt,mergeCommit
    ```
    Parses into the provider-agnostic `PrSnapshot`. The "required check" set comes from `gh api repos/<owner>/<repo>/branches/<base>/protection/required_status_checks` (cached per repo for the session); only failures within that set drive `PieceCiFailed`. Optional/flaky checks are noted but ignored.
20. Poll transitions from `PieceAwaitingCi`:
    - All required checks green → trigger Codex code review (§6.6) → FSM → `PieceAwaitingReview`.
    - Any required check failed → fetch failed logs via `gh run view --log-failed`, increment `piece.attempts`. If `attempts <= config.maxFixupRounds` (default 3), FSM → `PieceCiFailed(attempt = attempts)` → auto-spawn fix-up (§6.5). Otherwise → `NeedsHumanIntervention("piece <p> CI did not converge", RetryFromState(PieceFixingUp(...)))`.
    - No status yet → keep polling.
21. From `PieceAwaitingReview`:
    - Codex `approve` posted → FSM → `PieceAwaitingMerge`.
    - Codex `request_changes` posted → FSM → `PieceReviewFailed` → fix-up (same `attempts` cap).
    - Human reviewer leaves `CHANGES_REQUESTED` or new comments (detected via `PollState.lastSeenCommentId`/`lastSeenReviewId`) → FSM → `PieceReviewFailed` → fix-up.
22. From `PieceAwaitingMerge`:
    - `snapshot.state == MERGED` (with `mergedAt` confirmation) → FSM → `Refining(p)` → (after Refinery) → `PieceMerged` → advance to next piece.
    - Otherwise keep polling.

    **v1 rule: piece PRs are merged by the human, not by Forge.** Forge gets the PR green, posts Codex's approving review, and waits. Once you click Merge, the next poll sees the merge and the FSM advances. This mirrors the design PR gate (§6.2).

    Two v2 candidates remain on the table (see §14): auto-merge on green+approval, and stacked PRs onto a per-feature integration branch with composite CI.

23. Polling is **only active when the FSM is in a state that cares**: `DesignAwaitingMerge`, `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceAwaitingMerge`. Other states do not poll. This keeps `gh` API budget cheap.

### 6.5 Fix-up sessions

When CI fails or review requests changes, Forge spawns a fresh Claude session (no `--resume`) with:

```
claude -p --output-format stream-json --input-format stream-json --verbose <isolation-flag> \
  --append-system-prompt @~/.forge/prompts/fixup.md \
  "Fix the failing CI/review on PR #<n>. Failure details and review comments are at \
   .forge/specs/<feature>/pieces/<p.id>.failures.md. Acceptance criteria unchanged. \
   Do not commit — Forge will commit on your behalf."
```

The session has access to the piece spec, the design, and `<p.id>.failures.md` which Forge writes with:
- The failing required CI step name + log excerpt.
- All unresolved review comments grouped by file.
- A list of *previous* failure reasons on this piece (so the model can see if it's going in circles).

When `piece.attempts > config.maxFixupRounds` (default 3), FSM → `NeedsHumanIntervention("piece <p> fix-up exhausted", RetryFromState(PieceFixingUp(...)))`. The PR is left open. `forge resume <feature>` after a human fix re-enters from `PieceAwaitingCi`.

### 6.6 Cross-piece Codex code review

Triggered when required CI checks go green on a piece PR, before merge:

```
codex exec --json --sandbox read-only \
  --output-schema ~/.forge/schemas/code-review.json \
  "Review PR #<n> on this repo. The piece spec is at .forge/specs/<feature>/pieces/<p.id>.md. \
   The diff is below. Focus on: \
   1) does it satisfy the acceptance criteria \
   2) does it match the design \
   3) obvious bugs, missing edge cases, untested paths."
```

The code-review schema mirrors design-review (§6.2 step 9) — blockers carry optional `path/startLine/endLine` so Forge can post them as inline comments. Blockers without a `path` become bullets in the summary comment.

Posting:
- `verdict == approve` → `gh pr review --approve --body "<codex summary>"`. FSM → `PieceAwaitingMerge`. In v2 with auto-merge enabled, this triggers `gh pr merge --squash --auto`.
- `verdict == request_changes` → for each blocker with a `path`: `gh api ... /pulls/<n>/comments` with `path`/`line`. One summary comment with `gh pr review --request-changes --body`. FSM → `PieceReviewFailed`.

Per-feature config disables review (`codex.review.code = false`) for sensitive features.

### 6.7 The Refinery step

After each piece merges, Forge runs the cheap part of the Refinery:

```scala
def refine(feature: Feature, justMerged: Piece): IO[RefineResult] =
  for
    _ <- BranchManager.checkoutMainAtHead
    r <- CodexConnector.refineCheck(
           designPath = ".forge/specs/<f>/design.md",
           decompPath = ".forge/specs/<f>/decomposition.md",
           justMerged = justMerged.id,
           remainingPieces = feature.pieces.filter(_.mergedAt.isEmpty)
         )
  yield r
```

There is no local test re-run: the merged PR already had required CI green, so the test signal is identical to "compile and test on main". The Refinery's only job is the design-validity check.

The Codex refine schema (`~/.forge/schemas/refine.json`):
```json
{
  "type": "object",
  "required": ["verdict", "deltaPieces"],
  "properties": {
    "verdict": { "enum": ["no_change", "update_decomposition", "reopen_design"] },
    "rationale": { "type": "string" },
    "deltaPieces": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["pieceId", "change"],
        "properties": {
          "pieceId": { "type": "string" },
          "change":  { "enum": ["unchanged", "edit", "remove", "add"] },
          "note":    { "type": "string" }
        }
      }
    }
  }
}
```

- `no_change` → silently continue.
- `update_decomposition` → Q&A prompt offers (a) auto-update via a Claude session, (b) defer until after the next piece, (c) pause and reopen design.
- `reopen_design` → FSM → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign)`.

This is the antidote to "the plan and reality drift apart" — explicit, recorded in the action log, never silent.

---

## 7. Source of truth for the work breakdown

**Hybrid, with bidirectional sync:**

| Lives where | What | Committed? |
|---|---|---|
| `.forge/specs/<feature>/design.md` | The design itself. Source of truth for design intent. | Yes |
| `.forge/specs/<feature>/decomposition.md` | Ordered list of pieces, each with title, summary, acceptance criteria. Updated by DocSync. | Yes |
| `.forge/specs/<feature>/pieces/<pn>.md` | One file per piece: detail spec + acceptance. Produced in the spec consolidation step (§6.1). | Yes |
| `.forge/log/<feature>.jsonl` | Append-only action log. **Canonical record.** | Yes |
| `.forge/state/<feature>.json` | FSM state cache. Reconstructible from the log via `forge rebuild-state`. | **No (gitignored)** |
| `.forge/state/.lock` | Process lock. | No (gitignored) |

**Invariant:** the log is canonical; the state file is a cache. On every Forge startup, the cache is verified against a log replay and rewritten if divergent. This eliminates the "committed state drifted across branches" failure mode in v0.1.

**Sync rules** (the DocSync component):

- When a piece transitions to `PieceMerged`, DocSync does **not** push to main. Instead, the next piece's implementation commit (§6.3 step 18) includes the `[x]` checkbox update with PR number and merge SHA appended. No extra PR.
- For the final piece, a single `chore(<feature>): mark feature complete` PR closes out the decomposition checklist. (One PR per feature, not per piece.)
- When the user manually edits `decomposition.md` while Forge is idle, Forge detects the diff on the next `forge` command via a content hash stored in `.forge/state/<feature>.json` and asks via Q&A pane: "I see decomposition has changed. Treat the new piece P5 as part of this feature, or as separate?"
- The state cache never holds piece *content* — only piece *status*. Content is re-read from Markdown on every read so a user edit takes effect immediately.

This is the "build sync into the process" approach: mechanical, predictable, observable in the action log, and respects branch protection.

---

## 8. The action / choices log

Newline-delimited JSON per feature, append-only. Schema (matches the case class in §5):

```json
{
  "seq": 142,
  "ts": "2026-05-24T15:42:18.341Z",
  "feature": "stripe-webhook",
  "piece": "p2",
  "kind": "fsm.transition",
  "payload": {
    "from": "PieceImplementing",
    "to":   "PieceAwaitingCi",
    "prNumber": 4291,
    "branch": "forge/stripe-webhook/p2"
  }
}
```

`seq` is monotonic per feature, used for replay-deterministic state reconstruction. All extras live in `payload` — no top-level overflow.

`kind` values (initial set):

- `fsm.transition` — every state change. Always logged.
- `claude.spawn` — `{ argv, sessionId, mode: "streaming" | "headless" }`.
- `claude.user_message` — summary only (≤200 chars).
- `claude.assistant_text` — summary only (≤200 chars + token count). Full content in Claude's JSONL under `~/.claude/projects/`; cross-reference via `sessionId`.
- `claude.tool_use` — `{ tool, target }`. For `Bash`: `{ tool: "Bash", commandSummary: "...", exitCode, durationMs }` — full command in Claude's JSONL.
- `claude.ask_user_question` — full question, full answer (this is a user-curated record, not bulk transcript).
- `codex.exec` — `{ argv, durationMs, exitCode, schemaPath }`.
- `codex.review` — `{ kind: "design"|"code"|"refine", verdict, blockerCount, suggestionCount, questionCount, fullReviewPath }`.
- `gh.poll` — `{ pr, state, requiredCheckRollup, reviewDecision }`. Sampled (every 5th poll). Transitions always logged in full.
- `gh.action` — `{ verb, args }` for any mutating `gh` call (create PR, post comment).
- `user.command` — TUI key bindings the user invoked.
- `harness.error` — exceptions with stack trace.
- `harness.preflight_bypassed` — `--force` use, with reason.
- `cost.update` — `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd }`. BudgetTracker reads these.

The TUI has a "tail action log" pane that pretty-prints the last N entries with colour. `forge replay <feature>` re-prints the log as a narrative.

Why this matters: when a feature goes wrong, the action log is the ground truth for *what the system did and why*. Pairs with the Claude/Codex transcript files for *what the models said*. The combination is what you tune the prompts against.

---

## 9. Polling-based GitHub integration

Deliberately simple, and provider-agnostic at the type boundary:

```scala
trait PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot]

class GhPollingWatcher(intervalMs: Int = 30000) extends PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot] =
    Stream
      .awakeEvery[IO](intervalMs.millis)
      .evalMap(_ => IO.blocking {
        os.proc("gh","pr","view",pr.toString,
          "--json","state,statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments,commits,mergedAt,mergeCommit"
        ).call().out.text()
      })
      .map(parseGhJsonToPrSnapshot)   // gh-specific parsing lives here only
      .changes                         // emit only on diff against previous snapshot
```

The FSM consumes `PrSnapshot`. The `gh` JSON parse and the "merged" interpretation (`state == "MERGED"`, confirmed by `mergedAt`) live entirely inside `forge-git`. A future GitLab adapter is a parallel parser feeding the same ADT — no FSM changes.

- Default interval: **30s**.
- Configurable per repo via `.forge/config.json` (`pollIntervalMs`).
- Polling active only in `DesignAwaitingMerge | PieceAwaitingCi | PieceAwaitingReview | PieceAwaitingMerge`.
- Required-check rollup is computed by intersecting `statusCheckRollup` with the branch-protection required-check list (fetched once per repo per session via `gh api`).
- Rate budget: at 30s and a few polls per state, a 4-piece feature uses ~100–200 `gh` calls. Comfortable margin under any reasonable limit.

`.changes` means the FSM only sees events when something actually changed — clean for state transition logic. The action log samples raw polls (1 in 5).

---

## 10. Build order (de-risked)

### 10.0 Slice 0 — Validate CLI assumptions (days, before any Scala)

Before Slice 1 starts, validate every CLI claim the design relies on. Failures here change the design, not just the implementation.

Checklist:

1. **`claude -p --input-format stream-json --output-format stream-json --verbose`** works as a long-lived streaming subprocess; stdin lines are interpreted as user messages; `result` events fire per-turn.
2. **Isolation flag** that suppresses user-level `CLAUDE.md`/skills/hooks but preserves repo-level `CLAUDE.md` exists. Identify the actual flag name (current draft assumes `--bare`; confirm or replace). Confirm interaction with `--permission-mode acceptEdits` and `--allowedTools`.
3. **`--resume <sessionId>`** works on a session that was started in stream-json mode (used in §6.2 step 12).
4. **`--append-system-prompt @file`** works alongside the above flags.
5. **`AskUserQuestion` tool-use suspension**: confirm that deferring the `tool_result` reply pauses the model without timeout for at least N minutes (long enough for a human to respond).
6. **`codex exec --json --output-schema <file> --sandbox read-only`** returns schema-conformant JSON in both `approve` and `request_changes` paths.
7. **`gh pr view --json state,...`** returns `state: "MERGED"` after a real merge and `mergedAt` is non-null; `mergeStateStatus` is *not* relied on for merge detection.
8. **`gh api repos/.../branches/main/protection/required_status_checks`** returns the required set for branch-protected repos and returns the expected 404/empty for unprotected ones.

Output: a `slice-0-report.md` recording observed behaviour and the *exact pinned versions* of `claude` and `codex` used. Each subsequent slice's tests pin these versions.

### 10.1 Slice 1 — Agent connectors (week 1)

Build `forge-agents` standalone, with a CLI demo and integration tests.

- `ClaudeConnector` with:
  - `runHeadless(prompt, systemPrompt, allowedTools): Stream[IO, ClaudeEvent]`
  - `runStreaming(systemPrompt): StreamingSession` exposing input sink + output stream
  - Tool intercept callback for `AskUserQuestion`
  - Session id capture from the `system/init` event
- `CodexConnector` with:
  - `exec[T](prompt, schema: JsonSchema[T]): IO[T]` returning a decoded, schema-validated result
  - Sandbox mode selection
- An integration test that:
  - Runs Claude headless on "create a hello world Scala file in /tmp/forge-test"
  - Asserts file exists, action log emitted >0 events, session JSONL exists
  - Runs Codex review on a known-good and known-bad markdown file, asserts schema-conformant verdict in both directions
- Settle semantics encoded as a typed `Settled` event with timeout.

**Why first:** if stream-json contracts or schema-constrained Codex output don't behave as Slice 0 expects, everything downstream is dead.

### 10.2 Slice 2 — FSM, ActionLog, StateCache (week 2)

Plain Scala, no external agents, no git.

- `forge-core`: FSM as `(FsmState, FsmEvent) => (FsmState, List[ActionLogEntry])`.
- `StateCache` using atomic file writes (`os.write.over` to temp then `os.move`).
- `ActionLog` appending to `.forge/log/<feature>.jsonl`.
- `forge rebuild-state <feature>` command: replays log → state cache.
- Property tests:
  - Any sequence of events produces a valid state.
  - Replaying the action log reproduces the final state cache.
  - `NeedsHumanIntervention` always carries a `ResumeHint` and `forge resume` produces a legal next state.

**Why second:** the FSM is small enough that getting it right on paper before wiring agents to it is cheap insurance.

### 10.3 Slice 3 — BranchManager + PRWatcher + ProcessLock (week 3)

- Shell out to `git` and `gh`. No magic.
- BranchManager: create design branch, create piece branch, push, create PR (parsing number from `gh pr create --json url`), preflight checks.
- PRWatcher: 30s poller, fs2 stream, returns `PrSnapshot` ADT.
- ProcessLock: `FileChannel.tryLock` on `.forge/state/.lock`, with `forge unlock --force`.
- Integration test against a sacrificial test repo on GitHub: create a branch, push a commit, open a PR, observe the watcher emit the right transitions through merge.

**Why third:** with Slices 1 and 2 done, this is the last piece of plumbing before the FSM can drive real work end-to-end.

### 10.4 Slice 4 — Headless feature loop with line-mode REPL (week 4)

Wire Slices 1–3 together. **No TUI yet** — drive from a CLI:

```
forge new "feature title"       → starts the FSM at Drafting
forge spec <feature>            → line-mode REPL: stdin lines → Claude user messages,
                                  stdout pretty-prints assistant text + tool uses,
                                  /done and AskUserQuestion intercepted here
forge run <feature>             → headless from DesignReady through to FeatureDone
forge status [<feature>]        → prints FSM state + action log tail
forge resume <feature>          → re-enter from NeedsHumanIntervention
forge abandon <feature>         → terminal Abandoned
forge rebuild-state <feature>   → replay log → state cache
forge unlock --force            → release stale lock
```

The line-mode REPL is the reusable intercept layer that Slice 5's TUI also drives. It exists before the TUI so the TUI can be a *view*, not a coupling.

By end of week 4 you can drive a real small feature through Forge from the command line. **First version that's actually useful.**

### 10.5 Slice 5 — TUI (week 5)

Build the termflow UI as a richer view over the Slice 4 hooks:

- Echo the action log to a pane.
- Replace the line-mode REPL with the in-TUI streaming Claude pane (same intercepts).
- Q&A pane for AskUserQuestion.
- Keyboard shortcuts for pause/resume/cancel.
- Cost + budget gauge in the status pane.

TUI last because it's the most subjective. Building it last means iterating on UX against a working system.

---

## 11. Configuration

`.forge/config.json` per repo:

```json
{
  "pollIntervalMs": 30000,
  "maxFixupRounds": 3,
  "maxDesignReviewRounds": 3,
  "maxFeatureCostUsd": 25.00,
  "maxPieceCostUsd": 8.00,
  "branchPrefix": "forge",
  "claude": {
    "model": "default",
    "permissionMode": "acceptEdits",
    "allowedTools": ["Read","Write","Edit","Bash","Glob","Grep","WebFetch","AskUserQuestion"],
    "isolationFlag": "auto",                       // resolves to whatever Slice 0 identifies
    "additionalSystemPrompt": "~/.forge/prompts/implement.md"
  },
  "codex": {
    "sandbox": "read-only",
    "review": { "design": true, "code": true, "refine": true }
  },
  "settle": {
    "specTimeoutSec": 300,
    "implementTimeoutSec": 1800,
    "fixupTimeoutSec": 900
  },
  "logRetention": "keep"
}
```

Global config at `~/.config/forge/config.json` for per-user overrides.

Templates and prompts live under `~/.forge/templates/` and `~/.forge/prompts/` (shipped with Forge install). A repo can override any file by mirroring its path under `.forge/overrides/` — useful for tightening the implementation prompt for a specific codebase.

---

## 12. Risks and open questions

| Risk | Mitigation |
|---|---|
| Claude `--input-format stream-json` schema drift between versions | Slice 0 + Slice 1 contract tests pinned to a specific `claude` version |
| Mid-turn stdin not persisted to JSONL (#41230) | Always also write AskUserQuestion answers to a Markdown file under `.forge/specs/...`. Documented as human-audit only |
| Codex schema-constrained output not strict enough | Validate locally with a JSON schema validator; on validation failure, log + retry with a "your last output did not match the schema" prompt, max 2 retries |
| Termflow is 0.0.1 with rendering improvements still WIP | Slice 4's line-mode REPL is a working `forge --no-tui` mode in its own right |
| `gh` rate limits during heavy polling | 30s default well under limits; configurable; back off on 403/429 |
| Anthropic subscription restrictions (April 4 2026 change) | Document that Forge supports both Pro/Max subscription and API-key modes via `ANTHROPIC_API_KEY` |
| Forge's own state cache out of sync with reality | Log is canonical; cache is rebuilt from log on every startup |
| Devcontainer + repo manipulation | v1 runs on the host, not inside the devcontainer |
| Branch protection rejects DocSync push to main | DocSync rides on the next piece PR; no direct push to main |
| Runaway fix-up cost | `maxFeatureCostUsd` and `maxPieceCostUsd` enforced by BudgetTracker; over-budget → `NeedsHumanIntervention` |
| Two `forge` processes on the same repo race the state cache | ProcessLock (flock on `.forge/state/.lock`); refuse start if held |
| User left uncommitted changes when invoking Forge | BranchManager preflight refuses non-clean working copy unless `--force` |

### Open questions to settle before/during Slice 1

1. **GitLab support.** v1 targets GitHub only. The `PrSnapshot` ADT and `BranchManager`/`PRWatcher` interfaces are designed for swap-in. Defer until v2.
2. **Cost tracking accuracy.** Claude `result` events and Codex JSON both emit cost. Slice 1 confirms both formats; Slice 2 introduces a unified `Cost` type.
3. **Action log retention / privacy.** Log is committed by default; Bash commands are truncated to 200 chars in the log (full in Claude's JSONL). Make `.forge/log/` gitignore opt-out for sensitive repos.
4. **Manually-driven pieces.** `forge piece skip <p>` marks a piece as manually completed; FSM advances on next poll when it sees the branch merged.
5. **The exact isolation flag.** Slice 0 to identify. Until then, config uses `"isolationFlag": "auto"`.

---

## 13. What this design explicitly rejects

- **Real-time webhooks.** 15–60s polling is fine and removes a deployment dependency.
- **Worktrees.** Devcontainer-incompatible.
- **LLM4S in the orchestrator.** Forge talks to Claude/Codex via their CLIs. Revisit post-v1.
- **A "manager LLM" choosing which agent does what.** The orchestrator is deterministic Scala.
- **Per-session Langfuse traces.** The action log replaces this for v1.
- **Direct DocSync pushes to main.** Branch-protection-unfriendly; DocSync rides on the next piece PR instead.
- **Committing the state cache.** Log is canonical; state is a rebuildable projection.
- **Terminal `Failed` state.** Any non-success that isn't an explicit abandon lands in `NeedsHumanIntervention` and is resumable.

Stacked PRs and auto-merge are **not** rejected — they're v1 non-goals and explicit v2 candidates (§14).

---

## 14. v2 candidates and future work

### 14.1 v2 merge model — decide after lived experience

After v1 has shepherded a few real features end-to-end, pick one based on actual pain:

**Option A: Auto-merge on green + Codex approval.**

- Add `autoMergeOnGreenAndApproved: true` to `.forge/config.json`.
- FSM transition: `PieceAwaitingMerge` with all-required-green + Codex `approve` triggers `gh pr merge --squash --auto`.
- Per-feature override (`autoMerge: "never" | "on-approval" | "manual"`) for high-risk features.
- TUI shows a "auto-merging in 10s, press space to cancel" countdown for the first few merges.
- **Pick this if** v1 friction is "clicking Merge on PRs that are clearly fine".

**Option B: Stacked PRs onto a per-feature integration branch with composite CI.**

- Forge opens a single `<branchPrefix>/<feature>/integration` PR against `main` at the start of the feature.
- Each piece PR targets the integration branch.
- CI runs on the integration PR after every piece merge — full feature surface every time.
- When the last piece merges to the integration branch and CI is green, the integration PR is ready to merge to `main`.
- **Pick this if** v1 friction is "each piece PR rerunning the same 15-minute CI from scratch".

**These options are not mutually exclusive.** A reasonable v2 endpoint: stacked PRs + auto-merge on piece PRs once Codex approves + human gate on the integration → main merge. The integration-branch approach also makes the Refinery cheaper because main isn't disturbed mid-feature.

### 14.2 Other future work (no decision needed yet)

- Parallel features across multiple checkout slots.
- GitLab adapter behind the `BranchManager` / `PRWatcher` interfaces (parallel parser into the same `PrSnapshot` ADT).
- LLM4S-based in-process helpers (transcript summarisation, comment classification).
- Contributing `ClaudeConnector` / `CodexConnector` back to LLM4S as the seed of an "external agent CLI" module.
- Webhook mode for sub-second reactivity.
- "Process tuning" mode: feed past features' action logs to an LLM and ask it to suggest prompt or FSM improvements.
- Optional Langfuse integration (`TRACING_MODE=langfuse`).

---

## 15. Decision summary

| Question | Decision |
|---|---|
| Adopt or build? | **Build.** Forge, a Scala harness. |
| Drive Claude how? | **Subprocess + stream-json**, not in-process SDK. |
| Drive Codex how? | **`codex exec --json --output-schema`** for structured review/refine. |
| TUI library? | **termflow** (llm4s/termflow), Elm-architecture, Scala 3. |
| Effect system? | **cats-effect 3 + fs2**. |
| Branch model v1? | **One branch per piece off main**; design has its own branch+PR merged first. |
| Merge gates v1? | **Human-merged at both gates** (design PR and each piece PR). |
| Merge gates v2 candidates? | **(a)** auto-merge on green+approval, or **(b)** stacked PRs onto integration branch with composite CI. Decide after lived experience. |
| GitHub integration? | **Polling `gh` every 30s** only when FSM is awaiting CI/review/merge. State signal: `state == "MERGED"` + `mergedAt`. |
| Required vs optional checks? | Honour branch protection; fix-up only on required-check failures. |
| Failure recovery? | **`NeedsHumanIntervention(reason, resumeHint)`** is non-terminal; `forge resume` re-enters. `Abandoned` requires explicit `forge abandon`. |
| Work breakdown SoT? | **Hybrid:** design/decomposition/piece markdowns in repo, log canonical, state cache rebuildable. DocSync rides on next piece PR — no direct push to main. |
| Autonomy between pieces? | **Headless implementation.** Forge writes code, opens PR, gets Codex approval; you click Merge. Escalates only on `AskUserQuestion`, red required CI, or budget breach. |
| Who commits? | **Forge commits, not Claude.** Removes "did Claude commit / with what message" ambiguity. |
| Refinery step? | **In v1, Codex-only.** No local test re-run; CI on the merged PR was sufficient signal. |
| Observability? | **Per-feature action log** (`.forge/log/<feature>.jsonl`, canonical, monotonic `seq`). |
| Concurrency? | **Single Forge process per repo**, enforced by `flock` on `.forge/state/.lock`. |
| Pre-flight? | **Hard precondition**: clean working copy or `--force`. |
| Cost cap? | `maxFeatureCostUsd` and `maxPieceCostUsd`; breach → `NeedsHumanIntervention`. |
| First thing to build? | **Slice 0:** validate CLI assumptions. **Slice 1:** agent connectors with integration tests pinned to specific Claude/Codex versions. |
| LLM4S role in v1? | **None.** Direct CLI integration. Revisit post-v1. |
