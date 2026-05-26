package io.forge.git.cli

import cats.effect.unsafe.implicits.global
import io.forge.core.BranchName

/** PR-A A3 fix — `RealGitClient.fastForwardBase` against a hand-rolled local two-repo setup. Covers the three cases the
  * design contract names, but specifically pins the **bootstrap path** flagged in PR-A review (missing local
  * `refs/heads/<base>` on a fresh clone must update-ref to the remote, not refuse).
  *
  * Each test sets up a "remote" bare repo, makes one or more commits, clones into a "local" working copy, then
  * exercises `fastForwardBase`. Total runtime stays well under the <60s default-on budget; the suite skips itself if
  * `git` isn't on `PATH` (mirroring `forge-it` pattern).
  */
class RealGitClientFastForwardSuite extends munit.FunSuite:

  private def hasGit: Boolean =
    try os.proc("git", "--version").call(check = false).exitCode == 0
    catch case _: Throwable => false

  override def munitTests(): Seq[munit.Test] =
    if hasGit then super.munitTests()
    else Seq.empty

  private val fixture = FunFixture[(os.Path, os.Path)](
    setup = _ =>
      val root = os.temp.dir(prefix = "forge-ff-")
      val bare = root / "remote.git"
      val work = root / "work"
      // Pin the default branch so this suite passes regardless of the host machine's `init.defaultBranch` config.
      os.proc("git", "-c", "init.defaultBranch=main", "init", "--bare", bare.toString)
        .call(cwd = root, stderr = os.Pipe)
      os.proc("git", "-C", bare.toString, "symbolic-ref", "HEAD", "refs/heads/main").call(stderr = os.Pipe)
      // Seed the bare repo with one commit on main via an intermediate seeder clone.
      val seeder = root / "seed"
      os.proc("git", "clone", bare.toString, seeder.toString).call(cwd = root, stderr = os.Pipe)
      os.proc("git", "-C", seeder.toString, "checkout", "-B", "main").call(stderr = os.Pipe)
      os.write(seeder / "README.md", "hello\n")
      os.proc("git", "-C", seeder.toString, "add", ".").call(stderr = os.Pipe)
      os.proc(
        "git",
        "-c",
        "user.email=t@example.com",
        "-c",
        "user.name=t",
        "-C",
        seeder.toString,
        "commit",
        "-m",
        "init"
      ).call(stderr = os.Pipe)
      os.proc("git", "-C", seeder.toString, "push", "origin", "main").call(stderr = os.Pipe)
      // Fresh clone into the working tree, explicit branch so the local `refs/heads/main` exists.
      os.proc("git", "clone", "--branch", "main", bare.toString, work.toString)
        .call(cwd = root, stderr = os.Pipe)
      (root, work)
    ,
    teardown = (root, _) => if os.exists(root) then os.remove.all(root)
  )

  fixture.test("AlreadyUpToDate — no change between local main and origin/main"):
    case (_, work) =>
      val client = RealGitClient(work)
      val res = client.fastForwardBase(BranchName("main")).unsafeRunSync()
      res match
        case Right(_: FastForwardResult.AlreadyUpToDate) => ()
        case other => fail(s"expected AlreadyUpToDate, got $other")

  fixture.test("bootstrap — missing local refs/heads/<base> on a fresh clone gets update-ref'd"):
    case (root, work) =>
      // Push a `release/1.0` branch from the seeder so the bare has it...
      val seeder = root / "seed"
      os.proc("git", "-C", seeder.toString, "checkout", "-B", "release/1.0").call(stderr = os.Pipe)
      os.proc("git", "-C", seeder.toString, "push", "origin", "release/1.0").call(stderr = os.Pipe)
      val client = RealGitClient(work)
      // ...but the local clone hasn't checked it out, so `refs/heads/release/1.0` is missing.
      val branch = BranchName("release/1.0")
      val preExists = client.branchExistsLocal(branch).unsafeRunSync()
      assertEquals(preExists, Right(false), "precondition: local ref must not exist before bootstrap")
      // fastForwardBase should bootstrap it from the remote, not refuse.
      val res = client.fastForwardBase(branch).unsafeRunSync()
      res match
        case Right(_: FastForwardResult.Updated) => ()
        case other => fail(s"expected Updated (bootstrap), got $other")
      val postExists = client.branchExistsLocal(branch).unsafeRunSync()
      assertEquals(postExists, Right(true), "local ref must exist after bootstrap")
