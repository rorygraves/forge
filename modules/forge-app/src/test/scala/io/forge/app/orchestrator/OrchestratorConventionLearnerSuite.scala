package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.MonitorOutcome
import io.forge.app.reviewer.ReviewerOutcome
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.profile.*
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import OrchestratorTestKit.*

/** Task 3.2 — the §11.7 `ConventionLearner` hooked out-of-band on the transition to `FeatureDone`, proven end-to-end
  * with scripted fakes. A single-piece feature is driven `DesignReady → FeatureDone`; its action log carries a
  * `profile.failure_classified` action (a `DeterministicFix` the run paid as a `DriverFixup` because the profile lacked
  * the autofix). On reaching `FeatureDone` the orchestrator consults the learner, which proposes the missing `format`
  * command + a CLAUDE.md note. The learner is advisory: the profile delta is saved via `ProfileStore.save`, the
  * CLAUDE.md edit is persisted to the feature's audit dir (no PR), and a `profile.conventions_learned` audit action is
  * recorded — none of it gates the (already-reached) `FeatureDone`.
  *
  * The 1.6 paths are byte-identical: `adapt.conventionLearner = false` suppresses the consultation entirely, and the
  * §7.11 cost lever means a run with **no** classified failure never spends a learner reviewer-call.
  */
class OrchestratorConventionLearnerSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-learner-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A profile that does NOT declare a `format` autofix — so the learner's proposed `format` command is genuinely
    * fresh, and the §8.3 local gate (which filters on `Format && required && autofix && Deterministic`) is a no-op
    * here.
    */
  private val baseProfile: RepoProfile = RepoProfile(
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
      branchModel = BranchModel.GitFlow,
      mergeStrategy = MergeStrategy.Squash
    )
  )

  /** The learner's proposal: add the `format` autofix the profile was missing + a CLAUDE.md note. */
  private val formatCmd =
    RepoCommand(
      CommandKind.Format,
      Vector("sbt", "scalafmtAll"),
      Determinism.Deterministic,
      required = true,
      autofix = true
    )
  private val deltas = ConventionDeltas(
    addCommands = Vector(formatCmd),
    claudeMdProposal =
      Some(ClaudeMdProposal("two fix-up rounds were spent on formatting", "Run `sbt scalafmtAll` before settling.")),
    summary = "add the format autofix gate"
  )

  private class RecordingProfileStore(saved: AtomicReference[Option[RepoProfile]]) extends ProfileStore:
    def load(): IO[Option[RepoProfile]] = IO.pure(Some(baseProfile))
    def save(profile: RepoProfile): IO[Unit] = IO(saved.set(Some(profile)))

  private def learningReviewer(learnCalls: AtomicInteger): FakeReviewerCall =
    new FakeReviewerCall(
      FakeReviewerCall.approveDesign,
      FakeReviewerCall.approvePr,
      FakeReviewerCall.refineNoChange,
      learnOutcome = IO { learnCalls.incrementAndGet(); ReviewerOutcome.Settled(deltas) }
    )

  private val featureId = FeatureId("feat")
  private val designPr = PrNumber(100)
  private val piecePr = PrNumber(200)
  private val p1 = PieceId("p1")

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** A classified gate failure to seed into the log before driving — the failure→remedy signal the learner mines: a
    * `DeterministicFix` that was paid as a `DriverFixup` because the profile had no matching autofix command.
    */
  private val seededFailure =
    FailureClassifiedAction.draft(
      featureId,
      p1,
      gate = "ci",
      classification = Classification(
        FailureKind.DeterministicFix,
        0.97,
        Some(CommandKind.Format),
        "scalafmt: 1 files must be formatted"
      ),
      route = FixupRoute.DriverFixup("scalafmt: 1 files must be formatted"),
      source = "rules"
    )

  /** Drive a single-piece feature DesignReady → FeatureDone, optionally seeding a classified failure into the log
    * first. Returns the terminal feature + the saved-profile slot + the learner call count + the resolved paths.
    */
  /** A FakeSideEffects whose conventions-PR open fails — exercises the orchestrator's persist-locally fallback. */
  private class PrFailingSideEffects extends FakeSideEffects(designPr, _ => piecePr):
    override def openConventionsPr(feature: Feature, proposal: ClaudeMdProposal): IO[Either[String, PrNumber]] =
      IO.pure(Left("gh unavailable"))

  private def driveToDone(
      root: os.Path,
      config: ForgeConfig,
      seedFailure: Boolean,
      sideEffects: SideEffects = new FakeSideEffects(designPr, _ => piecePr)
  ): (Feature, AtomicReference[Option[RepoProfile]], AtomicInteger, ForgePaths) =
    val paths = new ForgePaths(repoRoot = root)
    val saved = new AtomicReference[Option[RepoProfile]](None)
    val learnCalls = new AtomicInteger(0)
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.DesignReady)

    val out = (for
      logImpl <- FileActionLog(paths)
      _ <- if seedFailure then logImpl.append(featureId, seededFailure).void else IO.unit
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
      monitor <- FakeSessionMonitor.make(MonitorOutcome.Settled(SessionPhase.Implement, SettleOutcome.Clean))
      hookCache = new HookStateCache(new FileStateCache(paths), f => offerMergeOnAwaitingMerge(watcher, f))
      orch = new Orchestrator(
        sideEffects,
        monitor,
        watcher,
        learningReviewer(learnCalls),
        new FileSpecStore(paths),
        new FileManifestStore(paths),
        logImpl,
        hookCache,
        paths,
        config,
        profileStore = new RecordingProfileStore(saved)
      )
      out <- orch.drive(start)
    yield out).unsafeRunSync()
    (out, saved, learnCalls, paths)

  tempFixture.test(
    "FeatureDone with a classified failure: learner consulted → profile delta saved, CLAUDE.md PR opened"
  ): root =>
    val (out, saved, learnCalls, paths) = driveToDone(root, testConfig, seedFailure = true)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    // The learner was consulted exactly once, on the transition to FeatureDone.
    assertEquals(learnCalls.get(), 1)
    // The fresh `format` command was merged into the committed profile (a human-reviewable diff).
    val savedProfile = saved.get().getOrElse(fail("expected ProfileStore.save to have been called"))
    assert(savedProfile.command(CommandKind.Format).exists(_.autofix), savedProfile.commands.toString)
    // The existing Build command is preserved (additive merge).
    assert(savedProfile.command(CommandKind.Build).isDefined)
    // §11.7: the proposed CLAUDE.md edit is opened as a PR (#900 from the fake) — its number is recorded in the audit.
    val log = os.read(paths.featureLog(featureId))
    assert(log.contains("\"profile.conventions_learned\""), log)
    assert(log.contains("\"claudeMdPrNumber\":900"), log)
    // The PR succeeded, so the local persist-fallback was NOT triggered.
    assert(!os.exists(paths.audit(featureId, "learned-conventions.md")))

  tempFixture.test("conventions PR fails to open → proposal persisted locally as a fallback, no PR number logged"):
    root =>
      val (out, _, learnCalls, paths) =
        driveToDone(root, testConfig, seedFailure = true, sideEffects = new PrFailingSideEffects)

      assertEquals(out.state, FsmState.FeatureDone: FsmState)
      assertEquals(learnCalls.get(), 1)
      // The PR open failed, so the proposal is persisted to the audit dir so it is not lost.
      val proposal = os.read(paths.audit(featureId, "learned-conventions.md"))
      assert(proposal.contains("sbt scalafmtAll"), proposal)
      assert(proposal.contains("could not open a PR"), proposal)
      // The action still records the learning, with a null PR number.
      val log = os.read(paths.featureLog(featureId))
      assert(log.contains("\"profile.conventions_learned\""), log)
      assert(log.contains("\"claudeMdPrNumber\":null"), log)

  tempFixture.test("adapt.conventionLearner = false: learner is never consulted, nothing saved or logged"): root =>
    val cfg = testConfig.copy(adapt = testConfig.adapt.copy(conventionLearner = false))
    val (out, saved, learnCalls, paths) = driveToDone(root, cfg, seedFailure = true)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    assertEquals(learnCalls.get(), 0)
    assertEquals(saved.get(), None)
    assert(!os.exists(paths.audit(featureId, "learned-conventions.md")))
    assert(!os.read(paths.featureLog(featureId)).contains("\"profile.conventions_learned\""))

  tempFixture.test("no classified failure in the log: cost lever skips the learner entirely"): root =>
    val (out, saved, learnCalls, paths) = driveToDone(root, testConfig, seedFailure = false)

    assertEquals(out.state, FsmState.FeatureDone: FsmState)
    // The §7.11 cost lever: a clean run (no profile.failure_classified) spends no learner reviewer-call.
    assertEquals(learnCalls.get(), 0)
    assertEquals(saved.get(), None)
    assert(!os.read(paths.featureLog(featureId)).contains("\"profile.conventions_learned\""))
