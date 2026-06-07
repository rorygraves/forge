package io.forge.app.command

import cats.effect.{ExitCode, IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.app.cli.{DaemonCommand, WorkstreamCommand}
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.daemon.{Daemon, DaemonClient, DaemonState, JsonRpc, Supervisor}
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.2.4 — the `forge workstream new | list` and `forge worker list` operator client commands over a real running
  * daemon. Mirrors [[DaemonCommandsSuite]]: fork the foreground `daemon start`, then drive the client commands as
  * separate calls; exit codes are the assertion surface. A command with no daemon running degrades to exit 1.
  */
class WorkstreamCommandsSuite extends CatsEffectSuite:

  private val name = InstanceName("demo")

  private val fixture: Resource[IO, (os.Path, Instance)] =
    Resource.make {
      val home = os.Path("/tmp") / s"fws-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(name).flatMap {
        case Right(inst) => IO.pure((home, inst))
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    } { case (home, _) => IO.blocking(os.remove.all(home)).void }

  private def awaitReady(inst: Instance): IO[Unit] =
    DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(0L, "status")).void

  test("new → list → worker list against a running daemon all succeed") {
    fixture.use { case (home, inst) =>
      DaemonCommands.run(home, DaemonCommand.Start(Some(name))).background.use { startOutcome =>
        for
          _ <- awaitReady(inst)
          newExit <- WorkstreamCommands.run(home, WorkstreamCommand.New(Some(name), "add auth"))
          listExit <- WorkstreamCommands.run(home, WorkstreamCommand.List(Some(name)))
          workerListExit <- WorkstreamCommands.run(home, WorkstreamCommand.WorkerList(Some(name)))
          // The create went through the daemon and is visible in its snapshot.
          status <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(9L, "status"))
          _ <- DaemonCommands.run(home, DaemonCommand.Stop(Some(name)))
          _ <- startOutcome.flatMap(_.embedNever)
        yield
          assertEquals(newExit, ExitCode.Success)
          assertEquals(listExit, ExitCode.Success)
          assertEquals(workerListExit, ExitCode.Success)
          status match
            case JsonRpc.Response.Success(_, body) =>
              assertEquals(body.obj("workstreams").arr.head.obj("workstreamId").str, "ws-1")
            case other => fail(s"expected status success, got $other")
      }
    }
  }

  test("workstream spawn against a running daemon (fake supervisor) → exit 0 and the worker id is rendered") {
    fixture.use { case (home, inst) =>
      val fakeSupervisor: Supervisor = (_, _, _) => IO.pure(Right("w-7"))
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(io.forge.instance.InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        shutdown <- Deferred[IO, Unit]
        exit <- Daemon
          .serveUntilShutdown(inst.portFile, state, shutdown, fakeSupervisor)
          .background
          .use { _ =>
            awaitReady(inst) *>
              WorkstreamCommands
                .run(home, WorkstreamCommand.SpawnWorker(Some(name), WorkstreamId("ws-1"), "/repo", FeatureId("feat")))
                .guarantee(shutdown.complete(()).void)
          }
      yield assertEquals(exit, ExitCode.Success)
    }
  }

  test("workstream spawn against no running daemon → exit 1, not a crash") {
    fixture.use { case (home, _) =>
      WorkstreamCommands
        .run(home, WorkstreamCommand.SpawnWorker(Some(name), WorkstreamId("ws-1"), "/repo", FeatureId("feat")))
        .map(assertEquals(_, ExitCode(1)))
    }
  }

  test("workstream new against no running daemon → exit 1, not a crash") {
    fixture.use { case (home, _) =>
      WorkstreamCommands.run(home, WorkstreamCommand.New(Some(name), "add auth")).map(assertEquals(_, ExitCode(1)))
    }
  }

  test("workstream list / worker list against no running daemon → exit 1") {
    fixture.use { case (home, _) =>
      for
        ws <- WorkstreamCommands.run(home, WorkstreamCommand.List(Some(name)))
        wk <- WorkstreamCommands.run(home, WorkstreamCommand.WorkerList(Some(name)))
      yield
        assertEquals(ws, ExitCode(1))
        assertEquals(wk, ExitCode(1))
    }
  }

  test("a workstream command against an unknown instance → exit 1") {
    fixture.use { case (home, _) =>
      WorkstreamCommands
        .run(home, WorkstreamCommand.List(Some(InstanceName("ghost"))))
        .map(assertEquals(_, ExitCode(1)))
    }
  }
