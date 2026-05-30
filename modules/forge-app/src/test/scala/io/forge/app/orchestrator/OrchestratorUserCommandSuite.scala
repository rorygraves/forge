package io.forge.app.orchestrator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmState, ResumeHint, UserCommand}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Task 1.4.13 M5 / M8 — the orchestrator half of `forge resume` / `forge abandon`: [[Orchestrator.applyUserCommandTo]]
  * applies one operator `UserCommand` as a one-shot FSM transition, persists it through the J4 pipeline, then drives to
  * a loop-terminal state. Driven against scripted fakes + real `File*` stores in a temp tree (same shape as the J5 e2e
  * suites), so the persist + drive are the genuine ones. The CLI-flag → hint derivation is unit-tested separately in
  * `UserCommandHandlerSuite`.
  */
class OrchestratorUserCommandSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-usercmd-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")

  private def orchestratorFor(root: os.Path, watcher: FakePRWatcher, hookCache: HookStateCache): IO[Orchestrator] =
    val paths = new ForgePaths(repoRoot = root)
    for
      logImpl <- FileActionLog(paths)
      monitor <- FakeSessionMonitor.make() // no driver states are visited by these commands
    yield new Orchestrator(
      new FakeSideEffects(PrNumber(100), _ => PrNumber(200)),
      monitor,
      watcher,
      FakeReviewerCall.happyPath,
      new FileSpecStore(paths),
      new FileManifestStore(paths),
      logImpl,
      hookCache,
      paths,
      ForgeConfig.Default
    )

  // ---------------------------------------------------------------------------
  // abandon
  // ---------------------------------------------------------------------------

  tempFixture.test("abandon from a mid-flight state → Driven(Abandoned), persisted, no driver re-spawn"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.PieceImplementing(p1))

    val outcome = (for
      watcher <- FakePRWatcher.make
      orch <- orchestratorFor(root, watcher, new HookStateCache(cache, _ => IO.unit))
      out <- orch.applyUserCommandTo(start, _ => Right(UserCommand.Abandon("operator stopped it")))
    yield out).unsafeRunSync()

    outcome match
      case Orchestrator.CommandOutcome.Driven(terminal) =>
        assertEquals(terminal.state, FsmState.Abandoned("operator stopped it"): FsmState)
        // Persisted to the rebuildable cache (J4): a fresh load reads the terminal state back.
        val reloaded = cache.load(featureId).unsafeRunSync()
        assertEquals(reloaded.map(_.state), Some(FsmState.Abandoned("operator stopped it"): FsmState))
      case other => fail(s"expected Driven(Abandoned), got $other")

  tempFixture.test("abandon when the FSM would no-op → Rejected (the unchanged-state backstop)"): root =>
    // `UserCommand.Done` only applies in InteractiveSpec; from PieceImplementing it is an FSM no-op, which
    // applyUserCommandTo must surface as Rejected rather than driving an unchanged feature.
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.PieceImplementing(p1))

    val outcome = (for
      watcher <- FakePRWatcher.make
      cache = new FileStateCache(new ForgePaths(repoRoot = root))
      orch <- orchestratorFor(root, watcher, new HookStateCache(cache, _ => IO.unit))
      out <- orch.applyUserCommandTo(start, _ => Right(UserCommand.Done))
    yield out).unsafeRunSync()

    outcome match
      case Orchestrator.CommandOutcome.Rejected(state, _) =>
        assertEquals(state, FsmState.PieceImplementing(p1): FsmState)
      case other => fail(s"expected Rejected, got $other")

  tempFixture.test("a derive that rejects up front → Rejected, nothing persisted"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.FeatureDone)

    val outcome = (for
      watcher <- FakePRWatcher.make
      orch <- orchestratorFor(root, watcher, new HookStateCache(cache, _ => IO.unit))
      out <- orch.applyUserCommandTo(start, _ => Left("feature is already complete; nothing to abandon"))
    yield out).unsafeRunSync()

    outcome match
      case Orchestrator.CommandOutcome.Rejected(state, reason) =>
        assertEquals(state, FsmState.FeatureDone: FsmState)
        assertEquals(reason, "feature is already complete; nothing to abandon")
        // No transition was applied, so no cache was written.
        assertEquals(cache.load(featureId).unsafeRunSync(), None)
      case other => fail(s"expected Rejected, got $other")

  // ---------------------------------------------------------------------------
  // resume
  // ---------------------------------------------------------------------------

  tempFixture.test("resume from NHI(ResumeAfterHumanPush) bumps the cache epoch and drives to FeatureDone"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val pr = PrNumber(200)
    // A resumed-after-push piece is mid-implementation: its branch (baseSha) + PR already exist in the manifest.
    val piece = piecePending(p1, 1).copy(
      status = io.forge.core.manifest.PieceStatus.InProgress,
      baseSha = Some(BaseSha),
      prNumber = Some(pr)
    )
    val m = mkManifest(featureId, Vector(piece))
    val hint = ResumeHint.ResumeAfterHumanPush(p1, pr)
    val start = featureAt(featureId, m, FsmState.NeedsHumanIntervention("CI never reported", hint))

    val outcome = (for
      watcher <- FakePRWatcher.make
      _ <- watcher.offer(pr, snapshotResult(ciReadySnapshot(pr)))
      // The merge snapshot is delivered once the loop saves PieceAwaitingMerge (mirrors the J5 happy-path idiom).
      hookCache = new HookStateCache(
        cache,
        f =>
          f.state match
            case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
            case _ => IO.unit
      )
      orch <- orchestratorFor(root, watcher, hookCache)
      out <- orch.applyUserCommandTo(start, _ => Right(UserCommand.Resume(hint)))
    yield out).unsafeRunSync()

    outcome match
      case Orchestrator.CommandOutcome.Driven(terminal) =>
        assertEquals(terminal.state, FsmState.FeatureDone: FsmState)
        // §8.1: every forge resume bumps the branch-protection cache epoch (start was 0).
        assertEquals(terminal.branchProtectionCacheEpoch, start.branchProtectionCacheEpoch + 1)
      case other => fail(s"expected Driven(FeatureDone), got $other")
