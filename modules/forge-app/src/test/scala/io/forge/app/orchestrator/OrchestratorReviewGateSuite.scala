package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.forge.agents.{
  ConventionLearnerInput,
  DesignReview,
  DesignReviewInput,
  FailureClassifierInput,
  PrReview,
  PrReviewInput,
  RefineInput,
  RefineResult,
  RepoProfilerInput
}
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.MonitorOutcome
import io.forge.app.reviewer.{ReviewerCall, ReviewerLimits, ReviewerOutcome}
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

/** §11.5 (1.9 / design-3.3) — the **WorkflowProfile** parameterizes the FSM: a repo whose `workflow.reviewRequired` is
  * false skips the PR code-review step. The orchestrator's `PieceAwaitingReview` entry hook emits `ReviewSkipped`
  * (advancing straight to the merge gate) **instead of** spawning a reviewer one-shot — no reviewer call spent, the FSM
  * stays profile-agnostic (the decision lives in the orchestrator). Proven end-to-end with scripted fakes:
  *
  *   - `reviewRequired = false` (+ `workflowGate` on) ⇒ the reviewer is **never consulted** (a reviewer that raises on
  *     `prReview` still drives to `FeatureDone`), and the log shows the `PieceAwaitingReview → PieceAwaitingMerge`
  *     transition.
  *   - `reviewRequired = true` ⇒ the reviewer **is** consulted (the 1.6/1.8 path), no `ReviewSkipped` is logged.
  *   - `adapt.workflowGate = false` ⇒ even a `reviewRequired = false` profile keeps the review step (the operator
  *     override).
  *   - unprofiled ⇒ review required (1.6).
  */
class OrchestratorReviewGateSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-review-gate-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val piecePr = PrNumber(201)

  /** A profile whose only knob that matters here is `workflow.reviewRequired`; commands are irrelevant to the review
    * gate (no local gate runs in these drives).
    */
  private def profileWith(reviewRequired: Boolean): RepoProfile = RepoProfile(
    schemaVersion = RepoProfile.CurrentSchemaVersion,
    buildTool = "sbt",
    commands = Vector.empty,
    commitIdentity = CommitIdentity("Forge", "forge@example.com"),
    workflow = WorkflowProfile(
      reviewRequired = reviewRequired,
      ciRequiredChecks = Vector("ci"),
      branchModel = BranchModel.TrunkBased,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  private def profileStoreOf(p: Option[RepoProfile]): ProfileStore = new ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(p)
    def save(profile: RepoProfile): IO[Unit] = IO.unit

  /** A reviewer that **records every `prReview` call** (delegating the happy-path verdicts; `FakeReviewerCall` is
    * `final`, so this composes rather than subclasses). The counter stays 0 in the skip case and is 1 in the control
    * cases.
    */
  private class CountingReviewer(prCalls: AtomicInteger) extends ReviewerCall:
    private val delegate = FakeReviewerCall.happyPath
    def designReview(input: DesignReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[DesignReview]] =
      delegate.designReview(input, limits)
    def prReview(input: PrReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[PrReview]] =
      IO(prCalls.incrementAndGet()) >> delegate.prReview(input, limits)
    def refine(input: RefineInput, limits: ReviewerLimits): IO[ReviewerOutcome[RefineResult]] =
      delegate.refine(input, limits)
    def profileRepo(input: RepoProfilerInput, limits: ReviewerLimits): IO[ReviewerOutcome[RepoProfile]] =
      delegate.profileRepo(input, limits)
    def classifyFailure(input: FailureClassifierInput, limits: ReviewerLimits): IO[ReviewerOutcome[Classification]] =
      delegate.classifyFailure(input, limits)
    def learnConventions(input: ConventionLearnerInput, limits: ReviewerLimits): IO[ReviewerOutcome[ConventionDeltas]] =
      delegate.learnConventions(input, limits)

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** Drive a single InProgress piece from `PieceImplementing` to terminal: implement settles clean → PR opens → CI
    * green → review gate (skipped or run) → merge → `FeatureDone`. Returns the final feature, the recorded states, and
    * the prReview call count.
    */
  private def driveTo(
      root: os.Path,
      profile: Option[RepoProfile],
      config: ForgeConfig
  ): (Feature, Vector[FsmState], Int) =
    val paths = new ForgePaths(repoRoot = root)
    val prCalls = new AtomicInteger(0)
    val inProgress =
      piecePending(p1, 1).copy(status = PieceStatus.InProgress, baseSha = Some(BaseSha), prNumber = None)
    val start = featureAt(featureId, mkManifest(featureId, Vector(inProgress)), FsmState.PieceImplementing(p1))
    val (out, states) = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      recorded <- Ref.of[IO, Vector[FsmState]](Vector.empty)
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean))
      hookCache = new HookStateCache(
        new FileStateCache(paths),
        f => recorded.update(_ :+ f.state) >> offerMergeOnAwaitingMerge(watcher, f)
      )
      orch = new Orchestrator(
        new FakeSideEffects(PrNumber(100), _ => piecePr),
        monitor,
        watcher,
        new CountingReviewer(prCalls),
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        hookCache,
        paths,
        config,
        profileStore = profileStoreOf(profile)
      )
      out <- orch.drive(start)
      states <- recorded.get
    yield (out, states)).unsafeRunSync()
    (out, states, prCalls.get())

  private def paths(root: os.Path): ForgePaths = new ForgePaths(repoRoot = root)

  tempFixture.test("reviewRequired=false → review step skipped (reviewer never called), still reaches FeatureDone"):
    root =>
      val (out, states, prCalls) = driveTo(root, Some(profileWith(reviewRequired = false)), testConfig)

      assertEquals(out.state, FsmState.FeatureDone: FsmState)
      assertEquals(prCalls, 0, "the reviewer one-shot must not run when reviewRequired=false")
      // The FSM still visited PieceAwaitingReview, but advanced via ReviewSkipped (not a verdict).
      assert(states.contains(FsmState.PieceAwaitingReview(p1, piecePr)), states.toString)
      assert(states.contains(FsmState.PieceAwaitingMerge(p1, piecePr)), states.toString)
      assert(
        states.indexOf(FsmState.PieceAwaitingMerge(p1, piecePr)) > states.indexOf(
          FsmState.PieceAwaitingReview(p1, piecePr)
        ),
        s"merge must follow review: $states"
      )
      val log = os.read(paths(root).featureLog(featureId))
      assert(log.contains("ReviewSkipped") || log.contains("PieceAwaitingMerge"), log)
      assert(out.manifest.pieces.forall(_.status == PieceStatus.Merged), out.manifest.pieces.toString)

  tempFixture.test("reviewRequired=true → reviewer consulted (1.6/1.8 path), no skip"): root =>
    val (out, _, prCalls) = driveTo(root, Some(profileWith(reviewRequired = true)), testConfig)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(prCalls, 1, "the reviewer one-shot runs once when reviewRequired=true")
    val log = os.read(paths(root).featureLog(featureId))
    assert(!log.contains("ReviewSkipped"), "no ReviewSkipped when review is required")

  tempFixture.test("workflowGate=false → reviewRequired=false is ignored, reviewer still consulted"): root =>
    val gateOff = testConfig.copy(adapt = testConfig.adapt.copy(workflowGate = false))
    val (out, _, prCalls) = driveTo(root, Some(profileWith(reviewRequired = false)), gateOff)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(prCalls, 1, "workflowGate=false keeps the fixed 1.6 review step even for reviewRequired=false")

  tempFixture.test("unprofiled run → review required (1.6 behaviour)"): root =>
    val (out, _, prCalls) = driveTo(root, None, testConfig)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(prCalls, 1, "an unprofiled run reviews exactly as 1.6")
