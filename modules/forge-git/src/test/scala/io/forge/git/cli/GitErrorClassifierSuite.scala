package io.forge.git.cli

import io.forge.core.BranchName

/** PR-A A3 — `RealGitClient.classifyPush` smoke. The push classifier is the single place where the §11.3 step 5
  * force-with-lease-rejected stderr framing gets pinned to a typed `GitError`, so a real-world `git push` stderr blob
  * has to keep round-tripping cleanly.
  */
class GitErrorClassifierSuite extends munit.FunSuite:

  private val branch = BranchName("forge/feat/p1")

  test("exit 0 → Right(())"):
    val r = RealGitClient.classifyPush(branch, 0, "", "")
    assertEquals(r, Right(()))

  test("force-with-lease 'stale info' phrasing → ForceLeaseRejected"):
    val stderr =
      """ ! [rejected]    forge/feat/p1 -> forge/feat/p1 (stale info)
        |error: failed to push some refs to 'github.com:owner/repo.git'""".stripMargin
    RealGitClient.classifyPush(branch, 1, "", stderr) match
      case Left(GitError.ForceLeaseRejected(b, _)) => assertEquals(b, branch)
      case other => fail(s"expected ForceLeaseRejected, got $other")

  test("force-with-lease 'non-fast-forward' phrasing → ForceLeaseRejected"):
    val stderr =
      """ ! [rejected]    forge/feat/p1 -> forge/feat/p1 (non-fast-forward)
        |error: failed to push some refs""".stripMargin
    RealGitClient.classifyPush(branch, 1, "", stderr) match
      case Left(_: GitError.ForceLeaseRejected) => ()
      case other => fail(s"expected ForceLeaseRejected, got $other")

  test("no-upstream phrasing → NoUpstream"):
    val stderr = "fatal: The current branch forge/feat/p1 has no upstream branch.\n" +
      "To push the current branch and set the remote as upstream, use\n" +
      "    git push --set-upstream origin forge/feat/p1"
    RealGitClient.classifyPush(branch, 128, "", stderr) match
      case Left(GitError.NoUpstream(b)) => assertEquals(b, branch)
      case other => fail(s"expected NoUpstream, got $other")

  test("unknown non-zero exit → Transient"):
    RealGitClient.classifyPush(branch, 128, "", "weird git error") match
      case Left(GitError.Transient(128, _)) => ()
      case other => fail(s"expected Transient(128, ...), got $other")
