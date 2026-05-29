# Forge — design rationale

> Why the live spec ([`forge-design-1.2.md`](forge-design-1.2.md)) looks the way it does. Each item names a decision, what was rejected, and what to read in the live spec for the resulting contract. Older entries reference §-numbers in v1.1 — those sections survived intact into v1.2 unless explicitly noted.
>
> The design went through twelve iterations (0.1 → 0.9 → 1.0 → 1.1 → 1.2) with delta-only commentaries at each step. Those commentaries have been removed from the tree; this document preserves the non-obvious rationale a future contributor would want before re-litigating a settled question. Decisions already explicit in the live spec's §22 (rejects) or §20 (v2 candidates) are not repeated here.

---

## Architecture & module boundaries

### A1. Mode is a hard-coded two-variant enum, not a pluggable role system
**Decision:** `enum Mode = ClaudeDriver | CodexDriver`. Connector dispatch is by mode.
**Rejected:** Open-ended `DriverAgent` / `ReviewAgent` traits + capability matrix + "validate-or-emulate" strategies. Also rejected: dropping role configurability entirely (an overcorrection). Two named roles are a v1 product requirement; arbitrary role pluggability is a v2 candidate (§20) only when a real third agent appears.
**In v1.1:** §7 intro, §21 "Mode pluggability?".

### A2. Connector traits are concrete contracts each CLI must satisfy, not speculative abstractions
**Decision:** Both CLIs expose the surface needed for both roles. A CLI failing a role makes that mode unsupported and the doc says so.
**Rejected:** "Validate or emulate" — closing CLI gaps with prompt-engineering sub-projects. Slice 0 is validation, not silent triage (P3 below).
**In v1.1:** §7.1, §16, §16.1.

### A3. FSM only sees `PrSnapshot`, never raw `gh` JSON
**Decision:** Provider-agnostic ADT in `forge-core`; `gh` parsing confined to `forge-git`.
**Rejected:** Watchers that hard-code `gh` JSON field names into the FSM data path. Keeps the GitLab seam clean (the seam is the `PrSnapshot` ADT, not a bespoke adapter rewrite).
**In v1.1:** §6 (`PrSnapshot`), §9 (`PRWatcher`).

### A4. No "manager LLM" choosing which agent does what
**Decision:** The orchestrator dispatches deterministically by `Mode`.
**Rejected:** Higher-level LLM routing — implicit in the abandoned role-abstraction direction.
**In v1.1:** §22.

---

## Source of truth & logging

### B1. Local canonical action log is gitignored; only sanitized milestone snapshots are committed
**Decision:** `.forge/log/<feature>.jsonl` is the runtime canonical log, gitignored. Committed audit lives under `.forge/specs/<feature>/audit/` per `auditMode`.
**Rejected:** Committing the canonical action log. Nearly every event happens after a relevant PR is already open, so committing would either spam PRs or require constant follow-up sync commits — reintroducing the churn we removed from DocSync.
**In v1.1:** §4.

### B2. The committed state cache is rejected; log is canonical, state is a rebuildable projection
**Decision:** Gitignore `.forge/state/`. `forge rebuild-state <feature>` replays the log.
**Rejected:** Committing state for crash recovery. Two committed sources of truth would conflict and drift.
**In v1.1:** §4 invariant.

### B3. Action log uses fixed top-level keys + opaque `payload`
**Decision:** Top-level: `seq, ts, feature, piece, actor, role, kind`. Everything else inside `payload`.
**Rejected:** Per-event-kind top-level fields (e.g. `from`/`to` at top level). Forces parsers to know every event shape and breaks generic tooling.
**In v1.1:** §19.

### B4. Monotonic per-feature `seq` on every action
**Decision:** `seq: Long` (monotonic per feature) for replay determinism.
**Rejected:** Relying on `ts` ordering alone.
**In v1.1:** §19.

### B5. Bash command logging gets summary treatment, not full content
**Decision:** First 200 chars in the action log; full content in the CLI's native transcript.
**Rejected:** Logging full Bash commands by default. Quickly leaks secrets and dominates log size.
**In v1.1:** §19 (`<actor>.tool_use` — paths/command summaries only).

---

## Manifest & decomposition

### M1. `manifest.json` is the machine source of truth; `decomposition.md` is a rendered view
**Decision:** Forge rewrites `decomposition.md` from the manifest, not the other way around.
**Rejected:** Parsing `## Piece N: <title>` Markdown headings into piece IDs. Fragile — reordering/renaming breaks replay and DocSync.
**In v1.1:** §5.1, §5.3.

### M2. `forge reconcile` uses HTML-comment editable regions, not LLM-assisted parsing
**Decision:** Rendered `decomposition.md` has marker-delimited editable regions. Edits inside markers map deterministically to `ManifestPatch` ops; edits outside are refused.
**Rejected:** (a) Rule-based parsing of arbitrary Markdown (breaks immediately). (b) Model-assisted reconcile (reintroduces LLM dependency on a previously deterministic path; cost; non-determinism).
**In v1.1:** §5.3, §5.4.

### M3. `ManifestPatch` is a small custom ADT, not JSON Patch
**Decision:** Ops are `AddPiece`, `RemovePiece` (rejected if merged), `EditPiece`, `ReorderPieces`.
**Rejected:** RFC 6902 JSON Patch or generic patch formats. Custom ADT is easier to validate against domain rules ("can't remove merged pieces").
**In v1.1:** §6 (`ManifestPatchOp`).

### M4. `PlanningUpdate` carries the patch inline, not a file path
**Decision:** `PlanningUpdate(reason, patch: ManifestPatch)`. The audit-dir copy is for humans; the FSM source of truth is the in-state copy.
**Rejected:** `PlanningUpdate(reason, patchPath: String)`. A path is fragile across restarts/machine moves/audit cleanup; leaves dangling FSM state.
**In v1.1:** §6 (`FsmState`), §14.3.

### M5. Merged pieces form an immutable prefix; reorders affect only the pending tail
**Decision:** `ReorderPieces` validator enforces `newOrder.take(mergedCount) == currentOrder.take(mergedCount)`.
**Rejected:** Vaguer "pieces past the merge point can't change" wording. Doesn't tell the implementer what's invariant.
**In v1.1:** §5.5.

### M6. Slug algorithm + `-2`/`-3` collision suffix is pinned
**Decision:** Lowercase, non-alphanumeric runs collapse to `-`, trim, truncate to 40 chars at hyphen boundary, prefix `f-` if empty/digit-leading. `--id <id>` overrides.
**Rejected:** Leaving slugging implicit. Many plausible algorithms; collisions were undefined.
**In v1.1:** §5.2.

### M7. Manifest mutations are named at every FSM transition that implies one
**Decision:** Each annotation (baseSha, prNumber, attempts, merged status) is explicit in §11. Atomic temp-file write precedes the transition.
**Rejected:** Implicit "obviously the implementer does this" mutations. v0.9 had merge detection updating manifest implicitly, which caused an infinite loop with the `nextPiece` selector — the just-merged piece kept getting reselected.
**In v1.1:** §11.4 step 1, §11.5 step 1, §21 "Manifest mutation timing?".

### M8. `baseSha` is nullable until branch creation
**Decision:** `null` for pending pieces; populated by `BranchManager.createPieceBranch`; validator requires non-null when `status != "pending"`.
**Rejected:** Showing baseSha for pending pieces in examples (impossible by construction — no branch yet).
**In v1.1:** §5.1.

---

## FSM shape & state design

### F1. `Failed` is rejected as terminal; `NeedsHumanIntervention` is non-terminal and resumable
**Decision:** `NeedsHumanIntervention(reason, resumeHint)` is recoverable via `forge resume`; `Abandoned` is terminal only on explicit `forge abandon`.
**Rejected:** Single `Failed` terminal state. One flaky CI run would kill a feature.
**In v1.1:** §6, §11, §22.

### F2. `DesignAwaitingMerge` exists as a distinct state between `DesignReviewing` and `DesignReady`
**Decision:** Explicit waiting state with `prNumber`; the poller activates here.
**Rejected:** Going straight to `DesignReady` while still polling for merge (contradictory).
**In v1.1:** §6, §11.3.

### F3. No `PieceMerged` state — merge advances directly to `Refining`
**Decision:** Atomic manifest mutation on merge detection, then `Refining`. `audit.piece_merged` event for audit consumers.
**Rejected:** `PieceMerged` as a stable state. Reads as a loop with `Refining → PieceMerged`; never actually a stable resting point.
**In v1.1:** §6 (state comment), §11.5, §22.

### F4. Human feedback gates remain live through merge for both design and piece PRs
**Decision:** New comments / `CHANGES_REQUESTED` in `PieceAwaitingMerge` transition back to `PieceReviewFailed`. Design PRs get a `DesignPrFeedback` transition.
**Rejected:** Ignoring comments once reviewer approves. Common case: human notices something the reviewer missed during the merge gate.
**In v1.1:** §11.3, §11.5.

### F5. `Refining` failure is advisory; advances to next piece, doesn't block
**Decision:** Refinery is advisory tooling; failures log `harness.refinery_failed` and proceed.
**Rejected:** Wedging the FSM in `Refining` on refinery errors. The piece already merged successfully — don't block on advisory tooling.
**In v1.1:** §14.2.

### F6. Resume hints are operation-specific, not a generic retry
**Decision:** Typed variants — `ResumeAfterHumanPush`, `CommitAndPushHumanFix`, `RunAnotherFixup`, `ResolveLocalImplementationChanges`, `ReopenDesign`, `ApplyPlanningUpdate`, `AbortOrAbandon`.
**Rejected:** `RetryFromState(<old state>)` carrying a dead `sessionId`. Generic retry can't express "the human pushed already" vs "Forge should commit local edits" vs "run another fix-up".
**In v1.1:** §6 (`ResumeHint`).

### F7. ChangeCollector denial path is phase-aware
**Decision:** Pre-PR denial → `ResolveLocalImplementationChanges(p, branch)`; post-PR denial → `RunAnotherFixup(p, prNumber)`.
**Rejected:** Single denial → `RunAnotherFixup(p, prNumber)`. Illegal pre-PR because `prNumber` doesn't exist yet.
**In v1.1:** §10.1.

### F8. One `attempts` counter for all fix-up sources
**Decision:** CI failure, review request_changes, human CHANGES_REQUESTED, late merge-gate feedback all increment the same counter; all checked against `maxFixupRounds`.
**Rejected:** Split `ciAttempts` / `reviewAttempts`. User-facing cap is "rounds before surfacing to human"; splitting lets a piece accrue `maxFixupRounds` of each and run twice as long.
**In v1.1:** §5.1 (`attempts` semantics), §11.5.

### F9. Session ids are feature-scoped durable fields, not per-FSM-case parameters
**Decision:** `feature.designSessionId`, `feature.currentPieceSessionId` projected from the action log. FSM cases drop redundant `sessionId` parameters.
**Rejected:** Carrying `sessionId` only in some states — it disappears in `DesignNeedsHumanInput`, `DesignAwaitingMerge`, `DesignPrFeedback` even though they need it. Forces ad-hoc threading.
**In v1.1:** §6 (`Feature`), §6.1.

### F10. `currentPieceSessionId` is cleared at the advance boundary, not on entry to `Refining` / `PieceAwaitingCi`
**Decision:** `Some` from driver spawn through every state of the active piece (including CI/review/merge/refining); cleared on advance to next piece's `PieceImplementing` (or `FeatureDone` / `PlanningUpdate` / `NeedsHumanIntervention`).
**Rejected:** Three different formulations (entering `Refining` / exiting it / clearing on `PieceAwaitingCi`).
**In v1.1:** §6.1.

### F11. `requireSessionId` helper, not `.get`
**Decision:** Missing required session id → `NeedsHumanIntervention(..., ReopenDesign(...))`. No `.get` in production code.
**Rejected:** Letting `.get` throw on corruption / manual-edit. Spec should name the failure mode.
**In v1.1:** §6.2, §11.0 step 5, §22.

---

## Agent connectors & emulation policy

### C1. One bounded protocol or zero — never open-ended emulation
**Decision (historical, 0.6–0.9):** `SchemaFallback` was the *only* permitted emulation, max 2 attempts hard-coded, applied only to schema-constrained reviewer output.
**Decision (v1.1, post-Slice 0):** Both pinned CLIs have native schema enforcement, so v1 has *zero* emulation. `SchemaFallback` is parked as a v2 candidate (§20).
**Rejected:** Open-ended "validate or emulate" strategies that closed every CLI gap with prompt engineering. The precedent — one bounded protocol, never open-ended — applies to any future fallback.
**In v1.1:** §7.4 (Native everywhere), §7.5 (adapter-error path), §20, §22.

### C2. Schema fallback success bar was set at ≥19/20 per method, not aggregate, not 100%
**Decision:** Each reviewer method (`reviewDesign`, `reviewPr`, `refine`) is judged independently against N=20 fixtures.
**Rejected:** 100% (any transient miss disqualifies an otherwise-fine adapter); aggregate (a connector might pass two methods and fail one — that one method's mode is unsupported).
**In v1.1:** §16 (the bar now applies to the `HaltWithQuestion` reliability measurement deferred to Slice 1, not to schema — Native everywhere obviated the schema bar).

### C3. `schemaFallback.maxAttempts` is not a config knob
**Decision (historical):** The 2-attempt cap is a protocol invariant, hard-coded in the connector.
**Rejected:** Exposing as `config.schemaFallback.maxAttempts: 2`. Invites users to set it to 5 and get behaviour inconsistent with the spec — a "fake knob".
**Principle for v1.1:** `maxHaltRespawns`, `maxFixupRounds`, budgets, and timeouts are real knobs; protocol invariants are not. Apply the same scrutiny to any future knob.
**In v1.1:** §22 ("Fake config knobs").

### C4. Driver question capability is required; silent proceed is never permitted
**Decision:** Satisfied by `Native` (Claude's `AskUserQuestion`) or `HaltWithQuestion` (structured `{ status: "needs_human", ... }` output + re-spawn).
**Rejected:** "If the driver can't ask questions, it just proceeds". Silently breaks Goal 9 (escalation on uncertainty).
**In v1.1:** §1 Goal 9, §7.2, §7.3, §22.

### C5. `QuestionMechanism` and `SchemaMechanism` are separate enums, not a single `Mechanism`
**Decision:** Two enums; mixing is a type error.
**Rejected:** `enum Mechanism = Native | SchemaFallback | HaltWithQuestion`. `HaltWithQuestion` is illegal for schema output and `SchemaFallback` is illegal for questions — encode at compile time.
**In v1.1:** §6.

### C6. Driver question handling applies to every driver session
**Decision:** Defined once in the connector section; each lifecycle phase references it. `HaltWithQuestion` re-spawn carries the original session's prompt context.
**Rejected:** Specifying question handling only for the implementation phase. Invisible for Claude (works the same everywhere) but matters for Codex (every driver session needs the halt protocol).
**In v1.1:** §7.8.

### C7. Process retries and schema attempts are distinct layers (single layer in v1.1)
**Decision:** `refineProcessRetries` / `reviewProcessRetries` retry only process-level failures (network, sandbox, subprocess crash). Schema-validation failures return adapter errors and are not retried by the process layer.
**Rejected:** Conflating `refineRetries` with the SchemaFallback cap. A user setting `refineRetries: 5` could get 10 attempts, bypassing the schema-fallback invariant.
**Principle:** Schema failures are content failures, not transport failures. Don't retry them at the transport layer.
**In v1.1:** §7.5, §7.6, §21.

### C8. `AgentSession.kill()` exists on both streaming and headless
**Decision:** `AgentSession` trait with `kill()` / `close()`; `StreamingSession extends AgentSession`. Headless runs return `IO[AgentSession]`, not bare streams.
**Rejected:** Headless runs returning `Stream[IO, AgentEvent]`. Settle-timeout and per-turn-budget enforcement reference `session.kill()` — needs an actual session.
**In v1.1:** §7.1, §22.

### C9. `runStreamingSpec` returns `IO[StreamingSession]`, not a bare value
**Decision:** Process spawn is effectful; the IO wrap acknowledges it.
**Rejected:** Implicit non-IO return.
**In v1.1:** §7.1.

### C10. `resumeStreamingSpec(sessionId)` is an explicit trait method
**Decision:** Connector exposes the resume entry point; lifecycle calls it directly. Slice 0 validated resume works for both pinned CLIs and that the id is preserved.
**Rejected:** "Resume driver design session" as prose without a method.
**In v1.1:** §7.1, §6.1.

### C11. Streaming-spec sessions need an initial user message at spawn time (slice-1 runtime finding, resolved in v1.2)
**Decision (resolved in v1.2):** `runStreamingSpec(systemPrompt, initialUserMessage)` and `resumeStreamingSpec(sessionId, message)` take the user message alongside the system prompt path; the returned `StreamingSession` has a populated `sessionId` by the time the IO completes.
**Why:** A slice-1 runtime probe against Claude CLI 2.1.150 (`-p --input-format stream-json --output-format stream-json --verbose --system-prompt-file <path>`) showed the CLI emits the `init` event **only after the first user-message JSON frame arrives on stdin**. Empty stdin → CLI exits silently with no events at all. Codex's `exec` model is the same: a positional prompt is required at spawn. The v1.1 trait's synchronous `sessionId: String` accessor + the `StreamingDriver.fromSubprocess` "block on Init, then return session" pattern can't be honored without an initial message — which the v1.1 trait didn't carry.
**Rejected for v1:** (a) Priming the session internally with a sentinel like `"<INIT>"` to elicit init (pollutes conversation history; system prompt would have to know about it). (b) `sessionId: Option[String]` / `IO[String]` (drags through every caller; the synchronous shape is itself a contract). (c) Letting `runStreamingSpec` return a session whose `sessionId` is empty until first `send` (silently breaks `feature.designSessionId` recording in §11.1 step 2).
**State today:** v1.2 §7.1 has shipped the new trait signatures and §11.1/§11.2/§11.3 lifecycle steps fold the message into the spawn/resume calls (e.g. revision step 12 no longer has a separate "Forge sends a revision message" sub-step — it's `revisionMessage` passed to `resumeStreamingSpec`). The forge-agents code still stubs `runStreamingSpec` / `resumeStreamingSpec` with `NotImplementedError` carrying the runtime evidence; the trait-shape code change is the next slice-1 PR.
**In the spec:** v1.2 §7.1 (new trait signatures), §11.1 step 2, §11.2 step 12, §11.3 step 2.

### C12. `AskUserQuestion` → `tool_result` answer path needs an explicit trait method (slice-1 design gap, resolved in v1.2)
**Decision (resolved in v1.2):** `StreamingSession.answerQuestion(toolUseId: Option[String], answer: String): IO[Unit]` is the single trait entry for replying to a deferred `AskUserQuestion`. `AgentEvent.AskUserQuestion` carries a matching `toolUseId: Option[String]` — `Some(id)` for Native (Claude's `AskUserQuestion` tool use), `None` for `HaltWithQuestion` (no wire-level tool use exists). `ClaudeConnector.answerQuestion` requires `Some(...)` and raises an adapter error on `None` (signalling a parser regression); `CodexConnector.answerQuestion` ignores the argument and re-spawns with the answer in the prompt body. The `Option` type is preferred over a String sentinel because the wire-level absence is genuine, not a Codex-side convention.
**Why:** §7.2 step 4 says the orchestrator "sends the answer back on stdin as the `tool_result`." That's a different JSON frame than a plain user message — it carries the outstanding `tool_use_id` from the `AskUserQuestion` event. The current `send(input: String)` is plain user-message only; passing a free-text answer this way would be interpreted by Claude as a new user message, not the awaited `tool_result`. The orchestrator also has to track the pending `toolUseId` to call this correctly.
**Rejected for v1:** Letting `send` be the dual-purpose channel by sniffing the input (fragile and undocumented). A richer payload ADT (`UserMessage(text) | ToolResult(toolUseId, content)`) replacing `send` — considered, but two methods read more clearly at call sites where the distinction is load-bearing. Doing nothing (silently breaks the §7.2 Native question flow).
**State today:** v1.2 §7.1 has shipped the trait method; §7.2 step 4 now reads "Routes the answer through `session.answerQuestion(toolUseId, answer)`" rather than "Sends the answer back on stdin as the `tool_result`". The forge-agents implementation of `answerQuestion` (plus adding the `toolUseId` field to the `AgentEvent.AskUserQuestion` parser output) lands with the trait-shape code PR alongside C11.
**In the spec:** v1.2 §7.1 (trait method + event-field note), §7.2 (Native path), §7.3 (HaltWithQuestion path).

### C13. ChatCLI subprocess kill: SIGTERM → grace race → SIGKILL must be implemented as a wall-clock race, not a fixed sleep
**Decision:** `Subprocess.kill(grace)` calls `process.destroy()` (SIGTERM), races `process.onExit()` against `IO.sleep(grace)`, and only escalates to `destroyForcibly()` if the sleep wins. Returns immediately once the process is fully exited.
**Why:** Slice 0 measured ~100ms (Codex) and ~400ms (Claude) clean-exit times for SIGTERM. A naive `destroy(); IO.sleep(5s); if alive then destroyForcibly()` would always block 5s on every kill — that's the per-feature settle path × every fix-up cycle. Race-based escalation gives sub-second kill when the child is well-behaved, falls back at the configured grace when it's not.
**Test sharpness:** The SIGKILL-path test had to be reworked twice — first to use a shell loop that doesn't get exec'd away by `/bin/sh` (so the trap stays effective), then to wait for a synchronization line on stdout before sending SIGTERM (so the test doesn't race the trap installation itself). Both adjustments are documented inline in `SubprocessSuite.scala`; the underlying behaviour is correct.
**In v1.1:** §7.9 (kill semantics specified). Implementation in `forge-agents/Subprocess.scala`.

### C14. `Codex resumeStreamingSpec` cannot apply §7.10(a) system-prompt prepending under the v1.2 trait (slice-1 spec/code gap)
**Decision (interim, slice-1 PR-C):** `CodexConnector.resumeStreamingSpec(sessionId, message)` **does not** prepend the original system-prompt block to the resume `message`, contradicting the v1.2 §7.10(a) sentence "the same convention applies to `resumeStreamingSpec`". The connector relies on Codex's session memory of the original spawn's system block; the orchestrator that calls `resumeStreamingSpec` is expected to either re-issue the role framing in `message` or accept the model's recollection of the spawn-time prompt.
**Why this is a gap:** The shared `Connector.resumeStreamingSpec(sessionId: String, message: String)` trait signature (matched to Claude, which restores the system prompt server-side via `--resume`) carries no `systemPromptPath`. `CodexConnector` therefore has no path to read at resume time. Claude's resume is honest because the CLI keeps the prompt; Codex's resume is a fresh `codex exec resume --json <sid> <prompt>` invocation that doesn't remember anything the adapter doesn't pass in.
**Rejected (for now):** (a) Widening the trait to `resumeStreamingSpec(sessionId, systemPromptPath, message)` — drags Claude's adapter through a parameter it doesn't need and changes every call site. (b) Caching `systemPromptPath` on the `CodexConnector` instance from a prior `runStreamingSpec` call — a connector created fresh for the resume case (e.g. across a process restart) would have no cache, and an orchestrator that resumes multiple sessions on one connector would have only the most recent path. (c) Storing the path inside the `StreamingSession` and reapplying on resume — `resumeStreamingSpec` doesn't get a prior `StreamingSession` to read from; it gets a bare `sessionId`.
**Action required for v1:** v1.3 spec correction needs to either (i) drop the §7.10(a) "applies to resume" claim and shift the role-framing responsibility to the orchestrator's resume message, or (ii) widen the trait to carry the path and accept the call-site churn. Until that's decided, the existing comment in `CodexConnector.resumeStreamingSpec` documents the deviation; the orchestrator's resume code path (lands with the Slice 4 orchestrator loop — Slice 2 shipped the pure FSM only, no orchestrator) needs to be written aware of it.
**In the spec today:** v1.2 §7.10(a) (states the broken contract), §7.1 (the trait signature that doesn't carry the path). Surfaced as a PR-C reviewer comment; tracked here so v1.3 can close it explicitly.

### C15. Native schema regression suite (PR-D) deferred from Slice 1 to ship alongside reviewer assets (slice-1 close-out decision)
**Decision (slice-1 PR-E review):** `design-2.1.md` §1.4 PR-D — the ≥20-sample native-schema regression suite for `reviewDesign` / `reviewPr` / `refine` on each connector — is **explicitly deferred out of Slice 1**. The suite lands as a gating check on the PR that ships the reviewer schema files (`~/.forge/schemas/{design-review,code-review,refine}.json` per v1.2 §17, §10.2, §14.3 — matching the path and naming already used in `forge-agents/Reviews.scala` and `ReviewDecoders.scala`) and the reviewer system-prompt files, which the roadmap places in Slice 4. Slice 1 closes (`roadmap.md` §2.1 `[~] → [x]`) without PR-D having landed; the carry-forward to the reviewer-asset PR is recorded as a `design-2.1.md` §4 bullet that PR-E E6 must walk before flipping the roadmap.
**Why this is OK as a deferral:** The reviewer code path on both connectors is already shipped and exercised end-to-end against fake CLIs in `CodexConnectorSuite` / `ClaudeConnectorSuite` (per-method `ReviewerAssets` plumbing, shared `ReviewDecoders` + `ReviewerPrompts`, retryable `ReviewerProcessFailure` vs non-retryable `ReviewerNotConfigured` / `StructuredOutputMissing` / `StructuredOutputMalformed` adapter errors). What PR-D would add — real-CLI native-schema reliability measurement against the §16 ≥19/20 bar — can only be measured against shipped schemas + prompts. Without those assets the suite has nothing to assert against.
**Why this is honest under v1.2 §17:** v1.2 §17 slice-1 integration test list still names the native schema regression suite. This entry is the explicit deviation note so the roadmap flip from `[~]` to `[x]` doesn't silently bury the gap — v1.3 spec corrections should either (i) restate the regression suite as a Slice-4 deliverable alongside reviewer-asset shipping, or (ii) leave §17 as-is and have the v1.3 §17 reorganisation absorb the move.
**Rejected:** (a) **Ship reviewer schemas + prompts + PR-D in Slice 1** — expands Slice 1 scope and locks in schema content choices before the Slice 2-3 orchestrator work has surfaced reviewer schema requirements (e.g. the FSM may want fields PR-D would otherwise have to backfill). (b) **Keep Slice 1 open until PR-D lands** — couples Slice 1 closure to an external dependency (reviewer assets) that the roadmap doesn't schedule until Slice 4; Slice 2 (FSM) blocking on it would just stall the roadmap.
**Action required for v1:** ~~the reviewer-asset PR (Slice 4 per current roadmap) lands PR-D as its gating integration check; if the real-CLI ≥19/20 bar isn't met, schema/prompt tightening happens there.~~ **CLOSED in Slice 1.4a Task 1.4.7 (2026-05-29).** `ReviewerRegressionSuite` (`forge-it`, opt-in `FORGE_IT_RUN_REGRESSION=1`) measures the §16 bar against the shipped reviewer assets via the real `AssetInstaller`→bind→decode path. **Bar met (≥19/20) for all six method × connector pairs** in a full live 6×20 batch with the v1 reviewer config — claude reviewer on `haiku`, codex on `gpt-5.3-codex`, 3-min per-call cap. Three real-CLI drifts were found and fixed inside 1.4a en route ([[C16]] envelope, [[C17]] Codex strict schema, [[C18]] Claude 2.1.156 tolerant parse). Residual: a heavier Claude reviewer (sonnet/opus) is higher-quality but latency-prohibitive for pr-review under a reasonable wall-clock cap; the production reviewer **(model, cap, timeout-retry)** tuning is deferred to Task 1.4.9 / **S4-3**, and an unescaped-`"`-in-summary is a rare documented-unrepairable schema mode within the 1/20 tolerance.
**In the spec today:** v1.2 §17 (Slice 1 integration test list naming the regression suite), §16 (the ≥19/20 bar — now met). Surfaced as a PR-E reviewer comment; closed in Task 1.4.7.

### C16. Claude CLI 2.1.153 moved the reviewer schema payload from `structured_output` to the `result` string (Slice 1.4a Task 1.4.7 runtime finding)
**Finding (Task 1.4.7 wiring smoke):** The Slice 0 transcript `transcripts/07-claude-schema.json` (captured against the pinned floor, Claude 2.1.150) shows the schema-conformant reviewer output in a dedicated `structured_output` envelope field. Against the installed Claude **2.1.153**, that field is **gone**: the same `claude -p --output-format json --json-schema '<schema>' --system-prompt-file <path>` invocation now returns the schema-conformant JSON as the `result` **string** (a JSON document). The schema binding still works — `result` matches the bound schema exactly (`verdict`/`blockers`/`questions`/`summary`) — only the envelope field carrying it changed. This is the same shape the Codex path already extracts from `item.completed{agent_message}.text`. `ClaudeConnector.extractStructuredOutput` previously read only `structured_output`, so it failed 100% of reviewer calls on 2.1.153 (`StructuredOutputMissing`), making the entire Claude reviewer path non-functional and blocking the C15 ≥19/20 bar.
**Decision (Task 1.4.7):** `extractStructuredOutput` now prefers `structured_output` when present (back-compat with ≤ 2.1.150) and otherwise parses the `result` string as JSON. Covered by `ClaudeConnectorSuite` unit + fake-CLI tests against both envelope shapes. This is exactly the "capture real external shapes before writing decoders" discipline (AGENTS.md) catching a wire drift — the regression gate found it on its first live call, as designed.
**Why this is OK / open questions:** The fix is shape-tolerant and version-agnostic, so it survives a CLI that reintroduces `structured_output` or one that keeps `result`. Open: the pinned-floor docs (`docs/slice-0/slice-0-report.md`, §6.1-pinned flag set) still describe the 2.1.150 `structured_output` shape; a v1.3 note should record that the floor's *flag set* is unchanged (`--json-schema` is still the structured-output flag and still works) but the *result envelope field* drifted, so any future schema-shape assertions key off `extractStructuredOutput`'s tolerant behaviour rather than a literal field name. No spec (§7.4) change required — §7.4 names the mechanism (`--json-schema`), not the envelope field.
**In the spec today:** v1.2 §7.4 (Native schema mechanism — flag only, no envelope-field claim). Surfaced by the Task 1.4.7 wiring smoke; tracked here so the floor-vs-current shape gap is durable.

### C17. Codex `--output-schema` enforces OpenAI strict mode; the shared reviewer schemas must target that subset (Slice 1.4a Task 1.4.7 runtime finding)
**Finding (Task 1.4.7 Codex wiring smoke):** Codex's `--output-schema <path>` passes the schema to the OpenAI Responses API as a **strict** `response_format` json_schema, which accepts only a restricted JSON Schema subset and 400s otherwise. Claude's `--json-schema` is lenient (full draft-2020-12), so the same shipped schema worked for Claude but failed for Codex on three independent rules, surfaced one at a time by live `codex exec`: (1) `'required' ... must include every key in properties` — every property must be listed in `required` (optionals are expressed as nullable `["T","null"]`, not by omission); (2) `'oneOf' is not permitted` — `oneOf`/`allOf`/`not`/`if`/`then`/`else` are all forbidden (`anyOf` is allowed); (3) `schema must have a 'type' key` — a property using only `const` needs an explicit `type`. The shipped `design-review.json` / `code-review.json` / `refine.json` violated (1) (optional `ReviewBlocker`/`Question` fields omitted from `required`) and `refine.json` additionally violated (2) (the `manifestPatchOp` `oneOf`; the `allOf`/`if`/`then` conditional patch requirement) and (3) (`$type` `const` without `type`). Net effect: the entire Codex reviewer path 400'd on all three methods — half the C15 ≥19/20 bar was unreachable.
**Decision (Task 1.4.7):** keep a **single shared schema per method** (preserving the §17 "schemas are shared" design) but author it in the OpenAI-strict subset — which Claude's lenient validator also accepts. Concretely: list every property in `required` and model optionals as nullable; drop unsupported keywords (`minimum`); replace `oneOf` with `anyOf` + the `$type` `const` discriminator (now carrying `"type":"string"`); and make `refine.json`'s `patch` always-required-but-nullable, **dropping the `allOf`/`if`/`then` "patch present iff outcome == update_plan" conditional**. That §14.3 invariant does not disappear — it was already enforced (and is the authoritative enforcement) by `ReviewDecoders.refineResult` (covered by `ReviewDecodersSuite`); the schema conditional was redundant defense-in-depth that strict mode can't express. Verified by manual `codex exec --output-schema` calls (all three accepted; `agent_message` parses as schema-conformant JSON) and the live Codex wiring smoke. Schema-shape suites (`SchemaShapeSuites`) and `RefineSchemaValidationSuite` updated to pin the new strict-subset shape and the schema-vs-decoder split.
**Why this is OK / rejected alternative:** Rejected **per-connector schemas** (a strict variant for Codex, a rich variant for Claude): it would let Claude keep schema-level conditional enforcement, but that enforcement is redundant with the decoder, and per-connector schemas would break the shared-schema design, double the asset count, and split `ReviewerAssets.PerMethod.schema` per connector — real complexity for no behavioural gain. The single-strict-subset schema is strictly simpler and works for both. Residual: the schema is now slightly weaker for Claude (it *could* have enforced the conditional and the `minimum`), but the decoder is the contract of record either way.
**In the spec today:** v1.2 §7.4 (Native schema mechanism), §14.3 (refine patch invariant — now decoder-enforced), §17 (shared schemas — preserved). Surfaced by the Task 1.4.7 Codex smoke; tracked here so the Claude-lenient-vs-Codex-strict asymmetry is durable.

### C18. Claude CLI 2.1.156 `--json-schema` validates but does not hard-enforce; the reviewer path must salvage JSON from prose (Slice 1.4a Task 1.4.7 runtime finding)
**Finding (Task 1.4.7 wiring smoke, re-run against Claude 2.1.156):** After C16 fixed the `result`-string envelope for 2.1.153, the cheap re-run smoke on the upgraded **2.1.156** CLI showed the Codex pair clean but the Claude design-review pair failing `StructuredOutputMissing` ("`result` string was not valid JSON: expected json value got \"B\" at index 0"). A direct capture (`claude -p … --json-schema '<schema>'`, the exact `reviewerArgv` flags) proved the envelope shape is unchanged from C16 — `result` still carries the payload, no `structured_output` field — but `--json-schema` now behaves as **best-effort validation, not enforcement**: on a trivial design the model returned a bare JSON object, while on a larger real fixture it returned the same conformant object **wrapped in prose** ("Based on the design… {…}"). `claude --help` confirms the flag is still present and described as "JSON Schema for structured output **validation**" (not "enforcement"). So `ujson.read(result)` fails whenever the model prepends/appends prose or a Markdown fence, even though a conformant object is present. The orchestrator (Slice 1.4b) will hit the same leakage on every reviewer call, so this is a connector-robustness gap, not a test artifact.
**Decision (Task 1.4.7):** `extractStructuredOutput` keeps the C16 order (prefer `structured_output`, else parse `result` as JSON) and adds a third, salvage step (`parseResultPayload` → `salvageJsonObject`): when a clean parse fails, recover the **first balanced JSON object** from the `result` string (brace-depth scan that is string/escape aware, so `}` inside string literals doesn't close the span), and accept it only if `ujson` parses it cleanly. A `result` with no salvageable object still surfaces `Left` (→ `StructuredOutputMissing`) exactly as before, so a genuine prose-only refusal is not silently swallowed. Belt-and-braces, the three Claude reviewer system prompts (`design-review/code-review/refine.claude.md`) were tightened with a forceful "first char `{`, last char `}`, no preamble/fences" directive plus a closing "Output format (strict)" reminder, to lower the leak rate at the source. Covered by `ClaudeConnectorSuite`: two `extractStructuredOutput` salvage cases (prose-wrapped, ```json-fenced), two `salvageJsonObject` unit cases (string-aware brace matching; prose-with-no-object → `None`), and a fake-CLI end-to-end on the 2.1.156 prose shape (fake-mirrors-real discipline).
**Why this is OK / open questions:** Salvage is conservative — it never fabricates structure, only locates an object the model emitted and re-parses it, and the shared `ReviewDecoders` remains the contract of record (a salvaged-but-non-conformant object still fails `StructuredOutputMalformed`). The fix is version-agnostic: a future CLI that re-enforces the schema or reintroduces `structured_output` still decodes via the earlier branches. **Open:** the §16 "native schema ≥19/20" bar was premised on `--json-schema` *enforcing* structured output (C2); on 2.1.156 enforcement is the model's compliance, not the CLI's. The salvage + prompt-tightening is expected to keep the *decode* success rate at the bar even when raw-format compliance dips, but the full live 6×20 batch (G3/G4/G5) is what confirms it — C15 stays open until that run lands. If Claude pairs still miss the bar after salvage, the next lever is further prompt tightening (G4), not loosening the decoder.
**In the spec today:** v1.2 §7.4 (Native schema mechanism — flag only), §16 (≥19/20 reliability bar), C2/C16 (this is their 2.1.156 continuation). Surfaced by the Task 1.4.7 smoke re-run; tracked here so the validate-vs-enforce distinction is durable.
**Follow-up (Task 1.4.7, first real N=20 measurement):** the salvage + prompt tightening cleared design-review and refine, but **pr-review/claude measured 18/20** — one fail short of the bar. The two failures were *not* the prose-wrap case salvage already handles: the `result` started with a bare `{`, and salvage couldn't recover it. Diagnosis was done **without guessing**, per maintainer steer ("verify truncation, don't band-aid"): (1) an opt-in raw-envelope dump (`FORGE_REVIEWER_RAW_DUMP_DIR`) captured every reviewer call's full bytes; (2) a `resultDiagnostic` (length, `stop_reason`, `output_tokens`, brace-balance, raw-control-char count) was added to the failure `Left`. Offline analysis of a captured batch **ruled out truncation definitively** — every envelope parsed whole (so our stdout draining never truncated), every `stop_reason=end_turn` (so the model was never cut off), every object brace-balanced, and outputs reached ~11.8k tokens with no cap hit. A local `ujson` probe confirmed `ujson` **rejects raw control chars inside strings** (`control char (10) in string`) but accepts them once escaped. The model demonstrably emits raw newlines around its output (the prose-wrap dumps carry literal `\n\n`), so the residual pr-review failures are **literal newlines inside a multi-line `summary`** — legal inside the CLI's escaped `result` envelope field, illegal once that string's content is itself parsed as JSON.
**Decision (Task 1.4.7, the rational fix — not summary-shortening):** `parseResultPayload` now tries, most-trusting first: (1) clean `ujson.read`; (2) `normalizeControlCharsInStrings` — a string/escape-state-aware pass that escapes raw control chars occurring *inside* string literals (leaving whitespace between tokens, and already-escaped `\n`, untouched) — then re-read; (3) salvage the first balanced object from the normalised string (covers prose-wrap, now also with in-string newlines). This makes the parse tolerant of the model's formatting **without constraining review length or content** — the explicitly rejected alternative was forcing a one-line `summary`, which trades away legitimate review detail to dodge a serialization bug. The one mode the normaliser deliberately *cannot* repair is an unescaped `"` inside a string (ambiguous); that still surfaces `Left` with the underlying `ujson` reason + diagnostic, counting as a (rare) schema-fail within the 1/20 tolerance. Covered by `ClaudeConnectorSuite` (+5: in-string newline/tab recovery, prose+in-string-newline, unescaped-quote-still-fails-with-reason, two `normalizeControlCharsInStrings` unit cases). The §16 bar re-measurement for pr-review/claude under this fix is the remaining run; **C15 stays open until all six pairs clear ≥19/20.**

---

## Slice 2 spec deviations

### S2-1. Manifest data types live in `forge-core`, not `forge-specs` (slice-2 PR-A relocation)
**Decision (slice-2 PR-A):** `Manifest`, `ManifestPatch` / `ManifestPatchOp`, `Piece`, and `PieceStatus` (plus their suites and the `manifest-fixture.json` test resource) move from `modules/forge-specs/src/main/scala/io/forge/specs/` to `modules/forge-core/src/main/scala/io/forge/core/manifest/`. The package becomes `io.forge.core.manifest`. `forge-specs` keeps the rendering and persistence wrappers (`SpecStore`, `DocSync`, `ChangeCollector`) that land in Slice 4.
**Why this is a deviation:** v1.2 §3.2 names "Manifest" inside `forge-specs`. Slice 2 introduces `Feature` (which holds a `Manifest`) and `FsmState.PlanningUpdate(reason, patch: ManifestPatch)` — both in `forge-core`. Since `forge-specs` already depends on `forge-core`, leaving the manifest types in `forge-specs` would create a cycle (`forge-core.Feature` references `Manifest` in `forge-specs` which references `forge-core`). The relocation breaks the cycle in the direction the spec already implied — `Feature` lives in `forge-core` per §3.2, and the data it carries has to live there too.
**Rejected:** (a) **Inverted dependency** (`forge-core` depends on `forge-specs`) — pollutes the lowest-layer module with persistence/rendering concerns it doesn't need, and reverses the spec's stated module ordering. (b) **Duplicate the manifest types in `forge-core` and keep wrappers in `forge-specs`** — two definitions of the same domain shape drifting in parallel is exactly the bug-magnet the original placement was trying to avoid. (c) **Move `Feature` into `forge-specs`** — `Feature` is the FSM's state aggregate; the FSM transition function and tests live in `forge-core` per §17 Slice 2, and dragging both into `forge-specs` would make every Slice-2 module reach across the boundary.
**Action required for v1:** v1.3 spec correction needs to re-attribute the manifest data types to `forge-core` in §3.2 and narrow `forge-specs` to the rendering/persistence wrappers it actually owns. The `AGENTS.md` module-layout table is updated by PR-G G3 to reflect the new placement.
**In the spec today:** v1.2 §3.2 (states `forge-specs` ownership), §6 (`Feature`, `FsmState`, `ManifestPatch` are referenced from `forge-core` types). Surfaced inside PR-A so reviewers seeing the move have an immediate "why".

### S2-2. `FsmEvent` ADT shape not enumerated in v1.2 (slice-2 PR-B)
**Decision (slice-2 PR-B):** PR-B settled the `FsmEvent` ADT at 20 variants: `SessionSpawned`, `SessionResumed`, `Settled`, `SettleTimeout`, `TurnBudgetBreached`, `DesignReviewReceived`, `DesignReviewClarified`, `DesignPrSnapshotUpdated`, `BranchCreated`, `PrOpened`, `PrSnapshotUpdated`, `CodeReviewVerdict`, `CheckDiscoveryComplete`, `Merged`, `RefineOutcome`, `PlanningDecision`, `BudgetBreached`, `RequiredSessionIdMissing`, `UserCommandReceived`, `HarnessError`. Supporting enums (`SessionPhase`, `SettleOutcome`, `PlanningChoice`, `BudgetScope`, `UserCommand`) are co-located. The chosen variants become the de-facto contract for Slice 3 (`BranchManager` / `PRWatcher` / `SessionMonitor` produce these events) and Slice 4 (orchestrator loop).
**Why this is a deviation:** v1.2 §17 names `FsmEvent` as part of the slice-2 deliverable but doesn't enumerate the cases. v1.2 §11 implies most of them through the lifecycle prose, but the design intent — pure `(Feature, FsmEvent) ⇒ (Feature, Vector[ActionDraft])` — means every observable trigger has to be a typed event variant; the FSM can't read raw `gh` JSON, can't call a clock, can't call a logger. PR-B walked §11 step by step and listed each event the orchestrator has to manufacture for the FSM to advance. One PR-B engineering call was the design-PR-open trigger (§11.2 step 13): rather than add a new `DesignPrOpened(prNumber)` event, the FSM uses `DesignPrSnapshotUpdated(snapshot)` from `DesignReviewing(round)` as the trigger — keeps the ADT 1 case smaller without losing information.
**Rejected:** (a) **Per-state events** (e.g. `DraftingReady`, `DesignReviewingSettled`) — quadratic event count, and the FSM transition function ends up with a `(state, event)` cross-product that's mostly silent no-ops. The state-typed approach blocks straight-line reuse like `Settled(phase, outcome)` working across spec / design / impl / fixup. (b) **Open-typed events** carrying `Map[String, Any]` payloads — loses the compile-time guarantee that every variant has a `Fsm.transition` handler.
**Action required for v1:** v1.3 should lift the variant list into §6 or a new §11 sub-section. The current spec leaves it implicit, which leads reviewers re-deriving it from §11 prose every time.
**In the spec today:** v1.2 §17 (names `FsmEvent` as a slice-2 deliverable), §11 (lifecycle prose implies the variants); `modules/forge-core/src/main/scala/io/forge/core/fsm/FsmEvent.scala` is the de-facto contract.

### S2-3. `ActionLog` write durability vs. throughput (slice-2 PR-D)
**Decision (slice-2 PR-D):** `FileActionLog` writes via `Files.write(... CREATE, APPEND, SYNC)` — one OS write per `append` / per batch in `appendAll` (drafts are rendered to a single byte buffer then issued as one syscall). The single-write strategy reduces but doesn't eliminate the crash window between rendering and flush; durable correctness comes from `FileActionLog.replay`'s truncate-to-last-newline + `harness.error log_truncated` recovery contract (PR-D D5) plus `RebuildState.reconcile`'s case (b) repair (PR-E E4) for missing companion entries.
**Why this is a deviation:** v1.2 §19 says the action log is "append-only" without specifying fsync. PR-D's choice to sync on every write is the safer default — orchestrator crash semantics dominate the perf cost for typical Slice-1-feature throughput (single-digit actions per second peak). The trade-off becomes a live carry-forward only if Slice 4 surfaces a perf cliff: the fallback is per-batch `force()` after a non-syncing write, sacrificing durability for the final un-fsynced batch in exchange for batching the disk flush.
**Rejected:** (a) **No sync at all** — defers durability entirely to OS-managed flush, which on Linux is up to 30s by default; an unclean shutdown loses the most recent transitions, which the §11.5 atomic-merge contract specifically targets. (b) **Per-line sync via `BufferedWriter.flush` only** — JVM-side flush doesn't reach disk; needs `getChannel.force(true)` or `WRITE+SYNC` open option. (c) **Group commit / write coalescing** — overkill for v1; Slice 4 perf measurement would have to motivate it.
**Action required for v1:** none unless Slice 4 trips the perf cliff. If it does, the doc resolution is either (a) document the per-batch `force()` fallback in §19, or (b) keep `SYNC` as the documented default and let operators tune via a per-feature flag.
**In the spec today:** v1.2 §19 (append-only invariant), `modules/forge-core/src/main/scala/io/forge/core/log/FileActionLog.scala` (the impl + the truncate-and-recover repair contract that the durability story leans on).

### S2-4. `PrSnapshot` ownership doc mismatch in `AGENTS.md` (slice-2 PR-B / PR-G)
**Decision (slice-2 PR-B / PR-G):** `PrSnapshot` (plus `PrState`, `ReviewDecision`, `CheckState`, `CheckConclusion`, `CheckResult`, `CheckRollup`, `PrComment`) lives in `forge-core` under `io.forge.core.pr`. Per v1.2 §3.2 — `forge-core` owns the provider-neutral domain ADT; `forge-git` owns the `gh`-JSON parsing that produces a `PrSnapshot`. PR-G G3 updated `AGENTS.md`'s module-layout table to match (the pre-PR-G table placed `PrSnapshot` in `forge-git`, which would have created a cycle: `forge-core.Fsm.transition` consumes `PrSnapshot` events).
**Why this is a deviation:** v1.2 §3.2 names `PrSnapshot` under `forge-core`; `AGENTS.md`'s module-layout table was wrong, not the spec. The Slice-2 implementation matches the spec; PR-G fixes the doc.
**Rejected:** (a) **Place `PrSnapshot` in `forge-git`** — would require `forge-core` to depend on `forge-git`, reversing the module ordering and dragging GH-specific concerns into the FSM module. (b) **Duplicate the type across modules** — bug-magnet (cf. S2-1 rejected alternative b).
**Action required for v1:** none — §3.2 is correct. The doc fix lands with PR-G G3.
**In the spec today:** v1.2 §3.2 (correct), `modules/forge-core/src/main/scala/io/forge/core/pr/PrSnapshot.scala` (impl matches spec). Tracked here so the source-of-truth shift (`AGENTS.md` table → spec) is durable.

### S2-5. Writer-side atomic-merge ordering test deferred to Slice 4 (slice-2 PR-F)
**Decision (slice-2 PR-F):** F13 covers the reader side of §11.5 step 1 — that `RebuildState.run` correctly recovers from a crash between manifest write and FSM transition write, plus the four §11.5 reconcile sub-cases. The writer-side assertion (that the orchestrator atomically persists `manifest.json` before the FSM transition action and the state-cache write) is deferred to Slice 4's `design-1.4.md` carry-forward list as a gating test on the orchestrator-loop sub-PR.
**Why this is a deviation:** §17 slice-2 invariant 14 is "atomic merge mutation persists across crash". F13 is the slice-2 evidence for the reader side; the writer side needs an orchestrator under test (Slice 4) to exercise the actual write-sequence atomicity. Splitting the test across two slices keeps Slice 2 closed to its module boundary (`forge-core` has no orchestrator yet).
**Rejected:** (a) **Mock orchestrator in slice 2** — fake test scaffolding that asserts on the spec rather than the implementation; high risk of drift from the real Slice-4 wiring. (b) **Hold slice 2 open until Slice 4 lands** — couples slice closure to a downstream module; AGENTS.md's "section closures must explicitly carry deferrals forward" feedback memory exists exactly to permit deferrals like this.
**Action required for v1:** `design-1.4.md` (Slice 4) must open with this carry-forward in its §4 list and the writer-side atomic-merge test must be a gating check on the orchestrator-loop sub-PR.
**In the spec today:** v1.2 §11.5 step 1 (the writer contract), §17 slice-2 invariant 14 (the test obligation). Reader side: `modules/forge-core/src/test/scala/io/forge/core/property/F13AtomicMergeCrashSuite.scala`. Writer side: deferred.

### S2-6. `Feature.designPrFeedbackRound: Int` projection not in v1.2 §6 (slice-2 PR-C review round 1)
**Decision (slice-2 PR-C review round 1):** `Feature` carries `designPrFeedbackRound: Int = 0` — bumped on settle back to `DesignAwaitingMerge`, used as the "+1" base on the next entry to `DesignPrFeedback(prNumber, round + 1)`, reset to 0 on entering `DesignReady` (merge) or on `Resume(ReopenDesign)`. The field has a default so existing serialized `Feature` JSON decodes cleanly without the field.
**Why this is a deviation:** v1.2 §11.3 says `DesignPrFeedback(prNumber, round + 1)` but the §6 `Feature` case class as written doesn't carry the counter the "+1" reads from, and `DesignAwaitingMerge(prNumber)` has no `round` field to thread it through. Without the projection the FSM resets to `round = 1` on every cycle, reusing audit filenames (`design-pr-feedback-r1-answers.md`) and snapshot tags (`forge/_snapshots/<feat>/design-r1`) across successive cycles. PR-C round-1 review caught this; the fix is the new `Feature` field.
**Rejected:** (a) **Thread `round` through `DesignAwaitingMerge(prNumber, round)`** — pollutes the state-class signature with feedback-cycle bookkeeping that conceptually belongs to the `Feature` aggregate, not the FSM state. (b) **Store the counter in the action log only** — every `Fsm.transition` call would have to fold history to recover the current round; the projection field on `Feature` is exactly the §6.1-style cached projection the spec already uses for session ids.
**Action required for v1:** v1.3 §6 needs the field added to `Feature` (and optionally a §11.3 sentence naming the counter source).
**In the spec today:** v1.2 §6 (`Feature` case class missing the field), §11.3 (`+1` semantics imply a counter source). Tracked as design-2.2.md §4 carry-forward **S2-6**.

### S2-7. `fsm.transition` payload encodes the full `FsmState`, not the class-name tag (slice-2 PR-D)
**Decision (slice-2 PR-D):** `Fsm.fsmTransitionDraft` emits `payload.from` / `payload.to` as the **full** `FsmState` JSON via uPickle's derived enum encoding, rather than as bare class-name strings. uPickle's encoding rule produces bare strings for singleton enum cases (`Drafting`, `InteractiveSpec`, `DesignReady`, `FeatureDone`) and `{"$type": "...", ...}` objects for parameterised cases (`PieceImplementing(p)`, `PieceAwaitingMerge(p, prNumber)`, `Refining(p, prNumber, startedAt)`, etc.), so the §19 wire example `"from": "PieceImplementing"` is the singleton-case form of the new encoding rather than a contradiction of it.
**Why this is a deviation:** v1.2 §19's worked example shows `"from": "PieceImplementing", "to": "PieceAwaitingCi"` as bare strings — the natural reading is "states are tags". But PR-D's `Feature.foldEvents` and PR-E's `RebuildState.reconcile` need the parameterised values (piece id, prNumber, round, attempt, startedAt) to anchor structural matches like `t.from == PieceAwaitingMerge(p.id, p.prNumber.get)` in the reconciliation rule; tag-only encoding can't carry those. The alternative — keep tag strings and stash extras (prNumber, round, attempt) at the payload top level — leaks per-transition shape knowledge into the replay layer and breaks the symmetry between the in-memory `FsmState` ADT and its on-disk representation.
**Rejected:** (a) **Tag-only `from`/`to` + per-transition extras at payload top level** — the extras (`prNumber`, `round`, `attempt`, `startedAt`) would be per-transition shape knowledge duplicated between Fsm.scala's draft authoring and Replay's decoder, drifting independently. The reconcile rule already names `t.from == PieceAwaitingMerge(p.id, p.prNumber.get)` — that's a structural match, not a tag+extras match. (b) **Defer the encoding change to PR-E with reconcile** — PR-D needs the parameterised state in `ObservedTransition` for its own foldEvents tests (e.g. the "preserves parameterized states (PieceAwaitingMerge → Refining)" case). Splitting the change across two PRs would land PR-D against a contract that doesn't exist yet.
**Action required for v1:** v1.3 §19 needs to lift the worked example from a singleton case (`PieceImplementing` → `PieceAwaitingCi`) to a parameterised case so the encoding is documented, not just illustrated. E.g. `"from": {"$type": "PieceAwaitingMerge", "p": "p1", "prNumber": 4291}, "to": {"$type": "Refining", "p": "p1", "prNumber": 4291, "startedAt": "2026-05-26T12:00:05Z"}`.
**In the spec today:** v1.2 §19 worked example, v1.2 §6 (`FsmState` ADT), design-2.2.md §1.4 D4 (Replay.foldEvents) + §1.5 E4 (reconcile rule). Tracked as design-2.2.md §4 carry-forward **S2-7**.

### S2-8. `Fsm.transition` does not handle `SettleTimeout` for three `SessionPhase` variants (slice-2 PR-G review)
**Decision (slice-2 PR-G section review):** `Fsm.transition` routes `SettleTimeout(SessionPhase.Spec | DesignRevision | Implement | Fixup, _)` to `NeedsHumanIntervention` with a phase-appropriate `ResumeHint`. `SettleTimeout(SessionPhase.DesignReview | CodeReview | Refine, _)` falls through to the default no-op and leaves the FSM in `DesignReviewing` / `PieceAwaitingReview` / `Refining` respectively. The orchestrator can route around this by converting reviewer/refine settle-timeouts to `HarnessError` events at the call site, but the contract isn't documented anywhere.
**Why this is a deviation:** v1.2 §7.9 names settle as a per-phase concern with a per-phase timeout, and §7.1 (`reviewDesign` / `reviewPr` / `refine`) are reviewer phases that have a settle obligation. The Slice-2 FSM only handles the four driver-side phases; the three reviewer/refine phases are silent no-ops. Per AGENTS.md's "invariants enforced at every path" feedback memory, silent no-op on a legitimate FSM input is the failure mode the design discipline exists to catch.
**Rejected:** (a) **Add explicit handlers in PR-G fixups** — the reviewer-phase settle semantics interact with the reviewer-asset PR (Slice 4) and the C15 regression suite; introducing handlers without exercising them in a Slice-4 test could lock in the wrong NHI hint. (b) **Document orchestrator-side conversion as part of `Fsm.transition`'s scaladoc only** — pushes a load-bearing contract into a comment that won't get re-checked when Slice 4 wires up the reviewer settle path.
**Action required for v1:** Slice 4 (orchestrator wiring) must decide whether to (i) add explicit `SettleTimeout(SessionPhase.{DesignReview,CodeReview,Refine}, _)` handlers in `DesignReviewing` / `PieceAwaitingReview` / `Refining` routing to phase-appropriate `NHI` hints, or (ii) document in `Fsm.transition`'s scaladoc that the orchestrator must convert reviewer/refine settle-timeouts to `HarnessError`. Either way add a `ResumeHintCoverageSuite` row for the resulting paths.
**Resolution path chosen (Slice 1.4a Task 1.4.2, 2026-05-28):** option **(i)** — explicit FSM handlers. Slice 1.4a's reviewer-call wrapper (`io.forge.app.reviewer.ReviewerCall` / `RealReviewerCall`) emits `ReviewerOutcome.Timeout` on a wall-clock cap fire; the Slice 1.4b orchestrator (Task 1.4.10) will map that to `FsmEvent.SettleTimeout(SessionPhase.{DesignReview, CodeReview, Refine}, reason)`, and Task 1.4.12 will land the explicit `Fsm.transition` handlers routing each to phase-appropriate `NHI` hints plus the matching `ResumeHintCoverageSuite` rows. Rationale: symmetry with the driver-phase `SessionMonitor` / FSM surface — option (i) keeps the contract uniform across all seven `SessionPhase` variants instead of carving the three reviewer phases into a different routing channel that future readers would have to discover.
**In the spec today:** v1.2 §7.9 (per-phase settle), v1.2 §11 (lifecycle prose covers driver phases explicitly, reviewer/refine settle implicit). `modules/forge-core/src/main/scala/io/forge/core/fsm/Fsm.scala` handles 4 of 7 `SessionPhase` variants. Wrapper side closed at Slice 1.4a Task 1.4.2 (`modules/forge-app/src/main/scala/io/forge/app/reviewer/ReviewerCall.scala`); FSM side closes at Slice 1.4b Task 1.4.12.

### S2-9. `StateCache.verifyAgainstLog` always writes the cache, even on Consistent (slice-2 PR-G review)
**Decision (slice-2 PR-G section review):** `FileStateCache.verifyAgainstLog` calls `RebuildState.run` unconditionally on every invocation, which always calls `cache.save` at pipeline step 6 — so every consistency check writes a temp file, fsyncs it, renames it over the target, and fsyncs the parent. The current implementation is correct (the cache file content is byte-equal to what was there) but wasteful: it fires on every orchestrator restart, every `forge resume`, and (depending on Slice-4 wiring) potentially after every FSM transition.
**Why this is a deviation:** v1.2 §11.0 step 4 says the state cache is "verified against log replay; rewritten if divergent". The implementation rewrites unconditionally, which is functionally correct under the spec but pays the temp+rename+fsync cost on every steady-state run. Slice 2 (`forge-core`) has no orchestrator under load, so the cost is invisible in unit tests; Slice 4 will surface it.
**Rejected:** (a) **Compare cached `Feature` to the rebuild and only `save` on divergence** — this is the cleanest fix but requires the `Feature` equality semantics to be byte-identical with the on-disk uPickle round-trip; subtle (Instant-precision drift, Option re-ordering) could make `==` lie. (b) **Cache a fingerprint (manifest mtime + last log seq) and skip the rebuild when the fingerprint matches** — best fast path but introduces a new on-disk artefact; needs design-doc treatment in Slice 4.
**Action required for v1:** Slice 4 needs to either (a) compare the cached `Feature` to the rebuilt one (byte-identical assertion via uPickle round-trip) and skip `save` on match, or (b) introduce a manifest+log fingerprint cache. The choice depends on whether real-world workloads see `verifyAgainstLog` fire frequently enough to matter.
**In the spec today:** v1.2 §11.0 step 4, `modules/forge-core/src/main/scala/io/forge/core/state/FileStateCache.scala:84-111`, `modules/forge-core/src/main/scala/io/forge/core/state/RebuildState.scala` (the unconditional pipeline-step-6 `save`).

### S2-10. `audit.piece_merged` payload key tightened to `"p"` only (slice-2 PR-G fixups)
**Decision (slice-2 PR-G fixups):** `Replay.applyAuditPieceMerged` accepts the piece-id key as `"p"` only — any other spelling surfaces as `MalformedPayload`. The pre-PR-G implementation accepted `"p"` OR `"piece"` as a fallback, but writers (`Fsm.auditPieceMergedDraft` and `RebuildState.syntheticAuditDraft`) only ever emit `"p"`. The fallback was dead code that risked silently rewriting a hand-edited log on the next repair pass.
**Why this is a deviation:** v1.2 §19 names `audit.piece_merged` as a `kind` but doesn't pin the payload schema. PR-D chose `{ p, prNumber, mergeCommit, mergedAt }` to match the §11.5 step 1 wording (which uses `p` as the piece variable). The `.orElse("piece")` was a copy-paste artefact from `harness.crash_recovered` (which legitimately uses `"piece"`); leaving it in would have allowed two spellings to silently co-exist.
**Rejected:** (a) **Accept both spellings and pick one as canonical** — invites operator confusion and risks log replay drifting from the writer side. (b) **Defer the tightening to a future migration** — there are no production logs yet (Slice 2 just landed); now is the cheapest time to make the wire form strict.
**Action required for v1:** v1.3 §19 should pin the `audit.piece_merged` payload schema explicitly (`{ p, prNumber, mergeCommit, mergedAt }`) so future implementations don't re-derive the alias question.
**In the spec today:** v1.2 §19 (names the `kind`, doesn't pin the schema), `modules/forge-core/src/main/scala/io/forge/core/log/Replay.scala:359` (accepts `"p"` only).

---

## Slice 3 spec deviations

### S3-1. `forge-git` invokes `gh` / `git` via `os-lib` directly, not the `forge-agents.Subprocess` streaming wrapper (slice-3 PR-A)
**Decision (slice-3 PR-A):** `forge-git`'s `RealGhClient` and `RealGitClient` call `os.proc(argv).call(cwd = repoRoot, env = ..., check = false, stderr = os.Pipe)` for every `gh` / `git` invocation. `forge-git` does **not** depend on `forge-agents`; the `Subprocess` streaming wrapper stays where Slice 1 placed it. The shared `invoke(argv, stdinIfAny): IO[Either[GhError, String]]` helper inside `RealGhClient` (and its `GitError` sibling inside `RealGitClient`) owns exit-code / stderr classification — there is no shared subprocess utility in `forge-core`.
**Why this is a deviation:** v1.2 §3.3 / §22 say "CLIs only" but don't pin which subprocess primitive each module uses. Slice 1 introduced `forge-agents.Subprocess` as a streaming wrapper sized for the long-lived Claude / Codex sessions (per-line stdout `Stream[IO, String]`, separate stderr, SIGTERM-grace-SIGKILL kill semantics). Slice 3's `gh pr view` / `git fetch` / `git push` / `git tag` invocations are **one-shot**: command runs, exits, stdout + stderr + exit code captured in one go. `os.proc(...).call` returns a `CommandResult` carrying exactly that triple, blocking until exit. No streaming, no kill protocol, no fs2 stream lifecycle.
**Rejected:** (a) **Lift `Subprocess` into `forge-core`** — adds `fs2-core` + `fs2-io` deps to the lowest-layer module (currently `cats-effect` + `upickle` + `os-lib`) for a use case (one-shot invocation) that doesn't need streaming. `forge-core` would carry the streaming primitive purely so `forge-git` can wrap it back into a blocking call. (b) **Depend on `forge-agents` from `forge-git`** — reverses the module-layout intent (`forge-agents` owns CLI-of-Claude / CLI-of-Codex adapters; nothing in `forge-git` cares about agent traits). Drags `AgentEvent`, `Connector`, and `Prompts` into a transitive classpath that has no business with PR shape. (c) **Introduce a third subprocess utility shared by both modules** — minimum viable shape would be a `cats-effect`-flavoured wrapper around `os.proc.call`, and the only consumer is `forge-git`; premature abstraction with no other caller.
**Action required for v1:** none — this is a module-layout call, not a spec gap. No v1.3 §3.2 / §3.3 edit needed. The carry-forward exists so a future contributor wondering "why isn't there one subprocess utility?" can read the answer without re-deriving it from grep failures.
**In the spec today:** v1.2 §3.3 (CLIs only), §22 (orchestrator talks to CLIs); `modules/forge-agents/src/main/scala/io/forge/agents/Subprocess.scala` (streaming wrapper, Slice-1 surface); `modules/forge-git/src/main/scala/io/forge/git/cli/` (one-shot `os.proc.call` callers, Slice-3 surface).

### S3-3. `GhClient` / `GitClient` are trait + `Real…` impl + `Fake…` fixture abstractions, not v1.2-mandated (slice-3 PR-A)
**Decision (slice-3 PR-A):** `io.forge.git.cli` exposes two effect-typed traits — `GhClient` (`prView`, `prCreate`, `prUpdateBranch`, `prDiff`, `apiBranchProtection`) and `GitClient` (`currentBranch`, `currentSha`, `fetch`, `fastForwardBase`, `checkout`, `push`, `tag`, `pushTag`, `deleteRemoteTag`, `isWorktreeClean`, `branchExistsLocal`, `branchExistsRemote`, `listTags`, `deleteLocalTag`). Concrete `RealGhClient(repoRoot, env)` / `RealGitClient(repoRoot)` are the production wiring; `FakeGhClient.builder.…build` / `FakeGitClient.builder.…build` are the test fixtures. `BranchManager` and `PRWatcher` consume the traits, not the `Real…` types, so every §9 / §11.3 / §11.4 / §11.5 decision path is exercisable against an in-memory fake.
**Why this is a deviation:** v1.2 §9 enumerates `BranchManager` and `PRWatcher` methods but doesn't pin an inner abstraction — the §9 surface could legitimately be implemented as direct `os.proc.call` calls inside each manager method. Slice 3 deliberately introduces the inner trait layer because the §9 / §11.3 / §11.4 logic is decision-rich (preflight rules, base-freshness branching, force-lease handling, rate-limit back-off, baseline cursor advancement) and exercising those decisions against a real `gh` would require a sacrificial repo per test. The `forge-it` integration suite covers the happy path against real `gh` + `git`; the unit-test surface covers everything else.
**Rejected:** (a) **Direct `os.proc.call` inside `BranchManager` / `PRWatcher` methods** — couples §9 logic to subprocess plumbing; every unit test of preflight or `baseFreshness` needs a stubbed `os.proc.call` or a real `gh` binary. The decision-rich logic becomes hard to exercise. (b) **Inject `os.Path` and let callers mock the filesystem** — `gh` JSON parsing isn't reproducible by writing files; `gh`'s exit-code-plus-stderr classification (rate-limit / 404 / auth / transient) is what the classifier exists for, and mocking only the filesystem misses that surface entirely. (c) **Single combined `CliClient` trait** — `gh` and `git` have different invocation shapes (`gh` returns JSON; `git` returns plain text or exit-code-only) and different error families (`GhError` includes rate-limit; `GitError` includes `ForceLeaseRejected`). One trait would either lose the typed distinction or grow `Either[GhError | GitError, A]` returns, which is worse than two separate traits.
**Action required for v1:** none — testability seam, not a spec gap. No v1.3 §9 edit needed; the trait + `Real…` / `Fake…` split is a forge-git internal contract. The carry-forward is filed so a future contributor wondering "why isn't this just `os.proc.call`?" can read the rationale.
**In the spec today:** v1.2 §9 (`BranchManager` + `PRWatcher` surface — does not pin inner abstraction); `modules/forge-git/src/main/scala/io/forge/git/cli/` (`GhClient.scala`, `GitClient.scala`, `RealGhClient.scala`, `RealGitClient.scala`, `GhError.scala`, `GitError.scala`); `modules/forge-git/src/test/scala/io/forge/git/cli/fake/` (`FakeGhClient.scala`, `FakeGitClient.scala` builder fixtures); every `BranchManager*Suite` / `PRWatcher*Suite` consuming the fakes.

### S3-4. `PRWatcher.PollResult.RateLimited` is a non-failing stream event; failure threshold is configurable (slice-3 PR-D)
**Decision (slice-3 PR-D):** `PRWatcher.watch(pr, baselineRef): Stream[IO, PollResult]` emits `PollResult.RateLimited(retryAfter: Option[FiniteDuration])` as a non-failing event on each `GhError.RateLimited` from `gh.prView`. The stream sleeps for `retryAfter` (or `config.github.rateLimitBackoffMs`, default 60s) and continues polling. A per-watch consecutive-rate-limit counter resets on any non-rate-limit result; the Nth consecutive `RateLimited` (default N=3, configurable on `PRWatcherConfig.consecutiveRateLimitsBeforeFailing`) is promoted to `PollResult.Failed(GhError.RateLimited(...))` so the orchestrator's `harness.rate_limited` audit-log writer can surface the breach. `Failed` doesn't terminate the stream either — the orchestrator decides whether to keep watching.
**Why this is a deviation:** design-rationale **RL1** says "back-off on 403/429" and v1.2 §19 names `harness.rate_limited` as the action-log signal, but neither pins whether rate-limit surfaces as a stream failure (and re-entry on next call) or a non-failing event (with internal back-off). Slice 3 chose the non-failing event so the orchestrator (Slice 4) sees the rate-limit in the same `PollResult` channel as `Snapshot` / `Failed`, owns logging, and doesn't have to re-enter the watch stream on every back-off. The N-consecutive-rate-limits soft cliff exists so a sustained rate-limit (auth misconfigured, secondary rate-limit triggered) eventually surfaces as `Failed` instead of silently looping forever.
**Rejected:** (a) **Surface rate-limit as a stream failure on first 403/429** — forces the orchestrator to re-enter the stream on every back-off; complicates the back-off bookkeeping (the orchestrator would need its own retry counter). (b) **Hard-fail after one rate-limit** — sustained rate-limit is a legitimate condition on busy repos; first-occurrence failure is too brittle. (c) **No threshold; emit `RateLimited` indefinitely** — silently looping if `gh` is permanently broken (auth mis-set) means the orchestrator never surfaces the issue. The N-threshold is the compromise: transient rate-limit absorbed internally, persistent rate-limit surfaces as `Failed` for orchestrator handling.
**Action required for v1:** none unless Slice 4 surfaces a different cliff shape. Roadmap §7.2.3 carries S3-4 as a watch item; if Slice 4's orchestrator finds the soft 3-consecutive cliff too lenient (e.g. wants first-rate-limit-is-Failed for an interactive `forge run`) or too strict (e.g. wants N config-driven by `pollIntervalMs`), the threshold becomes a v1.3 §18 config knob shaped by lived experience.
**In the spec today:** design-rationale RL1 (rate-limit + caching); v1.2 §9 (`PRWatcher`), §18 (`rateLimitBackoffMs`), §19 (`harness.rate_limited`); `modules/forge-git/src/main/scala/io/forge/git/watcher/PRWatcher.scala` (`PollResult` ADT), `PRWatcherConfig.scala` (`consecutiveRateLimitsBeforeFailing` knob), `RealPRWatcher.scala` (per-watch counter); `modules/forge-git/src/test/scala/io/forge/git/watcher/PRWatcherRateLimitSuite.scala` (cliff pinned).

### S3-5. `SessionMonitor` covers only the four driver phases; reviewer/refine wall-clock caps live in the reviewer-asset wrapper (slice-3 PR-F)
**Decision (slice-3 PR-F):** `SessionMonitor.monitor(phase, session, events, limits, runningTotals)` accepts `SessionPhase.{Spec, DesignRevision, Implement, Fixup}` only. Reviewer (`SessionPhase.{DesignReview, CodeReview}`) and refine (`SessionPhase.Refine`) are refused at the precondition layer with `IllegalArgumentException` so a future Slice-4 caller can't silently route the wrong phase through. `SessionMonitor.DriverPhases` is the single source of truth shared with `SessionMonitorPhaseCoverageSuite`.
**Why this is a deviation:** v1.2 §12 / §7.9 name "settle bounds" and "budget enforcement" generically and don't carve out reviewer/refine. Slice 3 chose the carve-out because reviewer/refine sessions are one-shot adapter calls (`reviewDesign`, `reviewPr`, `refine`) that don't expose a `Stream[IO, AgentEvent]` to monitor — they invoke `claude exec -p ...` / `codex exec -p ...` in headless mode and consume `extractStructuredOutput` / `extractAgentMessageText` from the captured stdout. There is no per-turn event stream to track, no `AgentEvent.CostUpdate` to accumulate, no `session.kill()` to invoke; the only meaningful enforcement surface is a wall-clock cap on the whole one-shot call. Slice 4A's reviewer-asset wrappers ship that wall-clock cap colocated with `ReviewerAssets` so the per-reviewer-call timeout config and the kill mechanic stay together.
**Why this mirrors S2-8:** Slice 2 carry-forward **S2-8** records that `Fsm.transition` silently no-ops on `SettleTimeout(SessionPhase.{DesignReview, CodeReview, Refine}, _)`. S3-5 is the SessionMonitor-side equivalent: the FSM has no handler for reviewer/refine SettleTimeouts because Slice 3 deliberately never emits them. Slice 4A closes both sides of the carve-out together — either by adding explicit FSM handlers for the reviewer/refine phases and extending the SessionMonitor surface, or by lifting the reviewer wall-clock cap into the orchestrator's reviewer-call wrapper and documenting the SessionMonitor-side absence.
**Rejected:** (a) **SessionMonitor covers reviewer/refine via a synthetic event stream** — would require a fake `Stream[IO, AgentEvent]` carrying only the wall-clock signal; over-engineering when the reviewer call is already a one-shot blocking IO. (b) **Move the wall-clock cap to a separate `ReviewerMonitor` in `forge-app`** — fragments the monitor surface across two classes for no functional gain; the wall-clock cap is naturally part of the reviewer-call wrapper. (c) **Accept the silent route and rely on overall session-cost budgets to catch a runaway reviewer call** — too lenient: a stuck reviewer call could burn the whole feature budget before the cost cap fires.
**Action required for v1:** Slice 4A closes the carve-out per **S2-8** (explicit reviewer/refine FSM handlers or documented orchestrator-side conversion). No separate v1.3 §12 / §7.9 edit needed beyond what S2-8 already calls for; S3-5 is the SessionMonitor-side anchor.
**Resolution path (Slice 1.4a Task 1.4.2, 2026-05-28):** wrapper side landed. `io.forge.app.reviewer.ReviewerCall` / `RealReviewerCall` ship the reviewer/refine wall-clock cap via `IO.race(IO.sleep(cap), connector.review*.attempt)`, surfacing `ReviewerOutcome.Timeout` on cap fire. The wrapper deliberately does **not** emit `MonitorOutcome.SettleTimeout` — that is `SessionMonitor`'s driver-phase surface and reaching across would muddle the carve-out. Mapping `ReviewerOutcome.Timeout` to `FsmEvent.SettleTimeout(...)` is the orchestrator's responsibility per the S2-8 option (i) resolution; Task 1.4.10 wires it, Task 1.4.12 closes the FSM-side handlers + `ResumeHintCoverageSuite` rows.
**In the spec today:** v1.2 §7.9 (settle bounds), §12 (budget enforcement), §17 slice 3 (SessionMonitor scope); `modules/forge-app/src/main/scala/io/forge/app/monitor/SessionMonitor.scala` (trait + `DriverPhases`), `RealSessionMonitor.scala` (precondition check naming S3-5 / S2-8); `modules/forge-app/src/test/scala/io/forge/app/monitor/SessionMonitorPhaseCoverageSuite.scala` (precondition pinned). Wrapper-side closure landed at Slice 1.4a Task 1.4.2: `modules/forge-app/src/main/scala/io/forge/app/reviewer/ReviewerCall.scala`, `RealReviewerCall.scala`, `ReviewerOutcome.scala` and the suites `ReviewerCallWallClockSuite` / `ReviewerCallHappySuite`.

### S3-7. `PollBaseline` uses `BaselineCursor(at: Instant, seenIds: Set[String])`, not `databaseId: Long` (slice-3 PR-B review rounds 1 & 2)
**Decision (slice-3 PR-B, two review rounds).** `PollBaseline` fields are `commentCursor: Option[BaselineCursor]` and `reviewCursor: Option[BaselineCursor]`; `BaselineCursor(at: Instant, seenIds: Set[String])` couples a watermark timestamp with the set of entry ids observed exactly at that timestamp. `Comments.unseen` retains entries where `at.isAfter(cursor.at) || (at == cursor.at && !cursor.seenIds.contains(id))`. `Comments.advance` computes the next cursor from the **full** observed `(at, id)` set; the decoder exposes the result as `DecodedSnapshot.nextBaseline` so the orchestrator persists exactly what the next poll needs. `PrComment.id` is the GraphQL global node id string (e.g. `"IC_kwDOAB..."`, `"PRR_kwDOAB..."`) as returned by `gh pr view --json comments,reviews`. The `lastSeenCheckRunIds` slot survives but is typed `Set[String]` — its semantics are deferred to Slice 4.
**Why this is a deviation (round 1):** the original PR-B plan (and the v1.2 reading of design-rationale RL2) keyed every baseline on `databaseId: Long`. Empirically (`gh` 2.83.1, cli/cli#13506): `gh pr view --json comments,reviews` does **not** return `databaseId`; each entry carries a String `id` (GraphQL global node id) plus `createdAt` / `submittedAt`. Requesting `databaseId` from the decoder would have made `PRWatcher.pollOnce` fail with `MissingField("comments[0].databaseId")` on every real PR with comments or reviews — i.e. failure exactly when human feedback exists. The original Long-via-Double parsing concern (`ujson.Num.toLong` truncates above 2^53) dissolves with the type switch.
**Why the round-2 tie-breaker:** `gh` timestamps have one-second resolution; bare `at.isAfter(cursor)` would drop any entry created in the same second as the prior high-watermark. Concrete scenario: PR poll at T sees comment A (`createdAt = T`); human posts comment B at `createdAt = T` between poll-fire and the next persisted-cursor flush; next poll's filter excludes B forever because `T.isAfter(T) == false`. The `seenIds` set tracks which ids at the watermark Forge has already routed; B's id is unknown, so B surfaces. `Comments.advance` accumulates `seenIds` across polls that share a watermark, so the second poll persists `Set(A.id, B.id)` and the third poll correctly excludes both.
**Rejected:** (a) **Issue an extra `gh api graphql` query per poll** to fetch `databaseId`s — doubles the polling rate against GitHub for a baseline that timestamps already satisfy; pushes the `gh` rate-limit envelope (design-rationale RL1) closer for no functional gain. (b) **Use the GraphQL global `id` string** as the baseline — strings aren't ordered (the id payload is base64-of-opaque-bytes), so "is this entry newer than baseline" can't be derived from id comparison alone; we'd still need a parallel timestamp. (c) **Compare timestamps as ISO-8601 strings** rather than `Instant` — ISO-8601 with `Z` suffix DOES sort lexicographically, but the `Instant`-typed comparison is more readable and explicit about intent, with no downside. (d) **Skip the tie-breaker and accept lost same-second feedback** — silently losing CHANGES_REQUESTED replies or late merge-gate comments is exactly the failure mode RL2 exists to prevent.
**Empty-body filter (round 1):** `PrSnapshotDecoder` drops entries with empty `body` at decode time. GitHub allows empty-bodied review submissions (plain approvals); the FSM treats `unseenComments.nonEmpty` as a "human override" signal, so an empty approval would spuriously kick a piece back to `PieceReviewFailed`. Blocking review state still flows through `reviewDecision == CHANGES_REQUESTED`. Empty-body entries still feed `Comments.advance` so the cursor records them as observed.
**Action required for v1:** **v1.3 spec edit** in design-rationale RL2 and v1.2 §6 / §9: pin the cursor as `BaselineCursor(at, seenIds)`, name `id` as the String identifier, and call out the round-2 same-second mechanic. The empty-body filter belongs in v1.3 §9 (`PRWatcher.watch`) alongside the bot-author filter. Update v1.3 BM8 to confirm `gh pr view --json comments,reviews` schema does not include `databaseId`.
**In the spec today:** v1.2 §6 (`PrSnapshot`), §9 (`PRWatcher`), §11.4 step 6 (baseline captured at PR creation); `modules/forge-git/src/main/scala/io/forge/git/watcher/PollBaseline.scala`, `Comments.scala`, `PrSnapshotDecoder.scala`, `DecodedSnapshot.scala`; `modules/forge-git/src/test/scala/io/forge/git/watcher/CommentsSuite.scala` (tie-breaker + `advance` unit tests); `modules/forge-git/src/test/resources/gh-pr-view/open-with-comments.json` + `open-empty-approval.json` (fixture-level assertions).

### S3-6. `gh pr create` has no `--json` flag; PR-number capture is via stdout-URL regex (slice-3 PR-A)
**Decision (slice-3 PR-A).** `RealGhClient.prCreate` parses the PR number out of the trailing `/pull/<n>` segment of `gh pr create`'s stdout, using the pinned regex `^https?://[^/]+/[^/]+/[^/]+/pull/(\d+)\s*$`. A no-match surfaces as `GhError.ParseFailure("pr-create-url", _, raw)` rather than a silent truncation, so a future `gh` stdout-framing change is loudly diagnosable.
**Why this is a deviation:** design-rationale **BM8** names `gh pr create --json url -q .url` as the PR-number-capture pattern, but `gh pr create` has no `--json` flag on any released `gh` (verified against `gh 2.83.1` and 2.84.x — `gh pr view` is the JSON-shaped command, not `gh pr create`). Following BM8 verbatim would fail every Slice-3 piece-PR creation with a usage error. The stdout-URL form is the only contract `gh pr create` actually offers.
**Rejected:** (a) **Two-call form: `gh pr create … && gh pr view <url> --json number -q .number`** — works, but doubles the API surface for a baseline that the URL regex already satisfies; surfaces the same auth / rate-limit paths twice per PR creation for no functional gain. (b) **Issue `gh api repos/{owner}/{repo}/pulls?head=<branch>&state=open`** post-create to look up the number — also doubles the round-trip and depends on `gh`'s implicit `{owner}/{repo}` resolution being accurate on a freshly-cloned repo (failure mode: ambiguous match across forks). (c) **Switch BM8's contract to `gh pr create --json` and wait for upstream** — `gh` may eventually grow `--json` on `pr create`, but Slice 3 ships against the binary that exists today.
**Action required for v1:** **v1.3 spec edit** in design-rationale **BM8**: replace `gh pr create --json url -q .url` with `gh pr create … | parse /pull/<n>/`, naming the stdout-URL contract as the supported behaviour. Optionally add the two-call form as a `prNumberCaptureMode: "url-regex" | "two-call"` config knob for installations behind a strict-no-stdout proxy. The carry-forward is filed so a v1.3 reader doesn't try to "fix" the regex-based code by reverting to a non-existent `--json` flag.
**In the spec today:** v1.2 §9 (`BranchManager.createPr` → `gh.prCreate`); design-rationale BM8; `modules/forge-git/src/main/scala/io/forge/git/cli/RealGhClient.scala` (`parsePrCreateUrl`, `PrUrlPattern`); `modules/forge-git/src/test/scala/io/forge/git/cli/RealGhClientSuite.scala` (URL parse unit tests); `modules/forge-it/src/test/scala/io/forge/it/BranchManagerIntegrationSuite.scala` (PR-G G2 end-to-end against a real `gh pr create`).

### S3-8. `reviewDecision: ""` (empty string) decodes as `None` — `gh` flattens GraphQL null to empty string (slice-3 PR-G IT-surfaced)
**Decision (slice-3 PR-G, IT-surfaced).** `PrSnapshotDecoder.decodeReviewDecision` treats the string `""` identically to `null` / missing: both decode to `Option[ReviewDecision] = None`. Non-empty unknown strings still raise `UnknownEnumValue` so a future GitHub enum addition surfaces diagnosably.
**Why this is a deviation:** `gh pr view --json reviewDecision` on a brand-new PR with no associated reviews returns the JSON literal `""`, not `null`. The PR-B fixture suite covered the `null` case in `open-no-checks.json` but missed the empty-string case — it was surfaced by the PR-G sacrificial-repo IT (`BranchManagerIntegrationSuite`'s very first `pollOnce` against the newly-opened PR failed with `UnknownEnumValue("reviewDecision", "", _)`). Treating empty string as `None` is the only sensible decode because GitHub's GraphQL `reviewDecision` is nullable, and `gh`'s mapping of `null → ""` for this specific field is a documented quirk of the `gh` JSON output formatter rather than a model change. The decoder fix is one line; the fixture (`open-fresh-no-reviews.json`) + unit test (`PrSnapshotDecoderSuite`) pin the contract so a future fixture-only regression test catches it.
**Rejected:** (a) **Treat empty string as `UnknownEnumValue` and crash** — wrong: every brand-new piece PR would fail its first poll, which is exactly when the orchestrator needs the snapshot to drive `PrOpened`. (b) **Generalize the empty-string-as-None handling to every enum decoder** — over-fitting: `state` / `mergeable` / check states are non-nullable in GraphQL and `gh` never emits empty strings for them; broadening the rule would mask future contract drift on the non-nullable fields. The fix is `reviewDecision`-scoped exactly because that's the only nullable enum on the `gh pr view --json …` surface.
**Action required for v1:** none — code-only fix; v1.2 spec doesn't pin the wire-shape of `reviewDecision = null`. v1.3 §9 should note the `gh` null-flattening quirk on the `reviewDecision` field so a future contributor reading the spec alongside `gh` output sees the alignment.
**In the spec today:** v1.2 §6 (`PrSnapshot.reviewDecision: Option[ReviewDecision]`), §9 (`PRWatcher.DefaultFields` includes `reviewDecision`); `modules/forge-git/src/main/scala/io/forge/git/watcher/PrSnapshotDecoder.scala` (`decodeReviewDecision`'s `Some("") => Right(None)` branch + scaladoc); `modules/forge-git/src/test/scala/io/forge/git/watcher/PrSnapshotDecoderSuite.scala` ("open-fresh-no-reviews" test); `modules/forge-git/src/test/resources/gh-pr-view/open-fresh-no-reviews.json` (fixture).

---

## Slice 4 spec deviations

### S4-2. `forge replay` cut in favour of `forge tail`; `ForgeCommand.ReadOnlyKind.Replay` → `Tail` (slice-1.4b Task 1.4.9)
**Decision (slice-1.4b Task 1.4.9):** Slice 1.3 Task 1.3.3 landed `io.forge.git.branch.ForgeCommand.ReadOnly(kind)` with `ReadOnlyKind = Status | Replay | RebuildState`, carrying `Replay` (batch render-from-action-log) forward from an early sketch. Slice 1.4 ships **`forge tail`** (live tail of `.forge/log/<feature>.jsonl`) instead — that is what the §2.5 polish list and the §17/§15 command surface actually name. `ReadOnlyKind` becomes `Status | Tail | RebuildState`; the §17 command set the CLI routes is `new | spec | run | status | resume | reconcile | refresh-cache | abandon | rebuild-state | unlock --force | tail`.
**Why this is a deviation:** `Replay` had no §17 / §15 command-line anchor — §15 mentions "`replay`" only inside a `ForgeCommand` table comment, never as an operator command. Carrying it as a live `ReadOnlyKind` would imply a `forge replay` command the spec never defines, and would need a handler shell + router row that points nowhere.
**Why this is OK:** No v1.3 spec edit is required — §15's prose command surface does not list `replay`, so dropping the enum case removes an unanchored placeholder rather than a specified command. The one production reference (`RealBranchManager` preflight) matches on `ReadOnly(_)` regardless of kind, so the read-only preflight contract is unchanged; the only test touch is the audit-tag string. If a batch render-from-log feature is wanted later it can be re-introduced as a first-class §15 command at that point.
**In the spec today:** v1.2 §2.5 (polish list names `forge tail`), §15 (command/preflight table — `replay` only in a code-comment, not the command line), §17 slice 4 (full command set). `modules/forge-git/src/main/scala/io/forge/git/branch/ForgeCommand.scala` (`ReadOnlyKind`), consumed by `RealBranchManager.preflight` (`ReadOnly(_)`).

### S4-3. Reviewer call cost + observable kill diagnostics are out of scope in Slice 1.4a (slice-1.4a Task 1.4.2)
**Decision (slice-1.4a Task 1.4.2):** `io.forge.app.reviewer.ReviewerCall` wraps `Connector.reviewDesign` / `reviewPr` / `refine` with a wall-clock cap only. `ReviewerLimits` carries `wallClockTimeout: FiniteDuration` and **no `maxCostUsd`**. `ReviewerOutcome.Settled[A]` carries `result: A` and **no `cost: Cost`**. `ReviewerOutcome.Timeout` carries no `killError: Option[String]` field — on cap fire the wrapper cancels the in-flight reviewer fiber via `IO.race`'s loser-cancellation path and lets the connector's enclosing `Resource` finalizer handle any subprocess cleanup, without re-exposing whether SIGTERM/SIGKILL actually landed.
**Why this is a deviation:** v1.2 §12 names per-feature / per-piece / per-turn USD caps as universal budget invariants, and v1.2 §7.9 names settle bounds + kill discipline as per-phase concerns. The Slice 1.4a wrapper only enforces the wall-clock half on the reviewer side. The underlying connector boundary is the constraint: `Connector.reviewDesign` / `reviewPr` / `refine` return `IO[A]` (just the typed review value — no cost projection, no subprocess handle), and the one-shot reviewer collectors in `forge-agents` (`extractStructuredOutput` / `extractAgentMessageText`) do not emit `AgentEvent.CostUpdate` the way the streaming driver pipeline does. Surfacing cost or kill diagnostics through `ReviewerCall` would require a connector-boundary change that ripples through every existing connector suite and the Task 1.4.7 native-schema regression suite — disproportionate for a one-shot blocking adapter call that the wall-clock cap already bounds.
**Rejected:** (a) **Widen `Connector.review*` to return `IO[(A, Cost)]` in Slice 1.4a** — affects both connectors, every connector suite (`Claude*ConnectorSuite`, `Codex*ConnectorSuite`), the reviewer integration tests in `forge-it`, and the Task 1.4.7 regression suite scaffolding. Slice 1.4a's writable foundation has no orchestrator caller yet to verify the cost wiring against, so the change would land blind. (b) **Plumb `AgentEvent.CostUpdate` through the one-shot reviewer collectors** — would require the headless reviewer pipeline to emit cost events the way the streaming pipeline does, which means re-architecting `ClaudeConnector.reviewDesign` / `CodexConnector.reviewDesign` to feed an event channel rather than a one-shot collector. Same blast radius as (a), no orchestrator caller to validate against. (c) **Surface a kill-error channel on `ReviewerOutcome.Timeout`** — the connector exposes no subprocess handle to the wrapper; manufacturing one would mean exposing `Subprocess` (currently a `forge-agents` internal) through the `Connector` trait. Same connector-boundary widening as (a)/(b).
**Action required for v1:** Slice 1.4b Task 1.4.17 evaluates whether MVP-run cost data shows reviewer spend cliffs worth closing inside Phase 1. If it does, the closure path is either widening `Connector.review*` to return `(A, Cost)` (or a richer `ReviewerCall.Settled[A]` type that includes cost) **or** plumbing `AgentEvent.CostUpdate` through the one-shot reviewer collectors so the orchestrator's `Feature.cost` projection picks them up. If MVP-run data shows the reviewer spend is bounded by the wall-clock cap already, S4-3 rolls into v1.3 as a documented limitation: **the §12 budget caps (`maxFeatureCostUsd` / `maxPieceCostUsd` / `maxTurnCostUsd`) are driver-session-only invariants for v1**. v1.3 §7.1 reviewer-method signatures and v1.3 §12 budget-scope wording are the spec edit surfaces if closure happens.
**In the spec today:** v1.2 §7.1 (reviewer methods return typed review values, no cost), v1.2 §12 (budget caps phrased as universal but only the driver-session pipeline emits the `CostUpdate` events that feed `Feature.cost`). `modules/forge-agents/src/main/scala/io/forge/agents/Connector.scala` (`reviewDesign / reviewPr / refine: IO[A]`), `modules/forge-app/src/main/scala/io/forge/app/reviewer/ReviewerCall.scala` (`ReviewerLimits` + `ReviewerOutcome.Timeout` carry no cost / killError fields, docstrings name S4-3 as the closure anchor).

---

## CI policy

### CI1. CI policy has two variants only, not five
**Decision:** `BranchProtectionThenObserved` (default) and `None` (intentional skip). Other behaviours are reachable via timeout + `requiredChecksOverlay` tuning.
**Rejected:** Five-variant enum (`BranchProtectionThenObserved`, `BranchProtectionOnly`, `ConfiguredRequiredChecks`, `ObservedChecks`, `None`). Over-engineered for v1.
**In v1.1:** §6 (`CiPolicy`), §8.

### CI2. Required-check set has a discovery timeout to prevent "approve too early"
**Decision:** With no branch protection, an empty required-set is dangerous (all checks "green" before anything ran). Discovery timeout + `minimumExpectedChecks` is required.
**Rejected:** Treating an empty required-check set as instantly satisfied.
**In v1.1:** §8.

### CI3. Any required check (branch-protection OR overlay) that never appears after timeout → `NeedsHumanIntervention`
**Decision:** Generalised across both sources of "required". `source` field in the message distinguishes.
**Rejected:** Catching only overlay misses. A deleted/renamed workflow with stale branch protection would wait forever.
**In v1.1:** §8 rules 2 and 3.

### CI4. `minimumExpectedChecks` timeout has an explicit transition
**Decision:** After `checkDiscoveryTimeoutSec`, if observed < min → `NeedsHumanIntervention("only N CI checks observed, expected at least M", ResumeAfterHumanPush(...))`.
**Rejected:** "Keep polling until met or timeout" with the "or timeout" branch undefined.
**In v1.1:** §8 rule 3.

### CI5. Branch-protection cache is epoch-scoped, not process-lifetime
**Decision:** Cache key includes `cacheEpoch`; epoch increments on every `forge resume`, on explicit `forge refresh-cache`, or TTL expiry (1h default).
**Rejected:** Cache for full Forge process lifetime. A human fixing branch protection during a wait would never be picked up — Forge would poll the stale required set forever.
**In v1.1:** §8.1.

### CI6. `mergeStateStatus` is *not* used to detect merge
**Decision:** Use `state == "MERGED"` + `mergedAt` / `mergeCommit`.
**Rejected:** `mergeStateStatus == "MERGED"`. That field never returns `"MERGED"`; the poll-parse would never advance pieces.
**In v1.1:** §9 (PRWatcher), §11.3, §11.5.

### CI7. `gh pr review --request-changes` is validated before posting (must have ≥1 blocker)
**Decision:** A `request_changes` verdict with zero blockers is treated as an adapter bug — log `review.invalid_verdict` and retry the prompt once.
**Rejected:** Letting the empty-body post fail at the API level.
**In v1.1:** §10.2 step 5.

---

## Reviewer posting & inline comments

### R1. Forge owns the diff; reviewer adapter receives it, doesn't fetch
**Decision:** Forge fetches via `gh pr diff`, passes diff + spec to the reviewer. Diff cached per `(prNumber, headSha)` — naturally invalidates on push.
**Rejected:** Letting the adapter "see the diff" without specifying who fetches it.
**In v1.1:** §10.2.

### R2. Blockers carry `path`, `side`, `line`, `anchorText`; failure to anchor demotes to summary bullet
**Decision:** Inline comments need API-valid anchors. `anchorText` is the fallback when line numbers have drifted.
**Rejected:** Free-text `where: string` and `issue: string`. Won't parse to file:line pairs.
**In v1.1:** §10.3.

### R3. Fuzzy-anchor algorithm is specified, not vibes
**Decision:** (1) try at provided line, (2) scan ±10 lines for substring match, (3) scan whole changed file, (4) demote to summary. Log each demotion.
**Rejected:** "Fall back if invalid" with the valid path unspecified.
**In v1.1:** §10.3.

### R4. Pinned to the GitHub line-based `POST /pulls/{n}/comments` API
**Decision:** Use `path/side/line/commit_id`. Slice 0 confirmed `gh` version supports it; otherwise `gh api` raw.
**Rejected:** The classic `position`-in-diff-hunk variant.
**In v1.1:** §10.3.

### R5. Reviewer questions have explicit `severity`
**Decision:** `severity: "blocking" | "clarifying" | "optional"`. Only blocking stops progress. Schema also declares `allowFreeText`.
**Rejected:** Schema with unstructured questions where any question stops progress.
**In v1.1:** §6 (`Question`, `QuestionSeverity`).

---

## BranchManager, locking, preflight

### BM1. `BranchManager.syncBase()` runs `git fetch` + ff before branch creation and after merge
**Decision:** Local base divergence refuses with `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)`.
**Rejected:** Assuming "current main" without enforcing fetch/ff. Branches off stale main pretend CI is main-equivalent when it isn't.
**In v1.1:** §9.

### BM2. Behaviour on stale PR base is explicitly defined
**Decision:** If branch protection requires up-to-date → do nothing (let protection block). Otherwise, if `baseFreshness.autoUpdate: true` → `gh pr update-branch` and re-poll. If false → `NeedsHumanIntervention`.
**Rejected:** A status return with no FSM action defined.
**In v1.1:** §9.

### BM3. Hard preflight: clean worktree required, but command-aware
**Decision:** Automated commands require clean; `forge resume --commit-human-fix` explicitly permits modifications; `status` / `replay` / `rebuild-state` don't require clean.
**Rejected:** Universal "clean or `--force`" rule. Conflicts with manual-recovery resume after a human has edited files.
**In v1.1:** §15.

### BM4. OS lock paired with `.lock.json` metadata
**Decision:** `FileChannel.tryLock` on `.forge/state/.lock` + sibling `.lock.json` with PID / host / startedAt / command / feature. `unlock --force` only succeeds if no live OS lock.
**Rejected:** Bare `tryLock`. Doesn't identify holder; `unlock --force` can't release another live process's OS lock.
**In v1.1:** §13.

### BM5. Stale-lock UX is explicit: TUI prompt / CLI refusal / `--yes` flag
**Decision:** Three modes spelled out, with `FORGE_AUTO_UNLOCK_STALE=1` for CI.
**Rejected:** "Forge treats it as stale and can remove it after confirmation" without specifying how.
**In v1.1:** §13.

### BM6. `--commit-human-fix` validates current branch matches expected piece branch
**Decision:** Compares `git branch --show-current` to the manifest-derived piece branch; refuses on mismatch with a clear message.
**Rejected:** "Allowed only on the active piece branch" without specifying who validates.
**In v1.1:** §15.

### BM7. Branch name is derived (`branchPrefix + featureId + pieceId`), not stored
**Decision:** One source of truth. Same for the design branch and snapshot tags.
**Rejected:** Storing branch strings in the manifest.
**In v1.1:** §5.1 rules.

### BM8. PR number capture is specified: `gh pr create --json url -q .url` or `gh pr view --json number`
**Decision:** Spelled out in the BranchManager interface.
**Rejected:** "Opens a PR with `gh pr create`" with no number capture.
**In v1.1:** §11.2 step 13.

---

## ChangeCollector

### CC1. ChangeCollector exists at all — don't stage everything the agent touched
**Decision:** Capture `Write` / `Edit` paths, reconcile with `git status --porcelain`, apply allow / deny.
**Rejected:** "Stage everything the agent wrote". Agents create cache files, modify ignored files, write through shell commands. Stages noise into PRs.
**In v1.1:** §10.1.

### CC2. Three classes (`Allow`, `Deny`, `Ask`), not six
**Decision:** `Deny` covers security cases; `Ask` covers ambiguous; deny list is the lever.
**Rejected:** Six classes (`AllowedTrackedEdit`, `AllowedNewFile`, `GeneratedOrIgnored`, `SecretOrSensitive`, `OutsideRepo`, `Unexpected`). More granularity than v1 needs.
**In v1.1:** §10.1.

### CC3. Default policy is allow-anywhere-not-denied
**Decision:** Deny list catches dangerous cases. `staging.requireExplicitAllow: true` opt-in for stricter posture.
**Rejected:** Allow-list defaults like `allowNewFilesUnder: ["app/", "src/", "test/"]`. Scala-centric — Python/Go repos without `src/` would refuse every new file. Safety net becomes a wall.
**In v1.1:** §10.1.

### CC4. Pattern matching uses `java.nio.file.PathMatcher` glob, with a `**/`-prefix workaround
**Decision (Slice 1.4 Task 1.4.5 E2):** Match `staging.{deny,allow}Patterns` with the built-in `FileSystems.getDefault.getPathMatcher("glob:<pattern>")` (zero new deps) against the repo-relative path. **Workaround:** Java's glob treats a leading `**/` as requiring ≥1 directory segment before the `/`, so `**/.env` matches `config/.env` but **not** a repo-root `.env` (empirically verified — a security hole, since `**/.env` is a §18 default deny pattern meant to block secrets anywhere). For any `**/`-prefixed pattern the matcher therefore also compiles the prefix-stripped variant (`.env`), so the pattern matches the file at depth 0 *and* nested. `classify` is pure over `(repoRoot, Vector[FileChange], StagingConfig)` — no filesystem or git access — so a malformed operator glob surfaces as the `getPathMatcher` `PatternSyntaxException` raised into the `IO`, not a typed error (config validation is the Task 1.4.9 loader's job).
**Rejected:** (b) os-lib's native `**` matching and (c) a hand-rolled matcher — both more code / risk than the stdlib glob plus a one-line prefix workaround.
**Reason rule 4 (gitignore) needs a `FileChange.gitIgnored` flag:** the pure classifier can't shell out to `git check-ignore` (`forge-specs` has no `forge-git` dep), so the orchestrator (Task 1.4.10), which owns the git seam, populates the bit from `git status --porcelain --ignored`. The carve-out ("unless under `.forge/specs/...`") routes through `ForgePaths.specsRoot` (new accessor) so no `.forge` literal escapes the path seam.
**In v1.2:** §10.1 / §18.

---

## Budget enforcement

### BG1. Per-turn cost cap exists in addition to per-feature / per-piece
**Decision:** `maxTurnCostUsd` (default $2). On breach during active turn → SIGTERM (5s grace) → SIGKILL → `NeedsHumanIntervention`. The turn does *not* get to settle.
**Rejected:** "Let the current turn settle" on between-turn budget check. A runaway tool-use loop *is* the current turn — settling can blow $5+ in one turn.
**In v1.1:** §12 check 3.

### BG2. Budget checks happen before spawn and after every `cost.update`
**Decision:** Defined enforcement points.
**Rejected:** Configured caps with no defined enforcement timing.
**In v1.1:** §12 checks 1–3.

---

## Settle timeouts & runaway

### S1. "Settle" is defined precisely
**Decision:** Next `result` event after the most recent user message Forge sent, with a per-phase hard timeout. On timeout: `session.kill()`, log `harness.session_killed`, → `NeedsHumanIntervention`.
**Rejected:** "Wait for settle" as undefined prose. Stream-json emits `result` per turn, not per session.
**In v1.1:** §7.9, §11 (per-phase settle calls).

### S2. Every driver session is bounded by both settle timeout and per-turn cost cap
**Decision:** Two independent guards. A silent subprocess is bounded by settle; a chatty runaway is bounded by per-turn budget.
**Rejected:** Calling it "runaway detection" via one mechanism.
**In v1.1:** §7.9.

### S3. Per-phase settle timeouts (`spec`, `designRevision`, `implement`, `fixup`)
**Decision:** `designRevisionTimeoutSec` (default 600s) added — between spec's 300s and implementation's 1800s because revision carries more context than spec but edits docs not code.
**Rejected:** Reusing spec / implement timeouts; leaving design revision unbounded.
**In v1.1:** §7.9, §18.

---

## Q&A routing

### Q1. `AskUserQuestion` always routes through Forge Q&A pane regardless of trigger origin
**Decision:** PR-comment-triggered fix-up that produces a question still goes to the Q&A pane; never posted back to GitHub.
**Rejected:** Letting two question channels coexist (GitHub PR thread + Forge pane).
**In v1.1:** §10.4.

### Q2. Answer files are phase-specific, not piece-shaped only
**Decision:** Spec → `audit/spec-answers.md`; design review → `audit/design-review-r<n>-answers.md`; implementation → `pieces/<p.id>.impl-answers.md`; fix-up → `pieces/<p.id>.fixup-r<attempt>-answers.md`; etc.
**Rejected:** Single `<p.id>.answers.md` path. Spec consolidation / design revision have no piece; fix-up rounds overwrite each other.
**In v1.1:** §7.7.

### Q3. `.answers.md` files are for human history, not driver context
**Decision:** Fresh driver sessions don't see them.
**Rejected:** Implicit assumption that the driver re-reads its prior Q&A.
**In v1.1:** §7.7 (audit purpose).

---

## DocSync / design PRs / tags

### D1. DocSync doesn't sync per piece merge; rides next piece's PR
**Decision:** Update `decomposition.md` in the *next* piece's PR (off new main, rides cleanly). Final piece: one closing `chore` PR.
**Rejected:** "Tiny commit on main" per `[x]` flip. Branch protection rejects direct push; per-checkbox PR creates an absurd PR storm and an infinite Refinery loop.
**In v1.1:** §11.4 step 6 (DocSync ride), §14.3 (final-piece chore PR).

### D2. Design PR revisions use force-push with snapshot tag, not new PR
**Decision:** Force-push to the design branch preserves PR and reviewer state. Tag `forge/_snapshots/<feature>/design-r<n>` before each push; `git push --force-with-lease`.
**Rejected:** Opening a new PR per revision. Loses PR identity and reviewer comment state.
**In v1.1:** §11.3 steps 4–5.

### D3. Design revision snapshot tags are local-only by default
**Decision:** Don't pollute the remote. Optional `github.pushSnapshotTags: false` (default).
**Rejected:** Pushing snapshots by default.
**In v1.1:** §11.3 step 4, §18.

### D4. Snapshot tags live under `forge/_snapshots/` namespace
**Decision:** Visually distinct from feature branches.
**Rejected:** Tags alongside feature branches.
**In v1.1:** §5.1 rules.

### D5. Pre-revision tag retention: keep last 3 if pushed
**Decision:** `git push origin --delete refs/tags/forge/_snapshots/<feature>/design-r<n-3>` after each push.
**Rejected:** Unbounded snapshot tag accumulation.
**In v1.1:** §11.3 step 4, §18 (`snapshotTagRetention`).

---

## Refinery

### RF1. Refinery does not run local `sbt compile test`
**Decision:** Only the reviewer "is the design still accurate?" check.
**Rejected:** `runShell("sbt compile test")` between pieces. Blocks 5–30 min; CI already proved it on the PR; no new signal.
**In v1.1:** §14.

### RF2. Refinery progress is surfaced in the UI
**Decision:** TUI: `Refining: checking design against piece <p.id> (<elapsed>s)`. CLI: dotted progress line, 10s tick.
**Rejected:** Silent execution.
**In v1.1:** §14.1.

---

## CLI flag handling & paths

### CLI1. CLI flag claims are hypotheses until Slice 0
**Decision:** Slice 0 validated `--bare` / `--output-format stream-json` / `--permission-mode` / `--resume` / `--system-prompt-file` for Claude; `exec --json` / `--ignore-user-config` / `--output-schema` / `exec resume` for Codex.
**Rejected:** Treating CLI flag combinations as known-working.
**In v1.1:** §16, `docs/slice-0/slice-0-report.md`.

### CLI2. Two path roots only: `.forge/` (per-repo) and `~/.forge/` (install). Overrides via `.forge/overrides/`
**Decision:** All per-feature data under `.forge/`; immutable templates under `~/.forge/`.
**Rejected:** Mixed `.forge/...` and `forge/templates`, `forge/prompts`, `forge/schemas`.
**In v1.1:** §4 (paths), §18 notes.

### CLI3. Forge commits deterministically; the driver does not commit
**Decision:** Forge stages-per-policy and commits after the driver's `result` event with the canonical message.
**Rejected:** Asking the driver to commit.
**In v1.1:** §11.4 step 6, §21 "Who commits?".

### CLI4. "Interactive" terminology dropped in prose
**Decision:** "Streaming subprocess driven by the TUI" — one sentence acknowledges it presents *as* interactive.
**Rejected:** Calling `claude -p` "interactive". Misleading for future contributors reading the actual invocation.
**In v1.1:** §7 intro / §11.1.

---

## PR body & templates

### T1. PR body uses a Handlebars template, not free-form
**Decision:** `pr-body.md.hbs` under `~/.forge/templates/` with documented variables.
**Rejected:** "Body generated from pieces/<p.id>.md + acceptance" hand-wave.
**In v1.1:** §11.4 step 6.

### T2. Templates are rendered by a hand-rolled Handlebars-subset renderer, not a third-party engine
**Decision (Slice 1.4a, Task 1.4.4):** `forge-specs` renders Forge's shipped `.hbs` templates (`decomposition.md.hbs`, `pr-body.md.hbs`, the `*-answers.md.hbs` set) via `io.forge.specs.HandlebarsLite` — a small renderer supporting the construct set those templates use: `{{path}}` interpolation with dotted paths, `array.length` and `{{@index}}`, `{{#if}}`, `{{#each}}` (rebinding `this`, exposing 0-based `@index`), named helpers (registered in `io.forge.specs.TemplateHelpers` — `statusBadge`, `questionNumber`), `{{!-- --}}` comments, and mustache standalone-line trimming. `@index` stays 0-based (matching Handlebars, so the renderer isn't surprising); the answer templates render human-facing question numbers 1-based via `{{questionNumber @index}}` rather than expanding the language with `{{@index + 1}}` arithmetic. A structurally invalid tag (empty `{{}}`, a block helper with no argument, an unknown/mismatched/unterminated tag) surfaces as `RenderError.Parse`, keeping the malformed-template channel meaningful for operator edits. `ShippedTemplateRenderSuite` renders every shipped template through it so the supported set can't silently drift from what the templates need. Keeps `forge-specs` at its `osLib` + `upickle` dependency floor.
**Rejected:** Adding the Java `com.github.jknack:handlebars` engine. Full Handlebars compliance and robustness to arbitrary operator-customised templates, but a new third-party dependency (plus transitives) and a Java API to wrap, for a fixed set of templates Forge itself ships. The Scala `handlebars.scala` was a non-starter — Scala-2-only, won't build on 3.5.2.
**Escape hatch:** If a future template needs constructs beyond this set, swap in the rejected dependency — the renderer's `RenderError.{Parse,Eval}` channel and the `HandlebarsLite.render(template, root, helpers)` signature are the seam to preserve. This is a v1 module-layout call with no spec implication.

---

## Rate limiting & poll persistence

### RL1. GitHub rate-limit handling is required, with caching
**Decision:** Back-off on 403/429 honouring `Retry-After`. `harness.rate_limited` event. Branch-protection cache is epoch-scoped (CI5); diff cached per `(PR, head SHA)`.
**Rejected:** Unbounded polling that burns through the 5000/hour secondary limit.
**In v1.1:** §8.1, §18 (`rateLimitBackoffMs`), §19 (`harness.rate_limited`).

### RL2. "New comments since last seen" requires a persisted baseline
**Decision:** Poll state carries a persisted baseline so old comments aren't treated as new on the next poll. Captured at PR creation per §11.4 step 6.
**Cursor type — see S3-7:** the baseline is `BaselineCursor(at: Instant, seenIds: Set[String])` per collection (`commentCursor`, `reviewCursor`), plus a `Set[String]` of GraphQL check-run ids (`lastSeenCheckRunIds`, semantics deferred to Slice 4). The filter retains `at.isAfter(cursor.at) || (at == cursor.at && !cursor.seenIds.contains(id))` — strict-after PLUS an id tie-breaker at the watermark. The original v1.1 wording named `lastSeenCommentId` / `lastSeenReviewId` (`databaseId: Long`), but `gh pr view --json comments,reviews` does not expose `databaseId`; the empirically-supported wire surface is the GraphQL `id` String plus `createdAt` / `submittedAt`. The bare-`Instant` variant tried first (review round 1) silently dropped same-second human feedback because `gh` timestamps have second resolution — the round-2 `seenIds` set fixes that without an extra API call. Slice 3 PR-B switched to the cursor shape; v1.3 RL2 pins the change.
**Empty-body filter (S3-7):** the decoder also drops entries with empty `body` so plain approval submissions don't surface as `unseenComments` (the FSM reads that field as a human-override signal). Blocking review state flows through `reviewDecision` regardless.
**Rejected:** Detecting new comments without persistence; using GraphQL global `id` strings as the cursor (not order-comparable); accepting the same-second drop in exchange for a simpler bare-`Instant` cursor.
**In v1.1:** §11.4 step 6 (baseline), §11.5 comment-detection rules (with the v1.3 type update per S3-7).

---

## Process & meta

### P1. Standalone spec policy (v0.7+): every spec is fully self-contained
**Decision:** No "see prior version" cross-references. Commentaries are delta-only.
**Rejected:** Delta-only spec docs. Forces implementers to assemble across versions and reconcile drift.
**In v1.1:** §23.

### P2. Distinguish a requirement from its proposed implementation
**Decision:** When a reviewer flags an implementation as overweight, reject the implementation without rejecting the requirement.
**Origin:** The 0.4 → 0.5 lesson — 0.4 dropped role configurability because 0.3's role-abstraction implementation was overweight, when the requirement itself was valid. 0.5 reinstated configurability with a lighter implementation (hard-coded two-mode dispatch, A1 above).
**In v1.1:** implicit — apply when reviewing any future revision.

### P3. Slice 0 is validation, not silent triage
**Decision:** If a capability gap surfaces, the response is (a) wait for a CLI version, (b) explicit scope change in the doc, or (c) v1-blocking issue. Slice 0 doesn't pick whichever mode works.
**Rejected:** Implicit triage where Slice 0 just selects the working mode.
**In v1.1:** §16.1.
