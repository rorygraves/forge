package io.forge.daemon

import cats.effect.IO
import fs2.{text, Stream}
import fs2.io.net.unixsocket.{UnixSocketAddress, UnixSockets}

import scala.util.control.NonFatal

/** The daemon's Unix-domain-socket JSON-RPC server (Phase-4 contract §6.1/§6.3, Slice-4.1 spike Task 4.1.1).
  *
  * Binds the instance socket ([[io.forge.instance.Instance.socketFile]]) and serves newline-delimited JSON-RPC 2.0:
  * each connection's inbound lines are decoded to [[JsonRpc.Request]]s, dispatched through a pluggable [[Handler]], and
  * the resulting [[JsonRpc.Response]] is written back as one line. Multiple requests per connection are supported (the
  * line-oriented loop), and connections are served concurrently (`parJoinUnbounded`), so a long-lived `subscribe`
  * client (Task 4.1.4) does not block a `status` client.
  *
  * The socket file is recreated on bind (`deleteIfExists = true`) — a leftover socket from a crashed daemon is not a
  * live-holder signal; the **instance lock** (held by the supervisor, Task 4.1.3) is the liveness authority — and
  * removed on a clean stop (`deleteOnClose = true`).
  */
object DaemonSocketServer:

  /** Dispatches a decoded request to its method handler. A handler that raises is converted to an internal-error
    * response by [[serve]], so a single buggy method can't tear down the connection loop.
    */
  type Handler = JsonRpc.Request => IO[JsonRpc.Response]

  /** The server as a never-completing `Stream[IO, Nothing]`. Run it in the background (`.compile.drain.background`, or
    * a supervised fiber) for the lifetime of the daemon; cancelling the stream releases the socket.
    */
  def serve(
      socketPath: os.Path,
      handler: Handler,
      sockets: UnixSockets[IO] = UnixSockets.forIO
  ): Stream[IO, Nothing] =
    sockets
      .server(UnixSocketAddress(socketPath.toString), deleteIfExists = true, deleteOnClose = true)
      .map { client =>
        client.reads
          .through(text.utf8.decode)
          .through(text.lines)
          .filter(_.nonEmpty)
          .evalMap(line => respond(line, handler))
          .map(_ + "\n")
          .through(text.utf8.encode)
          .through(client.writes)
      }
      .parJoinUnbounded

  private def respond(line: String, handler: Handler): IO[String] =
    JsonRpc.decodeRequest(line) match
      case Left(err) =>
        // No id is recoverable from an unparseable request line → null-id failure, per JSON-RPC 2.0.
        IO.pure(JsonRpc.encodeResponse(JsonRpc.Response.Failure(None, err)))
      case Right(req) =>
        handler(req)
          .handleError { case NonFatal(t) =>
            JsonRpc.Response.fail(req.id, JsonRpc.RpcError.internal(t.getMessage))
          }
          .map(JsonRpc.encodeResponse)
