package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.app.config.ForgeConfig
import io.forge.core.*
import io.forge.core.fsm.FsmState
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.git.watcher.{BaselineCursor, PRWatcher, PollBaseline, PollResult}
import io.forge.specs.FileSpecStore

import java.time.Instant

import OrchestratorTestKit.*

/** Task 1.4.10-d2c (S4-1) — proves the loop persists the PRWatcher cursor after a Snapshot poll. A watcher that
  * advances the baseline `Ref` before emitting a merged design snapshot must leave that cursor on disk under
  * `ForgePaths.pollBaselineFile`, keyed by the watched PR.
  */
class OrchestratorPollBaselinePersistenceSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-baseline-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** Sets `baseline` to a non-empty cursor, then emits one merged snapshot — mirroring `RealPRWatcher.stepOnce`'s
    * "advance the ref before emitting the Snapshot" ordering.
    */
  private final class BaselineAdvancingWatcher(pr: PrNumber, advanced: PollBaseline) extends PRWatcher:
    override def watch(p: PrNumber, baseline: Ref[IO, PollBaseline]): Stream[IO, PollResult] =
      Stream.exec(baseline.set(advanced)) ++ Stream.emit(snapshotResult(mergedSnapshot(pr)))
    override def pollOnce(p: PrNumber, baseline: PollBaseline): IO[PollResult] =
      IO.pure(snapshotResult(mergedSnapshot(pr)))

  tempFixture.test("the design-PR poll cursor is persisted after the watcher settles"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val featureId = FeatureId("feat")
    val designPr = PrNumber(100)
    // No pieces: a merged design PR advances to DesignReady, whose entry hook (no pending pieces) routes to NHI — a
    // loop-terminal state, so the run ends right after the single watcher poll we care about.
    val m = mkManifest(featureId, Vector.empty)
    val start = featureAt(featureId, m, FsmState.DesignAwaitingMerge(designPr))
    val advanced = PollBaseline(Some(BaselineCursor(Instant.parse("2026-05-29T12:00:00Z"), Set("c7"))), None, Set.empty)

    val out = (for
      logImpl <- FileActionLog(paths)
      monitor <- FakeSessionMonitor.make()
      orch = new Orchestrator(
        new FakeSideEffects(designPr, _ => PrNumber(0)),
        monitor,
        new BaselineAdvancingWatcher(designPr, advanced),
        FakeReviewerCall.happyPath,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        ForgeConfig.Default
      )
      result <- orch.drive(start)
      persisted <- new PollBaselineStore(paths).load(featureId, designPr)
    yield (result.state, persisted)).unsafeRunSync()

    // Loop reached a terminal state (NHI from the no-pieces DesignReady entry hook).
    assert(out._1.isInstanceOf[FsmState.NeedsHumanIntervention], s"expected NHI, got ${out._1}")
    // And the advanced cursor was written to the per-feature baseline file under the design PR's key.
    assertEquals(out._2, advanced)
    assert(os.exists(paths.pollBaselineFile(featureId)), "poll-baselines.json should exist")
