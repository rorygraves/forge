package io.forge.agents

import io.forge.core.{Question, QuestionSeverity}

class HaltWithQuestionSuite extends munit.FunSuite:

  private val validEnvelope =
    """{
      |  "status": "needs_human",
      |  "question": "Which database engine should the new auth service use?",
      |  "options": ["Postgres", "MySQL", "SQLite"],
      |  "allowFreeText": true,
      |  "severity": "blocking"
      |}""".stripMargin

  test("detect returns the question for a well-formed halt envelope"):
    val expected = Question(
      "Which database engine should the new auth service use?",
      Vector("Postgres", "MySQL", "SQLite"),
      allowFreeText = true,
      QuestionSeverity.Blocking
    )
    assertEquals(HaltWithQuestion.detect(validEnvelope), Right(Some(expected)))

  test("detect tolerates surrounding whitespace"):
    val padded = s"\n\n  $validEnvelope  \n"
    assert(HaltWithQuestion.detect(padded).toOption.flatten.isDefined)

  test("detect ignores non-JSON text (plain assistant output)"):
    assertEquals(HaltWithQuestion.detect("Done implementing piece p1."), Right(None))
    assertEquals(HaltWithQuestion.detect(""), Right(None))

  test("detect ignores JSON arrays and bare values"):
    assertEquals(HaltWithQuestion.detect("[1,2,3]"), Right(None))
    assertEquals(HaltWithQuestion.detect("42"), Right(None))

  test("detect ignores JSON objects without status:needs_human"):
    assertEquals(HaltWithQuestion.detect("""{"status":"ok"}"""), Right(None))
    assertEquals(HaltWithQuestion.detect("""{"foo":"bar"}"""), Right(None))

  test("detect ignores text that mentions needs_human in prose"):
    assertEquals(
      HaltWithQuestion.detect("The model said it needs_human input but did not stop."),
      Right(None)
    )

  test("detect flags malformed envelopes (claimed halt, missing required field)"):
    val noQuestion = """{"status":"needs_human","options":[],"allowFreeText":true,"severity":"clarifying"}"""
    assert(HaltWithQuestion.detect(noQuestion).isLeft, clue = HaltWithQuestion.detect(noQuestion))

  test("detect flags malformed envelopes (unknown severity)"):
    val bad =
      """{"status":"needs_human","question":"q","options":[],"allowFreeText":false,"severity":"urgent"}"""
    assertEquals(HaltWithQuestion.detect(bad), Left("unknown severity 'urgent'"))

  test("tryParse collapses malformed envelopes to None"):
    val noQuestion = """{"status":"needs_human","options":[],"allowFreeText":true,"severity":"clarifying"}"""
    assertEquals(HaltWithQuestion.tryParse(noQuestion), None)

  test("tryParse returns Some for well-formed envelopes"):
    assert(HaltWithQuestion.tryParse(validEnvelope).isDefined)

  test("tryParse returns None for invalid JSON that starts with {"):
    assertEquals(HaltWithQuestion.tryParse("{not actually json"), None)

  test("all severity values round-trip"):
    for sev <- Seq("blocking", "clarifying", "optional") do
      val env = s"""{"status":"needs_human","question":"q","options":[],"allowFreeText":false,"severity":"$sev"}"""
      val q = HaltWithQuestion.tryParse(env).getOrElse(fail(s"expected Some for $sev"))
      assertEquals(QuestionSeverity.fromString(sev), Right(q.severity))
