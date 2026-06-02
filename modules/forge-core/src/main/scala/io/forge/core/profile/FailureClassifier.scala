package io.forge.core.profile

import upickle.default.{readwriter, ReadWriter}

/** What a gate failure *is*, as perceived by a sensor. The core routes deterministically on this (see
  * [[FailureRouting]]); it never re-invokes an LLM to act.
  */
enum FailureKind:
  /** A formatter / codegen gate the repo's own tool fixes in place (dogfood-#2 scalafmt). Routes to a local tool run.
    */
  case DeterministicFix

  /** A real code defect — needs a driver fix-up turn, *with the real failing log piped in*. */
  case CodeFix

  /** A transient test failure — retry the gate. */
  case Flaky

  /** Toolchain / infrastructure failure (missing binary, OOM) — back off. */
  case Env

  /** Provider / API rate limit (dogfood-#5 `GraphQL: API rate limit already exceeded`) — back off and keep polling. */
  case RateLimit

  /** Could not be classified — escalate to a human. */
  case Unknown

object FailureKind:
  def fromString(s: String): Either[String, FailureKind] = s match
    case "deterministic_fix" => Right(DeterministicFix)
    case "code_fix" => Right(CodeFix)
    case "flaky" => Right(Flaky)
    case "env" => Right(Env)
    case "rate_limit" => Right(RateLimit)
    case "unknown" => Right(Unknown)
    case other => Left(s"unknown failure kind '$other'")

  extension (k: FailureKind)
    def asString: String = k match
      case DeterministicFix => "deterministic_fix"
      case CodeFix => "code_fix"
      case Flaky => "flaky"
      case Env => "env"
      case RateLimit => "rate_limit"
      case Unknown => "unknown"

  given ReadWriter[FailureKind] =
    readwriter[String].bimap(_.asString, s => fromString(s).fold(m => throw IllegalArgumentException(m), identity))

/** A sensor's *proposal* about a gate failure. `suggested` names the repo command that would remediate a
  * `DeterministicFix`; `evidence` is the log fragment the classifier keyed on (kept so the routing/audit trail shows
  * *why*).
  */
final case class Classification(
    kind: FailureKind,
    confidence: Double,
    suggested: Option[CommandKind],
    evidence: String
) derives ReadWriter

/** Phase-3 "sensor" seam: perceive a gate failure (`failureLog` + `profile`) and *propose* a [[Classification]]. The
  * eventual implementation is an agentic role (an LLM reading `gh run view --log-failed`), routed through the §3.2 Role
  * indirection and the native-schema + ReviewDecoders pattern. This spike ships only the deterministic baseline
  * [[RuleBasedFailureClassifier]] so the routing contract can be exercised against real dogfood logs before any LLM is
  * wired. The core never *acts* on the proposal — [[FailureRouting]] does, deterministically.
  */
trait FailureClassifier:
  def classify(failureLog: String, profile: RepoProfile): Classification

/** Deterministic baseline classifier — substring/regex rules over the failure log. Stands in for the LLM sensor so the
  * spike can prove end-to-end routing against the *real* dogfood strings (`scalafmt`, `API rate limit already
  * exceeded`, a Scala compile error). It is intentionally conservative: anything it cannot pin lands as `Unknown` (low
  * confidence) and escalates, rather than guessing.
  *
  * Rule order matters — the first match wins, most-specific first.
  */
final class RuleBasedFailureClassifier extends FailureClassifier:
  import RuleBasedFailureClassifier.*

  override def classify(failureLog: String, profile: RepoProfile): Classification =
    val log = failureLog.toLowerCase

    def firstLineMatching(pred: String => Boolean): String =
      failureLog.linesIterator.find(l => pred(l.toLowerCase)).getOrElse("").trim

    if RateLimitMarkers.exists(log.contains) then
      Classification(FailureKind.RateLimit, 0.95, None, firstLineMatching(l => RateLimitMarkers.exists(l.contains)))
    else if FormatMarkers.exists(log.contains) then
      Classification(
        FailureKind.DeterministicFix,
        0.97,
        Some(CommandKind.Format),
        firstLineMatching(l => FormatMarkers.exists(l.contains))
      )
    else if EnvMarkers.exists(log.contains) then
      Classification(FailureKind.Env, 0.8, None, firstLineMatching(l => EnvMarkers.exists(l.contains)))
    else if FlakyMarkers.exists(log.contains) then
      Classification(FailureKind.Flaky, 0.6, None, firstLineMatching(l => FlakyMarkers.exists(l.contains)))
    else if CompileErrorMarkers.exists(log.contains) then
      Classification(
        FailureKind.CodeFix,
        0.85,
        None,
        firstLineMatching(l => CompileErrorMarkers.exists(l.contains))
      )
    else Classification(FailureKind.Unknown, 0.2, None, failureLog.linesIterator.take(1).mkString.trim)

object RuleBasedFailureClassifier:
  // Real markers drawn from the dogfood-#2/#5 logs and the standard tool output they came from.
  private val FormatMarkers: Vector[String] =
    Vector("must be formatted", "scalafmt", "prettier", "gofmt", "code style issues found")

  private val RateLimitMarkers: Vector[String] =
    Vector("api rate limit already exceeded", "rate limit exceeded", "secondary rate limit", "429 too many requests")

  private val EnvMarkers: Vector[String] =
    Vector("command not found", "no such file or directory", "outofmemoryerror", "could not resolve dependencies")

  private val FlakyMarkers: Vector[String] =
    Vector("flaky", "connection reset", "timed out waiting", "address already in use")

  private val CompileErrorMarkers: Vector[String] =
    Vector("[error]", "error:", "type mismatch", "compilation failed", "cannot find symbol")
