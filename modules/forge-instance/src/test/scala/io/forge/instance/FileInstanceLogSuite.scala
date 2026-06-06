package io.forge.instance

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, InstanceName}

import java.nio.charset.StandardCharsets
import upickle.default.read

/** Task 4.1.2 — append/replay round-trip, monotonic seq, and the partial-trailing-line repair contract for the durable
  * instance log. Mirrors `FileActionLogSuite` (real temp dirs, no mocks) at the instance level.
  */
class FileInstanceLogSuite extends munit.FunSuite:

  private val Name: InstanceName = InstanceName("demo")
  private val feature = FeatureId("image-creds-dedup")

  private val tempHome = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-instance-log-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def buildLog(home: os.Path): FileInstanceLog =
    FileInstanceLog(Instance.at(Name, home)).unsafeRunSync()

  private def logFile(home: os.Path): os.Path = Instance.at(Name, home).instanceLog

  // --- append + replay round-trip -------------------------------------------

  tempHome.test("append then replay returns the same record with seq=0 and a real timestamp"): home =>
    val log = buildLog(home)
    val (written, replayed) =
      (for
        w <- log.append(InstanceEvent.DaemonStarted(123))
        r <- log.replay
      yield (w, r)).unsafeRunSync()
    assertEquals(written.seq, 0L)
    assertEquals(written.kind, InstanceEvent.DaemonStartedKind)
    assertEquals(replayed, Vector(written))
    assertEquals(written.event, Some(InstanceEvent.DaemonStarted(123)))

  tempHome.test("appendAll preserves order and stamps successive seq values from the same `at`"): home =>
    val log = buildLog(home)
    val events = Vector(
      InstanceEvent.DaemonStarted(1),
      InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature),
      InstanceEvent.WorkerStatus("w1", "PieceImplementing")
    )
    val stamped = log.appendAll(events).unsafeRunSync()
    assertEquals(stamped.map(_.seq), Vector(0L, 1L, 2L))
    val sharedAt = stamped.head.at
    assert(stamped.forall(_.at == sharedAt), "appendAll should stamp every record with the same `at`")
    assertEquals(log.replay.unsafeRunSync(), stamped)
    assertEquals(stamped.flatMap(_.event), events)

  tempHome.test("nextSeq is monotonic across separate append calls"): home =>
    val log = buildLog(home)
    val seqs =
      (for
        a <- log.append(InstanceEvent.DaemonStarted(1))
        b <- log.append(InstanceEvent.WorkerRegistered("w1", "/r", feature))
        c <- log.append(InstanceEvent.WorkerStatus("w1", "x"))
        n <- log.nextSeq
      yield (a.seq, b.seq, c.seq, n)).unsafeRunSync()
    assertEquals(seqs, (0L, 1L, 2L, 3L))

  tempHome.test("appendAll with empty input is a no-op"): home =>
    val log = buildLog(home)
    assertEquals(log.appendAll(Vector.empty).unsafeRunSync(), Vector.empty[InstanceLogRecord])

  tempHome.test("replay on a missing file returns the empty vector and seeds nextSeq = 0"): home =>
    val log = buildLog(home)
    val (replayed, n) = (for
      r <- log.replay
      n <- log.nextSeq
    yield (r, n)).unsafeRunSync()
    assertEquals(replayed, Vector.empty[InstanceLogRecord])
    assertEquals(n, 0L)

  // --- replay-repair contract -----------------------------------------------

  tempHome.test("replay truncates a partially-flushed last line and appends a harness.error log_truncated entry"):
    home =>
      val log = buildLog(home)
      val seeded = log
        .appendAll(
          Vector(InstanceEvent.DaemonStarted(1), InstanceEvent.WorkerRegistered("w1", "/r", feature))
        )
        .unsafeRunSync()

      val file = logFile(home)
      val partial = """{"seq":2,"ts":"2026-06-06T12:00:00Z","kind":"worker.stat"""
      os.write.append(file, partial.getBytes(StandardCharsets.UTF_8))
      val droppedBytes = partial.length

      // Fresh instance so in-memory nextSeq rebuilds from disk (the post-crash boot path).
      val freshLog = buildLog(home)
      val replayed = freshLog.replay.unsafeRunSync()

      assertEquals(replayed.size, 3)
      assertEquals(replayed.take(2), seeded)
      val recovery = replayed.last
      assertEquals(recovery.kind, FileInstanceLog.TruncationRecoveryKind)
      assertEquals(recovery.payload("kind").str, "log_truncated")
      assertEquals(recovery.payload("droppedBytes").num.toInt, droppedBytes)
      assertEquals(recovery.seq, 2L)
      assertEquals(recovery.event, None) // skipped by the fold

      val onDiskText = new String(os.read.bytes(file), StandardCharsets.UTF_8)
      assert(onDiskText.endsWith("\n"), s"file should end with newline, got tail: ${onDiskText.takeRight(40)}")
      val onDiskLines = onDiskText.split('\n').filter(_.nonEmpty)
      assertEquals(onDiskLines.length, 3)
      assertEquals(read[InstanceLogRecord](onDiskLines.last), recovery)

      // Subsequent append continues seq monotonically past the recovery entry.
      val followUp = freshLog.append(InstanceEvent.WorkerStatus("w1", "done")).unsafeRunSync()
      assertEquals(followUp.seq, 3L)

  tempHome.test("replay-repair on a file with no newline at all seeds seq=0 and recovers"): home =>
    val log = buildLog(home)
    val file = logFile(home)
    os.makeDir.all(file / os.up)
    val partial = "{\"seq\":0,\"ts\":\"badly-flushed"
    os.write(file, partial.getBytes(StandardCharsets.UTF_8))

    val replayed = log.replay.unsafeRunSync()
    assertEquals(replayed.size, 1)
    val recovery = replayed.head
    assertEquals(recovery.seq, 0L)
    assertEquals(recovery.kind, FileInstanceLog.TruncationRecoveryKind)
    assertEquals(recovery.payload("droppedBytes").num.toInt, partial.length)
    assertEquals(new String(os.read.bytes(file), StandardCharsets.UTF_8).split('\n').filter(_.nonEmpty).length, 1)

  tempHome.test("replay's recovery entry survives a second replay (idempotent)"): home =>
    val log = buildLog(home)
    log.append(InstanceEvent.DaemonStarted(1)).unsafeRunSync()
    os.write.append(logFile(home), "{partial".getBytes(StandardCharsets.UTF_8))

    val first = log.replay.unsafeRunSync()
    assertEquals(first.size, 2)
    val second = log.replay.unsafeRunSync()
    assertEquals(second, first)

  // --- NDJSON wire shape -----------------------------------------------------

  tempHome.test("each on-disk line uses the instance-scoped {seq, ts, kind, payload} wire shape"): home =>
    val log = buildLog(home)
    log.append(InstanceEvent.WorkerRegistered("w1", "/repos/szork", feature)).unsafeRunSync()
    val text = new String(os.read.bytes(logFile(home)), StandardCharsets.UTF_8).trim
    val json = ujson.read(text)
    assertEquals(json("seq").num.toLong, 0L)
    assert(json.obj.contains("ts"), "wire form must use 'ts' not 'at'")
    assertEquals(json("kind").str, InstanceEvent.WorkerRegisteredKind)
    assertEquals(json("payload")("workerId").str, "w1")
    assertEquals(json("payload")("feature").str, feature.value)
    assert(!json.obj.contains("feature"), "instance record has no top-level feature/piece/actor/role")
