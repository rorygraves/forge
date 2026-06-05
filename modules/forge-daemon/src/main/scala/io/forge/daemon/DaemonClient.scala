package io.forge.daemon

import cats.effect.IO
import fs2.{text, Stream}
import fs2.io.net.unixsocket.{UnixSocketAddress, UnixSockets}

import scala.concurrent.duration.*

/** JSON-RPC client over the daemon's Unix-domain socket (Slice-4.1 spike Task 4.1.1). The CLI's `forge daemon status`
  * (Task 4.1.3) and later the TUI cockpit (4.4) are built on this.
  *
  * [[call]] opens a fresh connection, writes one request line, and reads back the single response line. The connection
  * stays open for the read (fs2's `Socket.writes` does not half-close), so the server's reply is received on the same
  * socket. [[callWithRetry]] tolerates the start-up race where the CLI connects a hair before the just-started daemon
  * has bound the socket.
  */
object DaemonClient:

  /** A single request/response round-trip. Fails (`IO` error) if the socket can't be connected (no daemon) — callers
    * that race a daemon start should use [[callWithRetry]].
    */
  def call(
      socketPath: os.Path,
      request: JsonRpc.Request,
      sockets: UnixSockets[IO] = UnixSockets.forIO
  ): IO[JsonRpc.Response] =
    sockets.client(UnixSocketAddress(socketPath.toString)).use { socket =>
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

  /** [[call]] with a bounded connect-retry (default: 20 attempts, 50ms apart ⇒ ~1s) for the start-up race. Only the
    * *connect* is retried — a delivered error response is returned as-is.
    */
  def callWithRetry(
      socketPath: os.Path,
      request: JsonRpc.Request,
      attempts: Int = 20,
      delay: FiniteDuration = 50.millis,
      sockets: UnixSockets[IO] = UnixSockets.forIO
  ): IO[JsonRpc.Response] =
    call(socketPath, request, sockets).handleErrorWith { err =>
      if attempts <= 1 then IO.raiseError(err)
      else IO.sleep(delay) *> callWithRetry(socketPath, request, attempts - 1, delay, sockets)
    }
