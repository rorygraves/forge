package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.lock.{FileProcessLock, LockAcquireResult, LockMetadata}
import io.forge.core.paths.ForgePaths

import java.io.IOException
import java.time.Instant

/** PR-G G3 sibling-JVM helper. Spawned by [[ProcessLockMultiJvmSuite]] to hold a `FileProcessLock` against a shared
  * `ForgePaths` rooted at the directory passed as the first argv, then either:
  *
  *   - Exit cleanly when stdin closes (parent-driven shutdown — exercises clean Resource release + metadata cleanup);
  *     OR
  *   - Stay blocked until SIGKILL (parent-driven crash — exercises `Stale(_)` recovery the next time a peer JVM tries
  *     to acquire).
  *
  * Communication protocol (newline-delimited on stdout, parsed by the parent test):
  *
  *   - `ACQUIRED:<pid>` — emitted once the OS lock is held and metadata is written. Tells the parent that the sibling
  *     JVM is ready and contention against the lock will surface the live-holder path (`Held` or `LiveHolderRefused`).
  *   - `STALE`, `HELD`, `ERROR:<message>` — emitted if the acquire didn't succeed for any reason. The parent test fails
  *     fast on these because the scenarios in `ProcessLockMultiJvmSuite` always set up a clean lock dir first.
  *
  * The parent test signals "exit now" by closing this JVM's stdin (`subprocess.closeStdin`). The blocking
  * `System.in.read()` loop unblocks with `-1`, the Resource scope exits, and the metadata + OS lock release together.
  * For the "crash" scenario, the parent invokes `Subprocess.kill(grace = 0.millis)` instead — SIGTERM is delivered
  * immediately followed by SIGKILL, the JVM goes down before any Resource finalizer can run, and the `.lock.json`
  * survives for the next start-up to surface as `Stale`.
  */
object LockHolderMain:

  def main(args: Array[String]): Unit =
    if args.length < 1 then
      System.err.println("usage: LockHolderMain <repoRoot>")
      System.exit(2)

    val repoRoot = os.Path(args(0))
    val paths = new ForgePaths(repoRoot)
    val lock = new FileProcessLock(paths)
    val metadata = LockMetadata(
      pid = ProcessHandle.current().pid(),
      hostname = "lock-holder",
      startedAt = Instant.now(),
      command = "io.forge.it.LockHolderMain",
      feature = None
    )

    val program: IO[Unit] =
      lock.acquire(metadata, acceptStale = false).use {
        case LockAcquireResult.Acquired =>
          IO.blocking {
            println(s"ACQUIRED:${ProcessHandle.current().pid()}")
            System.out.flush()
          } *> waitForStdinClose
        case LockAcquireResult.Stale(_) =>
          IO.blocking {
            println("STALE")
            System.out.flush()
          }
        case LockAcquireResult.Held(_) =>
          IO.blocking {
            println("HELD")
            System.out.flush()
          }
      }

    try program.unsafeRunSync()
    catch
      case t: Throwable =>
        println(s"ERROR:${t.getClass.getSimpleName}: ${Option(t.getMessage).getOrElse("")}")
        System.out.flush()
        System.exit(3)

  /** Block until parent closes our stdin. `System.in.read()` returns `-1` on EOF; until then we sit on the blocking
    * read so the JVM stays alive and the lock stays held. The IO is wrapped in `IO.blocking` so cats-effect's blocking
    * pool runs it (otherwise we'd starve the compute pool).
    */
  private def waitForStdinClose: IO[Unit] = IO.blocking {
    try
      var ch = 0
      while ch != -1 do ch = System.in.read()
    catch case _: IOException => ()
  }
