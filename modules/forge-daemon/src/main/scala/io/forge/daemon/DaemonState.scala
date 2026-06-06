package io.forge.daemon

import cats.effect.IO
import cats.effect.kernel.Ref
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
    stateRef: Ref[IO, InstanceState]
):

  /** The current in-memory snapshot — a pure `Ref` read, no I/O (the daemon's `status` RPC source). */
  def snapshot: IO[InstanceState] = stateRef.get

  /** Single-writer mutation: append `event` to the canonical instance log, fold it into the in-memory snapshot, then
    * persist the derived cache. Returns the stamped [[InstanceLogRecord]]. The append is the durable commit point; the
    * cache write is a best-effort convenience the next boot can rebuild without (§6.4).
    */
  def record(event: InstanceEvent): IO[InstanceLogRecord] =
    for
      rec <- log.append(event)
      updated <- stateRef.updateAndGet(RebuildInstanceState.step(_, event))
      _ <- cache.save(updated)
    yield rec

object DaemonState:

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
      state = new DaemonState(log, cache, ref)
      _ <- state.record(InstanceEvent.DaemonStarted(pid))
    yield state
