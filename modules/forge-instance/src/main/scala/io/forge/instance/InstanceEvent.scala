package io.forge.instance

import io.forge.core.FeatureId
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

object InstanceEvent:

  // --- well-known `kind` discriminators (the open string the on-disk record carries) ---
  val DaemonStartedKind: String = "daemon.started"
  val WorkerRegisteredKind: String = "worker.registered"
  val WorkerStatusKind: String = "worker.status"
  val WorkerEventKind: String = "worker.event"

  /** The on-disk `kind` for an event. */
  def kindOf(e: InstanceEvent): String = e match
    case _: DaemonStarted => DaemonStartedKind
    case _: WorkerRegistered => WorkerRegisteredKind
    case _: WorkerStatus => WorkerStatusKind
    case _: WorkerEvent => WorkerEventKind

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
