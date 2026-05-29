package io.forge.git.cli

import cats.effect.IO
import io.forge.core.{BranchName, Sha}

/** Result of [[GitClient.fastForwardBase]]. The three cases are exhaustive:
  *
  *   - [[FastForwardResult.AlreadyUpToDate]] — local and remote refs match.
  *   - [[FastForwardResult.Updated]] — local was strictly behind remote; the implementation fast-forwarded it. The
  *     working tree is preserved: either the current branch matched `<base>` (so `git merge --ff-only` ran) or it
  *     didn't (so `git update-ref` ran without touching the index).
  *   - [[FastForwardResult.LocallyDiverged]] — local has commits the remote doesn't; mapped by `BranchManager` to
  *     `BranchError.BaseDiverged` per design-rationale BM1.
  */
sealed trait FastForwardResult extends Product with Serializable

object FastForwardResult:
  final case class Updated(remote: Sha) extends FastForwardResult
  final case class AlreadyUpToDate(sha: Sha) extends FastForwardResult
  final case class LocallyDiverged(local: Sha, remote: Sha) extends FastForwardResult

/** One `git status --porcelain -z` entry (Task 1.4.10-d2a). The two-character XY status is split into [[index]]
  * (staged) and [[worktree]] (unstaged); [[origPath]] carries the rename/copy source (the new path lives in [[path]]);
  * [[ignored]] is the `!!` case.
  *
  * The orchestrator's `StatusEntry → FileChange` map (Task 1.4.10-d2b, in forge-app) populates `FileChange.gitIgnored`
  * from [[ignored]] — the §10.1 rule-4 bit `ChangeCollector` cannot compute itself. forge-git stays git-domain: it does
  * not depend on forge-specs, so the mapping lives in the consumer.
  */
final case class StatusEntry(
    index: Char,
    worktree: Char,
    path: String,
    origPath: Option[String],
    ignored: Boolean
)

/** Outcome of [[GitClient.commit]] (Task 1.4.10-d2a). A clean tree is **not** an error — it is a normal post-settle
  * case (a driver session that produced no working-tree change) the orchestrator must distinguish from a real commit,
  * so it is modelled as a result variant rather than a [[GitError]].
  */
enum CommitResult:
  case Committed
  case NothingToCommit

/** §9 / §11.3 step 5 / §11.4 step 1 `git` surface. One-shot subprocess invocations classified through [[GitError]]; no
  * streaming. `RealGitClient` shells out via `os.proc.call`; `FakeGitClient` (PR-A A4) is keyed by argv shape so suites
  * can stub deterministically.
  */
trait GitClient:

  /** `git branch --show-current` — empty stdout means detached HEAD; the parser refuses with `ParseFailure`. */
  def currentBranch: IO[Either[GitError, BranchName]]

  /** `git rev-parse HEAD` — the current commit. */
  def currentSha: IO[Either[GitError, Sha]]

  /** `git fetch <remote>`. */
  def fetch(remote: String = "origin"): IO[Either[GitError, Unit]]

  /** Fast-forward `refs/heads/<base>` to `refs/remotes/origin/<base>` if possible — **safe regardless of the current
    * branch**. The algorithm:
    *
    *   1. `git fetch origin <base>` (no working-tree side effects).
    *   1. If `refs/heads/<base>` and `refs/remotes/origin/<base>` resolve to the same SHA →
    *      [[FastForwardResult.AlreadyUpToDate]].
    *   1. Else if local is a strict ancestor of remote → fast-forward. Two sub-paths so the working tree is never
    *      disturbed:
    *      - current branch == `<base>` → `git merge --ff-only refs/remotes/origin/<base>`.
    *      - current branch != `<base>` → `git update-ref refs/heads/<base> <remoteSha>`.
    *   1. Else → [[FastForwardResult.LocallyDiverged]] (the BM1 trigger).
    *
    * Returning the result instead of throwing keeps `BranchManager.syncBase` free to map `LocallyDiverged` to its
    * domain-level `BranchError.BaseDiverged` without a try/catch.
    */
  def fastForwardBase(base: BranchName): IO[Either[GitError, FastForwardResult]]

  /** `git checkout -B <branch> <startPoint?>` — creates the branch from `startPoint` if given, otherwise from HEAD.
    *
    * `startPoint` is any git "commit-ish" expression: a branch name, a SHA, a tag, `HEAD~N`, etc. The caller decides
    * which is appropriate. `BranchManager.createDesignBranch` / `createPieceBranch` pass the resolved
    * `BaseSnapshot.sha` so the new branch is cut from the exact commit captured by `syncBase`, immune to base-ref
    * movement between `syncBase` and `checkout`.
    */
  def checkout(branch: BranchName, startPoint: Option[String]): IO[Either[GitError, Unit]]

  /** `git push origin <branch> [--force-with-lease]`. Per §11.3 step 5, `forceWithLease = true` is the design-revision
    * path; a `non-fast-forward` reply maps to [[GitError.ForceLeaseRejected]].
    */
  def push(branch: BranchName, force: Boolean = false, forceWithLease: Boolean = false): IO[Either[GitError, Unit]]

  /** `git tag <name> <sha>`. */
  def tag(name: String, sha: Sha): IO[Either[GitError, Unit]]

  /** `git push origin <name>` — used by §11.3 step 4's `pushSnapshotTags: true` path. */
  def pushTag(name: String): IO[Either[GitError, Unit]]

  /** `git push origin :refs/tags/<name>` — pairs with [[pushTag]] for the §11.3 step 4 prune-on-rotate path. */
  def deleteRemoteTag(name: String): IO[Either[GitError, Unit]]

  /** `git tag -d <name>` — local-only delete, used by [[io.forge.git.branch.BranchManager.pruneSnapshotTags]] when
    * §11.3 step 4 retention rotates an old snapshot.
    */
  def deleteLocalTag(name: String): IO[Either[GitError, Unit]]

  /** `git tag --list [pattern]` — returns tag names (one per line, sorted by `git` defaults). Used by
    * [[io.forge.git.branch.BranchManager.pruneSnapshotTags]] to enumerate the snapshot-tag namespace.
    */
  def listTags(pattern: Option[String] = None): IO[Either[GitError, Vector[String]]]

  /** `git status --porcelain` empty (clean) vs non-empty (dirty). */
  def isWorktreeClean: IO[Either[GitError, Boolean]]

  /** `git add -A -- <paths>` (Task 1.4.10-d2a) — stage adds / modifications / deletions for the given repo-relative
    * paths (the §11.4 step 6 / §11.6 commit-then-push flow stages exactly the `ChangeCollector` `Allow` set). Empty
    * `paths` is a no-op `Right(())`: the seam never stages the whole tree implicitly.
    */
  def stage(paths: Vector[String]): IO[Either[GitError, Unit]]

  /** `git status --porcelain -z [--ignored]` parsed into [[StatusEntry]] rows (Task 1.4.10-d2a). NUL framing (`-z`)
    * sidesteps the `core.quotePath` quoting trap. `includeIgnored = true` adds the `!!` rows the orchestrator needs to
    * set `FileChange.gitIgnored`.
    */
  def status(includeIgnored: Boolean = false): IO[Either[GitError, Vector[StatusEntry]]]

  /** `git commit -m <message>` (Task 1.4.10-d2a). A clean tree maps to [[CommitResult.NothingToCommit]] (not an error);
    * a real commit maps to [[CommitResult.Committed]] — read the new SHA via [[currentSha]]. Identity is ambient (the
    * repo / global `user.name` / `user.email`); committer-as-bot policy is an orchestrator concern, not the seam's.
    */
  def commit(message: String): IO[Either[GitError, CommitResult]]

  /** `git show-ref --verify refs/heads/<name>` — exit 0 ⇒ exists. */
  def branchExistsLocal(name: BranchName): IO[Either[GitError, Boolean]]

  /** `git show-ref --verify refs/remotes/origin/<name>` — exit 0 ⇒ exists. */
  def branchExistsRemote(name: BranchName): IO[Either[GitError, Boolean]]
