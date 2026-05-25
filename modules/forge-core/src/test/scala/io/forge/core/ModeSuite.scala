package io.forge.core

class ModeSuite extends munit.FunSuite:

  test("Mode round-trips through its wire form"):
    Mode.values.foreach: m =>
      assertEquals(Mode.fromString(m.asString), Right(m))

  test("Mode.fromString rejects unknown values"):
    assert(Mode.fromString("gemini-driver").isLeft)
    assert(Mode.fromString("").isLeft)

  test("QuestionSeverity round-trips through its wire form"):
    QuestionSeverity.values.foreach: s =>
      assertEquals(QuestionSeverity.fromString(s.asString), Right(s))

  test("QuestionSeverity.fromString is case-insensitive"):
    assertEquals(QuestionSeverity.fromString("BLOCKING"), Right(QuestionSeverity.Blocking))

  test("CiPolicy round-trips through its wire form"):
    CiPolicy.values.foreach: p =>
      assertEquals(CiPolicy.fromString(p.asString), Right(p))
