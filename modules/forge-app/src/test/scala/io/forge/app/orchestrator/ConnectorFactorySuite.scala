package io.forge.app.orchestrator

import cats.effect.unsafe.implicits.global
import io.forge.app.config.ForgeConfig
import io.forge.core.Cli
import io.forge.core.paths.ForgePaths

/** Task 1.4.10-d2b J3 — the connector factory builds the §7.1 connector for a `Cli` (resolved from the feature's `Mode`
  * via `RolePairing`, design-3.5 Task 3.5.2). The connector is constructed (no subprocess spawns at build time), so
  * these assertions are cheap and offline.
  */
class ConnectorFactorySuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-cf-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  tempFixture.test("Cli.Claude builds the claude connector"): repo =>
    val paths = ForgePaths(repo, repo / "home")
    val connector = ConnectorFactory.build(Cli.Claude, paths, ForgeConfig.Default).unsafeRunSync()
    assertEquals(connector.name, "claude")

  tempFixture.test("Cli.Codex builds the codex connector (missing price table degrades to empty)"): repo =>
    val paths = ForgePaths(repo, repo / "home")
    val connector = ConnectorFactory.build(Cli.Codex, paths, ForgeConfig.Default).unsafeRunSync()
    assertEquals(connector.name, "codex")
