package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.MonitorOutcome
import io.forge.app.reviewer.ReviewerOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.*
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import java.util.concurrent.atomic.AtomicInteger

import OrchestratorTestKit.*

/** §8.3 / §11.4 (1.8 / decision D2) — the local **Build** gate (shift-left, pre-PR), proven end-to-end with scripted
  * fakes. Unlike the Format gate (deterministic in-place autofix, [[OrchestratorLocalGateSuite]]), a Build failure is a
  * `CodeFix`: it routes to a **pre-PR driver fix-up** (`PieceBuildFailed` → `PieceBuildFixingUp`), catching the compile
  * error before the costly commit → push → PR → CI-fail → fix-up round-trip. The pre-PR fix-up budget lives in the FSM
  * state and **never** touches `manifest.attempts` (that budget is reserved for PR-side CI fix-ups, §11.4).
  *
  * Each test halts deterministically at `NeedsHumanIntervention` on a reviewer wall-clock Timeout once the piece
  * reaches `PieceAwaitingReview` — a terminal that does not touch `attempts`, isolating the gate decision from the
  * merge tail.
  */
class OrchestratorBuildGateSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-build-gate-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A profile declaring `sbt compile` as the required, deterministic, **non-autofix** Build command — the predicate
    * (`Build && required && !autofix && Deterministic`) the local Build gate filters on.
    */
  private val buildProfile: RepoProfile = RepoProfile(
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
      // matches the shared `ciReadySnapshot` green check so the Half A sensed required set is satisfied on advance.
      ciRequiredChecks = Vector("ci"),
      branchModel = BranchModel.GitFlow,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  /** A real-shaped Scala compile error — `[error]` / `type mismatch` markers route to `CodeFix` → `DriverFixup` in
    * `RuleBasedFailureClassifier` (so the Build gate goes to a pre-PR driver fix-up, not a local autofix).
    */
  private val compileError: String =
    """[error] -- [E007] Type Mismatch Error: Foo.scala:10:5
      |[error] 10 |  val n: Int = "not an int"
      |[error]    |               ^^^^^^^^^^^^^
      |[error]    |               Found:    ("not an int" : String)
      |[error]    |               Required: Int
      |[error] one error found""".stripMargin

  private def profileStoreOf(p: Option[RepoProfile]): ProfileStore = new ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(p)
    def save(profile: RepoProfile): IO[Unit] = IO.unit

  /** Records the Build gate calls. `failTimes` = how many of the first calls return the compile error (`Left`); later
    * calls pass (`Right(())`). Also counts `writeBuildFailures` + `launchBuildFixup` so a test can assert the pre-PR
    * fix-up actually fired.
    */
  private class BuildGateSideEffects(
      piecePr: PrNumber,
      failTimes: Int,
      buildCalls: AtomicInteger,
      writes: AtomicInteger,
      fixupLaunches: AtomicInteger
  ) extends FakeSideEffects(PrNumber(100), _ => piecePr):
    override def runLocalBuildGate(
        feature: Feature,
        piece: PieceId,
        commands: Vector[RepoCommand]
    ): IO[Either[String, Unit]] =
      IO(if buildCalls.incrementAndGet() <= failTimes then Left(compileError) else Right(()))
    override def writeBuildFailures(feature: Feature, piece: PieceId, failureLog: String): IO[Unit] =
      IO(writes.incrementAndGet()).void
    override def launchBuildFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession] =
      IO(fixupLaunches.incrementAndGet()) >> super.launchBuildFixup(feature, piece, attempt)

  private val timeoutPrReviewer =
    new FakeReviewerCall(FakeReviewerCall.approveDesign, ReviewerOutcome.Timeout, FakeReviewerCall.refineNoChange)

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val piecePr = PrNumber(201)

  private def startFeature =
    val inProgress = piecePending(p1, 1).copy(status = PieceStatus.InProgress, baseSha = Some(BaseSha), prNumber = None)
    featureAt(featureId, mkManifest(featureId, Vector(inProgress)), FsmState.PieceImplementing(p1))

  /** Drive the feature to terminal with a scripted monitor (`settles`, one per driver settle) and a Build gate that
    * fails its first `failTimes` calls. A green poll is offered so that, once a PR opens, CI advances to review.
    */
  private def driveTo(
      root: os.Path,
      profile: Option[RepoProfile],
      config: ForgeConfig,
      failTimes: Int,
      buildCalls: AtomicInteger,
      writes: AtomicInteger,
      fixupLaunches: AtomicInteger,
      settles: Vector[MonitorOutcome]
  ): Feature =
    val paths = new ForgePaths(repoRoot = root)
    (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(settles*)
      orch = new Orchestrator(
        new BuildGateSideEffects(piecePr, failTimes, buildCalls, writes, fixupLaunches),
        monitor,
        watcher,
        timeoutPrReviewer,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        config,
        profileStore = profileStoreOf(profile)
      )
      out <- orch.drive(startFeature)
    yield out).unsafeRunSync()

  private val implSettle = MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean)
  private val fixupSettle = MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean)
  private def paths(root: os.Path): ForgePaths = new ForgePaths(repoRoot = root)

  tempFixture.test("build fails pre-PR → driver fix-up → re-gate passes → PR opens; attempts stays 0"): root =>
    val (buildCalls, writes, fixups) = (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))
    // Implement settle → build #1 fails → pre-PR fix-up; fix-up settle → build #2 passes → PR opens.
    val out = driveTo(
      root,
      Some(buildProfile),
      testConfig,
      failTimes = 1,
      buildCalls,
      writes,
      fixups,
      settles = Vector(implSettle, fixupSettle)
    )

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(buildCalls.get(), 2, "Build gate ran on implement settle AND on the post-fix-up re-gate")
    assertEquals(writes.get(), 1, "the compile error was written to <p>.failures.md once, pre-PR")
    assertEquals(fixups.get(), 1, "exactly one pre-PR build fix-up driver launched")
    assertEquals(
      out.manifest.pieces.find(_.id == p1).map(_.attempts),
      Some(0),
      "pre-PR build budget never bumps attempts"
    )

    val log = os.read(paths(root).featureLog(featureId))
    assert(log.contains("PieceBuildFailed"), "FSM passed through the pre-PR awaiting-spawn state")
    assert(log.contains("PieceBuildFixingUp"), "FSM passed through the pre-PR fix-up state")
    assert(log.contains("\"profile.local_gate\""), "the Build gate run is observable")
    assert(log.contains("\"kind\":\"build\""), log)
    assert(log.contains("\"profile.failure_classified\""), "the §8.2 routing decision is recorded")
    assert(log.contains("\"gate\":\"local\""), log)

  tempFixture.test("build passes first time: no fix-up, PR opens directly"): root =>
    val (buildCalls, writes, fixups) = (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))
    val out =
      driveTo(root, Some(buildProfile), testConfig, failTimes = 0, buildCalls, writes, fixups, Vector(implSettle))

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(buildCalls.get(), 1, "Build gate ran once and passed")
    assertEquals(fixups.get(), 0, "no fix-up when the build is clean")
    val log = os.read(paths(root).featureLog(featureId))
    assert(log.contains("\"result\":\"pass\""), log)
    assert(!log.contains("PieceBuildFixingUp"))

  tempFixture.test("build keeps failing → exhausts maxFixupRounds → NHI(ResolveLocalImplementationChanges)"): root =>
    val (buildCalls, writes, fixups) = (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))
    val oneRound = testConfig.copy(maxFixupRounds = 1)
    // maxFixupRounds=1: implement settle → build #1 fails → fix-up(1); fix-up settle → build #2 fails → would-be
    // attempt 2 > 1 → NHI. Build fails every call.
    val out = driveTo(
      root,
      Some(buildProfile),
      oneRound,
      failTimes = 99,
      buildCalls,
      writes,
      fixups,
      settles = Vector(implSettle, fixupSettle)
    )

    out.state match
      case FsmState.NeedsHumanIntervention(_, io.forge.core.fsm.ResumeHint.ResolveLocalImplementationChanges(p, _)) =>
        assertEquals(p, p1)
      case other => fail(s"expected NHI(ResolveLocalImplementationChanges), got $other")
    assertEquals(fixups.get(), 1, "one fix-up attempt within maxFixupRounds=1")
    assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0), "exhaustion never touches attempts")

  tempFixture.test("unprofiled: Build gate never runs, PR opens — exact 1.6 behaviour"): root =>
    val (buildCalls, writes, fixups) = (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))
    val out = driveTo(root, None, testConfig, failTimes = 1, buildCalls, writes, fixups, Vector(implSettle))

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(buildCalls.get(), 0)
    assert(!os.read(paths(root).featureLog(featureId)).contains("\"kind\":\"build\""))

  tempFixture.test("adapt.localGate = false: Build gate suppressed, PR still opens"): root =>
    val (buildCalls, writes, fixups) = (new AtomicInteger(0), new AtomicInteger(0), new AtomicInteger(0))
    val gateOff = testConfig.copy(adapt = testConfig.adapt.copy(localGate = false))
    val out = driveTo(root, Some(buildProfile), gateOff, failTimes = 1, buildCalls, writes, fixups, Vector(implSettle))

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(buildCalls.get(), 0)
