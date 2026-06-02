package io.forge.core.profile

/** What the deterministic spine *does* with a sensor's [[Classification]] — the "core decides" half of A5: pure,
  * replayable, no LLM. The whole point of Phase 3 is that the high-value path, [[FixupRoute.RunLocalCommand]], never
  * spends a driver turn.
  */
enum FixupRoute:
  /** Deterministic fix: the spine runs the repo's own tool and commits the result — no driver turn, no LLM cost. The
    * dogfood-#2 collapse, where a 2-round / $1.78 / 12-min fix-up becomes one ~2s local scalafmt run.
    */
  case RunLocalCommand(command: RepoCommand)

  /** Real code fix: a driver fix-up turn that carries the full failing log from gh run view, so the driver sees the
    * real scalafmt error rather than only the gh pr checks summary — the dogfood-#4 ask.
    */
  case DriverFixup(failureLog: String)

  /** Transient test failure — re-run the gate. */
  case Retry

  /** Environment or rate-limit failure — wait and keep polling, the dogfood-#5 false-NHI fix. */
  case BackOff(reason: String)

  /** Unclassifiable — escalate to a human (NeedsHumanIntervention). */
  case Escalate(reason: String)

object FixupRoute:
  /** Stable label for the §19 `profile.failure_classified.route` field and the `forge stats` fold (a `RunLocalCommand`
    * is a fix-up round that did not happen). Matches the enum case name.
    */
  extension (r: FixupRoute)
    def label: String = r match
      case _: FixupRoute.RunLocalCommand => "RunLocalCommand"
      case _: FixupRoute.DriverFixup => "DriverFixup"
      case FixupRoute.Retry => "Retry"
      case _: FixupRoute.BackOff => "BackOff"
      case _: FixupRoute.Escalate => "Escalate"

/** The deterministic routing rule from a [[Classification]] plus the [[RepoProfile]] to a [[FixupRoute]]. Total. */
object FailureRouting:

  def route(classification: Classification, profile: RepoProfile, failureLog: String): FixupRoute =
    classification.kind match
      case FailureKind.DeterministicFix => deterministicRoute(classification, profile, failureLog)
      case FailureKind.CodeFix => FixupRoute.DriverFixup(failureLog)
      case FailureKind.Flaky => FixupRoute.Retry
      case FailureKind.RateLimit => FixupRoute.BackOff("provider rate limit — back off and keep polling")
      case FailureKind.Env => FixupRoute.BackOff("environment / toolchain failure — back off")
      case FailureKind.Unknown => FixupRoute.Escalate("unclassified gate failure — needs human intervention")

  /** A [[FailureKind.DeterministicFix]] collapses to a local run only when the profile declares a deterministic,
    * in-place autofix command for the suggested kind; otherwise it falls back to a driver fix-up with the real log.
    */
  private def deterministicRoute(c: Classification, profile: RepoProfile, log: String): FixupRoute =
    localAutofix(c, profile).fold(FixupRoute.DriverFixup(log))(FixupRoute.RunLocalCommand.apply)

  private def localAutofix(c: Classification, profile: RepoProfile): Option[RepoCommand] =
    c.suggested.flatMap(profile.command).filter(cmd => cmd.autofix && cmd.determinism == Determinism.Deterministic)
