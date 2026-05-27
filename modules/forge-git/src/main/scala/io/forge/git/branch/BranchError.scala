package io.forge.git.branch

import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.{GhError, GitError}

import scala.concurrent.duration.FiniteDuration

/** PR-C — domain-level failures emitted by [[BranchManager]]. Each variant maps to a §11 / §9 / §15 failure mode so the
  * Slice-4 orchestrator can pattern-match and route into `NeedsHumanIntervention` without re-reading subprocess stderr.
  *
  * Rationale for the wrapper layer (S3-3 + BM1 friends): the underlying [[GhError]] / [[GitError]] ADTs are
  * subprocess-shaped — they classify exit codes and stderr framing. [[BranchError]] is **operation-shaped**: a
  * fast-forward refusal under `syncBase` becomes [[BaseDiverged]] no matter which `git` invocation produced the
  * underlying failure, and the orchestrator never matches against the raw [[GitError]].
  */
sealed trait BranchError extends Product with Serializable:
  def message: String

object BranchError:

  /** BM1 — `BranchManager.syncBase` saw local strictly ahead-of-or-diverged-from remote. Surfaces verbatim into
    * `NeedsHumanIntervention("base branch diverged locally", AbortOrAbandon)` per v1.2 §9.
    */
  final case class BaseDiverged(local: Sha, remote: Sha) extends BranchError:
    def message: String =
      s"base branch diverged locally (local=${local.value} remote=${remote.value})"

  /** §11.3 step 5 — `git push --force-with-lease` saw a remote update since the local copy. Caller routes into
    * `NeedsHumanIntervention("design branch updated externally", ReopenDesign(prNumber))`.
    */
  final case class ForceLeaseRejected(branch: BranchName) extends BranchError:
    def message: String =
      s"force-push-with-lease rejected for '${branch.value}' (remote moved since last fetch)"

  /** `gh pr create` returned a URL the regex couldn't parse, or `gh pr view` returned malformed JSON in a context where
    * the caller expected a specific field. Indicates a wire-shape change in `gh` itself rather than user error.
    */
  final case class ParseFailure(stage: String, detail: String) extends BranchError:
    def message: String = s"branch-manager parse failure at '$stage': $detail"

  /** Branch-protection probe came back rate-limited. Surfaced so the orchestrator can either back off or fall through
    * with an empty overlay (C6 documents the latter as a deliberate pragmatic choice for `Unauthorized`).
    */
  final case class RateLimited(retryAfter: Option[FiniteDuration]) extends BranchError:
    def message: String =
      val ra = retryAfter.fold("")(d => s" retry-after=${d.toSeconds}s")
      s"gh rate limited$ra"

  /** Catch-all wrapper for any other [[GhError]] the caller didn't promote into a more specific [[BranchError]]. The
    * underlying error is preserved so audit-log entries can recover the original wire framing.
    */
  final case class GhFailure(error: GhError) extends BranchError:
    def message: String = error.message

  /** Catch-all wrapper for any other [[GitError]]. */
  final case class GitFailure(error: GitError) extends BranchError:
    def message: String = error.message
