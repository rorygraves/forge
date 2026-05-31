package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber}
import io.forge.core.fsm.{Feature, FsmState, ResumeHint, UserCommand}
import io.forge.core.manifest.Manifest
import io.forge.core.paths.ForgePaths
import io.forge.git.branch.ForgeCommand

/** Task 1.4.13 M5 / M8 — the pure CLI-flag → `UserCommand` derivation that backs `forge resume` / `forge abandon`, plus
  * the git-free manifest-not-found short-circuit each handler shares. The persist + drive half is covered in
  * `OrchestratorUserCommandSuite`.
  */
class UserCommandHandlerSuite extends munit.FunSuite:

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val p2 = PieceId("p2")
  private val pr = PrNumber(200)

  private def feature(state: FsmState): Feature =
    val m = Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = featureId.value,
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector.empty
    )
    Feature.initial(featureId, m).copy(state = state)

  // ---------------------------------------------------------------------------
  // AbandonFeature.deriveAbandon
  // ---------------------------------------------------------------------------

  test("deriveAbandon: an active state yields Abandon"):
    AbandonFeature.deriveAbandon(feature(FsmState.PieceImplementing(p1))) match
      case Right(UserCommand.Abandon(_)) => ()
      case other => fail(s"expected Right(Abandon), got $other")

  test("deriveAbandon: NeedsHumanIntervention is abandonable (NHI is non-terminal)"):
    val nhi = FsmState.NeedsHumanIntervention("stuck", ResumeHint.AbortOrAbandon)
    AbandonFeature.deriveAbandon(feature(nhi)) match
      case Right(UserCommand.Abandon(_)) => ()
      case other => fail(s"expected Right(Abandon), got $other")

  test("deriveAbandon: an already-terminal feature is rejected"):
    assert(AbandonFeature.deriveAbandon(feature(FsmState.FeatureDone)).isLeft)
    assert(AbandonFeature.deriveAbandon(feature(FsmState.Abandoned("done"))).isLeft)

  // ---------------------------------------------------------------------------
  // RefreshCacheFeature.deriveRefreshCache (M7)
  // ---------------------------------------------------------------------------

  test("deriveRefreshCache: an active state yields RefreshCache"):
    assertEquals(
      RefreshCacheFeature.deriveRefreshCache(feature(FsmState.PieceImplementing(p1))),
      Right(UserCommand.RefreshCache)
    )

  test("deriveRefreshCache: NeedsHumanIntervention is refreshable (NHI is non-terminal)"):
    val nhi = FsmState.NeedsHumanIntervention("stuck", ResumeHint.AbortOrAbandon)
    assertEquals(RefreshCacheFeature.deriveRefreshCache(feature(nhi)), Right(UserCommand.RefreshCache))

  test("deriveRefreshCache: an already-terminal feature is rejected"):
    assert(RefreshCacheFeature.deriveRefreshCache(feature(FsmState.FeatureDone)).isLeft)
    assert(RefreshCacheFeature.deriveRefreshCache(feature(FsmState.Abandoned("done"))).isLeft)

  // ---------------------------------------------------------------------------
  // ResumeFeature.deriveResume
  // ---------------------------------------------------------------------------

  test("deriveResume: a matching flag + piece uses the stored hint (with its PR number)"):
    val stored = ResumeHint.ResumeAfterHumanPush(p1, pr)
    val nhi = FsmState.NeedsHumanIntervention("CI never reported", stored)
    val cmd = ForgeCommand.ResumeAfterHumanPush(featureId, p1)
    assertEquals(ResumeFeature.deriveResume(cmd, feature(nhi)), Right(UserCommand.Resume(stored)))

  test("deriveResume: a flag naming a different hint kind is rejected"):
    val stored = ResumeHint.ResumeAfterHumanPush(p1, pr)
    val nhi = FsmState.NeedsHumanIntervention("CI never reported", stored)
    val cmd = ForgeCommand.ResumeRunFixup(featureId, p1)
    assert(ResumeFeature.deriveResume(cmd, feature(nhi)).isLeft)

  test("deriveResume: a flag naming a different piece is rejected"):
    val stored = ResumeHint.ResumeAfterHumanPush(p1, pr)
    val nhi = FsmState.NeedsHumanIntervention("CI never reported", stored)
    val cmd = ForgeCommand.ResumeAfterHumanPush(featureId, p2)
    assert(ResumeFeature.deriveResume(cmd, feature(nhi)).isLeft)

  test("deriveResume: a feature not in NHI is rejected"):
    val cmd = ForgeCommand.ResumeAfterHumanPush(featureId, p1)
    assert(ResumeFeature.deriveResume(cmd, feature(FsmState.PieceImplementing(p1))).isLeft)

  // ---------------------------------------------------------------------------
  // manifest-not-found short-circuit (git-free)
  // ---------------------------------------------------------------------------

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-usercmd-handler-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  tempFixture.test("forge abandon on a non-existent feature → exit 1"): root =>
    val code = AbandonFeature.execute(new ForgePaths(root), ForgeConfig.Default, featureId).unsafeRunSync()
    assertEquals(code, ExitCode(1))

  tempFixture.test("forge resume on a non-existent feature → exit 1"): root =>
    val cmd = ForgeCommand.ResumeAfterHumanPush(featureId, p1)
    val code = ResumeFeature.execute(new ForgePaths(root), ForgeConfig.Default, cmd).unsafeRunSync()
    assertEquals(code, ExitCode(1))

  tempFixture.test("forge refresh-cache on a non-existent feature → exit 1"): root =>
    val code = RefreshCacheFeature.execute(new ForgePaths(root), ForgeConfig.Default, featureId).unsafeRunSync()
    assertEquals(code, ExitCode(1))
