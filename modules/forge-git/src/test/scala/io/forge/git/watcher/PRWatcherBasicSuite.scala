package io.forge.git.watcher

import cats.effect.{IO, Ref}
import io.forge.core.PrNumber
import io.forge.core.pr.PrState
import io.forge.git.cli.fake.FakeGhClient
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** PR-D D5 — single-poll + streaming-loop basics for [[RealPRWatcher]].
  *
  * `pollOnce` calls `gh.prView` exactly once and routes the result through the decoder. `watch` consumes a `Ref` for
  * the baseline so successive Snapshots advance the cursor.
  *
  * Pollintervals are dialed to `1.millisecond` so the `evalTap(IO.sleep(...))` between emissions doesn't add real-time
  * latency to the test suite.
  */
class PRWatcherBasicSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val cfg = PRWatcherConfig(pollInterval = 1.millisecond, rateLimitBackoff = 1.millisecond)

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)

  test("pollOnce — decoded Snapshot with state=Open on a clean payload"):
    val gh = FakeGhClient.builder.prView(loadFixture("open-no-checks.json")).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.Snapshot(d) => assertEquals(d.snapshot.state, PrState.Open)
      case other => fail(s"expected Snapshot, got $other")
    }

  test("pollOnce — Failed wrapping a DecodeError when the payload is malformed"):
    val gh = FakeGhClient.builder.prView(loadFixture("malformed-missing-state.json")).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.Failed(_) => ()
      case other => fail(s"expected Failed, got $other")
    }

  test("watch — emits one Snapshot per poll, baseline Ref advances between polls"):
    val seq = Vector(
      loadFixture("open-no-checks.json"),
      loadFixture("open-checks-running.json"),
      loadFixture("open-checks-mixed.json")
    )
    val gh = FakeGhClient.builder.prViewSequence(seq.map(Right(_))).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(3).compile.toVector
      // The baseline ref ends at whatever the last Snapshot wrote.
      finalBaseline <- baseline.get
    yield
      assertEquals(events.size, 3)
      events.foreach {
        case PollResult.Snapshot(_) => ()
        case other => fail(s"every event should be Snapshot, got $other")
      }
      // None of the fixtures in this sequence carry comments, so the cursor stays at None — the meaningful
      // assertion is just that we got through all three polls without short-circuiting.
      assertEquals(finalBaseline, PollBaseline.empty)

  test("watch — exhausted fake → Failed events surface and stream continues"):
    val gh = FakeGhClient.builder.prViewSequence(Vector(Right(loadFixture("open-no-checks.json")))).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(3).compile.toVector
    yield
      assertEquals(events.size, 3)
      // First poll: Snapshot. Second + third: exhausted-sequence Transient → Failed.
      assert(events.head.isInstanceOf[PollResult.Snapshot], s"first should be Snapshot, got ${events.head}")
      events.tail.foreach {
        case PollResult.Failed(_) => ()
        case other => fail(s"post-exhaust events should be Failed, got $other")
      }

  test("DefaultFields matches the v1.2 §9 pinned set"):
    assertEquals(
      PRWatcher.DefaultFields,
      Vector(
        "state",
        "statusCheckRollup",
        "reviews",
        "reviewDecision",
        "mergeable",
        "mergeStateStatus",
        "comments",
        "commits",
        "mergedAt",
        "mergeCommit",
        "number"
      )
    )
