package io.forge.instance

import io.forge.core.FeatureId
import io.forge.core.Json.given

import upickle.default.ReadWriter

/** Phase-4 §6.4 / O8 — the rebuildable projection of the durable instance log (Slice 4.1, Task 4.1.2).
  *
  * The instance analogue of a feature's `Feature` projection: [[RebuildInstanceState.fold]] replays the
  * [[InstanceLogRecord]] stream into an [[InstanceState]] the daemon serves its `status` snapshot from. The §6.4
  * invariant mirrors §4's per-feature one — the **log is canonical**, the state cache ([[FileInstanceStateCache]]) is a
  * derivable convenience, so a restarted daemon can always reconstruct the exact same view from the log alone (the
  * Slice-4.1 exit criterion).
  */
object RebuildInstanceState:

  /** Bump when [[InstanceState]]'s persisted shape changes incompatibly. The instance-log records themselves stay
    * forward-compatible via the open `kind` (see [[InstanceEvent]]); this version guards the *cache* file only.
    */
  val CurrentSchemaVersion: Int = 1

  /** The empty state — no boots seen, no workers. The fold seed and the "fresh instance" value. */
  val empty: InstanceState = InstanceState(CurrentSchemaVersion, bootCount = 0, workers = Vector.empty)

  /** Pure fold of the instance log into [[InstanceState]]. Records are applied in `seq` order (the log's on-disk
    * order); a record whose `kind` this build does not recognise — a future `budget.*` variant or the `harness.error`
    * truncation-recovery marker [[FileInstanceLog]] writes — decodes to `None` and is skipped, exactly like the
    * unknown-`kind` no-op in the per-feature `Replay`. No I/O, no clock: hand it fixture records and assert.
    */
  def fold(records: Vector[InstanceLogRecord]): InstanceState =
    records.foldLeft(empty)((state, record) => record.event.fold(state)(applyEvent(state, _)))

  private def applyEvent(state: InstanceState, event: InstanceEvent): InstanceState =
    event match
      case InstanceEvent.DaemonStarted(_) =>
        state.copy(bootCount = state.bootCount + 1)

      case InstanceEvent.WorkerRegistered(workerId, repo, feature) =>
        // Upsert: a fresh worker seeds a record; a re-registration updates its repo/feature but preserves the status +
        // exported-feed tail already accumulated (idempotent-safe — registration is normally once-per-worker).
        state.updateWorker(workerId)(
          ifAbsent = WorkerRecord(workerId, repo, feature, WorkerRecord.RegisteredStatus, Vector.empty),
          ifPresent = _.copy(repo = repo, feature = feature)
        )

      case InstanceEvent.WorkerStatus(workerId, status) =>
        // A status for an unregistered worker is dropped (e.g. its `worker.registered` was lost to a truncation before
        // the recovery boundary) rather than inventing a record with no repo/feature.
        state.updateRegisteredWorker(workerId)(_.copy(status = status))

      case InstanceEvent.WorkerEvent(workerId, exported) =>
        state.updateRegisteredWorker(workerId)(w => w.copy(events = w.events :+ exported))

/** A worker as the daemon sees it (Slice 4.1) — a *record* plus its exported per-feature event feed, not yet a
  * supervised process (4.2/4.3). `repo` is the registered source checkout path; `feature` the feature it drives;
  * `status` the latest worker-reported FSM-state name (open string — the rich state lives behind the worker boundary);
  * `events` the exported-feed tail (B3), carried verbatim as the raw per-feature `Action`-shaped JSON.
  */
final case class WorkerRecord(
    workerId: String,
    repo: String,
    feature: FeatureId,
    status: String,
    events: Vector[ujson.Value]
) derives ReadWriter

object WorkerRecord:
  /** The status a worker carries from `worker.registered` until its first `worker.status`. */
  val RegisteredStatus: String = "registered"

/** The rebuildable instance projection (§6.4): how many times the supervisor has booted plus the per-worker records.
  * Persisted by [[FileInstanceStateCache]] and rebuilt by [[RebuildInstanceState.fold]]; the daemon serves its `status`
  * snapshot from it.
  */
final case class InstanceState(
    schemaVersion: Int,
    bootCount: Int,
    workers: Vector[WorkerRecord]
) derives ReadWriter:

  /** The worker with `workerId`, if registered. */
  def worker(workerId: String): Option[WorkerRecord] = workers.find(_.workerId == workerId)

  /** Upsert the worker `workerId`: append `ifAbsent` when none exists, else replace it with `ifPresent(existing)`
    * (preserving list order). Used by the fold's registration case.
    */
  private[instance] def updateWorker(
      workerId: String
  )(ifAbsent: => WorkerRecord, ifPresent: WorkerRecord => WorkerRecord): InstanceState =
    if workers.exists(_.workerId == workerId) then
      copy(workers = workers.map(w => if w.workerId == workerId then ifPresent(w) else w))
    else copy(workers = workers :+ ifAbsent)

  /** Update an already-registered worker in place; a no-op if `workerId` is unknown (the fold drops status/events for
    * an unregistered worker rather than inventing a record).
    */
  private[instance] def updateRegisteredWorker(workerId: String)(f: WorkerRecord => WorkerRecord): InstanceState =
    copy(workers = workers.map(w => if w.workerId == workerId then f(w) else w))

  /** A human/client-facing `status` snapshot (contract §6.3) — the daemon's `status` RPC result. The full exported feed
    * is summarised to its length here; clients stream the feed itself via `subscribe` (Task 4.1.4).
    */
  def toStatusJson: ujson.Value =
    ujson.Obj(
      "schemaVersion" -> ujson.Num(schemaVersion.toDouble),
      "bootCount" -> ujson.Num(bootCount.toDouble),
      "workers" -> ujson.Arr(
        workers.map(w =>
          ujson.Obj(
            "workerId" -> ujson.Str(w.workerId),
            "repo" -> ujson.Str(w.repo),
            "feature" -> ujson.Str(w.feature.value),
            "status" -> ujson.Str(w.status),
            "eventCount" -> ujson.Num(w.events.size.toDouble)
          )
        )*
      )
    )
