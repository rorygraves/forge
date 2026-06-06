package io.forge.daemon

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, RebuildInstanceState, WorkstreamStatus}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.2.4 — the `create-workstream` RPC + the workstream/attention surface of the `status` snapshot. A
  * `create-workstream` allocates the next `ws-N` id and appends a `workstream.created` the snapshot + durable log
  * reflect; spawning a worker into the workstream and reporting a human-needing status surfaces it in the snapshot's
  * derived `attention` projection. Mirrors [[DaemonWorkerSubscribeSuite]]'s in-process-served-daemon idiom.
  */
class DaemonWorkstreamSuite extends CatsEffectSuite:

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  private def served[A](inst: Instance)(body: DaemonState => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown)
        .background
        .use(_ => body(state).guarantee(shutdown.complete(()).void))
    yield result

  private def createReq(id: Long, goal: String): JsonRpc.Request =
    JsonRpc.Request(id, "create-workstream", ujson.Obj("goal" -> goal))

  test("create-workstream allocates ws-1, ws-2, … and the status snapshot reflects them") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          first <- DaemonClient.callWithRetry(inst.socketFile, createReq(1L, "add auth"))
          second <- DaemonClient.callWithRetry(inst.socketFile, createReq(2L, "add billing"))
          status <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(3L, "status"))
        yield
          assertEquals(idOf(first), "ws-1")
          assertEquals(idOf(second), "ws-2")
          status match
            case JsonRpc.Response.Success(_, body) =>
              val ws = body.obj("workstreams").arr.toVector
              assertEquals(ws.map(_.obj("workstreamId").str), Vector("ws-1", "ws-2"))
              assertEquals(ws.head.obj("status").str, "Planning")
              assertEquals(ws.head.obj("goal").str, "add auth")
            case other => fail(s"expected a status success, got $other")
      }
    }
  }

  test("create-workstream without a goal is rejected with InvalidParams, connection survives") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          bad <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(1L, "create-workstream"))
          ok <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(2L, "status"))
        yield
          bad match
            case JsonRpc.Response.Failure(Some(1L), err) => assertEquals(err.code, JsonRpc.RpcError.InvalidParams)
            case other => fail(s"expected InvalidParams, got $other")
          assert(ok.isInstanceOf[JsonRpc.Response.Success], s"expected status to still answer, got $ok")
      }
    }
  }

  test("a worker spawned into a workstream and reporting NeedsHumanIntervention surfaces in attention") {
    instance.use { inst =>
      served(inst) { state =>
        for
          create <- DaemonClient.callWithRetry(inst.socketFile, createReq(1L, "add auth"))
          ws = idOf(create)
          // worker.spawned is a daemon-side record (the supervisor records it in 4.2.5); here drive it via the state
          // directly to exercise the snapshot/attention surface this task owns.
          _ <- state.record(
            io.forge.instance.InstanceEvent
              .WorkerSpawned(
                "w1",
                io.forge.core.WorkstreamId(ws),
                "/repo",
                io.forge.core.FeatureId("feat"),
                "/clone/w1",
                4242L
              )
          )
          _ <- DaemonClient.callWithRetry(
            inst.socketFile,
            JsonRpc.Request(2L, "worker-status", ujson.Obj("workerId" -> "w1", "status" -> "NeedsHumanIntervention"))
          )
          status <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(3L, "status"))
        yield status match
          case JsonRpc.Response.Success(_, body) =>
            val w = body.obj("workers").arr.head.obj
            assertEquals(w("workstreamId").str, ws)
            assertEquals(w("live").bool, true)
            val attention = body.obj("workstreams").arr.head.obj("attention").arr.toVector
            assertEquals(attention.size, 1)
            assertEquals(attention.head.obj("workerId").str, "w1")
            assertEquals(attention.head.obj("reason").str, "needs-human-intervention")
          case other => fail(s"expected a status success, got $other")
      }
    }
  }

  test("create-workstream is durable: the workstream rebuilds from the instance log alone") {
    instance.use { inst =>
      for
        _ <- served(inst)(_ => DaemonClient.callWithRetry(inst.socketFile, createReq(1L, "add auth")).void)
        rebuilt <- FileInstanceLog(inst).flatMap(_.replay).map(RebuildInstanceState.fold)
      yield
        val ws = rebuilt.workstream(io.forge.core.WorkstreamId("ws-1")).getOrElse(fail("ws-1 missing from rebuilt log"))
        assertEquals(ws.goal, "add auth")
        assertEquals(ws.status, WorkstreamStatus.Planning)
    }
  }

  private def idOf(resp: JsonRpc.Response): String = resp match
    case JsonRpc.Response.Success(_, body) => body.obj("workstreamId").str
    case other => fail(s"expected a create-workstream success, got $other")
