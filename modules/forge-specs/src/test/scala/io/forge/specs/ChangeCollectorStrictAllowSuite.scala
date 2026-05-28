package io.forge.specs

/** Task 1.4.5 E6 — strict mode (`requireExplicitAllow: true`): a path matching `allowPatterns` is Allowed (rule 5
  * strict, matched branch).
  */
class ChangeCollectorStrictAllowSuite extends munit.FunSuite, ChangeCollectorSupport:

  private val strict = StagingConfig(
    requireExplicitAllow = true,
    denyPatterns = StagingConfig.DefaultDenyPatterns,
    allowPatterns = Vector("src/**", "**/*.scala")
  )

  test("strict mode Allows a path matched by allowPatterns") {
    val change = at("src/main/scala/Main.scala", FileChangeKind.Modified)
    classify(Vector(change), strict) match
      case Classification.Allow(included) => assertEquals(included, Vector(change))
      case other => fail(s"expected Allow, got $other")
  }

  test("strict mode Allows every change when all match allowPatterns") {
    val changes = Vector(
      at("src/Main.scala", FileChangeKind.Added),
      at("lib/Util.scala", FileChangeKind.Modified) // matched by **/*.scala
    )
    classify(changes, strict) match
      case Classification.Allow(included) => assertEquals(included, changes)
      case other => fail(s"expected Allow, got $other")
  }
