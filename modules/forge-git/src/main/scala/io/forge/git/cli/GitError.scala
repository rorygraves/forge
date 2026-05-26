package io.forge.git.cli

import io.forge.core.{BranchName, Sha}

/** §9 / §11.3 step 5 `git` invocation failures, classified at the subprocess boundary so `BranchManager` can branch on
  * a typed ADT.
  *
  *   - [[GitError.NoUpstream]] — push to a branch without an upstream and without `--set-upstream`.
  *   - [[GitError.ForceLeaseRejected]] — `git push --force-with-lease` saw a remote change since the local copy and
  *     refused. The §11.3 step 5 "remote diverged during design revision" path keys off this case.
  *   - [[GitError.FastForwardImpossible]] — emitted by `fastForwardBase` when local has diverged from remote (BM1).
  *   - [[GitError.Transient]] — non-zero exit with no specific framing. Caller decides whether to retry.
  *   - [[GitError.ParseFailure]] — exit 0 but stdout couldn't be parsed (e.g. `rev-parse` returned something that isn't
  *     a SHA).
  */
sealed trait GitError extends Product with Serializable:
  def message: String

object GitError:
  final case class NoUpstream(branch: BranchName) extends GitError:
    def message: String = s"git push: no upstream for branch '${branch.value}'"

  final case class ForceLeaseRejected(branch: BranchName, stderr: String) extends GitError:
    def message: String = s"git push --force-with-lease rejected for '${branch.value}': ${stderr.take(200)}"

  final case class FastForwardImpossible(local: Sha, remote: Sha) extends GitError:
    def message: String = s"git fast-forward impossible: local=${local.value} remote=${remote.value}"

  final case class Transient(exitCode: Int, stderr: String) extends GitError:
    def message: String = s"git exit=$exitCode stderr=${stderr.take(200)}"

  final case class ParseFailure(stage: String, cause: Throwable, raw: String) extends GitError:
    def message: String = s"git parse failure at '$stage': ${cause.getMessage} raw=${raw.take(200)}"
