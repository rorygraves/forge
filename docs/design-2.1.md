# design-2.1 — Slice 1 implementation plan

> **Maps to:** [`roadmap.md`](roadmap.md) §2.1 (Phase 1 / Slice 1 — Agent
> connectors) and [`forge-design-1.2.md`](forge-design-1.2.md) §17 slice 1
> deliverables.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation
> plans"): every uncomplete roadmap section gets a `design-<section>.md`
> companion. The roadmap stays terse — direction and exit criteria — and
> this file owns the per-task breakdown. Items get ticked off here as they
> land; the roadmap section gets ticked off only after a code review on
> the section as a whole.
>
> **Status:** in progress.

## 0. Exit criterion for Slice 1

Roadmap §2.1: `forge-agents` standalone with a CLI demo + integration
tests against real `claude` and `codex` binaries. Concretely, this slice
is done when:

1. Both connectors implement every method on the v1.2 §7.1 `Connector`
   trait against the new shape (`runStreamingSpec(systemPrompt,
   initialUserMessage)`, `resumeStreamingSpec(sessionId, message)`,
   `StreamingSession.answerQuestion(toolUseId, answer)`).
2. The full §17 slice-1 forge-it test list passes against the pinned
   CLI versions.
3. A code review on the section confirms (1) and (2); the
   `[~] ClaudeConnector and CodexConnector` line in `roadmap.md` §2.1
   flips to `[x]`.

## 1. Sub-PR breakdown

Six numbered sub-PRs. Each is independently mergeable; the dependency
graph is linear apart from #5/#6 which can land in either order once #4
is in.

### 1.1 PR A — trait-shape code change (in progress)

- [x] **A1.** Add `toolUseId: Option[String]` to
  `AgentEvent.AskUserQuestion`; remove the separate
  `AgentEvent.HaltWithQuestion` case. `CodexEventParser` emits
  `AskUserQuestion(_, None)` on the §7.3 halt envelope; `ClaudeEventParser`
  extracts the `tool_use` block-level `id` and emits `Some(id)`. Drop the
  event if `id` is missing (would mis-route as the HaltWithQuestion
  path). Spec: v1.2 §7.1 + design-rationale C12. Done.
- [x] **A2.** Extend `StreamingSession` trait with
  `answerQuestion(toolUseId: Option[String], answer: String): IO[Unit]`.
  Three implementor stubs updated (StreamingDriver placeholder + 2 test
  no-ops). Spec: v1.2 §7.1 + design-rationale C12. Done.
- [x] **A3.** Update `Connector.runStreamingSpec` /
  `resumeStreamingSpec` signatures per v1.2 §7.1. Both connector stubs
  re-thread the new parameters; existing tests updated to assert the new
  stub error messages. Spec: v1.2 §7.1 + design-rationale C11. Done.
- [x] **A4.** Extend `StreamingDriver.fromSubprocess` with two new
  optional params: `initialUserInput: Option[String]` (written to stdin
  before blocking on Init, mirrored as `UserMessage` after Init) and
  `encodeAnswer: Option[(Option[String], String) => IO[String]]`
  (connector-supplied tool_result encoder). New suite tests cover
  initial-input ordering, the encoded answer path, encoder failure
  propagation, and the default `encodeAnswer = None`
  → `NotImplementedError` behaviour. Spec: v1.2 §7.1. Done.
- [x] **A5.** Implement `ClaudeConnector.runStreamingSpec(systemPrompt,
  initialUserMessage)` / `resumeStreamingSpec(sessionId, message)`
  against `StreamingDriver` (passing `initialUserInput` and an
  `encodeAnswer` that requires `Some(toolUseId)` and raises an adapter
  error on `None`). Added public `encodeToolResultJson(toolUseId,
  answer)` + `encodeAnswer` helpers alongside `encodeUserMessageJson`;
  new `MissingToolUseId` adapter-error case in `AdapterError.scala`.
  Replaced the Task-#5-sentinel tests (one in `ClaudeConnectorSuite`,
  one in `ClaudeHeadlessSmokeSuite`) with fake-CLI round-trip tests:
  - spawn → init → drain (initial-message stdin write verified by
    echoing the encoded frame back as an `AssistantText` event)
  - resume → asserts `--resume <sid>` argv shape and end-to-end
    round-trip through the same fake-CLI machinery
  - answerQuestion(Some(id), text) writes the `tool_result` JSON frame
    (verified by echo round-trip)
  - answerQuestion(None, _) raises `MissingToolUseId` (parser-regression
    diagnostic)
  Spec: v1.2 §7.1 / §7.2. Done.
- [x] **A6.** Implement `CodexConnector.runStreamingSpec(systemPrompt,
  initialUserMessage)` / `resumeStreamingSpec(sessionId, message)` /
  `answerQuestion(toolUseId, answer)` as a multi-process facade over
  `codex exec` (one process per turn — Slice 0 §2.2). Implementation
  landed as a new `CodexStreamingSession` class:
  - thread-id captured from the first turn's `thread.started` →
    `AgentEvent.Init` via a `Deferred`; `start` blocks on it before
    returning so the synchronous `sessionId: String` accessor is honest
  - per-turn subprocess serialised under `cats.effect.std.Mutex` (the
    first turn runs in a fiber holding the mutex; `send` /
    `answerQuestion` queue behind it)
  - each turn's `stdout` is parsed by `CodexEventParser` and pushed to
    a shared `Channel[IO, AgentEvent]`; resume turns' `thread.started`
    events are dropped so consumers see exactly one `Init`. Per-turn
    stderr is drained into a shared `Ref` for diagnostics
  - system-prompt prepending (§7.10(a)) applied to initial spawn and
    subsequent `send` / `answerQuestion` resume turns via the session's
    stored `systemPromptPath`. `resumeStreamingSpec` deliberately runs
    with `systemPromptPath = None` (the trait signature, shared with
    Claude, doesn't carry the path); the orchestrator is responsible
    for re-issuing context in the message body or trusting Codex's
    session memory
  - `answerQuestion(_, answer)` ignores `toolUseId` (the §7.3 halt
    envelope has no wire-level tool use) and routes through the same
    resume path as `send(answer)`
  - `kill()` SIGTERMs any currently active subprocess via a
    `currentTurnRef: Ref[Option[Subprocess]]` and closes the channel;
    `close()` waits for in-flight turn to drain naturally then closes.
    `closedRef` gate rejects further turns after either path
  - mismatched `thread_id` on resume (CLI returns a different id than
    the caller-supplied one) raises rather than silently lying via
    `sessionId`
  Replaced the Task-#6-sentinel test in `CodexConnectorSuite` with seven
  new fake-CLI tests covering: first-turn thread_id capture and `Init →
  UserMessage` ordering, multi-turn `send` round-trip with exactly one
  `Init`, `answerQuestion(None, _)` and `answerQuestion(Some(id), _)`
  both routing through the resume path, `kill()` mid-turn finalising
  the channel, `resumeStreamingSpec` happy path, and resume
  thread-id-mismatch raising. Spec: v1.2 §7.1 / §7.3 / §7.10. Done.
- [ ] **A7.** PR-A landing checklist:
  - `sbt compile` clean under `-Xfatal-warnings`.
  - `sbt test` green across the build (forge-core, forge-agents,
    forge-specs).
  - `sbt scalafmtCheckAll` clean.
  - The two "stubbed pending Task #5/#6" sentinel tests are removed.
  - `roadmap.md` §2.1 `ClaudeConnector and CodexConnector` bullet's
    parenthetical updated to reflect that the trait-shape code PR has
    landed; this file's PR-A section gets a final ✅ in §3 below.

### 1.2 PR B — Claude streaming integration tests (forge-it)

- [ ] **B1.** Real-CLI streaming-spec round-trip: spawn with `(prompt,
  "hi")`, drain to first Result, capture sessionId, close. Assert the
  sessionId is a UUID and the stream contains the expected event
  shape (Init → ≥1 AssistantText/ToolUse → CostUpdate → Result).
- [ ] **B2.** Resume round-trip: spawn → close → resume with a new
  message; assert `newSessionId == oldSessionId` (§6.1 invariant on the
  pinned CLI).
- [ ] **B3.** answerQuestion against a contrived prompt that elicits an
  `AskUserQuestion` tool use. Capture the `toolUseId` from the stream,
  call `answerQuestion(Some(id), "yes")`, assert the session continues
  without re-asking and ends with a successful Result.
- [ ] **B4.** kill() exercised mid-stream; verify no zombie subprocess
  and the events stream terminates cleanly.
- [ ] **B5.** All four tests gated identically to
  `ClaudeHeadlessSmokeSuite` (PATH probe + `FORGE_IT_SKIP_CLAUDE=1`
  escape hatch).
- Spec: §17 slice-1 forge-it test list.

### 1.3 PR C — Codex streaming integration tests (forge-it)

- [ ] **C1.** Real-CLI headless smoke (mirror of
  `ClaudeHeadlessSmokeSuite`): `gpt-5-codex` against a trivial prompt;
  full event pipeline produces Init + AssistantText + CostUpdate +
  Result; CostUpdate USD > 0 via the shipped price table.
- [ ] **C2.** streamingSpec round-trip via the multi-process facade:
  initial spawn + at least one follow-up `send()`; assert thread_id
  preserved across all turns.
- [ ] **C3.** resumeStreamingSpec preserves thread_id (§6.1).
- [ ] **C4.** **HaltWithQuestion reliability sample** — contrived
  ambiguous prompt run ≥20 times; assert ≥19/20 produce a parseable
  halt envelope (§7.3 / §16 threshold). On miss, the test fails with the
  rate so we can decide whether to narrow scope per §16.1.
- [ ] **C5.** answerQuestion routes through the resume path (no
  outstanding tool_use to reference).
- [ ] **C6.** kill() mid-turn on the facade; verify no zombie
  subprocess.
- [ ] **C7.** PATH-probe + `FORGE_IT_SKIP_CODEX=1` gating.
- Spec: §17 slice-1 forge-it test list.

### 1.4 PR D — reviewer regression suites (forge-it)

> **Blocked on:** shipped reviewer schemas + reviewer system prompts.
> Those land later in Slice 1 / early Slice 4 alongside
> `ForgePaths`. Park this PR until they land.

- [ ] **D1.** For each connector (Claude, Codex) × each schema
  (design-review, code-review, refine), run a ≥20-sample regression
  against a checked-in fixture input.
- [ ] **D2.** Assert ≥19/20 produce schema-conformant output (§7.4 /
  §16 threshold). Failures bucketed by failure mode (process error vs
  adapter error) so the report distinguishes §7.5 from §7.6.
- [ ] **D3.** Gated identically to other forge-it suites (PATH probe +
  per-binary escape hatch).
- Spec: §7.4 / §17 slice-1 forge-it test list.

### 1.5 PR E — close-out: roadmap + docs sweep

- [ ] **E1.** Code review on PR-A through PR-D as a section. Reviewer
  notes get captured here, not on the spec.
- [ ] **E2.** Flip `roadmap.md` §2.1 `ClaudeConnector and CodexConnector`
  bullet from `[~]` to `[x]`. Flip the HaltWithQuestion + integration
  test bullets similarly. Remove the "What unblocks slice-1 closure"
  numbered list (its entries are all done at this point) or replace it
  with a one-line "✅ closed 2026-MM-DD" anchor.
- [ ] **E3.** Update `AGENTS.md` "Current state" Slice 1 paragraph to
  reflect closure.
- [ ] **E4.** Update `CLAUDE.md` TL;DR "Current state" line.
- [ ] **E5.** Move `docs/slice-1/slice-1-findings.md` next to
  slice-0-report.md style — convert from "evolution record" to "Slice 1
  outcomes" (or leave as-is if the v1.2 fold-in note is still
  accurate). Decide based on what the post-mortem actually surfaced.

## 2. Order of work

`A1 → A2 → A3 → A4 → A5 ⊕ A6 → A7 → (B || C) → (D when unblocked) → E`

Where `⊕` means A5 and A6 can be done in either order or in parallel
once A4 lands; both modify different connector files and the trait
shape they implement is settled by A1–A4.

## 3. Status log

Update this section as items land. The roadmap section ticks off only
after PR-E lands.

- 2026-05-25 — design-2.1.md created. A1–A4 landed in this session
  (single working branch, not yet committed). Continuing with A5 next.
- 2026-05-25 — A5 landed: `ClaudeConnector.runStreamingSpec` /
  `resumeStreamingSpec` wired through `StreamingDriver`;
  `encodeToolResultJson` / `encodeAnswer` helpers added; new
  `MissingToolUseId` adapter error; sentinel tests replaced with
  fake-CLI round-trips covering init, resume, `answerQuestion(Some)`,
  and `answerQuestion(None)` paths. Next up: A6 (Codex multi-process
  facade).
- 2026-05-25 — A6 landed: new `CodexStreamingSession` class implements
  the multi-process facade over `codex exec [resume] --json`. First
  turn captures thread_id via `Deferred`; per-turn subprocess
  serialised under `cats.effect.std.Mutex`; resume turns' Init events
  dropped from the session stream; `answerQuestion` routes through the
  resume path; `kill()` terminates the active subprocess and finalises
  the channel; resume thread-id mismatch raises. Seven new fake-CLI
  tests in `CodexConnectorSuite` replace the Task-#6 sentinel. Next
  up: A7 (PR-A landing checklist).

## 4. Cross-references

- v1.2 spec for trait shape: §7.1, §7.2, §7.3, §7.4, §7.5, §7.6, §7.10
- Decisions backing the trait-shape PR: design-rationale C11, C12
- Wire-shape findings: `slice-0/slice-0-report.md`, `slice-0/transcripts/`
- Pre-v1.2 findings doc (superseded but kept): `slice-1/slice-1-findings.md`
- Phase context + seam discipline: `roadmap.md` §2.6 (role-trait stub,
  paths helper deferral)
