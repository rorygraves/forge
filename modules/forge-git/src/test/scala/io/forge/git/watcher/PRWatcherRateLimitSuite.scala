package io.forge.git.watcher

import cats.effect.{IO, Ref}
import io.forge.core.PrNumber
import io.forge.git.cli.GhError
import io.forge.git.cli.fake.FakeGhClient
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** PR-D D5 — rate-limit semantics per design-rationale RL1 + the consecutive-threshold escalation per D3 / S3-4. */
class PRWatcherRateLimitSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val cfg = PRWatcherConfig(
    pollInterval = 1.millisecond,
    rateLimitBackoff = 1.millisecond,
    consecutiveRateLimitsBeforeFailing = 3
  )

  test("pollOnce — RateLimited(Some(d)) on a `gh` RateLimited GhError"):
    val gh = FakeGhClient.builder
      .prView(Left(GhError.RateLimited(Some(5.seconds), "rate limit exceeded")))
      .build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.RateLimited(Some(d)) => assertEquals(d, 5.seconds)
      case other => fail(s"expected RateLimited, got $other")
    }

  test("pollOnce — RateLimited(None) when `gh` didn't expose Retry-After"):
    val gh = FakeGhClient.builder.prView(Left(GhError.RateLimited(None, "secondary rate limit"))).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.RateLimited(None) => ()
      case other => fail(s"expected RateLimited(None), got $other")
    }

  test("pollOnce — other GhError flavours → Failed (NotFound, Unauthorized, Transient)"):
    val cases = Vector[GhError](
      GhError.NotFound("/repos/x/pulls/9999"),
      GhError.Unauthorized("auth"),
      GhError.Transient(1, "boom")
    )
    cases.foldLeft(IO.unit) { (acc, err) =>
      acc *> {
        val gh = FakeGhClient.builder.prView(Left(err)).build
        val watcher = new RealPRWatcher(gh, cfg)
        watcher.pollOnce(pr, PollBaseline.empty).map {
          case PollResult.Failed(e) => assertEquals(e, err)
          case other => fail(s"expected Failed($err), got $other")
        }
      }
    }

  test("watch — three consecutive RateLimited → the third surfaces as Failed(GhError.RateLimited)"):
    val gh = FakeGhClient.builder
      .prView(Left(GhError.RateLimited(None, "rate limit exceeded")))
      .build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(4).compile.toVector
    yield
    // First two polls: RateLimited (counter 1, 2). Third + fourth: Failed(RateLimited) (counter 3, 4 — both fail).
    events.zipWithIndex.foreach {
      case (PollResult.RateLimited(_), i) if i < 2 => ()
      case (PollResult.Failed(_: GhError.RateLimited), i) if i >= 2 => ()
      case (other, i) => fail(s"unexpected event at $i: $other")
    }

  test("watch — Snapshot in the middle resets the consecutive counter"):
    val payload = Right(loadFixture("open-no-checks.json"))
    val rateLimit: Either[GhError, ujson.Value] = Left(GhError.RateLimited(None, "rate limit exceeded"))
    val gh = FakeGhClient.builder
      .prViewSequence(Vector(rateLimit, rateLimit, payload, rateLimit, rateLimit))
      .build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(5).compile.toVector
    yield
      // Without the reset, the 3rd RateLimited would have already crossed the threshold. With the Snapshot reset,
      // counts go: 1 (RL), 2 (RL), 0 (Snapshot reset), 1 (RL), 2 (RL) → no Failed events.
      assert(events(0).isInstanceOf[PollResult.RateLimited], events.toString)
      assert(events(1).isInstanceOf[PollResult.RateLimited], events.toString)
      assert(events(2).isInstanceOf[PollResult.Snapshot], events.toString)
      assert(events(3).isInstanceOf[PollResult.RateLimited], events.toString)
      assert(events(4).isInstanceOf[PollResult.RateLimited], events.toString)

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)
