package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.core.*
import io.forge.core.fsm.FsmState
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.git.cli.GhError
import io.forge.git.watcher.PollResult
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Dogfood finding #5 — a transient `gh` server / network blip (e.g. an HTTP 503) on the §9 PR-state poll must be
  * absorbed (keep polling) rather than hard-routing to `NeedsHumanIntervention`. The watcher's promotion-after-N cliff
  * is unit-proven in `PRWatcherTransientSuite`; here we prove the *orchestrator's* end of the contract: a
  * `PollResult.TransientError` keeps the loop polling (no NHI), while a genuinely fatal `PollResult.Failed` still
  * escalates exactly as before.
  */
class OrchestratorTransientPollSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-transient-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A single-piece feature parked at `PieceAwaitingMerge`, mirroring the mid-lifecycle setup of the §8 CI-gate test.
    */
  private def awaitingMergeStart(featureId: FeatureId, p1: PieceId, piecePr: PrNumber) =
    val inProgress = piecePending(p1, 1).copy(
      status = io.forge.core.manifest.PieceStatus.InProgress,
      baseSha = Some(BaseSha),
      prNumber = Some(piecePr)
    )
    val m = mkManifest(featureId, Vector(inProgress))
    featureAt(featureId, m, FsmState.PieceAwaitingMerge(p1, piecePr))

  private def runWith(root: os.Path, start: io.forge.core.fsm.Feature, piecePr: PrNumber)(
      offered: Vector[PollResult]
  ): FsmState =
    val paths = new ForgePaths(repoRoot = root)
    (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- offered.foldLeft(IO.unit)((acc, r) => acc *> watcher.offer(piecePr, r))
      monitor <- FakeSessionMonitor.make()
      orch = new Orchestrator(
        new FakeSideEffects(PrNumber(100), _ => piecePr),
        monitor,
        watcher,
        FakeReviewerCall.happyPath,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        testConfig
      )
      out <- orch.drive(start)
    yield out.state).unsafeRunSync()

  tempFixture.test("a transient 503 poll is absorbed — the loop keeps polling and reaches FeatureDone"): root =>
    val featureId = FeatureId("feat")
    val p1 = PieceId("p1")
    val piecePr = PrNumber(201)
    val start = awaitingMergeStart(featureId, p1, piecePr)
    // First poll is a transient blip (absorbed → keep polling); the second poll sees the PR merged.
    val finalState = runWith(root, start, piecePr)(
      Vector(PollResult.TransientError(GhError.Transient(1, "HTTP 503")), snapshotResult(mergedSnapshot(piecePr)))
    )
    assertEquals(finalState, FsmState.FeatureDone: FsmState)

  tempFixture.test("a fatal poll error still escalates to NeedsHumanIntervention (regression guard)"): root =>
    val featureId = FeatureId("feat")
    val p1 = PieceId("p1")
    val piecePr = PrNumber(202)
    val start = awaitingMergeStart(featureId, p1, piecePr)
    val finalState = runWith(root, start, piecePr)(
      Vector(PollResult.Failed(GhError.Unauthorized("bad credentials")))
    )
    finalState match
      case FsmState.NeedsHumanIntervention(reason, _) => assert(reason.contains("PR poll failed"), reason)
      case other => fail(s"expected NHI on a fatal poll error, got $other")
