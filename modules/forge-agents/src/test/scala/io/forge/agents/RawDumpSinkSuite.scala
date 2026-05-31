package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*

class RawDumpSinkSuite extends munit.FunSuite:

  test("driver returns None when FORGE_DRIVER_RAW_DUMP_DIR is unset (no overhead, no file written)"):
    // The test process does not set the env var, so the env-gated path must return None. This is the steady-state
    // shape: default off, the connector wires a None sink, the parse pipeline runs without a tap.
    assert(sys.env.get(RawDumpSink.DriverEnvVar).isEmpty, "test must run without FORGE_DRIVER_RAW_DUMP_DIR set")
    val sink = RawDumpSink.driver("claude", "implement").unsafeRunSync()
    assertEquals(sink, None)

  test("sinkTo appends every non-blank line to a single per-session .jsonl file"):
    val dir = os.temp.dir(prefix = "raw-dump-")
    try
      val lines = Vector(
        """{"type":"system","subtype":"init","session_id":"sid-1"}""",
        """{"type":"assistant","message":{"content":[{"type":"text","text":"hi"}]}}""",
        "", // blank line (fs2 trailing EOF segment) — must be skipped
        """{"type":"result","subtype":"success","is_error":false}"""
      )
      val sink = RawDumpSink.sinkTo(dir, "claude", "implement").unsafeRunSync()
      lines.traverse_(sink).unsafeRunSync()

      val files = os.list(dir).filter(_.last.endsWith(".jsonl"))
      assertEquals(files.size, 1, clue = files)
      val file = files.head
      // Filename keys the connector + phase label; the UUID disambiguates sessions.
      assert(file.last.startsWith("claude-implement-"), clue = file.last)

      val written = os.read.lines(file)
      // The blank line was skipped; the three real envelopes are present in order.
      assertEquals(written.size, 3, clue = written)
      assertEquals(written.head, lines.head)
      assertEquals(written.last, lines.last)
    finally os.remove.all(dir)

  test("sinkTo mints a distinct file per call (one file per session)"):
    val dir = os.temp.dir(prefix = "raw-dump-")
    try
      val sinkA = RawDumpSink.sinkTo(dir, "codex", "spec").unsafeRunSync()
      val sinkB = RawDumpSink.sinkTo(dir, "codex", "spec").unsafeRunSync()
      (sinkA("a-line") *> sinkB("b-line")).unsafeRunSync()
      val files = os.list(dir).filter(_.last.endsWith(".jsonl"))
      assertEquals(files.size, 2, clue = files)
      assert(files.forall(_.last.startsWith("codex-spec-")), clue = files.map(_.last))
    finally os.remove.all(dir)
