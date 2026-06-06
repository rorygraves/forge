package io.forge.app.command

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.app.cli.{CliError, CliParser, WorkerCommand}
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.{FeatureId, InstanceName}
import io.forge.core.paths.ForgePaths
import io.forge.daemon.{Daemon, DaemonState}
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, RebuildInstanceState}
import munit.CatsEffectSuite

import java.time.Instant
import java.util.UUID

/** Task 4.2.1 / 4.2.3 — the hidden `forge worker` entrypoint ([[WorkerCommands]]).
  *
  * 4.2.1 proved the phone-home half (register → status → events over the real JSON-RPC transport against an in-process
  * daemon). 4.2.3 rewires the entrypoint onto its isolated clone: it now resolves the worker's re-rooted clone
  * [[io.forge.core.paths.ForgePaths]], loads the clone config, and drives the v1 loop via [[WorkerLoop]]. This suite
  * covers the entrypoint's resolution + degradation contract without real CLIs (a clone with no designed feature
  * short-circuits at manifest load); the feed exporter is unit-tested in [[WorkerFeedExporterSuite]], and the full
  * daemon-spawns-a-real-child-on-a-provisioned-clone tie-together is the Task 4.2.6 live dogfood.
  */
class WorkerCommandSuite extends CatsEffectSuite:

  private val featureId: FeatureId = FeatureId.fromString("add-feature").toOption.get

  /** A short `/tmp`-rooted home (macOS caps `sun_path` at 104 bytes) with a created instance, cleaned up after. */
  private val fixture: Resource[IO, (os.Path, Instance)] =
    Resource.make {
      val home = os.Path("/tmp") / s"fw-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure((home, inst))
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    } { case (home, _) => IO.blocking(os.remove.all(home)).void }

  /** Boot a daemon serving its socket for the body, tearing the serve down afterwards. */
  private def served[A](inst: Instance)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  /** Create the (empty) clone the supervisor would have provisioned, so the entrypoint gets past its checkout-exists
    * guard and reaches the loop. Empty (no `.git`, no designed feature) is enough for the manifest-missing path.
    */
  private def seedCheckout(inst: Instance): IO[Unit] =
    IO.blocking(os.makeDir.all(inst.workerCheckout("w1"))).void

  private def command: WorkerCommand =
    WorkerCommand(InstanceName("demo"), workerId = "w1", repo = "/repo", feature = featureId)

  test("a provisioned clone with no designed feature: registers, reports Abandoned, exits 1") {
    fixture.use { case (home, inst) =>
      for
        _ <- seedCheckout(inst)
        exit <- served(inst)(WorkerCommands.run(home, command))
        rebuilt <- FileInstanceLog(inst).flatMap(_.replay).map(RebuildInstanceState.fold)
      yield
        assertEquals(exit, ExitCode(1))
        // The worker registered (the instance log carries it) before degrading on the missing manifest.
        val w = rebuilt.worker("w1").getOrElse(fail("worker w1 missing from the rebuilt instance log"))
        assertEquals(w.feature, featureId)
        assertEquals(w.repo, "/repo")
        assertEquals(w.status, "Abandoned")
    }
  }

  test("a missing clone (the supervisor never provisioned it) exits 1, not a crash") {
    fixture.use { case (home, inst) =>
      // No checkout under workers/w1/, so the entrypoint cannot resolve its clone.
      served(inst)(WorkerCommands.run(home, command)).map(exit => assertEquals(exit, ExitCode(1)))
    }
  }

  test("forge worker against an absent instance exits 1, not a crash") {
    fixture.use { case (home, _) =>
      val ghost = WorkerCommand(InstanceName("ghost"), workerId = "w1", repo = "/repo", feature = featureId)
      WorkerCommands.run(home, ghost).map(exit => assertEquals(exit, ExitCode(1)))
    }
  }

  test("forge worker when no daemon is listening exits 1, not a crash") {
    fixture.use { case (home, inst) =>
      // Clone present but no daemon serving the socket: the register RPC retries briefly, then the loop degrades.
      seedCheckout(inst) *> WorkerCommands.run(home, command).map(exit => assertEquals(exit, ExitCode(1)))
    }
  }

  test("a held per-worker lock refuses a duplicate forge worker (exit 2), never sharing the re-rooted log") {
    fixture.use { case (home, inst) =>
      // The worker's re-rooted paths: repoRoot = the clone, localRoot = the worker root (where the §13 lock lives).
      val paths = new ForgePaths(inst.workerCheckout("w1"), home, localRootOpt = Some(inst.workerRoot("w1")))
      val held = LockMetadata(
        pid = 4242L,
        hostname = "other-host",
        startedAt = Instant.EPOCH,
        command = "forge worker w1 (other)",
        feature = Some(featureId)
      )
      // Pre-hold the lock from a distinct FileProcessLock instance (the cross-instance same-JVM contention the docs
      // describe surfaces as Held); the worker entrypoint must refuse rather than run a second writer on the same log.
      seedCheckout(inst) *>
        new FileProcessLock(paths).acquire(held, acceptStale = false).use {
          case LockAcquireResult.Acquired =>
            WorkerCommands.run(home, command).map(exit => assertEquals(exit, ExitCode(2)))
          case other => IO(fail(s"could not pre-acquire the worker lock for the test: $other"))
        }
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
