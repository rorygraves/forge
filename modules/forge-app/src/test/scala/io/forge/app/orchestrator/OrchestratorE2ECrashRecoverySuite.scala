package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Task 1.4.10 J5 — crash + recovery. Drives the happy path but injects a crash in the §11.5-step-1 merge window
  * (manifest written `merged`, but the `audit.piece_merged` action-log append fails). A fresh `run` then rebuilds from
  * the persisted files: `RebuildState.reconcile` synthesises the missing `audit.piece_merged` and advances the feature
  * through `Refining` to `FeatureDone`. This exercises the genuine S2-5 writer/reader invariant against real `File*`
  * stores.
  */
class OrchestratorE2ECrashRecoverySuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-crash-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  tempFixture.test("crash in the merge window is recovered by reconcile on the next run → FeatureDone"): root =>
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

    // --- Pass 1: drive the full flow with a log that crashes when the merge audit is appended. ---
    val crash = (for
      faultLog <- FileActionLog(paths).map(new FaultOnMergeAuditLog(_))
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(
        MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean),
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
      )
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, _ => piecePr)
      hookCache = new HookStateCache(baseCache, f => offerMergeOnAwaitingMerge(watcher, f))
      orch1 = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        faultLog,
        hookCache,
        paths,
        testConfig
      )
      result <- orch1.drive(start).attempt
    yield result).unsafeRunSync()

    assert(crash.isLeft, s"expected the injected merge-window crash, got $crash")

    // The manifest was persisted `merged` before the crash (S2-5 writer order: manifest then log).
    val mergedManifest = manifestStore.load(featureId).unsafeRunSync()
    assert(
      mergedManifest.exists(_.pieces.forall(_.status == PieceStatus.Merged)),
      s"manifest not merged: $mergedManifest"
    )
    // …but the action log is missing the merge audit (the crash fired before it was appended).
    val logBefore = FileActionLog(paths).flatMap(_.replay(featureId)).unsafeRunSync()
    assert(!logBefore.exists(_.kind == "audit.piece_merged"), "merge audit should be absent before recovery")

    // --- Pass 2: a fresh run rebuilds from the files; reconcile fills the gap and the loop completes. ---
    val (recovered, logAfter) = (for
      cleanLog <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      monitor <- FakeSessionMonitor.make()
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, _ => piecePr)
      orch2 = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        reviewer,
        specStore,
        manifestStore,
        cleanLog,
        baseCache,
        paths,
        testConfig
      )
      out <- orch2.run(featureId)
      tail <- cleanLog.replay(featureId)
    yield (out, tail)).unsafeRunSync()

    assertEquals(recovered.state, FsmState.FeatureDone: FsmState)
    assertEquals(
      logAfter.count(_.kind == "audit.piece_merged"),
      1,
      "reconcile should synthesise exactly one merge audit"
    )
