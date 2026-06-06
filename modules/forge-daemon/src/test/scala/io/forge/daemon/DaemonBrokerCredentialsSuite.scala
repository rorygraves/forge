package io.forge.daemon

import cats.effect.{IO, Ref, Resource}
import cats.effect.kernel.Deferred
import io.forge.core.InstanceName
import io.forge.instance.{FileInstanceStore, Instance}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.3.3 — the `broker-credentials` RPC routes `{workerId, repo}` to the [[CredentialBroker]] seam (O6) and maps
  * its `Either` back onto the JSON-RPC wire (`Right(creds)` → `{env, missing}`; `Left(err)` → an `InternalError`). The
  * *implementation* of the seam (the per-repo PAT + agent-key resolution) is `forge-app`'s `RealCredentialBroker`,
  * unit-proven in its own suite; here we prove only the daemon-side routing against a recording fake. Mirrors
  * [[DaemonSpawnWorkerSuite]]'s in-process-served-daemon idiom.
  */
class DaemonBrokerCredentialsSuite extends CatsEffectSuite:

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** A broker that records its calls and answers a scripted result. */
  private final class FakeBroker(
      calls: Ref[IO, Vector[(String, String)]],
      answer: Either[BrokerError, BrokeredCredentials]
  ) extends CredentialBroker:
    def brokerFor(workerId: String, repo: String): IO[Either[BrokerError, BrokeredCredentials]] =
      calls.update(_ :+ (workerId, repo)).as(answer)

  private def served[A](inst: Instance, broker: CredentialBroker)(body: => IO[A]): IO[A] =
    for
      state <- DaemonState.boot(inst, pid = 99L)
      shutdown <- Deferred[IO, Unit]
      result <- Daemon
        .serveUntilShutdown(inst.socketFile, state, shutdown, Supervisor.noop, broker)
        .background
        .use(_ => body.guarantee(shutdown.complete(()).void))
    yield result

  private def brokerReq(id: Long, workerId: String, repo: String): JsonRpc.Request =
    JsonRpc.Request(id, "broker-credentials", ujson.Obj("workerId" -> workerId, "repo" -> repo))

  test("broker-credentials routes to the broker and returns the resolved env + missing") {
    instance.use { inst =>
      Ref.of[IO, Vector[(String, String)]](Vector.empty).flatMap { calls =>
        val creds = BrokeredCredentials(
          env = Map("GH_TOKEN" -> "ghp_scoped", "ANTHROPIC_API_KEY" -> "sk-ant"),
          missing = Vector("OPENAI_API_KEY")
        )
        served(inst, new FakeBroker(calls, Right(creds))) {
          for
            resp <- DaemonClient.callWithRetry(inst.socketFile, brokerReq(1L, "w-1", "/repo"))
            seen <- calls.get
          yield
            resp match
              case JsonRpc.Response.Success(_, body) =>
                assertEquals(body.obj("env").obj("GH_TOKEN").str, "ghp_scoped")
                assertEquals(body.obj("env").obj("ANTHROPIC_API_KEY").str, "sk-ant")
                assertEquals(body.obj("missing").arr.map(_.str).toVector, Vector("OPENAI_API_KEY"))
              case other => fail(s"expected success, got $other")
            assertEquals(seen, Vector(("w-1", "/repo")))
        }
      }
    }
  }

  test("a broker refusal surfaces as an InternalError carrying the reason; the connection survives") {
    instance.use { inst =>
      Ref.of[IO, Vector[(String, String)]](Vector.empty).flatMap { calls =>
        served(inst, new FakeBroker(calls, Left(BrokerError.MissingRequiredSecret("FORGE_GH_TOKEN")))) {
          for
            bad <- DaemonClient.callWithRetry(inst.socketFile, brokerReq(1L, "w-1", "/repo"))
            ok <- DaemonClient.callWithRetry(inst.socketFile, JsonRpc.Request(2L, "status"))
          yield
            bad match
              case JsonRpc.Response.Failure(Some(1L), err) =>
                assertEquals(err.code, JsonRpc.RpcError.InternalError)
                assert(err.message.contains("FORGE_GH_TOKEN"), err.message)
              case other => fail(s"expected an InternalError, got $other")
            assert(ok.isInstanceOf[JsonRpc.Response.Success], s"expected status to still answer, got $ok")
        }
      }
    }
  }

  test("a malformed broker-credentials is InvalidParams without reaching the broker") {
    instance.use { inst =>
      Ref.of[IO, Vector[(String, String)]](Vector.empty).flatMap { calls =>
        served(inst, new FakeBroker(calls, Right(BrokeredCredentials(Map.empty)))) {
          for
            bad <- DaemonClient.callWithRetry(
              inst.socketFile,
              JsonRpc.Request(1L, "broker-credentials", ujson.Obj("workerId" -> "w-1")) // no repo
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

  test("the default (no-broker) daemon refuses broker-credentials with an InternalError") {
    instance.use { inst =>
      served(inst, CredentialBroker.noop) {
        DaemonClient.callWithRetry(inst.socketFile, brokerReq(1L, "w-1", "/repo")).map {
          case JsonRpc.Response.Failure(Some(1L), err) => assertEquals(err.code, JsonRpc.RpcError.InternalError)
          case other => fail(s"expected an InternalError, got $other")
        }
      }
    }
  }
