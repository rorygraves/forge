package io.forge.git.worktree

import cats.effect.IO
import io.forge.core.{BranchName, Sha}
import io.forge.git.cli.{GitClient, GitError, StatusEntry}

/** D3-2 (roadmap §3.5 / [[docs/design-3.5.md]]) — the **pure** worktree-safety classifier behind driver
  * respawn-avoidance. Given the commit/branch the orchestrator last left a piece branch at (`expectedBranch` /
  * `expectedHead`, both already durable: the manifest's `Piece.baseSha` and branch name) and the observed git state, it
  * decides whether the worktree is safe to resume the driver session onto (D3-3) or must route back to NHI.
  *
  * The classification is deliberately conservative — it carries the safety burden for the default-on D3-3 resume, so it
  * defaults to [[WorktreeSafety.UnexpectedDivergence]] on *any* ambiguity. The decision tree, in precedence order:
  *
  *   1. Branch must be exactly `expectedBranch`. A detached HEAD (`currentBranch` returns `Left` — the seam refuses an
  *      empty `git branch --show-current` with `ParseFailure`) or a different branch ⇒ unsafe.
  *   1. HEAD must be exactly `expectedHead`. Any commit beyond it (operator/other committed work; the driver itself is
  *      not expected to commit) ⇒ unsafe. A `currentSha` read that fails ⇒ unsafe (conservative).
  *   1. No merge/rebase conflict in progress ([[isUnmerged]] over the porcelain rows) ⇒ otherwise unsafe.
  *   1. An empty status ⇒ [[WorktreeSafety.Clean]]; ordinary uncommitted edits ⇒
  *      [[WorktreeSafety.DriverUncommittedOnly]].
  *
  * The [[classify]] overload is pure and table-testable; [[classifyWorktree]] gathers the three git reads through a
  * [[GitClient]] (`FakeGitClient` in tests) and applies it. `currentBranch` / `currentSha` `Left`s are folded *into*
  * the classification as divergence signals (a detached HEAD is a legitimate, expected outcome of those reads); only a
  * failed `status` read — which leaves the worktree shape genuinely unknown — propagates as a `Left` for D3-3 to treat
  * as non-resumable.
  */
object WorktreeSafetyClassifier:

  /** Pure classification over the resolved git reads. `branch` / `head` are the raw `Either`s from
    * [[GitClient.currentBranch]] / [[GitClient.currentSha]] so the conservative "could-not-resolve ⇒ unsafe" policy
    * lives here in one place; `entries` are the [[GitClient.status]] rows (ignored rows excluded — they are not tracked
    * changes).
    */
  def classify(
      expectedBranch: BranchName,
      expectedHead: Sha,
      branch: Either[GitError, BranchName],
      head: Either[GitError, Sha],
      entries: Vector[StatusEntry]
  ): WorktreeSafety =
    branch match
      case Left(_) => WorktreeSafety.UnexpectedDivergence // detached HEAD / parse failure
      case Right(b) if b != expectedBranch => WorktreeSafety.UnexpectedDivergence // operator switched branch
      case Right(_) =>
        head match
          case Left(_) => WorktreeSafety.UnexpectedDivergence // rev-parse failed
          case Right(h) if h != expectedHead => WorktreeSafety.UnexpectedDivergence // commits beyond expected HEAD
          case Right(_) =>
            if entries.exists(isUnmerged) then WorktreeSafety.UnexpectedDivergence // merge/rebase in progress
            else if entries.isEmpty then WorktreeSafety.Clean
            else WorktreeSafety.DriverUncommittedOnly

  /** Gather the three reads from `git` and classify. A failed `status` read propagates as `Left`; `currentBranch` /
    * `currentSha` failures are absorbed by [[classify]] as divergence.
    */
  def classifyWorktree(
      git: GitClient,
      expectedBranch: BranchName,
      expectedHead: Sha
  ): IO[Either[GitError, WorktreeSafety]] =
    git.status(includeIgnored = false).flatMap {
      case Left(err) => IO.pure(Left(err))
      case Right(entries) =>
        for
          branch <- git.currentBranch
          head <- git.currentSha
        yield Right(classify(expectedBranch, expectedHead, branch, head, entries))
    }

  /** git `status --porcelain` v1 "unmerged" (conflict) detection: a `U` in either the index or worktree column, or the
    * both-added (`AA`) / both-deleted (`DD`) pairs. An in-progress merge/rebase is never the expected mid-exploration
    * driver state, so it forces [[WorktreeSafety.UnexpectedDivergence]] even when branch and HEAD match.
    */
  private def isUnmerged(e: StatusEntry): Boolean =
    e.index == 'U' || e.worktree == 'U' ||
      (e.index == 'A' && e.worktree == 'A') ||
      (e.index == 'D' && e.worktree == 'D')
