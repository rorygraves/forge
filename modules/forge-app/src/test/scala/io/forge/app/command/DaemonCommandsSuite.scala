package io.forge.app.command

import cats.effect.{ExitCode, IO, Resource}
import io.forge.app.cli.DaemonCommand
import io.forge.core.InstanceName
import io.forge.daemon.{DaemonClient, JsonRpc}
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.1.3 — the `forge daemon start | stop | status` CLI lifecycle over a real instance lock + Unix socket.
  *
  * `start` is foreground (it blocks holding the instance lock), so the lifecycle tests fork it and drive `status` /
  * `stop` as separate client calls — exactly the two-terminal shape an operator uses. Exit codes are the assertion
  * surface (mirroring [[InstanceCommandsSuite]]'s "assert the outcome, not the console text").
  */
class DaemonCommandsSuite extends CatsEffectSuite:

  private val name = InstanceName("demo")

  /** A short `/tmp`-rooted `home` with the `demo` instance created (macOS `sun_path` 104-byte cap → not `os.temp.dir`),
    * cleaned up after the test. Yields the `home` and the resolved [[Instance]].
    */
  private val fixture: Resource[IO, (os.Path, Instance)] =
    Resource.make {
      val home = os.Path("/tmp") / s"fda-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(name).flatMap {
        case Right(inst) => IO.pure((home, inst))
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    } { case (home, _) => IO.blocking(os.remove.all(home)).void }

  /** Wait for the daemon to bind its socket (the foreground `start` boots asynchronously to our fork). */
  private def awaitReady(inst: Instance): IO[Unit] =
    DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(0L, "status")).void

  test("start → status → stop lifecycle returns success at every step") {
    fixture.use { case (home, inst) =>
      DaemonCommands.run(home, DaemonCommand.Start(Some(name))).background.use { startOutcome =>
        for
          _ <- awaitReady(inst)
          statusExit <- DaemonCommands.run(home, DaemonCommand.Status(Some(name)))
          stopExit <- DaemonCommands.run(home, DaemonCommand.Stop(Some(name)))
          startExit <- startOutcome.flatMap(_.embedNever)
        yield
          assertEquals(statusExit, ExitCode.Success)
          assertEquals(stopExit, ExitCode.Success)
          assertEquals(startExit, ExitCode.Success)
      }
    }
  }

  test("a second start while one holds the instance lock is refused (exit 2)") {
    fixture.use { case (home, inst) =>
      DaemonCommands.run(home, DaemonCommand.Start(Some(name))).background.use { startOutcome =>
        for
          _ <- awaitReady(inst)
          secondExit <- DaemonCommands.run(home, DaemonCommand.Start(Some(name)))
          _ <- DaemonCommands.run(home, DaemonCommand.Stop(Some(name)))
          _ <- startOutcome.flatMap(_.embedNever)
        yield assertEquals(secondExit, ExitCode(2))
      }
    }
  }

  test("status against no running daemon → exit 1") {
    fixture.use { case (home, _) =>
      DaemonCommands.run(home, DaemonCommand.Status(Some(name))).map(assertEquals(_, ExitCode(1)))
    }
  }

  test("stop against no running daemon → exit 1") {
    fixture.use { case (home, _) =>
      DaemonCommands.run(home, DaemonCommand.Stop(Some(name))).map(assertEquals(_, ExitCode(1)))
    }
  }

  test("daemon command against an unknown instance → exit 1") {
    fixture.use { case (home, _) =>
      DaemonCommands.run(home, DaemonCommand.Status(Some(InstanceName("ghost")))).map(assertEquals(_, ExitCode(1)))
    }
  }
