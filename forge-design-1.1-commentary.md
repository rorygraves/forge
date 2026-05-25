# Forge design v1.0 → v1.1 — revision commentary

> Slice 0 (CLI capability validation) ran on 2026-05-25 and surfaced three corrections that don't narrow scope but do change what the implementation contract should say. v1.1 folds them in. No architectural movement; no new types; one new adapter-internal subsection (§7.10).

**Reviewer:** Rory  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-1.0.md`  •  **Produces:** `forge-design-1.1.md`  •  **Source:** `docs/slice-0/slice-0-report.md` §5.2

This commentary is delta-only per §23 of v1.1. Everything not mentioned here is unchanged from 1.0.

---

## 1. Claude has native schema enforcement — SchemaFallback is gone from v1

**What Slice 0 found** (`docs/slice-0/slice-0-report.md` §3.1): `claude -p --output-format json --json-schema '<schema>'` returns a top-level `structured_output` field with schema-conformant JSON on the first attempt. The probe transcript is `docs/slice-0/transcripts/07-claude-schema.json`. Both connectors can declare `schemaMechanism = Native`.

This is the largest delta in v1.1 because §7.5 / §7.6 / §16 / §18 / §19 / §22 all referenced SchemaFallback. The plumbing comes out cleanly because none of it had downstream coupling — SchemaFallback was always confined to the connector layer.

### 1.1 §7 intro and §7.4 — heading reframed, mechanism table added

**Where in 1.0:** lines 457 (`## 7. Agent connectors, Mode, and bounded fallback protocols`), 547–549 (§7.4 brief paragraph)

The §7 heading promised "bounded fallback protocols"; the only one was SchemaFallback. v1.1 retitles to *"Agent connectors, Mode, and the `HaltWithQuestion` protocol"* — `HaltWithQuestion` is the only bounded protocol left, and it's a question-mechanism convention rather than capability emulation.

§7.4 in 1.0 was a three-line note that Codex was Native. v1.1 expands it into a two-row table covering both connectors with the exact CLI flag and where the structured output lands. The Slice 1 ≥19/20 regression sample is mentioned here so an implementer doesn't have to chase the §16 cross-reference.

### 1.2 §7.5 — SchemaFallback protocol removed; replaced with adapter-error path

**Where in 1.0:** lines 551–567 (§7.5 SchemaFallback protocol)

The 1.0 §7.5 protocol (1 try + 1 retry, validate JSON locally) was the only permitted capability emulation. With Native everywhere in v1, it's not needed. v1.1's §7.5 now describes what happens if a Native schema enforcement nevertheless returns malformed JSON: the connector raises an adapter error, and the caller does not retry via `reviewProcessRetries`. For refinery this falls through to the advisory-failure path (§14.2); for design/code review it becomes `NeedsHumanIntervention`.

The SchemaFallback protocol itself isn't deleted from history — it's parked as a v2 candidate (§20) and the `SchemaMechanism.SchemaFallback` enum case is retained as dormant (§6). The reason for retaining the enum case (rather than deleting it down to a single-value enum) is that the Slice 1 trait stubs already declare `schemaMechanism: SchemaMechanism`, and a future connector for a non-Native CLI can pick it up without an enum migration.

### 1.3 §7.6 — collapsed from two layers to one

**Where in 1.0:** lines 569–576 (§7.6 "Process retries vs SchemaFallback attempts — distinct layers")

In 1.0 the layering was:

```
RetryOnProcessFailure(processRetries) {
  connector.reviewX(...)  // may use SchemaFallback internally, 2-attempt cap
}
```

With SchemaFallback gone, there's no inner layer. v1.1's §7.6 says so plainly: a single `RetryOnProcessFailure(processRetries) { connector.reviewX(...) }` wrap; adapter errors (the §7.5 path) are not retried.

### 1.4 §16 — reviewer cells re-cast from "Native or SchemaFallback ≥19/20" to "Native"

**Where in 1.0:** lines 1001–1045 (§16 / §16.1 — Slice 0 matrix)

The 1.0 §16 was a future-tense plan for Slice 0. v1.1's §16 is past tense — Slice 0 ran, this is the result. The reviewer rows in the §16.1 table drop the SchemaFallback option:

| Cell | 1.0 threshold | 1.1 threshold + outcome |
|---|---|---|
| Claude reviewer | "Fallback success ≥19/20 **per method**" | Native via `--json-schema`. ✅ Met — first-attempt success on probe. |
| Codex reviewer | "All work" (Native expected) | Native via `--output-schema`. ✅ Met. |

The 19/20 sample suite still runs in Slice 1, but as regression coverage (catching model-induced edge cases), not feasibility validation. v1.1 flags this distinction explicitly so an implementer doesn't over-engineer the sample harness as a feasibility gate.

The other two cells (Claude driver, Codex driver) keep their 1.0 thresholds. The Codex `HaltWithQuestion` ≥19/20 measurement is deferred to Slice 1 per §5.1 of the Slice 0 report — the protocol *can* be invoked, but the reliability number depends on production prompts.

### 1.5 §17 — Slice 0 marked complete; SchemaFallback test removed

**Where in 1.0:** lines 1051–1071 (Slice 0 and Slice 1 deliverables)

The Slice 0 entry in 1.0 read as a future deliverable; v1.1 marks it complete with a pointer to `docs/slice-0/slice-0-report.md`.

Slice 1's bullet list in 1.0 included:
- "`SchemaFallback` protocol (§7.5) implemented once in `forge-agents`."
- "SchemaFallback exercised with schema-violating first responses; asserts 2-attempt cap."

Both are removed in v1.1. Replaced with a Native-schema regression suite bullet that targets the same goal (catch malformed reviewer output) via the new adapter-error path.

### 1.6 §18 — `schemaFallback.logValidatorErrors` config knob removed

**Where in 1.0:** lines 1177–1179 (`"schemaFallback": { "logValidatorErrors": true }`)

The knob's only purpose was to control whether SchemaFallback validator errors made it into audit files. With no SchemaFallback, no knob. Removed cleanly; no migration needed (greenfield project).

The 1.0 §18 "Notes" line *"SchemaFallback 2-attempt cap is a **protocol invariant** (not exposed in config)"* is replaced with a note saying schema-validation failures surface as adapter errors and aren't retried.

### 1.7 §19 — `schema_fallback_ok` / `schema_fallback_error` removed; `schema_invalid` added

**Where in 1.0:** lines 1240–1241

```
- `<actor>.schema_fallback_ok` — `{ method, attempt }`.
- `<actor>.schema_fallback_error` — `{ method, attempt, validatorError }`.
```

Both gone. v1.1 adds:

```
- `<actor>.schema_invalid` — `{ method, validatorError }`. Emitted when a Native schema enforcement returns malformed JSON; always paired with an adapter error.
```

One event instead of two, no `attempt` field (no retry loop), simpler observability.

### 1.8 §22 — rejects list updated

**Where in 1.0:** line 1333 — `- Capability emulation beyond \`SchemaFallback\`.`

Rephrased to: *"Capability emulation of any kind in v1. With Slice 0 confirming both CLIs natively enforce schema, there is no v1 emulation layer. A `SchemaFallback`-shaped emulation is parked as a v2 candidate (§20)..."*

The change in stance is that 1.0 rejected emulation *except* one form (SchemaFallback); 1.1 rejects all v1 emulation and notes that SchemaFallback is a v2 reservation, not a v1 carve-out.

### 1.9 §1 non-goals — "Emulation of capabilities other than..." rewritten

**Where in 1.0:** line 46 — `- Emulation of capabilities other than schema-constrained reviewer output (§7).`

Replaced with a clearer two-sentence non-goal: capability emulation of any kind, and a forward-pointer to §20 for SchemaFallback.

---

## 2. Both CLIs preserve session id on resume

**What Slice 0 found** (`docs/slice-0/slice-0-report.md` §2.1, §2.2): `claude -p --resume <id>` returns the same `session_id`. `codex exec resume <id>` returns the same `thread_id`. Claude has `--fork-session` to force a new id, but the default is preservation. Probes are in transcripts `02-claude-resume.jsonl` and `05-codex-resume.jsonl`.

The 1.0 spec assumed resume produces a *new* id and required the orchestrator to update the projection. With pinned-CLI behaviour, the update is idempotent — but the spec text shouldn't pretend the value changes.

### 2.1 §6.1 — resume event semantics clarified

**Where in 1.0:** lines 433–439 (§6.1 session-id invariants)

The 1.0 §6.1 didn't actually mis-state the resume behaviour (the invariants are about lifetime, not about whether the value changes). v1.1 adds a new "Resume event semantics" subsection after the existing invariants:

- `<actor>.resume` carries `{ oldSessionId, newSessionId }`.
- Under the pinned CLIs, `newSessionId == oldSessionId` (this is the normal case in v1).
- The two-field shape is retained because (a) a future CLI may mint a new id, and (b) `--fork-session` on Claude exists even if v1 doesn't use it.
- The projection updates from `newSessionId` regardless — idempotent in the equal case.

This means no orchestrator behaviour depends on the id changing. Tests can assert `oldSessionId == newSessionId` under the pinned CLIs (added to Slice 1 in §17).

### 2.2 §11.2 step 12 — wording softened

**Where in 1.0:** line 824 — `→ update \`feature.designSessionId\` with the new session id.`

Rewritten to:

> Log `<driver>.resume` with `{ oldSessionId = feature.designSessionId.get, newSessionId = session.sessionId }`; with the pinned CLIs the two are equal (§6.1). Update `feature.designSessionId = Some(session.sessionId)` regardless — idempotent in the equal case, correct in the (currently hypothetical) unequal case.

This trades a one-line update for three-line clarity. The implementer reading this no longer has to wonder "why are we storing the same value back into the field" — the answer is forward-compatibility with `--fork-session` or future CLIs.

### 2.3 §11.3 step 2 — same fix in the design-PR-feedback path

**Where in 1.0:** line 836 — `→ update \`feature.designSessionId\`.`

Same rewrite as §11.2 step 12, slightly shorter (the `requireSessionId` check is already covered by the cross-reference to §11.0 step 5).

### 2.4 §19 — `<actor>.resume` payload documented as may-be-equal

**Where in 1.0:** line 1234 — `\`<actor>.resume\` — \`{ sessionId, newSessionId }\`.`

Renamed the first field from `sessionId` to `oldSessionId` for symmetry, and added a one-line clarification that the two are equal under the pinned CLIs.

### 2.5 §17 Slice 2 — property test for the equal-id case added

**Where in 1.0:** line 1085 — `feature.designSessionId` lifecycle test.

v1.1 extends the test description with: *"Updating with `newSessionId` from a `<actor>.resume` event is idempotent when `newSessionId == oldSessionId` (the pinned-CLI case)."*

And the Slice 1 integration tests get a new assertion: *"`resumeStreamingSpec` exercised end-to-end. Assert `oldSessionId == newSessionId` on the `<actor>.resume` event under the pinned CLIs (§6.1)."*

---

## 3. Codex adapter notes — new §7.10

**What Slice 0 found** (`docs/slice-0/slice-0-report.md` §2.2, §2.3): three Codex-specific behaviours that the trait contract in §7.1 doesn't change, but every implementer of `CodexConnector` will hit on day one. v1.1 consolidates them into a new §7.10 *"Codex connector — adapter-internal notes"*.

### 3.1 (a) System prompt prepending

**Where in 1.0:** §7.1 trait `runStreamingSpec(systemPrompt: Path)` (line 487).

Codex has no `--system-prompt-file` flag. The trait signature `runStreamingSpec(systemPromptPath: os.Path)` stays as written — the adapter reads the file and prepends it as a `## System` block to the user prompt. Same convention for `runHeadlessImplementation` and `runFixup`. v1.1 records this in §7.10(a) so the implementation contract is closed.

The Slice 0 report (§2.3 design-impact row 2) called this "adapter-internal; no spec change". v1.1 nevertheless writes it down because an implementer who skips §7.10 and just reads the trait can plausibly assume Codex takes a `--system-prompt-file` flag, hit the error, and conclude the trait is wrong. Writing it once prevents that.

### 3.2 (b) USD cost via per-model price table

**Where in 1.0:** §6 (`costFrom(event)` on `Connector` trait, line 500); §12 (budget caps, line 908); §19 (`cost.update` event, line 1256).

This is the biggest new content in §7.10. The 1.0 design assumes `costFrom` returns USD; Slice 0 found Codex emits token counts only. So §7.10(b) defines:

- A `~/.forge/prices.json` schema with per-model `inputPerMillionUsd`, `cachedInputPerMillionUsd`, `outputPerMillionUsd`, `reasoningOutputPerMillionUsd`. (The token categories match Codex's `turn.completed.usage` payload from Slice 0 §2.2.)
- An optional per-repo override at `.forge/prices.json`.
- A ship-with-defaults example file at `~/.forge/prices.example.json`.
- Missing-model and missing-file behaviour: `costFrom` returns `None`, a one-time `harness.price_missing` event lands in the action log per `(feature, model)`, the TUI shows `$?`, and budget caps degrade to off for that model.

This last point — *budget caps degrade to off when prices are missing* — is the only behavioural surprise. v1.1 calls it out explicitly rather than silently relying on `cost = 0`. The alternative would be to refuse to spawn when prices are missing; v1.1 doesn't pick that because it would block users who deliberately want to opt out of cost tracking.

Added to Slice 1 §17 deliverables as a named bullet, with an integration test for present / missing / partial price tables.

Also added to §18 config notes and §19 action-log schema (`harness.price_missing` event; `cost.update` annotated with the missing-price degradation).

### 3.3 (c) Sticky session-scoped settings on `codex exec resume`

**Where in 1.0:** §7.1 (`resumeStreamingSpec`, line 488); §11.6 (`fresh driver session for fix-up`, line 887).

`codex exec resume` rejects `--sandbox`, `--output-schema`, `--add-dir`, `-a/--ask-for-approval`, `-C/--cd`. Settings inherit from the original `codex exec` invocation. The 1.0 spec already implies fresh-session-for-fix-up; v1.1 generalises in §7.10(c): *any phase that needs different session-scoped settings than the original spawn must spawn a fresh session*.

This is non-issue for the v1 lifecycle:
- Fix-up already spawns fresh (§11.6).
- Reviewer calls are one-shot `codex exec ...` invocations, not resumes — each can carry its own `--output-schema`.
- Design revision resumes are within the same session-scoped settings (no sandbox change between revision rounds).

So §7.10(c) is documentation-only for v1, but worth recording so that any future feature that wants to swap, say, sandbox mid-session knows to spawn fresh.

---

## 4. Slice 0 status — §16 and §17

**Where in 1.0:** §16 (Slice 0 spec), §17 Slice 0 entry (line 1053).

Both were future-tense in 1.0. v1.1 makes them past-tense:
- §16 heading: "Slice 0 — 2×2 validation (complete)" and an intro line pointing at `docs/slice-0/slice-0-report.md`.
- §16.1 table gains an "outcome" column.
- §17 Slice 0 entry rewritten as a one-liner pointing at the report and naming v1.1 as the resulting revision.

No content lost — the criteria in §16 #1–12 stay verbatim because they ARE the contract Slice 0 validated. They're still useful to read as a checklist when Slice 1 wires real production prompts.

---

## 5. §20 v2 candidates — additions

**Where in 1.0:** lines 1266–1275.

Added two rows:

1. **`SchemaFallback` emulation** — explicitly parked. Names the `SchemaMechanism.SchemaFallback` enum case as the seam, mentions the protocol is sketched in earlier design revisions and can be revived.
2. **Per-model cost telemetry for Claude** — Claude reports `total_cost_usd` directly today; if that changes, the §7.10(b) price-table mechanism extends naturally.

The second is included because v1.1 builds a price-table system for one connector; documenting "we know how to apply this to the other connector if needed" closes a v2 thought rather than leaving it implicit.

---

## 6. §21 decision summary — entries added/revised

**Where in 1.0:** lines 1281–1318.

Revised:

| Row | 1.0 | 1.1 |
|---|---|---|
| Reviewer schema | "`Native` or `SchemaFallback`" | "`Native` only in v1 — both pinned CLIs enforce schemas" |
| Process retries vs schema attempts | "Distinct layers" | "Single layer" |
| Capability emulation | "Only `SchemaFallback`" | "None in v1" |
| Slice 0 fallback bar | "≥19/20 per method" | (removed) |
| Slice 0 capability gap | "Design-scope decision" | (replaced by "Slice 0 outcome: ✅ Green light…") |
| First thing to build | "Slice 0. Then Slice 1" | "Slice 0 (done). Now Slice 1" |

Added:

- **Schema validation failure?** — adapter error, not retried.
- **Session id on resume?** — preserved by both pinned CLIs; event carries old + new for forward-compatibility.
- **Codex cost telemetry?** — price table at `~/.forge/prices.json`; missing entry → `usd=0` + `harness.price_missing`.
- **Codex system prompt?** — prepended as `## System` block.
- **Codex sticky settings?** — fresh session for any settings change.

These all reflect choices made in §7.4 / §7.5 / §7.6 / §7.10. A reader who scans only §21 should be able to reconstruct what v1.1 changed without going hunting.

---

## 7. Net assessment

v1.0 → v1.1 is the smallest substantive delta since the post-0.5 stabilisation, despite touching ten sections. The breakdown:

- **One protocol removed** (`SchemaFallback`), with cascading cleanups across §7, §16, §17, §18, §19, §22, §1, §21.
- **One spec assumption corrected** (session id on resume), with a clarification in §6.1 and softened wording in §11.2 and §11.3.
- **One new subsection** (§7.10 Codex adapter notes), consolidating three small adapter-internal behaviours.
- **One Slice 1 deliverable added** (Codex price table).
- **Two v2 candidates added** (SchemaFallback emulation, Claude price-table extension).

No architectural changes. No new types. No new FSM states or transitions. The connector trait shape is unchanged (the §7.10 adapter notes don't alter the contract; they explain how Codex implements it). The Slice 1 trait stubs already in `modules/forge-agents/src/main/scala/io/forge/agents/Connector.scala` continue to compile unchanged — Slice 1 just wires `SchemaMechanism.Native` into both connectors and follows §7.10 for the Codex adapter.

After 1.1, the design is implementation-ready for Slice 1. The shape of the system has been stable across six consecutive revisions (0.5 → 0.6 → 0.7 → 0.8 → 0.9 → 1.0 → 1.1).

---

## 8. What 1.1 does *not* change from 1.0

- All architectural decisions.
- Two-mode model (`ClaudeDriver`, `CodexDriver`).
- `AgentSession` / `StreamingSession` hierarchy and the §7.1 connector trait signature.
- `HaltWithQuestion` protocol and its 5-respawn cap (§7.3).
- Native questions for Claude (`AskUserQuestion`) — §7.2 unchanged.
- ChangeCollector (three classes, phase-aware denial).
- CI policy (two variants, three timeout transitions, epoch-scoped cache).
- `PlanningUpdate` carrying inline `ManifestPatch`.
- Budget enforcement (three caps, per-turn mid-turn `kill()`).
- Locking semantics.
- Per-phase settle timeouts.
- Phase-specific Q&A answer paths.
- Manifest invariants (slugging, `baseSha` nullability, atomic merge mutation, reorder rule).
- Session-id durable-field model (`feature.designSessionId`, `feature.currentPieceSessionId`) and `requireSessionId` precondition.
- Standalone-spec policy (§23) — v1.1 is standalone; this commentary is delta-only.

The §23 policy continues: 1.1 is standalone; no back-references to 1.0. This commentary is the only place the deltas are recorded — read v1.1 cold to implement; read this only to understand why something changed.
