package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmEvent, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildState}
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** roadmap §3.5 Unit B — the `monitor.outcome` writer + the effectful post-settle recovery step.
  *
  * Two halves of the same contract:
  *   - the **writer** records a `monitor.outcome` marker (piece-keyed) when a piece driver settles clean, and ONLY for
  *     the piece-driver settles — the Spec settle in the same run writes none;
  *   - **recovery** turns that marker into progress: a crash in the post-settle window (driver settled, marker written,
  *     process died before the `PrOpened` transition persisted) is recovered by a fresh `run` re-running the idempotent
  *     `classifyCommitOpenPr` and advancing the FSM, WITHOUT re-spawning the implementation driver (the D3 cost the
  *     unit exists to avoid). The implicit guard: the pass-2 `FakeSessionMonitor` is scripted empty, so a stray
  *     re-spawn would raise "no scripted outcome" rather than pass silently.
  */
class OrchestratorPostSettleRecoverySuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-postsettle-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** A `FakeSideEffects` that counts implementation-driver launches and can fail `classifyCommitOpenPr` once (toggled
    * off after the crash so the pass-2 recovery succeeds).
    */
  private final class RecoverySideEffects(
      designPr: PrNumber,
      piecePr: PrNumber,
      failOpenPr: Ref[IO, Boolean],
      implLaunches: Ref[IO, Int]
  ) extends FakeSideEffects(designPr, _ => piecePr):
    override def launchImplement(feature: Feature, piece: PieceId): IO[ActiveSession] =
      implLaunches.update(_ + 1) >> super.launchImplement(feature, piece)
    override def classifyCommitOpenPr(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
      failOpenPr.get.flatMap {
        case true => IO.raiseError(new RuntimeException("injected crash after monitor.outcome, before PrOpened"))
        case false => super.classifyCommitOpenPr(feature, piece)
      }

  // ---------------------------------------------------------------------------

  tempFixture.test("writer: a clean Implement settle records exactly one monitor.outcome marker (Spec settle none)"):
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

      val (out, markers) = (for
        logImpl <- FileActionLog(paths)
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
          logImpl,
          hookCache,
          paths,
          testConfig
        )
        out <- orch.drive(start)
        log <- logImpl.replay(featureId)
      yield (out, log.filter(_.kind == RebuildState.MonitorOutcomeKind))).unsafeRunSync()

      assertEquals(out.state, FsmState.FeatureDone: FsmState)
      // The Spec settle is NOT marked (design phases keep the conservative in-flight → NHI behaviour); only the
      // Implement settle writes a marker, piece-keyed so the projections line up.
      assertEquals(markers.size, 1, s"expected exactly one monitor.outcome marker, got $markers")
      assertEquals(markers.head.piece, Some(p1))
      assertEquals(markers.head.payload.objOpt.flatMap(_.get("phase")).flatMap(_.strOpt), Some("Implement"))

  tempFixture.test("recovery: crash after the marker is re-run on next run → FeatureDone, no driver re-spawn"): root =>
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

    val failOpenPr = Ref.unsafe[IO, Boolean](true)
    val implLaunches = Ref.unsafe[IO, Int](0)

    // --- Pass 1: drive from Drafting; the implement post-settle classifyCommitOpenPr raises (crash). ---
    val crash = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
      monitor <- FakeSessionMonitor.make(
        MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean),
        MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
      )
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new RecoverySideEffects(designPr, piecePr, failOpenPr, implLaunches)
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
        testConfig
      )
      r <- orch.drive(start).attempt
    yield r).unsafeRunSync()

    assert(crash.isLeft, s"expected the injected post-settle crash, got $crash")
    val launchesPass1 = implLaunches.get.unsafeRunSync()
    assertEquals(launchesPass1, 1, "the implement driver should have been spawned exactly once in pass 1")

    // The log shows the implement spawn + a monitor.outcome marker, but no PrOpened transition (crash before it).
    val logBefore = FileActionLog(paths).flatMap(_.replay(featureId)).unsafeRunSync()
    assert(logBefore.exists(_.kind == RebuildState.MonitorOutcomeKind), "marker should be on disk after the crash")

    // RebuildState projects the piece as settled-but-unadvanced (not in-flight → NHI).
    val rebuilt = RebuildState
      .run(featureId, paths, manifestStore, FileActionLog(paths).unsafeRunSync(), baseCache)
      .unsafeRunSync()
    rebuilt match
      case Right(r) =>
        assertEquals(r.feature.state, FsmState.PieceImplementing(p1): FsmState)
        assertEquals(r.inFlightSessions, Vector.empty)
        assertEquals(
          r.settledButUnadvanced,
          Vector(RebuildState.SettledSession(SessionPhase.Implement, "impl-p1", Some(p1)))
        )
      case Left(e) => fail(s"rebuild failed: $e")

    // --- Pass 2: a fresh run recovers by re-running classifyCommitOpenPr (now succeeds) without re-spawning. ---
    failOpenPr.set(false).unsafeRunSync()
    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make() // empty: a re-spawn would raise "no scripted outcome"
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new RecoverySideEffects(designPr, piecePr, failOpenPr, implLaunches)
      hookCache = new HookStateCache(baseCache, f => offerMergeOnAwaitingMerge(watcher, f))
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
      out <- orch.run(featureId)
    yield out).unsafeRunSync()

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assert(out.manifest.pieces.forall(_.status == PieceStatus.Merged), s"manifest: ${out.manifest.pieces}")
    // The whole point of Unit B: recovery advanced via the idempotent side effect, so the implement driver was NEVER
    // re-spawned in pass 2 (the count is still the single pass-1 launch).
    assertEquals(implLaunches.get.unsafeRunSync(), 1, "post-settle recovery must not re-spawn the implement driver")
