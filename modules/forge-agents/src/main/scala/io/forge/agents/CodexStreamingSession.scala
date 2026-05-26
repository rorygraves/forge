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
    firstTurnFailureRef: Ref[IO, Option[Throwable]],
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
    val rejectionError =
      IO.raiseError[Unit](
        IllegalStateException(
          s"CodexStreamingSession($sessionId): session is closed; further turns rejected"
        )
      )
    // Check the first-turn-failure ref before the generic "session is closed" rejection. When the first turn fails
    // after Init has arrived (Init satisfies the factory's blocking gate, but the drain then surfaces a non-zero
    // exit / missing Result / §6.1 mismatch), the session is finalised silently — closedRef = true — so the next
    // send/answerQuestion would otherwise raise the misleading "session is closed" message. Surfacing the actual
    // first-turn cause here is the only place the caller can see it: the start factory has already returned a live
    // session by then, so it can't raise on its own.
    val firstTurnFailureCheck =
      firstTurnFailureRef.get.flatMap {
        case Some(err) => IO.raiseError[Unit](err)
        case None => IO.unit
      }
    firstTurnFailureCheck *>
      closedRef.get.flatMap {
        case true => rejectionError
        case false =>
          // Fast-path bail above is a UX nicety; the load-bearing check is INSIDE the mutex. A queued turn waiting on
          // `turnMutex.lock` here could otherwise miss a `close()` / `kill()` that flips `closedRef` after the outer
          // check but before the mutex is acquired — and then spend an unwanted model call (and write into an already
          // closed channel under `kill()`). The contract in the class-level scaladoc is that `closedRef = true` halts
          // future turns; re-read inside the mutex to honour it. The first-turn-failure check is re-applied too: a
          // first turn that fails while we wait on the mutex must surface here.
          turnMutex.lock.surround(
            firstTurnFailureCheck *>
              closedRef.get.flatMap {
                case true => rejectionError
                case false => spawnAndDrainTurn(userMessage, isFirstTurn = false, initDef = None)
              }
          )
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
      // Per-turn capture of a resume-turn `thread.started` whose id doesn't match the session's. §6.1 invariant on
      // the pinned CLI is that `codex exec resume <sid>` preserves the original `thread_id`; a mismatch means
      // continuity broke (a different conversation got resumed, or the CLI silently re-keyed). The first-turn path
      // surfaces this via the factory's hint cross-check; this Ref covers the in-session send / answerQuestion turns
      // which would otherwise drop the mismatched event silently.
      for
        turnErrRef <- IO.ref(Option.empty[Throwable])
        // Did this turn emit a Result event? AgentSession.events says "Terminates with a Result event"; a turn that
        // exits without producing one violates that contract for downstream consumers and is treated as a turn
        // failure below (close session + raise, rather than letting send/answerQuestion silently report success).
        gotResultRef <- IO.ref(false)
        drainStdout =
          sp.stdout
            .evalMap: line =>
              parser.parse(line) match
                case Right(events) =>
                  events.traverse_ {
                    case AgentEvent.Init(id) =>
                      if isFirstTurn then
                        // First turn carries the session-level Init. Notify the factory and forward the event, then
                        // emit a UserMessage mirror so the action-log captures the originating user input
                        // ordering-wise after Init.
                        initDef.fold(IO.unit)(_.complete(Right(id)).attempt.void) *>
                          sessionChan.send(AgentEvent.Init(id)).void *>
                          sessionChan
                            .send(AgentEvent.UserMessage(userMessageMirror.take(200)))
                            .void
                      else if id != sessionId then
                        // Resume turn produced a different thread_id than the one we're tracking — §6.1 invariant
                        // violation. Record the first occurrence; the post-drain block below surfaces it as a turn
                        // failure rather than silently dropping the event.
                        turnErrRef.update {
                          case None =>
                            Some(
                              RuntimeException(
                                s"CodexStreamingSession($sessionId): resume turn produced thread_id '$id' " +
                                  s"(expected '$sessionId') — §6.1 continuity break"
                              )
                            )
                          case some => some
                        }
                      else
                        // Resume turns also emit a thread.started with the same id; drop it to keep exactly one
                        // Init in the session stream.
                        IO.unit
                    case res @ AgentEvent.Result(_, _) =>
                      gotResultRef.set(true) *> sessionChan.send(res).void
                    case other => sessionChan.send(other).void
                  }
                case Left(err) =>
                  stderrBuf.update(_ :+ s"parse error: $err  line=$line")
            .compile
            .drain
        drainStderr = sp.stderr.evalMap(line => stderrBuf.update(_ :+ line)).compile.drain
        // For resume turns we emit the mirror eagerly so the channel order is UserMessage → AssistantText/... →
        // Result (no Init in between, since we drop the resume's thread.started). For the first turn the mirror is
        // enqueued inside the stdout handler so it lands AFTER Init.
        preTurn =
          if isFirstTurn then IO.unit
          else sessionChan.send(AgentEvent.UserMessage(userMessageMirror.take(200))).void
        core = for
          _ <- currentTurnRef.set(Some(sp))
          // Codex reads stdin even when the prompt is on argv (it logs "Reading additional input from stdin..." to
          // stderr and blocks until EOF). The per-turn subprocess sends nothing on stdin, so close it immediately.
          // Same rationale as CodexConnector.spawnHeadless — see comment there.
          _ <- sp.closeStdin
          _ <- preTurn
          stdoutFiber <- drainStdout.start
          stderrFiber <- drainStderr.start
          // Block here until the subprocess exits naturally OR is killed externally. Either way the mutex stays
          // held until drain is fully complete — that's how a `send` queued behind a first-turn fiber lines up.
          exitCode <- sp.waitFor
          _ <- stdoutFiber.join.attempt.void
          _ <- stderrFiber.join.attempt.void
          _ <- currentTurnRef.set(None)
          // Build the final outcome. Three failure modes converge on the same finalisation (close session, then
          // raise for resume turns / silently finalise for the first turn):
          //   1. §6.1 thread_id mismatch on a resume turn (most specific — surfaces first).
          //   2. Non-zero exit code — process-level failure.
          //   3. Clean exit but no Result event — violates the AgentSession.events "terminates with Result" contract
          //      and would otherwise leave send/answerQuestion reporting success with a truncated event stream.
          // For resume turns the raise propagates back through `runResumeTurn` to the send/answerQuestion caller.
          // For first turns the fiber is forked via `.start` with no consumer, so raising would just leak an
          // unhandled-fiber stack trace to stderr (also: a SIGTERM-killed first turn via `kill()` is an expected
          // shutdown, not a bug to surface). Instead we close the channel + set closedRef so the consumer's events
          // stream truncates and any subsequent send/answerQuestion rejects.
          maybeMismatch <- turnErrRef.get
          haveResult <- gotResultRef.get
          turnLabel = if isFirstTurn then "first turn" else "resume turn"
          failure <- maybeMismatch match
            case Some(err) => IO.pure(Some(err))
            case None =>
              if exitCode != 0 then
                stderrBuf.get.map: buf =>
                  val tail = buf.takeRight(20).mkString("\n")
                  Some(
                    RuntimeException(
                      s"CodexStreamingSession($sessionId): $turnLabel exited $exitCode " +
                        s"(stderr tail: $tail)"
                    )
                  )
              else if !haveResult then
                stderrBuf.get.map: buf =>
                  val tail = buf.takeRight(20).mkString("\n")
                  Some(
                    RuntimeException(
                      s"CodexStreamingSession($sessionId): $turnLabel exited cleanly but produced no Result " +
                        s"event (stderr tail: $tail)"
                    )
                  )
              else IO.pure(None)
          _ <- failure match
            case None => IO.unit
            case Some(err) if isFirstTurn =>
              // First turn after Init arrived: factory already returned a live session, so we can't raise back to
              // the runStreamingSpec caller. Record the cause; the next send/answerQuestion picks it up via
              // firstTurnFailureRef and raises with the real error instead of the misleading "session is closed".
              firstTurnFailureRef.set(Some(err)) *> finaliseSession
            case Some(err) => finaliseAndRaise(err)
        yield ()
        _ <- core.guarantee(
          // If the first turn finished without producing Init, complete initDef with Left so the factory doesn't
          // hang. No-op if Init already arrived (Deferred.complete is single-shot).
          if isFirstTurn then
            initDef.fold(IO.unit)(
              _.complete(
                Left(RuntimeException("Codex first turn exited before producing thread.started"))
              ).attempt.void
            )
          else IO.unit
        )
      yield ()
    }

  /** Tear down the session (set `closedRef`, close the events channel). Used by both `finaliseAndRaise` and the silent
    * first-turn-failure path so the orchestrator never sees a half-torn-down session that still accepts sends.
    */
  private def finaliseSession: IO[Unit] =
    closedRef.set(true) *> sessionChan.close.attempt.void

  /** Finalise the session and raise `err` to the caller (resume turn → propagates back to send/answerQuestion). */
  private def finaliseAndRaise(err: Throwable): IO[Unit] =
    finaliseSession *> IO.raiseError(err)

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
      firstTurnFailureRef <- IO.ref(Option.empty[Throwable])
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
        firstTurnFailureRef = firstTurnFailureRef,
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
              firstTurnFailureRef = firstTurnFailureRef,
              systemPromptPath = systemPromptPath,
              binary = binary,
              cwd = cwd,
              extraEnv = extraEnv,
              parser = parser,
              initTimeout = initTimeout
            )
          )
    yield finalSession
