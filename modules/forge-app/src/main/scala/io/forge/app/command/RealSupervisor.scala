package io.forge.app.command

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.syntax.all.*
import io.forge.core.paths.ForgePaths
import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.daemon.{DaemonState, RealWorkerSpawner, Supervisor, WorkerHandle, WorkerSpawner}
import io.forge.instance.{Instance, InstanceEvent, InstanceState, WorkstreamStatus}

import scala.concurrent.duration.*

/** Task 4.2.5 — the real worker-process supervisor (`forge-app`'s implementation of the [[Supervisor]] seam).
  *
  * Composes the three previously-built pieces into the §6.2 supervision lifecycle: [[WorkerProvisioner]] (the isolated
  * clone, O10), [[WorkerLauncher]] (the `forge worker` launch spec), and the [[WorkerSpawner]] (the generic OS-process
  * mechanism, B4). On a `spawn-worker` request it provisions, launches, records `worker.spawned`, activates the
  * workstream, and forks an **exit-watcher** that records `worker.exited` with the real exit code when the child
  * terminates.
  *
  * All durable effects go through [[DaemonState]] (the single writer, §6.3.1), so the fleet is rebuildable from the
  * instance log alone (§6.4). The supervisor holds only volatile process handles ([[liveHandles]], for diagnostics /
  * future explicit kill); it deliberately does **not** kill its children on its own shutdown — a worker must survive
  * the daemon's restart (the §6.4 / Task 4.2.6 exit criterion), so spawned children are launched via
  * `Resource.allocated` and intentionally orphaned, their stdio captured to a per-worker file (not the daemon's pipes)
  * so they keep running cleanly after the daemon dies.
  *
  * Two recovery paths re-establish supervision after a daemon restart, neither of which has a [[WorkerHandle]] for the
  * pre-restart child:
  *
  *   - [[reconcile]] (one-shot, on boot, §6.4): for each worker the rebuilt state still considers `live`, probe the
  *     recorded pid. A dead pid is surfaced immediately as `worker.exited`; a still-alive one is left running (its
  *     socket feed resumes as the worker phones home to the rebound socket) and watched for exit via [[ProcessHandle]].
  *   - [[superviseLoop]] (periodic, the §6.2 daemon-coordinated **cadence**): a liveness sweep that records
  *     `worker.exited` for any still-`live` worker whose pid has since died. This is the backstop that catches an exit
  *     the handle/`ProcessHandle` watchers missed (e.g. a SIGKILL the JVM could not observe). The cadence is *only*
  *     this supervision tick — the FSM-driving poll stays inside the worker (contract §9), the daemon never drives it.
  */
final class RealSupervisor private (
    instance: Instance,
    state: DaemonState,
    spawner: WorkerSpawner,
    launcher: WorkerLauncher,
    provision: (String, os.Path) => IO[Either[String, ForgePaths]],
    liveHandles: Ref[IO, Map[String, WorkerHandle]],
    reserved: Ref[IO, Map[String, RealSupervisor.Reservation]]
) extends Supervisor:

  import RealSupervisor.{nextWorkerId, Allocation, ExternallyObservedExit, Reservation}

  /** Spawn (or, idempotently, return the existing) worker for `workstreamId` driving `feature`.
    *
    * Validation, idempotency, and worker-id allocation all happen in **one atomic step** ([[allocate]], under the
    * single-writer gate), because the daemon serves connections with `parJoinUnbounded`: two concurrent `spawn-worker`
    * calls that each read the snapshot independently could otherwise both pick `w-1` and race on the same checkout
    * path. The slow work (clone + process launch) stays **outside** the gate so M workers still spawn concurrently.
    *
    * Three outcomes, so a duplicate request never gets a *false* success:
    *   - [[Allocation.Existing]] — a worker is already durably recorded (`worker.spawned` landed); return its id.
    *   - [[Allocation.InFlight]] — another caller is mid-`launch` for this same workstream+feature; **wait on its
    *     result** ([[Reservation.result]]) and mirror it. So if the first launch ultimately fails, the duplicate sees
    *     the *same failure*, not a `Right(workerId)` for a worker that was never recorded.
    *   - [[Allocation.Fresh]] — we won the reservation; run `launch`, publish its result to any waiters, and drop the
    *     reservation in a `guarantee` (so the id frees up on failure and a later retry can re-allocate).
    */
  def spawnWorker(workstreamId: WorkstreamId, repo: String, feature: FeatureId): IO[Either[String, String]] =
    allocate(workstreamId, feature).flatMap {
      case Left(reason) => IO.pure(Left(reason))
      case Right(Allocation.Existing(workerId)) => IO.pure(Right(workerId))
      case Right(Allocation.InFlight(result)) => result.get
      case Right(Allocation.Fresh(workerId, result)) =>
        launch(workerId, workstreamId, repo, feature)
          .guaranteeCase {
            // Publish the launch's outcome to any duplicate caller waiting on `result`, in every termination case so a
            // waiter can never hang: success/refusal carries through verbatim, a raised error or cancellation becomes a
            // Left the waiter sees as a failure (it raises for the original caller as usual).
            case Outcome.Succeeded(fa) => fa.flatMap(r => result.complete(r).void)
            case Outcome.Errored(e) => result.complete(Left(s"worker launch failed: ${e.getMessage}")).void
            case Outcome.Canceled() => result.complete(Left(s"worker '$workerId' launch was cancelled")).void
          }
          .guarantee(releaseReservation(workerId))
    }

  /** Atomically validate the workstream, apply the idempotency guard, and either return an existing/in-flight worker or
    * reserve a fresh `w-<n>` id. Runs under [[DaemonState.modify]] (the single-writer gate) reading the live snapshot,
    * and considers **both** already-recorded workers and the in-flight [[reserved]] table — so two concurrent calls can
    * neither double-spawn the same workstream+feature nor allocate the same id. Records no event (allocation is a
    * volatile reservation; the durable `worker.spawned` lands in [[launch]] once the pid is known).
    */
  private def allocate(workstreamId: WorkstreamId, feature: FeatureId): IO[Either[String, Allocation]] =
    state.modify { snap =>
      snap.workstream(workstreamId) match
        case None => IO.pure((Vector.empty, Left(s"no such workstream '${workstreamId.value}'")))
        case Some(ws) if ws.status == WorkstreamStatus.Done || ws.status == WorkstreamStatus.Abandoned =>
          IO.pure((Vector.empty, Left(s"workstream '${workstreamId.value}' is ${WorkstreamStatus.name(ws.status)}")))
        case Some(_) =>
          reserved.get.flatMap { inFlight =>
            // A durably-recorded live worker → true idempotent success. An in-flight reservation for the same
            // workstream+feature → hand back its result Deferred so the duplicate waits on the original launch rather
            // than being told a worker exists before `worker.spawned` is recorded.
            recordedLiveWorker(snap, workstreamId, feature) match
              case Some(workerId) => IO.pure((Vector.empty, Right(Allocation.Existing(workerId))))
              case None =>
                inFlight.collectFirst {
                  case (_, r) if r.workstreamId == workstreamId && r.feature == feature => r
                } match
                  case Some(reservation) => IO.pure((Vector.empty, Right(Allocation.InFlight(reservation.result))))
                  case None =>
                    Deferred[IO, Either[String, String]].flatMap { result =>
                      val workerId = nextWorkerId(snap, inFlight.keySet)
                      reserved
                        .update(_ + (workerId -> Reservation(workstreamId, feature, result)))
                        .as((Vector.empty, Right(Allocation.Fresh(workerId, result))))
                    }
          }
    }

  /** Provision + launch the pre-allocated `workerId`, recording `worker.spawned` (which the fold also uses to flip the
    * workstream `Planning → Active`, so spawn + activation are one crash-atomic event — see [[RebuildInstanceState]]).
    */
  private def launch(
      workerId: String,
      workstreamId: WorkstreamId,
      repo: String,
      feature: FeatureId
  ): IO[Either[String, String]] =
    val source = os.Path(repo)
    provision(workerId, source).flatMap {
      case Left(reason) => IO.pure(Left(s"could not provision worker '$workerId': $reason"))
      case Right(_) =>
        val spec = launcher.workerSpec(instance, workerId, repo, feature)
        val checkout = instance.workerCheckout(workerId)
        spawner.spawn(spec).allocated.flatMap { case (handle, _) =>
          for
            _ <- liveHandles.update(_ + (workerId -> handle))
            _ <- state.record(
              InstanceEvent.WorkerSpawned(workerId, workstreamId, repo, feature, checkout.toString, handle.pid)
            )
            _ <- watchHandle(workerId, handle).start
          yield Right(workerId)
        }
    }

  /** Drop `workerId`'s in-flight reservation, under the write gate so it is consistent with the snapshot a concurrent
    * [[allocate]] reads. Safe to run after `worker.spawned` has landed: the id is then permanent in the snapshot
    * (max-suffix allocation never reuses it), so a later allocation still skips it via the snapshot rather than the
    * reservation. Safe after a *failed* launch too: the id was never recorded, so dropping the reservation frees it for
    * a retry (and any duplicate caller has already been handed this reservation's now-completed result). Idempotent.
    */
  private def releaseReservation(workerId: String): IO[Unit] =
    state.exclusive(reserved.update(_ - workerId))

  /** Watch a worker we spawned this session: when its [[WorkerHandle]] reports exit, record `worker.exited` with the
    * real exit code and drop the handle. Cancelled silently if the daemon shuts down first (the worker survives; a
    * restart's [[reconcile]] picks it up).
    */
  private def watchHandle(workerId: String, handle: WorkerHandle): IO[Unit] =
    handle.awaitExit.flatMap { code =>
      liveHandles.update(_ - workerId) *> state.record(InstanceEvent.WorkerExited(workerId, code)).void
    }

  /** §6.4 boot reconciliation: probe every still-`live` worker the rebuilt state carries. A dead pid is surfaced as
    * `worker.exited`; a live one is left running and watched for exit via its [[ProcessHandle]] (we have no
    * [[WorkerHandle]] for a process spawned before this boot). Idempotent: it records an exit only for a worker still
    * marked live, so a second reconcile (or an overlapping cadence tick) does not re-record.
    */
  def reconcile: IO[Unit] =
    state.snapshot.flatMap { snap =>
      snap.workers.filter(_.live).flatMap(w => w.pid.map((w.workerId, _))).traverse_ { (workerId, pid) =>
        IO.blocking(ProcessHandle.of(pid).filter(_.isAlive)).flatMap {
          case p if p.isPresent => watchProcessHandle(workerId, p.get).start.void
          case _ => surfaceExit(workerId)
        }
      }
    }

  /** Watch a reattached (pre-restart) worker via its OS [[ProcessHandle]]: record `worker.exited` when it terminates.
    * `ProcessHandle.onExit` yields no exit code (only [[Process]] does), so the externally-observed sentinel is used.
    */
  private def watchProcessHandle(workerId: String, ph: ProcessHandle): IO[Unit] =
    IO.fromCompletableFuture(IO.delay(ph.onExit())).flatMap(_ => surfaceExit(workerId))

  /** The §6.2 daemon-coordinated cadence: a periodic liveness sweep recording `worker.exited` for any still-`live`
    * worker whose pid has died. Runs forever (the daemon backgrounds it for its serving lifetime); `cadence` is the
    * tick interval. A backstop for the watchers above — the FSM-driving poll is the worker's, not the daemon's.
    */
  def superviseLoop(cadence: FiniteDuration): IO[Unit] =
    (superviseTick *> IO.sleep(cadence)).foreverM

  private def superviseTick: IO[Unit] =
    state.snapshot.flatMap { snap =>
      snap.workers.filter(_.live).flatMap(w => w.pid.map((w.workerId, _))).traverse_ { (workerId, pid) =>
        IO.blocking(ProcessHandle.of(pid).map(_.isAlive).orElse(false)).flatMap { alive =>
          if alive then IO.unit else surfaceExit(workerId)
        }
      }
    }

  /** Record an externally-observed exit (pid death seen by reconcile / the cadence sweep), guarding against a race with
    * a more precise [[watchHandle]] exit by re-checking the worker is still `live` before recording.
    */
  private def surfaceExit(workerId: String): IO[Unit] =
    state.snapshot.flatMap { snap =>
      if snap.worker(workerId).exists(_.live) then
        liveHandles.update(_ - workerId) *>
          state.record(InstanceEvent.WorkerExited(workerId, ExternallyObservedExit)).void
      else IO.unit
    }

  /** The id of a **durably recorded** live worker in `workstreamId` already driving `feature` (the §6.4 idempotency
    * guard against a re-issued request) — i.e. one whose `worker.spawned` has landed in the snapshot. The in-flight
    * (reserved-but-not-yet-recorded) case is handled separately in [[allocate]] (it waits on the original launch's
    * result rather than reporting a not-yet-real worker as success).
    */
  private def recordedLiveWorker(snap: InstanceState, workstreamId: WorkstreamId, feature: FeatureId): Option[String] =
    snap.workers
      .find(w => w.live && w.workstreamId.contains(workstreamId) && w.feature == feature)
      .map(_.workerId)

object RealSupervisor:

  /** The exit code recorded when an exit is observed only via pid liveness (reconcile / the cadence sweep) rather than
    * a [[WorkerHandle]] — the real code is unrecoverable from a bare [[ProcessHandle]]. Negative so it never collides
    * with a real (0–255) process exit status.
    */
  val ExternallyObservedExit: Int = -1

  /** The default §6.2 supervision cadence — the liveness-sweep interval, not the worker's FSM poll. */
  val DefaultCadence: FiniteDuration = 15.seconds

  /** A volatile in-flight worker reservation: an allocated-but-not-yet-recorded `w-<n>` plus the [[Deferred]] the
    * [[RealSupervisor.launch]] completes with its final result, so a concurrent duplicate request can wait on the real
    * outcome instead of being told a worker exists prematurely.
    */
  private final case class Reservation(
      workstreamId: WorkstreamId,
      feature: FeatureId,
      result: Deferred[IO, Either[String, String]]
  )

  /** The outcome of [[RealSupervisor.allocate]]:
    *   - [[Existing]] — a durably-recorded live worker the request collapses onto (true idempotency, no spawn).
    *   - [[InFlight]] — another caller is mid-launch for the same workstream+feature; wait on its `result`.
    *   - [[Fresh]] — we reserved a new id; provision + launch it and publish the result to `result`.
    */
  private enum Allocation:
    case Existing(workerId: String)
    case InFlight(result: Deferred[IO, Either[String, String]])
    case Fresh(workerId: String, result: Deferred[IO, Either[String, String]])

  /** Build a supervisor over the real spawner + launcher, with [[WorkerProvisioner]] backing clone provisioning. */
  def build(instance: Instance, state: DaemonState): IO[RealSupervisor] =
    build(
      instance,
      state,
      RealWorkerSpawner,
      WorkerLauncher.Real,
      (workerId, source) => WorkerProvisioner.provision(instance, workerId, source).map(_.left.map(_.message))
    )

  /** Injectable build for tests (stub spawner / launcher / provisioner). */
  def build(
      instance: Instance,
      state: DaemonState,
      spawner: WorkerSpawner,
      launcher: WorkerLauncher,
      provision: (String, os.Path) => IO[Either[String, ForgePaths]]
  ): IO[RealSupervisor] =
    for
      liveHandles <- Ref.of[IO, Map[String, WorkerHandle]](Map.empty)
      reserved <- Ref.of[IO, Map[String, Reservation]](Map.empty)
    yield new RealSupervisor(instance, state, spawner, launcher, provision, liveHandles, reserved)

  /** The next `w-<n>` id: one past the largest numeric suffix in use across **both** the recorded workers and the
    * `inFlight` reservations, or `w-1` for the first. Max-suffix (not count) so an id is never reused even after a
    * worker exits — mirrors the daemon's `nextWorkstreamId`. Including `inFlight` is what keeps two concurrent
    * allocations from both picking the same id before either has recorded its `worker.spawned`.
    */
  private[command] def nextWorkerId(state: InstanceState, inFlight: Set[String] = Set.empty): String =
    val used = (state.workers.map(_.workerId) ++ inFlight).flatMap {
      case s"w-$n" => n.toIntOption
      case _ => None
    }
    s"w-${used.maxOption.getOrElse(0) + 1}"
