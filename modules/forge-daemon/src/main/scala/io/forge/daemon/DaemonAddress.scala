package io.forge.daemon

import cats.effect.IO
import com.comcast.ip4s.{Host, Port, SocketAddress}

/** A daemon **TCP endpoint** — the `host:port` the daemon's JSON-RPC server ([[DaemonSocketServer]]) binds and clients
  * ([[DaemonClient]]) connect to (Phase-4 §6.1/§6.3). Replaces the Slice-4.1 Unix-domain socket path: the daemon now
  * speaks TCP so a **containerised** worker (Slice 4.3) can reach it from inside its OCI container over
  * `host.docker.internal` rather than a bind-mounted socket file (which is unreliable across the Docker Desktop VM
  * boundary on macOS, the documented host).
  *
  * Discovery mirrors the old socket file: the daemon binds an **ephemeral** port and writes it to
  * [[io.forge.instance.Instance.portFile]] ([[writePortFile]]); host clients read it back ([[readLoopback]]) and
  * connect over loopback. A containerised worker can't read the host instance dir, so the supervisor passes it the full
  * `host.docker.internal:<port>` on argv (`--socket`), which it [[parse]]s.
  */
final case class DaemonAddress(host: String, port: Int):

  /** The `host:port` wire form (the `--socket` flag value the supervisor passes a containerised worker). */
  def render: String = s"$host:$port"

  /** The ip4s [[SocketAddress]] the fs2 TCP client connects to. The host (a loopback literal, or
    * `host.docker.internal`) and port are validated at construction sites ([[loopback]] / [[parse]]), so a failure here
    * is a programming error rather than a recoverable input.
    */
  private[daemon] def socketAddress: SocketAddress[Host] =
    val h = Host.fromString(host).getOrElse(throw new IllegalArgumentException(s"invalid daemon host '$host'"))
    val p = Port.fromInt(port).getOrElse(throw new IllegalArgumentException(s"invalid daemon port $port"))
    SocketAddress(h, p)

object DaemonAddress:

  /** Loopback host — host CLI/TUI clients and a host-process worker reach the daemon here. */
  val Loopback: String = "127.0.0.1"

  /** The hostname a container uses to reach the host (Docker Desktop built-in; on Linux it is wired via
    * `--add-host=host.docker.internal:host-gateway`, which [[io.forge.app.command.ContainerRuntime]] always adds).
    */
  val DockerHost: String = "host.docker.internal"

  /** A loopback endpoint on `port` — the host-client / host-worker address. */
  def loopback(port: Int): DaemonAddress = DaemonAddress(Loopback, port)

  /** A `host.docker.internal:port` endpoint — the address a containerised worker reaches the host daemon on. */
  def dockerHost(port: Int): DaemonAddress = DaemonAddress(DockerHost, port)

  /** Parse a `host:port` string (the `--socket` flag value a containerised worker receives). Splits on the **last**
    * colon so an IPv6-ish host is not mangled; rejects a missing colon or an out-of-range port.
    */
  def parse(s: String): Either[String, DaemonAddress] =
    s.lastIndexOf(':') match
      case -1 => Left(s"not a host:port daemon address: '$s'")
      case i =>
        val host = s.substring(0, i)
        val portStr = s.substring(i + 1)
        if host.isEmpty then Left(s"empty host in daemon address: '$s'")
        else
          portStr.toIntOption.filter(p => p >= 1 && p <= 65535) match
            case Some(p) => Right(DaemonAddress(host, p))
            case None => Left(s"invalid port in daemon address: '$s'")

  /** Write the bound `port` to the discovery file (the daemon does this once it knows its ephemeral port). The instance
    * dir already exists (the daemon holds its lock there). Single small line — clients tolerate a partial read by
    * retrying.
    */
  def writePortFile(portFile: os.Path, port: Int): IO[Unit] =
    IO.blocking(os.write.over(portFile, port.toString))

  /** Remove the discovery file on a clean daemon stop (the OS lock, not this file, is the liveness authority — a stale
    * file from a crash is simply overwritten on the next bind). Idempotent.
    */
  def deletePortFile(portFile: os.Path): IO[Unit] =
    IO.blocking(os.remove(portFile, checkExists = false)).void

  /** Read the daemon's bound port from the discovery file. Fails (`IO` error) if the file is absent (no daemon has
    * bound yet) or malformed — callers either map that to "no daemon running", retry through the start-up race
    * ([[DaemonClient.callWithRetry]]), or rebuild a [[dockerHost]] address (the container supervisor).
    */
  def readPort(portFile: os.Path): IO[Int] =
    IO.blocking(os.read(portFile).trim).flatMap { s =>
      s.toIntOption.filter(p => p >= 1 && p <= 65535) match
        case Some(p) => IO.pure(p)
        case None => IO.raiseError(new RuntimeException(s"malformed daemon port file $portFile: '$s'"))
    }

  /** Read the daemon's **loopback** endpoint from the discovery file (the host-client / host-worker address). */
  def readLoopback(portFile: os.Path): IO[DaemonAddress] = readPort(portFile).map(loopback)
