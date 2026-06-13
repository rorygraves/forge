package io.forge.app.command

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Supervisor as FiberSupervisor
import cats.syntax.all.*
import io.forge.core.paths.ForgePaths
import io.forge.core.{FeatureId, WorkstreamId}
import io.forge.daemon.{DaemonState, DockerRuntime, OciRuntime, RealWorkerSpawner, Supervisor, WorkerSpawner}
import io.forge.instance.{Instance, InstanceEvent, InstanceState, WorkerRecord, WorkstreamStatus}

import scala.concurrent.duration.*

/** Task 4.2.5 / 4.3.4 — the real worker supervisor (`forge-app`'s implementation of the [[Supervisor]] seam).
  *
  * Composes the §6.2 supervision lifecycle: [[WorkerProvisioner]] (the isolated clone, O10) + a [[WorkerRuntime]] (the
  * spawn mechanism — [[HostProcessRuntime]] for a host OS process, B4, or [[ContainerRuntime]] for an isolated OCI
  * container, O7). On a `spawn-worker` request it provisions, launches, records `worker.spawned` (with the worker's
  * **liveness key** — a host `pid` or a container id), activates the workstream, and forks an **exit-watcher** that
  * records `worker.exited` with the real exit code when the execution unit terminates.
  *
  * All durable effects go through [[DaemonState]] (the single writer, §6.3.1), so the fleet is rebuildable from the
  * instance log alone (§6.4). The supervisor holds only volatile handles ([[liveHandles]], for diagnostics / future
  * explicit kill); it deliberately does **not** kill its workers on its own shutdown — a worker must survive the
  * daemon's restart (the §6.4 exit criterion), so they are launched via `Resource.allocated` and intentionally orphaned
  * (a host child's stdio captured to a per-worker file; a container owned by the OCI daemon), so they keep running
  * cleanly after the daemon dies.
  *
  * The **exit-watcher fibers** it forks ([[watchLaunched]] / [[watchProcessHandle]] / [[watchContainer]]) are a
  * different matter from the workers themselves: they are bookkeeping the daemon owns, so they run under a
  * `cats.effect.std.Supervisor` (the caller-owned [[fibers]] scope passed to [[RealSupervisor.build]]). Closing that
  * scope (the daemon's `Supervisor` resource on stop, or a test fixture's on teardown) **cancels and awaits** them — a
  * barrier that guarantees no watcher is still writing to the instance log/cache when the scope unwinds (a worker that
  * outlives the daemon is re-watched by the next boot's [[reconcile]]). Without it the watchers were detached `start`
  * fibers that could race a stopping daemon or a test deleting the instance directory underneath an in-flight
  * `worker.exited` write.
  *
  * Two recovery paths re-establish supervision after a daemon restart, neither of which has a [[LaunchedWorker]] for
  * the pre-restart worker. Both **dispatch on the recorded liveness key** (a host pid via [[ProcessHandle]], a
  * container id via the injected [[OciRuntime]]), so a daemon restarted in *either* spawn mode reconciles *both*
  * topologies it finds in the log:
  *
  *   - [[reconcile]] (one-shot, on boot, §6.4): for each worker the rebuilt state still considers `live`, probe its
  *     key. A dead key is surfaced immediately as `worker.exited`; a still-alive one is left running (its socket feed
  *     resumes as the worker phones home to the rebound socket) and watched for exit.
  *   - [[superviseLoop]] (periodic, the §6.2 daemon-coordinated **cadence**): a liveness sweep that records
  *     `worker.exited` for any still-`live` worker whose execution unit has since died. The backstop that catches an
  *     exit the reattach watchers missed (e.g. a SIGKILL the JVM could not observe). The cadence is *only* this
  *     supervision tick — the FSM-driving poll stays inside the worker (contract §9), the daemon never drives it.
  */
final class RealSupervisor private (
    instance: Instance,
    state: DaemonState,
    runtime: WorkerRuntime,
    oci: OciRuntime,
    provision: (String, os.Path) => IO[Either[String, ForgePaths]],
    liveHandles: Ref[IO, Map[String, LaunchedWorker]],
    reserved: Ref[IO, Map[String, RealSupervisor.Reservation]],
    fibers: FiberSupervisor[IO]
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
        val checkout = instance.workerCheckout(workerId)
        runtime.launch(instance, workerId, repo, feature).allocated.flatMap { case (launched, _) =>
          val (pid, containerId) = launched.key match
            case LivenessKey.HostPid(p) => (Some(p), None)
            case LivenessKey.ContainerId(id) => (None, Some(id))
          for
            _ <- liveHandles.update(_ + (workerId -> launched))
            _ <- state.record(
              InstanceEvent.WorkerSpawned(workerId, workstreamId, repo, feature, checkout.toString, pid, containerId)
            )
            _ <- fibers.supervise(watchLaunched(workerId, launched))
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

  /** Watch a worker we spawned this session: when its [[LaunchedWorker]] reports exit, record `worker.exited` with the
    * real exit code and drop the handle. Topology-agnostic (a host pid via `Process.onExit`, a container via `docker
    * wait`). Cancelled silently if the daemon shuts down first (the worker survives; a restart's [[reconcile]] picks it
    * up). Routes through [[recordExitIfLive]] so the concurrent cadence sweep ([[superviseTick]]) cannot record a
    * second `worker.exited` for the same worker.
    */
  private def watchLaunched(workerId: String, launched: LaunchedWorker): IO[Unit] =
    launched.awaitExit.flatMap(code => recordExitIfLive(workerId, code))

  /** §6.4 boot reconciliation: probe every still-`live` worker the rebuilt state carries, **dispatching on its recorded
    * liveness key** (independent of which runtime *this* daemon spawns with — a daemon restarted without `--container`
    * must still reattach surviving container workers, and vice versa). A dead key is surfaced as `worker.exited`; a
    * live one is left running and watched for exit (we have no [[LaunchedWorker]] for an execution unit started before
    * this boot). Idempotent: it records an exit only for a worker still marked live, so a second reconcile (or an
    * overlapping cadence tick) does not re-record.
    */
  def reconcile: IO[Unit] =
    state.snapshot.flatMap(_.workers.filter(_.live).traverse_(reattach))

  /** Reattach a single still-`live` worker by its recorded key: a host pid via [[ProcessHandle]] (no exit code on
    * reattach → the externally-observed sentinel), a container via the [[OciRuntime]] (`docker wait` yields the real
    * code). A worker with neither key (a degenerate record) is left alone.
    */
  private def reattach(w: WorkerRecord): IO[Unit] =
    livenessKeyOf(w) match
      case Some(LivenessKey.HostPid(pid)) =>
        IO.blocking(ProcessHandle.of(pid).filter(_.isAlive)).flatMap {
          case p if p.isPresent => fibers.supervise(watchProcessHandle(w.workerId, p.get)).void
          case _ => surfaceExit(w.workerId, ExternallyObservedExit)
        }
      case Some(LivenessKey.ContainerId(id)) =>
        oci.running(id).flatMap {
          case true => fibers.supervise(watchContainer(w.workerId, id)).void
          case false => surfaceExit(w.workerId, ExternallyObservedExit)
        }
      case None => IO.unit

  /** Watch a reattached (pre-restart) **host** worker via its OS [[ProcessHandle]]: record `worker.exited` when it
    * terminates. `ProcessHandle.onExit` yields no exit code (only [[Process]] does), so the externally-observed
    * sentinel is used.
    */
  private def watchProcessHandle(workerId: String, ph: ProcessHandle): IO[Unit] =
    IO.fromCompletableFuture(IO.delay(ph.onExit())).flatMap(_ => surfaceExit(workerId, ExternallyObservedExit))

  /** Watch a reattached (pre-restart) **container** worker via `docker wait` (which works for any live container,
    * regardless of who started it): record `worker.exited` with the **real** exit code when it stops. Guarded by the
    * still-live re-check so it does not race a cadence-observed exit into a double-record.
    */
  private def watchContainer(workerId: String, containerId: String): IO[Unit] =
    oci.attach(containerId).awaitExit.flatMap(code => surfaceExit(workerId, code))

  /** The §6.2 daemon-coordinated cadence: a periodic liveness sweep recording `worker.exited` for any still-`live`
    * worker whose execution unit has died. Runs forever (the daemon backgrounds it for its serving lifetime); `cadence`
    * is the tick interval. A backstop for the watchers above — the FSM-driving poll is the worker's, not the daemon's.
    */
  def superviseLoop(cadence: FiniteDuration): IO[Unit] =
    (superviseTick *> IO.sleep(cadence)).foreverM

  private def superviseTick: IO[Unit] =
    state.snapshot.flatMap { snap =>
      snap.workers.filter(_.live).traverse_ { w =>
        aliveByKey(w).flatMap(alive => if alive then IO.unit else surfaceExit(w.workerId, ExternallyObservedExit))
      }
    }

  /** Cheap liveness probe for the cadence sweep, dispatched on the worker's recorded key: a host pid via
    * [[ProcessHandle]], a container via [[OciRuntime.running]]. A keyless record reads as not-alive.
    */
  private def aliveByKey(w: WorkerRecord): IO[Boolean] =
    livenessKeyOf(w) match
      case Some(LivenessKey.HostPid(pid)) => IO.blocking(ProcessHandle.of(pid).map(_.isAlive).orElse(false))
      case Some(LivenessKey.ContainerId(id)) => oci.running(id)
      case None => IO.pure(false)

  /** The worker's recorded liveness key — a `containerId` (Slice 4.3) takes precedence over a `pid`, though a real
    * record carries exactly one. `None` for a keyless (degenerate) record.
    */
  private def livenessKeyOf(w: WorkerRecord): Option[LivenessKey] =
    w.containerId.map(LivenessKey.ContainerId(_)).orElse(w.pid.map(LivenessKey.HostPid(_)))

  /** Record an externally/independently-observed exit (a reconcile / cadence-observed death, or a container's real
    * `docker wait` code). Delegates to [[recordExitIfLive]] — the atomic live-checked recorder.
    */
  private def surfaceExit(workerId: String, code: Int): IO[Unit] = recordExitIfLive(workerId, code)

  /** Record `worker.exited` for `workerId` with `code` **iff the worker is still live**, atomically. The live-check and
    * the append run under a single [[DaemonState.modify]] write-gate hold, so two independent exit observers — the
    * precise [[watchLaunched]]/[[watchContainer]] `awaitExit` and the cadence/reconcile liveness sweep
    * ([[superviseTick]]/[[reattach]]) — cannot both record. A double record would double-release the worker's
    * reservation and, via the fold's last-write-wins on `exitCode`, let a cadence sentinel (`-1`) clobber a precise
    * `docker wait` code (or vice versa). The first observer to reach the gate records; the rest see a not-`live` worker
    * and no-op. The volatile handle is dropped only on the recording observer's pass.
    */
  private def recordExitIfLive(workerId: String, code: Int): IO[Unit] =
    state
      .modify { snap =>
        val events =
          if snap.worker(workerId).exists(_.live) then Vector(InstanceEvent.WorkerExited(workerId, code))
          else Vector.empty
        IO.pure((events, events.nonEmpty))
      }
      .flatMap(IO.whenA(_)(liveHandles.update(_ - workerId)))

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

  /** The default **host-process** supervisor (the frozen Slice-4.2 path): `RealWorkerSpawner` + `WorkerLauncher.Real`,
    * the real [[WorkerProvisioner]] clone provisioning, with [[DockerRuntime]] available so it can still reconcile any
    * pre-existing **container** workers a prior `--container` daemon life left behind (§6.4 — reconcile dispatches on
    * each record's key, not the daemon's spawn mode).
    */
  def build(instance: Instance, state: DaemonState, fibers: FiberSupervisor[IO]): IO[RealSupervisor] =
    build(
      instance,
      state,
      HostProcessRuntime(RealWorkerSpawner, WorkerLauncher.Real),
      DockerRuntime,
      realProvision(instance),
      fibers
    )

  /** The **containerised** supervisor (Slice 4.3, `forge daemon start --container`): runs `forge worker` inside an
    * isolated OCI container via [[DockerRuntime]] ([[ContainerRuntime]]), the same real clone provisioning + reconcile.
    */
  def buildContainer(instance: Instance, state: DaemonState, fibers: FiberSupervisor[IO]): IO[RealSupervisor] =
    build(
      instance,
      state,
      new ContainerRuntime(DockerRuntime, ContainerRuntime.DefaultImage),
      DockerRuntime,
      realProvision(instance),
      fibers
    )

  /** Injectable host-pair build for tests (stub spawner / launcher / provisioner) — preserves the 4.2 test surface;
    * wraps the pair in a [[HostProcessRuntime]] and uses [[DockerRuntime]] for the (untouched, for host workers)
    * container reconcile path.
    */
  def build(
      instance: Instance,
      state: DaemonState,
      spawner: WorkerSpawner,
      launcher: WorkerLauncher,
      provision: (String, os.Path) => IO[Either[String, ForgePaths]],
      fibers: FiberSupervisor[IO]
  ): IO[RealSupervisor] =
    build(instance, state, HostProcessRuntime(spawner, launcher), DockerRuntime, provision, fibers)

  /** Core injectable build over a [[WorkerRuntime]] (spawn mechanism) + an [[OciRuntime]] (container reconcile/probe).
    * The 4.3.4 reconcile-dispatch test injects a stub `OciRuntime`; the runtime is `HostProcessRuntime` or
    * `ContainerRuntime` in production.
    */
  def build(
      instance: Instance,
      state: DaemonState,
      runtime: WorkerRuntime,
      oci: OciRuntime,
      provision: (String, os.Path) => IO[Either[String, ForgePaths]],
      fibers: FiberSupervisor[IO]
  ): IO[RealSupervisor] =
    for
      liveHandles <- Ref.of[IO, Map[String, LaunchedWorker]](Map.empty)
      reserved <- Ref.of[IO, Map[String, Reservation]](Map.empty)
    yield new RealSupervisor(instance, state, runtime, oci, provision, liveHandles, reserved, fibers)

  /** The real [[WorkerProvisioner]]-backed clone provisioner for `instance` (shared by both runtimes — even a container
    * worker's clone is provisioned host-side, then bind-mounted in).
    */
  private def realProvision(instance: Instance): (String, os.Path) => IO[Either[String, ForgePaths]] =
    (workerId, source) => WorkerProvisioner.provision(instance, workerId, source).map(_.left.map(_.message))

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
