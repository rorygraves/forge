package io.forge.app.config

import cats.effect.unsafe.implicits.global
import io.forge.core.Mode
import io.forge.core.paths.ForgePaths
import io.forge.specs.StagingConfig

/** Task 1.4.9 I4 — [[ForgeConfigLoader]] over a real temp repo.
  *
  * Covers the four I4 cases: §18 defaults (missing file), partial config (missing keys default in), malformed JSON
  * (typed error), and per-key `.forge/overrides/<key>.json` resolution — plus the non-object-root and
  * override-replaces-key edge cases the lenient-merge contract implies.
  */
class ForgeConfigLoaderSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[ForgePaths](
    setup = _ => new ForgePaths(os.temp.dir(prefix = "forge-config-")),
    teardown = paths => if os.exists(paths.repoRoot) then os.remove.all(paths.repoRoot)
  )

  private def writeConfig(paths: ForgePaths, json: String): Unit =
    os.write.over(paths.configFile, json, createFolders = true)

  private def writeOverride(paths: ForgePaths, key: String, json: String): Unit =
    os.write.over(paths.overrideFile(key), json, createFolders = true)

  tempFixture.test("missing config.json yields the §18 defaults") { paths =>
    assertEquals(ForgeConfigLoader.loadSync(paths), Right(ForgeConfig.Default))
  }

  tempFixture.test("partial config.json defaults the unset keys in") { paths =>
    writeConfig(paths, """{ "baseBranch": "develop", "maxFixupRounds": 5 }""")
    val Right(config) = ForgeConfigLoader.loadSync(paths): @unchecked
    assertEquals(config.baseBranch, "develop")
    assertEquals(config.maxFixupRounds, 5)
    // everything else stays at the §18 default
    assertEquals(config.branchPrefix, "forge")
    assertEquals(config.mode, Mode.ClaudeDriver)
    assertEquals(config.claude.model, "default")
    assertEquals(config.staging.denyPatterns, StagingConfig.DefaultDenyPatterns)
  }

  tempFixture.test("a full config.json round-trips nested blocks") { paths =>
    writeConfig(
      paths,
      """{
        |  "mode": "codex-driver",
        |  "claude": { "model": "haiku", "reviewProcessRetries": 4 },
        |  "settle": { "implementTimeoutSec": 2400 },
        |  "github": { "pushSnapshotTags": true }
        |}""".stripMargin
    )
    val Right(config) = ForgeConfigLoader.loadSync(paths): @unchecked
    assertEquals(config.mode, Mode.CodexDriver)
    assertEquals(config.claude.model, "haiku")
    assertEquals(config.claude.reviewProcessRetries, 4)
    // partial nested block defaults its own unset keys
    assertEquals(config.claude.refineProcessRetries, 2)
    assertEquals(config.settle.implementTimeoutSec, 2400)
    assertEquals(config.settle.specTimeoutSec, 300)
    assert(config.github.pushSnapshotTags)
  }

  tempFixture.test("malformed config.json surfaces Malformed at the config path") { paths =>
    writeConfig(paths, """{ "baseBranch": """)
    ForgeConfigLoader.loadSync(paths) match
      case Left(ConfigError.Malformed(path, _)) => assertEquals(path, paths.configFile)
      case other => fail(s"expected Malformed(configFile), got $other")
  }

  tempFixture.test("a non-object config.json root is Malformed, not silently ignored") { paths =>
    writeConfig(paths, """[1, 2, 3]""")
    ForgeConfigLoader.loadSync(paths) match
      case Left(ConfigError.Malformed(path, _)) => assertEquals(path, paths.configFile)
      case other => fail(s"expected Malformed(configFile), got $other")
  }

  tempFixture.test("an invalid enum value surfaces Malformed") { paths =>
    writeConfig(paths, """{ "mode": "nonsense-driver" }""")
    ForgeConfigLoader.loadSync(paths) match
      case Left(ConfigError.Malformed(path, _)) => assertEquals(path, paths.configFile)
      case other => fail(s"expected Malformed(configFile), got $other")
  }

  tempFixture.test("per-key override replaces the whole top-level key, defaulting its unset sub-keys") { paths =>
    writeConfig(paths, """{ "claude": { "model": "haiku", "reviewProcessRetries": 4 } }""")
    writeOverride(paths, "claude", """{ "model": "opus" }""")
    val Right(config) = ForgeConfigLoader.loadSync(paths): @unchecked
    // override wins on model...
    assertEquals(config.claude.model, "opus")
    // ...and because override replaces the key, reviewProcessRetries falls back to the §18 default (not the base 4)
    assertEquals(config.claude.reviewProcessRetries, 2)
  }

  tempFixture.test("a scalar override (mode) applies") { paths =>
    writeConfig(paths, """{ "baseBranch": "develop" }""")
    writeOverride(paths, "mode", "\"codex-driver\"")
    val Right(config) = ForgeConfigLoader.loadSync(paths): @unchecked
    assertEquals(config.mode, Mode.CodexDriver)
    assertEquals(config.baseBranch, "develop") // untouched key survives
  }

  tempFixture.test("an override applies even with no base config.json present") { paths =>
    writeOverride(paths, "branchPrefix", "\"flow\"")
    val Right(config) = ForgeConfigLoader.loadSync(paths): @unchecked
    assertEquals(config.branchPrefix, "flow")
  }

  tempFixture.test("a malformed override surfaces Malformed at the override path") { paths =>
    writeConfig(paths, "{}")
    writeOverride(paths, "claude", """{ "model": """)
    ForgeConfigLoader.loadSync(paths) match
      case Left(ConfigError.Malformed(path, _)) => assertEquals(path, paths.overrideFile("claude"))
      case other => fail(s"expected Malformed(overrides/claude.json), got $other")
  }

  tempFixture.test("the IO entry point threads through to the same result") { paths =>
    writeConfig(paths, """{ "baseBranch": "trunk" }""")
    val config = ForgeConfigLoader.load(paths).unsafeRunSync()
    assertEquals(config.map(_.baseBranch), Right("trunk"))
  }
