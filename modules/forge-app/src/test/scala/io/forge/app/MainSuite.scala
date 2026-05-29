package io.forge.app

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global

/** Task 1.4.9 I2 — end-to-end boot wiring for [[Main]] over a real temp repo. Asserts the per-command-class routing,
  * exit codes, and that the state-changing path acquires and *releases* the process lock around its (shell) handler.
  */
class MainSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-main-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def run(repo: os.Path, args: String*): ExitCode =
    Main.run("--repo-root" :: repo.toString :: args.toList).unsafeRunSync()

  test("a usage error exits 64") {
    assertEquals(Main.run(List("frobnicate")).unsafeRunSync(), ExitCode(64))
  }

  test("a missing --repo-root directory exits 66") {
    assertEquals(Main.run(List("--repo-root", "/no/such/forge/dir", "status")).unsafeRunSync(), ExitCode(66))
  }

  tempFixture.test("unlock --force succeeds when no lock is present") { repo =>
    assertEquals(run(repo, "unlock", "--force"), ExitCode.Success)
  }

  tempFixture.test("unlock without --force is a usage error") { repo =>
    assertEquals(run(repo, "unlock"), ExitCode(64))
  }

  tempFixture.test("a read-only shell routes and exits 70 (not implemented)") { repo =>
    assertEquals(run(repo, "status"), ExitCode(70))
  }

  tempFixture.test("a state-changing command acquires then releases the lock around its handler") { repo =>
    // `forge run` on an undesigned feature exits 1 (no manifest yet); the point is the lock bracket — the handler runs
    // inside the lock Resource and must release it so a second run re-acquires (rather than seeing a held/stale lock).
    assertEquals(run(repo, "run", "my-feat"), ExitCode(1))
    assertEquals(run(repo, "run", "my-feat"), ExitCode(1))
  }

  tempFixture.test("config is loaded for non-unlock commands; a malformed config exits 78") { repo =>
    os.write.over(repo / ".forge" / "config.json", "{ not json", createFolders = true)
    assertEquals(run(repo, "status"), ExitCode(78))
    // unlock --force must still work with a broken config (recovery path skips config load)
    assertEquals(run(repo, "unlock", "--force"), ExitCode.Success)
  }
