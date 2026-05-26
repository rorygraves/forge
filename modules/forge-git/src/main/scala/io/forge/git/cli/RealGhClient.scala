package io.forge.git.cli

import cats.effect.IO
import io.forge.core.{BranchName, PrNumber}

import scala.concurrent.duration.*
import scala.util.matching.Regex

/** `os.proc(...).call(...)` shell-out implementation of [[GhClient]]. Each method builds a `Vector[String]` argv and
  * routes through the private `invoke` helper that owns exit-code / stderr classification.
  *
  * Design notes:
  *
  *   - **One-shot invocations only.** `gh pr view` returns a single JSON payload; `gh pr create` writes one URL line.
  *     The streaming-subprocess primitive (`forge-agents.Subprocess`) is sized for long-lived Claude / Codex sessions
  *     and would be over-build here — see design-rationale **S3-1** for the call.
  *   - **`stderr = os.Pipe`** so the classifier can scan it for rate-limit framing without polluting the parent
  *     process's stderr.
  *   - **`check = false`** because we want to classify non-zero exits, not throw.
  *   - **Network safety:** `repoRoot` is passed as `cwd` so `gh` picks up the local remote, and `env` overrides allow
  *     tests to inject `GH_TOKEN` / `NO_COLOR` without leaking JVM env state.
  *
  * @param repoRoot
  *   working directory for every `gh` call. Almost always the repo root.
  * @param env
  *   environment overlay applied on top of the JVM's environment. Empty by default.
  */
final class RealGhClient(repoRoot: os.Path, env: Map[String, String] = Map.empty) extends GhClient:

  override def prView(pr: PrNumber, fields: Vector[String]): IO[Either[GhError, ujson.Value]] =
    val argv = Vector("gh", "pr", "view", pr.value.toString, "--json", fields.mkString(","))
    invoke(argv).map(_.flatMap(parseJson("pr-view", _)))

  override def prCreate(
      title: String,
      body: String,
      base: BranchName,
      head: BranchName
  ): IO[Either[GhError, PrNumber]] =
    val argv = Vector(
      "gh",
      "pr",
      "create",
      "--title",
      title,
      "--body",
      body,
      "--base",
      base.value,
      "--head",
      head.value
    )
    invoke(argv).map(_.flatMap(parsePrCreateUrl))

  override def prUpdateBranch(pr: PrNumber): IO[Either[GhError, Unit]] =
    invoke(Vector("gh", "pr", "update-branch", pr.value.toString)).map(_.map(_ => ()))

  override def prDiff(pr: PrNumber): IO[Either[GhError, String]] =
    invoke(Vector("gh", "pr", "diff", pr.value.toString))

  override def apiBranchProtection(base: BranchName): IO[Either[GhError, Option[ujson.Value]]] =
    invoke(Vector("gh", "api", RealGhClient.branchProtectionApiPath(base))).map {
      case Left(GhError.NotFound(_)) => Right(None)
      case Left(GhError.Unauthorized(_)) => Right(None)
      case Left(other) => Left(other)
      case Right(stdout) => parseJson("branch-protection", stdout).map(Some(_))
    }

  private def invoke(argv: Vector[String]): IO[Either[GhError, String]] =
    IO.blocking {
      val res = os.proc(argv).call(cwd = repoRoot, env = env, check = false, stderr = os.Pipe)
      RealGhClient.classify(res.exitCode, res.out.text(), res.err.text())
    }

  private def parseJson(stage: String, raw: String): Either[GhError, ujson.Value] =
    try Right(ujson.read(raw))
    catch case t: Throwable => Left(GhError.ParseFailure(stage, t, raw))

  private def parsePrCreateUrl(raw: String): Either[GhError, PrNumber] =
    val trimmed = raw.trim
    RealGhClient.PrUrlPattern.findFirstMatchIn(trimmed) match
      case Some(m) =>
        try Right(PrNumber(m.group(1).toInt))
        catch case t: Throwable => Left(GhError.ParseFailure("pr-create-url", t, trimmed))
      case None =>
        Left(
          GhError.ParseFailure(
            "pr-create-url",
            IllegalArgumentException(s"no /pull/<n> match in '$trimmed'"),
            trimmed
          )
        )

object RealGhClient:

  /** §9 BM8 / S3-6: `gh pr create` writes the PR URL to stdout (no `--json` form). Pinned regex for the trailing
    * `/pull/<digits>` segment so a future change in `gh`'s stdout framing surfaces as a `ParseFailure` instead of a
    * silent truncation.
    */
  val PrUrlPattern: Regex = """^https?://[^/]+/[^/]+/[^/]+/pull/(\d+)\s*$""".r

  /** Build the `gh api` path for a branch's required-status-checks endpoint. The base name is URL-encoded so that
    * release-style names with `/` (e.g. `release/1.0`) map to a single path segment (`release%2F1.0`) and don't get
    * split into nested API parts by the GitHub router. `{owner}` and `{repo}` stay as `gh` template tokens — `gh` fills
    * them in from the local git remote.
    *
    * Visible for testing.
    */
  def branchProtectionApiPath(base: BranchName): String =
    val encoded = java.net.URLEncoder.encode(base.value, java.nio.charset.StandardCharsets.UTF_8)
    s"repos/{owner}/{repo}/branches/$encoded/protection/required_status_checks"

  private val RetryAfterPattern: Regex = """(?i)retry[- ]?after[:\s]+(\d+)""".r
  private val RateLimitPattern: Regex =
    """(?i)(rate limit exceeded|secondary rate limit|x-ratelimit-remaining:\s*0)""".r
  private val NotFoundPattern: Regex = """(?i)(\bhttp[\s/:]*404\b|\b404 not found\b)""".r
  private val UnauthorizedPattern: Regex =
    """(?i)(\bhttp[\s/:]*40[13]\b|\b40[13] (forbidden|unauthorized)\b|bad credentials)""".r

  /** Classify a finished `gh` invocation into either its stdout body (success) or a [[GhError]] variant.
    *
    * Exit code 0 ⇒ success. Non-zero ⇒ scan stderr in priority order — rate-limit wins over 404 wins over 401/403 wins
    * over generic transient. The exit-code-first ordering matters because `gh` exits 1 with rate-limit framing on
    * stderr; classifying purely on exit code would lose the rate-limit signal.
    *
    * Visible for testing: `GhErrorClassifierSuite` (PR-A A6 landing checklist) hands this method fixture stderr blobs
    * from real `gh` invocations and asserts the correct variant.
    */
  def classify(exitCode: Int, stdout: String, stderr: String): Either[GhError, String] =
    if exitCode == 0 then Right(stdout)
    else
      val err = stderr
      if RateLimitPattern.findFirstIn(err).isDefined then
        val retryAfter = RetryAfterPattern
          .findFirstMatchIn(err)
          .flatMap(m =>
            try Some(m.group(1).toLong.seconds)
            catch case _: Throwable => None
          )
        Left(GhError.RateLimited(retryAfter, err))
      else if NotFoundPattern.findFirstIn(err).isDefined then Left(GhError.NotFound(err.take(200)))
      else if UnauthorizedPattern.findFirstIn(err).isDefined then Left(GhError.Unauthorized(err.take(200)))
      else Left(GhError.Transient(exitCode, err))
