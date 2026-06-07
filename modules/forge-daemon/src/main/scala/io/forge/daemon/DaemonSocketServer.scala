package io.forge.daemon

import cats.effect.IO
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.io.net.Network
import fs2.{text, Stream}

/** The daemon's **TCP** JSON-RPC server (Phase-4 contract §6.1/§6.3, Slice-4.1 spike Task 4.1.1; moved off the
  * Unix-domain socket in the Slice-4.3 TCP migration).
  *
  * Binds a TCP port and serves newline-delimited JSON-RPC 2.0: each connection's inbound lines are decoded to
  * [[JsonRpc.Request]]s, dispatched through a pluggable [[Handler]], and each [[JsonRpc.Response]] the handler emits is
  * written back as one line. Most methods are **unary** (one request → one response, [[Handler.unary]]); a
  * **streaming** method — `subscribe` (Task 4.1.4) — returns a long-lived stream whose successive elements become
  * successive response lines on the same connection. Multiple requests per connection are supported (the line-oriented
  * loop), and connections are served concurrently (`parJoinUnbounded`), so a long-lived `subscribe` client does not
  * block a `status` client.
  *
  * TCP (rather than a Unix socket) is what lets a **containerised** worker (Slice 4.3) reach the daemon — it connects
  * to `host.docker.internal:<port>` from inside its OCI container, where a bind-mounted host socket file is unreliable
  * across the Docker Desktop VM boundary. The bind host defaults to loopback (`127.0.0.1`); the container path binds
  * all interfaces (`0.0.0.0`) so the container can reach it. The port is normally **ephemeral** (`port 0`) — the bound
  * address is emitted as the stream's first element so the caller can record it
  * ([[io.forge.instance.Instance.portFile]]).
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

  /** The running server as a `Stream[IO, SocketAddress[IpAddress]]` that **emits the bound address once** (the resolved
    * ephemeral port, when `port` is `0`) and then serves forever, emitting nothing further until interrupted. Run it in
    * the background (`.compile.drain.background`, or a supervised fiber) for the lifetime of the daemon; cancelling the
    * stream releases the listen socket. The single emitted element lets the caller learn the ephemeral port (to write
    * the discovery file / log it) via `.evalTap` before the serve loop blocks.
    */
  /** Loopback bind host — the safe default (host clients only). The container path binds [[AllInterfaces]] instead. */
  val Loopback: Host = Host.fromString("127.0.0.1").get

  /** All-interfaces bind host — what the container path binds so a worker can reach the daemon via
    * `host.docker.internal`.
    */
  val AllInterfaces: Host = Host.fromString("0.0.0.0").get

  /** Ephemeral port (the OS assigns a free one at bind; the resolved port is emitted as the stream's first element). */
  private val Ephemeral: Port = Port.fromInt(0).get

  def serve(
      handler: Handler,
      host: Host = Loopback,
      port: Port = Ephemeral,
      network: Network[IO] = Network.forIO
  ): Stream[IO, SocketAddress[IpAddress]] =
    Stream.resource(network.serverResource(Some(host), Some(port))).flatMap { case (bound, clients) =>
      val server = clients.map { client =>
        client.reads
          .through(text.utf8.decode)
          .through(text.lines)
          .filter(_.nonEmpty)
          .flatMap(line => respond(line, handler))
          .through(text.utf8.encode)
          .through(client.writes)
      }.parJoinUnbounded
      Stream.emit(bound) ++ server.drain
    }

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
