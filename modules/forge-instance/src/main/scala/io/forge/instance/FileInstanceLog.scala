package io.forge.instance

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.effect.std.Mutex

import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.{Files, StandardOpenOption}
import java.time.Instant
import upickle.default.{read, write}

/** Phase-4 §6.4 / O8 — file-backed append-only **instance action log** over `instances/<name>/log/instance.jsonl`
  * (Slice 4.1, Task 4.1.2). The instance-scoped analogue of `io.forge.core.log.FileActionLog`, lifted one level up:
  * append-only NDJSON, `CREATE|APPEND|SYNC` durability floor, and the same partial-trailing-line repair contract.
  *
  * **One log, one writer.** The per-feature `FileActionLog` keeps a `Map[FeatureId, …]` of per-feature mutexes because
  * many features share one process; the instance log is written by the **daemon alone** (single-writer, contract
  * §6.3.1), so there is exactly one stream and one `Mutex[IO]` guarding it — no per-key fan-out. `nextSeq` is a single
  * `Ref[IO, Option[Long]]` warmed lazily from `replay` on first use.
  *
  * **Replay's repair contract** (identical to `FileActionLog`'s D5):
  *
  *   1. Parse the on-disk file line-by-line until EOF or a non-newline-terminated trailing fragment. 2. If a trailing
  *      fragment is present, physically truncate the file to the last `\n` boundary on disk **before** writing the
  *      recovery entry, so the on-disk prefix is always valid NDJSON. 3. Append a single `harness.error { kind:
  *      "log_truncated", droppedBytes }` record (an open-`kind` log-level marker — [[RebuildInstanceState]] skips it as
  *      an unrecognised `kind`, never folding it into [[InstanceState]]) at `seq = survivors.lastSeq + 1`. 4. Return
  *      survivors + recovery entry — exactly the post-repair on-disk view.
  */
object FileInstanceLog:

  /** The log-level `kind` of the truncation-recovery marker (mirrors `FileActionLog`'s `harness.error`). Not part of
    * the [[InstanceEvent]] alphabet — [[RebuildInstanceState]] treats it as a no-op via the unknown-`kind` skip.
    */
  val TruncationRecoveryKind: String = "harness.error"

  private final case class ParseResult(
      survivors: Vector[InstanceLogRecord],
      keepBytes: Long,
      droppedBytes: Int
  )

  /** Build a `FileInstanceLog` over `instance`'s log file. In-memory `nextSeq` is warmed lazily on first use. */
  def apply(instance: Instance): IO[FileInstanceLog] =
    for
      mutex <- Mutex[IO]
      nextSeqRef <- Ref.of[IO, Option[Long]](None)
    yield new FileInstanceLog(instance.instanceLog, mutex, nextSeqRef)

final class FileInstanceLog private (
    logFile: os.Path,
    mutex: Mutex[IO],
    nextSeqRef: Ref[IO, Option[Long]]
):

  /** Append one event, stamping it with the next `seq` and a write-time `at`. */
  def append(event: InstanceEvent): IO[InstanceLogRecord] =
    appendAll(Vector(event)).map(_.head)

  /** Append a batch of events as one OS-level write. All records share the write-time `at` (one conceptually atomic
    * batch) and successive `seq` values from the current `nextSeq` onward. Empty input is a no-op.
    */
  def appendAll(events: Vector[InstanceEvent]): IO[Vector[InstanceLogRecord]] =
    if events.isEmpty then IO.pure(Vector.empty)
    else
      mutex.lock.surround {
        ensureWarmed *>
          (for
            startSeq <- currentSeq
            at <- IO.realTimeInstant
            stamped = events.zipWithIndex.map { case (e, i) => InstanceEvent.toDraft(e).stamp(startSeq + i.toLong, at) }
            _ <- unsafeAppendStamped(stamped)
            _ <- nextSeqRef.set(Some(startSeq + events.size.toLong))
          yield stamped)
      }

  /** Replay the durable log (with partial-trailing-line repair) and prime `nextSeq`. Returns the post-repair view. */
  def replay: IO[Vector[InstanceLogRecord]] =
    mutex.lock.surround(doReplay)

  /** The next `seq` an append would assign (warms from `replay` on first call). */
  def nextSeq: IO[Long] =
    mutex.lock.surround(ensureWarmed *> currentSeq)

  // ---------------------------------------------------------------------------
  // Internals — warm-up, replay-with-repair, raw write.
  // ---------------------------------------------------------------------------

  private def currentSeq: IO[Long] =
    nextSeqRef.get.map(_.getOrElse(throw new IllegalStateException("FileInstanceLog: nextSeq not warmed")))

  private def ensureWarmed: IO[Unit] =
    nextSeqRef.get.flatMap {
      case Some(_) => IO.unit
      case None => doReplay.void
    }

  /** Replay implementation; assumes the mutex is held. Reads the on-disk file, repairs a partial trailing line if
    * present, and primes `nextSeqRef`.
    */
  private def doReplay: IO[Vector[InstanceLogRecord]] =
    IO.blocking {
      if !os.exists(logFile) then FileInstanceLog.ParseResult(Vector.empty, 0L, 0)
      else parseAndDetectPartial(os.read.bytes(logFile))
    }.flatMap { result =>
      val survivors = result.survivors
      val survivorNextSeq = survivors.lastOption.map(_.seq + 1L).getOrElse(0L)
      if result.droppedBytes == 0 then nextSeqRef.set(Some(survivorNextSeq)).as(survivors)
      else
        for
          _ <- truncateTo(result.keepBytes)
          at <- IO.realTimeInstant
          recovery = recoveryEntry(survivorNextSeq, at, result.droppedBytes)
          _ <- unsafeAppendStamped(Vector(recovery))
          _ <- nextSeqRef.set(Some(survivorNextSeq + 1L))
        yield survivors :+ recovery
    }

  private def recoveryEntry(seq: Long, at: Instant, droppedBytes: Int): InstanceLogRecord =
    InstanceLogRecord(
      seq = seq,
      at = at,
      kind = FileInstanceLog.TruncationRecoveryKind,
      payload = ujson.Obj(
        "kind" -> ujson.Str("log_truncated"),
        "droppedBytes" -> ujson.Num(droppedBytes.toDouble)
      )
    )

  private def parseAndDetectPartial(bytes: Array[Byte]): FileInstanceLog.ParseResult =
    val total = bytes.length
    if total == 0 then FileInstanceLog.ParseResult(Vector.empty, 0L, 0)
    else
      val lastNewline = lastIndexOfByte(bytes, '\n'.toByte)
      val (keepBytes, droppedBytes) =
        if lastNewline == total - 1 then (total, 0)
        else
          val keep = if lastNewline < 0 then 0 else lastNewline + 1
          (keep, total - keep)
      val cleanText = new String(bytes, 0, keepBytes, StandardCharsets.UTF_8)
      val lines = cleanText.split('\n').filter(_.nonEmpty)
      val records = lines.toVector.map(line => read[InstanceLogRecord](line))
      FileInstanceLog.ParseResult(records, keepBytes.toLong, droppedBytes)

  private def lastIndexOfByte(bytes: Array[Byte], target: Byte): Int =
    var i = bytes.length - 1
    var found = -1
    while i >= 0 && found < 0 do
      if bytes(i) == target then found = i
      i -= 1
    found

  private def truncateTo(keepBytes: Long): IO[Unit] =
    IO.blocking {
      val ch = FileChannel.open(logFile.toNIO, StandardOpenOption.WRITE)
      try
        val _ = ch.truncate(keepBytes)
        ch.force(true)
      finally ch.close()
    }

  /** Write a batch of fully-stamped records as NDJSON lines, one OS-level write call. Reached by `appendAll` and
    * `replay`'s recovery path.
    */
  private def unsafeAppendStamped(records: Vector[InstanceLogRecord]): IO[Unit] =
    if records.isEmpty then IO.unit
    else
      IO.blocking {
        val parent = logFile / os.up
        if !os.exists(parent) then os.makeDir.all(parent)
        val sb = new StringBuilder
        records.foreach { r =>
          sb.append(write(r))
          sb.append('\n')
        }
        val bytes = sb.toString.getBytes(StandardCharsets.UTF_8)
        val _ = Files.write(
          logFile.toNIO,
          bytes,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.SYNC
        )
        ()
      }
