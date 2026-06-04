# Forge — design doc v1.10

> A Scala meta-orchestrator that sits above Claude Code and Codex CLI, breaking features into reviewable pieces and shepherding each through design → implement → PR → merge with cross-model review and human-in-the-loop.

**Author:** Rory  •  **Status:** v1.10 — Phase 3 (Repo Adaptation): `Mode` becomes a configuration of `Driver` / `Reviewer` / `Sensor` *roles*, resolved once  •  **Target:** personal tool, OSS later

**This is the implementation contract.** Earlier versions (0.1 → … → 1.8 → 1.9) and their commentaries remain in the workspace as a record of how the design evolved. Implementers read only this document together with the unchanged sections it freezes from 1.9.

**Standalone-by-freeze (this revision only, continuing the 1.7/1.8/1.9 exception).** 1.10 is a *focused* revision: it lands roadmap sub-slice **3.5 — Role-trait refactor** (roadmap §4.2; design-rationale **A5**: the Phase-3 sensors are the first concrete third+ roles, which makes the `Mode`→role-trait generalisation demand-driven). It **restates in full only the section it changes — §7 (the intro framing, §7.1 orchestrator wiring, and §7.11 sensor routing)** — and **freezes every other section at its 1.9 (hence 1.8 / 1.7 / 1.6) text**. Section numbering is preserved 1:1, so any "v1.9 §N" / "v1.6 §N" reference resolves to the same §N here. The runnable contract these sections describe already exists: the slice landed in `forge-core` (`RolePairing` + `Cli`, the single `Mode → pairing` resolver) + `forge-agents` (the `Agent` base trait; `Role.Driver`/`Reviewer`/`Sensor`) + `forge-app` (`ConnectorFactory.build(cli, …)`, `OrchestratorBuilder`/`SpecRepl`/`ProfileCommand` re-pointed at the resolved pairing), proven by `RolePairingSuite` + `RoleSuite` + `ConnectorFactorySuite` with the §6.1 replay invariant intact (`ProfileReplayInvarianceSuite` R1/R2). See [`design-3.5-role-trait.md`](design-3.5-role-trait.md).

**Changed in 1.10:** §7 intro (the `Mode` framing — modes are *configurations* of role traits, not behaviour-dispatching enum cases; cross-model review reconciled to **same-CLI**), §7.1 (the orchestrator wiring resolves a `Mode` to a `RolePairing` once at `RolePairing.of`, builds one connector per `Cli`), §7.11 (sensors route through the same-CLI reviewer-side connector named by `RolePairing.reviewer`, no `Role.pairFor`).

**Scope note — what 1.10 does *not* change.** The `Connector` / `AgentSession` / `StreamingSession` trait *signatures* (1.6 §7.1), the `HaltWithQuestion` protocol (§7.2/§7.3), the schema mechanism (§7.4/§7.5), and the connector-adapter internals (§7.10) are **unchanged** — 1.10 is a refactor of how a `Mode` selects its connectors, not of the connector surface itself. The persisted wire form is **byte-identical**: `manifest.json` still serialises `"claude-driver"` / `"codex-driver"`, `FsmEvent.UserCommand.New(mode)` and `ForgeConfig.mode` are unchanged on disk, and a cold `RebuildState` reads an existing feature identically. Splitting the `Connector` god-trait into per-role capability traits (`DriverConnector` / `ReviewerConnector` / `SensorConnector`) is **out of scope** — carried forward as **C1** (design-3.5-role-trait §5; roadmap §4.2), most valuable when Phase 4/5 adds the 4th/5th role.

---

## 7. Agent connectors, roles, and the `HaltWithQuestion` protocol

v1 supports two modes. A **`Mode` is the persisted wire token that names a role *configuration*** — it is *not* a behaviour-dispatching enum that call sites pattern-match on:

```scala
enum Mode:
  case ClaudeDriver   // Claude both drives (spec/implementation/fix-up) and reviews (design/code/refinery, cheaper model).
  case CodexDriver    // Codex both drives and reviews (single `-m`).
```

Mode is set at feature creation (`forge new --mode ...` or `config.mode`) and persisted in the manifest. Mid-feature mode switching is unsupported.

**Roles, not enum dispatch (roadmap §4.2 / design-3.5-role-trait).** The participants in a run are expressed as **roles over a `Connector`**, not as `match m: Mode` branches scattered across the orchestrator:

- A base `trait Agent { def connector: Connector; def role: String }` — a named participant backed by one connector. The base is deliberately *not* sealed, so a future role (a PR-watcher, a knowledge-base consultant) extends `Agent` directly without touching `Mode`.
- The built-in family is `sealed trait Role extends Agent` with `Role.Driver` / `Role.Reviewer` (the v1 pair) and `Role.Sensor` (the Phase-3 reviewer-side one-shot surface — §7.11). The family is closed because these are Forge's own built-ins; an *external* role extends `Agent`, not `Role`.

A `Mode` resolves to a **`RolePairing(driver: Cli, reviewer: Cli)`** — the concrete CLI configuration each role runs on — through the **single** resolver `RolePairing.of(mode)`. `enum Cli { Claude, Codex }` names a single CLI a connector is built from; connector construction and retry-block selection key off `Cli`, never off `Mode` directly. This is the §4.2 "the two concrete modes become *configurations* of those traits, not enum cases" made concrete: behaviour (connector recipe, retry block, role pairing) lives in the resolved configuration, while `Mode` stays purely the serialised selector (the wire form is byte-identical — see the Scope note).

**Same-CLI review (reconciled).** Both roles of a `Mode` resolve to the **same `Cli`**: one CLI both *drives* and *reviews* a feature, the reviewer running on a cheaper model. This is what the production `forge run` wiring ships and what the C15 regression bar validated — Claude drives on the CLI default and reviews on `haiku`; Codex drives and reviews on its single `gpt-5.3-codex`. The earlier cross-CLI shape ("the *other* CLI reviews", the retired `Role.pairFor`) was the stale framing; 1.10 reconciles the contract to same-CLI. The `driver` / `reviewer` fields of `RolePairing` are kept *distinct* on purpose: a future cross-CLI pairing (an independent reviewer model) becomes a change to `RolePairing.of` alone — callers already read the role-appropriate field, so they do not reshape.

### 7.1 `AgentSession`, `StreamingSession`, and `Connector` traits

The `AgentSession` / `StreamingSession` / `Connector` trait *signatures* are **unchanged from 1.6 §7.1** (driver methods `runStreamingSpec` / `resumeStreamingSpec` / `runHeadlessImplementation` / `runFixup` / `resumeHeadlessDriver`; reviewer one-shots `reviewDesign` / `reviewPr` / `refine`; the §7.11 sensor one-shots; `questionMechanism` / `schemaMechanism` / `costFrom`). 1.10 changes only **how the orchestrator selects which connector fills each role**.

**Orchestrator wiring — resolve once, build per `Cli`.** The orchestrator no longer carries a `Mode` it re-matches at each role boundary. The run's `Mode` is resolved to a `RolePairing` **once**, and one `Connector` is built per `Cli` at the sanctioned construction seam (`ConnectorFactory.build(cli, …)`):

```scala
// Resolve the configuration once (the single sanctioned `match Mode` outside `Mode` itself).
val pairing: RolePairing = RolePairing.of(mode)        // io.forge.core

// Build one connector per Cli (ConnectorFactory is the per-Cli construction seam).
//   ClaudeConnector pins the reviewer model (haiku) + 3-min cap; CodexConnector its single `-m`.
val driverConnector: Connector   = ConnectorFactory.build(pairing.driver, paths, config)
// Same-CLI: pairing.reviewer == pairing.driver today, so the same connector instance
// serves both the driver calls and the reviewer one-shots (one CLI, cheaper reviewer model).
```

The retry-budget selector (`OrchestratorBuilder`) and the spec REPL (`SpecRepl`) read `pairing.driver` (a `Cli`), not `Mode`; `forge profile` builds the single connector named by `pairing.reviewer` (§7.11). The smell test tightens accordingly: **`match m: Mode` appears only in `Mode` itself and in `RolePairing.of`** — every former match site now reads the resolved pairing. Adding a pairing touches `RolePairing.of` and `ConnectorFactory.build`, not four independent call sites.

*(§7.2–§7.10 — `HaltWithQuestion`, schema mechanism, mode-aware assets, adapter internals — are unchanged from 1.6. The `Mode`-aware asset paths `~/.forge/prompts/{specify,implement,fixup}.<driver>.md` and the shared schemas are unchanged; "`<driver>`" is `pairing.driver.name`.)*

### 7.11 Sensor roles (RepoProfiler / FailureClassifier / ConventionLearner)

The three sensors are **perceive-and-propose** roles. They reuse, not re-invent, the reviewer machinery:

- **Routed through the role abstraction, not a new `match Mode`.** Sensors run on the **reviewer-side connector** — the connector named by `RolePairing.of(mode).reviewer`. Under the same-CLI configuration that ships, this is the mode's own CLI (the cross-model "non-driver" framing of the retired `Role.pairFor` no longer applies): the reviewer-side connector is the cheaper-model reading/judging side of the *same* CLI, and routing sensors there preserves the driver≠observer *call* separation even when one CLI fills both roles. `Connector` carries three sensor methods alongside `reviewDesign`/`reviewPr`/`refine`:

  ```scala
  def profileRepo(input: RepoProfilerInput): IO[RepoProfile]
  def classifyFailure(input: FailureClassifierInput): IO[Classification]
  def learnConventions(input: ConventionLearnerInput): IO[ConventionDeltas]
  ```

  Each is invoked exactly like a reviewer one-shot: `~/.forge/schemas/<sensor>.json` Native schema (`--json-schema` / `--output-schema`, §7.4), a `~/.forge/prompts/<sensor>.<connector>.md` system prompt, a structured user body, and a `ReviewDecoders`-style decoder (`Either[String, A]`; malformed ⇒ non-retried `StructuredOutputMalformed`, §7.5). Wall-clock-capped via the existing `ReviewerCall` / `ReviewerOutcome` boundary. `forge profile` builds exactly this connector — `ConnectorFactory.build(RolePairing.of(config.mode).reviewer, …)` — and wraps it in the wall-clock boundary for the `profileRepo` one-shot. `Role.Sensor` is the named participant for these calls; wiring it into the orchestrator's sensor seams (beyond `forge profile`) remains carry-forward **C1**.

- **Rules first, LLM only on `Unknown` (the spike's cost lever).** The deterministic `RuleBasedFailureClassifier` (`forge-core`, free, instant) runs **first** on every failure. The LLM `classifyFailure` is consulted **only** when the rules return low-confidence `Unknown` — so the common cases (scalafmt, rate-limit, compile error) never spend a sensor call, and the LLM is reserved for the genuinely ambiguous tail. The chosen `Classification` (and its `source ∈ {rules, llm}`) is recorded (§19 `profile.failure_classified`) so replay reuses it rather than re-invoking the sensor.

- **`RepoProfiler`** runs on first encounter (no `.forge/profile.json`) or on a CLAUDE.md / AGENTS.md / `.github/workflows` / build-file change: reads those inputs → a `RepoProfile`, written via `ProfileStore.save`. Human-reviewable before first use (it is committed, so it lands in a normal diff).

- **`ConventionLearner`** runs post-run (§11.7 `FeatureDone`): mines failure→remedy patterns + recurring reviewer comments → `RepoProfile` deltas **and a proposed PR to the repo's own CLAUDE.md** ("implement driver must run `sbt scalafmtAll` before settling"). Human-approved; **no autonomous doc mutation** — it proposes, the human merges.

---

*Everything else — §0–§6, §8–§17, §18, §19–§24 — is frozen at its 1.9 (hence 1.8 / 1.7 / 1.6) text.*
