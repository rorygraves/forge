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

  test("idempotent re-acquire — nested scope sees Acquired via PID match, outer scope still cleans up"):
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
