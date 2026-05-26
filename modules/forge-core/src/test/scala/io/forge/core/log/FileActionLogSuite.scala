package io.forge.core.log

import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId}
import io.forge.core.paths.ForgePaths

import java.nio.charset.StandardCharsets
import upickle.default.read

/** PR-D D5 — file-level append+replay round-trip, monotonic seq across appends, and the replay-repair contract
  * (truncate trailing partial line + emit `harness.error log_truncated` recovery entry).
  *
  * Uses real temp directories under `os.temp.dir()`; no mocks. Each test calls `unsafeRunSync()` on the IO so munit's
  * `FunSuite` flow stays linear.
  */
class FileActionLogSuite extends munit.FunSuite:

  private val featureId = FeatureId("stripe-webhook")

  private val tempFixture = FunFixture[os.Path](
    setup = _ =>
      val dir = os.temp.dir(prefix = "forge-action-log-")
      // ForgePaths expects a repo root; the log file lands under <root>/.forge/log/.
      dir
    ,
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def buildLog(root: os.Path): FileActionLog =
    FileActionLog(new ForgePaths(repoRoot = root)).unsafeRunSync()

  private def draft(kind: String, payload: ujson.Value, piece: Option[PieceId] = None): ActionDraft =
    ActionDraft(
      feature = featureId,
      piece = piece,
      actor = Some("claude"),
      role = Some("driver"),
      kind = kind,
      payload = payload
    )

  // --- D5: append + replay round-trip ---

  tempFixture.test("append then replay returns the same action with seq=0 and a real timestamp"): root =>
    val log = buildLog(root)
    val (written, replayed) =
      (for
        w <- log.append(featureId, draft("user.command", ujson.Obj("cmd" -> "new")))
        r <- log.replay(featureId)
      yield (w, r)).unsafeRunSync()
    assertEquals(written.seq, 0L)
    assertEquals(replayed, Vector(written))

  tempFixture.test("appendAll preserves order and stamps successive seq values from the same `at`"): root =>
    val log = buildLog(root)
    val drafts = (0 until 4).toVector.map(i => draft("user.command", ujson.Obj("i" -> ujson.Num(i.toDouble))))
    val stamped = log.appendAll(featureId, drafts).unsafeRunSync()
    assertEquals(stamped.size, 4)
    assertEquals(stamped.map(_.seq), Vector(0L, 1L, 2L, 3L))
    val sharedAt = stamped.head.at
    assert(stamped.forall(_.at == sharedAt), "appendAll should stamp every action with the same `at`")
    val replayed = log.replay(featureId).unsafeRunSync()
    assertEquals(replayed, stamped)

  tempFixture.test("nextSeq is monotonic across separate append calls"): root =>
    val log = buildLog(root)
    val seqs =
      (for
        a <- log.append(featureId, draft("user.command", ujson.Obj("i" -> ujson.Num(0))))
        b <- log.append(featureId, draft("user.command", ujson.Obj("i" -> ujson.Num(1))))
        c <- log.append(featureId, draft("user.command", ujson.Obj("i" -> ujson.Num(2))))
        n <- log.nextSeq(featureId)
      yield (a.seq, b.seq, c.seq, n)).unsafeRunSync()
    assertEquals(seqs, (0L, 1L, 2L, 3L))

  tempFixture.test("appendAll with empty input is a no-op"): root =>
    val log = buildLog(root)
    val result = log.appendAll(featureId, Vector.empty).unsafeRunSync()
    assertEquals(result, Vector.empty[Action])

  tempFixture.test("replay on a missing file returns the empty vector and seeds nextSeq = 0"): root =>
    val log = buildLog(root)
    val (replayed, n) =
      (for
        r <- log.replay(featureId)
        n <- log.nextSeq(featureId)
      yield (r, n)).unsafeRunSync()
    assertEquals(replayed, Vector.empty[Action])
    assertEquals(n, 0L)

  // --- D5: replay-repair contract ---

  tempFixture.test("replay truncates a partially-flushed last line and appends a harness.error log_truncated entry"):
    root =>
      val log = buildLog(root)
      val paths = new ForgePaths(repoRoot = root)

      // Seed two clean lines via appendAll.
      val seedDrafts = Vector(
        draft("user.command", ujson.Obj("i" -> ujson.Num(0))),
        draft("user.command", ujson.Obj("i" -> ujson.Num(1)))
      )
      val seeded = log.appendAll(featureId, seedDrafts).unsafeRunSync()

      // Inject a partial trailing line directly on disk (simulates a crash mid-write).
      val logFile = paths.featureLog(featureId)
      val partial = """{"seq":2,"ts":"2026-05-26T12:00:00Z","feature":"stripe-webhook","piec"""
      os.write.append(logFile, partial.getBytes(StandardCharsets.UTF_8))
      val droppedBytes = partial.length

      // A fresh FileActionLog instance ensures the in-memory state is rebuilt from disk (matches the post-crash boot
      // path — the prior instance's nextSeqRef does not leak across).
      val freshLog = buildLog(root)
      val replayed = freshLog.replay(featureId).unsafeRunSync()

      // (a) Returned vector excludes the garbage AND includes the recovery entry.
      assertEquals(replayed.size, 3)
      assertEquals(replayed.take(2), seeded)
      val recovery = replayed.last
      assertEquals(recovery.kind, "harness.error")
      assertEquals(recovery.payload("kind").str, "log_truncated")
      assertEquals(recovery.payload("droppedBytes").num.toInt, droppedBytes)
      assertEquals(recovery.seq, 2L)

      // (b) On-disk file is truncated to a clean NDJSON prefix with the recovery entry appended.
      val onDiskBytes = os.read.bytes(logFile)
      val onDiskText = new String(onDiskBytes, StandardCharsets.UTF_8)
      assert(onDiskText.endsWith("\n"), s"file should end with newline, got tail: ${onDiskText.takeRight(40)}")
      val onDiskLines = onDiskText.split('\n').filter(_.nonEmpty)
      assertEquals(onDiskLines.length, 3)
      val onDiskRecovery = read[Action](onDiskLines.last)
      assertEquals(onDiskRecovery, recovery)

      // (c) Subsequent appendAll continues seq monotonically past the recovery entry.
      val followUp = freshLog
        .appendAll(featureId, Vector(draft("user.command", ujson.Obj("i" -> ujson.Num(3)))))
        .unsafeRunSync()
      assertEquals(followUp.head.seq, 3L)
      val finalView = freshLog.replay(featureId).unsafeRunSync()
      assertEquals(finalView.size, 4)
      assertEquals(finalView.map(_.seq), Vector(0L, 1L, 2L, 3L))

  tempFixture.test(
    "replay-repair on a file with no newline at all (degenerate single partial line) seeds seq=0 and recovers"
  ): root =>
    val log = buildLog(root)
    val paths = new ForgePaths(repoRoot = root)
    val logFile = paths.featureLog(featureId)
    // Manually create the directory + partial-only file.
    os.makeDir.all(logFile / os.up)
    val partial = "{\"seq\":0,\"ts\":\"badly-flushed"
    os.write(logFile, partial.getBytes(StandardCharsets.UTF_8))

    val replayed = log.replay(featureId).unsafeRunSync()
    assertEquals(replayed.size, 1)
    val recovery = replayed.head
    assertEquals(recovery.seq, 0L)
    assertEquals(recovery.kind, "harness.error")
    assertEquals(recovery.payload("kind").str, "log_truncated")
    assertEquals(recovery.payload("droppedBytes").num.toInt, partial.length)

    // The file should now contain exactly the recovery entry (the all-partial prefix was truncated to 0 bytes
    // before the recovery line was appended).
    val text = new String(os.read.bytes(logFile), StandardCharsets.UTF_8)
    assertEquals(text.split('\n').filter(_.nonEmpty).length, 1)

  tempFixture.test("replay's recovery entry survives a second replay call (idempotent)"): root =>
    val log = buildLog(root)
    val paths = new ForgePaths(repoRoot = root)
    // Seed + inject partial line, replay once.
    log.appendAll(featureId, Vector(draft("user.command", ujson.Obj("i" -> ujson.Num(0))))).unsafeRunSync()
    os.write.append(paths.featureLog(featureId), "{partial".getBytes(StandardCharsets.UTF_8))

    val first = log.replay(featureId).unsafeRunSync()
    assertEquals(first.size, 2)

    // A second replay should be a no-op — the file is now in a clean NDJSON state with the recovery entry committed.
    val second = log.replay(featureId).unsafeRunSync()
    assertEquals(second, first)

  // --- D5: NDJSON wire shape matches §19 ---

  tempFixture.test("each on-disk line matches the §19 wire shape (seq, ts, feature, kind, payload …)"): root =>
    val log = buildLog(root)
    val paths = new ForgePaths(repoRoot = root)
    val piece = PieceId("p1")
    log
      .append(
        featureId,
        draft("audit.piece_merged", ujson.Obj("p" -> "p1", "prNumber" -> 4291), piece = Some(piece))
      )
      .unsafeRunSync()
    val text = new String(os.read.bytes(paths.featureLog(featureId)), StandardCharsets.UTF_8).trim
    val json = ujson.read(text)
    assertEquals(json("seq").num.toLong, 0L)
    assert(json.obj.contains("ts"), "wire form must use 'ts' not 'at'")
    assertEquals(json("feature").str, featureId.value)
    assertEquals(json("piece").str, "p1")
    assertEquals(json("actor").str, "claude")
    assertEquals(json("role").str, "driver")
    assertEquals(json("kind").str, "audit.piece_merged")
    assertEquals(json("payload")("p").str, "p1")
