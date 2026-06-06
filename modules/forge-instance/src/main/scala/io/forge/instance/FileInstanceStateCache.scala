package io.forge.instance

import cats.effect.IO

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import upickle.default.{read, write}

/** Phase-4 §6.4 / O8 — file-backed rebuildable **instance state cache** over `instances/<name>/state/instance.json`
  * (Slice 4.1, Task 4.1.2). The instance-scoped analogue of `io.forge.core.state.FileStateCache`.
  *
  * Writes are atomic + durable at the filesystem layer (the `FileStateCache` idiom): serialise [[InstanceState]] to a
  * sibling temp file with `SYNC`, `Files.move` with `ATOMIC_MOVE`, then fsync the parent directory so the rename's
  * directory entry is durable too. A reader of the target path always sees either the previous committed view or the
  * new one — never a half-written file.
  *
  * Per §6.4, the **instance log is canonical** and this cache is a derivable convenience, so `load` treats a missing
  * file, a malformed file (decode failure / truncation), *and* a cache written at a `schemaVersion` this build does not
  * read as `None` — never authoritative. [[verifyAgainstLog]] rebuilds from the log ([[RebuildInstanceState]]) and
  * rewrites the cache when it diverges, which is also the daemon's cold-boot path (Task 4.1.3).
  */
final class FileInstanceStateCache(instance: Instance):

  private def stateFile: os.Path = instance.instanceStateFile

  /** Load the cached state, or `None` if absent / unreadable / a schema this build does not read. */
  def load: IO[Option[InstanceState]] =
    loadOrUnreadable.map {
      case Right(opt) => opt
      case Left(_) => None
    }

  /** Atomically persist `state` (temp file + `ATOMIC_MOVE` + parent fsync). */
  def save(state: InstanceState): IO[Unit] =
    IO.blocking {
      val target = stateFile
      val parent = target / os.up
      if !os.exists(parent) then os.makeDir.all(parent)
      // Sibling temp keeps the `ATOMIC_MOVE` inside one filesystem; the UUID suffix avoids collision with a stray
      // crashed temp file (single-writer is the contract, but defensive).
      val temp = parent / s".${target.last}.tmp.${UUID.randomUUID()}"
      val bytes = write(state, indent = 2).getBytes(StandardCharsets.UTF_8)
      try
        val _ = Files.write(
          temp.toNIO,
          bytes,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE,
          StandardOpenOption.SYNC
        )
        val _ = Files.move(
          temp.toNIO,
          target.toNIO,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        fsyncDirectory(parent)
        ()
      catch
        case t: Throwable =>
          try
            if os.exists(temp) then
              val _ = os.remove(temp)
              ()
          catch case _: Throwable => ()
          throw t
    }

  /** Rebuild [[InstanceState]] from `log` and reconcile the cache against it: if the cache already matches the rebuild
    * it is left alone ([[InstanceVerifyResult.Consistent]]); otherwise the rebuilt state is written and
    * [[InstanceVerifyResult.Rewritten]] returned. The rebuilt state is always the authoritative return value (§6.4 —
    * the log is canonical), so the daemon's cold boot can use this as its single source of truth.
    */
  def verifyAgainstLog(log: FileInstanceLog): IO[InstanceVerifyResult] =
    for
      cached <- load
      rebuilt <- log.replay.map(RebuildInstanceState.fold)
      result <-
        if cached.contains(rebuilt) then IO.pure(InstanceVerifyResult.Consistent(rebuilt))
        else save(rebuilt).as(InstanceVerifyResult.Rewritten(rebuilt))
    yield result

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Three-state read: `Right(Some)` present + decoded + current-schema; `Right(None)` absent; `Left(detail)` present
    * but undecodable. A present-but-stale-schema cache also maps to `Left` (rebuildable, so never authoritative).
    */
  private def loadOrUnreadable: IO[Either[String, Option[InstanceState]]] =
    IO.blocking {
      val file = stateFile
      if !os.exists(file) then Right(None)
      else
        try
          val state = read[InstanceState](os.read(file))
          if state.schemaVersion != RebuildInstanceState.CurrentSchemaVersion then
            Left(s"unsupported schemaVersion ${state.schemaVersion}")
          else Right(Some(state))
        catch
          case t: Throwable =>
            val msg = Option(t.getMessage).getOrElse("<no message>")
            Left(s"${t.getClass.getSimpleName}: $msg")
    }

  private def fsyncDirectory(dir: os.Path): Unit =
    try
      val ch = FileChannel.open(dir.toNIO, StandardOpenOption.READ)
      try ch.force(true)
      finally ch.close()
    catch case _: Throwable => ()

/** Outcome of [[FileInstanceStateCache.verifyAgainstLog]]. Both carry the authoritative rebuilt-from-log state. */
enum InstanceVerifyResult:
  /** The cache already matched the rebuild — no write performed. */
  case Consistent(state: InstanceState)

  /** The cache was absent / unreadable / diverged and has been rewritten from the log. */
  case Rewritten(state: InstanceState)
