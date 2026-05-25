# Forge design v0.4 — review commentary

> Critical review of `forge-design-0.4.md`. All seven findings from the reviewer are accepted; the most important one (role configurability) reverses a decision 0.4 got wrong. The companion `forge-design-0.5.md` applies these decisions.

**Reviewer:** Rory (consolidated review)  •  **Date:** 2026-05-25  •  **Target doc:** `forge-design-0.4.md`  •  **Precedes:** `forge-design-0.5.md`

---

## 1. Reversing 0.4's role-removal decision

**Where:** forge-design-0.4.md:27, 556, 687

0.4 deferred agent role pluggability to v2, framing it as scope creep introduced by 0.3. That framing was wrong: role configurability is a stated **product requirement**, not a generalisation. The product needs to be runnable as either Claude-driver / Codex-reviewer *or* Codex-driver / Claude-reviewer, and the design must reflect that.

The 0.3 proposal overshot in the *other* direction by introducing an open-ended `DriverAgent` / `ReviewAgent` capability matrix with "validate or emulate" emulation strategies. That's still too much for v1.

**Decision for 0.5:** Minimal role model — exactly two supported modes, no capability matrix, no emulation layer:

```scala
enum Mode:
  case ClaudeDriver   // Claude is specDriver + implementationDriver + fixup;
                      // Codex is designReviewer + codeReviewer + refineryReviewer
  case CodexDriver    // mirror
```

Config:
```json
{ "mode": "claude-driver" }   // or "codex-driver"
```

Both connector modules expose the surface needed for both roles. The orchestrator dispatches by mode. No third combination is supported in v1 (no Claude-reviews-Claude, no mixed assignments).

Implications:
- Slice 0 validates *both* CLIs in *both* roles: streaming spec, headless implementation, fix-up, schema-constrained review. The matrix is 2×2 (small, bounded), not N×M.
- The connector traits aren't speculative abstractions — they're concrete contracts each CLI must satisfy to participate in either mode.
- "Codex can't do streaming spec" or "Claude can't do schema-constrained output" become concrete Slice 0 findings, not open-ended emulation work. If a CLI fails a role, that role's mode is unsupported and the doc says so.

This is the **single most important change** in 0.5.

---

## 2. Missing source-of-truth / logging table

**Where:** forge-design-0.4.md:14, 76

0.4's summary claims the local canonical log + committed audit split is "kept from 0.3", but the actual table from 0.3 §2 didn't make it into 0.4. The reader has to cross-reference back to 0.3 to know what gets committed and what doesn't.

**Decision for 0.5:** Restore the table verbatim from 0.3 §2 as a top-level §2 in 0.5. Include `auditMode` values and what each value commits.

---

## 3. Stale role terminology

**Where:** forge-design-0.4.md:321 ("Resume specDriver design session"), 482 ("driver or reviewer")

0.4 removed roles structurally but left role vocabulary in the prose. Either remove all role words or restore the concept. With §1 above restoring roles, the terminology becomes consistent again — but 0.5 needs to use it deliberately throughout, not as 0.3 residue.

**Decision for 0.5:** Use role names (`driver`, `reviewer`) as concrete identifiers tied to the configured `Mode`. "Driver" always means the CLI selected by mode; "reviewer" always means the other one. The prose in §7 (lifecycle) reads naturally either way.

---

## 4. Manifest schema missing fields used later

**Where:** forge-design-0.4.md:108 (schema example), 274 (`baseFreshness`), 457 (`--commit-human-fix` branch check)

The example manifest in 0.4 §3 doesn't include `baseSha` (used by §6 freshness check) or `branch` (used by §10 branch check). 0.4 leaves the reader to infer.

**Decision for 0.5:**
- **Add `baseSha`** to each piece's manifest entry (captured at branch creation time in §7.4).
- **Derive `branch`** from `branchPrefix + featureId + pieceId`. Document the derivation rule in §3 explicitly so §10 doesn't have to invent it.

---

## 5. `forge reconcile` is still underspecified

**Where:** forge-design-0.4.md:137

0.4 says "applies the diff as a PlanningUpdate" but hides the hard part: converting arbitrary Markdown edits into typed `ManifestPatch` operations. Three options exist:

| Option | Pros | Cons |
|---|---|---|
| Deterministic rule-based parsing | Predictable, no LLM | Breaks the moment a user edits anything not encoded in the rules |
| Model-assisted (ask the driver to propose a `ManifestPatch`) | Handles arbitrary edits | Reintroduces LLM dependency in a previously-deterministic path; cost; non-determinism |
| Constrained editable regions (HTML-comment markers in the rendered file) | Predictable, no LLM, user knows the rules | Edits outside markers are rejected — rigid but learnable |

**Decision for 0.5:** **Constrained editable regions.** The rendered `decomposition.md` includes HTML-comment markers around editable sections. Editable: piece summary text, piece order (detected by heading order). Not editable: piece IDs, acceptance criteria (those live in the per-piece `pieces/<p>.md` files), merged-status badges.

If the user edits outside markers, `forge reconcile` shows the offending edits and refuses with: "These edits aren't reconcilable from the rendered file. Edit `manifest.json` directly, or the underlying `pieces/<p>.md` file, then re-run." Rigid, but predictable and zero-LLM.

Edits *inside* markers map deterministically to `EditPiece(specPath=..., title=...)` or `ReorderPieces(...)` ops.

This is more constrained than 0.4 implied but eliminates the silent failure modes a model-assisted reconcile would have.

---

## 6. Required overlay checks can wait forever

**Where:** forge-design-0.4.md:245, 247

If `requiredChecksOverlay` names a check that never appears (typo, renamed CI job, missing workflow), the polling loop has no exit condition. The doc covers "no checks discovered at all" but not "the *overlay-required* check is missing".

**Decision for 0.5:** Apply `checkDiscoveryTimeoutSec` to overlay checks specifically: at the end of the discovery window, if any overlay-required check name is not present in the rollup, transition to `NeedsHumanIntervention("required overlay check '<name>' never appeared", ResumeAfterHumanPush(p, prNumber))`. Resume after the human either pushes a commit that triggers the missing check or edits the overlay.

---

## 7. Design revision snapshot tag push/retention

**Where:** forge-design-0.4.md:323

0.4 introduces `git tag forge/<feature>/design-r<n>` before each force-push, but doesn't say:
- Is the tag local-only or pushed to the remote?
- How are old snapshot tags cleaned up?
- Do they live in a separate namespace?

**Decision for 0.5:**
- **Local-only by default.** Snapshot tags don't pollute the remote; recovery is on the machine that did the work. Document explicitly: "recovery of pre-revision design state is local-machine only."
- Tags live under `forge/_snapshots/<feature>/design-r<n>` (the `_snapshots` namespace makes them visually distinct from feature branches).
- Optional config `github.pushSnapshotTags: false` (default) flips to pushing them. If pushed, retention rule applies: `git push origin --delete refs/tags/forge/_snapshots/<feature>/design-r<n-3>` after each push, keeping the last 3.
- Same rule for piece snapshots if they're ever added (not in 0.5 — only design revisions get snapshotted because they're the only force-push case).

---

## 8. Net assessment

The 0.4 → 0.5 changes are smaller in count than 0.3 → 0.4, but one of them (role configurability) is a stated product requirement that 0.4 wrongly deferred. The remaining six are tightening and gap-closing.

0.5 should not change:
- Manifest as machine source (`forge reconcile` becomes more constrained, but the manifest itself is unchanged).
- ChangeCollector with three classes and allow-anywhere-not-denied default.
- CI policy with two variants.
- `PlanningUpdate` carrying an inline `ManifestPatch`.
- `Refining` failure → `PieceMerged` (advisory).
- Per-turn cost ceiling.
- AskUserQuestion always to Forge Q&A pane.
- Line-based GitHub comment API.
- Command-aware preflight.
- Stale-lock metadata UX.

0.5 reverses or extends:
- **Reverses:** role configurability is in v1 with two supported modes.
- **Extends:** Slice 0 validates 2×2 (both CLIs in both roles).
- **Adds:** SoT/logging table back in §2, manifest gets `baseSha`, derives branch name, overlay-check timeout, reconcile constrained to marker regions, snapshot tag rules.

---

## 9. Acknowledgement

0.4's framing of role pluggability as scope creep was a misread of the requirement. The 0.3 reviewer's instinct was correct; the *implementation* they proposed (full role abstraction + capability matrix + emulation) was too much. 0.5's minimal two-mode model is what both reviewers were converging toward.

The lesson for the design process: when a reviewer cites a requirement, distinguish *the requirement* from *the proposed implementation of it*. Reject the implementation if it's overweight; don't reject the requirement along with it.
