package io.forge.it

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.agents.Subprocess
import io.forge.app.lock.{FileProcessLock, ForceReleaseResult, LockAcquireResult, LockMetadata}
import io.forge.core.paths.ForgePaths

import java.time.Instant
import scala.concurrent.duration.*

/** PR-G G3 — cross-JVM `FileProcessLock` coverage. This is the **sole home** for tests that exercise live OS-level
  * `FileChannel.tryLock` contention; the same-JVM unit suite (`FileProcessLockSuite` in `forge-app`) cannot reproduce
  * `Held(_)` against a real second process or the `forceRelease` live-refusal path because
  * `OverlappingFileLockException` short-circuits same-JVM cross-instance contention.
  *
  * Each scenario spawns a sibling JVM running [[LockHolderMain]] against a shared `ForgePaths` rooted at a fresh
  * `os.temp.dir`:
  *
  *   - **Live `Held(_)` contention** — sibling holds the lock; the parent's `acquire(_, acceptStale = false)` sees
  *     `Held(_)` carrying the sibling's metadata; the parent closes the sibling's stdin so the sibling exits cleanly; a
  *     follow-up acquire then succeeds.
  *   - **Crash-stale recovery** — sibling holds the lock; the parent kills the sibling via `destroyForcibly` (no clean
  *     Resource release runs); the on-disk `.lock.json` survives; the next acquire surfaces `Stale(_)` and a follow-up
  *     `acquire(_, acceptStale = true)` recovers.
  *   - **`forceRelease` live-refusal** — sibling holds the lock; the parent's `forceRelease` sees
  *     `LiveHolderRefused(metadata)`; after the sibling exits cleanly, a second `forceRelease` returns `Released`.
  *
  * **Opt-in via `FORGE_IT_RUN_PROCLOCK=1`** — the per-test JVM spawn (~500ms each) keeps this off the default forge-it
  * run per the [test runtime cost is
  * design](file:///Users/rory.graves/.claude/projects/-Users-rory-graves-workspace-home-forge/memory/feedback-test-runtime-cost.md)
  * memory.
  */
class ProcessLockMultiJvmSuite extends munit.FunSuite:

  override def munitTimeout: scala.concurrent.duration.Duration = 60.seconds

  private val procLockOptIn: Boolean = sys.env.get("FORGE_IT_RUN_PROCLOCK").contains("1")

  /** Locate this test JVM's `java` binary. We launch sibling JVMs through the same binary so JDK / classpath
    * compatibility is guaranteed.
    */
  private val javaBin: String =
    val home = System.getProperty("java.home")
    val candidate = os.Path(home) / "bin" / "java"
    if !os.exists(candidate) then fail(s"unable to locate java binary at $candidate")
    candidate.toString

  /** Test JVM's classpath — passed verbatim to the sibling so LockHolderMain's transitive deps (forge-app, forge-core,
    * cats-effect, upickle, …) all resolve.
    *
    * sbt runs tests in-process with a layered classloader; `System.getProperty("java.class.path")` returns sbt's
    * launcher classpath in that mode, which is NOT what the test classes need. Instead we walk the test classloader
    * chain pulling URLs off any `URLClassLoader` we find and concatenating them; this matches what the test JVM
    * actually loads from and works whether sbt forked the test JVM or ran it in-process.
    */
  private val testClasspath: String =
    val sep = java.io.File.pathSeparator
    val urls = scala.collection.mutable.Buffer.empty[String]
    var cl: ClassLoader = getClass.getClassLoader
    while cl != null do
      cl match
        case u: java.net.URLClassLoader =>
          u.getURLs.foreach(url =>
            try urls += os.Path(java.nio.file.Paths.get(url.toURI)).toString
            catch case _: Throwable => urls += url.getPath
          )
        case _ => ()
      cl = cl.getParent
    if urls.isEmpty then System.getProperty("java.class.path")
    else urls.distinct.mkString(sep)

  /** Argv to spawn one sibling JVM whose only job is to run [[LockHolderMain]] against `repoRoot`. */
  private def lockHolderArgv(repoRoot: os.Path): List[String] =
    List(javaBin, "-cp", testClasspath, "io.forge.it.LockHolderMain", repoRoot.toString)

  private def sampleMetadata(command: String = "forge run test"): LockMetadata =
    LockMetadata(
      pid = ProcessHandle.current().pid(),
      hostname = "test-host",
      startedAt = Instant.now(),
      command = command,
      feature = None
    )

  /** Spawn a sibling JVM holding a lock, run the parent-side block, then close the sibling's stdin so the sibling exits
    * cleanly. Returns the parent block's result. The Resource finalizer kill-protects against the sibling outliving the
    * test.
    *
    * @param crash
    *   when `true`, the sibling is forcibly destroyed instead of receiving a clean stdin-close shutdown — used by the
    *   crash-stale scenario to leave `.lock.json` orphaned.
    */
  private def withLockHolder[A](repoRoot: os.Path, crash: Boolean = false)(body: Subprocess => IO[A]): IO[A] =
    Subprocess
      .spawn(lockHolderArgv(repoRoot))
      .use { sp =>
        for
          ack <- sp.stdout.take(1).compile.lastOrError.timeout(15.seconds)
          _ <- IO(assert(ack.startsWith("ACQUIRED:"), clue = s"sibling reported '$ack' instead of ACQUIRED:<pid>"))
          out <- body(sp)
          _ <-
            if crash then
              // SIGTERM-then-immediate-SIGKILL (grace = 0). Cats-effect's IORuntime shutdown hook stops the runtime
              // threads but does NOT run pending Resource releases — so a JVM brought down this way leaves the
              // sibling's `.lock.json` orphaned on disk, exactly what the Stale-recovery scenario needs to verify.
              sp.kill(grace = 0.millis)
            else sp.closeStdin *> sp.waitFor.void
        yield out
      }

  test("G3.1: live Held — sibling JVM holds the lock; parent acquire returns Held; recover after sibling exits"):
    assume(procLockOptIn, "skipped — set FORGE_IT_RUN_PROCLOCK=1 to opt in (per-scenario JVM spawn is ~500ms each)")

    val program: IO[Unit] = IO.blocking(os.temp.dir(prefix = "forge-lock-mjvm-held-")).flatMap { repo =>
      val paths = new ForgePaths(repo)
      val parentLock = new FileProcessLock(paths)
      withLockHolder(repo) { _ =>
        for
          held <- parentLock.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
          _ <- IO(held match
            case LockAcquireResult.Held(_) => ()
            case other => fail(s"expected Held(_) while sibling holds the lock, got $other")
          )
        yield ()
      } *> {
        // Sibling has now exited cleanly (closeStdin → wait). The Resource finalizer in
        // `FileProcessLock.acquire` removed the sibling's `.lock.json`, so a follow-up acquire should succeed.
        parentLock.acquire(sampleMetadata(), acceptStale = false).use {
          case LockAcquireResult.Acquired => IO.unit
          case other => IO(fail(s"expected Acquired after sibling exit, got $other"))
        }
      }
    }
    program.unsafeRunSync()

  test("G3.2: crash-stale — sibling killed without cleanup; parent sees Stale, accepts stale, then Acquired"):
    assume(procLockOptIn, "skipped — set FORGE_IT_RUN_PROCLOCK=1 to opt in")

    val program: IO[Unit] = IO.blocking(os.temp.dir(prefix = "forge-lock-mjvm-stale-")).flatMap { repo =>
      val paths = new ForgePaths(repo)
      val parentLock = new FileProcessLock(paths)
      // Spawn + crash the sibling without clean release. The on-disk `.lock.json` should survive.
      withLockHolder(repo, crash = true)(_ => IO.unit) *> {
        for
          _ <- IO(assert(os.exists(paths.lockMetadataFile), "crashed sibling should have left .lock.json behind"))
          stale <- parentLock.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
          _ <- IO(stale match
            case LockAcquireResult.Stale(_) => ()
            case other => fail(s"expected Stale(_) after crashed sibling, got $other")
          )
          accepted <- parentLock.acquire(sampleMetadata(), acceptStale = true).use(IO.pure)
          _ <- IO(accepted match
            case LockAcquireResult.Acquired => ()
            case other => fail(s"expected Acquired with acceptStale = true, got $other")
          )
        yield ()
      }
    }
    program.unsafeRunSync()

  test("G3.3: forceRelease live-refusal — sibling holds; forceRelease returns LiveHolderRefused; recovers after"):
    assume(procLockOptIn, "skipped — set FORGE_IT_RUN_PROCLOCK=1 to opt in")

    val program: IO[Unit] = IO.blocking(os.temp.dir(prefix = "forge-lock-mjvm-fr-")).flatMap { repo =>
      val paths = new ForgePaths(repo)
      val parentLock = new FileProcessLock(paths)
      withLockHolder(repo) { _ =>
        for
          refused <- parentLock.forceRelease
          _ <- IO(refused match
            case ForceReleaseResult.LiveHolderRefused(_) => ()
            case other => fail(s"expected LiveHolderRefused while sibling holds the lock, got $other")
          )
        yield ()
      } *> {
        // After sibling clean exit the OS lock is gone AND the sibling's Resource finalizer removed .lock.json,
        // so a fresh forceRelease has nothing to release.
        parentLock.forceRelease.flatMap {
          case ForceReleaseResult.NoLockPresent | ForceReleaseResult.Released => IO.unit
          case other => IO(fail(s"expected NoLockPresent or Released after sibling exit, got $other"))
        }
      }
    }
    program.unsafeRunSync()
