package io.forge.agents

import cats.effect.IO
import cats.effect.kernel.{Deferred, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.Channel

import scala.concurrent.duration.*

/** v1.2 §7.1 — multi-process facade over Codex's `exec` model.
  *
  * Codex spawns one process per turn (Slice 0 §2.2): a streaming-spec session is implemented as N invocations of `codex
  * exec [resume] --json ...` running sequentially under a mutex, with each turn's events merged into a session-level
  * Channel and the thread id captured from the first turn used as the session id for the lifetime of the session.
  *
  * Turn boundaries:
  *   - **First turn** is spawned by [[runFirstTurn]] before [[CodexStreamingSession.start]] returns. It's either a
  *     fresh `codex exec --json ... <combined-prompt>` (from `runStreamingSpec`) or a `codex exec resume --json <sid>
  *     <combined-prompt>` (from `resumeStreamingSpec`). Its `thread.started` event becomes the session's `Init`.
  *   - **Subsequent turns** (via `send` / `answerQuestion`) are always `codex exec resume --json <thread-id>
  *     <combined-prompt>`. Their `thread.started` event is dropped from the session stream so consumers see exactly one
  *     `Init`.
  *
  * §7.10(a) system-prompt prepending is applied to **every** turn — Codex's adapter contract has no notion of a
  * persistent system prompt across resume calls, and the model has been observed to lose the role framing without it.
  * `answerQuestion(_, answer)` is identical to `send(answer)`: the §7.3 halt envelope has no wire-level `tool_use` to
  * reference, so the orchestrator's `toolUseId` is dropped on the floor and the answer rides as the next turn's user
  * message.
  *
  * `kill()` SIGTERMs whatever subprocess is currently mid-turn (if any) and finalises the events channel; `close()`
  * waits for the current turn to drain naturally and then finalises.
  */
final class CodexStreamingSession private (
    val sessionId: String,
    sessionChan: Channel[IO, AgentEvent],
    stderrBuf: Ref[IO, Vector[String]],
    turnMutex: Mutex[IO],
    currentTurnRef: Ref[IO, Option[Subprocess]],
    closedRef: Ref[IO, Boolean],
    systemPromptPath: Option[os.Path],
    binary: String,
    cwd: Option[os.Path],
    extraEnv: Map[String, String],
    parser: CodexEventParser,
    initTimeout: FiniteDuration
) extends StreamingSession:

  def events: Stream[IO, AgentEvent] = sessionChan.stream

  /** Send a user message as the next turn's input. Serialised against any in-flight turn via the session mutex.
    */
  def send(input: String): IO[Unit] =
    runResumeTurn(input)

  /** §7.3 step 3 — `toolUseId` is dropped (HaltWithQuestion has no wire-level tool use); the answer becomes the next
    * turn's user message.
    */
  def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] =
    val _ = toolUseId
    runResumeTurn(answer)

  /** Wait for the current turn (if any) to drain naturally, then close the events channel. After this, any further
    * `send` / `answerQuestion` calls raise.
    */
  def close(): IO[Unit] =
    closedRef.set(true) *>
      turnMutex.lock.surround(IO.unit) *>
      sessionChan.close.void

  /** Terminate any currently active subprocess (SIGTERM → grace → SIGKILL via [[Subprocess.kill]]) and finalise the
    * events channel. Pending `send` / `answerQuestion` calls waiting on the mutex will find `closedRef = true` and
    * bail.
    */
  def kill(): IO[Unit] =
    closedRef.set(true) *>
      currentTurnRef.get.flatMap {
        case Some(sp) => sp.kill().attempt.void
        case None => IO.unit
      } *>
      sessionChan.close.void

  private def runResumeTurn(userMessage: String): IO[Unit] =
    closedRef.get.flatMap {
      case true =>
        IO.raiseError(
          IllegalStateException(
            s"CodexStreamingSession($sessionId): session is closed; further turns rejected"
          )
        )
      case false =>
        turnMutex.lock.surround(spawnAndDrainTurn(userMessage, isFirstTurn = false, initDef = None))
    }

  /** One-turn drain. Builds the combined system-prompt + user-message prompt, spawns `codex exec resume --json <sid>
    * <prompt>`, drains stdout into the session channel (Init events dropped on resume turns), drains stderr into the
    * shared buffer, waits for exit.
    *
    * For the first turn (called via the package-private factory below), `isFirstTurn = true` and `initDef` is the
    * Deferred the factory blocks on; that's how `start` returns synchronously with a populated `sessionId`.
    */
  private[agents] def spawnAndDrainTurn(
      userMessage: String,
      isFirstTurn: Boolean,
      initDef: Option[Deferred[IO, Either[Throwable, String]]]
  ): IO[Unit] =
    for
      combined <- IO.delay(
        systemPromptPath.fold(userMessage)(p => CodexPrompt.withSystemBlock(p, userMessage))
      )
      argv = CodexConnector.execResumeArgv(binary, sessionId, combined)
      _ <- runOneTurn(argv, userMessage, isFirstTurn, initDef)
    yield ()

  /** Inner per-turn driver. Pulled out so the first-turn factory can supply a different argv (initial spawn vs resume
    * spawn) but reuse all the drain plumbing. The fiber that calls this owns the turn mutex for the duration.
    *
    * Init synchronisation: `initDef` is completed (with `Right(id)`) the moment we see `thread.started` on stdout. If
    * the subprocess exits without producing one, the finalizer below completes `initDef` with `Left` so the factory's
    * `start` doesn't hang. The factory races `initDef.get` against its own `initTimeout`; if the timeout wins, the
    * factory kills the subprocess via `currentTurnRef` and propagates.
    */
  private[agents] def runOneTurn(
      argv: List[String],
      userMessageMirror: String,
      isFirstTurn: Boolean,
      initDef: Option[Deferred[IO, Either[Throwable, String]]]
  ): IO[Unit] =
    Subprocess.spawn(argv, cwd = cwd, env = extraEnv).use { sp =>
      val drainStdout =
        sp.stdout
          .evalMap: line =>
            parser.parse(line) match
              case Right(events) =>
                events.traverse_ {
                  case AgentEvent.Init(id) =>
                    if isFirstTurn then
                      // First turn carries the session-level Init. Notify the factory and forward the event, then
                      // emit a UserMessage mirror so the action-log captures the originating user input ordering-wise
                      // after Init.
                      initDef.fold(IO.unit)(_.complete(Right(id)).attempt.void) *>
                        sessionChan.send(AgentEvent.Init(id)).void *>
                        sessionChan
                          .send(AgentEvent.UserMessage(userMessageMirror.take(200)))
                          .void
                    else
                      // Resume turns also emit a thread.started; drop it to keep exactly one Init in the session
                      // stream.
                      IO.unit
                  case other => sessionChan.send(other).void
                }
              case Left(err) =>
                stderrBuf.update(_ :+ s"parse error: $err  line=$line")
          .compile
          .drain
      val drainStderr = sp.stderr.evalMap(line => stderrBuf.update(_ :+ line)).compile.drain
      // For resume turns we emit the mirror eagerly so the channel order is UserMessage → AssistantText/... → Result
      // (no Init in between, since we drop the resume's thread.started). For the first turn the mirror is enqueued
      // inside the stdout handler so it lands AFTER Init.
      val preTurn =
        if isFirstTurn then IO.unit
        else sessionChan.send(AgentEvent.UserMessage(userMessageMirror.take(200))).void

      val core =
        for
          _ <- currentTurnRef.set(Some(sp))
          _ <- preTurn
          stdoutFiber <- drainStdout.start
          stderrFiber <- drainStderr.start
          // Block here until the subprocess exits naturally OR is killed externally. Either way the mutex stays
          // held until drain is fully complete — that's how a `send` queued behind a first-turn fiber lines up.
          _ <- sp.waitFor
          _ <- stdoutFiber.join.attempt.void
          _ <- stderrFiber.join.attempt.void
          _ <- currentTurnRef.set(None)
        yield ()
      core.guarantee(
        // If the first turn finished without producing Init, complete initDef with Left so the factory doesn't hang.
        // No-op if Init already arrived (Deferred.complete is single-shot).
        if isFirstTurn then
          initDef.fold(IO.unit)(
            _.complete(
              Left(RuntimeException("Codex first turn exited before producing thread.started"))
            ).attempt.void
          )
        else IO.unit
      )
    }

  /** Snapshot of stderr lines drained across every turn so far. Useful for tests / diagnostics. */
  def stderrSnapshot: IO[Vector[String]] = stderrBuf.get

object CodexStreamingSession:

  /** Build and start a session with its first turn already in flight. Blocks (in IO) until the first turn's `Init`
    * arrives (or the init timeout elapses, in which case the IO raises).
    *
    * @param firstTurnArgv
    *   the argv for the FIRST turn. For [[CodexConnector.runStreamingSpec]] this is `codex exec ... <combined>`; for
    *   [[CodexConnector.resumeStreamingSpec]] it's `codex exec resume ... <combined>`. Subsequent turns always use the
    *   resume shape ([[CodexConnector.execResumeArgv]]).
    * @param sessionIdHint
    *   `Some(sid)` when [[CodexConnector.resumeStreamingSpec]] supplies the thread id up front; `None` when
    *   [[CodexConnector.runStreamingSpec]] discovers it from the Init event. Either way, the first turn's Init must
    *   eventually arrive — when both are present we cross-check.
    */
  def start(
      firstTurnArgv: List[String],
      initialUserMessage: String,
      systemPromptPath: Option[os.Path],
      binary: String,
      cwd: Option[os.Path],
      extraEnv: Map[String, String],
      parser: CodexEventParser,
      initTimeout: FiniteDuration,
      sessionIdHint: Option[String]
  ): IO[CodexStreamingSession] =
    for
      sessionChan <- Channel.unbounded[IO, AgentEvent]
      stderrBuf <- IO.ref(Vector.empty[String])
      turnMutex <- Mutex[IO]
      currentTurnRef <- IO.ref(Option.empty[Subprocess])
      closedRef <- IO.ref(false)
      initDef <- Deferred[IO, Either[Throwable, String]]
      // Take the mutex permit upfront so any concurrent send/answerQuestion called immediately after start returns
      // queues until the first turn drains. The first-turn fiber must release the permit when the turn ends.
      // Sketch: we run the first turn under turnMutex.lock.surround so the permit is released on completion.
      session = new CodexStreamingSession(
        sessionId = sessionIdHint.getOrElse(""), // placeholder; replaced after Init arrives, see below
        sessionChan = sessionChan,
        stderrBuf = stderrBuf,
        turnMutex = turnMutex,
        currentTurnRef = currentTurnRef,
        closedRef = closedRef,
        systemPromptPath = systemPromptPath,
        binary = binary,
        cwd = cwd,
        extraEnv = extraEnv,
        parser = parser,
        initTimeout = initTimeout
      )
      _ <- turnMutex.lock
        .surround(
          session.runOneTurn(
            argv = firstTurnArgv,
            userMessageMirror = initialUserMessage,
            isFirstTurn = true,
            initDef = Some(initDef)
          )
        )
        .start
      // Block on the first turn's Init, racing against initTimeout. The fiber will also complete initDef with Left if
      // the subprocess exits without producing thread.started — that's the "no Init before exit" case. Both branches
      // converge on cleanup-then-raise.
      sid <- initDef.get
        .timeoutTo(
          initTimeout,
          stderrBuf.get.flatMap: buf =>
            IO.pure(
              Left(
                RuntimeException(
                  s"Codex first turn produced no thread.started within $initTimeout " +
                    s"(stderr tail: ${buf.takeRight(20).mkString("\n")})"
                )
              )
            )
        )
        .flatMap {
          case Right(id) => IO.pure(id)
          case Left(err) =>
            closedRef.set(true) *>
              currentTurnRef.get.flatMap {
                case Some(sp) => sp.kill().attempt.void
                case None => IO.unit
              } *>
              sessionChan.close.attempt.void *>
              IO.raiseError(err)
        }
      // Cross-check the discovered sid against the hint (resume case). A mismatch would mean the CLI lost session
      // continuity on resume — surfacing here rather than silently lying via sessionId is the safer call.
      _ <- sessionIdHint match
        case Some(expected) if expected != sid =>
          closedRef.set(true) *>
            currentTurnRef.get.flatMap {
              case Some(sp) => sp.kill().attempt.void
              case None => IO.unit
            } *>
            sessionChan.close.attempt.void *>
            IO.raiseError(
              RuntimeException(
                s"CodexStreamingSession.start: resume produced thread_id '$sid' but expected '$expected'"
              )
            )
        case _ => IO.unit
      finalSession <-
        if sessionIdHint.exists(_ == sid) then IO.pure(session)
        else
          // Rebuild the wrapper with the discovered sid so the public `sessionId: String` reflects the real value.
          // All the underlying state (channels, refs, in-flight fiber) is reused.
          IO.pure(
            new CodexStreamingSession(
              sessionId = sid,
              sessionChan = sessionChan,
              stderrBuf = stderrBuf,
              turnMutex = turnMutex,
              currentTurnRef = currentTurnRef,
              closedRef = closedRef,
              systemPromptPath = systemPromptPath,
              binary = binary,
              cwd = cwd,
              extraEnv = extraEnv,
              parser = parser,
              initTimeout = initTimeout
            )
          )
    yield finalSession
