package io.forge.app.orchestrator

import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.{FeatureId, Mode}
import io.forge.core.fsm.FsmState
import io.forge.core.paths.ForgePaths

import OrchestratorTestKit.*

/** Task 1.4.10-d2c — construction smoke for the real wiring. Builds a fully-real [[Orchestrator]] (every Slice 1–3
  * collaborator: connector, git/gh/branch-manager, watcher, retrying reviewer, the `File*` triad) and drives it from an
  * already loop-terminal `Feature`. Because the start state is terminal the loop returns immediately without touching
  * the connector, git, or gh — so this exercises [[OrchestratorBuilder]] + [[ConnectorFactory]] for both modes with no
  * real CLI on PATH. The full real-CLI drive is the opt-in `forge-it` smoke + the Task 1.4.16 MVP gate.
  */
class OrchestratorBuilderSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-build-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")

  private def drivesTerminal(mode: Mode, root: os.Path): FsmState =
    val paths = new ForgePaths(root)
    val terminal = featureAt(featureId, mkManifest(featureId, Vector.empty), FsmState.FeatureDone)
    (for
      built <- OrchestratorBuilder.build(mode, paths, ForgeConfig.Default)
      out <- built._1.drive(terminal)
    yield out.state).unsafeRunSync()

  tempFixture.test("ClaudeDriver: builder yields an orchestrator that returns the terminal state"): root =>
    assertEquals(drivesTerminal(Mode.ClaudeDriver, root), FsmState.FeatureDone: FsmState)

  tempFixture.test("CodexDriver: builder yields an orchestrator that returns the terminal state"): root =>
    // Also exercises ConnectorFactory's price-table load (degrades to empty when no prices file is present).
    assertEquals(drivesTerminal(Mode.CodexDriver, root), FsmState.FeatureDone: FsmState)
