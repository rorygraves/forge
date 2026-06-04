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
> **Status:** 🟡 open — 2026-06-04. Last remaining Phase-3 sub-slice (3.0/3.1/3.2/3.3
> closed; 3.4 ConventionLearner landed via design-3.0 Task 3.2). Tier-1 thin runnable
> (Task 3.5.1) is the de-risking first slice.

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

### Task 3.5.2 — single role-pairing resolution (kill the scattered `match mode`)  [ ]

- [ ] Introduce the one resolution seam — a `RolePairing` (or `AgentPairing`) value that a
      `Mode` resolves to **once**, carrying the recipe the four sites need (which CLI
      drives, which reviews, the retry-budget block selector). `Mode` stays the
      **serialised wire token** (back-compat, criterion 3); it no longer carries behaviour
      dispatch. Resolve `Mode → RolePairing` at a single function.
- [ ] Re-point `ConnectorFactory.build`, `OrchestratorBuilder.retryBudgets`, and
      `ProfileCommand` at the resolved pairing instead of each matching `Mode`. The smell
      test ("`match m: Mode` only in `Mode` + connector construction") tightens to "only in
      the one resolver."
- [ ] **Reconcile D1** here: decide cross-CLI vs same-CLI as the single role→connector
      story and make `pairFor`'s caller (`ProfileCommand`) and the `forge run` path agree
      (or document why they legitimately differ — e.g. `forge profile` deliberately uses
      the *reviewer-side* connector, which is the same CLI under the same-CLI story).
- [ ] Tests: a resolver unit (both modes → expected pairing), and the existing
      `ProfileCommand` / orchestrator-builder coverage stays green. Persistence round-trip
      unchanged (`ModeSuite` / `ManifestSuite` / `FsmEventSuite`).

### Tier 3 — close-out

### Task 3.5.3 — contract revision + roadmap tick  [ ]

- [ ] Open `forge-design-1.10.md` (standalone-by-freeze over 1.9) restating only the §7.1
      framing ("driver + reviewer + sensor *roles* over connector configurations") and any
      §7.11 wording the reconciliation touches. Do **not** edit 1.9 in place.
- [ ] Walk the carry-forward list; place C1 (Connector-trait split) durably (roadmap §4 or
      a Phase-4 bucket — the role-capability split is most valuable when the daemon adds the
      4th/5th role).
- [ ] Whole-section review, then flip the roadmap §4 sub-slice 3.5 bullet to closed.

---

## 3. Order of work

3.5.1 ✅ (thin runnable — `Agent` base, prove openness) → 3.5.2 (`Mode`→pairing resolver,
reconcile D1) → 3.5.3 (contract + close-out). Tier 1 alone proves the §4.2 "base Agent"
claim; Tier 2 delivers the "configurations not enum cases" half; Tier 3 closes it.

---

## 4. Status log

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

### D1 — cross-CLI (`pairFor`) vs same-CLI (`ConnectorFactory`) reconciliation — open
`Role.pairFor` models the *other* CLI as reviewer; the production `forge run` wiring uses
the *same* CLI on a cheaper model. The role-trait abstraction needs one coherent story.
Leaning: the **same-CLI** story is what actually ships and what C15 validated (claude
driver + claude/`haiku` reviewer; codex driver + codex reviewer), so `pairFor`'s cross-CLI
shape is the stale one — `forge profile`'s "reviewer-side connector" is, under the same-CLI
story, just "the connector built for this mode, used for a reviewer one-shot." Resolve in
Task 3.5.2; confirm against the C15 reviewer config before changing `pairFor`'s semantics.

### D2 — `Mode` stays the wire token, does not become pure trait config — proposed
§4.2 says the modes "become configurations of those traits, **not enum cases**." Read
literally that kills the enum; but `Mode` is the persisted wire form
(`"claude-driver"` in every `manifest.json` on disk) and the natural config token. This
plan keeps `enum Mode` purely as the **serialised selector** and moves the *behaviour*
(connector recipe, retry block, role pairing) into a resolved `RolePairing` configuration
— satisfying "configurations, not enum-case dispatch" at the abstraction level while
preserving the wire form (criterion 3). Flag for the review round: confirm this reading
vs a full enum removal + migration.

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
- [`forge-design-1.9.md`](forge-design-1.9.md) §7.1 / §7.11 — the contract surface a
  Task 3.5.3 `forge-design-1.10.md` revision restates.
- [`design-3.3.md`](design-3.3.md) — the immediately-prior closed Phase-3 sub-slice
  (the "orchestrator decides / FSM stays pure" discipline this preserves).
