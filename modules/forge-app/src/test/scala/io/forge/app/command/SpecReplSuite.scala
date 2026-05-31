package io.forge.app.command

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, Question, QuestionSeverity}
import io.forge.core.fsm.{Feature, FsmConfig, FsmState}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

/** Task 1.4.13 **M2** — `forge spec` REPL. Two testable seams are exercised without a real CLI:
  *   - [[SpecRepl.runLoop]] — the event/stdin interaction, driven by a scripted [[StreamingSession]] + [[ReplConsole]];
  *   - [[SpecRepl.finalizeDone]] — the `/done` persist against real `File*` stores in a temp tree, proving the
  *     driver-owned manifest is reloaded (not clobbered) and the lifecycle lands in `DesignReviewing(1)`.
  *
  * The pure gate ([[SpecRepl.classifyStart]]) and answer helpers are unit-checked directly. The full real-CLI spec
  * session is the Task 1.4.16 MVP-gate integration surface.
  */
class SpecReplSuite extends munit.FunSuite:

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val p2 = PieceId("p2")
  private val fsmConfig = FsmConfig(maxDesignReviewRounds = 3, maxFixupRounds = 3)

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-specrepl-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  // --- classifyStart ---------------------------------------------------------

  test("classifyStart: Drafting opens the session"):
    assertEquals(SpecRepl.classifyStart(FsmState.Drafting), SpecRepl.StartDecision.Start)

  test("classifyStart: every non-Drafting state refuses with guidance"):
    val refusals = Vector(
      FsmState.InteractiveSpec,
      FsmState.DesignReviewing(1),
      FsmState.DesignReady,
      FsmState.PieceImplementing(p1),
      FsmState.FeatureDone,
      FsmState.Abandoned("done")
    )
    refusals.foreach { state =>
      SpecRepl.classifyStart(state) match
        case SpecRepl.StartDecision.Refuse(message) => assert(message.nonEmpty, s"empty refusal for $state")
        case SpecRepl.StartDecision.Start => fail(s"expected Refuse for $state")
    }

  // --- chooseAnswer / renderQuestion ----------------------------------------

  private def question(opts: Vector[String], free: Boolean, default: Option[String] = None): Question =
    Question("Pick one?", opts, allowFreeText = free, severity = QuestionSeverity.Clarifying, defaultOption = default)

  test("chooseAnswer: a 1-based number selects the option"):
    assertEquals(SpecRepl.chooseAnswer(question(Vector("a", "b", "c"), free = false), "2"), "b")

  test("chooseAnswer: out-of-range / non-numeric is passed through as free text"):
    val q = question(Vector("a", "b"), free = true)
    assertEquals(SpecRepl.chooseAnswer(q, "9"), "9")
    assertEquals(SpecRepl.chooseAnswer(q, "use my own wording"), "use my own wording")

  test("chooseAnswer: an empty line takes the default when present"):
    assertEquals(SpecRepl.chooseAnswer(question(Vector("a", "b"), free = false, default = Some("a")), ""), "a")

  test("renderQuestion: shows the text and numbered options"):
    val rendered = SpecRepl.renderQuestion(question(Vector("yes", "no"), free = true))
    assert(rendered.contains("Pick one?"), rendered)
    assert(rendered.contains("1. yes"), rendered)
    assert(rendered.contains("2. no"), rendered)

  // --- runLoop ---------------------------------------------------------------

  test("runLoop: streams assistant text then /done ends the session"):
    val session = ScriptedSpecSession.make(Vector(text("Here is the design."), result)).unsafeRunSync()
    val console = ScriptedConsole.make(List("/done")).unsafeRunSync()
    val end = SpecRepl.runLoop(session, console).unsafeRunSync()
    assertEquals(end, SpecRepl.ReplEnd.Done)
    assert(console.lines.unsafeRunSync().exists(_.contains("Here is the design.")))

  test("runLoop: a free-text turn is sent to the driver, then /done"):
    val session = ScriptedSpecSession.make(Vector(result, text("noted"), result)).unsafeRunSync()
    val console = ScriptedConsole.make(List("add a CLI flag", "/done")).unsafeRunSync()
    val end = SpecRepl.runLoop(session, console).unsafeRunSync()
    assertEquals(end, SpecRepl.ReplEnd.Done)
    assertEquals(session.sentMessages.unsafeRunSync(), Vector("add a CLI flag"))

  test("runLoop: a driver question is answered via answerQuestion at the Result boundary"):
    val q = question(Vector("Postgres", "SQLite"), free = false)
    val events = Vector(text("which db?"), AgentEvent.AskUserQuestion(q, Some("tu-1")), result, text("ok"), result)
    val session = ScriptedSpecSession.make(events).unsafeRunSync()
    val console = ScriptedConsole.make(List("1", "/done")).unsafeRunSync()
    val end = SpecRepl.runLoop(session, console).unsafeRunSync()
    assertEquals(end, SpecRepl.ReplEnd.Done)
    assertEquals(session.answers.unsafeRunSync(), Vector((Some("tu-1"), "Postgres")))

  test("runLoop: EOF at a prompt aborts without /done"):
    val session = ScriptedSpecSession.make(Vector(result)).unsafeRunSync()
    val console = ScriptedConsole.make(Nil).unsafeRunSync()
    assertEquals(SpecRepl.runLoop(session, console).unsafeRunSync(), SpecRepl.ReplEnd.Aborted)

  test("runLoop: a driver that exits before /done is reported as DriverEnded"):
    val session = ScriptedSpecSession.make(Vector(text("partial output"))).unsafeRunSync()
    val console = ScriptedConsole.make(List("/done")).unsafeRunSync()
    SpecRepl.runLoop(session, console).unsafeRunSync() match
      case SpecRepl.ReplEnd.DriverEnded(reason) => assert(reason.nonEmpty)
      case other => fail(s"expected DriverEnded, got $other")

  // --- finalizeDone ----------------------------------------------------------

  tempFixture.test("finalizeDone: reloads the driver's manifest and advances to DesignReviewing(1)"): root =>
    val paths = new ForgePaths(root)
    val specStore = new FileSpecStore(paths)
    val cache = new FileStateCache(paths)
    // The driver decomposed into two pieces on disk; the in-memory feature still carries the empty `forge new` seed.
    val driverManifest = manifest(Vector(piece(p1, 1), piece(p2, 2)))
    specStore.saveManifest(featureId, driverManifest).unsafeRunSync()
    val feature0 = Feature.initial(featureId, manifest(Vector.empty)) // state = Drafting, no pieces

    val result0 = (for
      log <- FileActionLog(paths)
      out <- SpecRepl.finalizeDone(specStore, log, cache, fsmConfig, feature0, "sess-123")
    yield out).unsafeRunSync()

    result0 match
      case Right(done) =>
        assertEquals(done.state, FsmState.DesignReviewing(1): FsmState)
        assertEquals(done.designSessionId, Some("sess-123"))
        // Reloaded from disk — the driver's two pieces, not the empty seed.
        assertEquals(done.manifest.pieces.map(_.id), Vector(p1, p2))
        // Persisted to the rebuildable cache.
        assertEquals(cache.load(featureId).unsafeRunSync().map(_.state), Some(FsmState.DesignReviewing(1): FsmState))
        // The driver-owned manifest.json was not rewritten (still the two pieces).
        assertEquals(specStore.loadManifest(featureId).unsafeRunSync().map(_.pieces.size), Right(2))
      case Left(message) => fail(s"expected Right, got Left($message)")

  tempFixture.test("finalizeDone: an unreadable manifest is surfaced, nothing persisted"): root =>
    val paths = new ForgePaths(root)
    val specStore = new FileSpecStore(paths)
    val cache = new FileStateCache(paths)
    val feature0 = Feature.initial(featureId, manifest(Vector.empty))

    val result0 = (for
      log <- FileActionLog(paths)
      out <- SpecRepl.finalizeDone(specStore, log, cache, fsmConfig, feature0, "sess-9")
    yield out).unsafeRunSync()

    assert(result0.isLeft, s"expected Left, got $result0")
    assertEquals(cache.load(featureId).unsafeRunSync(), None)

  // --- fixtures --------------------------------------------------------------

  private def text(s: String): AgentEvent = AgentEvent.AssistantText(s, outputTokens = 1)
  private val result: AgentEvent = AgentEvent.Result(success = true, durationMs = 5)

  private def manifest(pieces: Vector[Piece]): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "My Feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = pieces
    )

  private def piece(id: PieceId, order: Int): Piece =
    Piece(
      id = id,
      order = order,
      title = s"Piece ${id.value}",
      summary = s"summary ${id.value}",
      specPath = s".forge/specs/feat/pieces/${id.value}.md",
      acceptanceHash = "sha256:" + ("0" * 64),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

/** Scripted [[ReplConsole]]: serves `inputs` in order (`None` once exhausted = EOF) and records every printed line. */
final class ScriptedConsole(inputs: Ref[IO, List[String]], outputs: Ref[IO, Vector[String]]) extends ReplConsole:
  def println(line: String): IO[Unit] = outputs.update(_ :+ line)
  def readLine: IO[Option[String]] = inputs.modify {
    case Nil => (Nil, None)
    case head :: tail => (tail, Some(head))
  }
  def lines: IO[Vector[String]] = outputs.get

object ScriptedConsole:
  def make(inputs: List[String]): IO[ScriptedConsole] =
    for
      in <- Ref.of[IO, List[String]](inputs)
      out <- Ref.of[IO, Vector[String]](Vector.empty)
    yield new ScriptedConsole(in, out)

/** Scripted [[StreamingSession]]: replays a fixed event vector and records `send` / `answerQuestion` calls. */
final class ScriptedSpecSession(
    scriptedEvents: Vector[AgentEvent],
    sent: Ref[IO, Vector[String]],
    answered: Ref[IO, Vector[(Option[String], String)]],
    override val sessionId: String
) extends StreamingSession:
  def events: Stream[IO, AgentEvent] = Stream.emits(scriptedEvents).covary[IO]
  def send(input: String): IO[Unit] = sent.update(_ :+ input)
  def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = answered.update(_ :+ (toolUseId, answer))
  def close(): IO[Unit] = IO.unit
  def kill(): IO[Unit] = IO.unit
  def sentMessages: IO[Vector[String]] = sent.get
  def answers: IO[Vector[(Option[String], String)]] = answered.get

object ScriptedSpecSession:
  def make(events: Vector[AgentEvent], sessionId: String = "sess-test"): IO[ScriptedSpecSession] =
    for
      sent <- Ref.of[IO, Vector[String]](Vector.empty)
      answered <- Ref.of[IO, Vector[(Option[String], String)]](Vector.empty)
    yield new ScriptedSpecSession(events, sent, answered, sessionId)
