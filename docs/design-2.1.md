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

### 1.1 PR A — trait-shape code change ✅ landed 2026-05-25

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
- [x] **A7.** PR-A landing checklist — all green:
  - `sbt clean compile` clean under `-Xfatal-warnings` ✅.
  - `sbt test` green: 173 unit tests across the build (forge-core,
    forge-agents, forge-specs) ✅.
  - `sbt scalafmtCheckAll` clean ✅.
  - `sbt "project forge-it" test`: 1/1 (Claude headless smoke still
    passes; the Task-#5/#6 sentinel tests are gone) ✅.
  - Both "stubbed pending Task #5/#6" sentinel tests removed from
    `ClaudeConnectorSuite`, `ClaudeHeadlessSmokeSuite`, and
    `CodexConnectorSuite` ✅.
  - `roadmap.md` §2.1 `ClaudeConnector and CodexConnector` parenthetical
    rewritten to reflect that the trait-shape code PR landed; "What
    unblocks slice-1 closure" item #3 marked ✅ landed; status line
    bumped to v0.4 ✅.
  - `AGENTS.md` "Current state" Slice 1 paragraph rewritten to mention
    PR-A complete and the new `CodexStreamingSession` class ✅.
  - `CLAUDE.md` TL;DR "Current state" rewritten to match ✅.
  - This file's PR-A section flipped from "in progress" to
    "✅ landed 2026-05-25" in the §1.1 header ✅.
  Done.

### 1.2 PR B — Claude streaming integration tests (forge-it) ✅ landed 2026-05-26

- [x] **B1.** Real-CLI streaming-spec round-trip: spawn with `(prompt,
  "hi")`, drain to first Result, capture sessionId, close. Assert the
  sessionId is a UUID and the stream contains the expected event
  shape (Init → ≥1 AssistantText/ToolUse → CostUpdate → Result). Lands
  in `ClaudeStreamingSpecSuite`. Note: close-before-drain is the
  required idiom in streaming-spec mode (`-p --input-format
  stream-json` keeps stdin open; the events channel only closes after
  the CLI exits, which only happens after `closeStdin` signals EOF) —
  the existing headless smoke suite gets away with drain-first because
  `-p '<prompt>'` mode reads no stdin. Done.
- [x] **B2.** Resume round-trip: spawn → close → resume with a new
  message; assert `newSessionId == oldSessionId` (§6.1 invariant on the
  pinned CLI). Done — passes against Claude 2.1.150.
- [x] **B3.** answerQuestion against a contrived prompt that elicits an
  `AskUserQuestion` tool use. Capture the `toolUseId` from the stream,
  call `answerQuestion(Some(id), "yes")`, assert the session continues
  without re-asking and ends with a successful Result. Uses a Ref +
  Deferred collector pattern (fs2 Channels are single-consumer, so a
  second `compile.toVector` after a `takeThrough` race is unsafe).
  Done.
- [x] **B4.** kill() exercised mid-stream; verify no zombie subprocess
  and the events stream terminates cleanly. Done — kill() finalises
  the events channel so `compile.toVector` returns promptly.
- [x] **B5.** All four tests gated identically to
  `ClaudeHeadlessSmokeSuite` (PATH probe + `FORGE_IT_SKIP_CLAUDE=1`
  escape hatch). Done.
- Spec: §17 slice-1 forge-it test list.

### 1.3 PR C — Codex streaming integration tests (forge-it) ✅ landed 2026-05-26

PR-C also folded in two small upstream fixes the integration suite
surfaced while running against the installed `codex-cli 0.133.0` (Slice
0 was pinned against 0.130.0):

  - **`CodexConnector.execArgv` flag update.** `-a/--ask-for-approval
    <mode>` was removed in ≥0.131 in favour of a `-c
    approval_policy=<mode>` config override (TOML key/value form).
    Connector + the `execArgv` unit test in `CodexConnectorSuite`
    updated to match the new wire shape. The `execResumeArgv`
    negative-flag assertion also dropped the now-nonexistent
    `--ask-for-approval` from its rejection set; resume still rejects
    `--sandbox`, `--output-schema`, `--add-dir`, `-C`, but the
    sticky-settings surface narrowed in 0.133. §7.10(c) spec
    consequences parked for now — the contract is still "sticky flags
    require a fresh spawn", just with a different rejection list.
  - **stdin EOF on Codex spawns.** Codex CLI reads stdin even when the
    prompt is on argv (it logs `"Reading additional input from
    stdin..."` to stderr and blocks until EOF). JVM
    `ProcessBuilder` leaves the child's stdin as an open pipe, so
    Codex hangs waiting for EOF. `CodexConnector.spawnHeadless` and
    `CodexStreamingSession.runOneTurn` both call `sp.closeStdin` right
    after spawn so the CLI proceeds. Slice 0 didn't catch this because
    it ran codex from a shell with implicit EOF; the JVM-spawned path
    is the first to exercise the open-stdin behaviour.

PR-C tests then landed:

- [x] **C1.** Real-CLI headless smoke (mirror of
  `ClaudeHeadlessSmokeSuite`): `gpt-5-codex` against a trivial prompt;
  full event pipeline produces Init + AssistantText + CostUpdate +
  Result; CostUpdate USD > 0 via the shipped price table. Lands in
  `CodexHeadlessSmokeSuite`. The model defaults to `gpt-5-codex` but
  can be overridden via `FORGE_IT_CODEX_MODEL` for accounts that
  return 400 invalid_request_error on `gpt-5-codex` (e.g. ChatGPT
  accounts must use `gpt-5.3-codex`). The override must still match a
  `prices.example.json` entry or the USD > 0 assertion fails. Done.
- [x] **C2.** streamingSpec round-trip via the multi-process facade:
  initial spawn + at least one follow-up `send()`; assert thread_id
  preserved across all turns. Done — exactly one `Init` event across
  both turns (resume turn's `thread.started` is dropped by the
  facade); two `Result` events.
- [x] **C3.** resumeStreamingSpec preserves thread_id (§6.1). Done —
  the §6.1 invariant holds against `codex exec resume <sid>`.
- [x] **C4.** **HaltWithQuestion reliability sample** — contrived
  ambiguous prompt run ≥20 times; assert ≥19/20 produce a parseable
  halt envelope (§7.3 / §16 threshold). On miss, the test fails with the
  rate so we can decide whether to narrow scope per §16.1. Lands in
  `CodexHaltWithQuestionReliabilitySuite`. **Opt-in via
  `FORGE_IT_RUN_RELIABILITY=1`** — even when `codex` is on PATH the
  suite skips unless the gate is flipped, so the default forge-it test
  run isn't dominated by 20 real-model calls (~2–5 minutes wall-clock).
  The halt-envelope system prompt spells the JSON schema literally — a
  loose-worded variant was observed to fall well short of the bar. The
  gating bar itself (§16) hasn't been re-measured on `codex-cli 0.133`
  yet; that's an on-demand exercise the gate enables. Done.
- [x] **C5.** answerQuestion routes through the resume path (no
  outstanding tool_use to reference). Done — `answerQuestion(_,
  answer)` is byte-for-byte equivalent to `send(answer)` on Codex; the
  test exercises the resume path with `toolUseId = None` to match
  what a real §7.3 halt envelope would emit.
- [x] **C6.** kill() mid-turn on the facade; verify no zombie
  subprocess. Done — `kill()` SIGTERMs the active subprocess
  (Subprocess.kill blocks on `waitFor`, so the test only completes
  once the OS reap is done) and finalises the events channel.
- [x] **C7.** PATH-probe + `FORGE_IT_SKIP_CODEX=1` gating. Done —
  applied across all three new Codex suites (`CodexHeadlessSmokeSuite`,
  `CodexStreamingSpecSuite`, `CodexHaltWithQuestionReliabilitySuite`).
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
- [ ] **E6.** Walk §4 "Carry-forward to v1.3" below. Every open item
  there must be reflected somewhere durable (roadmap §3 v1.3 bucket,
  a dedicated tracking issue, or an explicit deferred-decision note
  in `design-rationale.md`) **before** E2 flips the §2.1 bullet to
  `[x]`. The section-as-a-whole tick must not bury a known v1.2
  spec/code discrepancy under it.

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
- 2026-05-25 — **PR-A complete.** A7 verified the landing checklist
  (clean compile + 173 unit tests + 1 forge-it smoke + scalafmt clean
  + no sentinel tests remaining); `roadmap.md`, `AGENTS.md`, and
  `CLAUDE.md` brought up to date to reflect the trait-shape code PR
  landing. Next up: PR-B (Claude real-CLI streaming integration tests).
  PR-C (Codex) can land in parallel with PR-B once we have a real
  `codex` binary available in CI.
- 2026-05-26 — **PR-B complete.** `ClaudeStreamingSpecSuite` lands
  with B1–B4 against Claude CLI 2.1.150; all four pass (~16s total).
  Close-before-drain idiom needed because streaming-spec mode keeps
  stdin open, so the events channel only closes after `closeStdin`
  signals EOF. B3 uses a Ref + Deferred collector pattern (fs2
  Channels are single-consumer; a second `compile.toVector` after a
  `takeThrough` race is unsafe).
- 2026-05-26 — **PR-C complete.** Three suites land
  (`CodexHeadlessSmokeSuite`, `CodexStreamingSpecSuite`,
  `CodexHaltWithQuestionReliabilitySuite`). C1, C2, C3, C5, C6 pass
  against `codex-cli 0.133.0` (~45s combined). C4 is opt-in behind
  `FORGE_IT_RUN_RELIABILITY=1` — 20 real-CLI runs take 2–5 minutes
  wall-clock so it's gated off the default test pass. Two upstream
  fixes folded into PR-C: (1) `CodexConnector.execArgv` swapped
  `--ask-for-approval <mode>` for `-c approval_policy=<mode>` (the
  flag was removed in codex ≥0.131); (2) `CodexConnector.spawnHeadless`
  + `CodexStreamingSession.runOneTurn` now close the child's stdin
  immediately after spawn — codex hangs on a JVM-spawned open stdin
  pipe even when the prompt is on argv. PR-C tests default to
  `gpt-5-codex` but accept `FORGE_IT_CODEX_MODEL` override for
  account-tier-restricted setups (e.g. ChatGPT accounts must use
  `gpt-5.3-codex`). Next up: PR-E (close-out). PR-D (reviewer regression
  suites) still parked — blocked on shipped reviewer schemas + system
  prompts.
- 2026-05-26 — **PR-C review follow-ups landed.** Four reviewer
  comments addressed: (1) `CodexConnector.runReviewer` spawn now
  `closeStdin`s (same hang root cause as headless/streaming); (2) C4
  reliability suite swapped to close-then-drain; (3) the
  `resumeStreamingSpec` system-prompt gap is recorded as a known v1.2
  spec/code discrepancy in **design-rationale C14** and as **§4
  carry-forward item C14** below — PR-E must carry it forward before
  flipping the §2.1 roadmap bullet to `[x]`; (4) `CodexStreamingSession`
  resume turns now verify thread_id matches `sessionId` and raise on
  mismatch (with a new unit test). 174 unit tests pass.

## 4. Carry-forward to v1.3

Items the section closure (PR-E) **must not silently bury** when it
flips the §2.1 roadmap bullet. Each one needs a durable home — a
roadmap §3 v1.3 bullet, a tracking issue, or an explicit
deferred-decision entry in `design-rationale.md` — before PR-E E2
ticks the section. PR-E item E6 walks this list.

- **C14 — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a)
  system-prompt prepending** (design-rationale C14). The shared trait
  signature `resumeStreamingSpec(sessionId, message)` doesn't carry a
  `systemPromptPath`, but v1.2 §7.10(a) claims the prepending
  convention applies to resume too. v1.3 needs to decide: widen the
  trait (drags Claude through a parameter it doesn't need), drop the
  §7.10(a) "applies to resume" claim and shift role-framing
  responsibility to the orchestrator's resume message, or carry
  the path through a different seam. **Status:** documented in
  design-rationale C14 + emphasised in the connector docstring; the
  orchestrator's resume path (Slice 2 FSM) will need to be written
  aware of the gap until v1.3 closes it.

## 5. Cross-references

- v1.2 spec for trait shape: §7.1, §7.2, §7.3, §7.4, §7.5, §7.6, §7.10
- Decisions backing the trait-shape PR: design-rationale C11, C12
- Wire-shape findings: `slice-0/slice-0-report.md`, `slice-0/transcripts/`
- Pre-v1.2 findings doc (superseded but kept): `slice-1/slice-1-findings.md`
- Phase context + seam discipline: `roadmap.md` §2.6 (role-trait stub,
  paths helper deferral)
