package io.forge.agents

import io.forge.core.profile.RepoProfile

/** Task 3.0.3 — `ReviewDecoders.repoProfile` validation. The load-bearing case is the second test: the decoder, fed the
  * **hand-authored `szork` / `forge` fixtures** (the committed `.forge/profile.json` exemplars the §0 exit criterion
  * leans on), produces exactly the `RepoProfile` upickle deserialises from the same JSON. That proves the LLM-shaped
  * structured-output decode and the canonical model agree, so a `forge profile` run that emits a fixture-shaped object
  * reconstructs the intended profile. The fixtures are read from the canonical `forge-core` test resources (sbt runs
  * tests with cwd = repo root, as `ForgePathsSuite` relies on) so there is no second copy to drift.
  */
class RepoProfileDecoderSuite extends munit.FunSuite:

  private val fixturesDir = os.pwd / "modules" / "forge-core" / "src" / "test" / "resources" / "profiles"

  private def decode(json: String): Either[String, RepoProfile] =
    ReviewDecoders.repoProfile(ujson.read(json))

  test("decodes a minimal repo-profile object"):
    val json =
      """{
        |  "buildTool": "sbt",
        |  "commands": [
        |    { "kind": "format", "argv": ["sbt", "scalafmtAll"], "determinism": "deterministic", "required": true, "autofix": true }
        |  ],
        |  "commitIdentity": { "name": "forge[bot]", "email": "forge@users.noreply.github.com" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": ["backend"], "branchModel": "trunk_based", "mergeStrategy": "squash" }
        |}""".stripMargin
    val profile = decode(json).fold(e => fail(s"expected Right, got Left($e)"), identity)
    assertEquals(profile.schemaVersion, RepoProfile.CurrentSchemaVersion)
    assertEquals(profile.buildTool, "sbt")
    assertEquals(profile.commands.size, 1)
    val fmt = profile.commands.head
    assert(fmt.autofix && fmt.required)
    assertEquals(fmt.argv, Vector("sbt", "scalafmtAll"))
    assertEquals(profile.workflow.ciRequiredChecks, Vector("backend"))

  test("decoder agrees with the canonical model on the hand-authored szork / forge fixtures"):
    for leaf <- List("szork.json", "forge.json") do
      val json = os.read(fixturesDir / leaf)
      val expected = RepoProfile.fromJson(json)
      val decoded = decode(json).fold(e => fail(s"$leaf: expected Right, got Left($e)"), identity)
      assertEquals(decoded, expected, s"$leaf: decoder disagreed with RepoProfile.fromJson")
      assertEquals(decoded.contentHash, expected.contentHash, s"$leaf: content hash drift")

  test("schemaVersion is stamped from the current constant, not read from input"):
    // No schemaVersion in the input; the decoder must still produce the current version (it is a Forge concern).
    val json =
      """{
        |  "buildTool": "npm",
        |  "commands": [],
        |  "commitIdentity": { "name": "forge[bot]", "email": "forge@users.noreply.github.com" },
        |  "workflow": { "reviewRequired": false, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "merge" }
        |}""".stripMargin
    assertEquals(decode(json).map(_.schemaVersion), Right(RepoProfile.CurrentSchemaVersion))

  test("a stray schemaVersion in the input is ignored, not honoured"):
    val json =
      """{
        |  "schemaVersion": 999,
        |  "buildTool": "sbt",
        |  "commands": [],
        |  "commitIdentity": { "name": "forge[bot]", "email": "forge@users.noreply.github.com" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "squash" }
        |}""".stripMargin
    assertEquals(decode(json).map(_.schemaVersion), Right(RepoProfile.CurrentSchemaVersion))

  test("unknown command kind is a Left, not a silent drop"):
    val json =
      """{
        |  "buildTool": "sbt",
        |  "commands": [
        |    { "kind": "deploy", "argv": ["sbt", "deploy"], "determinism": "deterministic", "required": true, "autofix": false }
        |  ],
        |  "commitIdentity": { "name": "x", "email": "y" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "squash" }
        |}""".stripMargin
    assert(decode(json).left.exists(_.contains("commands[0].kind")), clue = decode(json))

  test("non-boolean autofix is a Left"):
    val json =
      """{
        |  "buildTool": "sbt",
        |  "commands": [
        |    { "kind": "format", "argv": ["sbt", "scalafmtAll"], "determinism": "deterministic", "required": true, "autofix": "yes" }
        |  ],
        |  "commitIdentity": { "name": "x", "email": "y" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "squash" }
        |}""".stripMargin
    assert(decode(json).left.exists(_.contains("autofix")), clue = decode(json))

  test("unknown branchModel / mergeStrategy is a Left"):
    val json =
      """{
        |  "buildTool": "sbt",
        |  "commands": [],
        |  "commitIdentity": { "name": "x", "email": "y" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "feature_flow", "mergeStrategy": "squash" }
        |}""".stripMargin
    assert(decode(json).left.exists(_.contains("branchModel")), clue = decode(json))

  test("missing required field (buildTool) is a Left"):
    val json =
      """{
        |  "commands": [],
        |  "commitIdentity": { "name": "x", "email": "y" },
        |  "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "squash" }
        |}""".stripMargin
    assert(decode(json).left.exists(_.contains("buildTool")), clue = decode(json))

  test("missing commitIdentity / workflow is a Left"):
    val noIdentity =
      """{ "buildTool": "sbt", "commands": [], "workflow": { "reviewRequired": true, "ciRequiredChecks": [], "branchModel": "trunk_based", "mergeStrategy": "squash" } }"""
    assert(decode(noIdentity).left.exists(_.contains("commitIdentity")), clue = decode(noIdentity))
    val noWorkflow =
      """{ "buildTool": "sbt", "commands": [], "commitIdentity": { "name": "x", "email": "y" } }"""
    assert(decode(noWorkflow).left.exists(_.contains("workflow")), clue = decode(noWorkflow))
