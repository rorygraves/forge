package io.forge.app.lock

import cats.effect.IO
import io.forge.core.paths.ForgePaths

import java.time.Instant
import munit.CatsEffectSuite
import upickle.default.{read => upickleRead, write => upickleWrite}

/** PR-E E4 — same-JVM unit coverage for [[FileProcessLock]]. Cross-JVM `Held(_)` contention and `forceRelease`
  * live-refusal scenarios are exclusively in `forge-it`'s `ProcessLockMultiJvmSuite` (PR-G G3) because
  * `FileChannel.tryLock` semantics are OS-level and same-JVM tests cannot exercise live cross-process contention.
  *
  * Each test gets an isolated `ForgePaths` rooted at a fresh `os.temp.dir` so the lock file / metadata file are unique
  * per test — no global state leaks between cases.
  */
class FileProcessLockSuite extends CatsEffectSuite:

  private def withTempPaths[A](body: ForgePaths => IO[A]): IO[A] =
    IO.blocking(os.temp.dir(prefix = "forge-lock-")).flatMap { repo =>
      val paths = new ForgePaths(repo)
      body(paths)
    }

  private def sampleMetadata(pid: Long = ProcessHandle.current().pid()): LockMetadata =
    LockMetadata(
      pid = pid,
      hostname = "test-host",
      startedAt = Instant.parse("2026-05-27T12:00:00Z"),
      command = "forge run sample",
      feature = None
    )

  test("LockMetadata round-trip preserves every field"):
    val m = sampleMetadata(pid = 12345L)
    val json = upickleWrite(m)
    val decoded = upickleRead[LockMetadata](json)
    IO.pure(assertEquals(decoded, m))

  test("first-ever acquire — file created, metadata written, Acquired returned"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      val meta = sampleMetadata()
      lock.acquire(meta, acceptStale = false).use { result =>
        IO.blocking {
          assertEquals(result, LockAcquireResult.Acquired)
          assert(os.exists(paths.lockFile), "lock file should exist while held")
          assert(os.exists(paths.lockMetadataFile), "metadata file should exist while held")
          val onDisk = upickleRead[LockMetadata](os.read(paths.lockMetadataFile))
          assertEquals(onDisk, meta)
        }
      }
    }

  test("clean release removes .lock.json (OS lock is dropped too)"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      for
        _ <- lock.acquire(sampleMetadata(), acceptStale = false).use_
        gone <- IO.blocking(!os.exists(paths.lockMetadataFile))
        // OS lock can be re-acquired immediately after clean release
        reacquired <- lock.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
      yield
        assert(gone, "metadata file should be removed after clean release")
        assertEquals(reacquired, LockAcquireResult.Acquired)
    }

  test("ref-counted re-acquire — nested scope shares the OS lock; metadata only removed once all refs released"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      val meta = sampleMetadata()
      lock.acquire(meta, acceptStale = false).use { outer =>
        lock.acquire(meta, acceptStale = false).use { inner =>
          IO.blocking {
            assertEquals(outer, LockAcquireResult.Acquired)
            assertEquals(inner, LockAcquireResult.Acquired)
            assert(os.exists(paths.lockMetadataFile), "metadata still present mid-outer-scope")
          }
        }
      } *> IO.blocking {
        assert(!os.exists(paths.lockMetadataFile), "metadata should be removed once outer scope exits")
      }
    }

  test("non-lexical re-acquire — OS lock held until ALL refs released (PR-E review round 1 P1)"):
    // Regression for the P1 finding: an inner scope that outlives its outer must NOT silently lose the OS lock.
    // We use `Resource.allocated` to release the outer Resource explicitly BEFORE the inner — the lexical-nesting
    // assumption no longer holds. Per-instance refcounting on `FileProcessLock` keeps the OS lock alive until the
    // inner ref is also released; a second `FileProcessLock` instance on the same paths still sees `Held(_)` in
    // between.
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      val observer = new FileProcessLock(paths)
      for
        outerPair <- lock.acquire(sampleMetadata(), acceptStale = false).allocated
        (outerResult, outerFinalize) = outerPair
        _ = assertEquals(outerResult, LockAcquireResult.Acquired)
        innerPair <- lock.acquire(sampleMetadata(), acceptStale = false).allocated
        (innerResult, innerFinalize) = innerPair
        _ = assertEquals(innerResult, LockAcquireResult.Acquired)
        // Drop outer first. Inner still holds a ref; the OS lock and metadata must persist.
        _ <- outerFinalize
        midState <- IO.blocking {
          assert(os.exists(paths.lockMetadataFile), "metadata must still exist while inner ref is alive")
        }
        _ = midState
        // A second `FileProcessLock` instance proves the OS lock is still effective — same-JVM contention surfaces
        // as `Held(_)` via `OverlappingFileLockException`.
        midObserved <- observer.acquire(sampleMetadata(pid = 999L), acceptStale = false).use(IO.pure)
        _ = midObserved match
          case LockAcquireResult.Held(_) => ()
          case unexpected => fail(s"expected Held while inner ref alive, got $unexpected")
        // Now drop inner — OS lock truly releases.
        _ <- innerFinalize
        gone <- IO.blocking(!os.exists(paths.lockMetadataFile))
        _ = assert(gone, "metadata should be removed once the last ref releases")
        // Observer can now acquire fresh.
        finalGrab <- observer.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
      yield assertEquals(finalGrab, LockAcquireResult.Acquired)
    }

  test("cross-instance same-JVM — second FileProcessLock on the same path sees Held while first holds"):
    withTempPaths { paths =>
      val instance1 = new FileProcessLock(paths)
      val instance2 = new FileProcessLock(paths)
      instance1.acquire(sampleMetadata(), acceptStale = false).use { _ =>
        instance2.acquire(sampleMetadata(pid = 999L), acceptStale = false).use {
          case LockAcquireResult.Held(meta) =>
            IO {
              assert(
                meta.exists(_.pid == ProcessHandle.current().pid()),
                s"expected Held to carry instance1's metadata, got $meta"
              )
            }
          case other => IO(fail(s"expected Held, got $other"))
        }
      }
    }

  test("forceRelease — same-instance holder → LiveHolderRefused, OS lock and metadata preserved"):
    // forceRelease must not yank the lock out from under an in-process holder on the same instance. The §13 spec
    // is "live OS lock by another process → refuses"; same-JVM-same-instance is the strictest interpretation —
    // the operator can let the holder finish or send a real signal.
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      lock.acquire(sampleMetadata(), acceptStale = false).use { _ =>
        for
          result <- lock.forceRelease
          stillThere <- IO.blocking(os.exists(paths.lockMetadataFile))
        yield
          result match
            case ForceReleaseResult.LiveHolderRefused(meta) =>
              assert(
                meta.exists(_.pid == ProcessHandle.current().pid()),
                s"expected refusal to carry our metadata, got $meta"
              )
            case other => fail(s"expected LiveHolderRefused, got $other")
          assert(stillThere, "metadata must not be removed by a refused forceRelease")
      }
    }

  test("Stale metadata + no live lock — acceptStale = false → Stale(metadata)"):
    withTempPaths { paths =>
      // Synthesise a stale .lock.json from a foreign PID. We use PID 1 which is always live on macOS/Linux but
      // is never the current JVM, so the "PID matches our own" branch can't fire.
      val foreignMeta = sampleMetadata(pid = 1L)
      val lock = new FileProcessLock(paths)
      for
        _ <- IO.blocking {
          os.makeDir.all(paths.lockMetadataFile / os.up)
          os.write.over(paths.lockMetadataFile, upickleWrite(foreignMeta, indent = 2))
        }
        result <- lock.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
      yield result match
        case LockAcquireResult.Stale(m) => assertEquals(m, foreignMeta)
        case other => fail(s"expected Stale, got $other")
    }

  test("Stale metadata + acceptStale = true → Acquired and metadata rewritten"):
    withTempPaths { paths =>
      val foreignMeta = sampleMetadata(pid = 1L)
      val ourMeta = sampleMetadata()
      val lock = new FileProcessLock(paths)
      for
        _ <- IO.blocking {
          os.makeDir.all(paths.lockMetadataFile / os.up)
          os.write.over(paths.lockMetadataFile, upickleWrite(foreignMeta, indent = 2))
        }
        check <- lock.acquire(ourMeta, acceptStale = true).use { result =>
          IO.blocking {
            assertEquals(result, LockAcquireResult.Acquired)
            val onDisk = upickleRead[LockMetadata](os.read(paths.lockMetadataFile))
            assertEquals(onDisk, ourMeta)
          }
        }
      yield check
    }

  test("Unparseable .lock.json — acceptStale = false → Stale(LockMetadata.Unknown)"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      for
        _ <- IO.blocking {
          os.makeDir.all(paths.lockMetadataFile / os.up)
          os.write.over(paths.lockMetadataFile, "{ this is not valid json")
        }
        result <- lock.acquire(sampleMetadata(), acceptStale = false).use(IO.pure)
      yield result match
        case LockAcquireResult.Stale(m) => assertEquals(m, LockMetadata.Unknown)
        case other => fail(s"expected Stale(Unknown), got $other")
    }

  test("Parent directory is created automatically on first acquire"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      val parent = paths.lockFile / os.up
      for
        // Sanity: brand-new repo has no .forge/state directory yet
        existsBefore <- IO.blocking(os.exists(parent))
        _ <- lock.acquire(sampleMetadata(), acceptStale = false).use_
        existsAfter <- IO.blocking(os.exists(parent))
      yield
        assert(!existsBefore, "parent dir should not pre-exist")
        assert(existsAfter, "parent dir should be created by acquire")
    }

  test("forceRelease — no live lock and no metadata → NoLockPresent"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      lock.forceRelease.map(r => assertEquals(r, ForceReleaseResult.NoLockPresent))
    }

  test("forceRelease — no live lock + stale metadata → Released and metadata removed"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      for
        _ <- IO.blocking {
          os.makeDir.all(paths.lockMetadataFile / os.up)
          os.write.over(paths.lockMetadataFile, upickleWrite(sampleMetadata(pid = 1L), indent = 2))
        }
        result <- lock.forceRelease
        gone <- IO.blocking(!os.exists(paths.lockMetadataFile))
      yield
        assertEquals(result, ForceReleaseResult.Released)
        assert(gone, "metadata should be removed after forceRelease")
    }

  test("forceRelease — lock file exists but no metadata + no live lock → NoLockPresent"):
    withTempPaths { paths =>
      val lock = new FileProcessLock(paths)
      for
        _ <- IO.blocking {
          os.makeDir.all(paths.lockFile / os.up)
          os.write.over(paths.lockFile, "")
        }
        result <- lock.forceRelease
      yield assertEquals(result, ForceReleaseResult.NoLockPresent)
    }
