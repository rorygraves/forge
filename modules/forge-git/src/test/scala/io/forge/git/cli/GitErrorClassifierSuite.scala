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

  // --- Task 1.4.10-d2a: commit classifier ---

  test("classifyCommit: exit 0 → Committed"):
    assertEquals(RealGitClient.classifyCommit(0, "[main abc123] msg", ""), Right(CommitResult.Committed))

  test("classifyCommit: clean tree ('nothing to commit') on stdout → NothingToCommit"):
    val stdout = "On branch main\nnothing to commit, working tree clean\n"
    assertEquals(RealGitClient.classifyCommit(1, stdout, ""), Right(CommitResult.NothingToCommit))

  test("classifyCommit: 'no changes added to commit' phrasing → NothingToCommit"):
    val stdout = "On branch main\nno changes added to commit (use \"git add\" ...)\n"
    assertEquals(RealGitClient.classifyCommit(1, stdout, ""), Right(CommitResult.NothingToCommit))

  test("classifyCommit: other non-zero → Transient"):
    RealGitClient.classifyCommit(128, "", "fatal: not a git repository") match
      case Left(GitError.Transient(128, _)) => ()
      case other => fail(s"expected Transient(128, ...), got $other")

  // --- Task 1.4.10-d2a: porcelain -z status parser ---

  private val NUL: Char = 0.toChar

  test("parseStatusZ: empty input → no entries"):
    assertEquals(RealGitClient.parseStatusZ(""), Vector.empty)

  test("parseStatusZ: a single modified file"):
    val entries = RealGitClient.parseStatusZ(s" M keep.txt$NUL")
    assertEquals(entries, Vector(StatusEntry(' ', 'M', "keep.txt", None, ignored = false)))

  test("parseStatusZ: untracked file"):
    val entries = RealGitClient.parseStatusZ(s"?? c.txt$NUL")
    assertEquals(entries, Vector(StatusEntry('?', '?', "c.txt", None, ignored = false)))

  test("parseStatusZ: rename consumes the following token as origPath (new path first)"):
    val entries = RealGitClient.parseStatusZ(s"R  b.txt${NUL}a.txt$NUL")
    assertEquals(entries, Vector(StatusEntry('R', ' ', "b.txt", Some("a.txt"), ignored = false)))

  test("parseStatusZ: ignored entry (!!) sets the ignored bit"):
    val entries = RealGitClient.parseStatusZ(s"!! node_modules/$NUL")
    assertEquals(entries, Vector(StatusEntry('!', '!', "node_modules/", None, ignored = true)))

  test("parseStatusZ: realistic mixed change set (pins the real `git status --porcelain -z` shape)"):
    val raw = s"R  b.txt${NUL}a.txt$NUL M keep.txt$NUL?? .gitignore$NUL?? c.txt$NUL!! node_modules/$NUL"
    assertEquals(
      RealGitClient.parseStatusZ(raw),
      Vector(
        StatusEntry('R', ' ', "b.txt", Some("a.txt"), ignored = false),
        StatusEntry(' ', 'M', "keep.txt", None, ignored = false),
        StatusEntry('?', '?', ".gitignore", None, ignored = false),
        StatusEntry('?', '?', "c.txt", None, ignored = false),
        StatusEntry('!', '!', "node_modules/", None, ignored = true)
      )
    )
