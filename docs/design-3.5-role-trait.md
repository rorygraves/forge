# design-3.5-role-trait — Slice 3.5 implementation plan (Role-trait refactor)

> **Maps to:** [`roadmap.md`](roadmap.md) §4 (Phase 3 — Repo Adaptation), sub-slice
> **3.5 — Role-trait refactor** and §4.2 ("Generalise `Mode` … into role traits —
> `Driver`, `Reviewer`, and a base `Agent` for future roles; the two concrete modes
> become *configurations* of those traits, not enum cases"); the spine/senses
> direction in [`design-rationale.md`](design-rationale.md) **A5** (the sensors
> RepoProfiler / FailureClassifier / ConventionLearner are the first concrete
> third+ roles, which makes this refactor *demand-driven*, not speculative).
>
> **Filename note:** the legacy [`design-3.5.md`](design-3.5.md) is the *Phase-2
> D3 driver-respawn-avoidance* slice (closed 2026-06-01) — a different slice that
> happens to share the `3.5` number. This file is the **roadmap §4 sub-slice 3.5**
> (role-trait refactor); the `-role-trait` suffix dodges the collision, exactly as
> `design-2.1-tui.md` dodged `design-2.1.md`.
>
> **Convention** ([`AGENTS.md`](../AGENTS.md) §"Per-section implementation plans"):
> every in-flight roadmap sub-slice gets a `design-<slice-id>.md` companion with a
> Task breakdown (checkbox items), an exit criterion, a status log, and a
> carry-forward list. Tick items as they land — but not during a review round; tick
> the roadmap §4 bullet only at the Phase-3 slice close after a whole-section review.
>
> **Status:** ✅ closed — 2026-06-04. All three tasks landed: 3.5.1 (`Agent` base + open role
> hierarchy), 3.5.2 (`Mode`→`RolePairing` resolved once; D1 reconciled to same-CLI), 3.5.3
> (contract → [`forge-design-1.10.md`](forge-design-1.10.md) §7; C1 carried to Phase 4/5; roadmap
> §4 3.5 bullet flipped). This was the last remaining Phase-3 sub-slice (3.0/3.1/3.2/3.3 closed;
> 3.4 ConventionLearner landed via design-3.0 Task 3.2).

---

## 0. Exit criterion for Slice 3.5

Slice 3.5 is done when **`Mode` is no longer a behaviour-dispatching 2-case enum that
call sites pattern-match on; instead a base `Agent` trait + `Driver` / `Reviewer` role
traits express the participants, the two concrete pairings (`ClaudeDriver` /
`CodexDriver`) are *configurations* resolved at one seam, and a third role is
expressible without touching the FSM, the orchestrator, or any persistence wire form.**

Concretely:

1. A base `Agent` trait exists (a named participant backed by a `Connector`); `Driver`
   and `Reviewer` are role traits extending it; the hierarchy is *open* — a third role
   (the Phase-3 sensors / a future PR-watcher / knowledge-base consultant) can be added
   without editing `Mode` or any caller. Proven by a test that constructs a third role.
2. The four `match mode` sites that exist today
   ([`Role.pairFor`](../modules/forge-agents/src/main/scala/io/forge/agents/Role.scala),
   [`ConnectorFactory.build`](../modules/forge-app/src/main/scala/io/forge/app/orchestrator/ConnectorFactory.scala),
   [`OrchestratorBuilder.retryBudgets`](../modules/forge-app/src/main/scala/io/forge/app/orchestrator/OrchestratorBuilder.scala),
   and [`ProfileCommand.buildReviewerCall`](../modules/forge-app/src/main/scala/io/forge/app/command/ProfileCommand.scala))
   resolve through **one** pairing/configuration value, not four independent matches —
   so adding a pairing touches one site, not four.
3. The **persisted wire form is byte-identical**: `manifest.json` still serialises
   `"claude-driver"` / `"codex-driver"`, `FsmEvent.UserCommand.New(mode)` and
   `ForgeConfig.mode` are unchanged on disk, and a cold `RebuildState` over an existing
   feature reads identically (the back-compat invariant — see **R1** below).
4. The cross-CLI (`Role.pairFor` → `Driver(claude), Reviewer(codex)`) vs same-CLI
   (`ConnectorFactory` → one Claude CLI driving *and* reviewing on `haiku`) inconsistency
   is **reconciled** into one coherent role→connector story, documented, with both the
   `forge run` and `forge profile` behaviours preserved exactly (decision **D1**).
5. The whole build stays green and the §6.1 replay invariant holds
   (`ProfileReplayInvarianceSuite` R1/R2): the `io.forge.core.fsm` package still names no
   role/profile type, and `Fsm.transition` is unchanged.

**Out of scope (carry-forward, not this slice):** splitting the `Connector` god-trait
into per-role capability traits (`DriverConnector` / `ReviewerConnector` / `SensorConnector`)
— that sweeps every caller and the §4.2 note explicitly scopes this as "a refactor of
`Mode`'s implementation and the connector factories, **not** a sweep through every
caller." See **C1**.

---

## 1. Current-state map (grounded, 2026-06-04)

The seam Phase 1 left (the `Role` indirection + the "no `match m: Mode` outside `Mode`
and connector construction" smell test) means the surface is small and well-isolated:

**The two ADTs**
- `enum Mode { ClaudeDriver, CodexDriver }` —
  [`Mode.scala`](../modules/forge-core/src/main/scala/io/forge/core/Mode.scala). Carries a
  `ReadWriter[Mode]` over the wire strings `"claude-driver"` / `"codex-driver"`
  (`asString` / `fromString`). **Persisted** in `manifest.json` (`Manifest.mode`),
  embedded in `FsmEvent.UserCommand.New(mode)`, defaulted in `ForgeConfig.mode`.
- `sealed trait Role { def connector: Connector }` with `Role.Driver` / `Role.Reviewer`
  + `Role.pairFor(mode, claude, codex): (Driver, Reviewer)` —
  [`Role.scala`](../modules/forge-agents/src/main/scala/io/forge/agents/Role.scala).

**The four `match mode` sites beyond `Mode` itself**
1. `Role.pairFor` — **cross-CLI**: `ClaudeDriver → (Driver(claude), Reviewer(codex))`.
   *Only caller:* `ProfileCommand`.
2. `ConnectorFactory.build` — **same-CLI**: `ClaudeDriver →` one `ClaudeConnector` that
   both drives (default model) *and* reviews (`reviewerModel = haiku`). This is the real
   `forge run` path.
3. `OrchestratorBuilder.retryBudgets` — picks `config.claude.*` vs `config.codex.*` retry
   budgets.
4. `ProfileCommand.buildReviewerCall` — builds *both* connectors, then `Role.pairFor`
   picks the non-driver as the reviewer-side for `forge profile`.

**The `Connector` god-trait**
([`Connector.scala`](../modules/forge-agents/src/main/scala/io/forge/agents/Connector.scala))
mixes driver methods (`runStreamingSpec` / `resumeStreamingSpec` /
`runHeadlessImplementation` / `runFixup` / `resumeHeadlessDriver`), reviewer one-shots
(`reviewDesign` / `reviewPr` / `refine`), Phase-3 sensor one-shots (`profileRepo` /
`classifyFailure` / `learnConventions`), and telemetry (`costFrom` / `schemaMechanism` /
`questionMechanism`). The orchestrator holds a raw `Connector` (via `SideEffects` /
`RealReviewerCall`), **not** a `Role` — so `Role` is barely load-bearing at runtime today;
only `ProfileCommand` routes through it.

**The inconsistency to reconcile (D1).** `pairFor` says "the *other* CLI reviews"; the
production `forge run` wiring says "the *same* CLI reviews, on a cheaper model." Both
ship. The Mode docstring ("Codex reviews") matches `pairFor`, not the running system.
The role-trait abstraction must pick one coherent story; this slice reconciles it.

---

## 2. Task breakdown

### Tier 1 — the de-risking thin runnable

### Task 3.5.1 — `Agent` base trait + open role hierarchy  ⬅ **first runnable slice** ✅ 2026-06-04

The cheapest proof of the §4.2 "base `Agent` for future roles" claim, additive and
behaviour-free, so the contract is concrete before any call site moves.

- [x] In `forge-agents`, introduced `trait Agent { def connector: Connector; def role: String }`
      (the named-participant base, deliberately *not* sealed). Reshaped `Role` to `sealed trait
      Role extends Agent`; `Driver` / `Reviewer` keep `def connector` and gain `role` =
      `"driver"` / `"reviewer"`. No call-site change — `Role.Driver(c).connector` / `Role.pairFor`
      keep their exact shape (`ProfileCommand` unchanged).
- [x] Proved the hierarchy is **open**: added `Role.Sensor(connector)` (`role = "sensor"`) — the
      reviewer-side one-shot surface used by `profileRepo` / `classifyFailure` / `learnConventions`
      — plus `RoleSuite` tests that construct it and read `.connector` / `.role`. Adding it needed
      **no** `Mode` or `pairFor` edit. Not yet wired into the orchestrator (that is **C1**).
- [x] `forge-agents` 238/238 green (RoleSuite +2); full `sbt compile` clean (`-Xfatal-warnings`,
      no warnings); `scalafmtCheckAll` clean.

### Tier 2 — `Mode` becomes a configuration, resolved once

### Task 3.5.2 — single role-pairing resolution (kill the scattered `match mode`)  ✅ 2026-06-04

- [x] Introduced the one resolution seam — `RolePairing(driver: Cli, reviewer: Cli)` +
      `enum Cli { Claude, Codex }` in `forge-core` next to `Mode`
      ([`RolePairing.scala`](../modules/forge-core/src/main/scala/io/forge/core/RolePairing.scala)),
      with `RolePairing.of(mode)` the **single** `Mode → pairing` resolver. `Mode` stays the
      serialised wire token (R1 — untouched enum + `ReadWriter`); it no longer carries
      behaviour dispatch.
- [x] Re-pointed every former `match mode` site at the resolved pairing / its `Cli`:
      `ConnectorFactory.build` now takes a `Cli` (the sanctioned connector-construction seam);
      `OrchestratorBuilder` resolves `RolePairing.of(mode)` once and passes `pairing.driver`
      to the factory + `pairing.driver` to `retryBudgets` (which matches `Cli`, not `Mode`);
      `SpecRepl` resolves `RolePairing.of(manifest.mode).driver`. The smell test tightens to
      "`match m: Mode` only in `Mode` itself + `RolePairing.of`."
- [x] **Reconciled D1 — same-CLI** (operator-confirmed 2026-06-04, matches the shipping
      `ConnectorFactory` + C15 config): each `Mode` resolves to one `Cli` that both drives
      and reviews. `Role.pairFor` (the stale cross-CLI shape, only `ProfileCommand` used it)
      is **removed** — `Role.scala` no longer imports `Mode`; `ProfileCommand.buildReviewerCall`
      now builds the single connector named by `RolePairing.of(config.mode).reviewer` instead
      of constructing both CLIs to pick the non-driver. The stale "cross-model review is a
      core property" `RoleSuite` assertion retired with it.
- [x] Tests: `RolePairingSuite` (+4, both modes → same-CLI pairing, resolver total over
      `Mode`); `ConnectorFactorySuite` re-pointed to `Cli`; `RoleSuite` −3 (`pairFor` cases
      dropped, openness test kept). Persistence round-trip unchanged
      (`ModeSuite` / `ManifestSuite` / `FsmEventSuite` green). `forge-core` + `forge-agents` +
      `forge-app` all green (forge-app 423/423); `sbt compile` clean; `scalafmtCheckAll` clean.

### Tier 3 — close-out

### Task 3.5.3 — contract revision + roadmap tick  ✅ 2026-06-04

- [x] Opened [`forge-design-1.10.md`](forge-design-1.10.md) (standalone-by-freeze over 1.9,
      continuing the 1.7/1.8/1.9 exception) restating **§7** only — the intro framing ("`Mode`
      is a *configuration* of `Driver` / `Reviewer` / `Sensor` roles, resolved once via
      `RolePairing.of`, not enum-case dispatch"; cross-model review reconciled to **same-CLI**),
      the §7.1 orchestrator wiring (resolve once → build one connector per `Cli`), and the §7.11
      sensor routing (reviewer-side connector named by `RolePairing.reviewer`, no `Role.pairFor`).
      Trait *signatures* (1.6 §7.1), §7.2–§7.10, and the wire form are explicitly frozen. 1.9 left
      intact (the focused-revision chain is not stubbed; only pre-1.6 full docs were).
- [x] Placed C1 (Connector-trait split) durably in [`roadmap.md`](roadmap.md) §4.2 as an explicit
      Phase-4/5 carry-forward (worthwhile when the daemon / reactive review add the 4th/5th role).
- [x] Whole-section coherence pass: fixed the two stale cross-CLI `Mode` enum docstrings ("Codex
      reviews" / "Claude reviews" → same-CLI, both drive + review). Verified no other stale
      cross-CLI prose in main src (`RolePairing` / `ProfileCommand` references correctly *describe*
      the retired shape). Flipped the roadmap §4 sub-slice 3.5 bullet to ✅ closed.

---

## 3. Order of work

3.5.1 ✅ (thin runnable — `Agent` base, prove openness) → 3.5.2 (`Mode`→pairing resolver,
reconcile D1) → 3.5.3 (contract + close-out). Tier 1 alone proves the §4.2 "base Agent"
claim; Tier 2 delivers the "configurations not enum cases" half; Tier 3 closes it.

---

## 4. Status log

- **2026-06-04 — Task 3.5.3 landed; Slice 3.5 ✅ closed.** Opened
  [`forge-design-1.10.md`](forge-design-1.10.md) (standalone-by-freeze over 1.9) restating §7 only:
  the intro framing (modes are *configurations* of `Driver`/`Reviewer`/`Sensor` roles resolved once
  via `RolePairing.of`, **same-CLI** review per D1), the §7.1 orchestrator wiring (resolve once →
  `ConnectorFactory.build` one connector per `Cli`), and the §7.11 sensor routing (reviewer-side
  connector named by `RolePairing.reviewer`, `Role.pairFor` gone). Trait signatures + wire form
  frozen. Placed **C1** (Connector-trait split) into roadmap §4.2 as a Phase-4/5 carry-forward.
  Coherence pass fixed the two stale cross-CLI `Mode` docstrings (→ same-CLI) and confirmed no other
  stale cross-CLI prose in main src. Flipped the roadmap §4 sub-slice 3.5 bullet to ✅ closed. **D1
  resolved (same-CLI); D2's reading held** (enum `Mode` kept purely as the serialised wire token —
  no migration, the wire form is byte-identical, `ProfileReplayInvarianceSuite` R1/R2 green).
- **2026-06-04 — Task 3.5.2 landed (`Mode` becomes a configuration, resolved once).** Introduced
  `RolePairing(driver, reviewer)` + `enum Cli` in `forge-core` with `RolePairing.of(mode)` as the
  single `Mode → pairing` resolver, and re-pointed all former `match mode` sites
  (`ConnectorFactory.build` → `Cli`; `OrchestratorBuilder` resolves once then reads `pairing.driver`
  for both the connector and the retry block; `SpecRepl` → `pairing.driver`;
  `ProfileCommand` → `pairing.reviewer`). **Reconciled D1 to same-CLI** (operator-confirmed; it is
  what `ConnectorFactory` already ships and what C15 validated): `Role.pairFor`'s cross-CLI shape and
  its only caller's double-build are removed, and `Role.scala` no longer references `Mode`. `Mode`'s
  wire form is byte-unchanged (R1): `ModeSuite` / `ManifestSuite` / `FsmEventSuite` green. Added
  `RolePairingSuite` (+4); `RoleSuite` drops the 3 `pairFor`/cross-model tests; `ConnectorFactorySuite`
  re-pointed to `Cli`. `forge-core` + `forge-agents` + `forge-app` green (forge-app 423/423); `sbt
  compile` + `scalafmtCheckAll` clean. The Task 3.5.2 box is ticked; the roadmap §4 3.5 bullet stays
  **unticked** until Task 3.5.3 (contract revision + whole-section review). D1 is now **resolved**;
  D2's reading (keep `enum Mode` as wire token) held — confirm at the Task 3.5.3 review.
- **2026-06-04 — Slice opened + Task 3.5.1 landed (the thin runnable).** Opened this plan as the
  last Phase-3 sub-slice (3.0/3.1/3.2/3.3 closed; 3.4 ConventionLearner landed via design-3.0
  Task 3.2). Grounded the plan in a fresh current-state map (§1): the seam is small — one `Mode`
  enum, the `Role` indirection, and exactly four `match mode` sites (`Role.pairFor`,
  `ConnectorFactory.build`, `OrchestratorBuilder.retryBudgets`, `ProfileCommand`). Surfaced the
  load-bearing **D1** inconsistency (cross-CLI `pairFor` vs same-CLI `ConnectorFactory`) for the
  Task 3.5.2 reconciliation. Task 3.5.1 introduced the `Agent` base trait, reshaped `Role` to
  extend it, and added `Role.Sensor` to prove the family is open without a `Mode` edit — additive,
  behaviour-free, `RoleSuite` +2 (the existing cross-model `pairFor` invariant test untouched and
  green). `forge-agents` 238/238; full `sbt compile` clean; `scalafmtCheckAll` clean. The Task
  3.5.1 box is ticked; the roadmap §4 3.5 bullet stays **unticked** until Tier 2/3 + the
  whole-section review.

---

## 5. Decisions / carry-forward

### D1 — cross-CLI (`pairFor`) vs same-CLI (`ConnectorFactory`) reconciliation — ✅ resolved 2026-06-04 (same-CLI)
`Role.pairFor` modelled the *other* CLI as reviewer; the production `forge run` wiring uses
the *same* CLI on a cheaper model. **Resolved to same-CLI** in Task 3.5.2 (operator-confirmed
2026-06-04): it is what actually ships and what C15 validated (claude driver + claude/`haiku`
reviewer; codex driver + codex reviewer), so `pairFor`'s cross-CLI shape was the stale one.
`RolePairing.of` now resolves each `Mode` to a same-`Cli` driver/reviewer pair; `pairFor` is
removed; `forge profile` builds the single connector named by `RolePairing.reviewer` (the
mode's own connector, used for a reviewer one-shot). Observable change: `forge profile` under
`ClaudeDriver` now profiles with Claude (was Codex). The §7.1/§7.11 contract wording landed in
[`forge-design-1.10.md`](forge-design-1.10.md) §7 (Task 3.5.3).

### D2 — `Mode` stays the wire token, does not become pure trait config — ✅ confirmed 2026-06-04 (Task 3.5.3 review)
§4.2 says the modes "become configurations of those traits, **not enum cases**." Read
literally that kills the enum; but `Mode` is the persisted wire form
(`"claude-driver"` in every `manifest.json` on disk) and the natural config token. This
plan keeps `enum Mode` purely as the **serialised selector** and moves the *behaviour*
(connector recipe, retry block, role pairing) into a resolved `RolePairing` configuration
— satisfying "configurations, not enum-case dispatch" at the abstraction level while
preserving the wire form (criterion 3). **Confirmed at the Task 3.5.3 review**: the byte-identical
wire form (criterion 3) and an intact `ProfileReplayInvarianceSuite` R1/R2 make a full enum
removal + migration all cost and no benefit; the enum-as-wire-token reading stands.

### C1 — `Connector` god-trait split is out of scope — carry-forward
Splitting `Connector` into `DriverConnector` / `ReviewerConnector` / `SensorConnector`
capability traits would sweep every caller, which §4.2 explicitly excludes from this
slice. The sensors already live as one-shots on the single trait and work. Place durably
at close-out: most valuable when Phase 4's daemon / Phase 5's reactive-review add the 4th
/ 5th concrete role, when a per-role capability surface stops being speculative.

### R1 — replay / persistence invariant (the guardrail)
The refactor must not change a single persisted byte: `manifest.json` mode strings,
`FsmEvent.UserCommand.New(mode)` payloads, `ForgeConfig.mode`. The `fsm` package still
names no role type (`ProfileReplayInvarianceSuite` R2 stays green); `Fsm.transition` is
untouched. A cold `RebuildState` over an existing feature reads identically.

---

## 6. Cross-references

- [`roadmap.md`](roadmap.md) §4 sub-slice 3.5 + §4.2 (role-trait refactor); §2.6 (the
  Phase-1 `Role` indirection stub this builds on).
- [`design-rationale.md`](design-rationale.md) **A5** — the sensors are the first
  concrete third+ roles, making this demand-driven.
- [`forge-design-1.10.md`](forge-design-1.10.md) §7 — the live contract restatement (role-trait
  framing + same-CLI reconciliation), standalone-by-freeze over 1.9 §7.1 / §7.11.
- [`design-3.3.md`](design-3.3.md) — the immediately-prior closed Phase-3 sub-slice
  (the "orchestrator decides / FSM stays pure" discipline this preserves).
