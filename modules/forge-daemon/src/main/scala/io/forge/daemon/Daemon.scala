package io.forge.daemon

import cats.effect.IO
import cats.effect.kernel.Deferred

import scala.concurrent.duration.*

/** Phase-4 §6.1/§6.3 — the long-running daemon's serving loop (Slice 4.1, Task 4.1.3).
  *
  * Pairs a [[DaemonState]] (the rebuilt-from-log snapshot, §6.4) with the [[DaemonSocketServer]] transport: [[handler]]
  * answers the 4.1 control surface (`status` → the snapshot, `shutdown` → signal a clean stop) and
  * [[serveUntilShutdown]] binds the instance socket until the shutdown signal fires.
  *
  * The supervisor *lifecycle* — acquiring the instance lock (the §13 [[io.forge.app.lock.FileProcessLock]] lives in
  * `forge-app`), resolving the instance, and the `forge daemon` CLI — composes in `forge-app` around this loop; this
  * module owns only the durable state + the JSON-RPC serving mechanics.
  */
object Daemon:

  /** Grace delay between acking a `shutdown` request and actually firing the stop signal, so the ack line flushes to
    * the client before [[serveUntilShutdown]] interrupts the connection. Without it a clean `forge daemon stop` would
    * race the interrupt and see a reset connection instead of its ack. The crash-recovery path (Task 4.1.5) hard-kills
    * the daemon and so does not depend on this clean-stop handshake.
    */
  val ShutdownGrace: FiniteDuration = 250.millis

  /** Build the RPC handler for the 4.1.3 control surface. `status` returns the live snapshot; `shutdown` acks then
    * (after [[ShutdownGrace]], on a forked fiber) completes `shutdown`, which [[serveUntilShutdown]] watches.
    */
  def handler(state: DaemonState, shutdown: Deferred[IO, Unit]): DaemonSocketServer.Handler = {
    case req if req.method == "status" =>
      state.snapshot.map(s => JsonRpc.Response.ok(req.id, s.toStatusJson))
    case req if req.method == "shutdown" =>
      (IO.sleep(ShutdownGrace) *> shutdown.complete(()).void).start
        .as(JsonRpc.Response.ok(req.id, ujson.Obj("stopped" -> ujson.Bool(true))))
    case req =>
      IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.methodNotFound(req.method)))
  }

  /** Serve the instance socket until `shutdown` completes, then release it. Completes normally on shutdown; cancelling
    * the surrounding fiber (e.g. SIGINT under `IOApp`) also tears the socket down via the server resource's finalizer.
    */
  def serveUntilShutdown(socketPath: os.Path, state: DaemonState, shutdown: Deferred[IO, Unit]): IO[Unit] =
    DaemonSocketServer
      .serve(socketPath, handler(state, shutdown))
      .interruptWhen(shutdown.get.attempt)
      .compile
      .drain
