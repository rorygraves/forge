package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.app.cli.InstanceCommand
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceStore, Instance, RegisteredRepo}

/** Task 4.0.3 — behaviour of the instance-registry command handlers over a real temp `home`.
  *
  * Asserts on the durable effects (instance dir / registry contents) + exit codes rather than captured stdout,
  * mirroring `RunNewHandlerSuite`'s "assert the side effect, not the console text" idiom. Each handler runs through the
  * real instance-level [[io.forge.app.lock.FileProcessLock]], so the lock wiring is exercised same-JVM here
  * (cross-process contention lives in `forge-it`'s lock suite).
  */
class InstanceCommandsSuite extends munit.FunSuite:

  private val tempHome = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-instance-cmd-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def run(home: os.Path, command: InstanceCommand): ExitCode =
    InstanceCommands.run(home, command).unsafeRunSync()

  /** A throwaway directory that looks like a git working tree. */
  private def fakeGitRepo(parent: os.Path, name: String): os.Path =
    val repo = parent / name
    os.makeDir.all(repo / ".git")
    repo

  // --- init-instance ---------------------------------------------------------

  tempHome.test("init-instance creates the instance dir + config + registry"): home =>
    val name = InstanceName("demo")
    assertEquals(run(home, InstanceCommand.InitInstance(name)), ExitCode.Success)

    val instance = Instance.at(name, home)
    assert(os.exists(instance.configFile), "config.json should exist")
    assert(os.exists(instance.reposFile), "repos.json should exist")
    assert(os.isDir(instance.workersDir), "workers/ should exist")

  tempHome.test("init-instance twice → exit 1 (DuplicateInstance)"): home =>
    val name = InstanceName("demo")
    assertEquals(run(home, InstanceCommand.InitInstance(name)), ExitCode.Success)
    assertEquals(run(home, InstanceCommand.InitInstance(name)), ExitCode(1))

  // --- add-repo --------------------------------------------------------------

  tempHome.test("add-repo with explicit --instance registers a git working tree"): home =>
    val name = InstanceName("demo")
    run(home, InstanceCommand.InitInstance(name))
    val repo = fakeGitRepo(home, "project")

    assertEquals(run(home, InstanceCommand.AddRepo(Some(name), repo.toString)), ExitCode.Success)
    assertEquals(
      new FileInstanceStore(home).listRepos(Instance.at(name, home)).unsafeRunSync(),
      Right(Vector(RegisteredRepo(repo.toString)))
    )

  tempHome.test("add-repo resolves the sole instance when --instance is omitted"): home =>
    val name = InstanceName("solo")
    run(home, InstanceCommand.InitInstance(name))
    val repo = fakeGitRepo(home, "project")

    assertEquals(run(home, InstanceCommand.AddRepo(None, repo.toString)), ExitCode.Success)
    assertEquals(
      new FileInstanceStore(home).listRepos(Instance.at(name, home)).unsafeRunSync().map(_.map(_.path)),
      Right(Vector(repo.toString))
    )

  tempHome.test("add-repo with no instances and no flag → exit 1"): home =>
    val repo = fakeGitRepo(home, "project")
    assertEquals(run(home, InstanceCommand.AddRepo(None, repo.toString)), ExitCode(1))

  tempHome.test("add-repo with multiple instances and no flag → exit 1 (ambiguous)"): home =>
    run(home, InstanceCommand.InitInstance(InstanceName("alpha")))
    run(home, InstanceCommand.InitInstance(InstanceName("beta")))
    val repo = fakeGitRepo(home, "project")
    assertEquals(run(home, InstanceCommand.AddRepo(None, repo.toString)), ExitCode(1))

  tempHome.test("add-repo on a non-git path → exit 1 (NotAGitRepo)"): home =>
    val name = InstanceName("demo")
    run(home, InstanceCommand.InitInstance(name))
    val plain = home / "plain"
    os.makeDir.all(plain)
    assertEquals(run(home, InstanceCommand.AddRepo(Some(name), plain.toString)), ExitCode(1))

  tempHome.test("add-repo against an unknown --instance → exit 1 (NoSuchInstance)"): home =>
    val repo = fakeGitRepo(home, "project")
    assertEquals(run(home, InstanceCommand.AddRepo(Some(InstanceName("ghost")), repo.toString)), ExitCode(1))

  tempHome.test("add-repo of a duplicate → exit 1 (DuplicateRepo)"): home =>
    val name = InstanceName("demo")
    run(home, InstanceCommand.InitInstance(name))
    val repo = fakeGitRepo(home, "project")
    assertEquals(run(home, InstanceCommand.AddRepo(Some(name), repo.toString)), ExitCode.Success)
    assertEquals(run(home, InstanceCommand.AddRepo(Some(name), repo.toString)), ExitCode(1))

  // --- list-repos ------------------------------------------------------------

  tempHome.test("list-repos succeeds on an empty registry and after registration"): home =>
    val name = InstanceName("demo")
    run(home, InstanceCommand.InitInstance(name))
    assertEquals(run(home, InstanceCommand.ListRepos(Some(name))), ExitCode.Success)

    run(home, InstanceCommand.AddRepo(Some(name), fakeGitRepo(home, "project").toString))
    assertEquals(run(home, InstanceCommand.ListRepos(None)), ExitCode.Success)

  tempHome.test("list-repos with no instances → exit 1"): home =>
    assertEquals(run(home, InstanceCommand.ListRepos(None)), ExitCode(1))
