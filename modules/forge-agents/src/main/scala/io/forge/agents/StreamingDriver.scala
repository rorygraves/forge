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
    * @param encodeUserInput
    *   Connector-specific transform applied to each `session.send(input)` payload before it's written to stdin. The
    *   driver doesn't know the wire shape: Claude with `--input-format stream-json` expects each line to be a JSON
    *   frame like `{"type":"user","message":{"role":"user","content":"..."}}`; a plain-text CLI would want identity.
    *   Default is identity — fine for tests and for connectors that genuinely speak raw lines.
    * @param initialUserInput
    *   v1.2 §7.1: if present, written to stdin (after `encodeUserInput`) immediately after the parse fibers start, so
    *   the CLI can produce its init event. Both pinned streaming-spec CLIs require this — Claude (`-p --input-format
    *   stream-json`) emits init only after the first JSON frame; Codex's `exec` model takes its prompt positionally
    *   anyway, so streaming Codex won't use this parameter. Default `None` — fine for headless one-shots where the
    *   prompt is on argv.
    * @param encodeAnswer
    *   v1.2 §7.1 `StreamingSession.answerQuestion` encoder. `Some(encode)` means the driver supports the §7.2
    *   `tool_result` path: each `answerQuestion(toolUseId, answer)` call runs the encoder to produce the wire frame,
    *   then writes it on stdin. The encoder returns `IO[String]` so it can `IO.raiseError(adapterError)` for invalid
    *   inputs (e.g. Claude requires `Some(toolUseId)`; `None` is a parser regression). `None` means the driver was not
    *   built for streaming Q&A — calling `answerQuestion` on the returned session raises `NotImplementedError`.
    */
  def fromSubprocess(
      subprocess: Resource[IO, Subprocess],
      parseLine: String => Either[String, Vector[AgentEvent]],
      initTimeout: FiniteDuration = 30.seconds,
      encodeUserInput: String => String = identity,
      initialUserInput: Option[String] = None,
      encodeAnswer: Option[(Option[String], String) => IO[String]] = None
  ): IO[StreamingSessionWithDiagnostics] =
    subprocess.allocated.flatMap: (sp, release) =>
      buildSession(sp, release, parseLine, initTimeout, encodeUserInput, initialUserInput, encodeAnswer)

  private def buildSession(
      sp: Subprocess,
      release: IO[Unit],
      parseLine: String => Either[String, Vector[AgentEvent]],
      initTimeout: FiniteDuration,
      encodeUserInput: String => String,
      initialUserInput: Option[String],
      encodeAnswer: Option[(Option[String], String) => IO[String]]
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
      // Write the initial user input (if any) BEFORE blocking on Init: both pinned streaming-spec CLIs need a user
      // message before they'll emit it. The mirror UserMessage event is pushed *after* Init arrives so the events
      // stream's first element stays Init (channel-order contract).
      _ <- initialUserInput.traverse_(input => sp.sendLine(encodeUserInput(input)))
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
      // Mirror the initial input as a UserMessage so the action log captures it ordering-wise after Init.
      _ <- initialUserInput.traverse_(input => eventChan.send(AgentEvent.UserMessage(summary = input.take(200))).void)
    yield new StreamingSessionWithDiagnostics:
      val sessionId: String = sid
      def events: Stream[IO, AgentEvent] = eventChan.stream

      /** Per the `AgentSession.send` contract, this emits a mirror `UserMessage` event onto the events channel BEFORE
        * writing to stdin, so the action log captures every prompt in order (UserMessage → AssistantText/ToolUse →
        * Result). The `encodeUserInput` callback transforms the payload into the wire shape the CLI expects (Claude's
        * stream-json JSON frame, raw line for simpler CLIs).
        */
      def send(input: String): IO[Unit] =
        eventChan.send(AgentEvent.UserMessage(summary = input.take(200))).void *>
          sp.sendLine(encodeUserInput(input))

      /** v1.2 §7.1 — Native `tool_result` reply path. Behaviour:
        *
        *   - With `encodeAnswer = Some(encode)`: runs the encoder to produce the wire frame (which may raise an adapter
        *     error — e.g. Claude needs `Some(toolUseId)` and rejects `None`), pushes a mirror `UserMessage` summarising
        *     the answer, and writes the frame on stdin.
        *   - With `encodeAnswer = None`: raises `NotImplementedError` — this driver wasn't built for streaming Q&A.
        *     Headless implementations and `send`-only adapters land here.
        */
      def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] =
        encodeAnswer match
          case None =>
            IO.raiseError(
              NotImplementedError(
                "StreamingDriver.answerQuestion — this session was constructed without an encodeAnswer hook " +
                  "(headless / send-only). The connector must pass one to support the §7.2 tool_result path."
              )
            )
          case Some(encode) =>
            for
              wire <- encode(toolUseId, answer)
              // Mirror the answer with an [answer] prefix so the action log can tell it apart from plain `send`
              // calls when replaying the events stream without per-call context.
              _ <- eventChan.send(AgentEvent.UserMessage(summary = s"[answer] ${answer.take(190)}")).void
              _ <- sp.sendLine(wire)
            yield ()
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
