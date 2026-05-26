package io.forge.git.cli

import scala.concurrent.duration.FiniteDuration

/** §9 `gh` invocation failures, classified at the subprocess boundary so callers (`BranchManager`, `PRWatcher`) can
  * branch on a typed ADT instead of re-scanning stderr at every call site.
  *
  *   - [[GhError.RateLimited]] — `gh` exited non-zero with rate-limit framing on stderr. Carries the parsed
  *     `Retry-After` when present so `PRWatcher` (per design-rationale RL1) can sleep precisely instead of guessing.
  *   - [[GhError.NotFound]] — 404. The `apiBranchProtection` caller uses this to distinguish "unprotected branch" from
  *     "unreachable repo".
  *   - [[GhError.Unauthorized]] — 401 / non-rate-limit 403. Treated by `BranchManager.requiredChecksOverlay` as a
  *     pragmatic "fallback to empty overlay"; other callers propagate.
  *   - [[GhError.Transient]] — non-zero exit with no rate-limit / auth / 404 framing. Caller decides whether to retry.
  *   - [[GhError.ParseFailure]] — exit 0 but stdout couldn't be decoded (invalid JSON, URL regex miss, etc.).
  */
sealed trait GhError extends Product with Serializable:
  def message: String

object GhError:
  final case class RateLimited(retryAfter: Option[FiniteDuration], raw: String) extends GhError:
    def message: String =
      val ra = retryAfter.fold("")(d => s" retry-after=${d.toSeconds}s")
      s"gh rate limited$ra: $raw"

  final case class NotFound(path: String) extends GhError:
    def message: String = s"gh 404: $path"

  final case class Unauthorized(detail: String) extends GhError:
    def message: String = s"gh unauthorized: $detail"

  final case class Transient(exitCode: Int, stderr: String) extends GhError:
    def message: String = s"gh exit=$exitCode stderr=${stderr.take(200)}"

  final case class ParseFailure(stage: String, cause: Throwable, raw: String) extends GhError:
    def message: String = s"gh parse failure at '$stage': ${cause.getMessage} raw=${raw.take(200)}"
