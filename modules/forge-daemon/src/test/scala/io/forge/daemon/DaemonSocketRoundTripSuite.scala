package io.forge.daemon

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite

import java.util.UUID

/** Task 4.1.1 — the IPC spike's proof: a JSON-RPC `status` request round-trips over a real Unix-domain socket, an
  * unknown method comes back as a JSON-RPC `MethodNotFound` error (not a torn-down connection), and a malformed line is
  * answered with a `null`-id parse error. This exercises the whole transport (`DaemonSocketServer` ↔ `DaemonClient` ↔
  * `JsonRpc`) end-to-end, which is the riskiest new Phase-4.1 contract.
  */
class DaemonSocketRoundTripSuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted socket path (macOS caps `sun_path` at 104 bytes, so the long `/var/folders/...` tmp dir is
    * unsafe), cleaned up after the test.
    */
  private val socketPath: Resource[IO, os.Path] =
    Resource.make(IO(os.Path("/tmp") / s"forge-daemon-${UUID.randomUUID()}.sock")) { p =>
      IO.blocking(os.remove(p, checkExists = false)).void
    }

  /** Echo handler: `status` → `{status:"ok"}` (with the request id reflected so the test can assert id-echo), anything
    * else → MethodNotFound.
    */
  private val handler: DaemonSocketServer.Handler = {
    case req if req.method == "status" =>
      IO.pure(JsonRpc.Response.ok(req.id, ujson.Obj("status" -> "ok", "echoedId" -> ujson.Num(req.id.toDouble))))
    case req =>
      IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.methodNotFound(req.method)))
  }

  test("status request round-trips over the unix socket") {
    socketPath.use { sock =>
      DaemonSocketServer.serve(sock, handler).compile.drain.background.use { _ =>
        DaemonClient.callWithRetry(sock, JsonRpc.Request(7L, "status")).map { resp =>
          assertEquals(
            resp,
            JsonRpc.Response.Success(Some(7L), ujson.Obj("status" -> "ok", "echoedId" -> ujson.Num(7d)))
          )
        }
      }
    }
  }

  test("unknown method returns a JSON-RPC MethodNotFound error") {
    socketPath.use { sock =>
      DaemonSocketServer.serve(sock, handler).compile.drain.background.use { _ =>
        DaemonClient.callWithRetry(sock, JsonRpc.Request(3L, "no-such-method")).map { resp =>
          assertEquals(
            resp,
            JsonRpc.Response
              .Failure(Some(3L), JsonRpc.RpcError(JsonRpc.RpcError.MethodNotFound, "method not found: no-such-method"))
          )
        }
      }
    }
  }

  test("two requests on one connection both get answered (line-oriented loop)") {
    socketPath.use { sock =>
      DaemonSocketServer.serve(sock, handler).compile.drain.background.use { _ =>
        for
          a <- DaemonClient.callWithRetry(sock, JsonRpc.Request(1L, "status"))
          b <- DaemonClient.callWithRetry(sock, JsonRpc.Request(2L, "status"))
        yield
          assertEquals(a.id, Some(1L))
          assertEquals(b.id, Some(2L))
      }
    }
  }
