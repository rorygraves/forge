package io.forge.git.branch

import cats.Applicative
import cats.effect.{Clock, IO, Ref}

import java.time.Instant
import scala.concurrent.duration.{DurationLong, FiniteDuration}

/** Minimal mutable `Clock[IO]` for cache TTL tests. We deliberately *don't* pull in `cats-effect-testkit` here — Slice
  * 3 PR-F (design-2.3 §1.6 F5) adds that dep when SessionMonitor lands; until then, this small fixture is enough for
  * the one-Ref-of-Instant case that the cache needs.
  *
  * Not a test suite; the name follows the file pattern so the test compile picks it up alongside the cache suite.
  */
object FakeClockSuite:

  def fromRef(ref: Ref[IO, Instant]): Clock[IO] = new Clock[IO]:
    override def applicative: Applicative[IO] = IO.asyncForIO
    override def monotonic: IO[FiniteDuration] = realTime
    override def realTime: IO[FiniteDuration] =
      ref.get.map(i => i.getEpochSecond.seconds + i.getNano.toLong.nanos)
    override def realTimeInstant: IO[Instant] = ref.get
