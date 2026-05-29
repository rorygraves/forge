package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildState}
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Task 1.4.11 (S2-5) — the **writer-side** atomic-merge ordering test. Slice 1.2 PR-F's `F13AtomicMergeCrashSuite`
  * pinned the *reader* side (that `RebuildState.reconcile` recovers the four §11.5 sub-cases); this suite pins the
  * *writer* side it deferred: that the orchestrator persists in the §11.5-step-1 order — `manifest.json` **first**, the
  * FSM-transition + audit action-log entries **second**, the state cache **third** — so a crash in the merge window
  * leaves the three files in exactly the divergent shape `reconcile` is built to repair.
  *
  * Distinct from `OrchestratorE2ECrashRecoverySuite` (which drives the whole loop through to `FeatureDone`): this test
  * pins the precise three-way divergence at the crash instant — most importantly that the **state cache lags behind the
  * manifest** (it still holds the pre-transition `PieceAwaitingMerge` state, because `cache.save` runs only after the
  * audit append that crashed) — then restarts via `RebuildState.run` *directly* and asserts recovery to `Refining`.
  */
class OrchestratorAtomicMergeSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-atomic-merge-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  tempFixture.test("writer order: manifest persists `merged` before the cache; cache lags at PieceAwaitingMerge"):
    root =>
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

      // --- Pass 1: drive Drafting → … → PieceAwaitingMerge, then crash in the §11.5-step-1 merge window. The fault log
      // raises on the `audit.piece_merged` append, so the manifest is already written `merged` but neither the action-log
      // batch nor the (later) state-cache save lands. ---
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
        orch = new Orchestrator(
          sideEffects,
          monitor,
          watcher,
          reviewer,
          specStore,
          manifestStore,
          faultLog,
          hookCache,
          paths,
          ForgeConfig.Default
        )
        result <- orch.drive(start).attempt
      yield result).unsafeRunSync()

      assert(crash.isLeft, s"expected the injected merge-window crash, got $crash")

      // Writer step 1 — the manifest was persisted `merged` first (atomic temp+rename+fsync via SpecStore.saveManifest).
      val mergedManifest = manifestStore.load(featureId).unsafeRunSync()
      assert(
        mergedManifest.exists(_.pieces.forall(_.status == PieceStatus.Merged)),
        s"manifest not merged: $mergedManifest"
      )

      // Writer step 2 — the action-log batch never landed: the crash fired on the `audit.piece_merged` append, and the
      // paired `fsm.transition` is in the same atomic batch (`appendAll` writes all-or-nothing), so neither is present.
      // The audit's absence is asserted directly here; the paired transition's absence is proven in pass 2, where the log
      // folds to `PieceAwaitingMerge` and reconcile takes the three-draft case (c) rather than the two-draft case (b).
      val logBefore = FileActionLog(paths).flatMap(_.replay(featureId)).unsafeRunSync()
      assert(!logBefore.exists(_.kind == "audit.piece_merged"), "merge audit should be absent before recovery")

      // Writer step 3 — the **S2-5 invariant this suite pins**: `cache.save` runs only after the (crashed) audit append,
      // so the state cache lags BEHIND the manifest. It still holds the PRE-transition `PieceAwaitingMerge` state.
      val cachedAtCrash = baseCache.load(featureId).unsafeRunSync()
      assertEquals(cachedAtCrash.map(_.state), Some(FsmState.PieceAwaitingMerge(p1, piecePr): FsmState))

      // --- Pass 2: restart via `RebuildState.run` directly (not the full loop). The §11.5 reconcile case (c) — manifest
      // merged, but neither transition nor audit logged, fold-state = PieceAwaitingMerge — synthesises the missing
      // fsm.transition + audit.piece_merged + harness.crash_recovered and advances the FSM to `Refining`. ---
      val (rebuilt, logAfter) = (for
        cleanLog <- FileActionLog(paths)
        out <- RebuildState.run(featureId, paths, manifestStore, cleanLog, baseCache)
        tail <- cleanLog.replay(featureId)
      yield (out, tail)).unsafeRunSync()

      rebuilt match
        case Right(RebuildState.RebuildResult(f, inFlight)) =>
          f.state match
            case FsmState.Refining(p, pr, _) =>
              assertEquals(p, p1)
              assertEquals(pr, piecePr)
            case other => fail(s"expected Refining after reconcile, got $other")
          assertEquals(inFlight, Vector.empty, "recovered to Refining — no live driver session")
        case Left(err) => fail(s"expected Right after reconcile, got Left($err)")

      assertEquals(
        logAfter.count(_.kind == "audit.piece_merged"),
        1,
        "reconcile should synthesise exactly one merge audit"
      )
      assert(
        logAfter.map(_.kind).containsSlice(Vector("fsm.transition", "audit.piece_merged", "harness.crash_recovered")),
        s"expected case-(c) repair drafts at the log tail, got ${logAfter.map(_.kind)}"
      )

      // The cache now reflects the recovered `Refining` state (reconcile's pipeline-step-6 save).
      baseCache.load(featureId).unsafeRunSync().map(_.state) match
        case Some(FsmState.Refining(_, _, _)) => ()
        case other => fail(s"cache should reflect Refining after reconcile, got $other")
