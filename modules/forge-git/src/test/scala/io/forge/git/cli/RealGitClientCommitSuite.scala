package io.forge.git.cli

import cats.effect.unsafe.implicits.global

/** Task 1.4.10-d2a — `RealGitClient.stage` / `commit` / `status` against a real `git` binary in a throwaway repo. The
  * seam these add (working-tree staging + structured porcelain status + clean-tree-aware commit) is what the §11.4 /
  * §11.6 `ChangeCollector` → commit → push flow (Task 1.4.10-d2b) builds on, so the real `git` wire shape is pinned
  * here rather than inferred.
  *
  * Mirrors [[RealGitClientFastForwardSuite]]: a `FunFixture` builds a fresh repo with one seed commit, the suite skips
  * itself if `git` isn't on `PATH`, and runtime stays well under the <60s default-on budget.
  */
class RealGitClientCommitSuite extends munit.FunSuite:

  private def hasGit: Boolean =
    try os.proc("git", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  override def munitTests(): Seq[munit.Test] =
    if hasGit then super.munitTests()
    else Seq.empty

  /** A fresh repo seeded with `keep.txt`, `del.txt`, `ren.txt` on one commit, with a repo-local committer identity (so
    * the identity-neutral `RealGitClient.commit` finds ambient config).
    */
  private val fixture = FunFixture[os.Path](
    setup = _ =>
      val work = os.temp.dir(prefix = "forge-commit-")
      os.proc("git", "-c", "init.defaultBranch=main", "init").call(cwd = work, stderr = os.Pipe)
      os.proc("git", "config", "user.email", "t@example.com").call(cwd = work, stderr = os.Pipe)
      os.proc("git", "config", "user.name", "t").call(cwd = work, stderr = os.Pipe)
      os.write(work / "keep.txt", "keep\n")
      os.write(work / "del.txt", "delete me\n")
      os.write(work / "ren.txt", "rename me\n")
      os.proc("git", "add", "-A").call(cwd = work, stderr = os.Pipe)
      os.proc("git", "commit", "-m", "seed").call(cwd = work, stderr = os.Pipe)
      work
    ,
    teardown = work => if os.exists(work) then os.remove.all(work)
  )

  fixture.test("stage + commit → Committed, and currentSha advances"): work =>
    val client = RealGitClient(work)
    val before = client.currentSha.unsafeRunSync().getOrElse(fail("currentSha before"))
    os.write(work / "c.txt", "new\n")
    assertEquals(client.stage(Vector("c.txt")).unsafeRunSync(), Right(()))
    assertEquals(client.commit("add c.txt").unsafeRunSync(), Right(CommitResult.Committed))
    val after = client.currentSha.unsafeRunSync().getOrElse(fail("currentSha after"))
    assertNotEquals(before, after, "HEAD must move after a real commit")

  fixture.test("stage force-adds a gitignored-but-allowed path (Forge's .forge/specs source of truth)"): work =>
    // The target repo gitignores `.forge/` so it doesn't dirty the worktree, but Forge force-includes its own
    // `.forge/specs/...` source of truth into the design/piece commits (§10.1 rule 4). Without `-f`, `git add` refuses.
    val client = RealGitClient(work)
    os.write(work / ".gitignore", ".forge/\n")
    os.write(work / ".forge" / "specs" / "feat" / "manifest.json", "{}\n", createFolders = true)
    assertEquals(client.stage(Vector(".gitignore", ".forge/specs/feat/manifest.json")).unsafeRunSync(), Right(()))
    assertEquals(client.commit("add design assets").unsafeRunSync(), Right(CommitResult.Committed))
    // The ignored-but-forced file is now tracked (shows in HEAD), proving the force-add worked.
    val tracked = os.proc("git", "ls-files", ".forge/specs/feat/manifest.json").call(cwd = work).out.text().trim
    assertEquals(tracked, ".forge/specs/feat/manifest.json")

  fixture.test("commit on a clean tree → NothingToCommit (not an error)"): work =>
    val client = RealGitClient(work)
    // The seed commit already captured everything; nothing is staged.
    assertEquals(client.commit("noop").unsafeRunSync(), Right(CommitResult.NothingToCommit))

  fixture.test("stage(empty) is a no-op Right(()) and stages nothing"): work =>
    val client = RealGitClient(work)
    os.write(work / "c.txt", "new\n")
    assertEquals(client.stage(Vector.empty).unsafeRunSync(), Right(()))
    // c.txt must still be untracked, never staged.
    val entries = client.status().unsafeRunSync().getOrElse(fail("status"))
    assertEquals(entries.map(_.path).toSet, Set("c.txt"))
    assertEquals(entries.head.index, '?')

  fixture.test("status reports Modified / Deleted / Untracked worktree changes"): work =>
    val client = RealGitClient(work)
    os.write.append(work / "keep.txt", "more\n")
    os.remove(work / "del.txt")
    os.write(work / "c.txt", "new\n")
    val byPath = client.status().unsafeRunSync().getOrElse(fail("status")).map(e => e.path -> e).toMap
    assertEquals(byPath("keep.txt").worktree, 'M')
    assertEquals(byPath("del.txt").worktree, 'D')
    assertEquals(byPath("c.txt").index, '?')
    assert(byPath.values.forall(!_.ignored), "no entry should be ignored without .gitignore")

  fixture.test("status reports a staged rename with the source in origPath"): work =>
    val client = RealGitClient(work)
    os.move(work / "ren.txt", work / "ren2.txt")
    // Stage both sides so git records it as a rename (R) rather than delete + untracked.
    assertEquals(client.stage(Vector("ren.txt", "ren2.txt")).unsafeRunSync(), Right(()))
    val entries = client.status().unsafeRunSync().getOrElse(fail("status"))
    val rename = entries.find(_.index == 'R').getOrElse(fail(s"expected a rename entry, got $entries"))
    assertEquals(rename.path, "ren2.txt")
    assertEquals(rename.origPath, Some("ren.txt"))

  fixture.test("ignored files appear only under status(includeIgnored = true) with the ignored bit set"): work =>
    val client = RealGitClient(work)
    os.write(work / ".gitignore", "ignored/\n")
    os.makeDir(work / "ignored")
    os.write(work / "ignored" / "x.txt", "secret\n")
    val plain = client.status().unsafeRunSync().getOrElse(fail("status plain"))
    assert(!plain.exists(_.ignored), s"plain status must not surface ignored entries: $plain")
    assert(!plain.exists(_.path.startsWith("ignored")), "ignored dir must be absent from plain status")
    val withIgnored = client.status(includeIgnored = true).unsafeRunSync().getOrElse(fail("status --ignored"))
    val ign = withIgnored.find(_.ignored).getOrElse(fail(s"expected an ignored entry, got $withIgnored"))
    assert(ign.path.startsWith("ignored"), s"ignored entry path was ${ign.path}")
