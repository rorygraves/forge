# Forge design v0.3 — review commentary

> Critical review of `forge-design-0.3.md`. The 0.3 proposal substantially improves operational safety over 0.2 (which itself improved on 0.1), and most of its changes should be kept. This commentary identifies (a) places where 0.3 trades one underspecification for a different one, (b) new bugs introduced by 0.3, (c) scope creep worth trimming, and (d) issues both the 0.2 commentary and 0.3 missed. The companion `forge-design-0.4.md` applies these decisions.

**Reviewer:** Claude (second pass)  •  **Date:** 2026-05-24  •  **Target doc:** `forge-design-0.3.md`  •  **Precedes:** `forge-design-0.4.md`

---

## 1. What 0.3 got right (kept in 0.4 with minor tightening)

These are accepted wholesale or with small clarifications:

| 0.3 section | What | 0.4 disposition |
|---|---|---|
| §2 | Split local canonical runtime log from committed sanitized audit artifacts | Kept. Tighten `auditMode` defaults and spell out what each milestone snapshot contains. |
| §3 | `manifest.json` as machine source for pieces; Markdown as human view | Kept. Add the reconcile UX (§3.1 below) — when does it run, what does the user see. |
| §4 | Typed `ResumeHint` variants; `DesignPrFeedback`; `PlanningUpdate`; `Refining` | Kept structurally. Fix `PlanningUpdate` carrying a file path (§2.1 below). Add a `Refining` failure path (§4.1 below). |
| §6 | `BranchManager.syncBase()` and `ensurePrContainsBase()` | Kept. Specify what the FSM does on `stale` (§3.2 below). |
| §7 | Explicit CI readiness policy; discovery timeout; stable-green polls | Kept structurally. Trim from 5 policy variants to 2 (§5.1 below). |
| §8 | Feedback gates at design *and* piece PRs through merge | Kept. Specify force-push vs new-PR for design revisions (§3.3 below). |
| §9 | `ChangeCollector` with classification | Kept. Flip default to allow-anywhere-not-denied (§2.2 below). |
| §10 | Forge owns the diff; review agent posts inline + fallback summary | Kept. Pin the comment API and define `anchorText` matching (§3.4 below). |
| §11 | Three explicit `forge resume` subcommands | Kept. Clarify "active piece branch" check (§3.5 below). |
| §12 | Budget checks before spawn and after every `cost.update` | Kept. Add a per-turn ceiling (§2.3 below). |
| §13 | OS lock + `.lock.json` metadata | Kept. Specify stale-detection UX (§3.6 below). |
| §14 | Refinery proposes manifest patches via `PlanningUpdate` | Kept. Inline patch in state and define the patch format (§2.1, §2.4 below). |
| §17 | Property tests on the FSM | Kept and expanded. |

## 2. New bugs introduced by 0.3 (must fix in 0.4)

### 2.1 `PlanningUpdate(reason, patchPath: String)` puts a file path in FSM state
**Where:** forge-design-0.3.md:216, 553–560

A path is fragile: across restarts, machine moves, or accidental cleanup of `.forge/specs/<feature>/audit/`, the FSM state references something that may no longer exist. The state file ends up with a dangling pointer.

**Decision for 0.4:** Patches are small. Inline them: `PlanningUpdate(reason: String, patch: ManifestPatch)`. The patch is still *also* written to the audit dir for human review, but the FSM's source of truth is the in-state copy. Resume is robust to audit-dir loss.

### 2.2 `allowNewFilesUnder` allow-list defaults are Scala-centric
**Where:** forge-design-0.3.md:415, 612

Defaulting `allowNewFilesUnder: ["app/", "src/", "test/", "docs/", ".forge/specs/"]` means a Python or Go repo without `src/` refuses every new file Claude writes. This is a footgun that turns the safety net into a wall.

**Decision for 0.4:** Default policy is **allow anywhere not denied**. The deny list catches the dangerous cases (secrets, build outputs, IDE caches). Users who want a stricter posture set `staging.requireExplicitAllow: true` and enumerate paths.

### 2.3 §12 "let the current turn settle" has no in-turn ceiling
**Where:** forge-design-0.3.md:499–507

A runaway tool-use loop *is* the current turn. "Let it settle" can mean another \$5+ in one turn. The budget framework checks *between* turns but lets a single bad turn blow through.

**Decision for 0.4:** Add `maxTurnCostUsd` (default \$2). On the next `cost.update` event within an active turn that exceeds the cap, Forge sends an abort signal to the subprocess (SIGTERM, fall back to SIGKILL after 5s) and transitions to `NeedsHumanIntervention("turn budget exceeded", ...)`. The turn does *not* get to settle.

### 2.4 `manifestPatchPath` doesn't pin a patch format
**Where:** forge-design-0.3.md:553

JSON Patch (RFC 6902)? Custom? Full replacement?

**Decision for 0.4:** Patches are a small custom ADT, serialised as JSON. Operations: `AddPiece(after: PieceId, piece: Piece)`, `RemovePiece(id: PieceId)` (rejected if merged), `EditPiece(id: PieceId, fields)`, `ReorderPieces(newOrder: Vector[PieceId])`. Easier to validate against domain rules ("can't remove merged pieces") than generic JSON Patch.

### 2.5 `Refining` state has no failure path
**Where:** forge-design-0.3.md:215, §14

§14 only covers verdict-based outcomes. If the refinery `codex exec` errors (network, schema-invalid after retries, sandbox failure), the FSM is wedged in `Refining`.

**Decision for 0.4:** `Refining` failure → `PieceMerged` plus a `harness.refinery_failed` action-log entry. Rationale: the piece already merged successfully; refinery is advisory, not a gate. Don't block the feature on advisory tooling.

## 3. Places where 0.3 still hand-waves (tighten in 0.4)

These are the same shape of issue the 0.2 commentary criticised 0.2 for; 0.3 inherited or re-introduced them.

### 3.1 Manifest reconcile UX is unspecified
**Where:** forge-design-0.3.md:182, 696

"Manual edits to `decomposition.md` are treated as a proposed import/reconcile, not silently accepted as state." When does the reconcile run? Via what UX? What does the user see?

**Decision for 0.4:** On every `forge` command, Forge hashes `decomposition.md` and compares to the manifest-rendered version. On mismatch: refuse the command (similar to dirty-worktree refusal) with `forge reconcile <feature>`. `forge reconcile` shows a unified diff in the TUI/CLI, asks "import these edits into the manifest? (y/N)", and on yes applies them as a `PlanningUpdate` flow. Hard rule: pieces already merged cannot be removed by reconcile.

### 3.2 `ensurePrContainsBase()` returns a status but the FSM action on `stale` is unspecified
**Where:** forge-design-0.3.md:287, 296–300

What does Forge do when PR doesn't contain latest base? §6 is silent.

**Decision for 0.4:**
- If branch protection requires up-to-date: do nothing — the protection itself will block the merge, and `gh pr update-branch` (which the human can trigger) is the normal flow.
- If branch protection does *not* require up-to-date and `config.baseFreshness.autoUpdate: true`: Forge runs `gh pr update-branch` itself, re-enters `PieceAwaitingCi`, lets CI re-run.
- If `autoUpdate: false`: transition to `NeedsHumanIntervention("piece <p> PR is behind base", ResumeAfterHumanPush(...))`.

Default: `autoUpdate: true`.

### 3.3 Design PR revision: force-push vs new PR
**Where:** forge-design-0.3.md:357–360

"Commit and push a new design PR revision" is ambiguous. Force-push retains the PR (and any reviewer state). New PR loses both.

**Decision for 0.4:** **Force-push to the design branch.** Spell it out. Add a `pre-revision-snapshot` tag (`forge/<feature>/design-r<n>`) before each force-push so the prior revision is recoverable. Use `git push --force-with-lease` to refuse if someone else pushed concurrently.

### 3.4 `anchorText` fuzzy-match strategy is unspecified
**Where:** forge-design-0.3.md:445–446, 451

"Fall back to a summary bullet for any invalid anchor" is fine, but the *valid* path needs a definition: when the absolute line has drifted, how does Forge re-anchor?

**Decision for 0.4:** Algorithm: (1) try posting at the model-provided `line`; (2) on API rejection, scan ±10 lines of `line` in the latest PR head for an exact substring match of `anchorText`; (3) on no match, scan the whole changed-file diff; (4) on still no match, demote to a summary bullet. Log each demotion as `review.anchor_demoted`. Pin to the GitHub `POST /pulls/{n}/comments` line-based API (Slice 0 confirms `gh` version).

### 3.5 "Active piece branch" check is operationally fuzzy
**Where:** forge-design-0.3.md:267, §11

`--commit-human-fix` says "allowed only on the active piece branch". Who validates? What if the user fixed on a different branch?

**Decision for 0.4:** Forge checks `git branch --show-current` against the FSM's expected piece branch derived from `manifest.json`. Mismatch → refuse with a clear "you are on `<X>`, the piece branch is `<Y>`. Switch branches and retry, or use `forge resume --after-human-push` if you've already pushed your fix to the piece branch." No magic.

### 3.6 Stale-lock detection UX is unspecified
**Where:** forge-design-0.3.md:534–537

"Forge treats it as stale and can remove it after confirmation." TUI prompt? CLI flag? Automatable?

**Decision for 0.4:** On startup, if `.lock.json` exists but no OS lock is held:
- TUI mode: confirmation prompt with the stale metadata shown.
- CLI mode: refuses with the metadata, suggests `forge unlock --force` (which now succeeds because there's no live OS lock).
- `--yes` flag or `FORGE_AUTO_UNLOCK_STALE=1` env var: auto-clear stale metadata. Useful in CI/automation.

## 4. Scope creep: defer to v2

### 4.1 §1 "Agent role model" — defer the abstraction, keep the seam
**Where:** forge-design-0.3.md:37–107

The role abstraction (`DriverAgent` / `ReviewAgent` traits, capability matrix validation, "validate or emulate" emulation strategies) is defensible for OSS positioning but adds substantial v1 complexity for benefit that may never materialise. The "validate or emulate" line in particular is an unbounded promise: if Claude can't natively do schema-constrained output, the emulation involves prompt engineering + JSON Schema validation + retry loops + system-prompt schema injection — its own sub-project.

**Decision for 0.4:**
- v1: Claude is the driver, Codex is the reviewer. **Hard-coded.**
- The connector modules (`ClaudeConnector`, `CodexConnector`) remain separate (already done in 0.2's `forge-agents`).
- The FSM calls connectors directly — no `DriverAgent` / `ReviewAgent` trait introduced in v1.
- Document the role-pluggability vision in §15 ("v2 candidates") so the seam is acknowledged.
- Capability-matrix validation is removed from Slice 0; only "do these two specific CLIs do what we expect?" remains.

Cost saved: one full module of abstraction, the emulation sub-project, and the validation matrix. Cost paid: a v2 refactor when (and *if*) a second pair of agents matters.

### 4.2 §9 `ChangeCollector` change classes — start with three
**Where:** forge-design-0.3.md:395–402

Six change classes (`AllowedTrackedEdit`, `AllowedNewFile`, `GeneratedOrIgnored`, `SecretOrSensitive`, `OutsideRepo`, `Unexpected`) is more granularity than v1 needs.

**Decision for 0.4:** Three classes — `Allow`, `Deny`, `Ask`. `Deny` covers the security cases (secrets, outside-repo). `Ask` pops a Q&A prompt for ambiguous cases. The deny list is the lever; categories grow on demand.

### 4.3 §7 CI policy variants — start with two
**Where:** forge-design-0.3.md:233–237, 325–329

Five variants (`BranchProtectionThenObserved`, `BranchProtectionOnly`, `ConfiguredRequiredChecks`, `ObservedChecks`, `None`) is over-engineered for v1.

**Decision for 0.4:** Two variants: `BranchProtectionThenObserved` (default) and `None` (intentional skip). The "configured" and "observed-only" paths are reachable via tuning the discovery timeout and `requiredChecks` overlay — no separate variant. Add others when a real repo demands them.

## 5. Issues both 0.3 and the 0.2 commentary missed

### 5.1 AskUserQuestion during `DesignPrFeedback` / fix-up has no model
**Where:** forge-design-0.3.md:§8, §11

If a human leaves a PR comment that triggers a design-revision (or fix-up) Claude session, and Claude raises `AskUserQuestion` mid-revision, two question channels are now live: the GitHub PR comment thread and the Forge Q&A pane.

**Decision for 0.4:** All `AskUserQuestion`s go through the Forge Q&A pane regardless of trigger origin. The action log records which trigger (PR comment vs spec phase vs fix-up) so the audit trail is clear. No question is ever posted back to GitHub by Forge.

### 5.2 No per-feature GitHub API rate-limit budget
**Where:** new

`gh` polls, diff fetches, comment-thread fetches, branch-protection lookups, manifest commits — all hit GitHub. A feature with many fix-up rounds can burn through the 5000/hour secondary limit.

**Decision for 0.4:** Slice 0 measures the typical call count per piece. Slice 3 adds a back-off on 403/429 with `Retry-After` honoured, and a `harness.rate_limited` action-log event. Caching: branch-protection result cached for the feature's lifetime; diff cached per (PR, head SHA).

### 5.3 GitHub inline comment API choice is unpinned
**Where:** forge-design-0.3.md:445–451, §16 line 647

§10 uses `path/side/line` which matches the newer line-based `POST /pulls/{n}/comments` API. The classic `position`-in-diff-hunk variant is different. Slice 0 lists it as an open question but the design assumes the newer one works.

**Decision for 0.4:** Pin to the line-based variant. Slice 0 confirms `gh` version supports `--method POST /repos/.../pulls/.../comments` with `path`, `side`, `line`, `commit_id`. If not, fall back to using the GitHub REST API directly via `gh api` raw.

### 5.4 `Refining` is invisible in the UI
**Where:** forge-design-0.3.md:215

It's instantaneous in the happy case but on a slow refinery call (or schema-retry loop), the user sees... nothing.

**Decision for 0.4:** TUI status pane shows "Refinery: checking design against merged piece (`<n>`s elapsed)". CLI mode prints a single dotted-progress line. Minor UX work, but eliminates the "is it hung?" question.

### 5.5 `gh pr review --request-changes` requires a non-empty body
**Where:** forge-design-0.3.md:435

Trivial but: if the review agent produces zero blockers but `verdict: request_changes` (a buggy adapter output), the post fails. Validate before posting.

**Decision for 0.4:** Validation step: `verdict == request_changes` requires at least one blocker. On violation, treat as adapter bug, log `review.invalid_verdict`, retry the review prompt once with a "your verdict was request_changes but you provided no blockers" correction.

### 5.6 Slice 0's scope grew quietly
**Where:** forge-design-0.3.md:642–654

0.3's Slice 0 has 9 validation items (up from 8 in 0.2), with the new ones implying capability-matrix and emulation validation. Without the role abstraction (per §4.1 above), the checklist contracts back.

**Decision for 0.4:** Slice 0 = original 8 v0.2 items + the line-based comment API check (§5.3 above) + rate-limit baseline measurement (§5.2 above). No capability matrix.

## 6. Net assessment of 0.3

The 0.3 proposal **materially improves correctness and operational safety**. The 0.2 → 0.3 delta is the most important step in the design's evolution: PR feedback through merge, audit/log split, manifest-backed metadata, CI policy, and typed resume hints all close real holes.

But 0.3 has two systemic weaknesses:

1. **Inherited hand-wave style.** Six places where 0.3 introduces new mechanisms without specifying the operational details (manifest reconcile UX, base-stale FSM action, force-push vs new-PR, anchorText matching, active-branch check, stale-lock UX). 0.4 closes these.
2. **Scope creep.** Agent role abstraction, six change classes, five CI policy variants — each defensible individually, together pushing Slice 1–2 well past the original five-week plan. 0.4 trims to the v1 essentials and defers the rest with a documented seam.

0.4's footprint relative to 0.3: similar FSM size, fewer abstractions, tighter operational rules, one resolved comment-API choice, and three "we forgot about this" gaps closed.

## 7. Things deliberately left for 0.5+

These are real but premature:

- **Parallel features** — explicit v2 since 0.1.
- **GitLab adapter** — explicit v2 since 0.1; `PrSnapshot` ADT keeps the seam clean.
- **Role pluggability** — promoted from "0.3 design choice" back to v2 candidate (this commentary §4.1).
- **Stacked PRs / auto-merge** — explicit v2 candidates since 0.1.
- **OSS positioning concerns** (audit defaults, sensitive-repo handling beyond the v0.3 `auditMode`) — when a second user exists.
- **The capability emulation sub-project** (schema-constrained output via prompt-and-validate) — only if the role abstraction is ever taken up.
