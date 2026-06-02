# design-3.0 — Slice 3.0/3.1 implementation plan (Repo Adaptation)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation), the
> spine/senses decision in [`design-rationale.md`](design-rationale.md) **A5**, and
> the implementation contract [`forge-design-1.7.md`](forge-design-1.7.md) (§3 spine/senses,
> §6 domain types, §7 sensor roles, §8 classified routing, §11 lifecycle seams, §19 actions).
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap section gets a `design-<slice-id>.md` companion with a Task
> breakdown (checkbox items), an exit criterion, a status log, and a carry-forward list.
> Tick items as they land — but not during a review round; tick the roadmap bullet only
> at slice close after a whole-section review.
>
> **Filename note:** this plan covers roadmap sub-slices **3.0** (RepoProfile model +
> store + hash) and **3.1** (FailureClassifier + deterministic routing) together, because
> 3.1 is the highest-ROI first runnable and shares all of 3.0's types. Later sub-slices
> (RepoProfiler role population, ConventionLearner) get their own plans if they grow.
>
> **Status:** 🟡 open — 2026-06-02. Tier-1 types + deterministic routing **landed ahead of
> this plan via the Phase-3 spike** (commit `16396d2`, `forge-core` 420/420 green); the
> remaining Tier-1 work is wiring the live CI-fail routing into the orchestrator and
> re-running the dogfood-#2 case to measure the collapse on a real run.

---

## 0. Exit criterion for Slice 3.0/3.1

Slice 3.0/3.1 is done when **a re-run of the dogfood-#2 formatter case routes the
scalafmt CI failure to a local `sbt scalafmtAll` — no driver fix-up turn, no LLM
call, no `maxFixupRounds` consumed — and `forge stats` records it as a fix-up round
avoided.** The \$1.78 / 12-min / 2-round waste (dogfood #2 findings #3/#4) collapses
to one ~2s local step on a live run, not just in the spike's unit proof.

Concretely:

1. `RepoProfile` model + `ProfileStore` + `contentHash`, with committed `szork`/`forge`
   fixtures proving the model expresses both real repos (**determinism before any LLM**).
2. A deterministic `RuleBasedFailureClassifier` + pure `FailureRouting.route` that the
   spike exercises against the real scalafmt / rate-limit / compile-error logs.
3. `gh run view --log-failed` plumbed into the failure edge so the classifier — and any
   `DriverFixup` prompt — sees the real `scalafmt: 1 file must be formatted`, not the
   `gh pr checks` summary (dogfood #4).
4. The §8.2 classified router wired into §11.5 so a `RunLocalCommand` route amends + pushes
   + re-polls **without** an `attempts` increment, and a `RateLimit` backs off instead of
   hard-NHI (dogfood #5).
5. `profile.snapshot` + `profile.failure_classified` (§19) recorded so the run is replayable
   against the profile/classification-as-of-then, and `forge stats` can fold "fix-ups avoided".
6. A live `extract-media-network-config` (or equivalent format-gated) re-run on `szork`
   showing the collapse end-to-end.

**Deliberately deferred inside this slice (see §4):** the LLM `RepoProfiler` that *populates*
`.forge/profile.json` (Tier 2 — profiles are hand-authored fixtures until then); the LLM
`classifyFailure` consulted on `Unknown` (Tier 2 — rules cover every dogfood case so far);
`ConventionLearner` (Tier 3 — the least-developed sensor).

Tiering: **Tier 1** (Tasks 3.0.1, 3.1.1, 3.1.2) is the gating deliverable — it is the
dogfood-#2 collapse, live and measured. **Tier 2** (Tasks 3.0.2, 3.0.3, 3.1.3, 3.1.4) makes
the layer self-sufficient (profiles produced by a sensor, the local shift-left gate, the LLM
tail). **Tier 3** (Task 3.2) is the learning loop.

---

## 1. Task breakdown

### Tier 1 — the dogfood-#2 collapse, live

### Task 3.1.1 — RepoProfile model + deterministic classifier + routing  ⬅ **first runnable slice** ✅ 2026-06-02 (spike `16396d2`)

The thin runnable slice that grounds the whole contract — built before any LLM, exercised
against the real dogfood-#2/#5 strings (the "thin runnable slice over thick design pass"
discipline).

Landed (`modules/forge-core/src/main/scala/io/forge/core/profile/`):

- `RepoProfile.scala` — `RepoProfile` / `WorkflowProfile` / `RepoCommand` (commands tiered by
  `Determinism` + `required`/`autofix`), `CommitIdentity`, `MergeStrategy`, `BranchModel`;
  `contentHash` (stable 16-hex SHA-256 over canonical JSON).
- `FailureClassifier.scala` — `FailureKind` / `Classification`, the `FailureClassifier` sensor
  trait, and `RuleBasedFailureClassifier` (rules over the real markers).
- `FailureRouting.scala` — `FixupRoute` + the pure, total `FailureRouting.route`.
- `RepoProfileSpikeSuite` (15 tests) — the load-bearing proof: real scalafmt log →
  `RunLocalCommand(sbt scalafmtAll)`; compile error → `DriverFixup(fullLog)`; rate-limit →
  `BackOff`; hash stability; both fixtures round-trip.

### Task 3.0.1 — ProfileStore + ForgePaths + profile.snapshot + fixtures  ✅ 2026-06-02 (spike `16396d2`)

Landed:

- `ProfileStore.scala` — `ProfileStore` + atomic `FileProfileStore` over `.forge/profile.json`
  (mirrors `FileStateCache`; decode-failure propagates — committed source of truth, not a cache).
- `ForgePaths.profileFile` (the one sanctioned `.forge/profile.json` literal; literal-sweep green).
- `ProfileSnapshot.scala` — the §19 `profile.snapshot` action draft (hash + schemaVersion).
- `src/test/resources/profiles/{szork,forge}.json` — committed fixtures; the suite proves the
  model expresses both real repos.

### Task 3.1.2 — live CI-fail routing wired into the orchestrator + dogfood re-run  ⬅ **gating** [ ]

The step that turns the spike's unit proof into a real run. Risk: the §11.5 failed-check edge,
`attempts` accounting, and the gh log fetch are all live-CLI / FSM-touching — exercise with a
real run, not just fakes (the `gh` wire-shape + subprocess-lifecycle discipline applies).

- [ ] Plumb `gh run view <runId> --log-failed` into the §11.5 failed-check edge (capture a real
      fixture first — `docs/slice-3/fixtures/` — before writing the parse).
- [ ] Insert §8.2 classified routing in place of the blind "any required check failed →
      `attempts += 1` → fix-up": `RunLocalCommand` amends + pushes + re-polls with **no**
      `attempts` increment; `DriverFixup` writes the full-log `<p>.failures.md` and increments;
      `BackOff`/`Retry`/`Escalate` per §8.2.
- [ ] Emit `profile.failure_classified` (§19) before acting; replay reuses the recorded route.
- [ ] Re-run `extract-media-network-config` (or an equivalent format-gated feature) on `szork`
      with a hand-authored `.forge/profile.json`; confirm the scalafmt CI failure routes to a
      local `scalafmtAll`, `attempts` stays 0, and `forge stats` shows the avoided round.

**Exit:** the §0 criterion. Until this lands, Phase 3 is types-without-teeth.

### Tier 2 — self-sufficiency

### Task 3.0.2 — profile load + snapshot at feature start (§11.0)  [ ]

- [ ] At orchestrator startup (post-lock, pre-first-transition): `ProfileStore.load()`; on
      `Some`, append `profile.snapshot` and thread the `RepoProfile` as a read-only input to the
      router; on `None`, run unprofiled (1.6 behaviour).
- [ ] Assert `Fsm.transition` never reads `ProfileStore` (profile reaches it only as input) —
      the replayability invariant. Property/round-trip test.

### Task 3.0.3 — RepoProfiler LLM role (forge-agents)  [ ]

- [ ] `Connector.profileRepo` + `~/.forge/schemas/repo-profile.json` Native schema + a
      `ReviewDecoders`-style decoder, routed reviewer-side via `Role.pairFor` (§7.11). Capture a
      real Claude/Codex structured-output sample before pinning the schema.
- [ ] `forge profile <repo>` writes `.forge/profile.json` (human-reviewable committed diff).
      Validate its output against the hand-authored `szork`/`forge` fixtures.

### Task 3.1.3 — local format/build gate, pre-PR (§8.3)  [ ]

- [ ] In §11.4 step 6, after commit / before push: run the profile's `required` deterministic
      gates locally; `Format` autofix amends the commit (kills dogfood #3 at the source, zero
      round-trip); a local `Build` `CodeFix` routes to a pre-PR driver fix-up (no `attempts`
      until a PR-side failure).
- [ ] Gated by `adapt.localGate`; never runs `Heuristic` commands.

### Task 3.1.4 — LLM classifyFailure on Unknown  [ ]

- [ ] `Connector.classifyFailure` consulted **only** when `RuleBasedFailureClassifier` returns
      low-confidence `Unknown` (§7.11 cost lever). Record `source: "llm"`.
- [ ] `adapt.llmClassifierOnUnknown` gate; wall-clock-capped via the `ReviewerCall` boundary.

### Tier 3 — the learning loop

### Task 3.2 — ConventionLearner at FeatureDone (§11.7)  [ ]

- [ ] `Connector.learnConventions` invoked out-of-band on `FeatureDone` (advisory, never gates):
      mine failure→remedy + recurring reviewer comments → `RepoProfile` deltas (via
      `ProfileStore.save`) + a proposed CLAUDE.md PR. **No autonomous doc mutation** — proposes only.

---

## 2. Order of work

3.1.1 ✅ → 3.0.1 ✅ → **3.1.2 (gating, next)** → 3.0.2 → {3.0.3, 3.1.3, 3.1.4 in any order} → 3.2.
Tier-1 closes the slice's exit criterion; Tier 2/3 can land incrementally behind it.

---

## 3. Status log

- **2026-06-02 — Tasks 3.1.1 + 3.0.1 landed via the Phase-3 spike (commit `16396d2`) — Tier-1
  types complete.** Built the deterministic `io.forge.core.profile` package runnable-first and
  exercised it against the real dogfood-#2/#5 logs before opening this plan or the 1.7 contract.
  `RepoProfileSpikeSuite` (15 tests) proves the load-bearing route — the real scalafmt CI log
  collapses to `RunLocalCommand(sbt scalafmtAll)` — plus compile-error → `DriverFixup(fullLog)`,
  rate-limit → `BackOff`, hash stability, and both `szork`/`forge` fixtures round-tripping.
  `forge-core` 420/420 green; `scalafmtCheckAll` + `ForgePaths` literal-sweep clean; full build
  compiles with zero warnings. The spike's purpose was to make the 1.7 contract concrete from
  exercised types rather than prose — done; `forge-design-1.7.md` cites these types directly.
  One incident worth recording: `scalafmt` 3.8.3's `RedundantBraces` rewrite crashed
  (`next on empty iterator`) on a doc-comment backtick span containing parens/`--`; reworded the
  comment (the code structure was a red herring). Captured as a reference memory.

---

## 4. Carry-forward / decisions opened

### T1 — profiles are hand-authored fixtures until Task 3.0.3 — open

Tier 1 ships the `RepoProfile` *model* and consumes a committed `.forge/profile.json`, but the
LLM `RepoProfiler` that *produces* one is Tier 2. Until 3.0.3, the dogfood re-run (3.1.2) uses a
hand-authored profile (the `szork` fixture is exactly such a file). This is deliberate — A5's
"determinism before any LLM": prove the routing collapse with a known-good profile before trusting
a sensor to generate one.

### T2 — `profile.snapshot` records hash + schemaVersion only, not the full profile — open (1.7 §19)

The snapshot fingerprints the profile by `contentHash` and relies on the committed `.forge/profile.json`
(in git history) as the content. Alternative: embed the full profile JSON per run for self-contained
replay without git. Hash-only is leaner and matches A5's wording ("snapshotted/hashed"); revisit only
if a replay needs the profile content when the committed file has since changed and the old commit is
unreachable. Flagged in `forge-design-1.7.md` §19.

### T3 — sensor placement: core vs forge-agents — resolved (1.7 §3.1 / §7.11)

The spike co-located everything in `forge-core` so the runnable proof needed no cross-module wiring.
1.7 resolves the final split: the deterministic types + `RuleBasedFailureClassifier` + `FailureRouting`
stay in `forge-core` (the spine consumes them); the LLM sensors become `forge-agents` `Connector`
methods invoked reviewer-side. Tasks 3.0.3 / 3.1.4 / 3.2 implement the `forge-agents` side; no move of
the spiked core types is needed.

---

## 5. Cross-references

- [`roadmap.md`](roadmap.md) §4 — Phase 3 plan; tick its bullet only at slice close.
- [`forge-design-1.7.md`](forge-design-1.7.md) — the contract these Tasks implement (§3/§6/§7/§8/§11/§18/§19).
- [`design-rationale.md`](design-rationale.md) **A5** — spine/senses + replayability rationale.
- [`dogfood/extract-media-network-config.md`](dogfood/extract-media-network-config.md) — findings #3/#4/#5, the evidence and the re-run target.
- Spike: `modules/forge-core/src/main/scala/io/forge/core/profile/` + `RepoProfileSpikeSuite` (commit `16396d2`).
