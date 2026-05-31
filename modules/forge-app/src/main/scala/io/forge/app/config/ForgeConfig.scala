package io.forge.app.config

import io.forge.core.Mode
import io.forge.specs.StagingConfig
import upickle.default.ReadWriter

/** Task 1.4.9 I1 — typed mirror of `.forge/config.json` (v1.2 §18). Every command depends on it.
  *
  * **Lenient merge by default args.** Every field carries its §18 default, so a partially-specified `config.json` (one
  * that sets only, say, `baseBranch`) decodes with the rest of §18 filling in — the same shape
  * [[io.forge.specs.StagingConfig]] already relies on. This is what lets [[ForgeConfigLoader]] treat a *missing*
  * `config.json` as "use §18 defaults" rather than an error, and a partial `.forge/overrides/<key>.json` as "override
  * only the named key, default the rest of its sub-object".
  *
  * **`staging` is reused from `forge-specs`** rather than redefined — [[io.forge.specs.ChangeCollector]] (Task 1.4.5)
  * already consumes [[StagingConfig]], and `StagingConfig.DefaultDenyPatterns` is the single source of truth for the
  * §18 deny list. Keeping one definition means the loader and the collector can never drift.
  *
  * **Scope: §18 verbatim.** The shape mirrors §18 field-for-field, including the placeholder `String` keys
  * (`auditMode`, `logRetention`, `claude.permissionMode`, `codex.sandbox`, …) that the spec leaves un-enum'd for v1;
  * modelling them as `String` keeps the loader faithful to the wire shape and defers any enum tightening to the
  * consumer. Reviewer-side tuning knobs (model / wall-clock cap / timeout-retry — carry-forward **S4-5**) are **not**
  * added here: that is a §18 extension and goes through a `forge-design-1.3.md` revision, not a silent field addition.
  * Until then the reviewer model/cap stay at the Task 1.4.7 v1 values (`haiku` / `gpt-5.3-codex`, 3-min cap) inside the
  * reviewer-call wiring.
  */
final case class ForgeConfig(
    mode: Mode = Mode.ClaudeDriver,
    baseBranch: String = "main",
    branchPrefix: String = "forge",
    pollIntervalMs: Long = 30000L,
    maxFixupRounds: Int = 3,
    maxDesignReviewRounds: Int = 3,
    maxHaltRespawns: Int = 5,
    maxFeatureCostUsd: Double = 25.00,
    maxPieceCostUsd: Double = 8.00,
    maxTurnCostUsd: Double = 2.00,
    auditMode: String = "summary",
    logRetention: String = "keep-local",
    baseFreshness: BaseFreshnessConfig = BaseFreshnessConfig(),
    ci: CiConfig = CiConfig(),
    staging: StagingConfig = StagingConfig(),
    claude: ClaudeConfig = ClaudeConfig(),
    codex: CodexConfig = CodexConfig(),
    settle: SettleConfig = SettleConfig(),
    github: GithubConfig = GithubConfig()
) derives ReadWriter

object ForgeConfig:
  /** The §18 default posture — every field at its spec default. */
  val Default: ForgeConfig = ForgeConfig()

/** §18 `baseFreshness` block. */
final case class BaseFreshnessConfig(
    autoUpdate: Boolean = true
) derives ReadWriter

/** §18 `ci` block (§8.1 CI policy). */
final case class CiConfig(
    policy: String = "branch_protection_then_observed",
    requiredChecksOverlay: Vector[String] = Vector.empty,
    minimumExpectedChecks: Int = 1,
    checkDiscoveryTimeoutSec: Int = 180,
    stableGreenPolls: Int = 2
) derives ReadWriter

/** §18 `claude` block (driver/reviewer Claude connector settings). `reviewProcessRetries` / `refineProcessRetries`
  * cover process-level failures only (§7.6); schema-validation failures are adapter errors and not retried.
  */
final case class ClaudeConfig(
    model: String = "default",
    permissionMode: String = "acceptEdits",
    allowedTools: Vector[String] =
      Vector("Read", "Write", "Edit", "Bash", "Glob", "Grep", "WebFetch", "AskUserQuestion"),
    // `Task` is the sub-agent spawner. A focused single-piece driver should NOT fan out into exploration/verification
    // sub-agents — they re-scan the whole repo and ignore the driver prompt's constraints (e.g. they run the heavy
    // build), which is what blew the implement turn to ~$10 / 20 min on the first MVP run (S4-5 finding).
    disallowedTools: Vector[String] = Vector("Task"),
    isolationFlag: String = "auto",
    reviewProcessRetries: Int = 2,
    refineProcessRetries: Int = 2
) derives ReadWriter

/** §18 `codex` block. */
final case class CodexConfig(
    sandbox: String = "read-only",
    driverSandbox: String = "workspace-write",
    reviewProcessRetries: Int = 2,
    refineProcessRetries: Int = 2
) derives ReadWriter

/** §18 `settle` block — per-driver-phase wall-clock settle caps (seconds). */
final case class SettleConfig(
    specTimeoutSec: Int = 300,
    designRevisionTimeoutSec: Int = 600,
    implementTimeoutSec: Int = 1800,
    fixupTimeoutSec: Int = 900
) derives ReadWriter

/** §18 `github` block. */
final case class GithubConfig(
    commentApi: String = "line-based",
    cacheBranchProtection: Boolean = true,
    branchProtectionTtlSec: Int = 3600,
    cacheDiff: Boolean = true,
    rateLimitBackoffMs: Long = 60000L,
    pushSnapshotTags: Boolean = false,
    snapshotTagRetention: Int = 3
) derives ReadWriter
