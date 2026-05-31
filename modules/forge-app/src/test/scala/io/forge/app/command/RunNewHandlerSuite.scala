package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.{BranchName, FeatureId, Mode}
import io.forge.core.manifest.Manifest
import io.forge.core.paths.ForgePaths
import io.forge.specs.FileSpecStore

/** Task 1.4.10-d2c — git-free handler paths. `forge run` with no manifest, and `forge new` over an existing feature,
  * both short-circuit before any real git/gh/connector work, so they are unit-testable in `forge-app`. The full
  * scaffold + headless-drive happy paths (real `git`/CLI) live in the opt-in `forge-it` smoke + Task 1.4.16.
  */
class RunNewHandlerSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-handler-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")

  private def seedManifest(paths: ForgePaths): Unit =
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
    val _ = new FileSpecStore(paths).saveManifest(featureId, m).unsafeRunSync()

  tempFixture.test("forge run on an undesigned feature → exit 1 (no manifest yet)"): root =>
    val code = RunFeature.execute(new ForgePaths(root), ForgeConfig.Default, featureId).unsafeRunSync()
    assertEquals(code, ExitCode(1))

  tempFixture.test("forge new on an existing feature → exit 1 (refuses to re-scaffold)"): root =>
    val paths = new ForgePaths(root)
    seedManifest(paths)
    val code = NewFeature.scaffold(paths, ForgeConfig.Default, featureId).unsafeRunSync()
    assertEquals(code, ExitCode(1))
