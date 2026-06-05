package io.forge.app.orchestrator

import cats.effect.IO
import io.forge.agents.{ClaudeConnector, CodexConnector, CodexSessionSettings, Connector, PriceTable, ReviewerAssets}
import io.forge.app.config.ForgeConfig
import io.forge.core.Cli
import io.forge.core.paths.ForgePaths

import scala.concurrent.duration.*

/** Task 1.4.10 **J3** — constructs the single `Connector` an orchestrator run drives. One connector per `Cli` is built
  * once at run start and shared across every driver call (`runStreamingSpec` / `resumeStreamingSpec` /
  * `runHeadlessImplementation` / `runFixup`) and every reviewer one-shot (`reviewDesign` / `reviewPr` / `refine`) — the
  * §7.1 "driver + reviewer over a single CLI" surface. The caller resolves a feature's `Mode` to a `RolePairing` once
  * (design-3.5 Task 3.5.2) and passes the relevant `Cli`; this factory is the sanctioned per-`Cli` construction seam.
  *
  * **Reviewer assets** are resolved from the user's installed `~/.forge/{schemas,prompts}/` (Task 1.4.1
  * `AssetInstaller` populates them on first run). The schema files are shared across reviewers (`design-review.json`,
  * `code-review.json`, `refine.json`); the system prompts are per-CLI (`<method>.<cli>.md`).
  *
  * **Reviewer model / cap (S4-5, closed).** The reviewer model + per-call wall-clock cap come from the §18 `reviewer`
  * block ([[io.forge.app.config.ReviewerConfig]]); the defaults reproduce the Task 1.4.7 / C15 v1 values (Claude
  * reviewer `haiku`, Codex driver+reviewer `gpt-5.3-codex`, 3-minute cap), so an unset block is byte-identical to the
  * prior hard-wiring. The Claude driver model is the CLI default (`ClaudeConnector` exposes no driver-model flag); the
  * Codex `model` covers both driver and reviewer because the CLI takes a single `-m`. The cap here mirrors the one
  * `Orchestrator.reviewerWallClock` enforces — both read `config.reviewer.wallClockCapSec`.
  */
object ConnectorFactory:

  /** Build the connector for `cli`. Constructed once per run; the resulting `Connector` is shared (J3). */
  def build(cli: Cli, paths: ForgePaths, config: ForgeConfig): IO[Connector] =
    val reviewerCap = config.reviewer.wallClockCapSec.seconds
    cli match
      case Cli.Claude =>
        IO.pure(
          new ClaudeConnector(
            cwd = Some(paths.repoRoot),
            reviewerAssets = Some(reviewerAssets(paths, "claude")),
            reviewerModel = Some(config.reviewer.claudeModel),
            reviewerTimeout = reviewerCap,
            driverPermissionMode = config.claude.permissionMode,
            driverAllowedTools = config.claude.allowedTools,
            driverDisallowedTools = config.claude.disallowedTools
          )
        )
      case Cli.Codex =>
        loadPriceTable(paths).map { priceTable =>
          new CodexConnector(
            model = config.reviewer.codexModel,
            priceTable = priceTable,
            sessionSettings = CodexSessionSettings.driver(sandbox = config.codex.driverSandbox, approvalMode = "never"),
            cwd = Some(paths.repoRoot),
            reviewerAssets = Some(reviewerAssets(paths, "codex")),
            reviewerTimeout = reviewerCap
          )
        }

  /** §7.1 / §17 — per-method reviewer assets: shared schema file + per-CLI system prompt. */
  private def reviewerAssets(paths: ForgePaths, cli: String): ReviewerAssets =
    def per(schemaLeaf: String, promptMethod: String): ReviewerAssets.PerMethod =
      ReviewerAssets.PerMethod(
        schema = paths.userSchemasDir / schemaLeaf,
        systemPrompt = paths.userPromptsDir / s"$promptMethod.$cli.md"
      )
    ReviewerAssets(
      designReview = per("design-review.json", "design-review"),
      prReview = per("code-review.json", "code-review"),
      refine = per("refine.json", "refine"),
      profileRepo = per("repo-profile.json", "repo-profile"),
      classifyFailure = per("failure-classifier.json", "failure-classifier"),
      learnConventions = per("convention-deltas.json", "learn-conventions")
    )

  /** §7.10(b) — Codex cost telemetry needs the price table. A missing or malformed table degrades to
    * [[PriceTable.empty]] (cost reads as `None`) so the orchestrator keeps running; the user-level table wins over the
    * repo-level one when both are present.
    */
  private def loadPriceTable(paths: ForgePaths): IO[PriceTable] =
    IO.blocking {
      PriceTable.load(paths.pricesUser) match
        case PriceTable.LoadOutcome.Loaded(table) => table
        case _ =>
          PriceTable.load(paths.pricesRepo) match
            case PriceTable.LoadOutcome.Loaded(table) => table
            case _ => PriceTable.empty
    }
