package io.forge.agents

import io.forge.core.profile.{Classification, CommandKind, FailureKind}

/** Task 3.1.4 — `ReviewDecoders.failureClassification` validation. The decoder turns the LLM `FailureClassifier`'s
  * structured reply (matching `failure-classifier.json`) into a `forge-core` [[Classification]] the spine routes on. It
  * reuses the same `fromString` enum parsers as the rules classifier, so the accepted wire strings can't drift from the
  * model the deterministic `FailureRouting.route` consumes. The load-bearing cases: a `deterministic_fix` carries a
  * `suggested` `CommandKind` (the only kind that can collapse to a local run), and an absent / null `suggested` decodes
  * to `None` rather than failing.
  */
class FailureClassificationDecoderSuite extends munit.FunSuite:

  private def decode(json: String): Either[String, Classification] =
    ReviewDecoders.failureClassification(ujson.read(json))

  test("decodes a deterministic_fix with a suggested format command"):
    val json =
      """{ "kind": "deterministic_fix", "confidence": 0.97, "suggested": "format",
        |  "evidence": "scalafmt: 1 files must be formatted" }""".stripMargin
    val c = decode(json).fold(e => fail(s"expected Right, got Left($e)"), identity)
    assertEquals(c.kind, FailureKind.DeterministicFix)
    assertEquals(c.confidence, 0.97)
    assertEquals(c.suggested, Some(CommandKind.Format))
    assertEquals(c.evidence, "scalafmt: 1 files must be formatted")

  test("absent suggested decodes to None"):
    val json = """{ "kind": "code_fix", "confidence": 0.85, "evidence": "type mismatch" }"""
    assertEquals(decode(json).map(_.suggested), Right(None))

  test("explicit null suggested decodes to None"):
    val json = """{ "kind": "flaky", "confidence": 0.6, "suggested": null, "evidence": "connection reset" }"""
    assertEquals(decode(json).map(_.suggested), Right(None))

  test("an Unknown classification round-trips (the LLM's honest 'I can't tell')"):
    val json = """{ "kind": "unknown", "confidence": 0.3, "evidence": "some opaque failure" }"""
    val c = decode(json).fold(e => fail(s"expected Right, got Left($e)"), identity)
    assertEquals(c.kind, FailureKind.Unknown)
    assertEquals(c.suggested, None)

  test("unknown kind is a Left, not a silent default"):
    val json = """{ "kind": "deploy_failed", "confidence": 0.5, "evidence": "x" }"""
    assert(decode(json).left.exists(_.contains("kind")), clue = decode(json))

  test("unknown suggested command kind is a Left"):
    val json = """{ "kind": "deterministic_fix", "confidence": 0.9, "suggested": "deploy", "evidence": "x" }"""
    assert(decode(json).left.exists(_.contains("suggested")), clue = decode(json))

  test("missing confidence is a Left"):
    val json = """{ "kind": "code_fix", "evidence": "x" }"""
    assert(decode(json).left.exists(_.contains("confidence")), clue = decode(json))

  test("missing evidence is a Left"):
    val json = """{ "kind": "code_fix", "confidence": 0.8 }"""
    assert(decode(json).left.exists(_.contains("evidence")), clue = decode(json))

  test("non-number confidence is a Left"):
    val json = """{ "kind": "code_fix", "confidence": "high", "evidence": "x" }"""
    assert(decode(json).left.exists(_.contains("confidence")), clue = decode(json))
