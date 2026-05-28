package io.forge.specs

import upickle.default.ReadWriter

/** Task 1.4.5 E3 — the `staging` block of `.forge/config.json` (§18), the policy [[ChangeCollector]] consumes.
  *
  * Loaded by the orchestrator's config loader (Slice 1.4b Task 1.4.9) and threaded into every `classify` call; Task
  * 1.4.5 only consumes it. Default args mean a partially-specified `staging` JSON object (e.g. one that sets only
  * `requireExplicitAllow`) decodes with the §18 defaults filling the rest — the same lenient-merge shape Task 1.4.9
  * applies to the rest of `ForgeConfig`.
  *
  * [[StagingConfig.DefaultDenyPatterns]] is the single source of truth for the §18 default deny list; both this case
  * class's default and the orchestrator's config defaulting draw from it so the list can't drift between the consumer
  * (ChangeCollector) and the loader.
  */
final case class StagingConfig(
    requireExplicitAllow: Boolean = false,
    denyPatterns: Vector[String] = StagingConfig.DefaultDenyPatterns,
    allowPatterns: Vector[String] = Vector.empty
) derives ReadWriter

object StagingConfig:

  /** §18 default `staging.denyPatterns`. Verbatim from the spec's `.forge/config.json` example. */
  val DefaultDenyPatterns: Vector[String] = Vector(
    "**/.env",
    "**/.env.*",
    "**/*.pem",
    "**/*.key",
    "**/id_rsa*",
    "**/credentials.json",
    "**/.aws/**",
    "**/.ssh/**",
    "**/target/**",
    "**/build/**",
    "**/dist/**",
    "**/node_modules/**",
    "**/.bloop/**",
    "**/.metals/**",
    "**/.idea/**",
    "**/.vscode/**"
  )

  /** The §18 default posture: lenient (`requireExplicitAllow = false`) with the default deny list and no allow list. */
  val Default: StagingConfig = StagingConfig()
