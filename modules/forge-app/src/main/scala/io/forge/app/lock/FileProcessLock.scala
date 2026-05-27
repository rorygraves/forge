package io.forge.app.lock

import cats.effect.{IO, Resource}
import io.forge.core.paths.ForgePaths

import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.StandardOpenOption
import upickle.default.{read => upickleRead, write => upickleWrite}

/** OS-backed implementation of [[ProcessLock]]. Pairs `FileChannel.tryLock` on
  * [[io.forge.core.paths.ForgePaths.lockFile]] (`.forge/state/.lock`) with a sibling
  * [[io.forge.core.paths.ForgePaths.lockMetadataFile]] (`.forge/state/.lock.json`) that carries [[LockMetadata]] for
  * the §13 "another process holds the lock" prompt.
  *
  * The OS lock is exclusive across processes; the metadata file is best-effort decoration for the human. On a clean
  * Resource scope exit the metadata is removed alongside the lock release; on a hard crash the metadata survives so the
  * next start-up sees `Stale(_)` and can recover (BM4 / BM5).
  *
  * @param paths
  *   [[ForgePaths]] supplying the lock file and metadata file paths. Parent directory is created on first acquire.
  */
final class FileProcessLock(paths: ForgePaths) extends ProcessLock:

  import FileProcessLock.*

  private val lockPath: os.Path = paths.lockFile
  private val metadataPath: os.Path = paths.lockMetadataFile

  override def acquire(metadata: LockMetadata, acceptStale: Boolean): Resource[IO, LockAcquireResult] =
    Resource.make(acquireOnce(metadata, acceptStale))(release).map(_.result)

  override def forceRelease: IO[ForceReleaseResult] = IO.blocking {
    val metadataPresent = os.exists(metadataPath)
    val lockPresent = os.exists(lockPath)
    if !metadataPresent && !lockPresent then ForceReleaseResult.NoLockPresent
    else
      ensureParentDir()
      val channel = openChannel()
      try
        val lock =
          try channel.tryLock()
          catch case _: OverlappingFileLockException => null
        if lock == null then ForceReleaseResult.LiveHolderRefused(readMetadataSilent())
        else
          try
            if metadataPresent then
              val _ = os.remove(metadataPath, checkExists = false)
              ForceReleaseResult.Released
            else ForceReleaseResult.NoLockPresent
          finally lock.release()
      finally channel.close()
  }

  private def acquireOnce(metadata: LockMetadata, acceptStale: Boolean): IO[Acquisition] = IO.blocking {
    ensureParentDir()
    val channel = openChannel()
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then
        // Either another process holds the OS lock (tryLock returned null) or the same JVM already locked the
        // channel (OFLE caught above). Both surface as `Held` with whatever metadata is on disk; an idempotent
        // re-acquire from the same scope reads its own metadata back through PID-match below only when the OS
        // lock was actually granted, so co-located scopes still need to fall through here for safety.
        val existing = readMetadataSilent()
        val sameJvm = existing.exists(_.pid == OurPid)
        if sameJvm then
          // The outer Resource scope owns the channel + cleanup; the nested scope does no I/O on release.
          closeQuietly(channel)
          Acquisition(LockAcquireResult.Acquired, cleanup = None)
        else
          closeQuietly(channel)
          Acquisition(LockAcquireResult.Held(existing), cleanup = None)
      else handleAcquired(channel, lock, metadata, acceptStale)
    catch
      case t: Throwable =>
        closeQuietly(channel)
        throw t
  }

  private def handleAcquired(
      channel: FileChannel,
      lock: FileLock,
      metadata: LockMetadata,
      acceptStale: Boolean
  ): Acquisition =
    val existing = readMetadataSilent()
    existing match
      case None =>
        writeMetadata(metadata)
        Acquisition(LockAcquireResult.Acquired, Some(Cleanup(channel, lock, removeMetadata = true)))

      case Some(m) if m.pid == OurPid =>
        // We just won the OS lock fresh (the idempotent-same-scope path is handled in `acquireOnce`), so a
        // matching-PID metadata file means our PID was reused after a prior crash. Refresh the metadata and
        // proceed — there is no live holder to defer to.
        writeMetadata(metadata)
        Acquisition(LockAcquireResult.Acquired, Some(Cleanup(channel, lock, removeMetadata = true)))

      case Some(stale) =>
        if acceptStale then
          writeMetadata(metadata)
          Acquisition(LockAcquireResult.Acquired, Some(Cleanup(channel, lock, removeMetadata = true)))
        else
          // Surface Stale for the caller to prompt; release the OS lock so a subsequent
          // `acquire(_, acceptStale = true)` (or a `forge unlock --force`) can re-enter cleanly.
          releaseQuietly(lock)
          closeQuietly(channel)
          Acquisition(LockAcquireResult.Stale(stale), cleanup = None)

  private def release(acquisition: Acquisition): IO[Unit] = acquisition.cleanup match
    case None => IO.unit
    case Some(c) =>
      IO.blocking {
        try
          if c.removeMetadata then
            val _ = os.remove(metadataPath, checkExists = false)
        finally
          releaseQuietly(c.lock)
          closeQuietly(c.channel)
      }.handleErrorWith(_ => IO.unit)

  private def ensureParentDir(): Unit =
    os.makeDir.all(lockPath / os.up)

  private def openChannel(): FileChannel =
    FileChannel.open(
      lockPath.toNIO,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
      StandardOpenOption.READ
    )

  private def readMetadataSilent(): Option[LockMetadata] =
    if !os.exists(metadataPath) then None
    else
      val raw =
        try os.read(metadataPath)
        catch case _: Throwable => return Some(LockMetadata.Unknown)
      try Some(upickleRead[LockMetadata](raw))
      catch case _: Throwable => Some(LockMetadata.Unknown)

  private def writeMetadata(metadata: LockMetadata): Unit =
    os.write.over(metadataPath, upickleWrite(metadata, indent = 2), createFolders = true)

  private def closeQuietly(channel: FileChannel): Unit =
    try channel.close()
    catch case _: Throwable => ()

  private def releaseQuietly(lock: FileLock): Unit =
    try if lock.isValid then lock.release()
    catch case _: Throwable => ()

object FileProcessLock:

  /** PID of the running JVM — used to discriminate idempotent re-acquires (matching PID → silent `Acquired`) from
    * stale-metadata-from-a-different-process scenarios (mismatched PID → `Stale(_)` unless `acceptStale = true`).
    */
  private val OurPid: Long = ProcessHandle.current().pid()

  /** Internal pair returned from the acquire step: the public result variant and an optional cleanup descriptor.
    * `cleanup = None` covers two no-op-release scenarios: (a) `Held(_)` / `Stale(_)` — we never acquired the OS lock so
    * there is nothing to release; (b) idempotent re-acquire where the outer Resource scope owns the channel and will
    * clean up on its own scope exit.
    */
  private final case class Acquisition(result: LockAcquireResult, cleanup: Option[Cleanup])

  /** Held by [[Acquisition]] for clean-release paths. `removeMetadata` is `true` for the cases where this Resource
    * scope owns the on-disk `.lock.json`; `false` when we entered as an idempotent re-acquire whose outer scope already
    * owns the metadata.
    */
  private final case class Cleanup(channel: FileChannel, lock: FileLock, removeMetadata: Boolean)
