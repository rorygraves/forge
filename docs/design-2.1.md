# design-2.1 — Slice 1.1 implementation plan (✅ CLOSED 2026-05-26)

> **Status:** ✅ closed 2026-05-26. Condensed audit trail — the full per-sub-PR breakdown (§1) and round-by-round status log (§3) were summarised on 2026-05-31 in the Phase-1 docs consolidation; the complete original is recoverable from git history. The live spec is [`forge-design-1.3.md`](forge-design-1.3.md); deferred decisions / carry-forwards live in [`design-rationale.md`](design-rationale.md) and roadmap §7.2.

## Summary

Slice 1.1 shipped `forge-agents` — both CLI connectors against the v1.2 §7.1 `Connector` trait in its new shape (`runStreamingSpec(systemPrompt, initialUserMessage)`, `resumeStreamingSpec(sessionId, message)`, `StreamingSession.answerQuestion(toolUseId, answer)`). `ClaudeConnector` drives the Claude CLI through `StreamingDriver` (with a `tool_result` encoder + new `MissingToolUseId` adapter error); `CodexConnector` gained a new `CodexStreamingSession` class implementing a multi-process facade over `codex exec [resume]` (one process per turn, serialised under a `Mutex`, thread-id captured via `Deferred`). Real-CLI integration coverage landed in `forge-it` against Claude 2.1.150 and codex-cli 0.133.0. The native-schema reviewer regression suite (PR-D) was deferred to the Slice-4 reviewer-asset PR per design-rationale **C15**.

## 0. Exit criterion (met)

Roadmap §2.1: `forge-agents` standalone with a CLI demo + integration tests against real `claude` and `codex` binaries. Met when both connectors implement every v1.2 §7.1 trait method against the new shape, the §17 slice-1 forge-it test list passes against the pinned CLIs **minus** the native schema regression suite (PR-D, deferred per **C15**), and a section code review confirms this and the §4 carry-forward hand-off, flipping the `roadmap.md` §2.1 `[~] ClaudeConnector and CodexConnector` line to `[x]`. All conditions met at close.

## 3. Status log (condensed)

- 2026-05-25 — design-2.1.md created; **PR-A** (trait-shape code change, A1–A7) landed: `AskUserQuestion` gained `toolUseId`, `HaltWithQuestion` case removed; `ClaudeConnector` wired through `StreamingDriver`; new `CodexStreamingSession` multi-process facade; 173 unit tests + 1 forge-it smoke, scalafmt clean.
- 2026-05-26 — **PR-B** (Claude streaming integration tests, B1–B5) landed in `ClaudeStreamingSpecSuite` against Claude CLI 2.1.150; close-before-drain idiom required in streaming-spec mode.
- 2026-05-26 — **PR-C** (Codex streaming integration tests, C1–C7) landed across `CodexHeadlessSmokeSuite`, `CodexStreamingSpecSuite`, `CodexHaltWithQuestionReliabilitySuite` against codex-cli 0.133.0. Two upstream fixes folded in: `execArgv` swapped `--ask-for-approval` for `-c approval_policy=` (flag removed in codex ≥0.131); `spawnHeadless` + `runOneTurn` now `closeStdin` after spawn (codex hangs on a JVM-spawned open stdin pipe). C4 reliability sample opt-in behind `FORGE_IT_RUN_RELIABILITY=1`.
- 2026-05-26 — PR-C review follow-ups: `runReviewer` spawn `closeStdin`; C4 close-then-drain; `resumeStreamingSpec` system-prompt gap recorded as **C14**; resume turns verify thread_id and raise on mismatch.
- 2026-05-26 — **PR-D** (native schema regression suite) explicitly **deferred** out of Slice 1 to the Slice-4 reviewer-asset PR per **C15**.
- 2026-05-26 — **PR-E** (close-out, E1–E6) landed. **✅ Slice 1 closed.** Three review-round findings folded into `CodexStreamingSession` (in-mutex `closedRef` recheck; resume-turn non-zero-exit / missing-Result raising); roadmap §2.1 bullets flipped to `[x]`; `AGENTS.md` / `CLAUDE.md` / `README.md` synced; C14 + C15 confirmed durably homed. 177 unit tests pass.

## 4. Carry-forward (dispositions)

Both items are reconciled into [`design-rationale.md`](design-rationale.md) and `roadmap.md` §7.2 — see there for current status.

- **C14 — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a) system-prompt prepending.** The shared trait signature `resumeStreamingSpec(sessionId, message)` carries no `systemPromptPath`, yet v1.2 §7.10(a) claims the prepending convention applies to resume. Disposition: documented in design-rationale §C14 + roadmap §7.2 + the connector docstring; v1.3 to decide (widen trait / drop the §7.10(a) resume claim / carry the path through another seam). The orchestrator's resume path was written aware of the gap.
- **C15 — PR-D (native schema regression suite) deferred out of Slice 1.** v1.2 §17 names the ≥19/20 native-schema regression suite (`reviewDesign` / `reviewPr` / `refine` × Claude + Codex) as a Slice-1 deliverable, but its real-CLI runs need shipped reviewer schemas + system prompts that roadmap §2.6 places in Slice 4. Disposition: documented in design-rationale §C15 + roadmap §7.2; PR-D became a gating integration check on the Slice-4 reviewer-asset PR. (Closed by the Slice 1.4a `ReviewerRegressionSuite`, Task 1.4.7.)

## 5. Cross-references

- v1.2 spec for trait shape: §7.1, §7.2, §7.3, §7.4, §7.5, §7.6, §7.10
- Decisions backing the trait-shape PR: design-rationale C11, C12
- Wire-shape findings: `slice-0/slice-0-report.md`, `slice-0/transcripts/`
- Pre-v1.2 findings doc (superseded but kept): `slice-1/slice-1-findings.md`
- Phase context + seam discipline: `roadmap.md` §2.6 (role-trait stub, paths helper deferral)
