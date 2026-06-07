package io.forge.daemon

import cats.effect.{IO, Ref, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.2.5 — the `spawn-worker` RPC routes `{workstreamId, repo, feature}` to the [[Supervisor]] seam and maps its
  * `Either` back onto the JSON-RPC wire (`Right(workerId)` → `{workerId}` success; `Left(reason)` → an
  * `InternalError`). The *implementation* of the seam (clone provisioning, the real `forge worker` launch, the exit
  * watch) is `forge-app`'s `RealSupervisor`, unit-proven in its own suite; here we prove only the daemon-side routing
  * against a recording fake. Mirrors [[DaemonWorkstreamSuite]]'s in-process-served-daemon idiom.
  */
class DaemonSpawnWorkerSuite extends CatsEffectSuite:

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** A supervisor that records its calls and answers a scripted result. */
  private final class FakeSupervisor(
      calls: Ref[IO, Vector[(WorkstreamId, String, FeatureId)]],
      answer: Either[String, String]
  ) extends Supervisor:
    def spawnWorker(workstreamId: WorkstreamId, repo: String, feature: FeatureId): IO[Either[String, String]] =
      calls.update(_ :+ (workstreamId, repo, feature)).as(answer)

  private def served[A](inst: Instance, supervisor: Supervisor)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.portFile, state, shutdown, supervisor)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  private def spawnReq(id: Long, ws: String, repo: String, feature: String): JsonRpc.Request =
    JsonRpc.Request(id, "spawn-worker", ujson.Obj("workstreamId" -> ws, "repo" -> repo, "feature" -> feature))

  test("spawn-worker routes to the supervisor and returns its allocated worker id") {
    instance.use { inst =>
      Ref.of[IO, Vector[(WorkstreamId, String, FeatureId)]](Vector.empty).flatMap { calls =>
        served(inst, new FakeSupervisor(calls, Right("w-1"))) {
          for
            resp <- DaemonClient.callWithRetry(inst.portFile, spawnReq(1L, "ws-1", "/repo", "feat"))
            seen <- calls.get
          yield
            resp match
              case JsonRpc.Response.Success(_, body) =>
                assertEquals(body.obj("workerId").str, "w-1")
                assertEquals(body.obj("workstreamId").str, "ws-1")
              case other => fail(s"expected success, got $other")
            assertEquals(seen, Vector((WorkstreamId("ws-1"), "/repo", FeatureId("feat"))))
        }
      }
    }
  }

  test("a supervisor refusal surfaces as an InternalError carrying the reason; the connection survives") {
    instance.use { inst =>
      Ref.of[IO, Vector[(WorkstreamId, String, FeatureId)]](Vector.empty).flatMap { calls =>
        served(inst, new FakeSupervisor(calls, Left("no such workstream ws-9"))) {
          for
            bad <- DaemonClient.callWithRetry(inst.portFile, spawnReq(1L, "ws-9", "/repo", "feat"))
            ok <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(2L, "status"))
          yield
            bad match
              case JsonRpc.Response.Failure(Some(1L), err) =>
                assertEquals(err.code, JsonRpc.RpcError.InternalError)
                assert(err.message.contains("no such workstream ws-9"), err.message)
              case other => fail(s"expected an InternalError, got $other")
            assert(ok.isInstanceOf[JsonRpc.Response.Success], s"expected status to still answer, got $ok")
        }
      }
    }
  }

  test("a malformed spawn-worker is InvalidParams without reaching the supervisor") {
    instance.use { inst =>
      Ref.of[IO, Vector[(WorkstreamId, String, FeatureId)]](Vector.empty).flatMap { calls =>
        served(inst, new FakeSupervisor(calls, Right("w-1"))) {
          for
            bad <- DaemonClient.callWithRetry(
              inst.portFile,
              JsonRpc.Request(1L, "spawn-worker", ujson.Obj("workstreamId" -> "ws-1")) // no repo/feature
            )
            seen <- calls.get
          yield
            bad match
              case JsonRpc.Response.Failure(Some(1L), err) => assertEquals(err.code, JsonRpc.RpcError.InvalidParams)
              case other => fail(s"expected InvalidParams, got $other")
            assertEquals(seen, Vector.empty)
        }
      }
    }
  }

  test("the default (no-supervisor) daemon refuses spawn-worker with an InternalError") {
    instance.use { inst =>
      served(inst, Supervisor.noop) {
        DaemonClient.callWithRetry(inst.portFile, spawnReq(1L, "ws-1", "/repo", "feat")).map {
          case JsonRpc.Response.Failure(Some(1L), err) => assertEquals(err.code, JsonRpc.RpcError.InternalError)
          case other => fail(s"expected an InternalError, got $other")
        }
      }
    }
  }
