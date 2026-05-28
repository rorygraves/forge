package io.forge.specs

/** Task 1.4.5 E6 — rule 1 dominates rule 5: a path matching BOTH `denyPatterns` and `allowPatterns` falls to
  * [[Classification.Deny]], never `Allow`. Covers both the strict and lenient postures, since deny is evaluated before
  * the allow/ask branch in either.
  */
class ChangeCollectorDenyPrecedesAllowSuite extends munit.FunSuite, ChangeCollectorSupport:

  test("strict mode — a path in both deny and allow patterns is Denied") {
    val config = StagingConfig(
      requireExplicitAllow = true,
      denyPatterns = Vector("**/*.pem"),
      allowPatterns = Vector("**/*.pem", "**/*") // would Allow if deny didn't dominate
    )
    classify(Vector(at("certs/server.pem", FileChangeKind.Added)), config) match
      case Classification.Deny(denied) => assertEquals(denied.map(_._2), Vector("**/*.pem"))
      case other => fail(s"expected Deny, got $other")
  }

  test("lenient mode — an allow-pattern match does not rescue a deny-pattern hit") {
    val config = StagingConfig(
      requireExplicitAllow = false,
      denyPatterns = Vector("**/secret/**"),
      allowPatterns = Vector("**/*") // ignored in lenient mode, but pinned to show deny still wins
    )
    classify(Vector(at("app/secret/token.txt", FileChangeKind.Added)), config) match
      case Classification.Deny(denied) => assertEquals(denied.map(_._2), Vector("**/secret/**"))
      case other => fail(s"expected Deny, got $other")
  }
