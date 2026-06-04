package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.monitor.MonitorOutcome
import io.forge.app.reviewer.ReviewerOutcome
import io.forge.core.*
import io.forge.core.fsm.{FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.{FileManifestStore, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.pr.{CheckConclusion, CheckResult, CheckRollup, CheckState, PrSnapshot, PrState}
import io.forge.core.profile.*
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import java.util.concurrent.atomic.AtomicInteger

import OrchestratorTestKit.*

/** Task 3.1.2 — the dogfood-#2 collapse, proven end-to-end with scripted fakes: a `PieceAwaitingCi` whose required
  * check fails with a real scalafmt log routes to a local `sbt scalafmtAll` (`FixupRoute.RunLocalCommand`) — **no
  * driver fix-up turn, no `attempts` increment** — then the next (green) poll advances normally to review. The
  * pre-Phase-3 behaviour (the second test, unprofiled) instead spends a fix-up round: `attempts += 1` → `PieceCiFailed`
  * → fix-up driver.
  *
  * Both tests stop at `NeedsHumanIntervention` via a reviewer wall-clock Timeout once the piece reaches
  * `PieceAwaitingReview` — a deterministic terminal that does **not** touch `attempts`, so the `attempts` assertion
  * cleanly isolates the CI-routing decision (the merge → refine → FeatureDone tail is covered by the happy-path
  * suites).
  */
class OrchestratorCiRoutingSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-ci-routing-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A repo profile that declares `sbt scalafmtAll` as a deterministic, in-place format autofix — the single predicate
    * (`autofix && Deterministic`) that makes a `DeterministicFix` routable to a no-driver local run.
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
      ciRequiredChecks = Vector("backend"),
      branchModel = BranchModel.TrunkBased,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  private def profileStoreOf(p: Option[RepoProfile]): ProfileStore = new ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(p)
    def save(profile: RepoProfile): IO[Unit] = IO.unit

  /** The real dogfood-#2 failing line (see docs/slice-3/fixtures), enough for the rules classifier to key on. */
  private val scalafmtLog: String =
    """[warn] scalafmt: src/main/scala/.../MediaNetworkConfig.scala isn't formatted properly!
      |[error] scalafmt: 1 files must be formatted (/home/runner/work/szork/szork)""".stripMargin

  /** A failing required check carrying an Actions `detailsUrl` so the orchestrator can recover the run id. */
  private def ciFailedSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks = CheckRollup(
        Vector.empty,
        Vector(
          CheckResult(
            "backend",
            CheckState.Completed,
            Some(CheckConclusion.Failure),
            detailsUrl = Some("https://github.com/llm4s/szork/actions/runs/26786101936/job/78962117741")
          )
        )
      ),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  /** A green snapshot whose check is named `backend` — matching `scalafmtProfile.workflow.ciRequiredChecks`, so the
    * §3.3 Tier-2 sensed required set is satisfied on the advancing poll. The shared `ciReadySnapshot` emits a `ci`
    * check, which a profile declaring `ciRequiredChecks=["backend"]` would (correctly, post-Half-A) keep polling for —
    * so the profiled routing tests must advance on a `backend`-green snapshot, consistent with their failing snapshot.
    */
  private def ciReadyBackendSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks =
        CheckRollup(Vector.empty, Vector(CheckResult("backend", CheckState.Completed, Some(CheckConclusion.Success)))),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  /** A failing log the rules classifier cannot pin (no scalafmt / rate-limit / compile markers) — drives the Task 3.1.4
    * `Unknown` → LLM `classifyFailure` consultation path.
    */
  private val opaqueLog: String = "Process completed with exit code 1."

  private class RoutingSideEffects(
      piecePr: PrNumber,
      autofixCalls: AtomicInteger,
      failingLog: String = scalafmtLog
  ) extends FakeSideEffects(PrNumber(100), _ => piecePr):
    override def fetchFailingLog(runId: String): IO[Either[String, String]] = IO.pure(Right(failingLog))
    override def runLocalAutofixAndPush(
        feature: io.forge.core.fsm.Feature,
        piece: PieceId,
        command: RepoCommand
    ): IO[Either[String, Unit]] =
      IO { autofixCalls.incrementAndGet(); Right(()) }

  /** Reviewer that approves the design but times out the PR review — a deterministic terminal (NHI) that does not bump
    * `attempts`, so the test halts right after the routed gate without depending on the merge/refine tail.
    */
  private val timeoutPrReviewer =
    new FakeReviewerCall(
      FakeReviewerCall.approveDesign,
      ReviewerOutcome.Timeout,
      FakeReviewerCall.refineNoChange
    )

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val piecePr = PrNumber(201)

  private def startFeature =
    val inProgress = piecePending(p1, 1).copy(
      status = PieceStatus.InProgress,
      baseSha = Some(BaseSha),
      prNumber = Some(piecePr)
    )
    featureAt(featureId, mkManifest(featureId, Vector(inProgress)), FsmState.PieceAwaitingCi(p1, piecePr))

  tempFixture.test("profiled scalafmt CI failure routes to local autofix — attempts stays 0, no fix-up round"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val autofixCalls = new AtomicInteger(0)

    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      // 1) scalafmt failure → routed to local autofix (keep polling); 2) green → advance to review (then NHI on timeout).
      // The green snapshot is `backend`-named to match the profile's sensed required set (Half A), as is the failure.
      _ <- watcher.offer(piecePr, snapshotResult(ciFailedSnapshot(piecePr)))
      _ <- watcher.offer(piecePr, snapshotResult(ciReadyBackendSnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make() // no driver settle expected — the autofix is not a driver turn
      orch = new Orchestrator(
        new RoutingSideEffects(piecePr, autofixCalls),
        monitor,
        watcher,
        timeoutPrReviewer,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        testConfig,
        profileStore = profileStoreOf(Some(scalafmtProfile))
      )
      out <- orch.drive(startFeature)
    yield out).unsafeRunSync()

    // Reached PieceAwaitingReview (past the failing gate) then halted at NHI on the reviewer timeout.
    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    // The collapse: the formatter ran exactly once, and the piece never consumed a fix-up round.
    assertEquals(autofixCalls.get(), 1)
    assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0))
    // The classified-failure perception is recorded, routed to a local command (so `forge stats` can fold it).
    val log = os.read(paths.featureLog(featureId))
    assert(log.contains("\"profile.failure_classified\""), log)
    assert(log.contains("\"route\":\"RunLocalCommand\""), log)

  tempFixture.test("unprofiled CI failure keeps the 1.6 blind fix-up — attempts increments, no autofix"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val autofixCalls = new AtomicInteger(0)

    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      // failure → (no profile) PieceCiFailed → fix-up driver settles → re-push → green → review → NHI on timeout.
      // The green snapshot is offered only once the fix-up driver is running (state PieceFixingUp), so it is delivered
      // fresh to the second CI gate rather than sitting in the queue across the fix-up cycle.
      _ <- watcher.offer(piecePr, snapshotResult(ciFailedSnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Fixup, SettleOutcome.Clean))
      hookCache = new HookStateCache(
        new FileStateCache(paths),
        f =>
          f.state match
            case _: FsmState.PieceFixingUp => watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
            case _ => IO.unit
      )
      orch = new Orchestrator(
        new RoutingSideEffects(piecePr, autofixCalls),
        monitor,
        watcher,
        timeoutPrReviewer,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        hookCache,
        paths,
        testConfig,
        profileStore = profileStoreOf(None) // unprofiled ⇒ 1.6 behaviour
      )
      out <- orch.drive(startFeature)
    yield out).unsafeRunSync()

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    // The blind path spends one fix-up round; the deterministic autofix is never run without a profile.
    assertEquals(autofixCalls.get(), 0)
    assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(1))
    val log = os.read(paths.featureLog(featureId))
    assert(!log.contains("\"profile.failure_classified\""), log)

  /** Observed checks = a green `backend` (the profile's sole sensed required check) plus a *failing* non-required
    * `optional-lint`. Under the §3.3 Tier-2 sensed required set, only `backend` gates, so the failing non-required
    * check is ignored and the piece advances; without the profile, the observed fallback would treat `optional-lint` as
    * required and route it to a fix-up.
    */
  private def ciSensedSubsetSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks = CheckRollup(
        Vector.empty,
        Vector(
          CheckResult("backend", CheckState.Completed, Some(CheckConclusion.Success)),
          CheckResult("optional-lint", CheckState.Completed, Some(CheckConclusion.Failure))
        )
      ),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  tempFixture.test("§3.3 sensed required subset: a non-required failing check is ignored — advances, attempts 0"):
    root =>
      // Half A: the profile's ciRequiredChecks=["backend"] is threaded into the live §8 gate (workflowGate on by default).
      // Required={backend} (green) ⇒ Ready ⇒ PieceAwaitingReview, while the failing non-required `optional-lint` never
      // routes. Were the sensed set NOT plumbed, the observed fallback would make `optional-lint` required and route it
      // (logging profile.failure_classified) — so "reached review + no routing" is the discriminator.
      val paths = new ForgePaths(repoRoot = root)
      val autofixCalls = new AtomicInteger(0)

      val out = (for
        logImpl <- FileActionLog(paths)
        watcher <- FakePRWatcher.make
        _ <- watcher.offer(piecePr, snapshotResult(ciSensedSubsetSnapshot(piecePr)))
        monitor <- FakeSessionMonitor.make() // no driver settle — nothing should route to a fix-up
        orch = new Orchestrator(
          new RoutingSideEffects(piecePr, autofixCalls),
          monitor,
          watcher,
          timeoutPrReviewer,
          new FileSpecStore(paths),
          new FileManifestStore(paths),
          logImpl,
          new FileStateCache(paths),
          paths,
          testConfig,
          profileStore = profileStoreOf(Some(scalafmtProfile))
        )
        out <- orch.drive(startFeature)
      yield out).unsafeRunSync()

      // Past the CI gate to the review gate (then NHI on the reviewer timeout); the non-required failure was ignored.
      assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
      assertEquals(autofixCalls.get(), 0)
      assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0))
      val log = os.read(paths.featureLog(featureId))
      assert(log.contains("PieceAwaitingReview"), log)
      assert(!log.contains("\"profile.failure_classified\""), log)

  tempFixture.test("rules-Unknown CI failure → LLM classifyFailure pins it → local autofix, source=llm"): root =>
    // Task 3.1.4: the rules baseline returns Unknown on an opaque log; with adapt.llmClassifierOnUnknown the
    // orchestrator consults the LLM, which pins it as a DeterministicFix → local scalafmtAll. attempts stays 0, and the
    // §19 audit records source=llm.
    val paths = new ForgePaths(repoRoot = root)
    val autofixCalls = new AtomicInteger(0)

    val llmClassification =
      Classification(FailureKind.DeterministicFix, 0.92, Some(CommandKind.Format), "perceived drift")
    val llmReviewer =
      new FakeReviewerCall(
        FakeReviewerCall.approveDesign,
        ReviewerOutcome.Timeout,
        FakeReviewerCall.refineNoChange,
        classifyOutcome = IO.pure(ReviewerOutcome.Settled(llmClassification))
      )

    val out = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(piecePr, snapshotResult(ciFailedSnapshot(piecePr)))
      _ <- watcher.offer(piecePr, snapshotResult(ciReadyBackendSnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make()
      orch = new Orchestrator(
        new RoutingSideEffects(piecePr, autofixCalls, failingLog = opaqueLog),
        monitor,
        watcher,
        llmReviewer,
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        new FileStateCache(paths),
        paths,
        testConfig,
        profileStore = profileStoreOf(Some(scalafmtProfile))
      )
      out <- orch.drive(startFeature)
    yield out).unsafeRunSync()

    assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
    // The LLM pinned what the rules couldn't: the formatter ran once, no fix-up round consumed.
    assertEquals(autofixCalls.get(), 1)
    assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0))
    val log = os.read(paths.featureLog(featureId))
    assert(log.contains("\"route\":\"RunLocalCommand\""), log)
    assert(log.contains("\"source\":\"llm\""), log)

  tempFixture.test("rules-Unknown CI failure with LLM Timeout → blind escalate, attempts untouched, source not llm"):
    root =>
      // The safe degradation: when the LLM sensor stalls, the orchestrator keeps the rules Escalate rather than blocking.
      // Unknown → Escalate routes to CiReadinessBlocked → NHI; no autofix, attempts stays 0, audit records source=rules.
      val paths = new ForgePaths(repoRoot = root)
      val autofixCalls = new AtomicInteger(0)

      val llmTimeoutReviewer =
        new FakeReviewerCall(
          FakeReviewerCall.approveDesign,
          ReviewerOutcome.Timeout,
          FakeReviewerCall.refineNoChange,
          classifyOutcome = IO.pure(ReviewerOutcome.Timeout)
        )

      val out = (for
        logImpl <- FileActionLog(paths)
        watcher <- FakePRWatcher.make
        _ <- watcher.offer(piecePr, snapshotResult(ciFailedSnapshot(piecePr)))
        monitor <- FakeSessionMonitor.make()
        orch = new Orchestrator(
          new RoutingSideEffects(piecePr, autofixCalls, failingLog = opaqueLog),
          monitor,
          watcher,
          llmTimeoutReviewer,
          new FileSpecStore(paths),
          new FileManifestStore(paths),
          logImpl,
          new FileStateCache(paths),
          paths,
          testConfig,
          profileStore = profileStoreOf(Some(scalafmtProfile))
        )
        out <- orch.drive(startFeature)
      yield out).unsafeRunSync()

      assert(out.state.isInstanceOf[FsmState.NeedsHumanIntervention], out.state.toString)
      assertEquals(autofixCalls.get(), 0)
      assertEquals(out.manifest.pieces.find(_.id == p1).map(_.attempts), Some(0))
      val log = os.read(paths.featureLog(featureId))
      assert(log.contains("\"route\":\"Escalate\""), log)
      assert(log.contains("\"source\":\"rules\""), log)
