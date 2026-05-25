# Forge design v0.6 — review commentary

> Review of `forge-design-0.6.md`. 0.6 resolved the main 0.5 issues: bounded `SchemaFallback`, required driver-question mechanism, explicit Slice 0 scope decision, CI timeout transition, reorder invariant, and slugging rules are all solid additions. The six remaining issues are spec hygiene and edge-case precision, not architectural blockers. The companion `forge-design-0.7.md` applies these decisions and is the first **fully standalone** spec — no cross-references to prior versions. Prior commentaries and version files remain in the workspace as a record of design evolution.

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.6.md`  •  **Precedes:** `forge-design-0.7.md`

---

## 1. 0.6 is a delta doc, not an implementation spec

**Where:** forge-design-0.6.md:50, 196, 206, 240

0.6 has several sections marked "carried forward from 0.5 unchanged" (preconditions, design review, design PR gate, CI/review polling, fix-up, lifecycle 7.0–7.3, sections 6, 8, 9, 10, 11, 12, 13). That's fine as a review aid but unsuitable as a build spec — an implementer would need to read 0.5, 0.6, and the connecting commentary to assemble a complete picture, and would have to reconcile any drift themselves.

**Decision for 0.7:** **0.7 is the first fully standalone spec.** Every section is present in full. No "see 0.5" references. The commentaries and prior version files stay in the workspace as a record of how the design evolved, but the implementation spec is one document.

**Policy going forward:** every subsequent version will be standalone. Delta-only docs cause drift; full docs cost more to write but are the only artefact an implementer should need.

---

## 2. Schema fallback success threshold is undefined

**Where:** forge-design-0.6.md:387, 405

Slice 0 §15 says Claude-as-reviewer is supported if fallback success rate is "above threshold" but never sets the threshold. Without a number, "supported" is ambiguous.

**Decision for 0.7:** **≥19/20 (95%) on N=20 representative inputs** for each reviewer method (`reviewDesign`, `reviewPr`, `refine`). The 20 inputs are the same fixtures used in the connector unit tests, plus a handful of adversarial cases (deliberately malformed designs, large diffs). Per-method, not aggregate — a connector might pass `reviewPr` and fail `refine`.

Rationale:
- 100% (20/20) sets a bar that no LLM-based fallback realistically meets — single transient failures on the schema retry would disqualify otherwise-fine adapters.
- 95% (19/20) means at most one transient miss per 20 reviews. Run-time behaviour is graceful: any single review that exhausts both fallback attempts transitions to `NeedsHumanIntervention`, surfacing the failure to the human rather than silently degrading.

Below 95% on any method → that method's mode is unsupported and §1 Goal 6 (design-scope decision) kicks in.

---

## 3. Question handling must cover spec and fix-up sessions, not just implementation

**Where:** forge-design-0.6.md:208, 329

§7.4 carefully defines driver-question handling for the *implementation* phase, distinguishing `Native` from `HaltWithQuestion`. But spec consolidation (§7.1), design revision (§7.2 / §7.3), and fix-up (§7.6) all also run the driver and can also produce questions. The doc doesn't say the same mechanisms apply.

For Claude-driver this is invisible — `AskUserQuestion` works the same in any driver session. For Codex-driver it matters: every driver session needs the halt-with-question protocol, and the orchestrator needs to know how to handle the halt and re-spawn for each session type.

**Decision for 0.7:** Driver-question handling is a property of *every* driver session, not just implementation. The spec defines it once (in the connectors / Mode section) and references it from each lifecycle phase that spawns a driver: spec consolidation, design revision, implementation, fix-up. Each phase's text explicitly says "driver questions are handled per §<connectors>". The re-spawn protocol for `HaltWithQuestion` carries the *original session's prompt context* (design + decomposition + prior turn outputs) so re-spawned sessions don't lose state.

---

## 4. `Mechanism` enum is too broad

**Where:** forge-design-0.6.md:155, 296, 302

`enum Mechanism = Native | SchemaFallback | HaltWithQuestion`. But `HaltWithQuestion` is illegal for schema output, and `SchemaFallback` is illegal for questions. Type-encoding this avoids a category of bug at compile time.

**Decision for 0.7:** Two separate enums:

```scala
enum QuestionMechanism:
  case Native | HaltWithQuestion

enum SchemaMechanism:
  case Native | SchemaFallback
```

`Connector` exposes `questionMechanism: QuestionMechanism` and `schemaMechanism: SchemaMechanism`. The action log carries the appropriate one per event kind; mixing is a type error, not a runtime check.

---

## 5. `schemaFallback.maxAttempts` is a fake knob

**Where:** forge-design-0.6.md:323, 426

The protocol hard-limit is 2 attempts (1 retry). Exposing it as `config.schemaFallback.maxAttempts: 2` invites users to set it to 5 and get inconsistent behaviour with the spec.

**Decision for 0.7:** Remove `schemaFallback.maxAttempts` from config entirely. The 2-attempt cap is a protocol invariant, hard-coded in the connector. Keep `schemaFallback.logValidatorErrors: true` (that's a real preference). Users who want different behaviour are asking for a different protocol, not a tuning knob.

Same scrutiny applied to other "fake knobs" found in 0.6: none. `maxHaltRespawns` is a real tuning knob (different repos have different ambiguity profiles). `maxFixupRounds`, `maxDesignReviewRounds`, the budget caps, the timeouts — all real knobs.

---

## 6. Runaway detection should name both bounds

**Where:** forge-design-0.6.md:222

§7.4 says "a driver that produces neither a question nor a completion within its turn budget is treated as a runaway (§11 per-turn cap kicks in)". A stuck subprocess that emits no cost events at all (e.g., blocked on stdin, hung in a tool call) might never trigger a cost-based cap. The settle timeout is the other guard.

**Decision for 0.7:** State explicitly that two bounds apply to every driver session:

1. **Settle timeout** (`settle.<phase>TimeoutSec`) — wall-clock cap on the whole session. Hard SIGTERM/SIGKILL on expiry.
2. **Per-turn cost cap** (`maxTurnCostUsd`) — checked after every `cost.update`. Hard SIGTERM/SIGKILL on breach.

A subprocess that produces no output at all is bounded by (1). A subprocess that produces output but runs away cost-wise is bounded by (2). Both transition to `NeedsHumanIntervention` with a phase-appropriate `ResumeHint`. The phrase "treated as a runaway" gets replaced by "bounded by settle timeout *and* per-turn cost cap".

---

## 7. Net assessment

0.6's two load-bearing 0.5 → 0.6 changes (SchemaFallback as named bounded emulation; required driver-question capability with two acceptable mechanisms) hold up. The six findings in this review are tightening, not redirection.

0.7 changes:
- **Standalone.** Every section in full. No back-references. This is the biggest delta by line count but doesn't change the design.
- **Schema fallback bar:** ≥19/20 per method.
- **Question handling generalised** to all driver sessions; re-spawn protocol carries prior session context.
- **`QuestionMechanism` and `SchemaMechanism`** as separate enums.
- **`schemaFallback.maxAttempts` removed** from config (protocol invariant).
- **Runaway detection:** both settle timeout and per-turn cost cap named explicitly.

The shape of the system is unchanged across 0.6 → 0.7. The work is hygiene.

---

## 8. Policy: every future version is standalone

The 0.1 → 0.6 sequence used delta-only later versions, which made each easier to write but harder to consume. From 0.7 onward, every version is a complete spec. The trade-offs are accepted:

- More lines per version.
- Some duplicated content across versions.
- One canonical doc to read at any point in the design's life.
- No risk of drift between sections marked "unchanged" and the prior version they reference.

Commentaries continue to be delta-only — they're inherently about what *changed* between versions, and being shorter is a feature.
