package io.forge.daemon

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import fs2.Stream
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceLog, FileInstanceStore, Instance, InstanceEvent, RebuildInstanceState}
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Task 4.1.4 — the worker register / event-export / subscribe surface (B3). A `register-worker` RPC seeds a worker
  * record the `status` snapshot reflects; `worker-status` / `worker-event` are single-writer appends that update the
  * snapshot **and** the durable instance log (so a restart rebuilds them, Task 4.1.5); a `subscribe` client receives
  * the aggregated per-worker feed — the rebuilt exported-feed tail first, then live events as they are recorded.
  * Malformed worker params answer with `InvalidParams` rather than tearing the connection down.
  */
class DaemonWorkerSubscribeSuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted instance (macOS caps `sun_path` at 104 bytes), created on disk and cleaned up after. */
  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** Boot a daemon serving its socket for the body, tearing the serve down (via the shutdown signal) afterwards. */
  private def served[A](inst: Instance)(body: DaemonState => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown)
        .background
        .use(_ => body(state).guarantee(shutdown.complete(()).void))
    yield result

  private def registerReq(id: Long, workerId: String, repo: String, feature: String): JsonRpc.Request =
    JsonRpc.Request(
      id,
      "register-worker",
      ujson.Obj("workerId" -> workerId, "repo" -> repo, "feature" -> feature)
    )

  private def eventReq(id: Long, workerId: String, tag: String): JsonRpc.Request =
    JsonRpc.Request(id, "worker-event", ujson.Obj("workerId" -> workerId, "event" -> ujson.Obj("tag" -> tag)))

  test("register-worker seeds a worker record the status snapshot reflects") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          reg <- DaemonClient.callWithRetry(inst.socketFile, registerReq(1L, "w1", "/repo", "add-feature"))
          status <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(2L, "status"))
        yield
          assert(reg.isInstanceOf[JsonRpc.Response.Success], s"expected register ack, got $reg")
          status match
            case JsonRpc.Response.Success(_, body) =>
              val workers = body.obj("workers").arr.toVector
              assertEquals(workers.size, 1)
              val w = workers.head.obj
              assertEquals(w("workerId").str, "w1")
              assertEquals(w("feature").str, "add-feature")
              assertEquals(w("status").str, "registered")
              assertEquals(w("eventCount").num.toInt, 0)
            case other => fail(s"expected a status success, got $other")
      }
    }
  }

  test("worker-status and worker-event update the snapshot and persist to the durable log") {
    instance.use { inst =>
      for
        _ <- served(inst) { _ =>
          for
            _ <- DaemonClient.callWithRetry(inst.socketFile, registerReq(1L, "w1", "/repo", "add-feature"))
            _ <- DaemonClient.callWithRetry(
              inst.socketFile,
              JsonRpc.Request(2L, "worker-status", ujson.Obj("workerId" -> "w1", "status" -> "Refining"))
            )
            _ <- DaemonClient.callWithRetry(inst.socketFile, eventReq(3L, "w1", "a"))
            status <- DaemonClient.callWithRetry(inst.socketFile, eventReq(4L, "w1", "b")) *>
              DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(5L, "status"))
          yield status match
            case JsonRpc.Response.Success(_, body) =>
              val w = body.obj("workers").arr.head.obj
              assertEquals(w("status").str, "Refining")
              assertEquals(w("eventCount").num.toInt, 2)
            case other => fail(s"expected a status success, got $other")
        }
        // The log is canonical: rebuilding from it alone (no cache) reproduces the worker + its two exported events.
        rebuilt <- FileInstanceLog(inst).flatMap(_.replay).map(RebuildInstanceState.fold)
      yield
        val w = rebuilt.worker("w1").getOrElse(fail("worker w1 missing from the rebuilt log"))
        assertEquals(w.status, "Refining")
        assertEquals(w.events.map(_.obj("tag").str), Vector("a", "b"))
    }
  }

  test("subscribe streams the rebuilt feed tail then live events") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          // Seed: a worker + two exported events recorded before anyone subscribes.
          _ <- DaemonClient.callWithRetry(inst.socketFile, registerReq(1L, "w1", "/repo", "add-feature"))
          _ <- DaemonClient.callWithRetry(inst.socketFile, eventReq(2L, "w1", "seed-a"))
          _ <- DaemonClient.callWithRetry(inst.socketFile, eventReq(3L, "w1", "seed-b"))
          // Subscribe, and after the seed has been delivered, record one more event live.
          pushLive = Stream.eval(
            IO.sleep(300.millis) *> DaemonClient.call(inst.socketFile, eventReq(4L, "w1", "live-c"))
          )
          events <- DaemonClient
            .subscribe(inst.socketFile, JsonRpc.Request(9L, "subscribe"))
            .take(5)
            .concurrently(pushLive)
            .compile
            .toVector
        yield
          // Every streamed line echoes the subscribe id and carries the {kind, payload} wire shape.
          assert(events.forall(_.id.contains(9L)), s"expected all events to echo id 9, got ${events.map(_.id)}")
          val kinds = events.collect { case JsonRpc.Response.Success(_, body) => body.obj("kind").str }
          // Seed: worker.registered, worker.status, two worker.event; then the live worker.event.
          assertEquals(
            kinds,
            Vector(
              InstanceEvent.WorkerRegisteredKind,
              InstanceEvent.WorkerStatusKind,
              InstanceEvent.WorkerEventKind,
              InstanceEvent.WorkerEventKind,
              InstanceEvent.WorkerEventKind
            )
          )
          val eventTags = events.collect {
            case JsonRpc.Response.Success(_, body) if body.obj("kind").str == InstanceEvent.WorkerEventKind =>
              body.obj("payload").obj("event").obj("tag").str
          }
          assertEquals(eventTags, Vector("seed-a", "seed-b", "live-c"))
      }
    }
  }

  test("a malformed register-worker is rejected with InvalidParams, not a torn-down connection") {
    instance.use { inst =>
      served(inst) { _ =>
        for
          // 'FEATURE' is not a valid FeatureId (must be lowercase) → InvalidParams.
          bad <- DaemonClient.callWithRetry(inst.socketFile, registerReq(1L, "w1", "/repo", "FEATURE"))
          // The connection/daemon survives: a follow-up status still answers.
          ok <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(2L, "status"))
        yield
          bad match
            case JsonRpc.Response.Failure(Some(1L), err) =>
              assertEquals(err.code, JsonRpc.RpcError.InvalidParams)
            case other => fail(s"expected an InvalidParams failure, got $other")
          assert(ok.isInstanceOf[JsonRpc.Response.Success], s"expected status to still answer, got $ok")
      }
    }
  }
