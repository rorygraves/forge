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
  * The classifier is the deterministic rules baseline today (Tier 1); the LLM `classifyFailure` consulted on `Unknown`
  * is Tier 2 (`adapt.llmClassifierOnUnknown`), not wired here yet — so [[source]] is always `"rules"`.
  */
final class FailureRouter(classifier: FailureClassifier):

  /** Classify `failureLog` against `profile` and route it. Honours the `adapt.autofix = false` opt-out (§8.2): a
    * `RunLocalCommand` degrades to a `DriverFixup` carrying the same log, so Forge *proposes* the local fix in the
    * fix-up prompt rather than applying + committing it. The classification and the route are recorded either way.
    */
  def route(profile: RepoProfile, failureLog: String, adapt: AdaptConfig): RoutedFailure =
    val classification = classifier.classify(failureLog, profile)
    val routed = FailureRouting.route(classification, profile, failureLog)
    val effective = routed match
      case FixupRoute.RunLocalCommand(_) if !adapt.autofix => FixupRoute.DriverFixup(failureLog)
      case other => other
    RoutedFailure(classification, effective, source = "rules")

/** The router's output: the sensor's [[Classification]], the deterministic [[FixupRoute]] the spine will act on, and
  * the classification `source` (`"rules"` | `"llm"`) recorded in §19 `profile.failure_classified`.
  */
final case class RoutedFailure(classification: Classification, route: FixupRoute, source: String)
