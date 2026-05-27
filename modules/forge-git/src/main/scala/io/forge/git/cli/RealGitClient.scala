package io.forge.git.cli

import cats.data.EitherT
import cats.effect.IO
import io.forge.core.{BranchName, Sha}

import scala.util.matching.Regex

/** `os.proc(...).call(...)` shell-out implementation of [[GitClient]]. Same one-shot rationale as [[RealGhClient]] —
  * see design-rationale **S3-1** for the call to skip the streaming `forge-agents.Subprocess` wrapper.
  *
  * Every method routes through the private `run` helper that owns exit-code / stderr classification. The classifier is
  * **path-aware**: a few command-specific stderr patterns (force-lease rejection, "no upstream") get their own variant
  * before falling back to [[GitError.Transient]].
  *
  * @param repoRoot
  *   working directory for every `git` call. Almost always the repo root.
  */
final class RealGitClient(repoRoot: os.Path) extends GitClient:

  override def currentBranch: IO[Either[GitError, BranchName]] =
    run(Vector("git", "branch", "--show-current")).map(_.flatMap { stdout =>
      val trimmed = stdout.trim
      if trimmed.isEmpty then
        Left(GitError.ParseFailure("current-branch", IllegalStateException("detached HEAD"), stdout))
      else Right(BranchName(trimmed))
    })

  override def currentSha: IO[Either[GitError, Sha]] =
    run(Vector("git", "rev-parse", "HEAD")).map(_.flatMap(parseSha("current-sha", _)))

  override def fetch(remote: String): IO[Either[GitError, Unit]] =
    run(Vector("git", "fetch", remote)).map(_.map(_ => ()))

  override def fastForwardBase(base: BranchName): IO[Either[GitError, FastForwardResult]] =
    val ref = s"refs/heads/${base.value}"
    val remoteRef = s"refs/remotes/origin/${base.value}"

    def revParse(refName: String): IO[Either[GitError, Sha]] =
      run(Vector("git", "rev-parse", refName)).map(_.flatMap(parseSha(s"rev-parse $refName", _)))

    def isAncestor(local: Sha, remote: Sha): IO[Boolean] =
      IO.blocking {
        val res = os
          .proc("git", "merge-base", "--is-ancestor", local.value, remote.value)
          .call(cwd = repoRoot, check = false, stderr = os.Pipe)
        res.exitCode == 0
      }

    // Bootstrap: a fresh clone may not have a local `refs/heads/<base>` until something
    // checks it out. design-2.3.md A3 names this case explicitly — treat a missing local
    // ref as "match remote" and write it via `update-ref` so syncBase doesn't refuse on
    // valid setups. Missing remote ref still fails (refs/remotes/origin/<base> must exist
    // after fetch — otherwise we're talking to the wrong remote).
    val flow = for
      _ <- EitherT(fetch("origin"))
      remoteExists <- EitherT(branchExistsRemote(base))
      _ <-
        if remoteExists then EitherT.rightT[IO, GitError](())
        else
          EitherT.leftT[IO, Unit](
            GitError.ParseFailure(
              "gh-remote-missing",
              IllegalStateException(s"$remoteRef does not exist after fetch"),
              remoteRef
            )
          )
      remote <- EitherT(revParse(remoteRef))
      localExists <- EitherT(branchExistsLocal(base))
      result <-
        if !localExists then
          EitherT(run(Vector("git", "update-ref", ref, remote.value)))
            .map(_ => FastForwardResult.Updated(remote))
        else
          EitherT(revParse(ref)).flatMap { local =>
            if local == remote then EitherT.rightT[IO, GitError](FastForwardResult.AlreadyUpToDate(local))
            else
              EitherT.liftF[IO, GitError, Boolean](isAncestor(local, remote)).flatMap { ancestor =>
                if !ancestor then EitherT.rightT[IO, GitError](FastForwardResult.LocallyDiverged(local, remote))
                else
                  EitherT(currentBranch).flatMap { current =>
                    val update =
                      if current.value == base.value then run(Vector("git", "merge", "--ff-only", remoteRef))
                      else run(Vector("git", "update-ref", ref, remote.value))
                    EitherT(update).map(_ => FastForwardResult.Updated(remote))
                  }
              }
          }
    yield result

    flow.value

  override def checkout(branch: BranchName, createFrom: Option[BranchName]): IO[Either[GitError, Unit]] =
    val argv = createFrom match
      case Some(from) => Vector("git", "checkout", "-B", branch.value, from.value)
      case None => Vector("git", "checkout", "-B", branch.value)
    run(argv).map(_.map(_ => ()))

  override def push(
      branch: BranchName,
      force: Boolean = false,
      forceWithLease: Boolean = false
  ): IO[Either[GitError, Unit]] =
    val flags = Vector.empty[String] ++
      (if forceWithLease then Vector("--force-with-lease") else Vector.empty) ++
      (if force then Vector("--force") else Vector.empty)
    val argv = Vector("git", "push", "origin", branch.value) ++ flags
    IO.blocking {
      val res = os.proc(argv).call(cwd = repoRoot, check = false, stderr = os.Pipe)
      RealGitClient.classifyPush(branch, res.exitCode, res.out.text(), res.err.text())
    }

  override def tag(name: String, sha: Sha): IO[Either[GitError, Unit]] =
    run(Vector("git", "tag", name, sha.value)).map(_.map(_ => ()))

  override def pushTag(name: String): IO[Either[GitError, Unit]] =
    run(Vector("git", "push", "origin", name)).map(_.map(_ => ()))

  override def deleteRemoteTag(name: String): IO[Either[GitError, Unit]] =
    run(Vector("git", "push", "origin", s":refs/tags/$name")).map(_.map(_ => ()))

  override def deleteLocalTag(name: String): IO[Either[GitError, Unit]] =
    run(Vector("git", "tag", "-d", name)).map(_.map(_ => ()))

  override def listTags(pattern: Option[String]): IO[Either[GitError, Vector[String]]] =
    val argv = Vector("git", "tag", "--list") ++ pattern.toVector
    run(argv).map(_.map { out =>
      out.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    })

  override def isWorktreeClean: IO[Either[GitError, Boolean]] =
    run(Vector("git", "status", "--porcelain")).map(_.map(_.isEmpty))

  override def branchExistsLocal(name: BranchName): IO[Either[GitError, Boolean]] =
    refExists(s"refs/heads/${name.value}")

  override def branchExistsRemote(name: BranchName): IO[Either[GitError, Boolean]] =
    refExists(s"refs/remotes/origin/${name.value}")

  private def refExists(ref: String): IO[Either[GitError, Boolean]] =
    IO.blocking {
      val res = os
        .proc("git", "show-ref", "--verify", "--quiet", ref)
        .call(cwd = repoRoot, check = false, stderr = os.Pipe)
      if res.exitCode == 0 then Right(true)
      else if res.exitCode == 1 then Right(false)
      else Left(GitError.Transient(res.exitCode, res.err.text()))
    }

  private def run(argv: Vector[String]): IO[Either[GitError, String]] =
    IO.blocking {
      val res = os.proc(argv).call(cwd = repoRoot, check = false, stderr = os.Pipe)
      if res.exitCode == 0 then Right(res.out.text())
      else Left(GitError.Transient(res.exitCode, res.err.text()))
    }

  private def parseSha(stage: String, raw: String): Either[GitError, Sha] =
    Sha.fromString(raw.trim).left.map(msg => GitError.ParseFailure(stage, IllegalArgumentException(msg), raw))

object RealGitClient:

  private val ForceLeasePattern: Regex =
    """(?i)(stale info|non-fast-forward|failed to push some refs|\(rejected\)|\(non-fast-forward\)|force-with-lease)""".r
  private val NoUpstreamPattern: Regex =
    """(?i)(no upstream|has no upstream branch|--set-upstream)""".r

  /** Push-specific classifier: maps the `--force-with-lease` rejection (§11.3 step 5) to
    * [[GitError.ForceLeaseRejected]], and a missing upstream to [[GitError.NoUpstream]]. Falls back to
    * [[GitError.Transient]] for everything else.
    *
    * Visible for testing.
    */
  def classifyPush(branch: BranchName, exitCode: Int, stdout: String, stderr: String): Either[GitError, Unit] =
    if exitCode == 0 then Right(())
    else if NoUpstreamPattern.findFirstIn(stderr).isDefined then Left(GitError.NoUpstream(branch))
    else if ForceLeasePattern.findFirstIn(stderr).isDefined then Left(GitError.ForceLeaseRejected(branch, stderr))
    else Left(GitError.Transient(exitCode, stderr))
