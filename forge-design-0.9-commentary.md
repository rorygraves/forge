# Forge design v0.9 — review commentary

> Review of `forge-design-0.9.md`. 0.9 resolved the five 0.8 issues cleanly. Three final nits remain — all implementation-contract invariants around manifest mutation and session-id lifecycle. After 1.0 applies these, the design is implementation-ready and Slice 0 should start.

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.9.md`  •  **Precedes:** `forge-design-1.0.md` (release candidate)

---

## 1. Manifest mutation on merge detection is implicit

**Where:** forge-design-0.9.md:862 (merge detection → `Refining`), 879 (`nextPiece` selects by `status == "pending"`)

The lifecycle in §11.5 says "`state == MERGED` + `mergedAt != null` → `Refining(p, prNumber, now)`" but never explicitly mutates the piece's manifest entry to record the merge. §11.7 then says `nextPiece` is the first piece with `status == "pending"`. The current piece, never marked merged, gets selected again — infinite loop.

The mutation is *obviously* required and an implementer would put it somewhere, but the spec should name where and when.

**Decision for 1.0:** Add an explicit step in §11.5 between merge detection and `Refining`:

> From `PieceAwaitingMerge(p, prNumber)`, on `state == MERGED` + `mergedAt != null`:
> 1. **Atomically mutate the manifest:** set `pieces[i].status = "merged"`, `pieces[i].prNumber = prNumber`, `pieces[i].mergeCommit = snapshot.mergeCommit`, `pieces[i].mergedAt = snapshot.mergedAt`. Write via the atomic temp-file pattern (`os.write.over` → `os.move`).
> 2. Append `audit.piece_merged` event to the action log with the same fields.
> 3. FSM → `Refining(p, prNumber, now)`.

The mutation precedes the transition so a crash between (1) and (3) leaves the piece correctly marked merged; the next `forge resume` sees the merged status and advances through the normal `Refining` flow.

Same explicit-mutation rule applies symmetrically elsewhere — wherever an FSM transition implies a manifest annotation, the spec must name it. Reviewing the other transitions: `attempts` increment in §11.5 (already explicit), `baseSha` persistence in §11.4 step 1 (already explicit). Merge detection was the gap.

---

## 2. `currentPieceSessionId` lifecycle is inconsistent across three places

**Where:** forge-design-0.9.md:329 (comment: "cleared on Refining/advance"), 846 (prose: "retained until refinery exit"), 1082 (property test: "`Some` only while `PieceImplementing` / `PieceFixingUp`")

Three formulations, all contradicting each other:
- The `Feature` field comment says "cleared on Refining/advance" (ambiguous — entering Refining or exiting it?).
- §11.4 step 7 prose says "retained until refinery exit".
- Slice 2 property test says "`Some` only while `PieceImplementing` / `PieceFixingUp`" (i.e., cleared on transition to `PieceAwaitingCi`).

An implementer can't tell from this whether the session id should be available during fix-up planning, during CI polling, during reviewer posting, or during refinery.

**Decision for 1.0:** Single invariant, stated once and referenced everywhere:

> **`feature.currentPieceSessionId` is `Some(sessionId)` from the moment the driver is spawned for the active piece (`PieceImplementing` entry, or `PieceFixingUp` entry for fix-up sessions) through every subsequent state for that same piece — `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceCiFailed`, `PieceReviewFailed`, `PieceAwaitingMerge`, `Refining`. It is `None` in `PieceMerged`-equivalent transitions, i.e., cleared at the moment of advancing to the next piece's `PieceImplementing` (or to `FeatureDone` / `PlanningUpdate` / `NeedsHumanIntervention` if those are the next state).**

Rationale: the session id is meaningful for *the active piece*, not just the running subprocess. Keeping it through CI/review/merge/refining lets audit consumers, the TUI, and any future re-spawn logic reference the piece's most recent driver session. Clearing happens at the advance boundary — one clear point.

Fix-up sessions overwrite the field with the new fix-up session id when they spawn; that's correct, since the fix-up *is* the active session for that piece now.

Property tests in §17 Slice 2 are updated accordingly.

---

## 3. `feature.designSessionId.get` is unsafe in spec

**Where:** forge-design-0.9.md:811 (§11.2 step 12: `driver.resumeStreamingSpec(feature.designSessionId.get)`), 823 (§11.3 step 2: same)

The surrounding invariants make `.get` valid in normal flow (the field is populated during the design phase). But "normal flow" assumes:
- The action log hasn't been corrupted.
- `forge rebuild-state` produced a complete projection.
- No one manually edited `.forge/state/<feature>.json`.

In abnormal flow (corruption, manual edit, crash mid-write), `.get` throws. The spec should say what *should* happen rather than implying a NullPointerException.

**Decision for 1.0:** Define a uniform failure mode for missing required session ids. Add to §11.0 (preconditions):

> **Required session-id check.** Before any transition that calls `driver.resumeStreamingSpec(...)`, the orchestrator validates that the required session id is present:
> - `DesignReviewing` revision path or `DesignPrFeedback` requires `feature.designSessionId.isDefined`. If missing → `NeedsHumanIntervention("missing design session id, cannot resume", ReopenDesign(prNumber))` where `prNumber` is the current design PR if known.
> - (Future: any other resume call gets analogous handling.)

In the spec text, replace `feature.designSessionId.get` with `requireSessionId(feature.designSessionId, ReopenDesign(_))` and reference the precondition. Implementers know exactly what to do; no `.get`s in production code.

---

## 4. Net assessment

0.9 → 1.0 is the smallest delta of any revision pair:

- One explicit lifecycle step (manifest mutation on merge detection).
- One invariant consolidated (`currentPieceSessionId` lifecycle).
- One failure mode named (missing session id → `NeedsHumanIntervention`).

No architectural changes. No new types. No new config knobs.

After 1.0, the design is implementation-ready. The shape of the system has been stable across five consecutive revisions (0.5 → 0.6 → 0.7 → 0.8 → 0.9 → 1.0). Remaining work is Slice 0 (validate CLI assumptions) → Slice 1 (connectors) → Slices 2–5.

---

## 5. Versioning: 0.9 → 1.0

I'm marking the next version **1.0** rather than 0.10 to signal the design phase is complete. 1.0 is the **release candidate** — the document an implementer reads cold and starts building from. The 0.x series captured design evolution; 1.0 is the contract.

Future revisions after Slice 0 may need to update 1.0 — Slice 0 might discover capability gaps that force scope narrowing per §16.1. Those become 1.1, 1.2, etc., each fully standalone per §23.

---

## 6. What 1.0 does *not* change from 0.9

- All architectural decisions.
- Two-mode model.
- `AgentSession` / `StreamingSession` hierarchy.
- ChangeCollector (three classes, phase-aware denial).
- CI policy (two variants, three timeout transitions).
- `PlanningUpdate` carrying inline `ManifestPatch`.
- Branch-protection cache epoch scoping.
- Budget enforcement.
- Locking.
- `SchemaFallback` (only-permitted-emulation, 2-attempt hard cap).
- `HaltWithQuestion` (driver-question fallback).
- Per-phase settle timeouts.
- Phase-specific Q&A answer paths.
- Manifest invariants (slugging, `baseSha` nullability, reorder rule).
- Standalone-spec policy (§23).

Five consecutive revisions without architectural movement is the signal: this is done.
