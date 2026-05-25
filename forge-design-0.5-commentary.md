# Forge design v0.5 — review commentary

> Review of `forge-design-0.5.md`. 0.5 successfully restores the two-mode driver/reviewer requirement that 0.4 wrongly dropped, brings back the source-of-truth table, fixes the manifest/baseSha gap, constrains `forge reconcile` deterministically, and answers the overlay/tag questions. Six remaining issues sharpen the two-mode promise from "supported in principle" into "specified well enough to build". The companion `forge-design-0.6.md` applies these decisions.

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.5.md`  •  **Precedes:** `forge-design-0.6.md`

---

## 1. "No emulation" conflicts with the Claude-reviewer plan

**Where:** forge-design-0.5.md:537, 559

§14 claims "no emulation layer". §15 then says Claude-as-reviewer may use "prompt-side schema instruction with validator retry". That's emulation. The doc contradicts itself.

The honest framing: bounded prompt+validate+retry **is** emulation, and pretending otherwise hides a contract decision the implementer has to make anyway. The pragmatic call isn't to ban it — it's to make it explicit and bounded.

**Decision for 0.6:** Allow exactly one form of emulation, name it explicitly as **"schema fallback"**, and constrain its scope:

- Applies *only* to schema-constrained reviewer output. Not to streaming, not to questions, not to sandboxing, not to anything else.
- Strategy is fixed: the connector instructs the model to produce JSON matching the schema, validates locally, and on validation failure retries once with a corrective prompt ("your last output did not match the schema"). Maximum **2 attempts total**.
- On second failure: connector returns an adapter error → FSM transitions to `NeedsHumanIntervention("reviewer schema fallback exhausted", ...)`.
- Connectors declare per-method whether they're using **native** (CLI-enforced) or **fallback** (prompt+validate+retry). The action log records which.
- Any *other* missing capability fails Slice 0 for that mode — no further emulation.

This satisfies "bounded fallback" without opening the door to arbitrary capability emulation.

---

## 2. Codex-driver without a question primitive silently breaks human-escalation

**Where:** forge-design-0.5.md:354, 556, 577

§15 says "If Codex can't ask questions, `CodexDriver` mode degrades — driver just proceeds without asking". This silently breaks Goal 9 ("escalate only on `AskUserQuestion`, red required CI, or budget breach") — uncertainty events that should escalate now don't.

The reviewer is correct: "silent proceed" is unsafe.

**Decision for 0.6:** Driver question capability is **required**, satisfied by *one* of two mechanisms:

| Mechanism | How |
|---|---|
| **(a) Interactive primitive** | The driver suspends mid-turn (tool-use suspension), Forge pops the Q&A pane, answer sent back as `tool_result`. Claude has this via `AskUserQuestion`. |
| **(b) Halt-with-question protocol** | The driver stops execution and emits a structured output like `{ status: "needs_human", question: "...", options: [...] }` against a known schema. Forge surfaces via Q&A pane, then re-spawns the driver with the answer prepended to the next prompt. |

A driver mode missing **both** is **unsupported** in v1 — Slice 0 declares it, not "we'll proceed without asking".

For Codex specifically: if Codex `exec` doesn't have a native interactive question primitive, the driver's system prompt teaches it the halt-with-question protocol. Forge's prompt engineering (in `~/.forge/prompts/implement.codex.md`) explicitly establishes this convention.

Trade-off: option (b) costs a subprocess re-spawn per question (vs. mid-turn suspension in option (a)). For Codex's typical question rate, acceptable. The action log records which mechanism was used.

---

## 3. "Both modes first-class" vs "unsupported cells" framing

**Where:** forge-design-0.5.md:46, 577

Goal 6 says both modes are first-class. §15 says Slice 0 may declare cells unsupported. These coexist uneasily — "first-class but maybe not supported" is muddled.

**Decision for 0.6:** Reframe explicitly:

> **v1's design target is both modes as first-class.** Slice 0's job is to confirm that the pinned versions of Claude and Codex satisfy the required capabilities for both modes. If Slice 0 discovers a capability gap that cannot be closed by the bounded "schema fallback" or "halt-with-question" protocols, **the design itself fails** — the response is one of:
>
> 1. Wait for a future CLI version that closes the gap.
> 2. Change the supported-mode scope explicitly (e.g., "v1 ships claude-driver only; codex-driver is v2 pending Codex CLI support for X"), updated as a doc change.
> 3. Treat as a v1-blocking issue.
>
> Slice 0 is *not* a triage that silently picks whichever mode works — it's a validation that informs scope, and scope changes are explicit doc changes.

---

## 4. `minimumExpectedChecks` timeout outcome unspecified

**Where:** forge-design-0.5.md:263, 264

§5 says "If checks have appeared but fewer than `minimumExpectedChecks`, keep polling until met or timeout." The "or timeout" branch has no transition. (Compare to the overlay-check rule directly above it, which has a clean `NeedsHumanIntervention` transition.)

**Decision for 0.6:** Add explicit transition: after `checkDiscoveryTimeoutSec` has elapsed and the observed check count is below `minimumExpectedChecks`:

```
NeedsHumanIntervention(
  s"only ${observed} CI checks observed, expected at least ${minimumExpectedChecks}",
  ResumeAfterHumanPush(p, prNumber)
)
```

Resume after the human either fixes the CI config (so more checks appear) or lowers `minimumExpectedChecks` for this repo.

---

## 5. Reorder validation needs a sharper invariant

**Where:** forge-design-0.5.md:149, 159

"Pieces already merged cannot be removed or have their order changed past the merge point" is directionally right but ambiguous. "Past the merge point" doesn't tell an implementer what's actually invariant.

**Decision for 0.6:** State the invariant precisely:

> **Merged pieces form an immutable prefix of the piece list, in their original relative order. Reorder operations affect only the pending tail.**

Concretely, the `ReorderPieces(newOrder)` validator must check:
1. `newOrder` contains exactly the same piece IDs as the current piece list (no add/remove via reorder).
2. The merged pieces, in their original order, appear as the prefix of `newOrder`. Equivalently: `newOrder.take(mergedCount) == currentOrder.take(mergedCount)` where `mergedCount` is the number of merged pieces.
3. Only the suffix (pending pieces) may be permuted.

This rules out reordering that would interleave a pending piece between two merged pieces, or reorder merged pieces among themselves.

---

## 6. `FeatureId` slugging and collision handling are implicit

**Where:** forge-design-0.5.md:124

Branch names, paths, tags, and manifest identity all derive from `FeatureId`, but the doc never says how `forge new "Add Stripe webhook receiver"` becomes the id `stripe-webhook` (vs `add-stripe-webhook-receiver`, `stripe_webhook`, etc.), or what happens on collision.

**Decision for 0.6:** Specify the slug algorithm and collision rule:

**Slug algorithm:**
1. Lowercase.
2. Replace any run of non-`[a-z0-9]` characters with a single `-`.
3. Trim leading/trailing `-`.
4. Truncate to 40 characters at the last hyphen boundary (no mid-word truncation).
5. If the result is empty or starts with a digit, prefix with `f-`.

**Collision handling:**
- Check `.forge/specs/<slug>/` for existence.
- On collision, append `-2`, then `-3`, etc., until a free slug is found.
- The chosen slug is shown to the user before `forge new` proceeds. `--id <explicit-id>` overrides the algorithm entirely (validated to match `^[a-z][a-z0-9-]{0,49}$`).

Examples:
- `forge new "Add Stripe webhook receiver"` → `add-stripe-webhook-receiver`.
- `forge new "Fix bug #1234"` → `fix-bug-1234`.
- `forge new "🚀 Launch"` → `launch`.
- Second `forge new "Add Stripe webhook receiver"` → `add-stripe-webhook-receiver-2`.

---

## 7. Net assessment

0.5 closes 0.4's product-requirement regression and most of the structural gaps the prior reviews flagged. The remaining six findings are all about **making the two-mode promise crisp**:

- Findings 1 and 2 are the load-bearing ones: define the connector's emulation budget precisely (one mechanism, bounded), and never let the driver silently skip human escalation.
- Findings 3, 4, 5, 6 are tightening — turning "directionally right" into "specifiable in code".

0.6 is a small delta over 0.5: one new concept (schema fallback as a named, bounded thing), one new protocol (halt-with-question as the driver-question fallback for non-interactive CLIs), and four targeted spec tightenings. No architectural moves.

---

## 8. What 0.6 does *not* change

- The two-mode `Mode` enum remains exactly two variants. No third combination.
- ChangeCollector, CI policy, manifest, audit split, command-aware preflight, locking, budget enforcement — all unchanged.
- The `Connector` trait stays as 0.5 defined it; gains only a per-method `Mechanism` annotation (`Native` | `SchemaFallback` | `HaltWithQuestion`) for transparency in the action log.
- v2 candidates list unchanged.
