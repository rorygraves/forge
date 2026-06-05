package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.MonitorOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmEvent, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.*
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import java.util.concurrent.atomic.AtomicInteger

import OrchestratorTestKit.*

/** design-3.3 W3 — the trunk-based / no-PR lifecycle path, proven end-to-end with scripted fakes (the
  * `OrchestratorBuildGateSuite` precedent: there is no real `TrunkBased` fixture repo to drive — D5). A piece on a repo
  * whose `WorkflowProfile.branchModel` is `TrunkBased` (with `adapt.workflowGate` on) commits **straight to trunk** and
  * advances `PieceImplementing → Refining → FeatureDone` with **no PR opened, no CI poll, no reviewer one-shot** — the
  * pre-PR `PieceAwaitingCi` / `PieceAwaitingReview` / `PieceAwaitingMerge` tail is skipped. The local Build gate (§8.3)
  * still runs: a trunk repo must not push a broken compile to mainline, so a build failure still routes to a pre-PR
  * driver fix-up before the piece integrates.
  *
  * The FSM stays profile-agnostic throughout (it routes purely on the neutral `CommittedToTrunk` event); the branch
  * model decision lives in the orchestrator (`shouldCommitToTrunk`, gated on `adapt.workflowGate`). `GitFlow` /
  * gate-off / unprofiled runs keep the byte-identical PR path — covered by the rest of the orchestrator e2e suites.
  */
class OrchestratorTrunkPathSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-trunk-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A `TrunkBased` profile declaring `sbt compile` as the required deterministic non-autofix Build command (so the
    * local Build gate runs pre-integration). `reviewRequired` is irrelevant under trunk — the whole review tail is
    * skipped — but set `true` to prove it is the branch model, not review-skip, that removes the PR.
    */
  private val trunkProfile: RepoProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector(
      RepoCommand(
        CommandKind.Build,
        Vector("sbt", "compile"),
        Determinism.Deterministic,
        required = true,
        autofix = false
      )
    ),
    commitIdentity = CommitIdentity("Forge", "forge@example.com"),
    workflow = WorkflowProfile(
      reviewRequired = true,
      ciRequiredChecks = Vector("ci"),
      branchModel = BranchModel.TrunkBased,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  private val compileError: String =
    """[error] -- [E007] Type Mismatch Error: Foo.scala:10:5
      |[error] 10 |  val n: Int = "not an int"
      |[error] one error found""".stripMargin

  private def profileStoreOf(p: Option[RepoProfile]): ProfileStore = new ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(p)
    def save(profile: RepoProfile): IO[Unit] = IO.unit

  /** Records the two integration paths so a test can prove the trunk path was taken and the PR path was not. A
    * `failTimes`-aware Build gate (mirroring `OrchestratorBuildGateSuite`) lets the build-fail variant route to a
    * pre-PR fix-up before the piece integrates to trunk.
    */
  private class TrunkSideEffects(
      failTimes: Int,
      buildCalls: AtomicInteger,
      trunkCommits: AtomicInteger,
      openPrCalls: AtomicInteger,
      fixupLaunches: AtomicInteger
  ) extends FakeSideEffects(PrNumber(100), _ => PrNumber(999)):
    override def runLocalBuildGate(
        feature: Feature,
        piece: PieceId,
        commands: Vector[RepoCommand]
    ): IO[Either[String, Unit]] =
      IO(if buildCalls.incrementAndGet() <= failTimes then Left(compileError) else Right(()))
    override def commitToTrunk(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
      IO(trunkCommits.incrementAndGet()) >> super.commitToTrunk(feature, piece)
    override def classifyCommitOpenPr(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
      IO(openPrCalls.incrementAndGet()) >> super.classifyCommitOpenPr(feature, piece)
    override def launchBuildFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession] =
      IO(fixupLaunches.incrementAndGet()) >> super.launchBuildFixup(feature, piece, attempt)

  /** Under trunk the piece tail (CI / review / merge) is skipped, so only `refine` (the post-integration refinery) is
    * ever consulted; that the design/PR reviewer one-shots never run is asserted via the absence of
    * `PieceAwaitingReview` in the log (`FakeReviewerCall` is `final`, so it cannot be subclassed to raise-on-call).
    */
  private val refineOnlyReviewer = FakeReviewerCall.happyPath

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")

  private def startFeature(pieces: Vector[Piece], state: FsmState): Feature =
    featureAt(featureId, mkManifest(featureId, pieces), state)

  private def inProgress(id: PieceId, order: Int): Piece =
    piecePending(id, order).copy(status = PieceStatus.InProgress, baseSha = Some(BaseSha), prNumber = None)

  private def driveTo(
      root: os.Path,
      profile: Option[RepoProfile],
      config: ForgeConfig,
      failTimes: Int,
      counters: (AtomicInteger, AtomicInteger, AtomicInteger, AtomicInteger),
      start: Feature,
      settles: Vector[MonitorOutcome],
      // PR-path negative tests offer poll results so PieceAwaitingCi has a source and halts (it never enters this state
      // on the trunk path). Empty for the trunk tests.
      offers: Vector[(PrNumber, io.forge.git.watcher.PollResult)] = Vector.empty
  ): Feature =
    val (buildCalls, trunkCommits, openPrCalls, fixups) = counters
    val paths = new ForgePaths(repoRoot = root)
    (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- offers.traverse_ { case (pr, res) => watcher.offer(pr, res) }
      monitor <- FakeSessionMonitor.make(settles*)
      orch = new Orchestrator(
        new TrunkSideEffects(failTimes, buildCalls, trunkCommits, openPrCalls, fixups),
        monitor,
        watcher,
        refineOnlyReviewer,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        config,
        profileStore = profileStoreOf(profile)
      )
      out <- orch.drive(start)
    yield out).unsafeRunSync()

  private val implSettle = MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
  private val fixupSettle = MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean)
  private def paths(root: os.Path): ForgePaths = new ForgePaths(repoRoot = root)

  private def counters: (AtomicInteger, AtomicInteger, AtomicInteger, AtomicInteger) =
    (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))

  tempFixture.test("trunk: PieceImplementing → commit-to-trunk → Refining → FeatureDone, no PR / no CI / no review"):
    root =>
      val c @ (buildCalls, trunkCommits, openPrCalls, fixups) = counters
      val out = driveTo(
        root,
        Some(trunkProfile),
        testConfig,
        failTimes = 0,
        c,
        startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
        settles = Vector(implSettle)
      )

      assertEquals(out.state, FsmState.FeatureDone: FsmState)
      assertEquals(buildCalls.get(), 1, "the local Build gate still runs pre-integration under trunk")
      assertEquals(trunkCommits.get(), 1, "the piece committed straight to trunk exactly once")
      assertEquals(openPrCalls.get(), 0, "no PR was opened on the trunk path")
      assertEquals(fixups.get(), 0, "no fix-up on a clean build")

      val merged = out.manifest.pieces.find(_.id == p1).get
      assertEquals(merged.status, PieceStatus.Merged)
      assertEquals(merged.prNumber, None, "a trunk piece is merged with no PR number")
      assertEquals(merged.mergeCommit, Some(MergeCommit), "the trunk commit sha is recorded as the merge commit")
      assertEquals(merged.mergedAt, Some(MergedAt))

      val log = os.read(paths(root).featureLog(featureId))
      // The trunk integration records `audit.piece_merged` with a NULL prNumber — the W3-distinctive shape (a PR merge
      // carries a number). The `fsm.transition` payload encodes states (PieceImplementing → Refining), not event names.
      assert(log.contains("\"audit.piece_merged\""), "the piece-merged audit record is in the log")
      assert(log.contains("\"prNumber\":null"), s"a trunk piece is merged with a null prNumber:\n$log")
      assert(log.contains("\"result\":\"pass\""), "the Build gate pass is observable")
      assert(log.contains("Refining"), "the FSM advanced into Refining (the post-integration refinery)")
      assert(!log.contains("PieceAwaitingCi"), "the §8 CI gate is skipped under trunk")
      assert(!log.contains("PieceAwaitingReview"), "the review step is skipped under trunk")

  tempFixture.test(
    "trunk + build-fail: PieceImplementing → pre-PR fix-up → re-gate passes → commit-to-trunk; attempts 0"
  ): root =>
    val c @ (buildCalls, trunkCommits, openPrCalls, fixups) = counters
    // implement settle → build #1 fails → pre-PR fix-up; fix-up settle → build #2 passes → commit to trunk.
    val out = driveTo(
      root,
      Some(trunkProfile),
      testConfig,
      failTimes = 1,
      c,
      startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle, fixupSettle)
    )

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(buildCalls.get(), 2, "Build gate ran on the implement settle AND the post-fix-up re-gate")
    assertEquals(fixups.get(), 1, "exactly one pre-PR build fix-up launched")
    assertEquals(trunkCommits.get(), 1, "the piece integrated to trunk once the re-gate passed")
    assertEquals(openPrCalls.get(), 0, "still no PR on the trunk path")
    assertEquals(
      out.manifest.pieces.find(_.id == p1).map(_.attempts),
      Some(0),
      "the pre-PR build budget never bumps attempts"
    )
    val log = os.read(paths(root).featureLog(featureId))
    assert(log.contains("PieceBuildFixingUp"), "passed through the pre-PR fix-up state")
    assert(log.contains("\"audit.piece_merged\"") && log.contains("\"prNumber\":null"), "integrated to trunk, no PR")

  tempFixture.test("trunk: two pieces both integrate to trunk and reach FeatureDone"): root =>
    val p2 = PieceId("p2")
    val c @ (_, trunkCommits, openPrCalls, _) = counters
    val out = driveTo(
      root,
      Some(trunkProfile),
      testConfig,
      failTimes = 0,
      c,
      startFeature(Vector(inProgress(p1, 1), piecePending(p2, 2)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle, implSettle)
    )

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(trunkCommits.get(), 2, "both pieces committed to trunk")
    assertEquals(openPrCalls.get(), 0)
    assert(
      out.manifest.pieces.forall(p => p.status == PieceStatus.Merged && p.prNumber.isEmpty),
      s"both pieces merged with no PR: ${out.manifest.pieces}"
    )

  // The PR-path negative cases: prove the branch decision routes to ClassifyCommitOpenPr (PR opened, no trunk commit).
  // The run halts at the §8 CI gate via checkDiscoveryTimeoutSec = 0 (two observed-empty polls trip "no checks
  // discovered" → NHI(ResumeAfterHumanPush)) — short of the review step, so the refine-only reviewer is never consulted.
  private val piecePr = PrNumber(999)
  private def noCiOffers: Vector[(PrNumber, io.forge.git.watcher.PollResult)] =
    Vector(piecePr -> snapshotResult(openSnapshot(piecePr)), piecePr -> snapshotResult(openSnapshot(piecePr)))
  private def noCiConfig(base: ForgeConfig): ForgeConfig =
    base.copy(ci = base.ci.copy(checkDiscoveryTimeoutSec = 0))

  private def assertOpenedPrNotTrunk(out: Feature, trunkCommits: AtomicInteger, openPrCalls: AtomicInteger): Unit =
    out.state match
      case _: FsmState.NeedsHumanIntervention => ()
      case other => fail(s"expected the PR path to halt at the CI gate (NHI), got $other")
    assertEquals(trunkCommits.get(), 0, "must not commit to trunk")
    assertEquals(openPrCalls.get(), 1, "the PR path opens exactly one PR (the byte-identical 1.10 path)")

  tempFixture.test("GitFlow profile keeps the PR path (no trunk commit)"): root =>
    val gitFlow = trunkProfile.copy(workflow = trunkProfile.workflow.copy(branchModel = BranchModel.GitFlow))
    val c @ (_, trunkCommits, openPrCalls, _) = counters
    val out = driveTo(
      root,
      Some(gitFlow),
      noCiConfig(testConfig),
      failTimes = 0,
      c,
      startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle),
      offers = noCiOffers
    )
    assertOpenedPrNotTrunk(out, trunkCommits, openPrCalls)

  tempFixture.test("workflowGate=false: TrunkBased profile ignored, PR path taken"): root =>
    val gateOff = noCiConfig(testConfig).copy(adapt = testConfig.adapt.copy(workflowGate = false))
    val c @ (_, trunkCommits, openPrCalls, _) = counters
    val out = driveTo(
      root,
      Some(trunkProfile),
      gateOff,
      failTimes = 0,
      c,
      startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle),
      offers = noCiOffers
    )
    assertOpenedPrNotTrunk(out, trunkCommits, openPrCalls)

  tempFixture.test("PrBased profile keeps the PR path (the post-P0 safe default, no trunk commit)"): root =>
    val prBased = trunkProfile.copy(workflow = trunkProfile.workflow.copy(branchModel = BranchModel.PrBased))
    val c @ (_, trunkCommits, openPrCalls, _) = counters
    val out = driveTo(
      root,
      Some(prBased),
      noCiConfig(testConfig),
      failTimes = 0,
      c,
      startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle),
      offers = noCiOffers
    )
    assertOpenedPrNotTrunk(out, trunkCommits, openPrCalls)

  tempFixture.test("P0 safety: a stale pre-pr_based TrunkBased profile (schemaVersion 1) does NOT direct-push"): root =>
    // In v1 the profiler emitted trunk_based as the DEFAULT for ordinary PR repos. A committed v1 trunk_based profile
    // must therefore degrade to the PR lifecycle, not push straight to main, until the repo is re-profiled under v2.
    val staleTrunk = trunkProfile.copy(schemaVersion = 1)
    val c @ (_, trunkCommits, openPrCalls, _) = counters
    val out = driveTo(
      root,
      Some(staleTrunk),
      noCiConfig(testConfig),
      failTimes = 0,
      c,
      startFeature(Vector(inProgress(p1, 1)), FsmState.PieceImplementing(p1)),
      settles = Vector(implSettle),
      offers = noCiOffers
    )
    assertOpenedPrNotTrunk(out, trunkCommits, openPrCalls)
