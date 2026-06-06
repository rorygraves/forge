package io.forge.app.command

import cats.effect.{IO, Ref}
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
    liveHandles: Ref[IO, Map[String, WorkerHandle]]
) extends Supervisor:

  import RealSupervisor.{nextWorkerId, ExternallyObservedExit}

  def spawnWorker(workstreamId: WorkstreamId, repo: String, feature: FeatureId): IO[Either[String, String]] =
    state.snapshot.flatMap { snap =>
      snap.workstream(workstreamId) match
        case None => IO.pure(Left(s"no such workstream '${workstreamId.value}'"))
        case Some(ws) if ws.status == WorkstreamStatus.Done || ws.status == WorkstreamStatus.Abandoned =>
          IO.pure(Left(s"workstream '${workstreamId.value}' is ${WorkstreamStatus.name(ws.status)}"))
        case Some(_) =>
          // Idempotent against a re-issued request: a workstream that already has a live worker for this feature is not
          // re-spawned (§6.4) — return the existing id rather than launching a duplicate clone + process.
          existingLiveWorker(snap, workstreamId, feature) match
            case Some(workerId) => IO.pure(Right(workerId))
            case None => launch(snap, workstreamId, repo, feature)
    }

  /** Provision + launch a fresh worker, recording `worker.spawned` and activating the workstream. */
  private def launch(
      snap: InstanceState,
      workstreamId: WorkstreamId,
      repo: String,
      feature: FeatureId
  ): IO[Either[String, String]] =
    val workerId = nextWorkerId(snap)
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
            _ <- activate(workstreamId)
            _ <- watchHandle(workerId, handle).start
          yield Right(workerId)
        }
    }

  /** Flip a `Planning` workstream to `Active` on its first spawn; already-`Active` is a no-op. */
  private def activate(workstreamId: WorkstreamId): IO[Unit] =
    state.snapshot.flatMap { snap =>
      snap.workstream(workstreamId) match
        case Some(ws) if ws.status == WorkstreamStatus.Planning =>
          state.record(InstanceEvent.WorkstreamStatusChanged(workstreamId, WorkstreamStatus.Active)).void
        case _ => IO.unit
    }

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

  /** The id of a live worker in `workstreamId` already driving `feature`, if any (idempotency guard). */
  private def existingLiveWorker(snap: InstanceState, workstreamId: WorkstreamId, feature: FeatureId): Option[String] =
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
    Ref
      .of[IO, Map[String, WorkerHandle]](Map.empty)
      .map(new RealSupervisor(instance, state, spawner, launcher, provision, _))

  /** The next `w-<n>` id: one past the largest numeric suffix in use, or `w-1` for the first. Max-suffix (not count) so
    * an id is never reused even after a worker exits — mirrors the daemon's `nextWorkstreamId`.
    */
  private[command] def nextWorkerId(state: InstanceState): String =
    val used = state.workers.flatMap { w =>
      w.workerId match
        case s"w-$n" => n.toIntOption
        case _ => None
    }
    s"w-${used.maxOption.getOrElse(0) + 1}"
