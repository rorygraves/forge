package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, PieceId, PrNumber}
import io.forge.core.pr.PrState
import io.forge.git.branch.RealBranchManager
import io.forge.git.branch.protection.InMemoryBranchProtectionCache
import io.forge.git.cli.{RealGhClient, RealGitClient}
import io.forge.git.watcher.{PRWatcherConfig, PollBaseline, PollResult, RealPRWatcher}

import scala.concurrent.duration.*

/** PR-G G2 — end-to-end integration test against a sacrificial GitHub repo (see `modules/forge-it/README.md`).
  *
  * Drives one piece-branch lifecycle through the real Slice-3 stack:
  *
  *   1. clone `$FORGE_IT_GH_REPO` into a fresh temp dir;
  *   1. bootstrap `main` with a placeholder commit if the remote has no `main` yet (first-run convenience for a
  *      freshly-created empty repo);
  *   1. [[RealBranchManager.syncBase]] against `main`;
  *   1. [[RealBranchManager.createPieceBranch]] cuts `forge-it/slice3/<uid>/p1` from the resolved base;
  *   1. write a marker file, commit, [[RealBranchManager.pushCurrentBranch]];
  *   1. [[RealBranchManager.createPr]] opens the PR;
  *   1. [[RealPRWatcher.pollOnce]] sees `state == Open`;
  *   1. **`RealGhClient.prMerge`** drives the merge (this method is intentionally IT-only — see its scaladoc and
  *      `design-2.3.md` PR-G G2 step 7);
  *   1. [[RealPRWatcher.pollOnce]] with the `nextBaseline` from step 7 sees `state == Merged` with non-null `mergedAt`
  *      and `mergeCommit`.
  *
  * **Opt-in via `FORGE_IT_GH_REPO=<owner>/<repo>`** — when unset (or when `gh` / `git` are not on `PATH`) the suite
  * skips via `assume(...)`. This mirrors `CodexHaltWithQuestionReliabilitySuite`'s opt-in posture per the "default-on
  * test runtime <60s" feedback memory; each run hits GitHub for ~30–90s wall-clock on a warm `gh` cache.
  *
  * The sacrificial repo accumulates closed PRs and orphan branches; see `modules/forge-it/README.md` for cleanup. The
  * test does **not** delete the branch — leaving the trail visible matches design-2.3.md G2 step 9 ("the branch lingers
  * ... can be pruned out-of-band").
  */
class BranchManagerIntegrationSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 5.minutes

  private val ghRepo: Option[String] = sys.env.get("FORGE_IT_GH_REPO").filter(_.nonEmpty)
  private val ghOnPath: Boolean = onPath("gh")
  private val gitOnPath: Boolean = onPath("git")
  private val canRun: Boolean = ghRepo.isDefined && ghOnPath && gitOnPath

  private def onPath(binary: String): Boolean =
    sys.env.get("PATH").iterator.flatMap(_.split(":")).map(os.Path(_, os.pwd)).exists(p => os.exists(p / binary))

  /** Short hex suffix used in branch / feature names so parallel runs against the same repo don't collide. 10 hex chars
    * ≈ 40 bits — collision-free for any reasonable concurrent test run.
    */
  private def shortUid: String =
    val raw = java.util.UUID.randomUUID().toString.replace("-", "")
    raw.take(10).toLowerCase

  /** Git committer / author env so the test doesn't depend on the operator's global `git config`. The PR will show
    * these as the commit author.
    */
  private val gitIdentityEnv: Map[String, String] = Map(
    "GIT_AUTHOR_NAME" -> "Forge Integration Test",
    "GIT_AUTHOR_EMAIL" -> "forge-it@example.invalid",
    "GIT_COMMITTER_NAME" -> "Forge Integration Test",
    "GIT_COMMITTER_EMAIL" -> "forge-it@example.invalid"
  )

  private def gitCall(repoRoot: os.Path, argv: String*): Unit =
    val _ = os
      .proc("git", argv)
      .call(cwd = repoRoot, env = gitIdentityEnv, check = true, stderr = os.Pipe)

  private def cloneOrInit(repoSlug: String, workDir: os.Path): Unit =
    // `gh repo clone` inherits gh's auth posture; works for both HTTPS and SSH. Output is suppressed via os.Pipe so
    // the test log isn't drowned by clone progress.
    val res = os
      .proc("gh", "repo", "clone", repoSlug, workDir.toString)
      .call(check = false, stderr = os.Pipe)
    if res.exitCode != 0 then fail(s"gh repo clone failed (exit ${res.exitCode}): ${res.err.text()}")

  /** Ensure `main` exists on the remote with at least one commit. A freshly-created empty GitHub repo has no commits;
    * this seeds it once so the rest of the IT can fast-forward off `main`. Idempotent — if `main` already has commits,
    * this is a no-op.
    */
  private def bootstrapMainIfMissing(repoRoot: os.Path): Unit =
    val mainExists = os
      .proc("git", "ls-remote", "--heads", "origin", "main")
      .call(cwd = repoRoot, check = false, stderr = os.Pipe)
      .out
      .text()
      .trim
      .nonEmpty
    if !mainExists then
      // Empty-repo path: gh clone leaves us in a worktree with no commits. Create `main`, an initial commit, and
      // push -u origin main.
      gitCall(repoRoot, "checkout", "-b", "main")
      os.write.over(
        repoRoot / "README.md",
        "# forge-it sacrificial test-repo\n\nSeeded by BranchManagerIntegrationSuite.\n"
      )
      gitCall(repoRoot, "add", "README.md")
      gitCall(repoRoot, "commit", "-m", "chore: bootstrap main for forge-it")
      gitCall(repoRoot, "push", "-u", "origin", "main")
    else
      // Repo already had main; make sure we have it locally checked out (`gh repo clone` leaves us on the default
      // branch, which is main unless someone reconfigured the remote).
      gitCall(repoRoot, "fetch", "origin", "main")
      val current = os
        .proc("git", "branch", "--show-current")
        .call(cwd = repoRoot, check = true, stderr = os.Pipe)
        .out
        .text()
        .trim
      if current != "main" then gitCall(repoRoot, "checkout", "main")

  /** Re-poll `pollOnce` until either `state == Merged` or the budget expires. GitHub's API can take a few seconds to
    * reflect a merge — without the retry the test flakes ~1-in-5 against a warm `gh` cache.
    */
  private def pollUntilMerged(
      watcher: RealPRWatcher,
      pr: PrNumber,
      startBaseline: PollBaseline,
      timeout: FiniteDuration = 30.seconds,
      interval: FiniteDuration = 1.second
  ): IO[PollResult.Snapshot] =
    def go(deadline: Long, baseline: PollBaseline): IO[PollResult.Snapshot] =
      watcher.pollOnce(pr, baseline).flatMap {
        case s @ PollResult.Snapshot(decoded) if decoded.snapshot.state == PrState.Merged =>
          IO.pure(s)
        case PollResult.Snapshot(decoded) =>
          IO.monotonic.flatMap { now =>
            if now.toMillis >= deadline then
              IO.raiseError(
                AssertionError(
                  s"PR #${pr.value} did not reach Merged within $timeout (last state ${decoded.snapshot.state})"
                )
              )
            else IO.sleep(interval) *> go(deadline, decoded.nextBaseline)
          }
        case other =>
          IO.raiseError(AssertionError(s"unexpected PollResult while waiting for merge: $other"))
      }
    IO.monotonic.flatMap(now => go(now.toMillis + timeout.toMillis, startBaseline))

  test("G2: clone → sync → branch → push → PR → poll → merge → poll-merged against $FORGE_IT_GH_REPO"):
    assume(
      canRun,
      "skipped — set FORGE_IT_GH_REPO=<owner>/<repo> and ensure `gh` + `git` are on PATH " +
        "(see modules/forge-it/README.md for the sacrificial-repo setup)."
    )

    val repoSlug = ghRepo.get
    val uid = shortUid
    val workDir = os.temp.dir(prefix = s"forge-it-slice3-$uid-")

    try
      cloneOrInit(repoSlug, workDir)
      bootstrapMainIfMissing(workDir)

      val git = new RealGitClient(workDir)
      val gh = new RealGhClient(workDir)
      val watcher = new RealPRWatcher(gh, PRWatcherConfig())

      val branchPrefix = "forge-it/slice3"
      // FeatureId pattern requires a leading lowercase letter then [a-z0-9-]; "s3" prefix keeps the uid hex inside
      // the pattern. Resulting branch: forge-it/slice3/s3<uid>/p1.
      val feature = FeatureId(s"s3$uid")
      val piece = PieceId("p1")

      val program: IO[(PrNumber, PollResult.Snapshot, PollResult.Snapshot)] =
        for
          cache <- InMemoryBranchProtectionCache()
          bm = new RealBranchManager(git, gh, cache, cats.effect.Clock[IO])

          // 2. syncBase("main")
          base <- bm.syncBase(BranchName("main")).flatMap {
            case Right(snap) => IO.pure(snap)
            case Left(err) => IO.raiseError(AssertionError(s"syncBase(main) failed: $err"))
          }
          _ <- IO(assertEquals(base.base, BranchName("main")))

          // 3. createPieceBranch — branch derives as <branchPrefix>/<feature>/<piece>
          branchAndSha <- bm.createPieceBranch(feature, piece, branchPrefix, base).flatMap {
            case Right(pair) => IO.pure(pair)
            case Left(err) => IO.raiseError(AssertionError(s"createPieceBranch failed: $err"))
          }
          (branch, baseSha) = branchAndSha
          _ <- IO(assertEquals(baseSha, base.sha))
          _ <- IO(assert(branch.value == s"$branchPrefix/${feature.value}/${piece.value}", clue = branch.value))

          // 4. write a no-op marker, commit, push
          _ <- IO.blocking {
            val markerName = s"forge-it-$uid.txt"
            os.write.over(workDir / markerName, s"forge-it slice-3 IT marker uid=$uid\n")
            gitCall(workDir, "add", markerName)
            gitCall(workDir, "commit", "-m", s"feat: slice-3 IT $uid")
          }
          _ <- bm.pushCurrentBranch(forceWithLease = false).flatMap {
            case Right(_) => IO.unit
            case Left(err) => IO.raiseError(AssertionError(s"pushCurrentBranch failed: $err"))
          }

          // 5. createPr against main
          prNumber <- bm
            .createPr(
              title = s"forge-it slice-3 IT $uid",
              body = "Auto-opened by BranchManagerIntegrationSuite. Safe to ignore.",
              base = BranchName("main")
            )
            .flatMap {
              case Right(pr) => IO.pure(pr)
              case Left(err) => IO.raiseError(AssertionError(s"createPr failed: $err"))
            }

          // 6. pollOnce sees Open. We use the result's nextBaseline for step 8.
          openSnap <- watcher.pollOnce(prNumber, PollBaseline.empty).flatMap {
            case s: PollResult.Snapshot => IO.pure(s)
            case other => IO.raiseError(AssertionError(s"expected Snapshot from open poll, got $other"))
          }
          _ <- IO(assertEquals(openSnap.decoded.snapshot.state, PrState.Open))
          _ <- IO(assertEquals(openSnap.decoded.snapshot.number, prNumber))

          // 7. Drive the merge via the IT-only RealGhClient helper.
          _ <- gh.prMerge(prNumber, mode = "merge").flatMap {
            case Right(_) => IO.unit
            case Left(err) => IO.raiseError(AssertionError(s"prMerge #${prNumber.value} failed: $err"))
          }

          // 8. Poll until Merged (with the baseline threaded through from step 6).
          mergedSnap <- pollUntilMerged(watcher, prNumber, openSnap.decoded.nextBaseline)
          _ <- IO(assertEquals(mergedSnap.decoded.snapshot.state, PrState.Merged))
          _ <- IO(assert(mergedSnap.decoded.snapshot.mergedAt.isDefined, clue = mergedSnap.decoded.snapshot))
          _ <- IO(assert(mergedSnap.decoded.snapshot.mergeCommit.isDefined, clue = mergedSnap.decoded.snapshot))
        yield (prNumber, openSnap, mergedSnap)

      val (prNumber, _, _) = program.unsafeRunSync()
      // Diagnostic: surface the PR number on success so the operator can navigate to it if curious.
      println(s"[forge-it BranchManagerIntegrationSuite] merged $repoSlug#${prNumber.value} (branch left in place)")
    finally
      // Always remove the local clone — the remote branch + PR remain (G2 step 9 "the branch lingers ... can be
      // pruned out-of-band").
      try os.remove.all(workDir)
      catch case _: Throwable => ()
