package io.forge.agents

import cats.effect.IO
import fs2.Stream
import io.forge.core.{Mode, QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost

class RoleSuite extends munit.FunSuite:

  private final class TaggedConnector(val name: String) extends Connector:
    val questionMechanism = QuestionMechanism.Native
    val schemaMechanism = SchemaMechanism.Native

    private val notImplemented: IO[Nothing] =
      IO.raiseError(NotImplementedError("tagged connector"))

    private object Sess extends StreamingSession:
      val sessionId = s"$name-session"
      val events: Stream[IO, AgentEvent] = Stream.empty
      def close(): IO[Unit] = IO.unit
      def kill(): IO[Unit] = IO.unit
      def send(input: String): IO[Unit] = IO.unit
      def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

    def runStreamingSpec(systemPromptPath: os.Path, initialUserMessage: String): IO[StreamingSession] = IO.pure(Sess)
    def resumeStreamingSpec(sessionId: String, message: String): IO[StreamingSession] = IO.pure(Sess)
    def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] = IO.pure(Sess)
    def runFixup(prompt: FixupPrompt): IO[AgentSession] = IO.pure(Sess)
    def reviewDesign(input: DesignReviewInput): IO[DesignReview] = notImplemented
    def reviewPr(input: PrReviewInput): IO[PrReview] = notImplemented
    def refine(input: RefineInput): IO[RefineResult] = notImplemented
    def costFrom(event: AgentEvent): Option[Cost] = None

  private val claude = TaggedConnector("claude")
  private val codex = TaggedConnector("codex")

  test("Role.Driver and Role.Reviewer expose the wrapped connector via `connector`"):
    val d: Role = Role.Driver(claude)
    val r: Role = Role.Reviewer(codex)
    assertEquals(d.connector.name, "claude")
    assertEquals(r.connector.name, "codex")

  test("pairFor(ClaudeDriver) routes claude→driver, codex→reviewer"):
    val (driver, reviewer) = Role.pairFor(Mode.ClaudeDriver, claude, codex)
    assertEquals(driver.connector.name, "claude")
    assertEquals(reviewer.connector.name, "codex")

  test("pairFor(CodexDriver) routes codex→driver, claude→reviewer"):
    val (driver, reviewer) = Role.pairFor(Mode.CodexDriver, claude, codex)
    assertEquals(driver.connector.name, "codex")
    assertEquals(reviewer.connector.name, "claude")

  test("pairFor preserves cross-model review: driver and reviewer connectors differ"):
    // §1 / §22 — cross-model review is a core property; the pair must never
    // collapse to the same connector regardless of mode.
    val (d1, r1) = Role.pairFor(Mode.ClaudeDriver, claude, codex)
    val (d2, r2) = Role.pairFor(Mode.CodexDriver, claude, codex)
    assertNotEquals(d1.connector.name, r1.connector.name)
    assertNotEquals(d2.connector.name, r2.connector.name)
