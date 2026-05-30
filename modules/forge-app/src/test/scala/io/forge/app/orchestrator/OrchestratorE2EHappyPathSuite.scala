package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Task 1.4.10 J5 — the full happy-path e2e: drive a feature from `Drafting` all the way to `FeatureDone` through the
  * spec, design-review, design-PR, and one-piece-implementation phases, against scripted fakes + real `File*` stores in
  * a temp tree. Asserts the loop reaches `FeatureDone` and visits every documented §11 state in order.
  */
class OrchestratorE2EHappyPathSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-e2e-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: io.forge.core.fsm.Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  tempFixture.test("happy path: Drafting → FeatureDone (spec + design review + design PR + one piece)"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)

    val featureId = FeatureId("feat")
    val designPr = PrNumber(100)
    val piecePr = PrNumber(200)
    val p1 = PieceId("p1")
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.Drafting)

    val (out, states) = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      recorded <- Ref.of[IO, Vector[FsmState]](Vector.empty)
      _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(
        MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean),
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
      )
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, _ => piecePr)
      hookCache = new HookStateCache(
        baseCache,
        f => recorded.update(_ :+ f.state) >> offerMergeOnAwaitingMerge(watcher, f)
      )
      orch = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        logImpl,
        hookCache,
        paths,
        testConfig
      )
      out <- orch.drive(start)
      states <- recorded.get
    yield (out, states)).unsafeRunSync()

    assertEquals(out.state, FsmState.FeatureDone: FsmState)

    val expectedInOrder = Vector(
      FsmState.InteractiveSpec,
      FsmState.DesignReviewing(1),
      FsmState.DesignAwaitingMerge(designPr),
      FsmState.DesignReady,
      FsmState.PieceImplementing(p1),
      FsmState.PieceAwaitingCi(p1, piecePr),
      FsmState.PieceAwaitingReview(p1, piecePr),
      FsmState.PieceAwaitingMerge(p1, piecePr),
      FsmState.FeatureDone
    )
    // Each expected milestone appears, and in the documented order (subsequence of the recorded saves).
    val idxs = expectedInOrder.map(s => states.indexOf(s))
    assert(
      idxs.forall(_ >= 0),
      s"missing milestone(s): ${expectedInOrder.zip(idxs).filter(_._2 < 0).map(_._1)} in $states"
    )
    assertEquals(idxs, idxs.sorted, s"milestones out of order: $states")

    // The just-merged piece is persisted as merged in the manifest (§11.5 step 1).
    assert(
      out.manifest.pieces.forall(_.status == io.forge.core.manifest.PieceStatus.Merged),
      s"manifest: ${out.manifest.pieces}"
    )

  tempFixture.test("§8 CI gate: no checks discovered after the window → NHI(ResumeAfterHumanPush)"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)

    val featureId = FeatureId("feat")
    val p1 = PieceId("p1")
    val piecePr = PrNumber(201)
    val inProgress = piecePending(p1, 1).copy(
      status = io.forge.core.manifest.PieceStatus.InProgress,
      baseSha = Some(BaseSha),
      prNumber = Some(piecePr)
    )
    val m = mkManifest(featureId, Vector(inProgress))
    val start = featureAt(featureId, m, FsmState.PieceAwaitingCi(p1, piecePr))
    // checkDiscoveryTimeoutSec = 0 → the second poll (monotonic has advanced past 0) trips §8 rule 1.
    val cfg = testConfig.copy(ci = testConfig.ci.copy(checkDiscoveryTimeoutSec = 0))

    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      // two observed-empty snapshots: the first stamps the discovery anchor, the second trips the timeout.
      _ <- watcher.offer(piecePr, snapshotResult(openSnapshot(piecePr)))
      _ <- watcher.offer(piecePr, snapshotResult(openSnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make()
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(PrNumber(100), _ => piecePr)
      orch = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        logImpl,
        baseCache,
        paths,
        cfg
      )
      out <- orch.drive(start)
    yield out).unsafeRunSync()

    out.state match
      case FsmState.NeedsHumanIntervention(reason, io.forge.core.fsm.ResumeHint.ResumeAfterHumanPush(p, pr)) =>
        assertEquals(p, p1)
        assertEquals(pr, piecePr)
        assert(reason.contains("no CI checks discovered"), s"unexpected reason: $reason")
      case other => fail(s"expected NHI(ResumeAfterHumanPush), got $other")

  tempFixture.test("two pieces: Refining advances to the next PieceImplementing, then FeatureDone"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)

    val featureId = FeatureId("feat")
    val designPr = PrNumber(100)
    val p1 = PieceId("p1")
    val p2 = PieceId("p2")
    val p1Pr = PrNumber(201)
    val p2Pr = PrNumber(202)
    val prFor: PieceId => PrNumber = p => if p == p1 then p1Pr else p2Pr
    val m = mkManifest(featureId, Vector(piecePending(p1, 1), piecePending(p2, 2)))
    val start = featureAt(featureId, m, FsmState.DesignReady)

    val (out, states) = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      recorded <- Ref.of[IO, Vector[FsmState]](Vector.empty)
      _ <- watcher.offer(p1Pr, snapshotResult(ciReadySnapshot(p1Pr)))
      _ <- watcher.offer(p2Pr, snapshotResult(ciReadySnapshot(p2Pr)))
      monitor <- FakeSessionMonitor.make(
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean),
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
      )
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, prFor)
      hookCache = new HookStateCache(
        baseCache,
        f => recorded.update(_ :+ f.state) >> offerMergeOnAwaitingMerge(watcher, f)
      )
      orch = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        logImpl,
        hookCache,
        paths,
        testConfig
      )
      out <- orch.drive(start)
      states <- recorded.get
    yield (out, states)).unsafeRunSync()

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    // Both pieces were implemented in order, and the second was reached via the Refining→next advance.
    assert(states.contains(FsmState.PieceImplementing(p1)), s"missing p1 implement: $states")
    assert(states.exists { case _: FsmState.Refining => true; case _ => false }, s"missing Refining: $states")
    assert(
      states.indexOf(FsmState.PieceImplementing(p2)) > states.indexOf(FsmState.PieceImplementing(p1)),
      s"p2 should follow p1: $states"
    )
    assert(
      out.manifest.pieces.forall(_.status == io.forge.core.manifest.PieceStatus.Merged),
      s"manifest: ${out.manifest.pieces}"
    )
