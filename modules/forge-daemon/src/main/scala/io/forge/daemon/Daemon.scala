package io.forge.daemon

import cats.effect.IO
import cats.effect.kernel.Deferred
import fs2.Stream
import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.instance.{InstanceEvent, InstanceState}

import scala.concurrent.duration.*

/** Phase-4 §6.1/§6.3 — the long-running daemon's serving loop (Slice 4.1, Tasks 4.1.3 / 4.1.4).
  *
  * Pairs a [[DaemonState]] (the rebuilt-from-log snapshot, §6.4) with the [[DaemonSocketServer]] transport: [[handler]]
  * answers the 4.1 control + worker surface (`status` → the snapshot; `register-worker` / `worker-status` /
  * `worker-event` → single-writer appends, B3 event export; `subscribe` → the live aggregated per-worker feed;
  * `shutdown` → signal a clean stop) and [[serveUntilShutdown]] binds the instance socket until the shutdown signal
  * fires.
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

  /** Build the RPC handler for the 4.1.3/4.1.4 control + worker surface.
    *
    *   - `status` → the live snapshot (unary).
    *   - `register-worker` (`{workerId, repo, feature}`) / `worker-status` (`{workerId, status}`) / `worker-event`
    *     (`{workerId, event}`) → a single-writer [[DaemonState.record]] append (B3 event export), unary. Malformed
    *     params answer with `InvalidParams` rather than tearing down the connection.
    *   - `create-workstream` (`{goal}`) → allocate a [[WorkstreamId]] and append a `workstream.created` (Task 4.2.4),
    *     answering `{workstreamId, goal}`. The `status` snapshot then reflects the new workstream + its `attention`
    *     projection; `forge workstream list` / `worker list` render it client-side.
    *   - `spawn-worker` (`{workstreamId, repo, feature}`) → drive the [[Supervisor]] seam (Task 4.2.5): provision an
    *     isolated clone, launch the `forge worker` child, record `worker.spawned`, and activate the workstream. Answers
    *     `{workerId}`; a supervisor refusal is an `InternalError` carrying the reason.
    *   - `subscribe` → the live aggregated per-worker feed ([[DaemonState.subscribe]]), one response line per event,
    *     seeded with the rebuilt exported-feed tail. Long-lived: the connection stays open until the client disconnects
    *     or the daemon shuts down.
    *   - `shutdown` → ack, then (after [[ShutdownGrace]], on a forked fiber) complete `shutdown`, which
    *     [[serveUntilShutdown]] watches.
    */
  def handler(
      state: DaemonState,
      shutdown: Deferred[IO, Unit],
      supervisor: Supervisor = Supervisor.noop
  ): DaemonSocketServer.Handler = {
    case req if req.method == "status" =>
      Stream.eval(state.snapshot.map(s => JsonRpc.Response.ok(req.id, s.toStatusJson)))
    case req if req.method == "register-worker" =>
      Stream.eval(registerWorker(state, req))
    case req if req.method == "worker-status" =>
      Stream.eval(workerStatus(state, req))
    case req if req.method == "worker-event" =>
      Stream.eval(workerEvent(state, req))
    case req if req.method == "create-workstream" =>
      Stream.eval(createWorkstream(state, req))
    case req if req.method == "spawn-worker" =>
      Stream.eval(spawnWorker(supervisor, req))
    case req if req.method == "subscribe" =>
      state.subscribe().map(event => JsonRpc.Response.ok(req.id, eventWire(event)))
    case req if req.method == "shutdown" =>
      Stream.eval(
        (IO.sleep(ShutdownGrace) *> shutdown.complete(()).void).start
          .as(JsonRpc.Response.ok(req.id, ujson.Obj("stopped" -> ujson.Bool(true))))
      )
    case req =>
      Stream.emit(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.methodNotFound(req.method)))
  }

  // --- worker control methods (single-writer appends to the instance log) ------

  /** `register-worker` (`{workerId, repo, feature}`) → a `worker.registered` append seeding a fresh worker record. */
  private def registerWorker(state: DaemonState, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        repo <- strField(req.params, "repo")
        featureRaw <- strField(req.params, "feature")
        feature <- FeatureId.fromString(featureRaw).left.map(reason => s"invalid 'feature': $reason")
      yield InstanceEvent.WorkerRegistered(workerId, repo, feature)
    recordOrReject(state, req, parsed) {
      case InstanceEvent.WorkerRegistered(workerId, _, _) =>
        ujson.Obj("registered" -> ujson.Bool(true), "workerId" -> ujson.Str(workerId))
      case _ => ujson.Obj("registered" -> ujson.Bool(true))
    }

  /** `worker-status` (`{workerId, status}`) → a `worker.status` append mirroring the worker's latest FSM-state name. */
  private def workerStatus(state: DaemonState, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        status <- strField(req.params, "status")
      yield InstanceEvent.WorkerStatus(workerId, status)
    recordOrReject(state, req, parsed)(_ => ujson.Obj("accepted" -> ujson.Bool(true)))

  /** `create-workstream` (`{goal}`) → allocate the next [[WorkstreamId]] and append a `workstream.created` seeding a
    * `Planning` workstream; answers `{workstreamId, goal}`. Allocation + append run atomically under the single-writer
    * gate ([[DaemonState.modify]]), so two concurrent `create-workstream` calls (the server multiplexes connections
    * with `parJoinUnbounded`) can never read the same snapshot and both choose `ws-N` — the second sees the first's
    * `workstream.created` already folded in and picks `ws-N+1`.
    */
  private def createWorkstream(state: DaemonState, req: JsonRpc.Request): IO[JsonRpc.Response] =
    strField(req.params, "goal") match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right(goal) =>
        state
          .modify { snap =>
            val id = nextWorkstreamId(snap)
            IO.pure((Vector(InstanceEvent.WorkstreamCreated(id, goal)), id))
          }
          .map(id =>
            JsonRpc.Response.ok(req.id, ujson.Obj("workstreamId" -> ujson.Str(id.value), "goal" -> ujson.Str(goal)))
          )

  /** The next `ws-<n>` id: one past the largest numeric suffix already in use, or `ws-1` for the first. Max-suffix (not
    * count) so an id is never reused even if a workstream is later abandoned.
    */
  private[daemon] def nextWorkstreamId(state: InstanceState): WorkstreamId =
    val used = state.workstreams.flatMap { ws =>
      ws.id.value match
        case s"ws-$n" => n.toIntOption
        case _ => None
    }
    WorkstreamId(s"ws-${(used.maxOption.getOrElse(0)) + 1}")

  /** `worker-event` (`{workerId, event}`) → a `worker.event` append carrying one exported per-feature event verbatim.
    */
  private def workerEvent(state: DaemonState, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        event <- field(req.params, "event")
      yield InstanceEvent.WorkerEvent(workerId, event)
    recordOrReject(state, req, parsed)(_ => ujson.Obj("accepted" -> ujson.Bool(true)))

  /** `spawn-worker` (`{workstreamId, repo, feature}`) → drive the [[Supervisor]] seam (Task 4.2.5): provision an
    * isolated clone, launch the `forge worker` child, record `worker.spawned`, and activate the workstream. Answers
    * `{workerId}` on success. A malformed param is `InvalidParams`; a supervisor refusal (unknown workstream, a
    * clone/spawn failure, or a control-only daemon with no supervisor) is `InternalError` carrying the reason — the
    * request was well-formed, the daemon could not satisfy it.
    */
  private def spawnWorker(supervisor: Supervisor, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workstreamRaw <- strField(req.params, "workstreamId")
        workstreamId <- WorkstreamId.fromString(workstreamRaw).left.map(reason => s"invalid 'workstreamId': $reason")
        repo <- strField(req.params, "repo")
        featureRaw <- strField(req.params, "feature")
        feature <- FeatureId.fromString(featureRaw).left.map(reason => s"invalid 'feature': $reason")
      yield (workstreamId, repo, feature)
    parsed match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right((workstreamId, repo, feature)) =>
        supervisor.spawnWorker(workstreamId, repo, feature).map {
          case Right(workerId) =>
            JsonRpc.Response.ok(
              req.id,
              ujson.Obj("workerId" -> ujson.Str(workerId), "workstreamId" -> ujson.Str(workstreamId.value))
            )
          case Left(reason) => JsonRpc.Response.fail(req.id, JsonRpc.RpcError.internal(reason))
        }

  /** Record a parsed event (single-writer append) and answer `ok(result(event))`, or answer `InvalidParams` when the
    * params failed to parse — a malformed worker request must not crash the connection.
    */
  private def recordOrReject(state: DaemonState, req: JsonRpc.Request, parsed: Either[String, InstanceEvent])(
      result: InstanceEvent => ujson.Value
  ): IO[JsonRpc.Response] =
    parsed match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right(event) => state.record(event).as(JsonRpc.Response.ok(req.id, result(event)))

  /** The on-the-wire JSON for one streamed feed event: `{kind, payload}` (the same open-`kind` shape the instance log
    * persists, via [[InstanceEvent.kindOf]] / [[InstanceEvent.payloadOf]]), so a subscriber decodes it the same way the
    * log fold does.
    */
  private def eventWire(event: InstanceEvent): ujson.Value =
    ujson.Obj("kind" -> ujson.Str(InstanceEvent.kindOf(event)), "payload" -> InstanceEvent.payloadOf(event))

  /** Extract a required string field from a JSON-RPC `params` object, or a human reason for an `InvalidParams` error.
    */
  private def strField(params: ujson.Value, field: String): Either[String, String] =
    params.objOpt.flatMap(_.get(field)).flatMap(_.strOpt).toRight(s"missing or non-string '$field'")

  /** Extract a required (arbitrary-typed) field from a JSON-RPC `params` object, or a reason for `InvalidParams`. */
  private def field(params: ujson.Value, name: String): Either[String, ujson.Value] =
    params.objOpt.flatMap(_.get(name)).toRight(s"missing '$name'")

  /** Serve the instance socket until `shutdown` completes, then release it. Completes normally on shutdown; cancelling
    * the surrounding fiber (e.g. SIGINT under `IOApp`) also tears the socket down via the server resource's finalizer.
    */
  def serveUntilShutdown(
      socketPath: os.Path,
      state: DaemonState,
      shutdown: Deferred[IO, Unit],
      supervisor: Supervisor = Supervisor.noop
  ): IO[Unit] =
    DaemonSocketServer
      .serve(socketPath, handler(state, shutdown, supervisor))
      .interruptWhen(shutdown.get.attempt)
      .compile
      .drain
