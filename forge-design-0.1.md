# Forge — design doc

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** draft v1  •  **Target:** personal tool, OSS later

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
| Manual task decomposition | Design phase produces `decomposition.md` with N pieces; the harness drives one piece at a time |
| Context bloat across features | New `claude` subprocess per piece; no `--resume` across pieces |
| 30-min CI feedback latency | Poll `gh` every 15–60s during `AwaitingCi`/`AwaitingReview`; idle otherwise |
| Inconsistent branches | Branch Manager owns all git; deterministic naming `forge/<feature>/<piece-n>` off `main` |
| Poor /loop ergonomics | Forge IS the loop; no model is asked to monitor anything |
| No cross-model review | Codex reviews every design and every PR by default |
| Hallucinated specs | `AskUserQuestion` interception escalates to the TUI Q&A pane |
| Process opacity | Per-feature action log (`actions.jsonl`) records every decision |

---

## 3. Key features from the landscape worth borrowing

Reduced from the wider survey to the bits that materially shape Forge:

- **Spec Kit's slash-command structure** (`/specify`, `/plan`, `/tasks`, `/implement`) — good template for the prompts Forge gives Claude during the interactive spec phase.
- **BMAD's PRD/architecture templates** — borrow the structure of a good design doc (problem, constraints, interfaces, test strategy, decomposition).
- **Gas Town's "Refinery" role** — the conceptual idea that there's a distinct merge/integration step where someone (a model or rules) checks that pieces compose correctly. Forge implements this as a deterministic step that re-runs the design's acceptance checks against `main` after each piece merges.
- **hub-team's phase model** — explicit Planning → Implementation → Testing → Review → PR phases. Forge adopts this as the FSM.
- **Devin's clarifying-question gate** — never start implementation with open spec questions. Forge enforces this: any `AskUserQuestion` raised during design must be answered and recorded in `design.md` before the FSM leaves `DesignReady`.
- **OpenAI cookbook's `codex exec --output-schema` pattern** — structured review output, no regex parsing of LLM prose.
- **Anthropic's `--bare` flag** — for headless implementation runs, suppress user-level CLAUDE.md/skills/hooks so behaviour is reproducible from the design doc alone.
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
│  │ • feature        │  │  - Interactive spec chat             │    │
│  │ • current piece  │  │  - Action log tail                   │    │
│  │ • FSM state      │  │  - Q&A prompt (AskUserQuestion)      │    │
│  │ • tokens / $     │  │  - Idle / awaiting CI                │    │
│  │ • last action    │  │                                      │    │
│  └──────────────────┘  └──────────────────────────────────────┘    │
└──────────────────────────────┬─────────────────────────────────────┘
                               │  Sub[Msg] / Cmd[Msg]
┌──────────────────────────────┴─────────────────────────────────────┐
│  Orchestrator (pure Scala)                                         │
│                                                                    │
│   ┌─────────────┐    ┌─────────────┐    ┌────────────────────┐     │
│   │ FSM         │    │ ActionLog   │    │ StateStore         │     │
│   │ (per-feat.) │───▶│ (.jsonl)    │    │ (.harness/state/)  │     │
│   └─────────────┘    └─────────────┘    └────────────────────┘     │
│           │                                                        │
│           ├── BranchManager   (git + gh, jgit for status)          │
│           ├── PRWatcher       (15–60s gh poll, only when active)   │
│           ├── SpecStore       (markdown in repo + state DB)        │
│           └── DocSync         (keeps decomposition.md in sync)     │
└──────────────────────────────┬─────────────────────────────────────┘
                               │
┌──────────────────────────────┴─────────────────────────────────────┐
│  Agent connectors                                                  │
│                                                                    │
│   ┌─────────────────────┐         ┌─────────────────────┐          │
│   │ ClaudeConnector     │         │ CodexConnector      │          │
│   │  - interactive      │         │  - exec --json      │          │
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
    forge-core/          ← FSM, ActionLog, StateStore, domain model
    forge-agents/        ← ClaudeConnector, CodexConnector, stream-json
    forge-git/           ← BranchManager, PRWatcher (uses gh CLI)
    forge-specs/         ← SpecStore, DocSync
    forge-tui/           ← termflow app, panes, key bindings
    forge-app/           ← main entry point, wiring, config
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
| Testing | `munit`, `munit-cats-effect` |

Deliberately **not** depending on `llm4s` core in v1. Forge talks to Claude/Codex via their CLIs, not via the Anthropic/OpenAI APIs directly. If a future need arises for in-process LLM calls (e.g., extracting structured questions from a transcript), add `llm4s` then.

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
  case Drafting                                    // user has named the feature, nothing written yet
  case InteractiveSpec(sessionId: String)          // claude session running, user driving
  case DesignReviewing(sessionId: String)          // Codex reviewing design.md
  case DesignNeedsHumanInput(questions: Vector[Question])
  case DesignReady                                 // design.md + decomposition.md committed on design branch

  // Implementation phase, parameterised by piece index
  case PieceImplementing(p: PieceId, sessionId: String)
  case PieceAwaitingCi(p: PieceId, prNumber: Int)
  case PieceAwaitingReview(p: PieceId, prNumber: Int)
  case PieceCiFailed(p: PieceId, prNumber: Int, log: String)
  case PieceReviewFailed(p: PieceId, prNumber: Int, comments: Vector[ReviewComment])
  case PieceFixingUp(p: PieceId, prNumber: Int, sessionId: String)
  case PieceMerged(p: PieceId, prNumber: Int)

  // Terminal
  case FeatureDone
  case Failed(reason: String)

case class Feature(
  id: FeatureId,
  title: String,
  designBranch: String,                  // "forge/<id>/design"
  pieces: Vector[Piece],
  state: FsmState,
  createdAt: Instant
)

case class Piece(
  id: PieceId,
  title: String,
  acceptance: Vector[String],            // bullet acceptance criteria
  branch: String,                        // "forge/<feature>/<piece>"
  prNumber: Option[Int],
  mergedAt: Option[Instant]
)

case class Question(text: String, options: Vector[String], allowFreeText: Boolean)

case class Action(
  at: Instant,
  feature: FeatureId,
  piece: Option[PieceId],
  kind: ActionKind,        // "fsm.transition", "claude.spawn", "codex.review", ...
  payload: ujson.Value,    // free-form structured payload
  cost: Option[Cost]       // tokens + $ when known
)
```

---

## 6. The feature lifecycle in detail

### 6.1 Spec phase — interactive

The hardest decision was how to wire the spec conversation. The choice: **the harness launches an interactive Claude session inside a TUI pane, the user converses freely, and when satisfied the user types `/done`. The harness then reads the session's JSONL and asks Claude (in a fresh, non-interactive turn) to consolidate the conversation into `design.md` + `decomposition.md`.**

Why this and not the alternatives:

- A pure `AskUserQuestion`-driven structured flow is too rigid for real spec conversations where exploration of the codebase generates the right questions.
- Running `claude` fully foregrounded (taking over the terminal) loses the action-log integration and the Q&A pane.

Concretely:

1. User: `forge new "add Stripe webhook receiver"`
2. Forge creates branch `forge/stripe-webhook/design` off `main`, switches the repo to it.
3. Forge spawns `claude -p --output-format stream-json --input-format stream-json --verbose --append-system-prompt @forge/prompts/specify.md` and pipes its stdin/stdout into the TUI's spec pane.
4. The pane shows Claude's streamed output and a prompt for the user. User typing goes to Claude's stdin as a user message.
5. The action log records `claude.user_message`, `claude.assistant_message` (text only, not full transcripts — those stay in the JSONL Claude itself writes).
6. When the user types `/done` in the pane (not sent to Claude), Forge:
   - Sends Claude one final message: *"Produce a complete `design.md` at `.forge/specs/<feature>/design.md` and `decomposition.md` at the same dir. Use the templates at `forge/templates/design.md` and `forge/templates/decomposition.md`. Use `Write`, do not output design content as chat."*
   - Waits for the session to settle (final `result` event).
   - Verifies both files exist, parses `decomposition.md` for `## Piece N: <title>` headings, populates the `pieces` field on the Feature.
7. FSM → `DesignReviewing`.

The interactive pane uses termflow's `Cmd`/`Sub` pattern: `Sub.fromIterator` over the stream-json output, `Cmd.send` to stdin.

### 6.2 Design review

8. Forge spawns `codex exec --json --sandbox read-only --output-schema forge/schemas/design-review.json "Review the design at .forge/specs/<feature>/design.md against the codebase. Flag blockers, suggestions, open questions."`
9. The schema is:
   ```json
   {
     "type":"object",
     "required":["verdict","blockers","suggestions","questions"],
     "properties":{
       "verdict":{"enum":["approve","request_changes"]},
       "blockers":{"type":"array","items":{"type":"object","required":["where","issue"],"properties":{"where":{"type":"string"},"issue":{"type":"string"}}}},
       "suggestions":{"type":"array","items":{"type":"string"}},
       "questions":{"type":"array","items":{"type":"object","required":["text","options"],"properties":{"text":{"type":"string"},"options":{"type":"array","items":{"type":"string"}}}}}
     }
   }
   ```
10. Codex's output is appended to `design.md` under `## Codex Review (round <n>)`.
11. If `questions` is non-empty, FSM → `DesignNeedsHumanInput`; the TUI's Q&A pane pops up with the questions and options. The user's answers are appended to `design.md` under `## Clarifications (round <n>)`. FSM returns to `DesignReviewing` for one more pass.
12. If `verdict == request_changes` and `blockers` is non-empty, Forge restarts Claude with `--resume <designSessionId>`, asks it to revise `design.md` and `decomposition.md` addressing the blockers, then loops back to step 8. Max 3 review rounds, then bail to `Failed("design did not converge")`.
13. When `verdict == approve`, Forge commits `design.md`, `decomposition.md`, and `actions.jsonl` (the spec-phase slice) to the design branch and opens a PR `[design] <feature title>` against `main`.
14. **The design PR is merged by the user, not Forge.** This is the human gate before any implementation runs. Once merged, Forge detects the merge on its next 60s poll and transitions to `DesignReady`.

The design branch and design PR are deliberate: they make the design reviewable in GitHub, give CI a chance to run any linters/spell-checks/spec-checks you have on docs, and create a clean point in history before any code touches `main`.

### 6.3 Implementation phase — one piece at a time, headless

For each piece `p` in `decomposition.md` in order:

15. Forge creates branch `forge/<feature>/<piece-id>` off the current `main` (which now contains the merged design).
16. Forge spawns:
    ```
    claude -p \
      --output-format stream-json --input-format stream-json --verbose \
      --bare \
      --permission-mode acceptEdits \
      --allowedTools Read,Write,Edit,Bash,Glob,Grep,WebFetch,AskUserQuestion \
      --append-system-prompt @forge/prompts/implement.md \
      <implementation prompt>
    ```
    The implementation prompt is:
    > Implement piece `<p.id>` from the design at `.forge/specs/<feature>/design.md`. The piece spec is at `.forge/specs/<feature>/pieces/<p.id>.md`. Acceptance criteria are at the bottom of that file. Work to those acceptance criteria. When complete, commit with message `feat(<feature>): <piece title>` and stop.
17. Forge tails the stream-json output, recording every tool use into the action log. Specifically:
    - `Read`/`Edit`/`Write` events get logged with paths only (not content).
    - `Bash` events get logged with command + exit code.
    - `AskUserQuestion` events suspend Claude (do not return tool_result), pop the TUI Q&A pane, write the answer to `.forge/specs/<feature>/pieces/<p.id>.answers.md` (because mid-turn stdin is not persisted to JSONL), and then send the tool_result back over stdin.
18. When the session emits `result` and there's a new commit on the piece branch, Forge:
    - Pushes the branch.
    - Opens a PR with `gh pr create --base main --head forge/<feature>/<p.id> --title "feat(<feature>): <piece title>" --body <generated from pieces/<p.id>.md + acceptance>`.
    - FSM → `PieceAwaitingCi`.

### 6.4 CI and review polling

19. Forge enters a 30s poll loop (configurable 15–60s, default 30) calling:
    ```
    gh pr view <n> --json statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments
    ```
20. Poll transitions:
    - All checks green → trigger Codex code review → FSM → `PieceAwaitingReview`.
    - Any check failed → fetch failed logs via `gh run view --log-failed`, FSM → `PieceCiFailed`, then auto-spawn a fix-up Claude session (see §6.5) within seconds.
    - No status yet → keep polling.
21. In `PieceAwaitingReview`:
    - If `reviewDecision == CHANGES_REQUESTED` or new comments since last seen → FSM → `PieceReviewFailed` → auto-spawn fix-up session.
    - If the PR is merged (`mergeStateStatus == "MERGED"`) → FSM → `PieceMerged`, advance to next piece.
    - Otherwise keep polling.

    **v1 rule: piece PRs are merged by you, not by Forge.** Forge gets the PR green, gets Codex's approving review posted on it, and waits. Once you click Merge, the next poll sees the merge and the FSM advances. This mirrors the design PR gate (§6.2) and gives you a checkpoint between every piece.

    Two v2 candidates for reducing this friction, decided after lived experience:

    **(a) Auto-merge.** Add `autoMergeOnGreenAndApproved: true` to `.forge/config.json`. When set, `PieceAwaitingReview` with all checks green and Codex approval triggers `gh pr merge --squash --auto`. Strongly recommend keeping a per-feature override so high-risk features can stay human-gated.

    **(b) Stacked PRs with composite CI.** Either fix CI to run on the stacked PRs themselves, or have Forge open a per-feature base PR (`forge/<feature>/integration`) that all piece PRs stack on, and let CI run on the integration branch. Pieces merge to the integration branch (which itself never lands until the feature is done), so CI on the integration PR exercises the full feature surface at every step. This solves your "CI doesn't run fully on stacked PRs" objection by routing CI through a single integration branch rather than per-piece-stack.

22. Polling is **only active when the FSM is in `PieceAwaitingCi` or `PieceAwaitingReview`**. Idle states (e.g., human is doing the design review) do not poll. This keeps `gh` API budget cheap.

### 6.5 Fix-up sessions

When CI fails or review requests changes, Forge spawns a fresh Claude session (no `--resume`) with:

```
claude -p --output-format stream-json --input-format stream-json --verbose --bare \
  --append-system-prompt @forge/prompts/fixup.md \
  "Fix the failing CI/review on PR #<n>. Failure details and review comments are at \
   .forge/specs/<feature>/pieces/<p.id>.failures.md. Acceptance criteria unchanged. \
   Commit and push when done."
```

The session has access to the piece spec, the design, and a new file `<p.id>.failures.md` that Forge wrote with:
- The failing CI step name + log excerpt
- All unresolved review comments grouped by file
- Previous failures on this piece (so the model can see if it's going in circles)

After 3 fix-up rounds on a single piece, FSM → `Failed("piece <id> did not converge")` and the TUI flags it for manual intervention. The PR is left open.

### 6.6 Cross-piece codex code review

Triggered when CI goes green on a piece PR, before merge:

```
codex exec --json --sandbox read-only \
  --output-schema forge/schemas/code-review.json \
  "Review PR #<n> on this repo. The piece spec is at .forge/specs/<feature>/pieces/<p.id>.md. \
   The diff is below. Focus on: \
   1) does it satisfy the acceptance criteria \
   2) does it match the design \
   3) obvious bugs, missing edge cases, untested paths."
```

Codex's output:
- `verdict: approve` → Forge posts an approving review on the PR via `gh pr review --approve --body "<codex review summary>"`. In v1, the FSM stays in `PieceAwaitingReview` and waits for you to merge. In v2 with auto-merge enabled, this is the trigger.
- `verdict: request_changes` → Forge posts review comments via `gh api` (one inline comment per blocker, plus a summary comment), then transitions to fix-up.

You can disable cross-model code review per feature in the config (`codex.review.code = false`) if you want to do it yourself for sensitive features.

### 6.7 The "Refinery" step

After each piece merges to `main`, before starting the next piece, Forge runs a quick deterministic check:

```scala
def refine(feature: Feature, justMerged: Piece): IO[RefineResult] =
  for
    _      <- BranchManager.checkoutMainAtHead
    _      <- runShell("sbt compile test")   // configurable: from .forge/config.json
    review <- CodexConnector.refineCheck(
                designPath = ".forge/specs/<f>/design.md",
                decompPath = ".forge/specs/<f>/decomposition.md",
                remainingPieces = feature.pieces.filter(_.mergedAt.isEmpty)
              )
  yield review
```

The Codex refine check asks: *"Given main now contains piece `<p>`, is the design still accurate? Are the remaining pieces still well-specified? Any pieces now redundant or needing changes?"*

If Codex says "update needed", Forge raises a Q&A prompt asking the user whether to:
1. Auto-update `decomposition.md` (Claude session writes the update).
2. Defer until after the next piece.
3. Pause the feature and reopen design.

This is the antidote to "the plan and reality drift apart" — it's an explicit step, recorded in the action log, never silent.

---

## 7. Source of truth for the work breakdown

**Hybrid, with bidirectional sync:**

| Lives where | What |
|---|---|
| `.forge/specs/<feature>/design.md` (in repo) | The design itself. Source of truth for design intent. |
| `.forge/specs/<feature>/decomposition.md` (in repo) | Ordered list of pieces, each with title, summary, acceptance criteria. Updated by DocSync. |
| `.forge/specs/<feature>/pieces/<pn>.md` (in repo) | One file per piece: detail spec + acceptance. |
| `.forge/state/<feature>.json` (in repo, gitignored optionally) | FSM state, session ids, PR numbers, mergedAt timestamps. Source of truth for *status*. |
| `.forge/log/<feature>.jsonl` (in repo, gitignored optionally) | Append-only action log. |

**Sync rules** (the DocSync component):

- When a piece transitions to `PieceMerged`, DocSync edits `decomposition.md` to mark that piece's checkbox `[x]` with the PR number and merge commit SHA appended. This goes in as a tiny commit on `main` titled `chore(<feature>): mark <piece-id> merged`.
- When the user manually edits `decomposition.md` (e.g., re-orders pieces, adds a new piece mid-feature), Forge detects the diff on its next FSM tick and asks via Q&A pane: "I see you've added piece P5. Should I include it in this feature, or treat it as a separate feature?"
- The state DB never holds piece *content* — only piece *status*. The content is always re-read from the Markdown so a user edit takes effect immediately.

This is the "build sync into the process" approach: the sync is mechanical, predictable, and observable in the action log.

You can decide whether `.forge/state/` and `.forge/log/` should be gitignored. Recommended default: **commit them**. The action log is itself a valuable artefact for retrospectives, and committing state lets you crash-recover by re-cloning. Add a `.forge/log/*.jsonl` gitattribute to mark them as generated for sensible diff views.

---

## 8. The action / choices log

A single newline-delimited JSON file per feature, written append-only. Schema:

```json
{
  "ts": "2026-05-24T15:42:18.341Z",
  "feature": "stripe-webhook",
  "piece": "p2",
  "kind": "fsm.transition",
  "from": "PieceImplementing",
  "to": "PieceAwaitingCi",
  "payload": { "prNumber": 4291, "branch": "forge/stripe-webhook/p2" }
}
```

`kind` values (initial set):

- `fsm.transition` — every state change. Always logged.
- `claude.spawn` — `{ argv, sessionId, mode: "interactive" | "headless" }`.
- `claude.assistant_text` — summary only (first 200 chars + token count), not full content. Full content lives in Claude's own JSONL session file under `~/.claude/projects/`; the action log records the session id so you can cross-reference.
- `claude.tool_use` — `{ tool, target }`. For `Bash`: `{ tool: "Bash", command, exitCode, durationMs }`.
- `claude.ask_user_question` — full question, full answer.
- `codex.exec` — `{ argv, durationMs, exitCode, schemaPath }`.
- `codex.review` — `{ verdict, blockerCount, suggestionCount, questionCount }` plus a pointer to where the full review was written.
- `gh.poll` — `{ pr, statusChecksRollup, reviewDecision }`. Sampled (every 5th poll) to keep the log readable; transitions are always logged in full.
- `gh.action` — `{ verb, args }` for any `gh` command that mutates (create PR, post comment, merge).
- `user.command` — TUI key bindings the user invoked.
- `harness.error` — exceptions, with stack trace.
- `cost.update` — `{ provider, model, inputTokens, outputTokens, usd }`.

The TUI has a "tail action log" pane that pretty-prints the last N entries with colour. There's also a `forge replay <feature>` command that re-prints the log as a narrative.

Why this matters: when a feature goes wrong, the action log is the ground truth for *what the system did and why*. Pairs with the Claude/Codex transcript files for *what the models said*. The combination is what you tune the prompts against.

---

## 9. Polling-based GitHub integration

Deliberately simple:

```scala
trait PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot]

class GhPollingWatcher(intervalMs: Int = 30000) extends PRWatcher:
  def watch(pr: PrNumber): Stream[IO, PrSnapshot] =
    Stream
      .awakeEvery[IO](intervalMs.millis)
      .evalMap(_ => IO.blocking {
        os.proc("gh","pr","view",pr.toString,
          "--json","statusCheckRollup,reviews,reviewDecision,mergeable,mergeStateStatus,comments,commits"
        ).call().out.text()
      })
      .map(parsePrSnapshot)
      .changes  // emit only when something differs from previous snapshot
```

- Default interval: **30s**.
- Configurable per repo via `.forge/config.json` (`pollIntervalMs`).
- Forge keeps polling only while FSM is in a state that cares (`PieceAwaitingCi`, `PieceAwaitingReview`, or `DesignReady` waiting for design PR merge).
- When idle (e.g., user is in interactive spec phase), no polling.
- Rate budget: at 30s and 2 polls per state, a typical 4-piece feature uses ~50–100 `gh` calls per piece, well under any reasonable limit.

`.changes` on the stream means the FSM only sees events when something actually changed in the PR — clean for state transition logic. The action log samples raw poll responses (1 in 5) to give you visibility without spamming.

---

## 10. Build order (de-risked)

Five vertical slices. Each is independently shippable and validates a risk before any dependent code is written.

### Slice 1 — Agent connectors (week 1)

Build `forge-agents` standalone, with a CLI demo and integration tests.

- `ClaudeConnector` with:
  - `runHeadless(prompt, systemPrompt, allowedTools): Stream[IO, ClaudeEvent]`
  - `runInteractive(systemPrompt): InteractiveSession` exposing input sink + output stream
  - Tool intercept callback for `AskUserQuestion`
  - Session id capture from the `system/init` event
- `CodexConnector` with:
  - `exec[T](prompt, schema: JsonSchema[T]): IO[T]` returning a decoded, schema-validated result
  - Sandbox mode selection
- An integration test that:
  - Runs Claude headless on "create a hello world Scala file in /tmp/forge-test"
  - Asserts file exists, action log emitted >0 events, session JSONL exists
  - Runs Codex review on a known-good and known-bad markdown file, asserts schema-conformant verdict in both directions

**Why first:** if the stream-json contract drifts, or schema-constrained Codex output doesn't work, everything downstream is dead. Validate this against the *exact versions* of `claude` and `codex` you intend to use, and pin them.

### Slice 2 — FSM, ActionLog, StateStore (week 2)

Plain Scala, no external agents, no git.

- `forge-core` module: FSM as `(FsmState, FsmEvent) => (FsmState, List[ActionLogEntry])`.
- `StateStore` using atomic file writes (`os.write.over` to temp then `os.move`).
- `ActionLog` appending to `.forge/log/<feature>.jsonl`.
- Property tests: any sequence of events produces a valid state; replaying the action log reproduces the final state.

**Why second:** the FSM is small enough that getting it right on paper before wiring agents to it is cheap insurance.

### Slice 3 — BranchManager + PRWatcher (week 3)

- Shell out to `git` and `gh`. No magic.
- BranchManager: create design branch, create piece branch, push, create PR, merge.
- PRWatcher: 30s poller as above, fs2 stream.
- Integration test against a sacrificial test repo on GitHub: create a branch, push a commit, open a PR, observe the watcher emit the right transitions.

**Why third:** with Slice 1 and 2 done, this is the last piece of plumbing before the FSM can drive real work end-to-end.

### Slice 4 — Headless feature loop (week 4)

Wire Slices 1–3 together. **No TUI yet** — drive from a plain CLI:

```
forge new "feature title"       → starts the FSM at Drafting
forge spec <feature>            → opens Claude in current terminal (raw passthrough)
forge run <feature>             → headless from DesignReady through to FeatureDone
forge status                    → prints FSM state + action log tail
```

By end of week 4 you should be able to drive a real small feature through Forge from the command line. **This is the first version that's actually useful**, before any TUI work.

### Slice 5 — TUI (week 5)

Now build the termflow UI:

- Echo the action log to a pane.
- Replace `forge spec` with the in-TUI interactive Claude pane.
- Add the Q&A pane for AskUserQuestion handling.
- Add keyboard shortcuts for pause/resume/cancel.

The TUI is last because it's the most subjective and the most likely to need iteration. Building it last means you're iterating on UX against a working system, not chasing FSM bugs through a TUI you don't yet understand.

---

## 11. Configuration

`.forge/config.json` per repo:

```json
{
  "pollIntervalMs": 30000,
  "maxFixupRounds": 3,
  "maxDesignReviewRounds": 3,
  "claude": {
    "model": "default",
    "permissionMode": "acceptEdits",
    "allowedTools": ["Read","Write","Edit","Bash","Glob","Grep","WebFetch","AskUserQuestion"],
    "bare": true,
    "additionalSystemPrompt": "forge/prompts/implement.md"
  },
  "codex": {
    "sandbox": "read-only",
    "review": { "design": true, "code": true, "refine": true }
  },
  "ci": {
    "refineCommand": "sbt compile test",
    "refineTimeoutSec": 600
  },
  "branchPrefix": "forge",
  "logRetention": "keep"
}
```

Global config at `~/.config/forge/config.json` for per-user overrides (e.g., default poll interval).

---

## 12. Risks and open questions

| Risk | Mitigation |
|---|---|
| Claude `--input-format stream-json` schema drift between versions | Slice 1 contract test pinned to a specific `claude` version; CI runs it in a matrix as new versions release |
| Mid-turn stdin not persisted to JSONL (#41230) | Always also write AskUserQuestion answers to a Markdown file under `.forge/specs/...` |
| Codex schema-constrained output not strict enough | Validate locally with a JSON schema validator; on validation failure, log + retry with a "your last output did not match the schema" prompt, max 2 retries |
| Termflow is 0.0.1 with rendering improvements still WIP | Keep the TUI minimal until 0.1.x; have a `forge --no-tui` mode that runs the same FSM with stdout-only output (in fact: implement that first per Slice 4) |
| `gh` rate limits during heavy polling | 30s default is well under limits; configurable; back off on 403/429 |
| Anthropic subscription restrictions (April 4 2026 change) | Document that Forge supports both Pro/Max subscription (Agent Teams not used so should be fine) and API-key modes via standard `ANTHROPIC_API_KEY` env |
| Forge's own state file getting out of sync with reality | Action log is the source of truth; state file is a cached projection; a `forge rebuild-state <feature>` command replays the log |
| Devcontainer + repo manipulation | v1 runs on the host, not inside the devcontainer — the harness sits *above* dev environments |

### Open questions to settle before/during Slice 1

1. **GitLab support.** Rory's work uses GitLab. v1 targets GitHub (`gh`) only. Add `glab` adapter later or behind a `forge-git-gitlab` module? **Recommendation:** GitHub only for v1. Build the `BranchManager` interface so a GitLab implementation is a swap-in.
2. **Cost tracking accuracy.** Claude Code emits cost in its `result` event; Codex CLI emits it in JSON too. Confirm both formats in Slice 1 and decide on a unified `Cost` type.
3. **Action log retention / privacy.** Logs contain Bash commands and file paths. Default: commit them. Make `.forge/log/` gitignore opt-out.
4. **What if the user wants to drive a piece manually?** Add a `forge piece skip <p>` command that marks a piece as manually completed (user is expected to merge it themselves). The FSM advances on next poll when it sees the branch merged.

---

## 13. What this design explicitly rejects

- **Real-time webhooks.** 15–60s polling is fine for this use case and removes a deployment dependency.
- **Worktrees.** Devcontainer-incompatible per prior experience.
- **LLM4S in the orchestrator.** Forge talks to Claude/Codex via their CLIs. LLM4S may be added later for in-process Scala-side LLM calls if a need emerges; v1 has no such need.
- **A "manager LLM" choosing which agent does what.** The orchestrator is deterministic Scala. LLMs do design, code, and review — they don't decide *when* to do them.
- **Per-session Langfuse traces.** The action log replaces this for v1. Adding Langfuse later is a 50-line addition.

Note: stacked PRs and auto-merge are **not** rejected — they're v1 non-goals and explicit v2 candidates. See §14.

---

## 14. v2 candidates and future work

### 14.1 v2 merge model — decide after lived experience

After v1 has shepherded a few real features end-to-end, pick one of the following based on what actually slowed you down:

**Option A: Auto-merge on green + Codex approval.**

- Add `autoMergeOnGreenAndApproved: true` to `.forge/config.json`.
- FSM transition: `PieceAwaitingReview` with all-green CI and Codex `verdict: approve` triggers `gh pr merge --squash --auto`.
- Per-feature override (`autoMerge: "never" | "on-approval" | "manual"`) for high-risk features.
- The TUI shows a clear "auto-merging in 10s, press space to cancel" countdown for the first few merges so you can build trust gradually.
- **Pick this if** v1 friction is "I'm clicking Merge on PRs that are clearly fine, and the only reason I'm there is to click the button".

**Option B: Stacked PRs onto a per-feature integration branch with composite CI.**

- Forge opens a single `forge/<feature>/integration` PR against `main` at the start of the feature.
- Each piece PR targets the integration branch, not `main`.
- CI runs on the integration PR after every piece merge — i.e. CI sees the cumulative state at every step, which is what "running full CI on stacked PRs" actually means in your environment.
- Piece PRs can be small and reviewable in isolation; the integration PR is the one that exercises the full feature.
- When the last piece merges to the integration branch and CI is green, the integration PR is ready to merge to `main` (auto or human-gated, your choice).
- **Pick this if** v1 friction is "each piece PR is rerunning the same 15-minute CI from scratch and I'd rather review N small diffs but only pay for CI once per feature".

**These options are not mutually exclusive.** A reasonable v2 endpoint might be "stacked PRs onto an integration branch, with auto-merge on the piece PRs once Codex approves, human gate on the integration PR going to main". The integration-branch approach also makes the Refinery step (§6.7) cheaper because main isn't disturbed mid-feature.

### 14.2 Other future work (no decision needed yet)

- Parallel features across multiple checkout slots.
- GitLab adapter behind the `BranchManager` interface.
- LLM4S-based in-process helpers (transcript summarisation, comment classification).
- Contributing `ClaudeConnector` / `CodexConnector` back to LLM4S as the seed of an "external agent CLI" module.
- Webhook mode for sub-second reactivity if a future use case demands it.
- "Process tuning" mode: feed past features' action logs to an LLM and ask it to suggest prompt or FSM improvements.
- Optional Langfuse integration (`TRACING_MODE=langfuse`) for richer trace UX.

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
| Merge gates v2 candidates? | **(a)** auto-merge piece PRs on green+Codex-approval, or **(b)** stacked piece PRs onto a per-feature integration branch with composite CI. Decide after lived experience. |
| GitHub integration? | **Polling `gh` every 30s** only when FSM is awaiting CI/review. |
| Work breakdown SoT? | **Hybrid:** design/decomposition/piece markdowns in repo, status in state DB, DocSync keeps them aligned. |
| Autonomy between pieces? | **Headless implementation.** Forge writes code, opens PR, gets Codex approval; you click Merge. Escalates only on `AskUserQuestion` or red CI. |
| Refinery step? | **In v1.** Deterministic post-merge check that re-validates design against new `main`. |
| Observability? | **Per-feature action log** (`.forge/log/<feature>.jsonl`). |
| First thing to build? | **Agent connectors** with integration tests pinned to specific Claude/Codex versions. |
| LLM4S role in v1? | **None.** Direct CLI integration. Revisit post-v1. |
