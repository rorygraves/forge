# Forge design v0.8 — review commentary

> Review of `forge-design-0.8.md`. 0.8 cleanly addressed the seven 0.7 findings. The remaining issues are state-and-process-handle precision: design-phase sessionId persistence, killable handles for headless runs, post-refinery state semantics, branch-protection cache invalidation, and phase-specific answer paths. None changes design direction. The companion `forge-design-0.9.md` applies these decisions as a standalone spec.

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.8.md`  •  **Precedes:** `forge-design-0.9.md`

---

## 1. Design session id is dropped in states that later need it

**Where:** forge-design-0.8.md:323–325 (`DesignNeedsHumanInput`, `DesignAwaitingMerge`, `DesignPrFeedback` — none carries `sessionId`), 763 (return to `DesignReviewing(round + 1)` without sessionId), 776 (`DesignPrFeedback` calls `driver.resumeStreamingSpec(sessionId)`)

The design session id is captured in `InteractiveSpec(sessionId)` and `DesignReviewing(sessionId, round)` but disappears in:
- `DesignNeedsHumanInput(round, questions)` — Q&A pane → answers appended → returns to `DesignReviewing(round + 1)` but the sessionId is gone.
- `DesignAwaitingMerge(prNumber)` — long human gate, sessionId not retained.
- `DesignPrFeedback(prNumber, round)` — calls `resumeStreamingSpec(sessionId)` but the FSM state doesn't have one.

In all three cases, the operational flow needs the sessionId, but the type doesn't carry it. Implementers would have to invent ad-hoc threading.

**Decision for 0.9:** Promote the design session id to a **feature-scoped durable field**, not a per-state field. The session id is a property of the *feature's design phase*, not of any particular FSM state within it.

```scala
case class Feature(
  // ... existing fields
  designSessionId: Option[String],   // populated by InteractiveSpec; cleared on DesignReady
  currentPieceSessionId: Option[String]  // populated by PieceImplementing/PieceFixingUp; cleared on PieceMerged
)
```

Both fields are projections of the action log (the most recent `<driver>.spawn` event during the relevant phase). The state cache stores them; `forge rebuild-state` recovers them from the log. The FSM enum loses redundant `sessionId` parameters where the feature field is authoritative.

The states still carrying `sessionId` in 0.8 (`InteractiveSpec`, `DesignReviewing`, `PieceImplementing`, `PieceFixingUp`) become:
- `InteractiveSpec` — no field; reads from `feature.designSessionId`.
- `DesignReviewing(round)` — drop `sessionId`.
- `PieceImplementing(p)` — drop `sessionId`; reads from `feature.currentPieceSessionId`.
- `PieceFixingUp(p, prNumber, attempt)` — drop `sessionId`.

Cleaner enum, no lossy state transitions.

---

## 2. Headless runs return bare streams; nothing to kill on timeout/cost breach

**Where:** forge-design-0.8.md:431–432 (`runHeadlessImplementation` and `runFixup` return `Stream[IO, AgentEvent]`), 541 (`session.kill()` on settle timeout), 845 (`session.kill()` on per-turn breach)

Only `StreamingSession` has `kill()`. Headless runs return a bare stream — the orchestrator has no handle to SIGTERM the underlying subprocess. The two enforcement paths (§7.9 settle timeout, §12.3 per-turn breach) reference `session.kill()` without a session to kill.

**Decision for 0.9:** Introduce a small type hierarchy:

```scala
trait AgentSession:
  def sessionId: String
  def events: Stream[IO, AgentEvent]
  def close(): IO[Unit]      // graceful shutdown after settle
  def kill(): IO[Unit]       // SIGTERM, 5s grace, SIGKILL

trait StreamingSession extends AgentSession:
  def send(input: String): IO[Unit]   // headless sessions don't expose this

trait Connector:
  def runStreamingSpec(systemPrompt: Path): IO[StreamingSession]
  def resumeStreamingSpec(sessionId: String): IO[StreamingSession]
  def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession]
  def runFixup(prompt: FixupPrompt): IO[AgentSession]
  // ... (reviewer methods unchanged)
```

Every driver session — streaming or headless — exposes `kill()` and `close()`. The orchestrator's settle/budget monitors call `kill()` uniformly. `StreamingSession extends AgentSession` so existing streaming code paths are unaffected.

---

## 3. `PieceMerged` → `Refining` → `PieceMerged` reads as a loop

**Where:** forge-design-0.8.md:877 (post-merge transition to `Refining`), 889 (refinery failure → `PieceMerged`)

§11.5 says "merge detected → `Refining(p, now)`". §14.2 says refinery failure → `PieceMerged`. The labels suggest `Refining → PieceMerged → Refining`. There's no actual loop in the transition table — `PieceMerged` is meant to be transient — but the names don't make that clear, and an implementer reading the enum could plausibly believe `PieceMerged` is a stable state.

**Decision for 0.9:** Rename + document explicitly:

- **`PieceMerged(p, prNumber)`** is removed.
- **`Refining(p, startedAt)`** stays — refinery in progress.
- Refinery outcomes transition directly to the *next* state:
  - `no_change` → `PieceImplementing(nextPieceId)` if more pieces remain, else `FeatureDone`.
  - `update_plan` → `PlanningUpdate(reason, patch)`.
  - `reopen_design` → `NeedsHumanIntervention("refinery flagged design drift", ReopenDesign(None))`.
  - **Refinery failure (advisory)** → same as `no_change`: advance to next piece or `FeatureDone`. Log `harness.refinery_failed`. No intermediate "PieceMerged" state.

This eliminates the confusing name and the suggestion of looping. The action log preserves the "piece merged" milestone via the existing `fsm.transition` event (`from: PieceAwaitingMerge, to: Refining, prNumber, mergeCommit`).

If a `PieceMerged` milestone is needed for audit consumers, add it as a non-FSM `audit.piece_merged` event written when entering `Refining`.

---

## 4. Branch-protection cache invalidation on human fix

**Where:** forge-design-0.8.md:592 (human fixes protection/overlay → `ResumeAfterHumanPush`), 604 (cache for Forge process lifetime)

If the human fixes branch protection after a "required check never appeared" timeout, the cached required-check set is stale. Forge keeps polling for the old required set, never sees the new reality, and stays stuck — or worse, treats the (now non-required) check as required.

**Decision for 0.9:** Scope the branch-protection cache more finely:

- Cache key: `(featureId, baseBranch, cacheEpoch)`.
- `cacheEpoch` increments on:
  - Every `forge resume` (regardless of variant).
  - Explicit `forge refresh-cache <feature>` command (new).
  - TTL expiry (default 1 hour, `config.github.branchProtectionTtlSec`).
- The orchestrator reads the cache by current epoch; older entries are evicted lazily.

Resume after fixing protection just works: the cache is invalidated, the next poll re-fetches.

Same treatment for the diff cache (`(prNumber, headSha)`-keyed already, so naturally invalidates on push — no change needed).

---

## 5. `HaltWithQuestion` answer paths are piece-shaped only

**Where:** forge-design-0.8.md:479 (writes answers to `<p.id>.answers.md`)

The path assumes a piece id. Spec consolidation and design revision sessions have no piece; using `<p.id>.answers.md` breaks. The same applies to multiple fix-up rounds for the same piece — they all overwrite the same file.

**Decision for 0.9:** Phase-specific answer paths:

| Phase | Path |
|---|---|
| Spec consolidation | `.forge/specs/<feature>/audit/spec-answers.md` |
| Design review (driver revision after blockers) | `.forge/specs/<feature>/audit/design-review-r<n>-answers.md` |
| Design PR feedback | `.forge/specs/<feature>/audit/design-pr-feedback-r<n>-answers.md` |
| Implementation | `.forge/specs/<feature>/pieces/<p.id>.impl-answers.md` |
| Fix-up | `.forge/specs/<feature>/pieces/<p.id>.fixup-r<n>-answers.md` |

The orchestrator dispatches by current FSM state when writing. Same dispatch applies to `Native` `AskUserQuestion` answers (currently §11.4 step 4 writes to `<p.id>.answers.md` too — same fix).

`<n>` is the round/attempt counter for the relevant phase.

---

## 6. Net assessment

0.8 → 0.9 is the smallest delta in this design's history:

- One promoted field (`Feature.designSessionId` / `currentPieceSessionId`).
- One type hierarchy refinement (`AgentSession` / `StreamingSession`).
- One state removed (`PieceMerged`) with explicit direct-advance transitions.
- One cache scoping change (epoch + TTL).
- One file-path dispatch (phase-specific answer files).

These are all things an implementer would hit on day 1–2 of Slice 1 (the type hierarchy) or day 1 of Slice 2 (the state shape and durable fields) and have to invent. Spec them now.

After 0.9, the design should be implementation-ready. The shape of the system has now been stable across four consecutive revisions (0.5 → 0.6 → 0.7 → 0.8 → 0.9). Remaining work is Slice 0 validation followed by Slices 1–5.

---

## 7. What 0.9 does *not* change from 0.8

- Mode model (two first-class modes, hard-coded dispatch).
- ChangeCollector (three classes, allow-anywhere-not-denied default, phase-aware denial hints).
- CI policy (two variants, three timeout transitions).
- `PlanningUpdate` carrying inline `ManifestPatch`.
- Budget enforcement (three caps, mid-turn SIGTERM/SIGKILL).
- Locking (OS lock + metadata + stale-detection UX).
- Manifest invariants (slugging, `baseSha` nullability, reorder rule).
- `SchemaFallback` (only-permitted-emulation, 2-attempt hard cap).
- `HaltWithQuestion` (driver-question fallback, `maxHaltRespawns` cap).
- Per-phase settle timeouts.
- `piece.attempts` semantics (one counter for all fix-up causes).
- Standalone-spec policy (§23).

The §23 policy continues: 0.9 is standalone; no back-references to 0.8 or earlier.
