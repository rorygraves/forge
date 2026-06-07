package io.forge.daemon

import cats.effect.{IO, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.3.5 (B2) — the daemon-side `reserve-budget` handshake and the implicit `cost.update` finalization, over an
  * in-process served daemon (mirrors [[DaemonSpawnWorkerSuite]]). Proves: a grant under the cap returns a
  * `reservationId`; a refuse over the cap is a *success* body with `granted: false` (not an error) and the connection
  * survives; a malformed request is `InvalidParams`; and an exported `cost.update` (a `worker-event`) clears the
  * worker's reservation while fanning its spend into the committed totals.
  */
class DaemonReserveBudgetSuite extends CatsEffectSuite:

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  private def served[A](inst: Instance, budget: BudgetPolicy)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.portFile, state, shutdown, Supervisor.noop, CredentialBroker.noop, budget)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  private def reserveReq(id: Long, workerId: String, estimate: Double): JsonRpc.Request =
    JsonRpc.Request(id, "reserve-budget", ujson.Obj("workerId" -> workerId, "estimateUsd" -> ujson.Num(estimate)))

  private def costUpdate(usd: Double): ujson.Value =
    ujson.Obj("kind" -> "cost.update", "payload" -> ujson.Obj("usd" -> usd))

  private def costUpdateSeq(seq: Long, usd: Double): ujson.Value =
    // `seq` as a JSON number — the real `writeJs[Action]` shape (a bare `Long` would render as a string here).
    ujson.Obj("seq" -> ujson.Num(seq.toDouble), "kind" -> "cost.update", "payload" -> ujson.Obj("usd" -> usd))

  private def workerEventReq(id: Long, workerId: String, event: ujson.Value): JsonRpc.Request =
    JsonRpc.Request(id, "worker-event", ujson.Obj("workerId" -> workerId, "event" -> event))

  private def registerReq(id: Long, workerId: String): JsonRpc.Request =
    JsonRpc.Request(id, "register-worker", ujson.Obj("workerId" -> workerId, "repo" -> "/repo", "feature" -> "feat"))

  test("reserve-budget grants under the cap and returns a reservationId"):
    instance.use { inst =>
      served(inst, BudgetPolicy.default) {
        DaemonClient.callWithRetry(inst.portFile, reserveReq(1L, "w1", 8.0)).map {
          case JsonRpc.Response.Success(_, body) =>
            assertEquals(body.obj("granted").bool, true)
            assert(body.obj("reservationId").str.nonEmpty, "expected a non-empty reservationId")
          case other => fail(s"expected success, got $other")
        }
      }
    }

  test("reserve-budget refuses over the cap with granted:false (a success body, not an error); connection survives"):
    instance.use { inst =>
      val tiny = BudgetPolicy(perWorkstreamCapUsd = BigDecimal(1), perInstanceCapUsd = BigDecimal(1))
      served(inst, tiny) {
        for
          refused <- DaemonClient.callWithRetry(inst.portFile, reserveReq(1L, "w1", 8.0))
          ok <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(2L, "status"))
        yield
          refused match
            case JsonRpc.Response.Success(_, body) =>
              assertEquals(body.obj("granted").bool, false)
              assert(body.obj("reason").str.nonEmpty, "expected a refusal reason")
            case other => fail(s"expected a success body with granted:false, got $other")
          assert(ok.isInstanceOf[JsonRpc.Response.Success], s"expected status to still answer, got $ok")
      }
    }

  test("a malformed reserve-budget (missing estimateUsd) is InvalidParams"):
    instance.use { inst =>
      served(inst, BudgetPolicy.default) {
        DaemonClient
          .callWithRetry(inst.portFile, JsonRpc.Request(1L, "reserve-budget", ujson.Obj("workerId" -> "w1")))
          .map {
            case JsonRpc.Response.Failure(Some(1L), err) => assertEquals(err.code, JsonRpc.RpcError.InvalidParams)
            case other => fail(s"expected InvalidParams, got $other")
          }
      }
    }

  test("an exported cost.update finalizes the worker's reservation and fans its spend into committed"):
    instance.use { inst =>
      served(inst, BudgetPolicy.default) {
        for
          // register, grant a reservation, then export a cost.update for the worker
          _ <- DaemonClient.callWithRetry(
            inst.portFile,
            JsonRpc.Request(
              1L,
              "register-worker",
              ujson.Obj("workerId" -> "w1", "repo" -> "/repo", "feature" -> "feat")
            )
          )
          grant <- DaemonClient.callWithRetry(inst.portFile, reserveReq(2L, "w1", 8.0))
          beforeStatus <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(3L, "status"))
          _ <- DaemonClient.callWithRetry(
            inst.portFile,
            JsonRpc.Request(4L, "worker-event", ujson.Obj("workerId" -> "w1", "event" -> costUpdate(2.5)))
          )
          afterStatus <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(5L, "status"))
        yield
          assert(grant.isInstanceOf[JsonRpc.Response.Success])
          // before the cost.update: the 8.0 estimate is outstanding, nothing committed
          val before = beforeStatus.asInstanceOf[JsonRpc.Response.Success].result.obj
          assertEquals(before("outstandingUsd").num, 8.0)
          assertEquals(before("committedUsd").num, 0.0)
          // after: the reservation is finalized (outstanding back to 0) and the actual spend committed
          val after = afterStatus.asInstanceOf[JsonRpc.Response.Success].result.obj
          assertEquals(after("outstandingUsd").num, 0.0)
          assertEquals(after("committedUsd").num, 2.5)
      }
    }

  test("a replayed cost.update neither double-commits nor finalizes a later reservation (at-least-once dedup)"):
    instance.use { inst =>
      served(inst, BudgetPolicy.default) {
        for
          _ <- DaemonClient.callWithRetry(inst.portFile, registerReq(1L, "w1"))
          // session 1: reserve, then export a seq-stamped cost.update → commits 2.5, clears the reservation.
          _ <- DaemonClient.callWithRetry(inst.portFile, reserveReq(2L, "w1", 8.0))
          _ <- DaemonClient.callWithRetry(inst.portFile, workerEventReq(3L, "w1", costUpdateSeq(0, 2.5)))
          // session 2: a fresh reservation (the worker drives its next piece).
          _ <- DaemonClient.callWithRetry(inst.portFile, reserveReq(4L, "w1", 8.0))
          // the worker resumes / re-exports its feed from seq 0: the session-1 cost.update arrives AGAIN (seq 0).
          replay <- DaemonClient.callWithRetry(inst.portFile, workerEventReq(5L, "w1", costUpdateSeq(0, 2.5)))
          finalStatus <- DaemonClient.callWithRetry(inst.portFile, JsonRpc.Request(6L, "status"))
        yield
          // the replay is still acked (so the worker's export watermark advances and it stops re-sending)
          assert(replay.isInstanceOf[JsonRpc.Response.Success], s"expected the replay to be acked, got $replay")
          val st = finalStatus.asInstanceOf[JsonRpc.Response.Success].result.obj
          // committed stays at the single real spend (NOT 5.0) — the replay did not double-count
          assertEquals(st("committedUsd").num, 2.5)
          // the session-2 reservation is intact (NOT spuriously finalized by the replayed cost.update)
          assertEquals(st("outstandingUsd").num, 8.0)
      }
    }
