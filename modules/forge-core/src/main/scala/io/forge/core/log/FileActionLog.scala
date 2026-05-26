package io.forge.core.log

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Mutex
import io.forge.core.FeatureId
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant
import upickle.default.{read, write}

/** §4 / §19 — file-backed [[ActionLog]] over `.forge/log/<feature>.jsonl`.
  *
  * Writes are one OS-level call per `append` / per batch in `appendAll`: a single `Files.write(path, bytes, CREATE,
  * APPEND, SYNC)`. Append + SYNC is the durability floor; per the design-2.2 carry-forward S2-3, if Slice 4 surfaces a
  * perf cliff, the trade-off graduates to per-batch `force()`.
  *
  * Per-feature serialisation is via a `Mutex[IO]` held under the §13 single-writer process lock; the `nextSeq` counter
  * is a `Ref[IO, Option[Long]]` warmed lazily on the first operation from `replay`. `replay` itself runs under the same
  * mutex so a partial-line recovery never interleaves with a concurrent caller.
  *
  * **Replay's repair contract** (matches design-2.2 §1.4 D5):
  *
  *   1. Parse the on-disk file line-by-line until either EOF or a non-newline-terminated trailing fragment. 2. If a
  *      trailing fragment is present, physically truncate the file to the last `\n` boundary on disk **before** writing
  *      the recovery entry, so the on-disk prefix is always valid NDJSON. 3. Render a `harness.error { kind:
  *      "log_truncated", droppedBytes }` action with `seq = survivors.lastOption.map(_.seq + 1).getOrElse(0)` and `at =
  *      IO.realTimeInstant`, then write it via the private no-replay path (so `replay` doesn't recurse). 4. Return
  *      survivors + recovery entry — exactly what subsequent consumers see on disk.
  *
  * `Feature.foldEvents` (PR-D D4) treats the recovery entry as a no-op projection.
  *
  * Higher-level cross-cutting reconciliation (missing `audit.piece_merged` after an `fsm.transition`, etc.) is
  * `RebuildState.reconcile`'s job in PR-E E4, not this layer.
  */
object FileActionLog:
  private[log] final case class FeatureLogState(
      mutex: Mutex[IO],
      nextSeqRef: Ref[IO, Option[Long]]
  )

  private[log] final case class ParseResult(
      survivors: Vector[Action],
      keepBytes: Long,
      droppedBytes: Int
  )

  /** Build a `FileActionLog` over `paths`. Per-feature in-memory state is created lazily on first use. */
  def apply(paths: ForgePaths): IO[FileActionLog] =
    Ref.of[IO, Map[FeatureId, FeatureLogState]](Map.empty).map(state => new FileActionLog(paths, state))

final class FileActionLog private (
    paths: ForgePaths,
    state: Ref[IO, Map[FeatureId, FileActionLog.FeatureLogState]]
) extends ActionLog:

  override def append(featureId: FeatureId, draft: ActionDraft): IO[Action] =
    withWarmedState(featureId) { fs =>
      for
        seqOpt <- fs.nextSeqRef.get
        seq = seqOpt.getOrElse(throw new IllegalStateException("FileActionLog: nextSeq not warmed"))
        at <- IO.realTimeInstant
        action = draft.stamp(seq, at)
        _ <- unsafeAppendStamped(featureId, Vector(action))
        _ <- fs.nextSeqRef.set(Some(seq + 1))
      yield action
    }

  override def appendAll(featureId: FeatureId, drafts: Vector[ActionDraft]): IO[Vector[Action]] =
    if drafts.isEmpty then IO.pure(Vector.empty)
    else
      withWarmedState(featureId) { fs =>
        for
          seqOpt <- fs.nextSeqRef.get
          startSeq = seqOpt.getOrElse(throw new IllegalStateException("FileActionLog: nextSeq not warmed"))
          at <- IO.realTimeInstant
          // Single rendered-batch write: all drafts share the wall-clock `at` (the conceptually atomic FSM transition
          // captured them together) and successive `seq` values from startSeq onward.
          stamped = drafts.zipWithIndex.map { case (d, i) => d.stamp(startSeq + i.toLong, at) }
          _ <- unsafeAppendStamped(featureId, stamped)
          _ <- fs.nextSeqRef.set(Some(startSeq + drafts.size.toLong))
        yield stamped
      }

  override def replay(featureId: FeatureId): IO[Vector[Action]] =
    getOrCreate(featureId).flatMap { fs =>
      fs.mutex.lock.surround(doReplay(featureId, fs))
    }

  override def nextSeq(featureId: FeatureId): IO[Long] =
    withWarmedState(featureId) { fs =>
      fs.nextSeqRef.get.map(
        _.getOrElse(throw new IllegalStateException("FileActionLog: nextSeq not warmed"))
      )
    }

  // ---------------------------------------------------------------------------
  // Internals — per-feature state, warm-up, replay-with-repair, raw write.
  // ---------------------------------------------------------------------------

  private def getOrCreate(featureId: FeatureId): IO[FileActionLog.FeatureLogState] =
    state.get.flatMap { current =>
      current.get(featureId) match
        case Some(existing) => IO.pure(existing)
        case None =>
          for
            mutex <- Mutex[IO]
            nextSeqRef <- Ref.of[IO, Option[Long]](None)
            fresh = FileActionLog.FeatureLogState(mutex, nextSeqRef)
            chosen <- state.modify { m =>
              m.get(featureId) match
                case Some(existing) => (m, existing)
                case None => (m.updated(featureId, fresh), fresh)
            }
          yield chosen
    }

  private def withWarmedState[A](
      featureId: FeatureId
  )(f: FileActionLog.FeatureLogState => IO[A]): IO[A] =
    getOrCreate(featureId).flatMap { fs =>
      fs.mutex.lock.surround(ensureWarmed(featureId, fs) *> f(fs))
    }

  private def ensureWarmed(featureId: FeatureId, fs: FileActionLog.FeatureLogState): IO[Unit] =
    fs.nextSeqRef.get.flatMap {
      case Some(_) => IO.unit
      case None => doReplay(featureId, fs).void
    }

  /** Replay implementation; assumes the per-feature mutex is held. Reads the on-disk file, repairs a partial trailing
    * line if present, and primes `nextSeqRef`. Returns the durable post-repair view (survivors + recovery entry, or
    * just survivors on a clean file).
    */
  private def doReplay(
      featureId: FeatureId,
      fs: FileActionLog.FeatureLogState
  ): IO[Vector[Action]] =
    IO.blocking {
      val file = paths.featureLog(featureId)
      if !os.exists(file) then FileActionLog.ParseResult(Vector.empty, 0L, 0)
      else parseAndDetectPartial(os.read.bytes(file))
    }.flatMap { result =>
      val survivors = result.survivors
      val survivorNextSeq = survivors.lastOption.map(_.seq + 1L).getOrElse(0L)
      if result.droppedBytes == 0 then fs.nextSeqRef.set(Some(survivorNextSeq)).as(survivors)
      else
        // Repair path: physically truncate to the last newline boundary, then write a single recovery entry via the
        // no-replay append. nextSeq is set to recoverySeq + 1 so subsequent appends extend a valid file.
        for
          _ <- truncateTo(paths.featureLog(featureId), result.keepBytes)
          at <- IO.realTimeInstant
          recovery = recoveryEntry(featureId, survivorNextSeq, at, result.droppedBytes)
          _ <- unsafeAppendStamped(featureId, Vector(recovery))
          _ <- fs.nextSeqRef.set(Some(survivorNextSeq + 1L))
        yield survivors :+ recovery
    }

  private def recoveryEntry(
      featureId: FeatureId,
      seq: Long,
      at: Instant,
      droppedBytes: Int
  ): Action =
    Action(
      seq = seq,
      at = at,
      feature = featureId,
      piece = None,
      actor = None,
      role = None,
      kind = "harness.error",
      payload = ujson.Obj(
        "kind" -> ujson.Str("log_truncated"),
        "droppedBytes" -> ujson.Num(droppedBytes.toDouble)
      )
    )

  private def parseAndDetectPartial(bytes: Array[Byte]): FileActionLog.ParseResult =
    val total = bytes.length
    if total == 0 then FileActionLog.ParseResult(Vector.empty, 0L, 0)
    else
      val lastNewline = lastIndexOfByte(bytes, '\n'.toByte)
      val (keepBytes, droppedBytes) =
        if lastNewline == total - 1 then (total, 0)
        else
          val keep = if lastNewline < 0 then 0 else lastNewline + 1
          (keep, total - keep)
      val cleanText = new String(bytes, 0, keepBytes, StandardCharsets.UTF_8)
      val lines = cleanText.split('\n').filter(_.nonEmpty)
      val actions = lines.toVector.map(line => read[Action](line))
      FileActionLog.ParseResult(actions, keepBytes.toLong, droppedBytes)

  private def lastIndexOfByte(bytes: Array[Byte], target: Byte): Int =
    var i = bytes.length - 1
    var found = -1
    while i >= 0 && found < 0 do
      if bytes(i) == target then found = i
      i -= 1
    found

  private def truncateTo(file: os.Path, keepBytes: Long): IO[Unit] =
    IO.blocking {
      val ch = FileChannel.open(file.toNIO, StandardOpenOption.WRITE)
      try
        val _ = ch.truncate(keepBytes)
        ch.force(true)
      finally ch.close()
    }

  /** Write a batch of fully-stamped `Action`s as NDJSON lines, one OS-level write call. Public callers reach this via
    * `append` / `appendAll`; `replay` uses it for its recovery entry. Not part of the `ActionLog` trait.
    */
  private[log] def unsafeAppendStamped(featureId: FeatureId, actions: Vector[Action]): IO[Unit] =
    if actions.isEmpty then IO.unit
    else
      IO.blocking {
        val file = paths.featureLog(featureId)
        val parent = file / os.up
        if !os.exists(parent) then os.makeDir.all(parent)
        val sb = new StringBuilder
        actions.foreach { a =>
          sb.append(write(a))
          sb.append('\n')
        }
        val bytes = sb.toString.getBytes(StandardCharsets.UTF_8)
        val _ = Files.write(
          file.toNIO,
          bytes,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.SYNC
        )
        ()
      }
