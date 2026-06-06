package io.forge.daemon

import cats.effect.IO
import fs2.{text, Stream}
import fs2.io.net.unixsocket.{UnixSocketAddress, UnixSockets}

/** The daemon's Unix-domain-socket JSON-RPC server (Phase-4 contract §6.1/§6.3, Slice-4.1 spike Task 4.1.1).
  *
  * Binds the instance socket ([[io.forge.instance.Instance.socketFile]]) and serves newline-delimited JSON-RPC 2.0:
  * each connection's inbound lines are decoded to [[JsonRpc.Request]]s, dispatched through a pluggable [[Handler]], and
  * each [[JsonRpc.Response]] the handler emits is written back as one line. Most methods are **unary** (one request →
  * one response, [[Handler.unary]]); a **streaming** method — `subscribe` (Task 4.1.4) — returns a long-lived stream
  * whose successive elements become successive response lines on the same connection. Multiple requests per connection
  * are supported (the line-oriented loop), and connections are served concurrently (`parJoinUnbounded`), so a
  * long-lived `subscribe` client does not block a `status` client.
  *
  * The socket file is recreated on bind (`deleteIfExists = true`) — a leftover socket from a crashed daemon is not a
  * live-holder signal; the **instance lock** (held by the supervisor, Task 4.1.3) is the liveness authority — and
  * removed on a clean stop (`deleteOnClose = true`).
  */
object DaemonSocketServer:

  /** Dispatches a decoded request to a **stream** of responses written back as successive lines. A unary method (one
    * request → one response) is lifted with [[Handler.unary]]; a streaming method (`subscribe`) returns its live feed
    * directly. A handler stream that raises is converted to a single internal-error response by [[serve]], so one buggy
    * method can't tear down the connection loop.
    */
  type Handler = JsonRpc.Request => Stream[IO, JsonRpc.Response]

  object Handler:
    /** Lift a unary request→response handler into the streaming [[Handler]] shape (a single-element response stream).
      */
    def unary(f: JsonRpc.Request => IO[JsonRpc.Response]): Handler = req => Stream.eval(f(req))

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
          .flatMap(line => respond(line, handler))
          .through(text.utf8.encode)
          .through(client.writes)
      }
      .parJoinUnbounded

  /** The response line(s) for one inbound request line — a single line for a unary method, or a line per streamed
    * element for `subscribe`. `flatMap`-sequenced per connection, so a long-lived `subscribe` stream holds the
    * connection open (it sends no further requests) while distinct connections stay concurrent via `parJoinUnbounded`.
    */
  private def respond(line: String, handler: Handler): Stream[IO, String] =
    JsonRpc.decodeRequest(line) match
      case Left(err) =>
        // No id is recoverable from an unparseable request line → null-id failure, per JSON-RPC 2.0.
        Stream.emit(JsonRpc.encodeResponse(JsonRpc.Response.Failure(None, err)) + "\n")
      case Right(req) =>
        handler(req)
          .handleErrorWith(t => Stream.emit(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.internal(t.getMessage))))
          .map(resp => JsonRpc.encodeResponse(resp) + "\n")
