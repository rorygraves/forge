package io.forge.instance

import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.core.Json.given

import java.time.Instant
import upickle.default.ReadWriter
import upickle.implicits.key

/** Phase-4 §6.4 / O8 — the semantic event alphabet of the durable **instance action log** (Slice 4.1, Task 4.1.2).
  *
  * The instance log mirrors the per-feature `io.forge.core.log` durability idiom one level up: where a feature's log
  * records FSM transitions over its manifest, the *instance* log records the daemon's view of its workers (and its own
  * boot). It is written by the daemon alone (single-writer, contract §6.3.1) and is the source of truth a restarted
  * daemon rebuilds [[InstanceState]] from.
  *
  * The minimal 4.1 set is deliberately small — enough to register a worker, track its status, and carry its exported
  * per-feature event feed (B3). The B2 budget-reservation records (`budget.*`) slot in later **without a schema
  * break**: the enum is `sealed`, the on-disk [[InstanceLogRecord]] keeps an open `kind: String` + a `ujson.Value`
  * payload (the same open-`kind` design `io.forge.core.log.Action` uses), and the log file is versioned via the state
  * cache's `schemaVersion`. An unknown future `kind` therefore decodes to "skip" in [[RebuildInstanceState]] rather
  * than crashing an older daemon's fold.
  *
  * A "worker" in 4.1 is an instance-store *record* + an exported event feed, not yet a daemon-spawned container process
  * (that is 4.2/4.3). `workerId` is a plain `String` for the same reason — the opaque worker-id type arrives with the
  * worker process.
  */
enum InstanceEvent:

  /** The daemon bound the instance and began serving (`daemon.started`). Appended on every boot; the fold counts boots
    * so the rebuilt status view can show how many times the supervisor has cycled. `pid` is forensic only.
    */
  case DaemonStarted(pid: Long)

  /** A worker record was registered against the instance (`worker.registered`): its id, the registered source `repo`
    * path, and the `feature` it drives. Seeds a fresh [[WorkerRecord]] in the rebuilt state.
    */
  case WorkerRegistered(workerId: String, repo: String, feature: FeatureId)

  /** A worker reported a new status (`worker.status`) — the worker's FSM-state name, mirrored at the instance level.
    * Kept as an open `String` (not a re-modelled FSM enum) because the instance view only forwards what the worker
    * exports; the rich state stays behind the worker boundary (contract §9).
    */
  case WorkerStatus(workerId: String, status: String)

  /** A worker exported one per-feature event (`worker.event`, B3 event export). `event` is the raw per-feature
    * `Action`-shaped JSON tree, carried verbatim into the worker's exported-feed tail in [[InstanceState]]; the daemon
    * does not reinterpret it.
    */
  case WorkerEvent(workerId: String, event: ujson.Value)

  /** A workstream coordination object was created (`workstream.created`, §5, Task 4.2.4): its daemon-allocated id and
    * its human `goal`. Seeds a fresh [[Workstream]] in `Planning` in the rebuilt state.
    */
  case WorkstreamCreated(workstreamId: WorkstreamId, goal: String)

  /** A workstream's scalar lifecycle advanced (`workstream.status`, §5): `Planning → Active → Done/Abandoned`. Carried
    * as a [[WorkstreamStatus]] (serialised by name); an unknown name decodes to `None` (skip), like an unknown `kind`.
    */
  case WorkstreamStatusChanged(workstreamId: WorkstreamId, status: WorkstreamStatus)

  /** The daemon spawned a worker **process** into a workstream (`worker.spawned`, §6.2, Task 4.2.4 — emitted by the
    * supervisor in 4.2.5). Unlike [[WorkerRegistered]] (the worker phoning home), this is the daemon-side record: it
    * knows the worker's `workstreamId`, its isolated `checkoutRoot` (the clone, O10), and the child `pid` before the
    * worker connects. Seeds/updates the [[WorkerRecord]] with those daemon-known fields and appends the worker to the
    * workstream's ordering.
    */
  case WorkerSpawned(
      workerId: String,
      workstreamId: WorkstreamId,
      repo: String,
      feature: FeatureId,
      checkoutRoot: String,
      pid: Long
  )

  /** A spawned worker process exited (`worker.exited`, §6.2/§6.4): its `exitCode`. Clears the worker's liveness so the
    * supervisor's restart reconciliation (4.2.5) treats it as terminated rather than re-probing its pid.
    */
  case WorkerExited(workerId: String, exitCode: Int)

object InstanceEvent:

  // --- well-known `kind` discriminators (the open string the on-disk record carries) ---
  val DaemonStartedKind: String = "daemon.started"
  val WorkerRegisteredKind: String = "worker.registered"
  val WorkerStatusKind: String = "worker.status"
  val WorkerEventKind: String = "worker.event"
  val WorkstreamCreatedKind: String = "workstream.created"
  val WorkstreamStatusKind: String = "workstream.status"
  val WorkerSpawnedKind: String = "worker.spawned"
  val WorkerExitedKind: String = "worker.exited"

  /** The on-disk `kind` for an event. */
  def kindOf(e: InstanceEvent): String = e match
    case _: DaemonStarted => DaemonStartedKind
    case _: WorkerRegistered => WorkerRegisteredKind
    case _: WorkerStatus => WorkerStatusKind
    case _: WorkerEvent => WorkerEventKind
    case _: WorkstreamCreated => WorkstreamCreatedKind
    case _: WorkstreamStatusChanged => WorkstreamStatusKind
    case _: WorkerSpawned => WorkerSpawnedKind
    case _: WorkerExited => WorkerExitedKind

  /** The event-specific payload (the `payload` field of [[InstanceLogRecord]]). Mirrors how `Action.payload` carries
    * per-`kind` data as a raw JSON tree rather than a closed schema.
    */
  def payloadOf(e: InstanceEvent): ujson.Value = e match
    case DaemonStarted(pid) =>
      ujson.Obj("pid" -> ujson.Num(pid.toDouble))
    case WorkerRegistered(workerId, repo, feature) =>
      ujson.Obj("workerId" -> ujson.Str(workerId), "repo" -> ujson.Str(repo), "feature" -> ujson.Str(feature.value))
    case WorkerStatus(workerId, status) =>
      ujson.Obj("workerId" -> ujson.Str(workerId), "status" -> ujson.Str(status))
    case WorkerEvent(workerId, event) =>
      ujson.Obj("workerId" -> ujson.Str(workerId), "event" -> event)
    case WorkstreamCreated(workstreamId, goal) =>
      ujson.Obj("workstreamId" -> ujson.Str(workstreamId.value), "goal" -> ujson.Str(goal))
    case WorkstreamStatusChanged(workstreamId, status) =>
      ujson.Obj("workstreamId" -> ujson.Str(workstreamId.value), "status" -> ujson.Str(WorkstreamStatus.name(status)))
    case WorkerSpawned(workerId, workstreamId, repo, feature, checkoutRoot, pid) =>
      ujson.Obj(
        "workerId" -> ujson.Str(workerId),
        "workstreamId" -> ujson.Str(workstreamId.value),
        "repo" -> ujson.Str(repo),
        "feature" -> ujson.Str(feature.value),
        "checkoutRoot" -> ujson.Str(checkoutRoot),
        "pid" -> ujson.Num(pid.toDouble)
      )
    case WorkerExited(workerId, exitCode) =>
      ujson.Obj("workerId" -> ujson.Str(workerId), "exitCode" -> ujson.Num(exitCode.toDouble))

  /** This event as an unstamped [[InstanceEventDraft]] — the daemon hands a draft to `FileInstanceLog.append`, which
    * stamps it with the next `seq` and a write-time `at` into a durable [[InstanceLogRecord]].
    */
  def toDraft(e: InstanceEvent): InstanceEventDraft = InstanceEventDraft(kindOf(e), payloadOf(e))

  /** Reconstruct the semantic event from a persisted `(kind, payload)`. Returns `None` for any `kind` this build does
    * not recognise — an unknown future variant (`budget.*`) or a log-level marker (the `harness.error` truncation
    * recovery entry [[FileInstanceLog]] writes). [[RebuildInstanceState]] treats `None` as a no-op, mirroring the
    * unknown-`kind` no-op in the per-feature `Replay`. A *known* kind with a malformed payload also yields `None`
    * rather than throwing, so the fold stays total over a hand-edited log.
    */
  def decode(kind: String, payload: ujson.Value): Option[InstanceEvent] =
    val obj = payload.objOpt
    def str(field: String): Option[String] = obj.flatMap(_.get(field)).flatMap(_.strOpt)
    def num(field: String): Option[Double] = obj.flatMap(_.get(field)).flatMap(_.numOpt)
    def workstreamId(field: String): Option[WorkstreamId] = str(field).flatMap(WorkstreamId.fromString(_).toOption)
    kind match
      case DaemonStartedKind =>
        obj.flatMap(_.get("pid")).flatMap(_.numOpt).map(n => DaemonStarted(n.toLong))
      case WorkerRegisteredKind =>
        for
          workerId <- str("workerId")
          repo <- str("repo")
          feature <- str("feature").flatMap(FeatureId.fromString(_).toOption)
        yield WorkerRegistered(workerId, repo, feature)
      case WorkerStatusKind =>
        for
          workerId <- str("workerId")
          status <- str("status")
        yield WorkerStatus(workerId, status)
      case WorkerEventKind =>
        for
          workerId <- str("workerId")
          event <- obj.flatMap(_.get("event"))
        yield WorkerEvent(workerId, event)
      case WorkstreamCreatedKind =>
        for
          id <- workstreamId("workstreamId")
          goal <- str("goal")
        yield WorkstreamCreated(id, goal)
      case WorkstreamStatusKind =>
        for
          id <- workstreamId("workstreamId")
          status <- str("status").flatMap(WorkstreamStatus.fromString)
        yield WorkstreamStatusChanged(id, status)
      case WorkerSpawnedKind =>
        for
          workerId <- str("workerId")
          id <- workstreamId("workstreamId")
          repo <- str("repo")
          feature <- str("feature").flatMap(FeatureId.fromString(_).toOption)
          checkoutRoot <- str("checkoutRoot")
          pid <- num("pid")
        yield WorkerSpawned(workerId, id, repo, feature, checkoutRoot, pid.toLong)
      case WorkerExitedKind =>
        for
          workerId <- str("workerId")
          exitCode <- num("exitCode")
        yield WorkerExited(workerId, exitCode.toInt)
      case _ => None

/** §6.4 — one entry in the durable instance log. The instance-scoped analogue of `io.forge.core.log.Action`: the same
  * `{seq, ts, kind, payload}` wire skeleton (no `feature`/`piece`/`actor`/`role` — those are per-feature concerns
  * behind the worker boundary). `seq` is monotonic per instance (assigned by `FileInstanceLog.append`); the
  * `@key("ts")` rename keeps the on-disk field name aligned with the per-feature log. `payload` is a raw `ujson.Value`
  * so the open `kind` enum can carry per-variant shapes without a closed schema.
  */
final case class InstanceLogRecord(
    seq: Long,
    @key("ts") at: Instant,
    kind: String,
    payload: ujson.Value
) derives ReadWriter:
  /** The semantic event this record encodes, or `None` if its `kind` is unknown / its payload malformed. */
  def event: Option[InstanceEvent] = InstanceEvent.decode(kind, payload)

/** Unstamped sibling of [[InstanceLogRecord]] — the instance analogue of `io.forge.core.log.ActionDraft`. Producers
  * (the daemon, via [[InstanceEvent.toDraft]]) emit drafts; `FileInstanceLog.append` stamps a draft with the
  * daemon-allocated `seq` and write-time `at`. Never serialised on its own; the on-disk shape is always
  * [[InstanceLogRecord]].
  */
final case class InstanceEventDraft(kind: String, payload: ujson.Value):
  def stamp(seq: Long, at: Instant): InstanceLogRecord = InstanceLogRecord(seq, at, kind, payload)
