package io.forge.daemon

import cats.effect.IO
import munit.CatsEffectSuite

import scala.concurrent.duration.*

/** Task 4.2.1 — the worker-process spawn mechanism ([[RealWorkerSpawner]]), the daemon side of the B4 boundary. Proven
  * against **real** OS child processes (`sh` builtins — universally present on the darwin/Linux build hosts), not
  * mocks: a real process lifecycle is exactly the contract `forge-agents`' `Subprocess` tests exercise the same way,
  * and the "fake CLIs must mirror real blocking behaviour" lesson means the spawn path wants a real subprocess.
  *
  * The full tie-together — the daemon spawning a real `forge worker` child that phones home over the socket — is the
  * Task 4.2.6 live dogfood; here each half of the boundary is unit-proven (this suite = spawn/lifecycle;
  * `WorkerCommandSuite` in forge-app = the worker→daemon socket export).
  */
class WorkerSpawnerSuite extends CatsEffectSuite:

  test("spawn a real child that exits with a code; pid is live and awaitExit yields that code") {
    RealWorkerSpawner.spawn(WorkerSpec(List("sh", "-c", "exit 7"), cwd = os.pwd)).use { handle =>
      for
        code <- handle.awaitExit
        _ = assert(handle.pid > 0L, s"expected a real pid, got ${handle.pid}")
      yield assertEquals(code, 7)
    }
  }

  test("a long-running child can be killed; kill is idempotent and reaps the process") {
    RealWorkerSpawner.spawn(WorkerSpec(List("sh", "-c", "sleep 60"), cwd = os.pwd)).use { handle =>
      for
        // The sleeper does not exit on its own within the test budget; killing it makes awaitExit complete.
        _ <- handle.kill
        code <- handle.awaitExit.timeout(5.seconds)
        // A forcibly-destroyed process exits non-zero (SIGKILL → 137 on POSIX); we only assert it is not a clean 0.
        _ = assertNotEquals(code, 0)
        _ <- handle.kill // idempotent: killing an already-reaped process is a no-op
      yield ()
    }
  }

  test("the Resource finalizer force-kills a child still alive at release") {
    for
      pidRef <- RealWorkerSpawner
        .spawn(WorkerSpec(List("sh", "-c", "sleep 60"), cwd = os.pwd))
        .use(handle => IO.pure(handle.pid))
      // After the Resource releases, the recorded pid must no longer resolve to a live process.
      alive <- IO.blocking(ProcessHandle.of(pidRef).map(_.isAlive).orElse(false))
    yield assert(!alive, s"expected pid $pidRef to be reaped after Resource release")
  }
