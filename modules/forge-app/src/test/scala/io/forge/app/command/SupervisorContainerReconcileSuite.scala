package io.forge.app.command

import cats.effect.{IO, Resource}
import io.forge.core.paths.ForgePaths
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.daemon.{ContainerHandle, ContainerSpec, DaemonState, DockerRuntime, OciRuntime}
import io.forge.instance.{FileInstanceStore, Instance, InstanceEvent}
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Task 4.3.4 — the supervisor's **container** reconcile dispatch (§6.4 for the OCI topology), the container analogue
  * of [[SupervisorReconcileSuite]] (which proves the host-process pid path).
  *
  *   - Always-on (no Docker): reconcile dispatches on a worker record's `containerId` to a **stub** [[OciRuntime]] — a
  *     `running` container is reattached + stays live; a not-`running` one is surfaced as exited. This proves the
  *     pid-vs-container branch added to `reconcile` without needing a Docker daemon.
  *   - Opt-in (`FORGE_IT_RUN_DOCKER=1`): a **real** container (a `busybox` sleeper standing in for the `forge worker`
  *     JVM, exactly as `SupervisorReconcileSuite` uses a real `sh` child) is spawned via a [[ContainerRuntime]]-shaped
  *     runtime, the daemon is abandoned mid-flight (the container survives, owned by Docker), and a fresh supervisor
  *     reconciles + reattaches **by container id** from the instance log alone — then killing it records its exit via
  *     the reattached `docker wait` watcher.
  */
class SupervisorContainerReconcileSuite extends CatsEffectSuite:

  private val dockerEnabled: Boolean = sys.env.get("FORGE_IT_RUN_DOCKER").contains("1")

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** A provisioner that succeeds without cloning (the clone path is proven in WorkerProvisionerSuite). */
  private val provisionOk: (String, os.Path) => IO[Either[String, ForgePaths]] =
    (_, _) => IO.pure(Right(new ForgePaths(os.pwd)))

  /** A runtime whose `launch` must never be called (the dispatch tests hand-record `worker.spawned`). */
  private val unusedRuntime: WorkerRuntime =
    (_, _, _, _) =>
      Resource.eval(IO.raiseError(new IllegalStateException("launch must not be called in a reconcile test")))

  /** A stub OCI runtime whose `running` is fixed and whose `attach` yields a handle that never exits (so a reattached
    * worker stays live for the assertion). `run` is unused.
    */
  private def stubOci(isRunning: Boolean): OciRuntime = new OciRuntime:
    def run(spec: ContainerSpec): Resource[IO, ContainerHandle] = Resource.eval(IO.never)
    def running(containerId: String): IO[Boolean] = IO.pure(isRunning)
    def attach(id: String): ContainerHandle = new ContainerHandle:
      def containerId: String = id
      def awaitExit: IO[Int] = IO.never
      def kill: IO[Unit] = IO.unit

  private def containerWorker(state: DaemonState): IO[Unit] =
    state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g")) *>
      state
        .record(
          InstanceEvent
            .WorkerSpawned("w-1", WorkstreamId("ws-1"), "/repo", FeatureId("feat"), "/clone", None, Some("c-123"))
        )
        .void

  /** Poll `cond` until true (≈2s budget), else fail. */
  private def eventually(cond: IO[Boolean], attempts: Int = 100): IO[Unit] =
    cond.flatMap { ok =>
      if ok then IO.unit
      else if attempts <= 1 then IO.raiseError(new AssertionError("condition not met within budget"))
      else IO.sleep(20.millis) *> eventually(cond, attempts - 1)
    }

  // --- always-on: reconcile dispatches on the container id key ---

  test("reconcile reattaches a still-running containerised worker by id (the record carries containerId, not pid)") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- containerWorker(state)
        sup <- RealSupervisor.build(inst, state, unusedRuntime, stubOci(isRunning = true), provisionOk)
        _ <- sup.reconcile
        snap <- state.snapshot
      yield
        val w = snap.worker("w-1").getOrElse(fail("w-1 missing"))
        assert(w.live, "a running container reattaches and stays live")
        assertEquals(w.containerId, Some("c-123"))
        assertEquals(w.pid, None, "a containerised worker carries no host pid")
    }
  }

  test("reconcile surfaces a containerised worker whose container is gone (running=false → exit recorded)") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- containerWorker(state)
        sup <- RealSupervisor.build(inst, state, unusedRuntime, stubOci(isRunning = false), provisionOk)
        _ <- sup.reconcile
        snap <- state.snapshot
      yield
        val w = snap.worker("w-1").getOrElse(fail("w-1 missing"))
        assert(!w.live, "a gone container is surfaced as exited")
        assertEquals(w.exitCode, Some(RealSupervisor.ExternallyObservedExit))
    }
  }

  // --- opt-in real Docker: spawn a real container, crash, reconcile-by-id, kill, exit (§6.4) ---

  /** A runtime that launches a real `busybox` sleeper container via [[DockerRuntime]] (standing in for the `forge
    * worker` JVM), keyed by its container id.
    */
  private val sleeperRuntime: WorkerRuntime = new WorkerRuntime:
    def launch(instance: Instance, workerId: String, repo: String, feature: FeatureId): Resource[IO, LaunchedWorker] =
      DockerRuntime.run(ContainerSpec(image = "busybox:latest", command = Seq("sleep", "120"))).map { h =>
        new LaunchedWorker:
          def key: LivenessKey = LivenessKey.ContainerId(h.containerId)
          def awaitExit: IO[Int] = h.awaitExit
          def kill: IO[Unit] = h.kill
      }

  test("a containerised worker survives the daemon crash and reconcile reattaches by container id (§6.4)") {
    assume(dockerEnabled, "set FORGE_IT_RUN_DOCKER=1 (and have a running Docker daemon) to run the live container test")
    instance.use { inst =>
      for
        // --- First daemon life: spawn a real container, then abandon the daemon. ---
        state1 <- DaemonState.boot(inst, pid = 1L)
        _ <- state1.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        sup1 <- RealSupervisor.build(inst, state1, sleeperRuntime, DockerRuntime, provisionOk)
        spawned <- sup1.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        _ = assertEquals(spawned, Right("w-1"))
        snap1 <- state1.snapshot
        cid = snap1.worker("w-1").flatMap(_.containerId).getOrElse(fail("w-1 has no recorded container id"))
        _ = assertEquals(snap1.worker("w-1").flatMap(_.pid), None, "a container worker records no host pid")
        // The "crash": drop state1/sup1; the container is owned by Docker, so it keeps running.
        crashAlive <- DockerRuntime.running(cid)
        _ = assert(crashAlive, "the container must outlive the crashed daemon")
        _ <- IO.blocking(os.remove(inst.instanceStateFile, checkExists = false))

        // --- Restart: rebuild from the log alone, reconcile to reattach by id. ---
        state2 <- DaemonState.boot(inst, pid = 2L)
        rebuilt <- state2.snapshot
        _ = assertEquals(rebuilt.worker("w-1").flatMap(_.containerId), Some(cid))
        _ = assert(rebuilt.worker("w-1").exists(_.live), "the survivor rebuilds as live from the log")
        sup2 <- RealSupervisor.build(inst, state2, sleeperRuntime, DockerRuntime, provisionOk)
        _ <- sup2.reconcile
        afterReconcile <- state2.snapshot
        _ = assert(afterReconcile.worker("w-1").exists(_.live), "reconcile leaves the live container running")

        // --- Prove reattachment is live: killing the container records its exit via the rebound docker-wait watcher. ---
        _ <- DockerRuntime.attach(cid).kill
        _ <- eventually(state2.snapshot.map(_.worker("w-1").exists(_.exitCode.isDefined)))
        finalSnap <- state2.snapshot
        _ <- IO.blocking(os.proc("docker", "rm", "-f", cid).call(check = false, stderr = os.Pipe, stdout = os.Pipe))
      yield assert(!finalSnap.worker("w-1").exists(_.live), "the killed container is recorded as exited")
    }
  }
