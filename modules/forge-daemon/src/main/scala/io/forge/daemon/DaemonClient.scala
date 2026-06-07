package io.forge.daemon

import cats.effect.IO
import fs2.io.net.Network
import fs2.{text, Stream}

import scala.concurrent.duration.*

/** JSON-RPC client over the daemon's **TCP** socket (Slice-4.1 spike Task 4.1.1; moved off the Unix-domain socket in
  * the Slice-4.3 TCP migration). The CLI's `forge daemon status` (Task 4.1.3), the operator workstream commands, and
  * the worker's control RPCs are built on this.
  *
  * Two address forms: a resolved [[DaemonAddress]] (`host:port` — what a containerised worker connects to:
  * `host.docker.internal:<port>`), and a port-discovery file ([[io.forge.instance.Instance.portFile]]) the daemon wrote
  * its ephemeral port to (what host clients use — they read it and connect over loopback). The file form re-reads on
  * each attempt, so [[callWithRetry]] also rides out the start-up race where a client connects a hair before the daemon
  * has bound its port and written the file.
  *
  * [[call]] opens a fresh connection, writes one request line, and reads back the single response line. The connection
  * stays open for the read (fs2's `Socket.writes` does not half-close), so the server's reply is received on the same
  * socket.
  */
object DaemonClient:

  /** A single request/response round-trip to a resolved [[DaemonAddress]]. Fails (`IO` error) if the socket can't be
    * connected (no daemon) — callers that race a daemon start should use [[callWithRetry]].
    */
  def call(
      address: DaemonAddress,
      request: JsonRpc.Request,
      network: Network[IO] = Network.forIO
  ): IO[JsonRpc.Response] =
    network.client(address.socketAddress).use { socket =>
      val reqLine = JsonRpc.encodeRequest(request) + "\n"
      for
        _ <- Stream.emit(reqLine).through(text.utf8.encode).through(socket.writes).compile.drain
        line <- socket.reads
          .through(text.utf8.decode)
          .through(text.lines)
          .filter(_.nonEmpty)
          .head
          .compile
          .lastOrError
      yield JsonRpc.decodeResponse(line) match
        case Right(resp) => resp
        case Left(err) => JsonRpc.Response.fail(request.id, err)
    }

  /** [[call]] resolving the daemon's loopback endpoint from its port-discovery file first (the host-client form). Fails
    * if the file is absent (no daemon) or the connect fails.
    */
  def call(
      portFile: os.Path,
      request: JsonRpc.Request,
      network: Network[IO]
  ): IO[JsonRpc.Response] =
    DaemonAddress.readLoopback(portFile).flatMap(call(_, request, network))

  /** Port-file [[call]] with the default network. */
  def call(portFile: os.Path, request: JsonRpc.Request): IO[JsonRpc.Response] =
    call(portFile, request, Network.forIO)

  /** [[call]] (resolved address) with a bounded connect-retry (default: 20 attempts, 50ms apart ⇒ ~1s). Only the
    * *connect* is retried — a delivered error response is returned as-is.
    */
  def callWithRetry(
      address: DaemonAddress,
      request: JsonRpc.Request,
      attempts: Int = 20,
      delay: FiniteDuration = 50.millis,
      network: Network[IO] = Network.forIO
  ): IO[JsonRpc.Response] =
    call(address, request, network).handleErrorWith { err =>
      if attempts <= 1 then IO.raiseError(err)
      else IO.sleep(delay) *> callWithRetry(address, request, attempts - 1, delay, network)
    }

  /** Port-file [[callWithRetry]]: re-reads the discovery file each attempt, so a missing-file (daemon not yet bound) as
    * well as a connect-refused is ridden out across the start-up race.
    */
  def callWithRetry(
      portFile: os.Path,
      request: JsonRpc.Request,
      attempts: Int,
      delay: FiniteDuration,
      network: Network[IO]
  ): IO[JsonRpc.Response] =
    call(portFile, request, network).handleErrorWith { err =>
      if attempts <= 1 then IO.raiseError(err)
      else IO.sleep(delay) *> callWithRetry(portFile, request, attempts - 1, delay, network)
    }

  /** Port-file [[callWithRetry]] with the default attempts/delay/network. */
  def callWithRetry(portFile: os.Path, request: JsonRpc.Request): IO[JsonRpc.Response] =
    callWithRetry(portFile, request, 20, 50.millis, Network.forIO)

  /** Open a connection, send a streaming `subscribe` request once, and emit each successive response line the daemon
    * pushes back as a [[JsonRpc.Response]] (Task 4.1.4 — the aggregated per-worker feed). The stream ends when the
    * daemon closes the connection (clean shutdown) or the consumer cancels (e.g. `.take(n)` / surrounding fiber
    * cancellation), which releases the socket and, server-side, the daemon's feed subscription. A malformed response
    * line is surfaced as a failure response carrying the request id rather than aborting the stream.
    */
  def subscribe(
      address: DaemonAddress,
      request: JsonRpc.Request,
      network: Network[IO] = Network.forIO
  ): Stream[IO, JsonRpc.Response] =
    Stream.resource(network.client(address.socketAddress)).flatMap { socket =>
      val send = Stream
        .emit(JsonRpc.encodeRequest(request) + "\n")
        .through(text.utf8.encode)
        .through(socket.writes)
      val receive = socket.reads
        .through(text.utf8.decode)
        .through(text.lines)
        .filter(_.nonEmpty)
        .map(line =>
          JsonRpc.decodeResponse(line) match
            case Right(resp) => resp
            case Left(err) => JsonRpc.Response.fail(request.id, err)
        )
      send.drain ++ receive
    }

  /** [[subscribe]] resolving the daemon's loopback endpoint from its port-discovery file first (the host-client form).
    */
  def subscribe(
      portFile: os.Path,
      request: JsonRpc.Request,
      network: Network[IO]
  ): Stream[IO, JsonRpc.Response] =
    Stream.eval(DaemonAddress.readLoopback(portFile)).flatMap(subscribe(_, request, network))

  /** Port-file [[subscribe]] with the default network. */
  def subscribe(portFile: os.Path, request: JsonRpc.Request): Stream[IO, JsonRpc.Response] =
    subscribe(portFile, request, Network.forIO)
