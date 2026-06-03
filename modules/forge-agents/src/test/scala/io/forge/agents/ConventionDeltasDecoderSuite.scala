package io.forge.agents

import io.forge.core.profile.{CommandKind, ConventionDeltas, Determinism, RepoCommand}

/** Task 3.2 — `ReviewDecoders.conventionDeltas` validation. The decoder turns the LLM `ConventionLearner`'s structured
  * reply (matching `convention-deltas.json`) into a `forge-core` [[ConventionDeltas]]. `addCommands` reuses the same
  * per-command decode as `repo-profile.json` (so the accepted enum wire strings can't drift); `claudeMdProposal` is the
  * nullable channel (absent / null ⇒ `None`). The load-bearing case: the canonical dogfood proposal — add the `format`
  * autofix the profile was missing — round-trips into a `RepoCommand` the orchestrator can merge.
  */
class ConventionDeltasDecoderSuite extends munit.FunSuite:

  private def decode(json: String): Either[String, ConventionDeltas] =
    ReviewDecoders.conventionDeltas(ujson.read(json))

  test("decodes an addCommands delta + a CLAUDE.md proposal (the canonical dogfood learning)"):
    val json =
      """{ "addCommands": [
        |   { "kind": "format", "argv": ["sbt", "scalafmtAll"], "determinism": "deterministic",
        |     "required": true, "autofix": true } ],
        |  "claudeMdProposal": { "rationale": "two fix-up rounds were spent on formatting",
        |     "suggestedAddition": "Run `sbt scalafmtAll` before settling." },
        |  "summary": "add the format autofix gate" }""".stripMargin
    val d = decode(json).fold(e => fail(s"expected Right, got Left($e)"), identity)
    assertEquals(
      d.addCommands,
      Vector(RepoCommand(CommandKind.Format, Vector("sbt", "scalafmtAll"), Determinism.Deterministic, true, true))
    )
    assertEquals(d.claudeMdProposal.map(_.suggestedAddition), Some("Run `sbt scalafmtAll` before settling."))
    assertEquals(d.summary, "add the format autofix gate")

  test("empty addCommands + null proposal decodes (the learner proposes nothing)"):
    val json = """{ "addCommands": [], "claudeMdProposal": null, "summary": "nothing actionable" }"""
    val d = decode(json).fold(e => fail(s"expected Right, got Left($e)"), identity)
    assertEquals(d.addCommands, Vector.empty[RepoCommand])
    assertEquals(d.claudeMdProposal, None)

  test("absent addCommands defaults to empty (additive-schema tolerance)"):
    val json = """{ "claudeMdProposal": null, "summary": "doc only" }"""
    assertEquals(decode(json).map(_.addCommands), Right(Vector.empty[RepoCommand]))

  test("absent claudeMdProposal decodes to None"):
    val json = """{ "addCommands": [], "summary": "x" }"""
    assertEquals(decode(json).map(_.claudeMdProposal), Right(None))

  test("an unknown command kind inside addCommands is a Left (reuses the shared enum parse)"):
    val json =
      """{ "addCommands": [ { "kind": "deploy", "argv": ["x"], "determinism": "deterministic",
        |  "required": true, "autofix": false } ], "claudeMdProposal": null, "summary": "x" }""".stripMargin
    assert(decode(json).left.exists(_.contains("kind")), clue = decode(json))

  test("a claudeMdProposal missing suggestedAddition is a Left"):
    val json =
      """{ "addCommands": [], "claudeMdProposal": { "rationale": "why" }, "summary": "x" }"""
    assert(decode(json).left.exists(_.contains("suggestedAddition")), clue = decode(json))

  test("missing summary is a Left"):
    val json = """{ "addCommands": [], "claudeMdProposal": null }"""
    assert(decode(json).left.exists(_.contains("summary")), clue = decode(json))
