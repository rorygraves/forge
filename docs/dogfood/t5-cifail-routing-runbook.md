# T5 — live re-run runbook: §8.2 CI-fail → local-autofix routing on `szork`

> **What this closes:** [`design-3.0.md`](../design-3.0.md) Task 3.1.2's last open box
> (the ⬅ **gating** item): *"Re-run `extract-media-network-config` (or an equivalent
> format-gated feature) on `szork` with a hand-authored `.forge/profile.json`; confirm
> the scalafmt CI failure routes to a local `scalafmtAll`, `attempts` stays 0, and
> `forge stats` shows the avoided round."* Until it lands, Phase 3 is *types-without-teeth*.
>
> **Status:** prepared 2026-06-03 — wiring verified green (see §1); **live run not yet
> driven** (needs real `claude`/`codex`/`gh` + API spend against `szork`, partly
> interactive). Drive §3, then fill in §5.

---

## 0. The one decision that shapes the run — `adapt.localGate`

Two Phase-3 seams both remediate a scalafmt failure, at **different points in the lifecycle**:

| Seam | When | Config | Effect |
|---|---|---|---|
| **§8.3 local format gate** (Task 3.1.3) | *pre-PR*, before the piece commit | `adapt.localGate = true` (**default**) | `sbt scalafmtAll` runs on the worktree before commit → the commit is format-clean → **CI never fails**. Cheapest, but the §8.2 CI router never fires. |
| **§8.2 CI-fail routing** (Task 3.1.2 = **this T5**) | *post-PR*, after CI fails | reached only when the format issue survives to CI | the failing-check log is fetched, classified `deterministic_fix`, routed to `RunLocalCommand(sbt scalafmtAll)`, amended + pushed, polling continues — **no fix-up driver turn, `attempts` untouched**. |

Because the default `localGate = true` **pre-empts the CI failure**, T5 — which is specifically
about the §8.2 CI-fail routing path — must be driven with **`adapt.localGate = false`** so the
driver's non-conformant output survives to CI and the §8.2 router is exercised. Run §3 in that
mode (**Mode A**). Optionally re-run with the production default afterwards (**Mode B**, §4) to
confirm the shift-left path is even cheaper.

> This is a real interaction, not a config quirk: §8.3 landed *after* T5 was written and shifts
> the same fix left of CI. T5 still has teeth — it proves the CI router is a correct **backstop**
> for any format issue the pre-commit gate misses (e.g. a `Heuristic` formatter the local gate
> won't run, or a repo with `localGate` deliberately off).

---

## 1. Wiring already proven green (unit) — what the live run adds

The whole decision chain is unit-complete; the live run only confirms it against real `szork` CI:

- `OrchestratorCiRoutingSuite` — *"profiled scalafmt CI failure routes to local autofix —
  attempts stays 0, no fix-up round"* (the exact §8.2 path) + the unprofiled 1.6 contrast.
- `FailureRouterSuite` — scalafmt log + Format-autofix profile → `RunLocalCommand(sbt scalafmtAll)`,
  `source=rules`; `adapt.autofix=false` degrades to `DriverFixup`.
- `OrchestratorLocalGateSuite` — the §8.3 pre-PR gate (the Mode-B path).
- `ProfileReplayInvarianceSuite` R1/R2 — `profile.*` actions are inert at replay; the FSM never
  reads a profile.

Run them: `sbt "forge-app/testOnly *OrchestratorCiRoutingSuite *FailureRouterSuite
*OrchestratorLocalGateSuite" "forge-core/testOnly *ProfileReplayInvarianceSuite"`
(all green 2026-06-03).

**What only the live run can surface:** the real `gh run view <runId> --log-failed` wire shape on
a current `gh`, the `CheckResult.runId` extraction off a live failing-check `detailsUrl`, the
amend+push re-triggering CI under real branch protection, and the end-to-end `attempts`-stays-0
accounting against a real Actions run (all the subprocess-lifecycle / wire-shape risks the
CLAUDE.md discipline flags for fakes).

---

## 2. Prerequisites & setup

1. **CLIs on PATH:** `claude` (≥ 2.1.150), `codex`, `gh` (authed against the `szork` remote).
2. **A fresh `szork` checkout** with `main` green. The original `extract-media-network-config`
   (#14/#15) is already merged, so pick a **fresh, equivalent format-gated feature** — a small
   refactor in `src/main/scala` that the driver will plausibly leave scalafmt-non-conformant.
   Suggested: *"extract the duplicated retry/back-off literals in the `media` package into a
   shared `MediaRetryConfig`"* (same shape as the merged feature, untouched code).
3. **Pre-empt dogfood-#2 finding #2** (preflight clean-worktree check vs `.forge/`): add `.forge/`
   to the checkout's exclude before anything writes there —
   `printf '.forge/\n' >> .git/info/exclude`.
4. **Drop the hand-authored profile** at `<szork>/.forge/profile.json` (Forge loads it
   automatically at run start via `ProfileStore.load` → `profile.snapshot`; no `forge profile`
   LLM call is needed). Content — copy verbatim from [`profile.szork.json`](profile.szork.json)
   in this directory (identical to the committed
   `modules/forge-core/src/test/resources/profiles/szork.json` fixture):

   ```json
   {
     "schemaVersion": 1,
     "buildTool": "sbt",
     "commands": [
       { "kind": "format", "argv": ["sbt", "scalafmtAll"],     "determinism": "deterministic", "required": true,  "autofix": true  },
       { "kind": "build",  "argv": ["sbt", "compile"],          "determinism": "deterministic", "required": true,  "autofix": false },
       { "kind": "test",   "argv": ["sbt", "test"],             "determinism": "heuristic",     "required": true,  "autofix": false }
     ],
     "commitIdentity": { "name": "forge[bot]", "email": "forge@users.noreply.github.com" },
     "workflow": { "reviewRequired": true, "ciRequiredChecks": ["backend", "frontend"], "branchModel": "trunk_based", "mergeStrategy": "squash" }
   }
   ```

5. **Forge config — set `adapt.localGate = false` for Mode A** (the only change from defaults; keep
   `enabled`/`autofix`/`workflowGate` at their `true` defaults so the §8.2 router and its
   `RunLocalCommand` are live). In the forge config's `adapt` block:

   ```json
   "adapt": { "enabled": true, "localGate": false, "autofix": true }
   ```

   > `workflowGate` stays `true` but is inert here — the szork profile has `reviewRequired = true`,
   > so review is **not** skipped (the run still spends one reviewer call at `PieceAwaitingReview`,
   > exactly as 1.6/1.8; that is orthogonal to T5).

---

## 3. Drive the run (Mode A — §8.2 CI-fail routing)

```bash
cd <szork>
forge new   <feature>          # e.g. extract-media-retry-config
forge spec  <feature>          # interactive; /done when the spec is settled
forge run   <feature>
```

Expected lifecycle (the dogfood-#2 shape, but the fix-up rounds collapse to a local autofix):

```
Drafting → DesignReviewing(1, approve) → DesignAwaitingMerge → [merge design PR] → DesignReady
→ PieceImplementing → PieceAwaitingCi
   → §8 CI gate: backend "Check formatting" FAILS (scalafmt)
   → §8.2 router: fetch gh run view --log-failed → classify deterministic_fix
     → RunLocalCommand(sbt scalafmtAll) → amend + push  (NO PieceCiFailed, attempts UNCHANGED)
   → CI re-runs → green
→ PieceAwaitingReview → (reviewer approve) → PieceAwaitingMerge → [merge piece PR]
→ Refining → FeatureDone
```

---

## 4. Mode B (optional, production default) — confirm the shift-left is even cheaper

Re-run the same feature with `adapt.localGate = true` (the default — drop the override). Expect the
piece commit to be **format-clean before CI** (`profile.local_gate` action logged pre-PR), so
`backend "Check formatting"` **passes on the first try** and the §8.2 router never fires. This
confirms the production default avoids even the one CI round-trip Mode A pays.

---

## 5. Verification & sign-off (fill in after the run)

**Pass criteria (Mode A):**

- [ ] `.forge/log/<feature>.jsonl` contains `profile.snapshot` at feature start.
- [ ] A `profile.failure_classified` action with `gate: "ci"`, `kind: "deterministic_fix"`,
      `route: "RunLocalCommand"`, `source: "rules"` (the rules classifier pins scalafmt for free —
      no LLM tail).
- [ ] **No `PieceCiFailed` transition and `attempts` stays 0** across the CI failure (the whole
      point — the failure was remedied without a fix-up driver round).
- [ ] `forge stats <feature>` prints the note: *"N fix-up(s) avoided — a CI failure was remedied by
      the repo's own …"* with N ≥ 1.
- [ ] The piece PR merged with exactly one auto-healed CI round-trip; the amend commit is the
      `sbt scalafmtAll` output (authored per the profile's `commitIdentity`, ambient git identity
      is the known follow-up — design-3.0 D9 note).

**Capture:** copy the feature's `.forge/log/<feature>.jsonl` + `forge stats` output into
`docs/dogfood/t5-run/<feature>/`, then tick Task 3.1.2's box in `design-3.0.md`, add a status-log
entry, and note the dollars/minutes saved vs dogfood-#2's `$1.78 / 12 min / 2 rounds`.

**New findings:** log anything the live run surfaces (wire-shape drift, preflight friction, etc.)
as a dated row here and, if it's a Forge bug, in `design-3.0.md` §4.

---

## 6. Cross-references

- [`design-3.0.md`](../design-3.0.md) Task 3.1.2 (the box this closes) + §2 order-of-work "T5".
- [`extract-media-network-config.md`](extract-media-network-config.md) — dogfood #2, the original
  run whose `$1.78 / 12 min` fix-up this collapses (findings #3/#4 are the evidence).
- [`../slice-3/fixtures/gh-run-view-log-failed.scalafmt.txt`](../slice-3/fixtures/gh-run-view-log-failed.scalafmt.txt)
  — the real scalafmt failing-check log the classifier keys on.
</content>
</invoke>
