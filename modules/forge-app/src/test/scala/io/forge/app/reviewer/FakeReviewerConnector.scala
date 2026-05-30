package io.forge.app.reviewer

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.*
import io.forge.core.{QuestionMechanism, SchemaMechanism}
import io.forge.core.cost.Cost

/** Minimal [[Connector]] for [[ReviewerCall]] unit tests. Each of the three reviewer methods is backed by a
  * programmable `IO` so individual cases can install `IO.never` (timeout), `IO.pure(x)` (clean settle), or
  * `IO.raiseError(...)` (adapter / process failure). Driver methods are unused by [[ReviewerCall]] and return a no-op
  * session so the trait is implementable.
  *
  * `cancelled` flips to `true` when the reviewer IO is cancelled (via the `IO.race` loser path). Asserted by
  * [[ReviewerCallWallClockSuite]] in lieu of an observable kill channel — there is none on [[ReviewerOutcome.Timeout]]
  * (see [[ReviewerCall]] docstring + carry-forward S4-3).
  */
final class FakeReviewerConnector(
    designReviewIO: IO[DesignReview],
    prReviewIO: IO[PrReview],
    refineIO: IO[RefineResult],
    val cancelled: Ref[IO, Boolean]
) extends Connector:

  override val name: String = "fake"
  override val questionMechanism: QuestionMechanism = QuestionMechanism.Native
  override val schemaMechanism: SchemaMechanism = SchemaMechanism.Native

  private object NoopSession extends StreamingSession:
    override val sessionId: String = "fake-session"
    override val events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] = IO.unit
    override def send(input: String): IO[Unit] = IO.unit
    override def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

  override def runStreamingSpec(systemPromptPath: os.Path, initialUserMessage: String): IO[StreamingSession] =
    IO.pure(NoopSession)
  override def resumeStreamingSpec(
      sessionId: String,
      systemPromptPath: os.Path,
      message: String
  ): IO[StreamingSession] =
    IO.pure(NoopSession)
  override def runHeadlessImplementation(prompt: ImplementationPrompt): IO[AgentSession] = IO.pure(NoopSession)
  override def runFixup(prompt: FixupPrompt): IO[AgentSession] = IO.pure(NoopSession)

  override def reviewDesign(input: DesignReviewInput): IO[DesignReview] =
    designReviewIO.onCancel(cancelled.set(true))
  override def reviewPr(input: PrReviewInput): IO[PrReview] =
    prReviewIO.onCancel(cancelled.set(true))
  override def refine(input: RefineInput): IO[RefineResult] =
    refineIO.onCancel(cancelled.set(true))

  override def costFrom(event: AgentEvent): Option[Cost] = None

object FakeReviewerConnector:
  def make(
      designReviewIO: IO[DesignReview] = IO.raiseError(new IllegalStateException("designReviewIO not programmed")),
      prReviewIO: IO[PrReview] = IO.raiseError(new IllegalStateException("prReviewIO not programmed")),
      refineIO: IO[RefineResult] = IO.raiseError(new IllegalStateException("refineIO not programmed"))
  ): IO[FakeReviewerConnector] =
    Ref.of[IO, Boolean](false).map { cancelled =>
      new FakeReviewerConnector(designReviewIO, prReviewIO, refineIO, cancelled)
    }
