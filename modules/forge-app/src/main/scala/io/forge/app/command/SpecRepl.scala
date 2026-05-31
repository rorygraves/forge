package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import fs2.{Pull, Stream}
import io.forge.agents.{AgentEvent, Connector, StreamingSession}
import io.forge.app.config.ForgeConfig
import io.forge.app.orchestrator.ConnectorFactory
import io.forge.core.{FeatureId, Question}
import io.forge.core.fsm.{Feature, Fsm, FsmConfig, FsmEvent, FsmState, UserCommand}
import io.forge.core.log.{ActionLog, FileActionLog}
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildError, RebuildState}
import io.forge.specs.{FileSpecStore, SpecStore, SpecStoreError}

/** Task 1.4.13 **M2** — `forge spec <feature>`, the one stateful interactive command (§11.1, §17).
  *
  * **Standalone line-mode REPL (not the orchestrator loop).** The `InteractiveSpec` "SessionMonitor + REPL race" the J2
  * source table sketches cannot work as drawn: a session's `events` is a single-consumer fs2 `Channel`, so the monitor
  * and the REPL would steal events from each other. `forge spec` therefore owns the event stream itself — it is the
  * sole consumer — and runs as its own handler (mirroring [[RunFeature]]). The orchestrator's `EventSource.Repl` stays
  * `IO.never`: headless `forge run` never enters the spec phase (`InteractiveSpec` is reachable only here).
  *
  * **The turn model.** Both pinned CLIs end every turn with an `AgentEvent.Result`; an `AskUserQuestion` tool use ends
  * its turn with a `Result` too, then the CLI blocks for the `tool_result`. So the loop reacts only at `Result`
  * boundaries: a `Result` that follows a question prompts the human *for that question* and routes the reply via
  * `answerQuestion`; a plain `Result` prompts for the next free message (or `/done`) and routes it via `send`.
  * Assistant text streams to stdout as it arrives.
  *
  * **Persistence is at `/done` only.** Nothing is written while the REPL runs, so an interrupted session (Ctrl-D, a
  * driver crash) leaves the feature in `Drafting` and a re-run of `forge spec` simply starts fresh — there is no
  * persisted `InteractiveSpec` to strand the operator in (the §J2 restart-recovery row for `InteractiveSpec` is thus
  * theoretical in v1; resuming the *same* CLI conversation across an interruption is deferred). On `/done` the
  * lifecycle is recorded atomically: `SessionSpawned` (`Drafting → InteractiveSpec`, projecting `designSessionId`)
  * **then** `UserCommand.Done` (`InteractiveSpec → DesignReviewing(1)`), with the manifest **reloaded from disk** — the
  * spec driver owns `manifest.json` while it decomposes, so Forge records the log + cache but never writes the manifest
  * back (writing the stale in-memory seed would clobber the driver's pieces). The §11.1 step-7 coherence post-check
  * (which the orchestrator runs on an auto-`Settled(Spec, Clean)`) is intentionally skipped on the human-driven `/done`
  * path — the operator is the coherence gate; folding it in is a later refinement.
  */
object SpecRepl:

  // --- public entry ----------------------------------------------------------

  def execute(
      paths: ForgePaths,
      config: ForgeConfig,
      featureId: FeatureId,
      io: ReplConsole = ReplConsole.real
  ): IO[ExitCode] =
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val cache = new FileStateCache(paths)
    FileActionLog(paths).flatMap { log =>
      manifestStore.load(featureId).flatMap {
        case Left(failure) =>
          fail(
            featureId,
            s"cannot load manifest — ${failure.cause.getMessage}. Has the feature been created? " +
              s"Run: forge new \"<title>\""
          )
        case Right(manifest) =>
          RebuildState.run(featureId, paths, manifestStore, log, cache).flatMap {
            case Left(err) => fail(featureId, rebuildMessage(err))
            case Right(rebuilt) =>
              classifyStart(rebuilt.feature.state) match
                case StartDecision.Refuse(message) => fail(featureId, message)
                case StartDecision.Start =>
                  ConnectorFactory.build(manifest.mode, paths, config).flatMap { connector =>
                    startSession(paths, config, specStore, log, cache, connector, rebuilt.feature, manifest.title, io)
                  }
          }
      }
    }

  /** Pure state gate: `forge spec` only opens against a fresh `Drafting` feature. Every other state names the right
    * next command so the operator is never told to use a command that would bounce them back here.
    */
  def classifyStart(state: FsmState): StartDecision = state match
    case FsmState.Drafting => StartDecision.Start
    case FsmState.InteractiveSpec =>
      // Not persisted by v1 `forge spec` (we only write at /done), so this is an out-of-band state: a prior tool, or a
      // future increment that resumes the spec session, left it here. Steer the operator to a clean restart.
      StartDecision.Refuse(
        "a spec session is already marked in progress for this feature. Resuming an interrupted spec is not " +
          "supported in v1 — run `forge abandon <feature>` and start over."
      )
    case _: FsmState.DesignReviewing | _: FsmState.DesignNeedsHumanInput | _: FsmState.DesignAwaitingMerge |
        _: FsmState.DesignPrFeedback | FsmState.DesignReady =>
      StartDecision.Refuse(
        s"the spec is already complete (current state: ${state}). Run `forge run <feature>` to continue."
      )
    case _: FsmState.Abandoned =>
      StartDecision.Refuse("this feature has been abandoned; start a new one with `forge new`.")
    case FsmState.FeatureDone =>
      StartDecision.Refuse("this feature is already complete.")
    case other =>
      StartDecision.Refuse(s"the feature is past the spec phase (current state: ${other}). Run `forge run <feature>`.")

  enum StartDecision:
    case Start
    case Refuse(message: String)

  // --- session lifecycle -----------------------------------------------------

  private def startSession(
      paths: ForgePaths,
      config: ForgeConfig,
      specStore: SpecStore,
      log: ActionLog,
      cache: FileStateCache,
      connector: Connector,
      feature0: Feature,
      title: String,
      io: ReplConsole
  ): IO[ExitCode] =
    val fsmConfig = FsmConfig(config.maxDesignReviewRounds, config.maxFixupRounds)
    specStore.loadDesign(feature0.id).map(briefFrom(_, title)).flatMap { brief =>
      io.println(intro(feature0.id, connector.name)) >>
        connector.runStreamingSpec(promptPath(paths, connector.name), specMessage(title, brief)).flatMap { session =>
          runLoop(session, io)
            .flatMap(concludeWith(specStore, log, cache, fsmConfig, feature0, session.sessionId, _, io))
            .guarantee(session.close().attempt.void)
        }
    }

  private def concludeWith(
      specStore: SpecStore,
      log: ActionLog,
      cache: FileStateCache,
      fsmConfig: FsmConfig,
      feature0: Feature,
      sessionId: String,
      outcome: ReplEnd,
      io: ReplConsole
  ): IO[ExitCode] =
    outcome match
      case ReplEnd.Done =>
        finalizeDone(specStore, log, cache, fsmConfig, feature0, sessionId).flatMap {
          case Left(message) => fail(feature0.id, message)
          case Right(_) => io.println(doneMessage(feature0.id)).as(ExitCode.Success)
        }
      case ReplEnd.Aborted =>
        io.println(abortMessage(feature0.id)).as(ExitCode(1))
      case ReplEnd.DriverEnded(reason) =>
        Console[IO]
          .errorln(
            s"forge spec ${feature0.id.value}: $reason. Nothing was recorded — re-run `forge spec` to " +
              s"retry."
          )
          .as(ExitCode(1))

  /** Record the spec lifecycle atomically once the operator types `/done`: reload the driver-owned manifest, then fold
    * `SessionSpawned` (`Drafting → InteractiveSpec`) and `UserCommand.Done` (`InteractiveSpec → DesignReviewing(1)`)
    * onto it, persisting the action-log drafts + the rebuilt cache. The manifest is **not** written back — the driver
    * is its source of truth through the spec phase (§4).
    */
  private[command] def finalizeDone(
      specStore: SpecStore,
      log: ActionLog,
      cache: FileStateCache,
      fsmConfig: FsmConfig,
      feature0: Feature,
      sessionId: String
  ): IO[Either[String, Feature]] =
    specStore.loadManifest(feature0.id).flatMap {
      case Left(err) =>
        IO.pure(Left(s"the spec driver left manifest.json unreadable: ${specErr(err)}"))
      case Right(diskManifest) =>
        val base = feature0.copy(manifest = diskManifest)
        val (spawned, d1) =
          Fsm.transition(base, FsmEvent.SessionSpawned("driver", "spec", sessionId, None), fsmConfig)
        val (done, d2) = Fsm.transition(spawned, FsmEvent.UserCommandReceived(UserCommand.Done), fsmConfig)
        done.state match
          case _: FsmState.DesignReviewing =>
            log.appendAll(feature0.id, d1 ++ d2) >> cache.save(feature0.id, done).as(Right(done))
          case unexpected =>
            IO.pure(Left(s"internal: /done did not reach design review (landed in $unexpected)"))
    }

  // --- the REPL loop (testable core) -----------------------------------------

  /** How the REPL ended. The sole consumer of `session.events`; reacts only at `Result` boundaries (see the class
    * docstring's turn model).
    */
  enum ReplEnd:
    case Done
    case Aborted
    case DriverEnded(reason: String)

  private[command] def runLoop(session: StreamingSession, io: ReplConsole): IO[ReplEnd] =
    def go(s: Stream[IO, AgentEvent], pendingQ: Option[(Question, Option[String])]): Pull[IO, ReplEnd, Unit] =
      s.pull.uncons1.flatMap {
        case None =>
          Pull.output1(ReplEnd.DriverEnded("the spec driver exited before you typed /done"))
        case Some((event, rest)) =>
          event match
            case AgentEvent.AssistantText(text, _) =>
              Pull.eval(io.println(text)) >> go(rest, pendingQ)
            case AgentEvent.ToolUse(tool, summary) =>
              Pull.eval(io.println(s"  · $tool — $summary")) >> go(rest, pendingQ)
            case AgentEvent.AskUserQuestion(question, toolUseId) =>
              go(rest, Some((question, toolUseId)))
            case AgentEvent.Result(_, _) =>
              pendingQ match
                case Some((question, toolUseId)) =>
                  Pull.eval(answer(session, io, question, toolUseId)).flatMap {
                    case true => go(rest, None)
                    case false => Pull.output1(ReplEnd.Aborted)
                  }
                case None =>
                  Pull.eval(prompt(session, io)).flatMap {
                    case PromptOutcome.Done => Pull.output1(ReplEnd.Done)
                    case PromptOutcome.Aborted => Pull.output1(ReplEnd.Aborted)
                    case PromptOutcome.Sent => go(rest, None)
                  }
            case AgentEvent.Init(_) | AgentEvent.UserMessage(_) | AgentEvent.CostUpdate(_) =>
              go(rest, pendingQ)
      }
    go(session.events, None).stream.compile.lastOrError

  private enum PromptOutcome:
    case Done
    case Aborted
    case Sent

  private def prompt(session: StreamingSession, io: ReplConsole): IO[PromptOutcome] =
    io.println(PromptHint) >> io.readLine.flatMap {
      case None => IO.pure(PromptOutcome.Aborted)
      case Some(raw) =>
        raw.trim match
          case "/done" => IO.pure(PromptOutcome.Done)
          case "/help" => io.println(HelpText) >> prompt(session, io)
          case "" => prompt(session, io)
          case _ => session.send(raw).as(PromptOutcome.Sent)
    }

  /** Returns `true` once the question is answered, `false` on EOF while awaiting the answer (→ [[ReplEnd.Aborted]]). */
  private def answer(
      session: StreamingSession,
      io: ReplConsole,
      question: Question,
      toolUseId: Option[String]
  ): IO[Boolean] =
    io.println(renderQuestion(question)) >> io.readLine.flatMap {
      case None => IO.pure(false)
      case Some(raw) => session.answerQuestion(toolUseId, chooseAnswer(question, raw.trim)).as(true)
    }

  /** A bare number picks the matching option (1-based); an empty line takes `defaultOption` when present; anything else
    * is passed through as a free-text answer (the driver schema decides whether to accept it).
    */
  private[command] def chooseAnswer(question: Question, raw: String): String =
    if raw.isEmpty then question.defaultOption.getOrElse(raw)
    else
      raw.toIntOption
        .filter(i => i >= 1 && i <= question.options.size)
        .map(i => question.options(i - 1))
        .getOrElse(raw)

  // --- rendering / messages --------------------------------------------------

  private val PromptHint = "\n[forge spec] Your reply (/done to finish, /help for commands):"

  private val HelpText =
    """  /done   finalise the spec and advance to design review
      |  /help   show this help
      |  (anything else is sent to the spec driver as your message)""".stripMargin

  private[command] def renderQuestion(question: Question): String =
    val header = s"\n[forge spec] The driver is asking:\n${question.text}"
    val options =
      if question.options.isEmpty then ""
      else "\n" + question.options.zipWithIndex.map((o, i) => s"  ${i + 1}. $o").mkString("\n")
    val freeText = if question.allowFreeText then "\n(or type a free-text answer)" else ""
    val default = question.defaultOption.map(d => s"\n(press enter for the default: $d)").getOrElse("")
    header + options + freeText + default

  private def intro(featureId: FeatureId, cli: String): String =
    s"forge spec ${featureId.value}: starting an interactive spec session (driver: $cli). " +
      "Describe what you want; type /done when the design is ready."

  private def doneMessage(featureId: FeatureId): String =
    s"forge spec ${featureId.value}: design captured and advanced to review. Next: forge run ${featureId.value}"

  private def abortMessage(featureId: FeatureId): String =
    s"forge spec ${featureId.value}: spec not finalised (no /done). Nothing was recorded — re-run `forge spec " +
      s"${featureId.value}` to continue, or `forge abandon ${featureId.value}` to discard."

  private def fail(featureId: FeatureId, detail: String): IO[ExitCode] =
    Console[IO].errorln(s"forge spec ${featureId.value}: $detail").as(ExitCode(1))

  private def rebuildMessage(err: RebuildError): String = err match
    case RebuildError.ManifestLoadFailed(_, cause) =>
      s"cannot rebuild state — manifest load failed: ${cause.getMessage}"
    case RebuildError.ReplayInconsistent(replay) => s"cannot rebuild state — action log is inconsistent: $replay"
    case RebuildError.InconsistentRecovery(reason) => s"cannot rebuild state — $reason"
    case RebuildError.CacheCorrupt(_, detail) => s"cannot rebuild state — state cache is corrupt: $detail"

  private def specErr(e: SpecStoreError): String = e match
    case SpecStoreError.NotFound(p) => s"not found: $p"
    case SpecStoreError.Malformed(p, c) => s"malformed $p: ${c.getMessage}"
    case SpecStoreError.IoFailure(p, c) => s"io error $p: ${c.getMessage}"

  private def briefFrom(designE: Either[SpecStoreError, String], title: String): String =
    designE.toOption.map(_.trim).filter(_.nonEmpty).getOrElse(title)

  /** Mirrors `RealSideEffects.promptPath("specify")` / `specMessage` — kept in sync deliberately; the spec spawn shape
    * is identical to the orchestrator's so a Claude vs Codex driver sees the same first turn either way.
    */
  private def promptPath(paths: ForgePaths, cli: String): os.Path = paths.userPromptsDir / s"specify.$cli.md"

  private def specMessage(title: String, brief: String): String =
    s"""We are starting a new feature for this repository: $title.
       |
       |$brief
       |
       |Follow your instructions to design the feature and decompose it into pieces.""".stripMargin

/** The REPL's human-facing I/O seam: line out (stdout) + line in (stdin, `None` on EOF). Abstracted so the loop is
  * unit-testable with a scripted console; [[ReplConsole.real]] is the `Console[IO]`-backed production wiring.
  */
trait ReplConsole:
  def println(line: String): IO[Unit]
  def readLine: IO[Option[String]]

object ReplConsole:
  val real: ReplConsole = new ReplConsole:
    def println(line: String): IO[Unit] = Console[IO].println(line)
    def readLine: IO[Option[String]] = Console[IO].readLine.attempt.map(_.toOption)
