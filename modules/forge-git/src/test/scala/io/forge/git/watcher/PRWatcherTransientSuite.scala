package io.forge.git.watcher

import cats.effect.{IO, Ref}
import io.forge.core.PrNumber
import io.forge.git.cli.GhError
import io.forge.git.cli.fake.FakeGhClient
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** Dogfood finding #5 — transient `gh` server / network blips (e.g. an HTTP 503) on the §9 PR-state poll must be a
  * soft, retry-worthy back-off signal rather than a hard failure that routes to `NeedsHumanIntervention`. Mirrors
  * [[PRWatcherRateLimitSuite]]: a single (or few) transient(s) surface as [[PollResult.TransientError]] and the watcher
  * keeps polling; only after [[PRWatcherConfig.consecutiveTransientFailuresBeforeFailing]] consecutive transients does
  * it promote to [[PollResult.Failed]] so a genuinely-down GitHub eventually escalates.
  */
class PRWatcherTransientSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val cfg = PRWatcherConfig(
    pollInterval = 1.millisecond,
    rateLimitBackoff = 1.millisecond,
    transientBackoff = 1.millisecond,
    consecutiveRateLimitsBeforeFailing = 3,
    consecutiveTransientFailuresBeforeFailing = 3
  )

  test("pollOnce — a GhError.Transient (the 503 bucket) → TransientError, not Failed"):
    val gh = FakeGhClient.builder.prView(Left(GhError.Transient(1, "HTTP 503: Service Unavailable"))).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.TransientError(GhError.Transient(1, raw)) => assert(raw.contains("503"))
      case other => fail(s"expected TransientError(Transient), got $other")
    }

  test("watch — three consecutive transients → the third surfaces as Failed (escalation cliff)"):
    val gh = FakeGhClient.builder.prView(Left(GhError.Transient(1, "boom 503"))).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(4).compile.toVector
    yield
    // First two polls: TransientError (counter 1, 2). Third + fourth: Failed (counter 3, 4 — both promoted).
    events.zipWithIndex.foreach {
      case (PollResult.TransientError(_), i) if i < 2 => ()
      case (PollResult.Failed(_), i) if i >= 2 => ()
      case (other, i) => fail(s"unexpected event at $i: $other")
    }

  test("watch — a Snapshot between transients resets the consecutive counter (a single 503 is absorbed)"):
    val payload = Right(loadFixture("open-no-checks.json"))
    val transient: Either[GhError, ujson.Value] = Left(GhError.Transient(1, "HTTP 503"))
    val gh = FakeGhClient.builder
      .prViewSequence(Vector(transient, transient, payload, transient, transient))
      .build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(5).compile.toVector
    yield
      // Without the reset, the 3rd transient would have crossed the threshold. With the Snapshot reset, counts go:
      // 1 (T), 2 (T), 0 (Snapshot reset), 1 (T), 2 (T) → no Failed events.
      assert(events(0).isInstanceOf[PollResult.TransientError], events.toString)
      assert(events(1).isInstanceOf[PollResult.TransientError], events.toString)
      assert(events(2).isInstanceOf[PollResult.Snapshot], events.toString)
      assert(events(3).isInstanceOf[PollResult.TransientError], events.toString)
      assert(events(4).isInstanceOf[PollResult.TransientError], events.toString)

  test("watch — a rate-limit between transients resets the transient counter (the cliffs are independent)"):
    val transient: Either[GhError, ujson.Value] = Left(GhError.Transient(1, "HTTP 503"))
    val rateLimit: Either[GhError, ujson.Value] = Left(GhError.RateLimited(None, "rate limit exceeded"))
    val gh = FakeGhClient.builder
      .prViewSequence(Vector(transient, transient, rateLimit, transient, transient))
      .build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(5).compile.toVector
    yield
      // Transient counts: 1, 2, (reset by RL), 1, 2 — and the lone RL is counter 1, well under its own cliff of 3.
      // No Failed events: the two cliffs don't pool.
      assert(events.forall(e => !e.isInstanceOf[PollResult.Failed]), events.toString)
      assert(events(2).isInstanceOf[PollResult.RateLimited], events.toString)

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)
