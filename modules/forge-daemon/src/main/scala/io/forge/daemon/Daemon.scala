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
    *   - `broker-credentials` (`{workerId, repo}`) → drive the [[CredentialBroker]] seam (Task 4.3.3, O6): resolve the
    *     short-lived host-isolated credentials a containerised worker needs and answer `{env, missing}`. A broker
    *     refusal (no broker, or a required secret unconfigured) is an `InternalError` carrying the reason.
    *   - `reserve-budget` (`{workerId, estimateUsd}`) → the B2 reservation handshake (Task 4.3.5): under the
    *     single-writer gate, authorize `estimate` against the per-workstream / per-instance [[BudgetPolicy]] caps and
    *     record a durable `budget.reserve` + `budget.grant`/`budget.refuse`, answering `{granted, reservationId?,
    *     reason?}`. A *refuse* is a normal protocol outcome (`granted: false`), **not** a JSON-RPC error — the worker
    *     holds on it rather than failing. Finalization is implicit: the worker's exported `cost.update` (a
    *     `worker-event`) clears the reservation + fans its actual spend into the committed totals.
    *   - `subscribe` → the live aggregated per-worker feed ([[DaemonState.subscribe]]), one response line per event,
    *     seeded with the rebuilt exported-feed tail. Long-lived: the connection stays open until the client disconnects
    *     or the daemon shuts down.
    *   - `shutdown` → ack, then (after [[ShutdownGrace]], on a forked fiber) complete `shutdown`, which
    *     [[serveUntilShutdown]] watches.
    */
  def handler(
      state: DaemonState,
      shutdown: Deferred[IO, Unit],
      supervisor: Supervisor = Supervisor.noop,
      broker: CredentialBroker = CredentialBroker.noop,
      budget: BudgetPolicy = BudgetPolicy.unlimited
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
    case req if req.method == "broker-credentials" =>
      Stream.eval(brokerCredentials(broker, req))
    case req if req.method == "reserve-budget" =>
      Stream.eval(reserveBudget(state, budget, req))
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
    *
    * When the exported event is a §19 `cost.update` and the worker holds an outstanding B2 reservation, the daemon also
    * appends a `budget.finalize` in the **same** atomic write (under [[DaemonState.modify]]) — the implicit B2
    * finalization (Task 4.3.5): the `cost.update` fan-in (in the fold) moves the actual spend into the committed
    * totals, and the finalize releases the worker's outstanding reservation. Reading the pre-event snapshot's
    * reservation under the gate is what makes the pair atomic with respect to a concurrent reserver.
    */
  private def workerEvent(state: DaemonState, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        event <- field(req.params, "event")
      yield (workerId, event)
    parsed match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right((workerId, exported)) =>
        state
          .modify { snap =>
            val workerEvent = InstanceEvent.WorkerEvent(workerId, exported)
            val finalize =
              for
                usd <- InstanceEvent.costUpdateUsd(exported)
                reservation <- snap.reservations.get(workerId)
              yield InstanceEvent.BudgetFinalize(workerId, reservation.reservationId, usd)
            IO.pure((workerEvent +: finalize.toVector, ()))
          }
          .as(JsonRpc.Response.ok(req.id, ujson.Obj("accepted" -> ujson.Bool(true))))

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

  /** `broker-credentials` (`{workerId, repo}`) → drive the [[CredentialBroker]] seam (Task 4.3.3, O6): resolve the
    * short-lived, host-isolated credentials worker `workerId` needs for `repo` and answer `{env, missing}` — the env
    * entries the worker sets in its own process environment (a scoped `gh` token + any configured agent auth) and the
    * canonical names of optional credentials the host had not configured. A malformed param is `InvalidParams`; a
    * broker refusal (no broker wired, or a required secret unconfigured — O6 refuses rather than falling back to the
    * host-wide credential) is `InternalError` carrying the reason. The secret values cross only the (mounted,
    * in-container) control socket — never the instance log, never the container spec.
    */
  private def brokerCredentials(broker: CredentialBroker, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        repo <- strField(req.params, "repo")
      yield (workerId, repo)
    parsed match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right((workerId, repo)) =>
        broker.brokerFor(workerId, repo).map {
          case Right(creds) => JsonRpc.Response.ok(req.id, credentialsWire(creds))
          case Left(err) => JsonRpc.Response.fail(req.id, JsonRpc.RpcError.internal(err.message))
        }

  /** `reserve-budget` (`{workerId, estimateUsd}`) → the B2 reservation handshake (Task 4.3.5). Resolves the worker's
    * owning workstream from its record, then **under the single-writer gate** ([[DaemonState.modify]] — so two
    * concurrent reservers cannot both win against the same headroom) authorizes `estimate` against the [[BudgetPolicy]]
    * caps. On grant: append `budget.reserve` + `budget.grant` (a fresh `reservationId`) and answer `{granted: true,
    * reservationId}`. On refuse: append `budget.reserve` + `budget.refuse` and answer `{granted: false, reason}`. A
    * refuse is a normal protocol outcome (a JSON-RPC *success* with `granted: false`), not an error — the worker holds.
    * A malformed param is `InvalidParams`. The `reservationId` is stamped into the durable `budget.grant`, so a replay
    * rebuilds the same reservation table deterministically.
    */
  private def reserveBudget(state: DaemonState, budget: BudgetPolicy, req: JsonRpc.Request): IO[JsonRpc.Response] =
    val parsed =
      for
        workerId <- strField(req.params, "workerId")
        estimate <- numField(req.params, "estimateUsd")
      yield (workerId, BigDecimal(estimate))
    parsed match
      case Left(detail) => IO.pure(JsonRpc.Response.fail(req.id, JsonRpc.RpcError.invalidParams(detail)))
      case Right((workerId, estimate)) =>
        state
          .modify { snap =>
            val workstreamId = snap.worker(workerId).flatMap(_.workstreamId)
            if budget.authorize(snap, workerId, workstreamId, estimate) then
              IO(java.util.UUID.randomUUID().toString).map { reservationId =>
                val events = Vector(
                  InstanceEvent.BudgetReserve(workerId, workstreamId, estimate),
                  InstanceEvent.BudgetGrant(workerId, workstreamId, reservationId, estimate)
                )
                (events, grantWire(reservationId))
              }
            else
              val reason =
                s"budget cap reached: reserving $$$estimate would oversubscribe the configured per-workstream/" +
                  "per-instance cap"
              val events = Vector(
                InstanceEvent.BudgetReserve(workerId, workstreamId, estimate),
                InstanceEvent.BudgetRefuse(workerId, workstreamId, estimate, reason)
              )
              IO.pure((events, refuseWire(reason)))
          }
          .map(body => JsonRpc.Response.ok(req.id, body))

  /** The on-the-wire JSON for a granted reservation: `{granted: true, reservationId}`. */
  private def grantWire(reservationId: String): ujson.Value =
    ujson.Obj("granted" -> ujson.Bool(true), "reservationId" -> ujson.Str(reservationId))

  /** The on-the-wire JSON for a refused reservation: `{granted: false, reason}`. A normal protocol outcome the worker
    * holds on — not a JSON-RPC error.
    */
  private def refuseWire(reason: String): ujson.Value =
    ujson.Obj("granted" -> ujson.Bool(false), "reason" -> ujson.Str(reason))

  /** The on-the-wire JSON for brokered credentials: `{env: {<key>: <secret>}, missing: [<key>]}`. The `env` map carries
    * the secret values the worker injects into its environment; `missing` lists optional agent-auth keys the host had
    * not configured (non-secret, for the worker's diagnostics).
    */
  private def credentialsWire(creds: BrokeredCredentials): ujson.Value =
    ujson.Obj(
      "env" -> ujson.Obj.from(creds.env.view.mapValues(ujson.Str(_)).toSeq),
      "missing" -> ujson.Arr(creds.missing.map(ujson.Str(_))*)
    )

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

  /** Extract a required numeric field from a JSON-RPC `params` object, or a human reason for an `InvalidParams` error.
    */
  private def numField(params: ujson.Value, field: String): Either[String, Double] =
    params.objOpt.flatMap(_.get(field)).flatMap(_.numOpt).toRight(s"missing or non-numeric '$field'")

  /** Serve the instance socket until `shutdown` completes, then release it. Completes normally on shutdown; cancelling
    * the surrounding fiber (e.g. SIGINT under `IOApp`) also tears the socket down via the server resource's finalizer.
    */
  def serveUntilShutdown(
      socketPath: os.Path,
      state: DaemonState,
      shutdown: Deferred[IO, Unit],
      supervisor: Supervisor = Supervisor.noop,
      broker: CredentialBroker = CredentialBroker.noop,
      budget: BudgetPolicy = BudgetPolicy.unlimited
  ): IO[Unit] =
    DaemonSocketServer
      .serve(socketPath, handler(state, shutdown, supervisor, broker, budget))
      .interruptWhen(shutdown.get.attempt)
      .compile
      .drain
