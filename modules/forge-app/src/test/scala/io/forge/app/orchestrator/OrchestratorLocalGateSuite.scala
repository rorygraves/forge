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

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import OrchestratorTestKit.*

/** Task 3.1.3 — the §8.3 local format gate (shift-left, pre-PR), proven end-to-end with scripted fakes. A piece whose
  * implement driver settles clean routes through `ClassifyCommitOpenPr`; with a profile that declares a deterministic
  * `Format` autofix and `adapt.localGate` on, the gate runs that command on the working tree **before** the piece
  * commit (dogfood #3: the driver's non-conformant output never reaches CI, zero round-trip), records a
  * `profile.local_gate` action, and the PR opens normally — no fix-up round, `attempts` stays 0.
  *
  * The unprofiled and `localGate = false` cases keep the exact 1.6 path: the gate never runs and nothing is logged.
  * Each test halts deterministically at `NeedsHumanIntervention` on a reviewer wall-clock Timeout once the piece
  * reaches `PieceAwaitingReview` — a terminal that does not touch `attempts`, isolating the gate decision from the
  * merge tail.
  */
class OrchestratorLocalGateSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-local-gate-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A profile declaring `sbt scalafmtAll` as the required, deterministic, in-place format autofix — the predicate
    * (`Format && required && autofix && Deterministic`) the local gate filters on.
    */
  private val scalafmtProfile: RepoProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector(
      RepoCommand(
        CommandKind.Format,
        Vector("sbt", "scalafmtAll"),
        Determinism.Deterministic,
        required = true,
        autofix = true
      )
    ),
    commitIdentity = CommitIdentity("Forge", "forge@example.com"),
    workflow = WorkflowProfile(
      reviewRequired = true,
      // matches the shared `ciReadySnapshot` green check so the Half A sensed required set is satisfied on advance.
      ciRequiredChecks = Vector("ci"),
      branchModel = BranchModel.TrunkBased,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  private def profileStoreOf(p: Option[RepoProfile]): ProfileStore = new ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(p)
    def save(profile: RepoProfile): IO[Unit] = IO.unit

  /** Records the §8.3 gate calls (count + the commands seen) so a test can assert it ran exactly once with the
    * profile's format command — or never, on the 1.6 paths.
    */
  private class GateSideEffects(piecePr: PrNumber, gateCalls: AtomicInteger, seen: AtomicReference[Vector[String]])
      extends FakeSideEffects(PrNumber(100), _ => piecePr):
    override def runLocalFormatGate(
        feature: Feature,
        piece: PieceId,
        commands: Vector[RepoCommand]
    ): IO[Either[String, Unit]] =
      IO { gateCalls.incrementAndGet(); seen.set(commands.map(_.argv.mkString(" "))); Right(()) }

  private val timeoutPrReviewer =
    new FakeReviewerCall(FakeReviewerCall.approveDesign, ReviewerOutcome.Timeout, FakeReviewerCall.refineNoChange)

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val piecePr = PrNumber(201)

  /** A piece with a created branch (baseSha set) but no PR yet — the §11.4-step-6 entry: the implement driver spawns,
    * settles clean, and the orchestrator runs the local gate then commits + opens the PR.
    */
  private def startFeature =
    val inProgress = piecePending(p1, 1).copy(status = PieceStatus.InProgress, baseSha = Some(BaseSha), prNumber = None)
    featureAt(featureId, mkManifest(featureId, Vector(inProgress)), FsmState.PieceImplementing(p1))

  private def driveTo(
      root: os.Path,
      profile: Option[RepoProfile],
      config: ForgeConfig,
      gateCalls: AtomicInteger,
      seen: AtomicReference[Vector[String]]
  ): Feature =
    val paths = new ForgePaths(repoRoot = root)
    (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      // The implement driver settles clean → PR opens; the green poll then advances to review (NHI on reviewer timeout).
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean))
      orch = new Orchestrator(
        new GateSideEffects(piecePr, gateCalls, seen),
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

  tempFixture.test("profiled + localGate on: format gate runs pre-PR (once, with scalafmtAll), attempts stays 0"):
    root =>
      val gateCalls = new AtomicInteger(0)
      val seen = new AtomicReference[Vector[String]](Vector.empty)
      val out = driveTo(root, Some(scalafmtProfile), testConfig, gateCalls, seen)

      assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
      // The shift-left: the formatter ran exactly once, before the PR, with the profile's command — no fix-up round.
      assertEquals(gateCalls.get(), 1)
      assertEquals(seen.get(), Vector("sbt scalafmtAll"))
      assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0))
      // The gate run is observable in the log (forge stats / TUI can fold it).
      val log = os.read(paths(root).featureLog(featureId))
      assert(log.contains("\"profile.local_gate\""), log)
      assert(log.contains("\"gate\":\"local\""), log)

  tempFixture.test("unprofiled: local gate never runs, nothing logged — exact 1.6 behaviour"): root =>
    val gateCalls = new AtomicInteger(0)
    val seen = new AtomicReference[Vector[String]](Vector.empty)
    val out = driveTo(root, None, testConfig, gateCalls, seen)

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(gateCalls.get(), 0)
    assert(!os.read(paths(root).featureLog(featureId)).contains("\"profile.local_gate\""))

  tempFixture.test("profiled but adapt.localGate = false: gate is suppressed, PR still opens"): root =>
    val gateCalls = new AtomicInteger(0)
    val seen = new AtomicReference[Vector[String]](Vector.empty)
    val gateOff = testConfig.copy(adapt = testConfig.adapt.copy(localGate = false))
    val out = driveTo(root, Some(scalafmtProfile), gateOff, gateCalls, seen)

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    assertEquals(gateCalls.get(), 0)
    assert(!os.read(paths(root).featureLog(featureId)).contains("\"profile.local_gate\""))

  private def paths(root: os.Path): ForgePaths = new ForgePaths(repoRoot = root)
