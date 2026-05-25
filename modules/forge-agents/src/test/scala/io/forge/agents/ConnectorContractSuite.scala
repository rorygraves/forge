package io.forge.agents

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.core.{QuestionMechanism, SchemaMechanism}

/** Smoke test — verifies the §7.1 trait shape is concrete enough to implement without unimplemented members. Every
  * method here returns a failing IO; the point is the compile-time signature check.
  */
class ConnectorContractSuite extends munit.FunSuite:

  private object NoopConnector extends Connector:
    val name = "noop"
    val questionMechanism = QuestionMechanism.Native
    val schemaMechanism = SchemaMechanism.SchemaFallback

    private val notImplemented: IO[Nothing] =
      IO.raiseError(NotImplementedError("noop connector"))

    private object NoopSession extends StreamingSession:
      val sessionId = "noop-session"
      val events: Stream[IO, AgentEvent] = Stream.empty
      def close(): IO[Unit] = IO.unit
      def kill(): IO[Unit] = IO.unit
      def send(input: String): IO[Unit] = IO.unit
      def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

    def runStreamingSpec(systemPromptPath: os.Path, initialUserMessage: String): IO[StreamingSession] =
      IO.pure(NoopSession)
    def resumeStreamingSpec(sessionId: String, message: String): IO[StreamingSession] =
      IO.pure(NoopSession)
    def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] = IO.pure(NoopSession)
    def runFixup(prompt: FixupPrompt): IO[AgentSession] = IO.pure(NoopSession)
    def reviewDesign(input: DesignReviewInput): IO[DesignReview] = notImplemented
    def reviewPr(input: PrReviewInput): IO[PrReview] = notImplemented
    def refine(input: RefineInput): IO[RefineResult] = notImplemented
    def costFrom(event: AgentEvent): Option[Cost] = event match
      case AgentEvent.CostUpdate(c) => Some(c)
      case _ => None

  test("Connector trait is implementable (smoke)"):
    val c: Connector = NoopConnector
    assertEquals(c.name, "noop")
    assertEquals(c.questionMechanism, QuestionMechanism.Native)
    assertEquals(c.schemaMechanism, SchemaMechanism.SchemaFallback)

  test("StreamingSession extends AgentSession"):
    val s: AgentSession = NoopConnector.runStreamingSpec(os.root / "tmp" / "fake", "hello").unsafeRunSync()
    assertEquals(s.sessionId, "noop-session")
    s.close().unsafeRunSync()

  test("costFrom extracts cost from CostUpdate, returns None otherwise"):
    val c = Cost("anthropic", "claude-opus", 100, 50, BigDecimal("0.01"))
    assertEquals(NoopConnector.costFrom(AgentEvent.CostUpdate(c)), Some(c))
    assertEquals(NoopConnector.costFrom(AgentEvent.Result(true, 0L)), None)
