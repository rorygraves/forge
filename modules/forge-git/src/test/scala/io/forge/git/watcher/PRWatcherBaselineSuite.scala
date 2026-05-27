package io.forge.git.watcher

import cats.effect.{IO, Ref}
import io.forge.core.PrNumber
import io.forge.git.cli.fake.FakeGhClient
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** PR-D D5 — baseline RL2 contract: feed the same `gh pr view` payload twice and assert the second pass surfaces zero
  * unseen comments (the cursor advanced after the first decode).
  */
class PRWatcherBaselineSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val cfg = PRWatcherConfig(pollInterval = 1.millisecond, rateLimitBackoff = 1.millisecond)

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)

  test("first poll sees comments; second poll on the same payload sees none (RL2)"):
    val payload = loadFixture("open-with-comments.json")
    val gh = FakeGhClient.builder.prViewSequence(Vector(Right(payload), Right(payload))).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(2).compile.toVector
    yield
      val firsts = events(0) match
        case PollResult.Snapshot(d) => d.snapshot.unseenComments
        case other => fail(s"expected Snapshot, got $other")
      val seconds = events(1) match
        case PollResult.Snapshot(d) => d.snapshot.unseenComments
        case other => fail(s"expected Snapshot, got $other")
      assert(firsts.nonEmpty, "fixture must seed at least one comment for this assertion to be meaningful")
      assertEquals(seconds, Vector.empty)

  test("baseline Ref ends up at the last Snapshot's nextBaseline"):
    val payload = loadFixture("open-with-comments.json")
    val gh = FakeGhClient.builder.prView(Right(payload)).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(1).compile.toVector
      ended <- baseline.get
    yield
      val expected = events.head match
        case PollResult.Snapshot(d) => d.nextBaseline
        case other => fail(s"expected Snapshot, got $other")
      assertEquals(ended, expected)

  test("pollOnce — caller-controlled baseline, no Ref mutation"):
    val payload = loadFixture("open-with-comments.json")
    val gh = FakeGhClient.builder.prView(Right(payload)).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      // Manually thread the baseline: first call uses empty, second call uses the cursor returned from the first.
      first <- watcher.pollOnce(pr, PollBaseline.empty)
      firstBaseline = first match
        case PollResult.Snapshot(d) => d.nextBaseline
        case other => fail(s"expected Snapshot, got $other")
      second <- watcher.pollOnce(pr, firstBaseline)
    yield second match
      case PollResult.Snapshot(d) => assertEquals(d.snapshot.unseenComments, Vector.empty)
      case other => fail(s"expected Snapshot, got $other")
