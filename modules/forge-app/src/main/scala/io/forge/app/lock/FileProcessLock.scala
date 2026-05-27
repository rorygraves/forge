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
  * **Same-JVM re-acquire is reference-counted on the instance.** Every [[acquire]] call on the same `FileProcessLock`
  * shares the underlying OS lock; the lock is released only when the last outstanding Resource scope exits, regardless
  * of release order. This avoids the lexical-nesting trap where an inner "idempotent re-acquire" Resource could outlive
  * the outer one (e.g. via `.allocated`, fiber-based release, or non-nested scopes), see its `Acquired` outcome, and
  * operate as if the lock were held — while the OS lock had already been dropped by the outer scope. Cross-instance
  * same-JVM contention (two distinct `FileProcessLock` instances on the same paths) still surfaces as `Held(_)`:
  * instances do not share refcount state and `FileChannel.tryLock`'s `OverlappingFileLockException` is what catches the
  * collision in that case.
  *
  * @param paths
  *   [[ForgePaths]] supplying the lock file and metadata file paths. Parent directory is created on first acquire.
  */
final class FileProcessLock(paths: ForgePaths) extends ProcessLock:

  import FileProcessLock.*

  private val lockPath: os.Path = paths.lockFile
  private val metadataPath: os.Path = paths.lockMetadataFile

  // `holder` is guarded by `monitor`. All acquire / release / forceRelease paths take the monitor before touching
  // OS lock state or metadata so the ref count and the channel/lock pair move atomically.
  private val monitor: AnyRef = new Object
  private var holder: Option[Holder] = None

  override def acquire(metadata: LockMetadata, acceptStale: Boolean): Resource[IO, LockAcquireResult] =
    Resource
      .make(acquireOnce(metadata, acceptStale)) {
        case Acquisition.Refcounted(_) => decrementRef
        case Acquisition.Inert(_) => IO.unit
      }
      .map(_.result)

  override def forceRelease: IO[ForceReleaseResult] = IO.blocking {
    monitor.synchronized {
      if holder.isDefined then
        // This same instance currently holds the lock — `forge unlock --force` doesn't yank a live in-process
        // holder. Surfacing the on-disk metadata gives the operator the same diagnostic the cross-process refusal
        // path produces.
        ForceReleaseResult.LiveHolderRefused(readMetadataSilent())
      else
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
  }

  private def acquireOnce(metadata: LockMetadata, acceptStale: Boolean): IO[Acquisition] = IO.blocking {
    monitor.synchronized {
      holder match
        case Some(h) =>
          // Same instance already owns the OS lock — share it via ref count. The new Resource scope's release will
          // decrement; the OS lock drops only when the last reference goes away (regardless of release order).
          holder = Some(h.copy(refCount = h.refCount + 1))
          Acquisition.Refcounted(LockAcquireResult.Acquired)
        case None =>
          tryFreshAcquireLocked(metadata, acceptStale)
    }
  }

  /** PRECONDITION: caller holds `monitor` and `holder` is `None`. */
  private def tryFreshAcquireLocked(metadata: LockMetadata, acceptStale: Boolean): Acquisition =
    ensureParentDir()
    val channel = openChannel()
    try
      val lock =
        try channel.tryLock()
        catch case _: OverlappingFileLockException => null
      if lock == null then
        // OS lock held by another process OR by a different `FileProcessLock` instance in this JVM (same-JVM
        // contention manifests as `OverlappingFileLockException`, caught above). Both surface as `Held` — the
        // caller doesn't need to distinguish.
        closeQuietly(channel)
        Acquisition.Inert(LockAcquireResult.Held(readMetadataSilent()))
      else handleAcquiredLocked(channel, lock, metadata, acceptStale)
    catch
      case t: Throwable =>
        closeQuietly(channel)
        throw t

  /** PRECONDITION: caller holds `monitor`, `holder` is `None`, and `lock` is a freshly-acquired OS lock on `channel`.
    */
  private def handleAcquiredLocked(
      channel: FileChannel,
      lock: FileLock,
      metadata: LockMetadata,
      acceptStale: Boolean
  ): Acquisition =
    readMetadataSilent() match
      case None =>
        writeMetadata(metadata)
        holder = Some(Holder(channel, lock, refCount = 1))
        Acquisition.Refcounted(LockAcquireResult.Acquired)

      case Some(m) if m.pid == OurPid =>
        // We just won the OS lock fresh (the same-instance re-acquire path is handled above in `acquireOnce` via
        // the `holder = Some(_)` branch), so matching-PID metadata on disk means our PID was reused after a prior
        // crashed run. Refresh the metadata and proceed — there is no live holder to defer to.
        writeMetadata(metadata)
        holder = Some(Holder(channel, lock, refCount = 1))
        Acquisition.Refcounted(LockAcquireResult.Acquired)

      case Some(stale) =>
        if acceptStale then
          writeMetadata(metadata)
          holder = Some(Holder(channel, lock, refCount = 1))
          Acquisition.Refcounted(LockAcquireResult.Acquired)
        else
          // Surface Stale for the caller to prompt; release the OS lock so a subsequent
          // `acquire(_, acceptStale = true)` (or a `forge unlock --force`) can re-enter cleanly.
          releaseQuietly(lock)
          closeQuietly(channel)
          Acquisition.Inert(LockAcquireResult.Stale(stale))

  private def decrementRef: IO[Unit] = IO
    .blocking {
      monitor.synchronized {
        holder match
          case Some(h) if h.refCount > 1 =>
            holder = Some(h.copy(refCount = h.refCount - 1))
          case Some(h) =>
            // Last outstanding reference — release OS lock and remove our own metadata.
            try
              val _ = os.remove(metadataPath, checkExists = false)
            finally
              releaseQuietly(h.lock)
              closeQuietly(h.channel)
              holder = None
          case None =>
            // Shouldn't happen: a `Refcounted` Acquisition is only handed out when `holder` is (or becomes) Some.
            // Defensive no-op — never throw from a release.
            ()
      }
    }
    .handleErrorWith(_ => IO.unit)

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

  /** PID of the running JVM — used to detect PID-reuse-after-crash on a fresh OS-lock acquire (matching PID metadata on
    * disk while we just won the lock means a prior process from this PID-slot crashed without cleanup).
    */
  private val OurPid: Long = ProcessHandle.current().pid()

  /** Per-instance OS-lock holder + reference count. Guarded by `FileProcessLock.monitor`. */
  private final case class Holder(channel: FileChannel, lock: FileLock, refCount: Int)

  /** Internal acquire-step outcome that the Resource finalizer dispatches on:
    *   - `Refcounted` — the caller's release decrements the ref count and drops the OS lock when it reaches zero.
    *   - `Inert` — release is a no-op (this scope never participated in OS-lock ownership: `Held(_)` and `Stale(_)`
    *     outcomes).
    */
  private sealed trait Acquisition:
    def result: LockAcquireResult

  private object Acquisition:
    final case class Refcounted(result: LockAcquireResult) extends Acquisition
    final case class Inert(result: LockAcquireResult) extends Acquisition
