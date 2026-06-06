package io.forge.app.command

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.app.cli.{CliError, CliParser, WorkerCommand}
import io.forge.core.{FeatureId, InstanceName}
import io.forge.daemon.{Daemon, DaemonState}
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, RebuildInstanceState}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.2.1 — the forge-app half of the worker-process boundary spike: the hidden `forge worker` entrypoint
  * ([[WorkerCommands]]) phones home to the instance socket (register → status → events) over the real JSON-RPC
  * transport against an **in-process** daemon. The other half (the daemon spawning a real OS child) is
  * `WorkerSpawnerSuite` in forge-daemon; the two tied together as a real `forge worker` child spawned by the daemon is
  * the Task 4.2.6 live dogfood.
  */
class WorkerCommandSuite extends CatsEffectSuite:

  private val featureId: FeatureId = FeatureId.fromString("add-feature").toOption.get

  /** A short `/tmp`-rooted home with a created instance (macOS caps `sun_path` at 104 bytes, so the socket under it
    * must be short), cleaned up after. Yields the `home` the entrypoint resolves the instance under plus the resolved
    * [[Instance]] the in-process daemon serves.
    */
  private val fixture: Resource[IO, (os.Path, Instance)] =
    Resource.make {
      val home = os.Path("/tmp") / s"fw-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure((home, inst))
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    } { case (home, _) => IO.blocking(os.remove.all(home)).void }

  /** Boot a daemon serving its socket for the body, tearing the serve down afterwards (the `served` idiom from
    * `DaemonWorkerSubscribeSuite`).
    */
  private def served[A](inst: Instance)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  test("forge worker phones home: registers, reports status, exports events into the instance log") {
    fixture.use { case (home, inst) =>
      val command = WorkerCommand(InstanceName("demo"), workerId = "w1", repo = "/repo", feature = featureId)
      for
        exit <- served(inst)(WorkerCommands.run(home, command))
        // The log is canonical: rebuilding from it alone reproduces the worker, its reported status, and its 2 events.
        rebuilt <- FileInstanceLog(inst).flatMap(_.replay).map(RebuildInstanceState.fold)
      yield
        assertEquals(exit, ExitCode.Success)
        val w = rebuilt.worker("w1").getOrElse(fail("worker w1 missing from the rebuilt instance log"))
        assertEquals(w.feature, featureId)
        assertEquals(w.repo, "/repo")
        assertEquals(w.status, "Refining")
        assertEquals(w.events.map(_.obj("kind").str), Vector("spawned", "heartbeat"))
    }
  }

  test("forge worker against an absent instance exits 1, not a crash") {
    fixture.use { case (home, _) =>
      // No daemon is served and the instance name does not exist under `home`.
      val command = WorkerCommand(InstanceName("ghost"), workerId = "w1", repo = "/repo", feature = featureId)
      WorkerCommands.run(home, command).map(exit => assertEquals(exit, ExitCode(1)))
    }
  }

  test("forge worker when no daemon is listening exits 1, not a crash") {
    fixture.use { case (home, _) =>
      // The instance exists but no daemon is serving its socket, so the connect fails and the handler degrades.
      val command = WorkerCommand(InstanceName("demo"), workerId = "w1", repo = "/repo", feature = featureId)
      WorkerCommands.run(home, command).map(exit => assertEquals(exit, ExitCode(1)))
    }
  }

  test("parseWorker requires every flag") {
    assertEquals(
      CliParser.parseWorker(Vector("--worker-id", "w1", "--repo", "/r", "--feature", "f")),
      Left(CliError.MissingWorkerFlag("--instance"))
    )
    assertEquals(
      CliParser.parseWorker(Vector("--instance", "demo", "--repo", "/r", "--feature", "f")),
      Left(CliError.MissingWorkerFlag("--worker-id"))
    )
    val ok = CliParser.parseWorker(
      Vector("--instance", "demo", "--worker-id", "w1", "--repo", "/r", "--feature", "add-feature")
    )
    assertEquals(ok, Right(WorkerCommand(InstanceName("demo"), "w1", "/r", featureId)))
  }
