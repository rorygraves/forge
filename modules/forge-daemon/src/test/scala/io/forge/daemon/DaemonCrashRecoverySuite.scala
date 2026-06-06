package io.forge.daemon

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceStore, Instance, InstanceEvent}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.1.5 — the Slice-4.1 **crash-recovery exit criterion**. A daemon registers a worker, records its status + a
  * couple of exported events, then is **crashed** mid-life — the serve fiber is cancelled abruptly, with no clean
  * `shutdown` RPC, and the derived state cache is deleted so only the canonical instance log survives. A fresh daemon
  * restart must rebuild the *exact* view from the instance log alone: the `status` snapshot (boot count, worker, latest
  * status, event count) and the worker's exported-event tail (replayed in order by `subscribe`).
  *
  * This is the in-process automated proof; the matching live two-terminal `forge daemon` SIGKILL exercise is captured
  * in `docs/dogfood/4.1-daemon.md`.
  */
class DaemonCrashRecoverySuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted instance (macOS caps `sun_path` at 104 bytes), created on disk and cleaned up after. */
  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  private def registerReq(id: Long, workerId: String, repo: String, feature: String): JsonRpc.Request =
    JsonRpc.Request(id, "register-worker", ujson.Obj("workerId" -> workerId, "repo" -> repo, "feature" -> feature))

  private def statusReq(id: Long, workerId: String, status: String): JsonRpc.Request =
    JsonRpc.Request(id, "worker-status", ujson.Obj("workerId" -> workerId, "status" -> status))

  private def eventReq(id: Long, workerId: String, tag: String): JsonRpc.Request =
    JsonRpc.Request(id, "worker-event", ujson.Obj("workerId" -> workerId, "event" -> ujson.Obj("tag" -> tag)))

  /** Boot a daemon, serve its socket for `body`, then **crash**: cancel the serve fiber on scope exit without ever
    * firing the `shutdown` signal (the `Deferred` stays unset). Mirrors a SIGKILL — no clean stop handshake, no final
    * state flush; only the durable per-append log writes survive.
    */
  private def crashAfter[A](inst: Instance, pid: Long)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid)
      shutdown <- Deferred[IO, Unit] // deliberately never completed → the serve fiber is cancelled, not stopped cleanly
      result <- Daemon.serveUntilShutdown(inst.socketFile, state, shutdown).background.use(_ => body)
    yield result

  test("crash mid-life and restart: status snapshot + worker event tail rebuild from the instance log alone") {
    instance.use { inst =>
      for
        // First daemon life: register a worker, set its status, push two exported events, then crash.
        _ <- crashAfter(inst, pid = 1L) {
          for
            _ <- DaemonClient.callWithRetry(inst.socketFile, registerReq(1L, "w1", "/repo", "add-feature"))
            _ <- DaemonClient.callWithRetry(inst.socketFile, statusReq(2L, "w1", "Refining"))
            _ <- DaemonClient.callWithRetry(inst.socketFile, eventReq(3L, "w1", "a"))
            _ <- DaemonClient.callWithRetry(inst.socketFile, eventReq(4L, "w1", "b"))
          yield ()
        }
        // Lose the derived cache: a hard crash may leave only the canonical log behind. The restart must rebuild from it
        // alone, so deleting the cache proves the log — not a fortunate cache — is the source of truth (§6.4).
        _ <- IO.blocking(os.remove(inst.instanceStateFile, checkExists = false))
        // Restart: a fresh daemon rebuilds, then we read both the status snapshot and the replayed worker feed.
        result <- DaemonState.boot(inst, pid = 2L).flatMap { state2 =>
          Deferred[IO, Unit].flatMap { shutdown2 =>
            Daemon.serveUntilShutdown(inst.socketFile, state2, shutdown2).background.use { _ =>
              (for
                status <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(5L, "status"))
                feed <- DaemonClient
                  .subscribe(inst.socketFile, JsonRpc.Request(6L, "subscribe"))
                  .take(4) // worker.registered, worker.status, two worker.event — the rebuilt tail
                  .compile
                  .toVector
              yield (status, feed)).guarantee(shutdown2.complete(()).void)
            }
          }
        }
      yield
        val (status, feed) = result
        status match
          case JsonRpc.Response.Success(_, body) =>
            assertEquals(body.obj("bootCount").num.toInt, 2) // first life + this restart, rebuilt from the log
            val workers = body.obj("workers").arr.toVector
            assertEquals(workers.size, 1)
            val w = workers.head.obj
            assertEquals(w("workerId").str, "w1")
            assertEquals(w("feature").str, "add-feature")
            assertEquals(w("status").str, "Refining")
            assertEquals(w("eventCount").num.toInt, 2)
          case other => fail(s"expected a status success after restart, got $other")
        // The exported-event tail rebuilt with its content (not just its count) intact, in recorded order.
        val kinds = feed.collect { case JsonRpc.Response.Success(_, body) => body.obj("kind").str }
        assertEquals(
          kinds,
          Vector(
            InstanceEvent.WorkerRegisteredKind,
            InstanceEvent.WorkerStatusKind,
            InstanceEvent.WorkerEventKind,
            InstanceEvent.WorkerEventKind
          )
        )
        val tags = feed.collect {
          case JsonRpc.Response.Success(_, body) if body.obj("kind").str == InstanceEvent.WorkerEventKind =>
            body.obj("payload").obj("event").obj("tag").str
        }
        assertEquals(tags, Vector("a", "b"))
    }
  }
