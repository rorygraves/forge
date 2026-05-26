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
**Action required for v1:** v1.3 spec correction needs to either (i) drop the §7.10(a) "applies to resume" claim and shift the role-framing responsibility to the orchestrator's resume message, or (ii) widen the trait to carry the path and accept the call-site churn. Until that's decided, the existing comment in `CodexConnector.resumeStreamingSpec` documents the deviation; the orchestrator's resume code path (lands with Slice 2 FSM) needs to be written aware of it.
**In the spec today:** v1.2 §7.10(a) (states the broken contract), §7.1 (the trait signature that doesn't carry the path). Surfaced as a PR-C reviewer comment; tracked here so v1.3 can close it explicitly.

### C15. Native schema regression suite (PR-D) deferred from Slice 1 to ship alongside reviewer assets (slice-1 close-out decision)
**Decision (slice-1 PR-E review):** `design-2.1.md` §1.4 PR-D — the ≥20-sample native-schema regression suite for `reviewDesign` / `reviewPr` / `refine` on each connector — is **explicitly deferred out of Slice 1**. The suite lands as a gating check on the PR that ships the reviewer schema files (`.forge/reviewer-schemas/{design-review,pr-review,refine}.json`) and the reviewer system-prompt files, which the roadmap (§2.6) places in Slice 4 alongside `ForgePaths`. Slice 1 closes (`roadmap.md` §2.1 `[~] → [x]`) without PR-D having landed; the carry-forward to the reviewer-asset PR is recorded as a `design-2.1.md` §4 bullet that PR-E E6 must walk before flipping the roadmap.
**Why this is OK as a deferral:** The reviewer code path on both connectors is already shipped and exercised end-to-end against fake CLIs in `CodexConnectorSuite` / `ClaudeConnectorSuite` (per-method `ReviewerAssets` plumbing, shared `ReviewDecoders` + `ReviewerPrompts`, retryable `ReviewerProcessFailure` vs non-retryable `ReviewerNotConfigured` / `StructuredOutputMissing` / `StructuredOutputMalformed` adapter errors). What PR-D would add — real-CLI native-schema reliability measurement against the §16 ≥19/20 bar — can only be measured against shipped schemas + prompts. Without those assets the suite has nothing to assert against.
**Why this is honest under v1.2 §17:** v1.2 §17 slice-1 integration test list still names the native schema regression suite. This entry is the explicit deviation note so the roadmap flip from `[~]` to `[x]` doesn't silently bury the gap — v1.3 spec corrections should either (i) restate the regression suite as a Slice-4 deliverable alongside reviewer-asset shipping, or (ii) leave §17 as-is and have the v1.3 §17 reorganisation absorb the move.
**Rejected:** (a) **Ship reviewer schemas + prompts + PR-D in Slice 1** — expands Slice 1 scope and locks in schema content choices before the Slice 2-3 orchestrator work has surfaced reviewer schema requirements (e.g. the FSM may want fields PR-D would otherwise have to backfill). (b) **Keep Slice 1 open until PR-D lands** — couples Slice 1 closure to an external dependency (reviewer assets) that the roadmap doesn't schedule until Slice 4; Slice 2 (FSM) blocking on it would just stall the roadmap.
**Action required for v1:** the reviewer-asset PR (Slice 4 per current roadmap) lands PR-D as its gating integration check; if the real-CLI ≥19/20 bar isn't met, schema/prompt tightening happens there. `design-2.1.md` §4 keeps a carry-forward bullet referencing this entry until that PR closes; PR-E's section-closure checklist (E6) explicitly walks §4 before flipping the roadmap.
**In the spec today:** v1.2 §17 (Slice 1 integration test list naming the regression suite), §16 (the ≥19/20 bar). Surfaced as a PR-E reviewer comment; tracked here so the deferral is durable.

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

---

## Rate limiting & poll persistence

### RL1. GitHub rate-limit handling is required, with caching
**Decision:** Back-off on 403/429 honouring `Retry-After`. `harness.rate_limited` event. Branch-protection cache is epoch-scoped (CI5); diff cached per `(PR, head SHA)`.
**Rejected:** Unbounded polling that burns through the 5000/hour secondary limit.
**In v1.1:** §8.1, §18 (`rateLimitBackoffMs`), §19 (`harness.rate_limited`).

### RL2. "New comments since last seen" requires persisted IDs
**Decision:** Poll state carries `lastSeenCommentId`, `lastSeenReviewId`, `lastSeenCheckRunIds`. Stored at PR creation so old comments aren't treated as new.
**Rejected:** Detecting new comments without persistence.
**In v1.1:** §11.4 step 6 (baseline IDs), §11.5 comment-detection rules.

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
