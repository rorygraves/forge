package io.forge.instance

import io.forge.core.{FeatureId, WorkstreamId}
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
    *
    * v2 (Task 4.2.4) — [[InstanceState]] gained `workstreams` and [[WorkerRecord]] gained
    * `workstreamId`/`checkoutRoot`/`pid`/`exitCode`. v3 (Task 4.3.4) — [[WorkerRecord]] gained `containerId` and `pid`
    * became one of two optional liveness keys (a containerised worker carries `containerId`, not `pid`). v4 (Task
    * 4.3.5, B2) — [[InstanceState]] gained the budget aggregates (`committedUsd` / `committedByWorkstream`) + the
    * outstanding-reservation table (`reservations`). A stale-version cache fails the version check in
    * [[FileInstanceStateCache]] and is rebuilt from the (forward-compatible) log.
    */
  val CurrentSchemaVersion: Int = 4

  /** The empty state — no boots seen, no workers, no workstreams, no spend. The fold seed and the "fresh instance"
    * value.
    */
  val empty: InstanceState =
    InstanceState(CurrentSchemaVersion, bootCount = 0, workers = Vector.empty, workstreams = Vector.empty)

  /** Pure fold of the instance log into [[InstanceState]]. Records are applied in `seq` order (the log's on-disk
    * order); a record whose `kind` this build does not recognise — a future `budget.*` variant or the `harness.error`
    * truncation-recovery marker [[FileInstanceLog]] writes — decodes to `None` and is skipped, exactly like the
    * unknown-`kind` no-op in the per-feature `Replay`. No I/O, no clock: hand it fixture records and assert.
    */
  def fold(records: Vector[InstanceLogRecord]): InstanceState =
    records.foldLeft(empty)((state, record) => record.event.fold(state)(applyEvent(state, _)))

  /** Apply one already-decoded event to an existing state — the incremental fold step the daemon's single-writer path
    * uses to fold a freshly appended event into its in-memory [[InstanceState]] without replaying the whole log (Task
    * 4.1.3). `fold(records)` is exactly `records.flatMap(_.event).foldLeft(empty)(step)`.
    */
  def step(state: InstanceState, event: InstanceEvent): InstanceState = applyEvent(state, event)

  private def applyEvent(state: InstanceState, event: InstanceEvent): InstanceState =
    event match
      case InstanceEvent.DaemonStarted(_) =>
        state.copy(bootCount = state.bootCount + 1)

      case InstanceEvent.WorkerRegistered(workerId, repo, feature) =>
        // Upsert: a fresh worker seeds a record; a re-registration updates its repo/feature but preserves the status +
        // exported-feed tail (and any daemon-side spawn fields) already accumulated (idempotent-safe — registration is
        // normally once-per-worker, and may follow a `worker.spawned` that already seeded the record).
        state.updateWorker(workerId)(
          ifAbsent = WorkerRecord.registered(workerId, repo, feature),
          ifPresent = _.copy(repo = repo, feature = feature)
        )

      case InstanceEvent.WorkerStatus(workerId, status) =>
        // A status for an unregistered worker is dropped (e.g. its `worker.registered` was lost to a truncation before
        // the recovery boundary) rather than inventing a record with no repo/feature.
        state.updateRegisteredWorker(workerId)(_.copy(status = status))

      case InstanceEvent.WorkerEvent(workerId, exported) =>
        // Append the exported event to the worker's feed tail, AND — when it is a `cost.update` (§19) — fan its per-turn
        // USD delta into the committed-spend totals (the B2 fan-in backing both the authorization decision and the
        // cockpit spend view). The fan-in is the sole writer of committed spend; `budget.finalize` only releases the
        // outstanding reservation, so a turn's actual cost is never double-counted.
        val appended = state.updateRegisteredWorker(workerId)(w => w.copy(events = w.events :+ exported))
        InstanceEvent.costUpdateUsd(exported) match
          case Some(usd) => appended.addCommitted(appended.worker(workerId).flatMap(_.workstreamId), usd)
          case None => appended

      case InstanceEvent.WorkstreamCreated(workstreamId, goal) =>
        // Upsert: a fresh workstream seeds a Planning record; a duplicate create updates the goal but preserves the
        // status + worker ordering already accumulated (idempotent-safe).
        state.updateWorkstream(workstreamId)(
          ifAbsent = Workstream.planning(workstreamId, goal),
          ifPresent = _.copy(goal = goal)
        )

      case InstanceEvent.WorkstreamStatusChanged(workstreamId, status) =>
        // A status for an unknown workstream is dropped (its `workstream.created` was lost to a truncation) rather than
        // inventing a record with no goal.
        state.updateRegisteredWorkstream(workstreamId)(_.copy(status = status))

      case InstanceEvent.WorkerSpawned(workerId, workstreamId, repo, feature, checkoutRoot, pid, containerId) =>
        // The daemon-side seed: upsert the worker with its spawn fields (clearing any prior exitCode for a re-spawn),
        // append it to the owning workstream's ordering, AND activate the workstream (`Planning → Active`) in the same
        // step. Folding activation into `worker.spawned` keeps the spawn crash-atomic: there is no window where a
        // replay rebuilds a live worker attached to a still-`Planning` workstream (which a separate
        // `workstream.status` append would open if the daemon died between the two records). Only `Planning` is
        // advanced — an already-`Active` workstream is unchanged, and a `Done`/`Abandoned` one is never resurrected
        // (the supervisor refuses to spawn into a terminal workstream; the fold is defensive on top of that). A spawn
        // for an unknown workstream still seeds the worker (the record is the authority for the worker); the
        // missing-workstream case is a truncation edge.
        state
          .updateWorker(workerId)(
            ifAbsent = WorkerRecord
              .registered(workerId, repo, feature)
              .copy(
                workstreamId = Some(workstreamId),
                checkoutRoot = Some(checkoutRoot),
                pid = pid,
                containerId = containerId
              ),
            ifPresent = _.copy(
              repo = repo,
              feature = feature,
              workstreamId = Some(workstreamId),
              checkoutRoot = Some(checkoutRoot),
              pid = pid,
              containerId = containerId,
              exitCode = None
            )
          )
          .updateRegisteredWorkstream(workstreamId) { ws =>
            val joined = ws.withWorker(workerId)
            if joined.status == WorkstreamStatus.Planning then joined.copy(status = WorkstreamStatus.Active)
            else joined
          }

      case InstanceEvent.WorkerExited(workerId, exitCode) =>
        // Record the exit AND release any reservation the worker still held — a dead worker holds no budget (the §8
        // reconciliation-lite; the TTL/grant-reclaim hardening is 4.5).
        val exited = state.updateRegisteredWorker(workerId)(_.copy(exitCode = Some(exitCode)))
        exited.copy(reservations = exited.reservations - workerId)

      case InstanceEvent.BudgetReserve(_, _, _) =>
        state // audit breadcrumb — the deciding grant/refuse carries the aggregate effect

      case InstanceEvent.BudgetGrant(workerId, workstreamId, reservationId, estimateUsd) =>
        // A fresh grant REPLACES the worker's prior reservation (a worker drives sequentially ⇒ one session in flight),
        // so a missing finalize cannot leak outstanding headroom across that worker's sessions.
        state.copy(reservations =
          state.reservations.updated(workerId, BudgetReservation(reservationId, workstreamId, estimateUsd))
        )

      case InstanceEvent.BudgetRefuse(_, _, _, _) =>
        state // audit breadcrumb — nothing was reserved

      case InstanceEvent.BudgetFinalize(workerId, reservationId, _) =>
        // Clear the worker's reservation iff it is the one being finalized; a late/duplicate settle for an
        // already-replaced reservation is a no-op. Committed spend is folded by the `cost.update` fan-in, not here.
        state.reservations.get(workerId) match
          case Some(r) if r.reservationId == reservationId => state.copy(reservations = state.reservations - workerId)
          case _ => state

/** A worker as the daemon sees it — a *record* plus its exported per-feature event feed. In Slice 4.1 a worker was only
  * a record + feed; Slice 4.2 (Task 4.2.4) adds the daemon-side **spawn** fields a supervised process carries.
  *
  *   - `repo` — the registered source checkout path; `feature` — the feature it drives.
  *   - `status` — the latest worker-reported FSM-state name (open string — the rich state lives behind the worker
  *     boundary).
  *   - `events` — the exported-feed tail (B3), carried verbatim as the raw per-feature `Action`-shaped JSON.
  *   - `workstreamId` — the owning [[Workstream]], `None` for a 4.1-style standalone-registered worker.
  *   - `checkoutRoot` — the worker's isolated clone path (O10), `None` until the daemon spawns it.
  *   - `pid` — the spawned **host-process** child's id (`None` for a containerised or unspawned worker).
  *   - `containerId` — the spawned **container's** OCI id (`None` for a host-process or unspawned worker, Slice 4.3).
  *     Exactly one of `pid`/`containerId` is `Some` for a real spawn — the worker's liveness key, by topology.
  *   - `exitCode` — `Some` once the worker has exited (`worker.exited`); `None` while it may still be running.
  *
  * The new fields default so a hand-written fixture / partial JSON still decodes; a real cache is always rebuilt at the
  * current schema (see [[RebuildInstanceState.CurrentSchemaVersion]]).
  */
final case class WorkerRecord(
    workerId: String,
    repo: String,
    feature: FeatureId,
    status: String,
    events: Vector[ujson.Value],
    workstreamId: Option[WorkstreamId] = None,
    checkoutRoot: Option[String] = None,
    pid: Option[Long] = None,
    containerId: Option[String] = None,
    exitCode: Option[Int] = None
) derives ReadWriter:

  /** A spawned worker is **live** while it carries a liveness key (a host `pid` **or** a `containerId`) and has not
    * reported an exit. The supervisor's restart reconciliation (4.2.5/4.3.4) probes that key only for live workers —
    * the host pid via [[ProcessHandle]], the container id via the OCI runtime; a record with neither key was never a
    * real spawn (a 4.1-style registration), and one with an `exitCode` has terminated.
    */
  def live: Boolean = (pid.isDefined || containerId.isDefined) && exitCode.isEmpty

object WorkerRecord:
  /** The status a worker carries from `worker.registered` until its first `worker.status`. */
  val RegisteredStatus: String = "registered"

  /** A freshly registered worker — [[RegisteredStatus]], empty feed, no spawn fields yet. */
  def registered(workerId: String, repo: String, feature: FeatureId): WorkerRecord =
    WorkerRecord(workerId, repo, feature, RegisteredStatus, Vector.empty)

/** One outstanding (granted, not-yet-finalized) B2 budget reservation (§8, Task 4.3.5) — the headroom a worker holds
  * for an in-flight agent session. Keyed in [[InstanceState.reservations]] by `workerId` (a worker drives sequentially
  * ⇒ at most one outstanding at a time; a fresh grant replaces the prior). `estimateUsd` is the coarse per-session
  * estimate (O11) the authorization decision counts; the real spend lands in the committed totals via the `cost.update`
  * fan-in.
  */
final case class BudgetReservation(
    reservationId: String,
    workstreamId: Option[WorkstreamId],
    estimateUsd: BigDecimal
) derives ReadWriter

/** The rebuildable instance projection (§6.4): how many times the supervisor has booted, the per-worker records, (Task
  * 4.2.4) the per-workstream coordination objects, and (Task 4.3.5, B2) the budget aggregates — committed spend
  * (per-instance + per-workstream, fanned in from each worker's exported `cost.update`) and the outstanding-reservation
  * table. Persisted by [[FileInstanceStateCache]] and rebuilt by [[RebuildInstanceState.fold]]; the daemon serves its
  * `status` snapshot — and its reservation authorization decision — from it.
  */
final case class InstanceState(
    schemaVersion: Int,
    bootCount: Int,
    workers: Vector[WorkerRecord],
    workstreams: Vector[Workstream] = Vector.empty,
    // B2 (Task 4.3.5): committed (finalized) spend, fanned in from `cost.update`. `committedByWorkstream` is keyed by the
    // workstream id's string value (a plain JSON object on the wire). `reservations` is the outstanding table, keyed by
    // `workerId`.
    committedUsd: BigDecimal = BigDecimal(0),
    committedByWorkstream: Map[String, BigDecimal] = Map.empty,
    reservations: Map[String, BudgetReservation] = Map.empty
) derives ReadWriter:

  /** The worker with `workerId`, if registered. */
  def worker(workerId: String): Option[WorkerRecord] = workers.find(_.workerId == workerId)

  /** Total outstanding (granted, not-yet-finalized) reservation estimate across all workers, optionally excluding one
    * worker's own current reservation (so a re-reserving worker is not counted against itself). Backs the per-instance
    * authorization check.
    */
  def outstandingUsd(excludingWorker: Option[String] = None): BigDecimal =
    reservations.iterator.collect { case (wid, r) if !excludingWorker.contains(wid) => r.estimateUsd }.sum

  /** Outstanding reservation estimate for one workstream (same exclusion semantics as [[outstandingUsd]]). Backs the
    * per-workstream authorization check.
    */
  def outstandingUsdForWorkstream(ws: WorkstreamId, excludingWorker: Option[String] = None): BigDecimal =
    reservations.iterator.collect {
      case (wid, r) if !excludingWorker.contains(wid) && r.workstreamId.contains(ws) => r.estimateUsd
    }.sum

  /** Committed (finalized) spend for one workstream. */
  def committedUsdForWorkstream(ws: WorkstreamId): BigDecimal = committedByWorkstream.getOrElse(ws.value, BigDecimal(0))

  /** Fan a per-turn `cost.update` USD delta into the committed totals (per-instance always; per-workstream when the
    * worker has an owning workstream). The fold's sole writer of committed spend.
    */
  private[instance] def addCommitted(ws: Option[WorkstreamId], usd: BigDecimal): InstanceState =
    copy(
      committedUsd = committedUsd + usd,
      committedByWorkstream = ws.fold(committedByWorkstream)(id =>
        committedByWorkstream.updated(id.value, committedByWorkstream.getOrElse(id.value, BigDecimal(0)) + usd)
      )
    )

  /** The workstream with `id`, if created. */
  def workstream(id: WorkstreamId): Option[Workstream] = workstreams.find(_.id == id)

  /** The **attention** projection (§5) for a workstream: which of its workers need a human, and why — derived from each
    * worker's exported status ([[AttentionReason.forStatus]]), never a lifecycle state. Workers are returned in the
    * workstream's ordering; an unknown worker id in the ordering (truncation edge) is skipped. An unknown workstream
    * yields an empty vector.
    */
  def attention(id: WorkstreamId): Vector[WorkerAttention] =
    workstream(id).toVector.flatMap { ws =>
      ws.workers.flatMap(wid => worker(wid)).flatMap { w =>
        AttentionReason.forStatus(w.status).map(WorkerAttention(w.workerId, _))
      }
    }

  /** Upsert the worker `workerId`: append `ifAbsent` when none exists, else replace it with `ifPresent(existing)`
    * (preserving list order). Used by the fold's registration / spawn cases.
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

  /** Upsert the workstream `id`: append `ifAbsent` when none exists, else replace it with `ifPresent(existing)`
    * (preserving list order). Used by the fold's `workstream.created` case.
    */
  private[instance] def updateWorkstream(
      id: WorkstreamId
  )(ifAbsent: => Workstream, ifPresent: Workstream => Workstream): InstanceState =
    if workstreams.exists(_.id == id) then
      copy(workstreams = workstreams.map(ws => if ws.id == id then ifPresent(ws) else ws))
    else copy(workstreams = workstreams :+ ifAbsent)

  /** Update an already-created workstream in place; a no-op if `id` is unknown (the fold drops a status/spawn-ordering
    * update for an uncreated workstream rather than inventing a record with no goal).
    */
  private[instance] def updateRegisteredWorkstream(id: WorkstreamId)(f: Workstream => Workstream): InstanceState =
    copy(workstreams = workstreams.map(ws => if ws.id == id then f(ws) else ws))

  /** A human/client-facing `status` snapshot (contract §6.3) — the daemon's `status` RPC result. The full exported feed
    * is summarised to its length here; clients stream the feed itself via `subscribe` (Task 4.1.4). Each worker carries
    * its spawn fields (workstreamId / pid / live); each workstream carries its lifecycle, worker ordering, and the
    * derived [[attention]] projection (Task 4.2.4).
    */
  def toStatusJson: ujson.Value =
    ujson.Obj(
      "schemaVersion" -> ujson.Num(schemaVersion.toDouble),
      "bootCount" -> ujson.Num(bootCount.toDouble),
      "committedUsd" -> ujson.Num(committedUsd.toDouble),
      "outstandingUsd" -> ujson.Num(outstandingUsd().toDouble),
      "workers" -> ujson.Arr(workers.map(workerJson)*),
      "workstreams" -> ujson.Arr(workstreams.map(workstreamJson)*)
    )

  private def workerJson(w: WorkerRecord): ujson.Value =
    val base = ujson.Obj(
      "workerId" -> ujson.Str(w.workerId),
      "repo" -> ujson.Str(w.repo),
      "feature" -> ujson.Str(w.feature.value),
      "status" -> ujson.Str(w.status),
      "eventCount" -> ujson.Num(w.events.size.toDouble),
      "live" -> ujson.Bool(w.live)
    )
    w.workstreamId.foreach(id => base("workstreamId") = ujson.Str(id.value))
    w.checkoutRoot.foreach(root => base("checkoutRoot") = ujson.Str(root))
    w.pid.foreach(p => base("pid") = ujson.Num(p.toDouble))
    w.containerId.foreach(id => base("containerId") = ujson.Str(id))
    w.exitCode.foreach(c => base("exitCode") = ujson.Num(c.toDouble))
    base

  private def workstreamJson(ws: Workstream): ujson.Value =
    ujson.Obj(
      "workstreamId" -> ujson.Str(ws.id.value),
      "goal" -> ujson.Str(ws.goal),
      "status" -> ujson.Str(WorkstreamStatus.name(ws.status)),
      "committedUsd" -> ujson.Num(committedUsdForWorkstream(ws.id).toDouble),
      "outstandingUsd" -> ujson.Num(outstandingUsdForWorkstream(ws.id).toDouble),
      "workers" -> ujson.Arr(ws.workers.map(ujson.Str(_))*),
      "attention" -> ujson.Arr(
        attention(ws.id).map(a =>
          ujson.Obj(
            "workerId" -> ujson.Str(a.workerId),
            "reason" -> ujson.Str(AttentionReason.token(a.reason))
          )
        )*
      )
    )
