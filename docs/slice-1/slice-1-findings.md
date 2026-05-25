# Slice 1 — implementation findings + design follow-up

> Mirrors the [Slice 0 report](../slice-0/slice-0-report.md): captures the
> non-obvious runtime findings surfaced while implementing the
> [`forge-design-1.1.md`](../forge-design-1.1.md) §7.1 connector contract,
> and the resulting **spec corrections that v1.1 does not yet reflect**.
> These corrections will land in `forge-design-1.2.md` (standalone, per
> §23); until then, the implementation diverges from 1.1 in two named
> places below and the two connector docstrings flag the divergence at
> the source-code level.
>
> **Status:** draft 2026-05-25. Recorded mid-slice-1 in response to a
> code review flagging the 1.1 contract drift. Update / finalise when
> 1.2 lands.

## 1. What's done in slice 1

Implementation contract: §17 slice-1 deliverables.

| Item | Status | Lands in |
|---|---|---|
| `AgentSession`, `StreamingSession`, `Connector` traits (§7.1) | ✅ | `forge-agents` |
| `Role` indirection seam (`roadmap.md` §2.6) | ✅ | `forge-agents` |
| `PriceTable` + shipped `prices.example.json` (§7.10(b)) | ✅ | `forge-agents` |
| `CodexPrompt.withSystemBlock` (§7.10(a)) | ✅ | `forge-agents` |
| `CodexSessionSettings` + `isCompatibleForResume` (§7.10(c)) | ✅ | `forge-agents` |
| `ClaudeEventParser` (stream-json → `AgentEvent`) | ✅ | `forge-agents` |
| `CodexEventParser` (`--json` → `AgentEvent`, via `PriceTable`) | ✅ | `forge-agents` |
| `HaltWithQuestion.detect` / `tryParse` (§7.3 envelope) | ✅ | `forge-agents` |
| `Subprocess` (spawn / SIGTERM→grace→SIGKILL / stdio streams) | ✅ | `forge-agents` |
| `StreamingDriver` (subprocess + parser → `StreamingSession`) | ✅ | `forge-agents` |
| `ClaudeConnector` — headless driver methods | ✅ | `forge-agents` |
| `ClaudeConnector` — streaming driver methods | ⚠️ stubbed | see §3 below |
| `CodexConnector` — headless driver methods | ✅ | `forge-agents` |
| `CodexConnector` — streaming driver methods | ⚠️ stubbed | see §3 below |
| Reviewer one-shots (`reviewDesign` / `reviewPr` / `refine`) | ⏳ Layer 5 | both connectors |
| `HaltWithQuestion` orchestrator-side re-spawn loop | ⏳ Slice 2 (FSM) | `forge-core` |
| Real-CLI smoke (Claude headless hello-world) | ✅ | `forge-it` |
| Full §17 forge-it test list | ⏳ | gated on the §3 follow-up |

## 2. Runtime findings (not in 1.1)

These are behaviours of the pinned CLIs that Slice 0 didn't pin and that
Slice 1 implementation work surfaced. They don't narrow scope; they
change the connector trait shape.

### F1. Claude `--input-format stream-json`: init emitted only after first user message

**What 1.1 implies:** §7.1's synchronous `def sessionId: String`
accessor on `StreamingSession` is honored at spawn time — `runStreamingSpec`
returns a session whose `sessionId` is already populated from the CLI's
init event.

**What the CLI actually does (verified against Claude CLI 2.1.150):**
With `-p --input-format stream-json --output-format stream-json --verbose
--system-prompt-file <path>`, the CLI emits the `system.init` event
**only after the first user-message JSON frame arrives on stdin**. Empty
stdin → CLI exits silently with no events at all. A 2-second delay
before the first frame confirms: init arrives after the frame, not at
spawn.

**Implication for the §7.1 trait:**
`runStreamingSpec(systemPromptPath: os.Path): IO[StreamingSession]`
cannot return a session with a populated `sessionId` because the CLI
hasn't emitted one yet — it's waiting for input. The
`StreamingDriver.fromSubprocess` model (block on init, then return
session ready for `send`) is incompatible.

### F2. Codex `exec`: positional prompt required before thread_id

**What 1.1 implies:** Same as F1, for Codex's session start.

**What the CLI actually does (Slice 0 §2.2):** `codex exec --json
'<prompt>'` requires a prompt; without one, the CLI doesn't start a
session. `codex exec resume <thread-id> '<msg>'` similarly needs a
message.

**Implication:** Same trait-level mismatch as F1. The Codex case was
already flagged in Slice 0 §2.2 ("no `--system-prompt-file` flag — pass
the system prompt as the first positional argument") but the
trait-level consequence wasn't followed through into the §7.1 contract.

### F3. `AskUserQuestion` answer is a `tool_result`, not a user message

**What 1.1 says:** §7.2 step 4 says the orchestrator "sends the answer
back on stdin as the `tool_result`."

**What the trait offers:** `StreamingSession.send(input: String)`. Plain
string. The wire-shape for a `tool_result` is different — it carries
the outstanding `tool_use_id` from the `AskUserQuestion` event and a
content payload:

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"...","content":"answer text"}]}}
```

Passing a free-text answer through `send(...)` would be interpreted by
Claude as a *new* user message, not the awaited `tool_result`, leaving
the deferred tool use dangling.

**Implication:** The trait needs to model the tool_result path
explicitly. Either an additional method (e.g.
`answerQuestion(toolUseId, answer): IO[Unit]`) or a richer payload ADT
for `send`. The orchestrator also has to track outstanding
`tool_use_id`s to call it correctly.

## 3. Spec corrections proposed for forge-design-1.2

Per §23 (standalone-revision rule), 1.2 will be a complete spec
incorporating these into §7.1 / §7.2 / §11. This section is the
**proposed delta** until the standalone 1.2 is written.

### 3.1 Trait extension — `StreamingSession` and `Connector`

```scala
trait StreamingSession extends AgentSession:
  def send(input: String): IO[Unit]
  // NEW — §7.2 tool_result answer path (F3 above)
  def answerQuestion(toolUseId: String, answer: String): IO[Unit]

trait Connector:
  // CHANGED — initial user message required to elicit init (F1, F2 above)
  def runStreamingSpec(
      systemPromptPath: Path,
      initialUserMessage: String
  ): IO[StreamingSession]

  def resumeStreamingSpec(
      sessionId: String,
      message: String   // resume always needs a fresh user message
  ): IO[StreamingSession]

  // unchanged: runHeadlessImplementation, runFixup, reviewer methods, costFrom
```

### 3.2 Lifecycle adjustments (§11)

§11.1 step 2 currently reads:

> 2. Spawn driver: `session <- driver.runStreamingSpec(~/.forge/prompts/specify.<driver>.md)`.

Becomes:

> 2. Read the first user input from the spec-pane prompt (or use the
>    rendered "What feature do you want?" preamble as the implicit first
>    message). Spawn driver:
>    `session <- driver.runStreamingSpec(~/.forge/prompts/specify.<driver>.md, firstUserMessage)`.
>    The init event arrives in response to that message, so
>    `feature.designSessionId = Some(session.sessionId)` is well-defined
>    immediately after the call returns.

§11.2 step 12, §11.3 step 2: `resumeStreamingSpec(feature.designSessionId.get)`
becomes `resumeStreamingSpec(feature.designSessionId.get, revisionMessage)`
where `revisionMessage` is the blocker-addressing prompt the orchestrator
was already sending right after the resume call. Net effect on the FSM:
a fold of two adjacent steps into one.

§11.4 step 2 (`runHeadlessImplementation`): no change — headless already
carries its prompt at spawn.

§11.6 (Fixup): no change — same as headless.

### 3.3 Q&A handling (§7.2 / §10.4)

§7.2 step 4 stays, but the implementation maps to
`session.answerQuestion(toolUseId, answer)` rather than `session.send(answer)`.
The orchestrator captures the `tool_use_id` from the
`AgentEvent.AskUserQuestion` event (which already carries it in
`ClaudeEventParser`'s output — needs a field added). On Codex
(`HaltWithQuestion` mechanism, §7.3), `answerQuestion` is implemented
as a re-spawn with the answer in the prompt body; the orchestrator
calls it via the same trait method.

## 4. Why this isn't already 1.2

These corrections were uncovered during slice-1 wiring, not during
slice-0 validation. Three reasons to write the formal 1.2 only when the
slice-1 follow-up PR opens:

- The trait shape may evolve further as the Layer 5 reviewer one-shots
  and the orchestrator-side `HaltWithQuestion` re-spawn loop come in.
  Folding all four corrections into one 1.2 standalone is cheaper than
  two near-back-to-back revisions.
- Codex's `exec resume` exact behaviour for multi-turn streaming hasn't
  been integration-tested yet; subtleties may surface there.
- The standalone-revisions rule (§23) means 1.2 is a ~1400-line
  rewrite; the cost is meaningfully higher than a delta doc, and the
  payoff is greater when several corrections land at once.

This doc + the inline docstrings on `AgentSession.scala`,
`ClaudeConnector.scala`, and `CodexConnector.scala` are the tracked
spec follow-up in the meantime.

## 5. Anchors in the code

| Finding | Code/test anchors |
|---|---|
| F1 | `ClaudeConnector.runStreamingSpec` docstring; `ClaudeConnectorSuite` "runStreamingSpec / resumeStreamingSpec raise NotImplementedError"; `ClaudeHeadlessSmokeSuite` "Claude streaming spec is currently stubbed" |
| F2 | `CodexConnector.runStreamingSpec` docstring; `CodexConnectorSuite` "runStreamingSpec / resumeStreamingSpec raise NotImplementedError with a clear message" |
| F3 | `AgentSession.scala` `StreamingSession` class-level docstring (note 2) |
| Subprocess kill race | `SubprocessSuite` "kill escalates to SIGKILL when the process traps SIGTERM" |

Rationale items: see [`design-rationale.md`](../design-rationale.md)
C11 (F1), C12 (F3), C13 (subprocess kill).
