package io.forge.daemon

import cats.effect.IO
import cats.effect.kernel.Ref
import fs2.Stream
import fs2.concurrent.Topic
import io.forge.instance.{
  FileInstanceLog,
  FileInstanceStateCache,
  Instance,
  InstanceEvent,
  InstanceLogRecord,
  InstanceState,
  InstanceVerifyResult,
  RebuildInstanceState
}

/** Phase-4 §6.4 / O8 — the daemon's authoritative runtime state (Slice 4.1, Task 4.1.3).
  *
  * Wraps the durable instance log + rebuildable cache ([[FileInstanceLog]] / [[FileInstanceStateCache]], Task 4.1.2)
  * behind a single in-memory [[InstanceState]] `Ref`, and enforces the §6.3.1 **single-writer** discipline: every
  * mutation goes through [[record]], which appends to the canonical log *first*, then folds the event into the
  * in-memory snapshot and persists the derived cache. The daemon serves its `status` RPC from [[snapshot]] (a pure
  * `Ref` read), so a status read never touches disk.
  *
  * The log is the source of truth (§6.4 invariant): on every boot [[DaemonState.boot]] rebuilds the snapshot from the
  * log alone (reconciling a stale/absent cache), which is exactly the Slice-4.1 crash-recovery exit criterion — a
  * restarted daemon reconstructs the same view without the cache.
  */
final class DaemonState private (
    log: FileInstanceLog,
    cache: FileInstanceStateCache,
    stateRef: Ref[IO, InstanceState],
    feed: Topic[IO, InstanceEvent]
):

  /** The current in-memory snapshot — a pure `Ref` read, no I/O (the daemon's `status` RPC source). */
  def snapshot: IO[InstanceState] = stateRef.get

  /** Single-writer mutation: append `event` to the canonical instance log, fold it into the in-memory snapshot, persist
    * the derived cache, then publish the event to the live [[feed]] (B3 fan-out). Returns the stamped
    * [[InstanceLogRecord]]. The append is the durable commit point; the cache write is a best-effort convenience the
    * next boot can rebuild without (§6.4); the publish is fire-and-forget — a subscriber that has fallen behind its
    * bounded buffer drops, it never back-pressures the writer.
    */
  def record(event: InstanceEvent): IO[InstanceLogRecord] =
    for
      rec <- log.append(event)
      updated <- stateRef.updateAndGet(RebuildInstanceState.step(_, event))
      _ <- cache.save(updated)
      _ <- feed.publish1(event).void
    yield rec

  /** Subscribe to the aggregated **per-worker** event feed (Task 4.1.4, B3 event export): the worker-scoped events
    * already in the rebuilt state (the exported-feed tail, replayed as
    * `worker.registered`/`worker.status`/`worker.event`) followed by live events as they are [[record]]ed. The
    * `daemon.started` boot marker is filtered out — it is not a per-worker event.
    *
    * Seeding is loss-free: the subscription is registered (via `subscribeAwait`) **before** the snapshot is read, so
    * any event recorded after the snapshot arrives on the live tail rather than being missed. An event recorded in the
    * narrow window between subscription and snapshot can appear in both the seed and the live tail (a possible
    * duplicate); de-duplication is a cockpit concern (4.4), not a 4.1 single-worker-proof one.
    */
  def subscribe(maxQueued: Int = 64): Stream[IO, InstanceEvent] =
    Stream
      .resource(feed.subscribeAwait(maxQueued))
      .flatMap { live =>
        Stream.eval(stateRef.get).flatMap(snap => Stream.emits(DaemonState.seedEvents(snap))) ++ live
      }
      .filter(DaemonState.isWorkerEvent)

object DaemonState:

  /** The worker-scoped subset of the feed — everything except the `daemon.started` boot marker. */
  private def isWorkerEvent(event: InstanceEvent): Boolean = event match
    case _: InstanceEvent.DaemonStarted => false
    case _ => true

  /** Replay a rebuilt [[InstanceState]] into the per-worker events a fresh subscriber should see first: each worker's
    * registration, its latest status, then its exported-feed tail in order. Mirrors the order a live observer would
    * have seen them recorded, so the seed and the live tail form one coherent stream.
    */
  private def seedEvents(state: InstanceState): Vector[InstanceEvent] =
    state.workers.flatMap { w =>
      InstanceEvent.WorkerRegistered(w.workerId, w.repo, w.feature) +:
        InstanceEvent.WorkerStatus(w.workerId, w.status) +:
        w.events.map(e => InstanceEvent.WorkerEvent(w.workerId, e))
    }

  /** Cold boot: rebuild [[InstanceState]] from the canonical log (reconciling the cache via
    * [[FileInstanceStateCache.verifyAgainstLog]]), seed the in-memory `Ref`, then append this boot's `daemon.started`
    * event. `pid` is recorded on the event for forensics only. The returned state's `bootCount` is therefore the
    * rebuilt count plus one (this boot).
    */
  def boot(instance: Instance, pid: Long): IO[DaemonState] =
    for
      log <- FileInstanceLog(instance)
      cache = new FileInstanceStateCache(instance)
      verify <- cache.verifyAgainstLog(log)
      rebuilt = verify match
        case InstanceVerifyResult.Consistent(s) => s
        case InstanceVerifyResult.Rewritten(s) => s
      ref <- Ref.of[IO, InstanceState](rebuilt)
      feed <- Topic[IO, InstanceEvent]
      state = new DaemonState(log, cache, ref, feed)
      _ <- state.record(InstanceEvent.DaemonStarted(pid))
    yield state
