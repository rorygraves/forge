package io.forge.app.orchestrator

import io.forge.app.config.AdaptConfig
import io.forge.core.profile.{Classification, FailureClassifier, FailureRouting, FixupRoute, RepoProfile}

/** §8.2 deterministic failure router — the thin, pure wiring around the `forge-core` spine
  * ([[io.forge.core.profile.RuleBasedFailureClassifier]] + [[io.forge.core.profile.FailureRouting]]) that the
  * orchestrator consults on a CI-gate failure (and, later, a §8.3 local gate failure). Pure given the already-fetched
  * `failureLog`: the effectful parts — pulling `gh run view --log-failed`, running the autofix command, pushing — live
  * in the orchestrator / [[SideEffects]]. Keeping the decision pure is what keeps it trivially testable against the
  * real dogfood log and replayable (the chosen [[Classification]] / route is recorded in §19
  * `profile.failure_classified`).
  *
  * [[route]] uses the deterministic rules baseline ([[source]] `"rules"`); the LLM `classifyFailure` consulted on
  * `Unknown` (Tier 2, `adapt.llmClassifierOnUnknown`) is wired orchestrator-side, which re-routes its proposal through
  * [[routeFrom]] with [[source]] `"llm"` — the IO call stays out of this pure router (Task 3.1.4).
  */
final class FailureRouter(classifier: FailureClassifier):

  /** Classify `failureLog` against `profile` and route it. Honours the `adapt.autofix = false` opt-out (§8.2): a
    * `RunLocalCommand` degrades to a `DriverFixup` carrying the same log, so Forge *proposes* the local fix in the
    * fix-up prompt rather than applying + committing it. The classification and the route are recorded either way.
    */
  def route(profile: RepoProfile, failureLog: String, adapt: AdaptConfig): RoutedFailure =
    routeFrom(classifier.classify(failureLog, profile), profile, failureLog, adapt, source = "rules")

  /** Route a [[Classification]] that *already exists* — used by the orchestrator to re-route from the LLM
    * `classifyFailure` proposal when the rules baseline left the failure `Unknown` (Task 3.1.4, `source = "llm"`).
    * Applies the same `adapt.autofix = false` degradation as [[route]], so an LLM-proposed `RunLocalCommand` honours
    * the opt-out identically. Keeping the classification an explicit input (rather than re-classifying) is what lets
    * the LLM tail and the rules baseline share one routing rule and one audit shape.
    */
  def routeFrom(
      classification: Classification,
      profile: RepoProfile,
      failureLog: String,
      adapt: AdaptConfig,
      source: String
  ): RoutedFailure =
    val routed = FailureRouting.route(classification, profile, failureLog)
    val effective = routed match
      case FixupRoute.RunLocalCommand(_) if !adapt.autofix => FixupRoute.DriverFixup(failureLog)
      case other => other
    RoutedFailure(classification, effective, source)

/** The router's output: the sensor's [[Classification]], the deterministic [[FixupRoute]] the spine will act on, and
  * the classification `source` (`"rules"` | `"llm"`) recorded in §19 `profile.failure_classified`.
  */
final case class RoutedFailure(classification: Classification, route: FixupRoute, source: String)
