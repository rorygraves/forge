package io.forge.agents

import cats.effect.IO
import fs2.Stream
import io.forge.core.{QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost
import io.forge.core.profile.RepoProfile

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
    def resumeStreamingSpec(sessionId: String, systemPromptPath: os.Path, message: String): IO[StreamingSession] =
      IO.pure(Sess)
    def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] = IO.pure(Sess)
    def runFixup(prompt: FixupPrompt): IO[AgentSession] = IO.pure(Sess)
    def resumeHeadlessDriver(sessionId: String, systemPromptPath: os.Path, message: String): IO[AgentSession] =
      IO.pure(Sess)
    def reviewDesign(input: DesignReviewInput): IO[Reviewed[DesignReview]] = notImplemented
    def reviewPr(input: PrReviewInput): IO[Reviewed[PrReview]] = notImplemented
    def refine(input: RefineInput): IO[Reviewed[RefineResult]] = notImplemented
    def profileRepo(input: RepoProfilerInput): IO[Reviewed[RepoProfile]] = notImplemented
    def classifyFailure(input: FailureClassifierInput): IO[Reviewed[io.forge.core.profile.Classification]] =
      notImplemented
    def learnConventions(input: ConventionLearnerInput): IO[Reviewed[io.forge.core.profile.ConventionDeltas]] =
      notImplemented
    def costFrom(event: AgentEvent): Option[Cost] = None

  private val claude = TaggedConnector("claude")
  private val codex = TaggedConnector("codex")

  test("Role.Driver and Role.Reviewer expose the wrapped connector via `connector`"):
    val d: Role = Role.Driver(claude)
    val r: Role = Role.Reviewer(codex)
    assertEquals(d.connector.name, "claude")
    assertEquals(r.connector.name, "codex")

  test("every Role is an Agent exposing connector + a stable role tag"):
    // roadmap §4.2 — Driver/Reviewer are now configurations of the `Agent` base.
    val d: Agent = Role.Driver(claude)
    val r: Agent = Role.Reviewer(codex)
    assertEquals(d.role, "driver")
    assertEquals(r.role, "reviewer")
    assertEquals(d.connector.name, "claude")

  test("the role family is open to a third role without a Mode edit (design-3.5 openness)"):
    // The §7.11 sensors are the first concrete third role; constructing one needs no
    // change to `Mode`, proving the §4.2 "base Agent for future roles" claim.
    val s: Agent = Role.Sensor(claude)
    assertEquals(s.role, "sensor")
    assertEquals(s.connector.name, "claude")
