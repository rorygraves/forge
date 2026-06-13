package io.forge.app.command

import cats.effect.{IO, Resource}
import cats.effect.std.Supervisor as FiberSupervisor
import io.forge.core.paths.ForgePaths
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.daemon.{DaemonState, RealWorkerSpawner, WorkerSpawner, WorkerSpec}
import io.forge.instance.{FileInstanceStore, Instance, InstanceEvent}
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Task 4.2.6 — the **slice exit criterion**, automated: a real worker **process** survives a daemon **crash** and is
  * **reattached** on restart from the instance log alone (§6.4). This is the composition the two earlier suites each
  * prove half of:
  *
  *   - [[io.forge.app.command.SupervisorSuite]] proves spawn / exit-watch / reconcile / cadence against real `sh`
  *     children, but within a single daemon life (no crash).
  *   - [[io.forge.daemon.DaemonCrashRecoverySuite]] proves the status snapshot + event tail rebuild from the log after
  *     a hard crash, but for a *record-only* worker (no real OS process behind it).
  *
  * Here a real child is spawned by a [[RealSupervisor]], the supervisor + its [[DaemonState]] are abandoned mid-life
  * (the §6.4 "crash" — the spawned child is launched via `Resource.allocated` and intentionally **orphaned**, so it
  * keeps running after the daemon dies), the derived cache is deleted so only the canonical log survives, a fresh
  * `DaemonState` + `RealSupervisor` are booted, and `reconcile` re-establishes supervision over the *same still-alive
  * pid* without interrupting it. Killing the reattached worker afterwards records its exit via the rebound
  * [[ProcessHandle]] watcher — proving the reattachment is live, not just a bookkeeping reload.
  *
  * The matching real-`forge daemon` / real-`forge worker` / real-`kill -9` exercise is dogfood #7,
  * `docs/dogfood/4.2-supervisor.md`.
  */
class SupervisorReconcileSuite extends CatsEffectSuite:

  /** A short `/tmp`-rooted instance (macOS caps `sun_path` at 104 bytes), created on disk and cleaned up after. Paired
    * with a fiber Supervisor the test's `supervisor(...)` runs its exit-watchers under: LIFO release closes `fibers`
    * (cancelling + awaiting any in-flight `worker.exited` writer) before `os.remove.all`, so no watcher races the
    * delete.
    */
  private val instance: Resource[IO, (Instance, FiberSupervisor[IO])] =
    for
      inst <- Resource.make {
        val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
        new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
          case Right(inst) => IO.pure(inst)
          case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
        }
      }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)
      fibers <- FiberSupervisor[IO]
    yield (inst, fibers)

  /** A launcher that ignores the worker identity and always yields `cmd` (a real `sh` child standing in for the real
    * `forge worker` JVM — the JVM launch is exercised live in dogfood #7).
    */
  private def launcherOf(cmd: List[String]): WorkerLauncher =
    (_: Instance, _: String, _: String, _: FeatureId) => WorkerSpec(cmd, cwd = os.pwd)

  /** A provisioner that succeeds without cloning (the clone path is proven in WorkerProvisionerSuite). */
  private val provisionOk: (String, os.Path) => IO[Either[String, ForgePaths]] =
    (_, _) => IO.pure(Right(new ForgePaths(os.pwd)))

  private def supervisor(
      inst: Instance,
      state: DaemonState,
      cmd: List[String],
      fibers: FiberSupervisor[IO],
      spawner: WorkerSpawner = RealWorkerSpawner
  ): IO[RealSupervisor] =
    RealSupervisor.build(inst, state, spawner, launcherOf(cmd), provisionOk, fibers)

  private def pidAlive(pid: Long): IO[Boolean] =
    IO.blocking(ProcessHandle.of(pid).map(_.isAlive).orElse(false))

  /** Poll `cond` until true (≈2s budget), else fail. */
  private def eventually(cond: IO[Boolean], attempts: Int = 100): IO[Unit] =
    cond.flatMap { ok =>
      if ok then IO.unit
      else if attempts <= 1 then IO.raiseError(new AssertionError("condition not met within budget"))
      else IO.sleep(20.millis) *> eventually(cond, attempts - 1)
    }

  test("a spawned worker process survives the daemon crash and reconcile reattaches to the same live pid (§6.4)") {
    instance.use { case (inst, fibers) =>
      for
        // --- First daemon life: spawn a real long-lived worker, then abandon the daemon mid-flight. ---
        state1 <- DaemonState.boot(inst, pid = 1L)
        _ <- state1.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "add auth"))
        sup1 <- supervisor(inst, state1, List("sh", "-c", "sleep 30"), fibers)
        spawned <- sup1.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        _ = assertEquals(spawned, Right("w-1"))
        snap1 <- state1.snapshot
        pid = snap1.worker("w-1").flatMap(_.pid).getOrElse(fail("w-1 has no recorded pid"))
        _ <- IO(assert(pid > 0L, s"expected a real pid, got $pid"))
        // The "crash": state1 / sup1 are simply dropped — the child was launched via Resource.allocated and orphaned, so
        // it keeps running. Confirm it is genuinely still alive (the §6.4 premise) before the cache is removed.
        _ <- IO(assert(snap1.worker("w-1").exists(_.live), "freshly-spawned worker should be live"))
        crashAlive <- pidAlive(pid)
        _ = assert(crashAlive, "the orphaned worker process must outlive the crashed daemon")
        // Delete the derived cache so the restart is provably log-driven (the cache is a derivable convenience, §6.4).
        _ <- IO.blocking(os.remove(inst.instanceStateFile, checkExists = false))

        // --- Restart: rebuild from the log alone, then reconcile to reattach to the survivor. ---
        state2 <- DaemonState.boot(inst, pid = 2L)
        rebuilt <- state2.snapshot
        // The rebuilt view (log only — cache was deleted) still carries the worker as live with the same pid.
        wRebuilt = rebuilt.worker("w-1").getOrElse(fail("w-1 did not rebuild from the log"))
        _ = assertEquals(rebuilt.bootCount, 2)
        _ = assertEquals(wRebuilt.pid, Some(pid))
        _ = assert(wRebuilt.live, "the survivor must rebuild as live (no worker.exited in the log)")
        _ = assertEquals(wRebuilt.workstreamId, Some(WorkstreamId("ws-1")))

        sup2 <- supervisor(inst, state2, List("sh", "-c", "sleep 30"), fibers)
        _ <- sup2.reconcile
        // Reconcile probed the recorded pid, found it alive, and left it running (no re-spawn, no spurious exit).
        afterReconcile <- state2.snapshot
        _ = assert(afterReconcile.worker("w-1").exists(_.live), "reconcile must leave the live survivor running")
        _ = assertEquals(afterReconcile.workers.size, 1, "reconcile must not double-spawn the survivor")
        stillAlive <- pidAlive(pid)
        _ = assert(stillAlive, "reconcile must not interrupt the survivor")

        // --- Prove the reattachment is live: killing the survivor now records its exit via the rebound watcher. ---
        _ <- IO.blocking(ProcessHandle.of(pid).ifPresent { ph =>
          val _ = ph.destroy()
        })
        _ <- eventually(state2.snapshot.map(_.worker("w-1").exists(_.exitCode.isDefined)))
        finalSnap <- state2.snapshot
      yield
        val w = finalSnap.worker("w-1").getOrElse(fail("w-1 missing"))
        assert(!w.live, "the killed survivor is recorded as exited")
        // A pre-restart child has no WorkerHandle, so the reattached ProcessHandle watcher records the sentinel code.
        assertEquals(w.exitCode, Some(RealSupervisor.ExternallyObservedExit))
    }
  }
