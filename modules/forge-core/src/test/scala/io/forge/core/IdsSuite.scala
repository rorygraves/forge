package io.forge.core

class IdsSuite extends munit.FunSuite:

  test("FeatureId.fromString accepts the slug examples from §5.2"):
    val ok = List(
      "add-stripe-webhook-receiver",
      "fix-bug-1234",
      "launch",
      "f-42-line-issue",
      "add-stripe-webhook-receiver-2"
    )
    ok.foreach(s => assertEquals(FeatureId.fromString(s).map(_.value), Right(s)))

  test("FeatureId.fromString rejects empty, capitals, leading digit, and oversize input"):
    val bad = List("", "ABC", "42-line-issue", "a" * 51)
    bad.foreach(s => assert(FeatureId.fromString(s).isLeft, s"expected '$s' to be rejected"))

  test("FeatureId.apply throws on invalid input"):
    intercept[IllegalArgumentException](FeatureId(""))
    intercept[IllegalArgumentException](FeatureId("UpperCase"))

  test("PieceId pattern accepts p<digits>"):
    List("p1", "p2", "p10", "p999").foreach: s =>
      assertEquals(PieceId.fromString(s).map(_.value), Right(s))

  test("PieceId rejects non-conforming ids"):
    List("", "P1", "piece1", "p", "p01a").foreach: s =>
      assert(PieceId.fromString(s).isLeft, s"expected '$s' to be rejected")

  test("PrNumber.apply rejects non-positive values"):
    intercept[IllegalArgumentException](PrNumber(0))
    intercept[IllegalArgumentException](PrNumber(-1))
    assertEquals(PrNumber(42).value, 42)

  test("BranchName rejects empty"):
    intercept[IllegalArgumentException](BranchName(""))
    assertEquals(BranchName("forge/feat/p1").value, "forge/feat/p1")

  test("Sha accepts 7..40 lowercase hex"):
    assert(Sha.fromString("abc1234").isRight)
    assert(Sha.fromString("0" * 40).isRight)
    assert(Sha.fromString("0" * 6).isLeft)
    assert(Sha.fromString("0" * 41).isLeft)
    assert(Sha.fromString("ABC1234").isLeft) // uppercase
    assert(Sha.fromString("xyz1234").isLeft) // non-hex
