package io.forge.daemon

import cats.effect.{Deferred, IO}
import munit.CatsEffectSuite

/** Task 4.1.1 — the IPC spike's proof (re-pointed at TCP in the Slice-4.3 migration): a JSON-RPC `status` request
  * round-trips over a real loopback TCP socket, an unknown method comes back as a JSON-RPC `MethodNotFound` error (not
  * a torn-down connection), and two requests on one connection are both answered. This exercises the whole transport
  * (`DaemonSocketServer` ↔ `DaemonClient` ↔ `JsonRpc`) end-to-end, which is the riskiest new Phase-4 contract.
  */
class DaemonSocketRoundTripSuite extends CatsEffectSuite:

  /** Echo handler: `status` → `{status:"ok"}` (with the request id reflected so the test can assert id-echo), anything
    * else → MethodNotFound.
    */
  private val handler: DaemonSocketServer.Handler = DaemonSocketServer.Handler.unary {
    case req if req.method == "status" =>
      IO.pure(JsonRpc.Response.ok(req.id, ujson.Obj("status" -> "ok", "echoedId" -> ujson.Num(req.id.toDouble))))
    case req =>
      IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.methodNotFound(req.method)))
  }

  /** Bind the server on an ephemeral loopback port, hand `body` the resolved [[DaemonAddress]], and tear the server
    * down when `body` completes. The server stream emits its bound address as the first element, which we relay through
    * a `Deferred` so the client knows which port to dial.
    */
  private def served[A](body: DaemonAddress => IO[A]): IO[A] =
    Deferred[IO, Int].flatMap { boundPort =>
      DaemonSocketServer
        .serve(handler)
        .evalTap(bound => boundPort.complete(bound.port.value).void)
        .compile
        .drain
        .background
        .use(_ => boundPort.get.flatMap(p => body(DaemonAddress.loopback(p))))
    }

  test("status request round-trips over the TCP socket") {
    served { addr =>
      DaemonClient.callWithRetry(addr, JsonRpc.Request(7L, "status")).map { resp =>
        assertEquals(
          resp,
          JsonRpc.Response.Success(Some(7L), ujson.Obj("status" -> "ok", "echoedId" -> ujson.Num(7d)))
        )
      }
    }
  }

  test("unknown method returns a JSON-RPC MethodNotFound error") {
    served { addr =>
      DaemonClient.callWithRetry(addr, JsonRpc.Request(3L, "no-such-method")).map { resp =>
        assertEquals(
          resp,
          JsonRpc.Response
            .Failure(Some(3L), JsonRpc.RpcError(JsonRpc.RpcError.MethodNotFound, "method not found: no-such-method"))
        )
      }
    }
  }

  test("two requests on one connection both get answered (line-oriented loop)") {
    served { addr =>
      for
        a <- DaemonClient.callWithRetry(addr, JsonRpc.Request(1L, "status"))
        b <- DaemonClient.callWithRetry(addr, JsonRpc.Request(2L, "status"))
      yield
        assertEquals(a.id, Some(1L))
        assertEquals(b.id, Some(2L))
    }
  }
