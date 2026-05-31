package io.forge.app.orchestrator

import cats.effect.IO
import io.forge.agents.{ClaudeConnector, CodexConnector, CodexSessionSettings, Connector, PriceTable, ReviewerAssets}
import io.forge.app.config.ForgeConfig
import io.forge.core.Mode
import io.forge.core.paths.ForgePaths

import scala.concurrent.duration.*

/** Task 1.4.10 **J3** — constructs the single `Connector` an orchestrator run drives. One connector per `Mode` is built
  * once at run start and shared across every driver call (`runStreamingSpec` / `resumeStreamingSpec` /
  * `runHeadlessImplementation` / `runFixup`) and every reviewer one-shot (`reviewDesign` / `reviewPr` / `refine`) — the
  * §7.1 "driver + reviewer over a single CLI" surface.
  *
  * **Reviewer assets** are resolved from the user's installed `~/.forge/{schemas,prompts}/` (Task 1.4.1
  * `AssetInstaller` populates them on first run). The schema files are shared across reviewers (`design-review.json`,
  * `code-review.json`, `refine.json`); the system prompts are per-CLI (`<method>.<cli>.md`).
  *
  * **v1 model / cap hard-wiring (S4-5).** `ForgeConfig` deliberately ships no reviewer-tuning knobs (Task 1.4.9 I1 — a
  * §18 extension belongs in `forge-design-1.3.md`). Until **S4-5** closes, the factory pins the Task 1.4.7 / C15 v1
  * configuration here: the Claude reviewer runs on `haiku`, the Codex driver+reviewer on `gpt-5.3-codex`, both with a
  * 3-minute per-call cap (matching the wall-clock cap the orchestrator enforces in [[Orchestrator]]). The Claude driver
  * model is the CLI default (`ClaudeConnector` exposes no driver-model flag); the Codex `model` covers both driver and
  * reviewer because the CLI takes a single `-m`.
  */
object ConnectorFactory:

  /** v1 reviewer wall-clock cap; mirrors `Orchestrator.reviewerWallClock`. S4-5 makes this a `ForgeConfig` knob. */
  private val ReviewerCap: FiniteDuration = 3.minutes

  /** v1 Claude reviewer model (C15). S4-5 makes this a `ForgeConfig` knob. */
  private val ClaudeReviewerModel: String = "haiku"

  /** v1 Codex driver + reviewer model (C15). S4-5 makes this a `ForgeConfig` knob. */
  private val CodexModel: String = "gpt-5.3-codex"

  /** Build the connector for `mode`. Constructed once per run; the resulting `Connector` is shared (J3). */
  def build(mode: Mode, paths: ForgePaths, config: ForgeConfig): IO[Connector] =
    mode match
      case Mode.ClaudeDriver =>
        IO.pure(
          new ClaudeConnector(
            cwd = Some(paths.repoRoot),
            reviewerAssets = Some(reviewerAssets(paths, "claude")),
            reviewerModel = Some(ClaudeReviewerModel),
            reviewerTimeout = ReviewerCap,
            driverPermissionMode = config.claude.permissionMode,
            driverAllowedTools = config.claude.allowedTools,
            driverDisallowedTools = config.claude.disallowedTools
          )
        )
      case Mode.CodexDriver =>
        loadPriceTable(paths).map { priceTable =>
          new CodexConnector(
            model = CodexModel,
            priceTable = priceTable,
            sessionSettings = CodexSessionSettings.driver(sandbox = config.codex.driverSandbox, approvalMode = "never"),
            cwd = Some(paths.repoRoot),
            reviewerAssets = Some(reviewerAssets(paths, "codex")),
            reviewerTimeout = ReviewerCap
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
      refine = per("refine.json", "refine")
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
