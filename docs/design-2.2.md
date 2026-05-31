# design-2.2 — Slice 1.2 implementation plan (✅ CLOSED 2026-05-26)

> **Status:** ✅ closed 2026-05-26. Condensed audit trail — the full per-sub-PR breakdown (§1) and round-by-round status log (§3) were summarised on 2026-05-31 in the Phase-1 docs consolidation; the complete original is recoverable from git history. The live spec is [`forge-design-1.3.md`](forge-design-1.3.md); deferred decisions / carry-forwards live in [`design-rationale.md`](design-rationale.md) and roadmap §7.2.

## Summary

Slice 1.2 shipped `forge-core` — the FSM and its persistence layer. Delivered: `ForgePaths(repoRoot)` owning every `.forge/*` literal (build-enforced by `ForgePathsSuite`'s `os.walk` sweep); the relocated manifest data types (`Manifest` / `ManifestPatch` / `Piece` / `PieceStatus` moved from `forge-specs` to `io.forge.core.manifest`); the domain model (`FsmState`, `FsmEvent`, `Feature`, `ResumeHint`, `Action`/`ActionDraft`, `PrSnapshot`, core-side reviewer-verdict summaries, `Cost`/`CostTotals`); the pure `Fsm.transition(feature, event, config): (Feature, Vector[ActionDraft])` covering every §11 lifecycle rule plus `requireSessionId`; `FileActionLog` (NDJSON append-only, monotonic seq, truncate-and-recover replay) + `Feature.foldEvents`; `FileStateCache` (atomic temp + `os.move`) with `verifyAgainstLog`; `ManifestStore`/`FileManifestStore`; and `RebuildState.run` + the pure `reconcile` crash-recovery rule. The §17 slice-2 property-test suite (F1–F13) closed out the invariant list. All seven sub-PRs (PR-A → PR-G) landed 2026-05-26; `forge-core` reached 358 unit tests; roadmap §2.2 flipped `[~]` → `[x]`.

## 0. Exit criterion (met)

`forge-core` ships the FSM as a pure function over `(Feature, FsmEvent)`, an append-only `ActionLog` with monotonic `seq`, an atomically-written `StateCache` with a verify-against-log path (§11.0 step 4), a `ForgePaths` helper owning every `.forge/*` literal, `Feature.foldEvents` replay, `RebuildState.run` (the entry point the Slice-4 `forge rebuild-state` CLI delegates to), and the §17 slice-2 property-test suite — each invariant cross-referenced to its spec section. The transition takes the whole `Feature` (it both reads and mutates manifest state) and emits `ActionDraft` (no `seq`/`at`) so it stays pure; `ActionLog.append` stamps on the way to disk. A section-level code review confirmed all of the above and that the §4 carry-forwards were durably handed off. **Met** — closed 2026-05-26.

## 3. Status log (condensed)

- **2026-05-26** — design-2.2.md created on the close of Slice 1 (`design-2.1.md` closed earlier same day).
- **2026-05-26 — PR-A landed.** Manifest data types relocated to `io.forge.core.manifest` via `git mv`; `ForgePaths` added with 13 path methods; `ForgePathsSuite` (17 tests) graduated the no-`".forge"`-literal smell test to a build-enforced `os.walk` gate; S2-1 filed. (`forge-core` → 89.)
- **2026-05-26 — codex IT flake resolved (side-quest, post-PR-A).** Fixed two real `forge-agents` bugs masked by a model-rejection 400: `CodexEventParser` silently dropped `turn.failed`/`error` events; `CodexStreamingSession` silently finalised on first-turn failure. Default `FORGE_IT_CODEX_MODEL` moved to `gpt-5.3-codex`.
- **2026-05-26 — PR-B landed.** Types-only: `PrSnapshot` (+ supporting enums), core-side reviewer-verdict summaries, `FsmState` (19 cases), `ResumeHint` (7), `FsmEvent` (20 cases; `DesignReviewVerdict` event renamed `DesignReviewReceived` to avoid clashing with the verdict ADT), `Feature`, `Action`/`ActionDraft` (`at`↔`ts` wire rename), `Cost`/`CostTotals`. Round-trip suites. (`forge-core` → 130.)
- **2026-05-26 — PR-C landed (+ review rounds 1, 2).** Pure `Fsm.transition` + `requireSessionId` + `FsmConfig` covering every §11.x rule; C5 C14-awareness comments. Round 1 fixed three findings (High: `Resume(RunAnotherFixup)` session-id projection; Medium: design-PR-feedback round counter → new `Feature.designPrFeedbackRound` field, S2-6; Medium: PR-number-match guards on snapshot/merge handlers). Round 2 cleared stale `currentPieceSessionId` at every NHI transition. (`forge-core` → 227.)
- **2026-05-26 — PR-D landed (+ review round 1).** `ActionLog`/`FileActionLog` (NDJSON, `APPEND|SYNC`, truncate-and-recover replay with `harness.error log_truncated` no-op marker), `Replay.foldEvents`/`Feature.foldEvents` + `FoldResult`, `ReplayError` (local to `io.forge.core.log`). Switched `fsm.transition` payload to full `FsmState` JSON (S2-7). Round 1 fixed: `applyTransitionProjections` mirroring every non-state mutation (High); per-actor session tracking via `Map[String, Set[String]]` (Medium). (`forge-core` → 291.)
- **2026-05-26 — PR-E landed (+ review round 1).** `StateCache`/`FileStateCache` (atomic temp+rename, `verifyAgainstLog` → `Consistent`/`Rewritten`), `RebuildError`, `ManifestStore`/`FileManifestStore`, `RebuildState.run` 6-step pipeline + pure `reconcile` (four §11.5 sub-cases). Round 1 fixed: cache decode failure no longer blocks rebuild (High); parent-directory fsync after move (Medium); `featureId`/`schemaVersion` cross-checks in `FileManifestStore.load` (Medium). (`forge-core` → 321.)
- **2026-05-26 — PR-F landed.** Property suites F1–F13 (one per §17 slice-2 invariant) over shared ScalaCheck generators + `FsmTrajectory`; F13 covers the reader-side crash-recovery fixtures (a/b₁/b₂/c/d/e). (`forge-core` → 357.)
- **2026-05-26 — PR-G landed (close-out).** Section review: **no High findings**. Fixups: deleted dead `Fsm.stateTag`; tightened `audit.piece_merged` key to `"p"` only (S2-10). Filed new carry-forwards S2-8, S2-9. Updated roadmap §2.2 (✅, draft v0.6), roadmap §7.2 (S2-1…S2-10), AGENTS.md, CLAUDE.md, design-rationale.md. (`forge-core` → 358; roadmap §2.2 flipped `[~]` → `[x]`.)

## 4. Carry-forward (dispositions)

All items below have a durable home in [`design-rationale.md`](design-rationale.md) ("Slice 2 spec deviations" section, 10 entries) and are mirrored in `roadmap.md` §7.2 with v1.3 / Slice-4 pointers.

### Inherited from Slice 1

- **C14** — `CodexConnector.resumeStreamingSpec` cannot honour §7.10(a) system-prompt prepending. PR-C C5 placed FSM-side awareness comments; no code resolution in Slice 2; v1.3 closes the trait gap.
- **C15** — Native schema regression suite deferred from Slice 1; rolls forward unchanged to the Slice-4 reviewer-asset PR.

### New in Slice 2

- **S2-1** — Manifest data types live in `forge-core`, not `forge-specs` (PR-A). Filed in design-rationale; v1.3 corrects §3.2.
- **S2-2** — `FsmEvent` ADT shape not in v1.2 spec (PR-B B4); the chosen 20 variants are the de-facto contract for Slices 3/4; v1.3 lifts into §6/§11.
- **S2-3** — `ActionLog` write durability vs. throughput (PR-D). Defaulted to `APPEND+SYNC`; opens as a carry-forward only if Slice 4 trips a perf cliff (fallback: per-batch `force()`).
- **S2-4** — `PrSnapshot` ownership mismatch (v1.2 §3.2 = `forge-core`, AGENTS.md = `forge-git`). Implemented in `forge-core`; PR-G corrected the AGENTS.md row; no v1.3 spec change needed (doc bug).
- **S2-5** — Writer-side atomic-merge ordering test deferred to Slice 4 (Slice-2 F13 covers the reader side only). Picked up by `design-1.4.md`'s carry-forward list.
- **S2-6** — `Feature.designPrFeedbackRound: Int` projection not in v1.2 §6 (PR-C round 1). Field added with a default; v1.3 §6 needs it on the `Feature` case class.
- **S2-7** — `fsm.transition` payload encodes full `FsmState`, not the class-name tag (PR-D); singleton cases still serialise as bare strings. v1.3 §19 needs a parameterised wire example.
- **S2-8** — `Fsm.transition` doesn't handle `SettleTimeout` for `SessionPhase.{DesignReview, CodeReview, Refine}` (PR-G review). Slice 4 chooses explicit FSM handlers or documented orchestrator-side conversion to `HarnessError`.
- **S2-9** — `StateCache.verifyAgainstLog` always writes the cache, even on Consistent (PR-G review). Slice 4 needs compare-then-skip or a manifest+log fingerprint cache.
- **S2-10** — `audit.piece_merged` payload key tightened to `"p"` only (PR-G fixup); legacy `"piece"` now surfaces as `ReplayError.MalformedPayload`. v1.3 §19 should pin the payload schema.

## 5. Cross-references

- v1.2 spec for FSM, Feature, ActionLog, StateCache: §4, §6, §6.1, §6.2, §11.0–§11.7, §19.
- v1.2 spec for Manifest invariants exercised in F10–F12: §5.1, §5.5.
- v1.2 spec for the paths-helper seam (PR-A): §4, §17 Slice 2.
- v1.2 spec for budget and locking events surfaced as `FsmEvent`s (PR-B B4): §12, §13.
- Slice 0 wire-shape findings consumed by `FsmEvent` design: `slice-0/slice-0-report.md` §2 (resume preserves session id).
- Decisions backing the FSM trait shape: design-rationale C9, C10, C11, C12, C14.
- Phase context + seam discipline: `roadmap.md` §2.2, §2.6, §7.1.
- Predecessor: `design-2.1.md` (Slice 1 audit trail) — closed 2026-05-26.
