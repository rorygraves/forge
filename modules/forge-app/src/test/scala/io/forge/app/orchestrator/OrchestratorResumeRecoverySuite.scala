package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, ResumeHint, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildState}
import io.forge.git.worktree.WorktreeSafety
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** D3-3 (roadmap §3.5 driver-respawn-avoidance) — the orchestrator's resume-instead-of-respawn on a restart from an
  * in-flight implement/fix-up driver.
  *
  * Both tests share **pass 1**: drive from `Drafting` with the implement-turn monitor outcome left unscripted, so the
  * loop spawns the implementation driver (persisting the `driver.spawn` durability entry) and then crashes when the
  * monitor finds no scripted outcome. A cold `RebuildState.run` then projects the piece as an `InFlightSession`
  * (`PieceImplementing`, `currentPieceSessionId` durable) — the realistic D3 mid-exploration crash.
  *
  * **Pass 2** is the contract:
  *   - **safe worktree** → the loop's resume-gate calls the D3-1 resume seam with the durable session id and drives to
  *     `FeatureDone`, and the implementation driver is **never re-spawned** (the implicit guard from
  *     `OrchestratorPostSettleRecoverySuite`: the pass-2 monitor scripts only the *resumed* turn's settle, so a stray
  *     fresh spawn would itself raise — plus an explicit launch-count assertion);
  *   - **unsafe worktree** (`UnexpectedDivergence`) → the gate routes to `NeedsHumanIntervention`
  *     (`ResolveLocalImplementationChanges`) with no resume and no fresh spawn.
  */
class OrchestratorResumeRecoverySuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-resume-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")
  private val designPr = PrNumber(100)
  private val piecePr = PrNumber(200)
  private val p1 = PieceId("p1")

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** A `FakeSideEffects` that counts implement launches + resumes and reports a configurable worktree verdict. */
  private final class ResumeSideEffects(
      safety: WorktreeSafety,
      implLaunches: Ref[IO, Int],
      implResumes: Ref[IO, Int]
  ) extends FakeSideEffects(designPr, _ => piecePr):
    override def launchImplement(feature: Feature, piece: PieceId): IO[ActiveSession] =
      implLaunches.update(_ + 1) >> super.launchImplement(feature, piece)
    override def resumeImplement(feature: Feature, piece: PieceId, sessionId: String): IO[ActiveSession] =
      implResumes.update(_ + 1) >> super.resumeImplement(feature, piece, sessionId)
    override def classifyPieceWorktree(feature: Feature, piece: PieceId): IO[Either[String, WorktreeSafety]] =
      IO.pure(Right(safety))

  /** Pass 1 — drive from Drafting until the implement driver is spawned, then crash (the implement monitor outcome is
    * unscripted). Asserts the cold rebuild sees exactly one in-flight implement session and one launch.
    */
  private def crashMidImplement(
      paths: ForgePaths,
      specStore: FileSpecStore,
      manifestStore: FileManifestStore,
      baseCache: FileStateCache,
      implLaunches: Ref[IO, Int]
  ): Unit =
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.Drafting)
    val crash = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Spec, SettleOutcome.Clean))
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new ResumeSideEffects(WorktreeSafety.DriverUncommittedOnly, implLaunches, Ref.unsafe[IO, Int](0))
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

    assert(crash.isLeft, s"expected the unscripted-implement-turn crash, got $crash")
    assertEquals(implLaunches.get.unsafeRunSync(), 1, "the implement driver should have been spawned once in pass 1")

    val rebuilt = RebuildState
      .run(featureId, paths, manifestStore, FileActionLog(paths).unsafeRunSync(), baseCache)
      .unsafeRunSync()
    rebuilt match
      case Right(r) =>
        assertEquals(r.feature.state, FsmState.PieceImplementing(p1): FsmState)
        assertEquals(r.feature.currentPieceSessionId, Some("impl-p1"))
        assertEquals(
          r.inFlightSessions,
          Vector(RebuildState.InFlightSession(SessionPhase.Implement, "impl-p1", Some(p1)))
        )
      case Left(e) => fail(s"rebuild failed: $e")

  // ---------------------------------------------------------------------------

  tempFixture.test("safe worktree: a fresh run resumes the implement session → FeatureDone, no re-spawn"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)
    val implLaunches = Ref.unsafe[IO, Int](0)
    val implResumes = Ref.unsafe[IO, Int](0)

    crashMidImplement(paths, specStore, manifestStore, baseCache, implLaunches)

    // --- Pass 2: safe worktree → resume, drive to FeatureDone, no fresh launchImplement. ---
    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      // Only the RESUMED implement turn's settle is scripted; a stray fresh spawn would raise "no scripted outcome".
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean))
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new ResumeSideEffects(WorktreeSafety.DriverUncommittedOnly, implLaunches, implResumes)
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
      log <- logImpl.replay(featureId)
    yield (out, log)).unsafeRunSync()

    assertEquals(out._1.state, FsmState.FeatureDone: FsmState)
    assert(out._1.manifest.pieces.forall(_.status == PieceStatus.Merged), s"manifest: ${out._1.manifest.pieces}")
    assertEquals(implResumes.get.unsafeRunSync(), 1, "the implement session must be resumed exactly once")
    assertEquals(implLaunches.get.unsafeRunSync(), 1, "resume must NOT re-spawn the implement driver (still 1)")
    // The durable timeline records the resume (`driver.resume`), the discriminator a second restart would key on.
    assert(out._2.exists(_.kind == "driver.resume"), "expected a driver.resume durability entry after the resume")
    // D3-4: the resumed turn's settle stamps `session.complete.resumed = true`, the field `forge stats` folds into the
    // gap-#10 re-exploration-avoided saving (distinct from a fresh spawn, whose `session.complete` is resumed=false).
    val resumedSessions = out._2.filter(a =>
      a.kind == "session.complete" && a.payload.objOpt.flatMap(_.get("resumed")).flatMap(_.boolOpt).contains(true)
    )
    assertEquals(resumedSessions.size, 1, s"expected exactly one resumed session.complete, got ${out._2.map(_.kind)}")

  tempFixture.test("unsafe worktree: an UnexpectedDivergence routes to NHI, no resume, no re-spawn"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)
    val implLaunches = Ref.unsafe[IO, Int](0)
    val implResumes = Ref.unsafe[IO, Int](0)

    crashMidImplement(paths, specStore, manifestStore, baseCache, implLaunches)

    // --- Pass 2: unsafe worktree → NHI(ResolveLocalImplementationChanges); empty monitor (a respawn would raise). ---
    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      monitor <- FakeSessionMonitor.make() // empty: a resume or fresh spawn would raise
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new ResumeSideEffects(WorktreeSafety.UnexpectedDivergence, implLaunches, implResumes)
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
      out <- orch.run(featureId)
    yield out).unsafeRunSync()

    out.state match
      case FsmState.NeedsHumanIntervention(_, ResumeHint.ResolveLocalImplementationChanges(pp, _)) =>
        assertEquals(pp, p1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")
    assertEquals(implResumes.get.unsafeRunSync(), 0, "an unsafe worktree must not resume")
    assertEquals(implLaunches.get.unsafeRunSync(), 1, "an unsafe worktree must not re-spawn (still the pass-1 launch)")
