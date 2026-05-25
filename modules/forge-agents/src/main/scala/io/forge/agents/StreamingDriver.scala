package io.forge.agents

import cats.effect.IO
import cats.effect.kernel.{Deferred, Resource}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Channel

import scala.concurrent.duration.*

/** §7.1 — factory that composes a [[Subprocess]] + an event parser into a `StreamingSession`.
  *
  * The factory owns the subprocess lifecycle until the returned `IO[StreamingSession]` is consumed. It blocks until the
  * first `AgentEvent.Init` event arrives on stdout (so the trait's synchronous `sessionId: String` accessor is honest)
  * and only then returns the session. From there, the session's `close()` / `kill()` are responsible for shutting down
  * the subprocess and releasing the Resource.
  *
  * **Stderr is drained in a background fiber** so the OS pipe buffer can't fill up and stall the child (Slice 0 §2.2:
  * Codex leaks `"Reading additional input from stdin..."` on stderr even when the prompt is on argv). The drained lines
  * are appended to an in-memory buffer that the caller can inspect via [[StreamingSessionWithDiagnostics]]; a
  * production caller would pipe them into the action log instead.
  *
  * **Parse errors do not crash the stream.** A `Left` from the parser is logged into the same stderr buffer and the
  * line is dropped. Adapter bugs surface as missing-event symptoms downstream rather than as an exception that kills
  * the whole driver.
  */
object StreamingDriver:

  /** Failure reasons. Surface to the orchestrator as `NeedsHumanIntervention` reasons. */
  sealed trait Error extends Product with Serializable:
    def message: String
  object Error:
    /** The subprocess exited or stdout EOFed before any `Init` event arrived. */
    final case class NoInitBeforeExit(stderr: String) extends Error:
      def message: String = s"subprocess exited before first Init event (stderr tail: ${stderr.takeRight(500)})"

    /** `initTimeout` elapsed without an `Init` event. */
    final case class InitTimedOut(timeout: FiniteDuration, stderr: String) extends Error:
      def message: String = s"no Init event within $timeout (stderr tail: ${stderr.takeRight(500)})"

  /** Extension of `StreamingSession` exposing a few hooks useful in tests and diagnostics. The streaming Connector
    * methods return the base trait; tests cast up to inspect stderr buffer / exit code.
    */
  trait StreamingSessionWithDiagnostics extends StreamingSession:
    /** Snapshot of stderr lines drained so far. */
    def stderrSnapshot: IO[Vector[String]]

    /** Exit value of the subprocess; blocks until exit. */
    def exitValue: IO[Int]

  /** Build a `StreamingSessionWithDiagnostics` from a not-yet-acquired subprocess Resource and an event parser.
    *
    * @param subprocess
    *   The subprocess to spawn. Owned by the returned session: `close()` or `kill()` releases it; the JVM-process
    *   Resource finalizer is a fallback safety net.
    * @param parseLine
    *   The connector-specific parser ([[ClaudeEventParser.parse]] or `CodexEventParser#parse`).
    * @param initTimeout
    *   How long to wait for the first `Init` event before raising [[Error.InitTimedOut]]. Defaults to 30s — generous
    *   for a cold-start CLI on the user's laptop; the orchestrator's settle timeouts kick in once the session is live.
    */
  def fromSubprocess(
      subprocess: Resource[IO, Subprocess],
      parseLine: String => Either[String, Vector[AgentEvent]],
      initTimeout: FiniteDuration = 30.seconds
  ): IO[StreamingSessionWithDiagnostics] =
    subprocess.allocated.flatMap: (sp, release) =>
      buildSession(sp, release, parseLine, initTimeout)

  private def buildSession(
      sp: Subprocess,
      release: IO[Unit],
      parseLine: String => Either[String, Vector[AgentEvent]],
      initTimeout: FiniteDuration
  ): IO[StreamingSessionWithDiagnostics] =
    for
      initDef <- Deferred[IO, Either[Error, String]]
      eventChan <- Channel.unbounded[IO, AgentEvent]
      stderrBuf <- IO.ref(Vector.empty[String])
      stderrSink = (line: String) => stderrBuf.update(_ :+ line)
      parsePipeline = parseStreamPipeline(sp, parseLine, initDef, eventChan, stderrSink, stderrBuf)
      stderrPipeline = sp.stderr.evalMap(stderrSink).compile.drain
      parseFiber <- parsePipeline.compile.drain.start
      stderrFiber <- stderrPipeline.start
      initResult <-
        initDef.get.timeoutTo(
          initTimeout,
          stderrBuf.get.map(buf => Left[Error, String](Error.InitTimedOut(initTimeout, buf.mkString("\n"))))
        )
      sid <- initResult match
        case Right(id) => IO.pure(id)
        case Left(err) =>
          sp.kill().attempt.void *>
            parseFiber.cancel.attempt.void *>
            stderrFiber.cancel.attempt.void *>
            release.attempt.void *>
            IO.raiseError(RuntimeException(err.message))
    yield new StreamingSessionWithDiagnostics:
      val sessionId: String = sid
      def events: Stream[IO, AgentEvent] = eventChan.stream
      def send(input: String): IO[Unit] = sp.sendLine(input)
      def stderrSnapshot: IO[Vector[String]] = stderrBuf.get
      def exitValue: IO[Int] = sp.waitFor
      def close(): IO[Unit] =
        sp.closeStdin *> sp.waitFor.void *> parseFiber.join.attempt.void *> stderrFiber.join.attempt.void *>
          release.attempt.void
      def kill(): IO[Unit] =
        sp.kill() *> parseFiber.cancel.attempt.void *> stderrFiber.cancel.attempt.void *> release.attempt.void

  private def parseStreamPipeline(
      sp: Subprocess,
      parseLine: String => Either[String, Vector[AgentEvent]],
      initDef: Deferred[IO, Either[Error, String]],
      eventChan: Channel[IO, AgentEvent],
      stderrSink: String => IO[Unit],
      stderrBuf: cats.effect.kernel.Ref[IO, Vector[String]]
  ): Stream[IO, Unit] =
    sp.stdout
      .evalMap: line =>
        parseLine(line) match
          case Right(events) =>
            events.traverse_ {
              case e @ AgentEvent.Init(id) =>
                initDef.complete(Right(id)).attempt.void *> eventChan.send(e).void
              case other => eventChan.send(other).void
            }
          case Left(err) =>
            stderrSink(s"parse error: $err  line=$line")
      .onFinalize(
        stderrBuf.get.flatMap: buf =>
          initDef.complete(Left(Error.NoInitBeforeExit(buf.mkString("\n")))).attempt.void
            *> eventChan.close.void
      )
