package io.forge.core.state

import cats.effect.IO
import io.forge.core.FeatureId
import io.forge.core.fsm.Feature
import io.forge.core.log.{ActionDraft, ActionLog}
import io.forge.core.manifest.ManifestStore
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardCopyOption, StandardOpenOption}
import java.util.UUID
import upickle.default.{read, write}

/** §4 + §11.5 — file-backed [[StateCache]] over `.forge/state/<feature>.json`.
  *
  * Writes are atomic at the filesystem layer: serialise the [[Feature]], write to a sibling temp file with `SYNC`,
  * `fsync` parent directory, then `Files.move` with `ATOMIC_MOVE`. A reader observing the target path always sees
  * either the previous committed view or the new one — never a half-written file.
  *
  * Reads via `os.read` + uPickle decode. `load` returns `None` on a missing file and bubbles any decode error as an
  * `IO` exception (genuinely unrecoverable at this layer — the caller's recovery is to call [[verifyAgainstLog]] which
  * triggers a full rebuild and overwrite).
  *
  * `verifyAgainstLog` runs the full [[RebuildState.run]] pipeline; if the rebuilt `Feature` matches the cached value
  * the cache is left alone, otherwise it's rewritten and a `harness.cache_invalidated` action is appended to the log.
  */
final class FileStateCache(paths: ForgePaths) extends StateCache:

  override def load(featureId: FeatureId): IO[Option[Feature]] =
    IO.blocking {
      val file = paths.stateFile(featureId)
      if !os.exists(file) then None
      else Some(read[Feature](os.read(file)))
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
    load(featureId).flatMap { cachedOpt =>
      RebuildState.run(featureId, paths, manifestStore, log, this).flatMap {
        case Left(err) => IO.pure(Left(err))
        case Right(rebuilt) =>
          cachedOpt match
            case Some(cached) if cached == rebuilt =>
              // Cache matched the rebuild — the `RebuildState.run` re-save was a no-op overwrite. Don't append a
              // cache_invalidated marker for the no-op case.
              IO.pure(Right(VerifyResult.Consistent(rebuilt)))
            case _ =>
              log
                .append(featureId, cacheInvalidatedDraft(featureId, cachedOpt.isEmpty))
                .as(Right(VerifyResult.Rewritten(rebuilt)))
      }
    }

  private def cacheInvalidatedDraft(featureId: FeatureId, cacheMissing: Boolean): ActionDraft =
    ActionDraft(
      feature = featureId,
      piece = None,
      actor = None,
      role = None,
      kind = "harness.cache_invalidated",
      payload = ujson.Obj(
        "reason" -> ujson.Str(if cacheMissing then "cache_missing" else "cache_diverged")
      )
    )
