package io.forge.app.monitor

import cats.effect.{IO, Ref}
import fs2.Stream
import io.forge.agents.{AgentEvent, StreamingSession}

/** Minimal [[StreamingSession]] for PR-F unit tests. Records `kill()` invocations into a `Ref` so suites can assert
  * whether the monitor invoked it (per §12 kill discipline: settle-timeout and turn-budget breaches kill, feature- and
  * piece-budget breaches do not).
  *
  * `send`, `answerQuestion`, and `close` are no-ops — SessionMonitor does not call them. `events` is unused (the
  * monitor takes the event stream as a separate parameter to keep the test setup explicit about what's being driven).
  */
final class FakeStreamingSession(
    val killCount: Ref[IO, Int],
    val killed: Ref[IO, Boolean],
    override val sessionId: String = "fake-session"
) extends StreamingSession:
  override def events: Stream[IO, AgentEvent] = Stream.empty
  override def close(): IO[Unit] = IO.unit
  override def kill(): IO[Unit] = killCount.update(_ + 1) *> killed.set(true)
  override def send(input: String): IO[Unit] = IO.unit
  override def answerQuestion(toolUseId: Option[String], answer: String): IO[Unit] = IO.unit

object FakeStreamingSession:
  def make: IO[FakeStreamingSession] =
    for
      count <- Ref.of[IO, Int](0)
      flag <- Ref.of[IO, Boolean](false)
    yield new FakeStreamingSession(count, flag)
