package io.forge.core.state

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.fsm.Feature
import io.forge.core.log.{ActionDraft, ActionLog}
import io.forge.core.manifest.ManifestStore
import io.forge.core.paths.ForgePaths

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import upickle.default.{read, write}

/** §4 + §11.5 — file-backed [[StateCache]] over `.forge/state/<feature>.json`.
  *
  * Writes are atomic and durable at the filesystem layer: serialise the [[Feature]], write to a sibling temp file with
  * `SYNC` (file-contents fsync), `Files.move` with `ATOMIC_MOVE`, then fsync the parent directory so the rename's
  * directory entry is also durable. A reader observing the target path always sees either the previous committed view
  * or the new one — never a half-written file — and a crash after `save` returns finds the file in place.
  *
  * Reads via `os.read` + uPickle decode. `load` treats both a missing file **and** a malformed cache (decode failure,
  * truncation, partial flush) as `None`. The §4 invariant is that the local action log is the canonical record and the
  * state cache is rebuildable from log + manifest, so an unreadable cache is never authoritative — callers that need
  * authoritative state run [[verifyAgainstLog]] which will rebuild and overwrite.
  *
  * `verifyAgainstLog` runs the full [[RebuildState.run]] pipeline; if the rebuilt `Feature` matches the cached value
  * the cache is left alone, otherwise it's rewritten and a `harness.cache_invalidated` action is appended to the log.
  * The action's `reason` distinguishes `cache_missing` (no file), `cache_unreadable` (decode failure), and
  * `cache_diverged` (cache present and decoded but differs from the rebuild) so post-hoc audits can tell them apart.
  */
final class FileStateCache(paths: ForgePaths) extends StateCache:

  override def load(featureId: FeatureId): IO[Option[Feature]] =
    loadOrUnreadable(featureId).map {
      case Right(opt) => opt
      case Left(_) => None
    }

  override def save(featureId: FeatureId, feature: Feature): IO[Unit] =
    IO.blocking {
      val target = paths.stateFile(featureId)
      val parent = target / os.up
      if !os.exists(parent) then os.makeDir.all(parent)
      // Sibling temp file so `Files.move(ATOMIC_MOVE)` stays inside the same filesystem (POSIX rename atomicity is only
      // guaranteed within a single device). UUID suffix avoids collision when two writers race the §13 lock acquire
      // (defensive — single-writer is the contract, but a stray crashed temp file shouldn't poison the next run).
      val temp = parent / s".${target.last}.tmp.${UUID.randomUUID()}"
      val bytes = write(feature, indent = 2).getBytes(StandardCharsets.UTF_8)
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
        // Fsync the parent directory so the rename's directory entry is durable. On filesystems that flush data and
        // metadata independently (ext4 default, APFS), the file contents from the temp write survive a crash via
        // `SYNC` above, but the rename's directory entry can be lost without this step. Best-effort: opening a
        // directory as a `FileChannel` works on macOS/Linux; on platforms where it doesn't, swallow the failure rather
        // than failing an otherwise-successful save (the file is still in place; only durability across a crash is
        // weaker on that platform).
        fsyncDirectory(parent)
        ()
      catch
        case t: Throwable =>
          // Best-effort cleanup of the stray temp file; rethrow so the IO carries the actual cause.
          try
            if os.exists(temp) then
              val _ = os.remove(temp)
              ()
          catch case _: Throwable => ()
          throw t
    }

  override def verifyAgainstLog(
      featureId: FeatureId,
      manifestStore: ManifestStore,
      log: ActionLog
  ): IO[Either[RebuildError, VerifyResult]] =
    loadOrUnreadable(featureId).flatMap { loaded =>
      RebuildState.run(featureId, paths, manifestStore, log, this).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(rebuilt) =>
          loaded match
            case Right(Some(cached)) if cached == rebuilt =>
              // Cache matched the rebuild — the `RebuildState.run` re-save was a no-op overwrite. Don't append a
              // cache_invalidated marker for the no-op case.
              IO.pure(Right(VerifyResult.Consistent(rebuilt)))
            case Right(Some(_)) =>
              log
                .append(featureId, cacheInvalidatedDraft(featureId, "cache_diverged", detail = None))
                .as(Right(VerifyResult.Rewritten(rebuilt)))
            case Right(None) =>
              log
                .append(featureId, cacheInvalidatedDraft(featureId, "cache_missing", detail = None))
                .as(Right(VerifyResult.Rewritten(rebuilt)))
            case Left(detail) =>
              log
                .append(featureId, cacheInvalidatedDraft(featureId, "cache_unreadable", detail = Some(detail)))
                .as(Right(VerifyResult.Rewritten(rebuilt)))
      }
    }

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  /** Three-state cache read used by `load` and `verifyAgainstLog`:
    *
    *   - `Right(Some(feature))` — file present and decoded.
    *   - `Right(None)` — file absent.
    *   - `Left(detail)` — file present but uPickle decode threw (malformed, truncated, partial flush). The §4 invariant
    *     says the cache is rebuildable from log + manifest, so this case must not poison the caller; both public `load`
    *     (returns `None`) and `verifyAgainstLog` (appends `cache_unreadable` and rewrites) tolerate it.
    */
  private def loadOrUnreadable(featureId: FeatureId): IO[Either[String, Option[Feature]]] =
    IO.blocking {
      val file = paths.stateFile(featureId)
      if !os.exists(file) then Right(None)
      else
        try Right(Some(read[Feature](os.read(file))))
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

  private def cacheInvalidatedDraft(featureId: FeatureId, reason: String, detail: Option[String]): ActionDraft =
    val baseObj = ujson.Obj("reason" -> ujson.Str(reason))
    detail.foreach(d => baseObj("detail") = ujson.Str(d))
    ActionDraft(
      feature = featureId,
      piece = None,
      actor = None,
      role = None,
      kind = "harness.cache_invalidated",
      payload = baseObj
    )
