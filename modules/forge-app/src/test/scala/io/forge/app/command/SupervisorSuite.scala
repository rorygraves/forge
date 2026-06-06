package io.forge.app.command

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.forge.core.paths.ForgePaths
import io.forge.core.{FeatureId, InstanceName, WorkstreamId}
import io.forge.daemon.{DaemonState, RealWorkerSpawner, WorkerHandle, WorkerSpawner, WorkerSpec}
import io.forge.instance.{FileInstanceStore, Instance, InstanceEvent, WorkstreamStatus}
import munit.CatsEffectSuite

import java.util.UUID
import scala.concurrent.duration.*

/** Task 4.2.5 — the worker supervisor's lifecycle, proven against **real** OS child processes (`sh` builtins) with the
  * `forge worker` launcher stubbed out (the real JVM launch is the Task 4.2.6 live dogfood). Three behaviours:
  *
  *   - **spawn** — provision (stubbed) → launch a real child → record `worker.spawned` (pid, checkout, workstream) →
  *     flip the workstream `Planning → Active`; the exit-watcher records `worker.exited` with the real code on exit.
  *   - **reconcile** (§6.4 boot recovery) — a dead pid is surfaced as `worker.exited`; a live one is left running and
  *     watched (killing it later records the exit).
  *   - **cadence** (§6.2) — the periodic supervision sweep records `worker.exited` for a still-`live` worker whose pid
  *     has since died, with no exit-watcher attached.
  */
class SupervisorSuite extends CatsEffectSuite:

  private val instance: Resource[IO, Instance] =
    Resource.make {
      val home = os.Path("/tmp") / s"fd-${UUID.randomUUID().toString.take(8)}"
      new FileInstanceStore(home).create(InstanceName("demo")).flatMap {
        case Right(inst) => IO.pure(inst)
        case Left(err) => IO.raiseError(new IllegalStateException(s"could not create test instance: $err"))
      }
    }(inst => IO.blocking(os.remove.all(inst.dir / os.up / os.up / os.up)).void)

  /** A launcher that ignores the worker identity and always yields `cmd` (a real `sh` child). */
  private def launcherOf(cmd: List[String]): WorkerLauncher =
    (_: Instance, _: String, _: String, _: FeatureId) => WorkerSpec(cmd, cwd = os.pwd)

  /** A provisioner that succeeds without cloning (the clone path is proven in WorkerProvisionerSuite / 4.2.6). */
  private val provisionOk: (String, os.Path) => IO[Either[String, ForgePaths]] =
    (_, _) => IO.pure(Right(new ForgePaths(os.pwd)))

  /** A provisioner that sleeps before succeeding, widening the allocate→record window so a concurrent-spawn id race
    * would actually manifest if allocation were not atomic.
    */
  private val provisionSlow: (String, os.Path) => IO[Either[String, ForgePaths]] =
    (_, _) => IO.sleep(60.millis).as(Right(new ForgePaths(os.pwd)))

  /** Spawn a real child via the real spawner and hand back its (kept-alive) handle for the test to drive/kill. */
  private def childHandle(cmd: List[String]): IO[WorkerHandle] =
    RealWorkerSpawner.spawn(WorkerSpec(cmd, cwd = os.pwd)).allocated.map(_._1)

  /** Poll `cond` until true (≈2s budget), else fail. */
  private def eventually(cond: IO[Boolean], attempts: Int = 100): IO[Unit] =
    cond.flatMap { ok =>
      if ok then IO.unit
      else if attempts <= 1 then IO.raiseError(new AssertionError("condition not met within budget"))
      else IO.sleep(20.millis) *> eventually(cond, attempts - 1)
    }

  private def supervisor(
      inst: Instance,
      state: DaemonState,
      launcher: WorkerLauncher,
      provision: (String, os.Path) => IO[Either[String, ForgePaths]] = provisionOk,
      spawner: WorkerSpawner = RealWorkerSpawner
  ): IO[RealSupervisor] =
    RealSupervisor.build(inst, state, spawner, launcher, provision)

  test("spawnWorker provisions, launches a real child, records worker.spawned, and activates the workstream") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "add auth"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
        res <- sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        snap <- state.snapshot
      yield
        assertEquals(res, Right("w-1"))
        val w = snap.worker("w-1").getOrElse(fail("w-1 not recorded"))
        assertEquals(w.workstreamId, Some(WorkstreamId("ws-1")))
        assertEquals(w.feature, FeatureId("feat"))
        assertEquals(w.checkoutRoot, Some(inst.workerCheckout("w-1").toString))
        assert(w.pid.exists(_ > 0L), s"expected a real pid, got ${w.pid}")
        assert(w.live, "expected the freshly-spawned worker to be live")
        val ws = snap.workstream(WorkstreamId("ws-1")).getOrElse(fail("ws-1 missing"))
        assertEquals(ws.status, WorkstreamStatus.Active)
        assertEquals(ws.workers, Vector("w-1"))
    }
  }

  test("the exit-watcher records worker.exited with the real exit code when the child terminates") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "exit 9")))
        _ <- sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        _ <- eventually(state.snapshot.map(_.worker("w-1").exists(_.exitCode.isDefined)))
        snap <- state.snapshot
      yield
        val w = snap.worker("w-1").getOrElse(fail("w-1 missing"))
        assertEquals(w.exitCode, Some(9))
        assert(!w.live, "a worker that has exited is not live")
    }
  }

  test("spawnWorker is idempotent: a second request for the same workstream+feature does not double-spawn") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
        first <- sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        second <- sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        snap <- state.snapshot
      yield
        assertEquals(first, Right("w-1"))
        assertEquals(second, Right("w-1"))
        assertEquals(snap.workers.size, 1)
    }
  }

  test("concurrent spawns for distinct features allocate distinct ids (no allocate-the-same-id race)") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")), provision = provisionSlow)
        // Fire five spawns at once, each a distinct feature so the idempotency guard doesn't collapse them. With a
        // non-atomic allocation they would read the same snapshot and several would pick w-1; atomic allocation gives
        // five distinct ids.
        results <- (1 to 5).toList.parTraverse(i =>
          sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId(s"feat-$i"))
        )
        snap <- state.snapshot
      yield
        assert(results.forall(_.isRight), s"all spawns should succeed: $results")
        val ids = results.collect { case Right(id) => id }
        assertEquals(ids.toSet.size, 5, s"all five worker ids must be distinct: $ids")
        assertEquals(snap.workers.map(_.workerId).toSet, ids.toSet, "every allocated worker must be recorded once")
        assertEquals(snap.workstream(WorkstreamId("ws-1")).map(_.workers.size), Some(5))
    }
  }

  test("concurrent identical spawns collapse to a single worker (in-flight idempotency)") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")), provision = provisionSlow)
        // Same workstream + same feature, fired concurrently: the in-flight reservation must dedup them to one worker.
        results <- (1 to 5).toList.parTraverse(_ => sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat")))
        snap <- state.snapshot
      yield
        assert(results.forall(_.isRight), s"all spawns should succeed: $results")
        assertEquals(results.collect { case Right(id) => id }.toSet, Set("w-1"), "all must collapse to one id")
        assertEquals(snap.workers.size, 1, "exactly one worker must be spawned for identical concurrent requests")
    }
  }

  test("concurrent identical spawns all observe the SAME launch failure (no false success for the duplicate)") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        // The winning spawn's launch fails (provision returns Left after a delay). The duplicates must wait on that
        // launch and see the same failure — not a Right(workerId) for a worker that was never recorded.
        failSlow = (_: String, _: os.Path) => IO.sleep(40.millis).as(Left("boom"))
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")), provision = failSlow)
        results <- (1 to 5).toList.parTraverse(_ => sup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat")))
        snap <- state.snapshot
      yield
        assert(results.forall(_.isLeft), s"every caller must observe the failure, not a false success: $results")
        assertEquals(results.toSet.size, 1, s"all callers must observe the identical failure result: $results")
        assert(results.head.left.exists(_.contains("boom")), s"the failure must carry the launch reason: $results")
        assertEquals(snap.workers, Vector.empty, "a failed launch records no worker")
    }
  }

  test("spawnWorker refuses an unknown workstream and a provisioning failure, recording nothing") {
    instance.use { inst =>
      for
        state <- DaemonState.boot(inst, pid = 1L)
        unknownSup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
        unknown <- unknownSup.spawnWorker(WorkstreamId("ws-404"), "/repo", FeatureId("feat"))
        _ <- state.record(InstanceEvent.WorkstreamCreated(WorkstreamId("ws-1"), "g"))
        failSup <- supervisor(
          inst,
          state,
          launcherOf(List("sh", "-c", "sleep 2")),
          provision = (_, _) => IO.pure(Left("boom"))
        )
        failed <- failSup.spawnWorker(WorkstreamId("ws-1"), "/repo", FeatureId("feat"))
        snap <- state.snapshot
      yield
        assert(unknown.isLeft && unknown.left.exists(_.contains("ws-404")), unknown.toString)
        assert(failed.isLeft && failed.left.exists(_.contains("boom")), failed.toString)
        assertEquals(snap.workers, Vector.empty)
    }
  }

  test("reconcile surfaces a worker whose pid has died (§6.4)") {
    instance.use { inst =>
      for
        dead <- childHandle(List("sh", "-c", "exit 0"))
        _ <- dead.awaitExit // ensure the pid is gone before reconcile probes it
        state <- DaemonState.boot(inst, pid = 1L)
        _ <- state.record(
          InstanceEvent.WorkerSpawned("w-1", WorkstreamId("ws-1"), "/repo", FeatureId("feat"), "/clone", Some(dead.pid))
        )
        sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
        _ <- sup.reconcile
        snap <- state.snapshot
      yield
        val w = snap.worker("w-1").getOrElse(fail("w-1 missing"))
        assert(!w.live, "a dead-pid worker is surfaced as exited by reconcile")
        assertEquals(w.exitCode, Some(RealSupervisor.ExternallyObservedExit))
    }
  }

  test("reconcile leaves a live worker running and watches it; killing it later records the exit") {
    instance.use { inst =>
      childHandle(List("sh", "-c", "sleep 30")).flatMap { alive =>
        for
          state <- DaemonState.boot(inst, pid = 1L)
          _ <- state.record(
            InstanceEvent.WorkerSpawned(
              "w-1",
              WorkstreamId("ws-1"),
              "/repo",
              FeatureId("feat"),
              "/clone",
              Some(alive.pid)
            )
          )
          sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
          _ <- sup.reconcile
          afterReconcile <- state.snapshot
          _ = assert(afterReconcile.worker("w-1").exists(_.live), "a still-alive worker stays live after reconcile")
          _ <- alive.kill
          _ <- eventually(state.snapshot.map(_.worker("w-1").exists(_.exitCode.isDefined)))
          snap <- state.snapshot
        yield assert(!snap.worker("w-1").exists(_.live), "killing the reattached worker records its exit")
      }
    }
  }

  test("the cadence sweep records worker.exited for a live worker whose pid dies (§6.2)") {
    instance.use { inst =>
      childHandle(List("sh", "-c", "sleep 30")).flatMap { alive =>
        for
          state <- DaemonState.boot(inst, pid = 1L)
          _ <- state.record(
            InstanceEvent.WorkerSpawned(
              "w-1",
              WorkstreamId("ws-1"),
              "/repo",
              FeatureId("feat"),
              "/clone",
              Some(alive.pid)
            )
          )
          sup <- supervisor(inst, state, launcherOf(List("sh", "-c", "sleep 2")))
          // Run only the cadence loop (no reconcile ⇒ no ProcessHandle watcher), so the exit is detected by the sweep.
          result <- sup
            .superviseLoop(40.millis)
            .background
            .use { _ =>
              alive.kill *> eventually(state.snapshot.map(_.worker("w-1").exists(_.exitCode.isDefined)))
            }
            .as(true)
        yield assert(result, "cadence sweep should have recorded the exit")
      }
    }
  }
