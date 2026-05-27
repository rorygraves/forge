package io.forge.git.watcher

import cats.effect.{IO, Ref}
import io.forge.core.PrNumber
import io.forge.core.pr.PrState
import io.forge.git.cli.fake.FakeGhClient
import munit.CatsEffectSuite

import scala.concurrent.duration.DurationInt

/** PR-D D5 — exercise the CI6 trap: a transcript Open → Open → Merged that exposes the `Merged` ingredients via `state
  * \== "MERGED"` + `mergedAt` + `mergeCommit`, **never** consulting `mergeStateStatus`.
  *
  * The `merged-stale-mergestate.json` fixture in particular carries `mergeStateStatus = CLEAN` even though the PR has
  * merged — confirming the decoder doesn't read it.
  */
class PRWatcherMergedDetectionSuite extends CatsEffectSuite:

  private val pr = PrNumber(42)
  private val cfg = PRWatcherConfig(pollInterval = 1.millisecond, rateLimitBackoff = 1.millisecond)

  private def loadFixture(name: String): ujson.Value =
    val url = getClass.getResource(s"/gh-pr-view/$name")
    val raw = scala.io.Source.fromURL(url, "UTF-8").mkString
    ujson.read(raw)

  test("Open → Open → Merged sequence — third Snapshot has state=Merged, mergedAt and mergeCommit non-null"):
    val seq = Vector(
      Right(loadFixture("open-no-checks.json")),
      Right(loadFixture("open-no-checks.json")),
      Right(loadFixture("merged.json"))
    )
    val gh = FakeGhClient.builder.prViewSequence(seq).build
    val watcher = new RealPRWatcher(gh, cfg)
    for
      baseline <- Ref.of[IO, PollBaseline](PollBaseline.empty)
      events <- watcher.watch(pr, baseline).take(3).compile.toVector
    yield
      events(0) match
        case PollResult.Snapshot(d) => assertEquals(d.snapshot.state, PrState.Open)
        case other => fail(s"expected Open Snapshot at #0, got $other")
      events(1) match
        case PollResult.Snapshot(d) => assertEquals(d.snapshot.state, PrState.Open)
        case other => fail(s"expected Open Snapshot at #1, got $other")
      events(2) match
        case PollResult.Snapshot(d) =>
          assertEquals(d.snapshot.state, PrState.Merged)
          assert(d.snapshot.mergedAt.isDefined, "mergedAt should be defined on merged.json")
          assert(d.snapshot.mergeCommit.isDefined, "mergeCommit should be defined on merged.json")
        case other => fail(s"expected Merged Snapshot at #2, got $other")

  test("merged-stale-mergestate.json — `mergeStateStatus` is ignored; state=Merged drives detection (CI6)"):
    val gh = FakeGhClient.builder.prView(Right(loadFixture("merged-stale-mergestate.json"))).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.Snapshot(d) =>
        assertEquals(d.snapshot.state, PrState.Merged)
        assert(d.snapshot.mergedAt.isDefined)
        assert(d.snapshot.mergeCommit.isDefined)
      case other => fail(s"expected Snapshot, got $other")
    }

  test(
    "closed-not-merged.json — state=Closed, mergedAt+mergeCommit null (§11.3 step 3 / §11.5 PR closed without merge)"
  ):
    val gh = FakeGhClient.builder.prView(Right(loadFixture("closed-not-merged.json"))).build
    val watcher = new RealPRWatcher(gh, cfg)
    watcher.pollOnce(pr, PollBaseline.empty).map {
      case PollResult.Snapshot(d) =>
        assertEquals(d.snapshot.state, PrState.Closed)
        assertEquals(d.snapshot.mergedAt, None)
        assertEquals(d.snapshot.mergeCommit, None)
      case other => fail(s"expected Snapshot, got $other")
    }
