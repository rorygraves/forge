package io.forge.daemon

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, InstanceEvent}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.1.3 — the daemon supervisor mechanics: [[DaemonState]] boots from the durable log (appending `daemon.started`
  * each boot), [[Daemon.serveUntilShutdown]] answers the real `status` snapshot over the socket, a `shutdown` RPC stops
  * the serve, and a restart rebuilds the exact view from the instance log alone (the Slice-4.1 crash-recovery shape;
  * Task 4.1.5 adds the hard-kill exercise + the `forge daemon` CLI proof in `forge-app`).
  */
class DaemonLifecycleSuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted instance (macOS caps `sun_path` at 104 bytes, so the long `/var/folders/...` tmp dir would
    * overflow the socket path), created on disk and cleaned up after the test.
    */
  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      val name = InstanceName("demo")
      new FileInstanceStore(home).create(name).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  test("boot appends daemon.started and seeds bootCount = 1") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 4242L)
        snap <- state.snapshot
        records <- FileInstanceLog(inst).flatMap(_.replay)
      yield
        assertEquals(snap.bootCount, 1)
        assertEquals(snap.workers, Vector.empty)
        assertEquals(records.flatMap(_.event), Vector(InstanceEvent.DaemonStarted(4242L)))
    }
  }

  test("a restart rebuilds bootCount from the log alone (no cache reliance)") {
    instance.use { inst =>
      for
        _ <- DaemonState.boot(inst, pid = 1L)
        // Drop the state cache so the second boot can only rebuild from the canonical log.
        _ <- IO.blocking(os.remove(inst.instanceStateFile, checkExists = false))
        restarted <- DaemonState.boot(inst, pid = 2L)
        snap <- restarted.snapshot
      yield assertEquals(snap.bootCount, 2)
    }
  }

  test("status RPC serves the live snapshot and shutdown stops the serve") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 7L)
        shutdown <- Deferred[IO, Unit]
        result <- Daemon
          .serveUntilShutdown(inst.socketFile, state, shutdown)
          .background
          .use { serveOutcome =>
            for
              status <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(1L, "status"))
              stop <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(2L, "shutdown"))
              // The serve loop must terminate once the shutdown grace elapses; joining proves it stopped.
              _ <- serveOutcome
            yield (status, stop)
          }
      yield
        val (status, stop) = result
        status match
          case JsonRpc.Response.Success(Some(1L), body) =>
            assertEquals(body.obj.get("bootCount").flatMap(_.numOpt).map(_.toLong), Some(1L))
          case other => fail(s"expected a status success, got $other")
        assert(stop.isInstanceOf[JsonRpc.Response.Success], s"expected a shutdown ack, got $stop")
    }
  }
