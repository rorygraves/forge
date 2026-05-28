package io.forge.specs

/** Task 1.4.5 E6 — rule 5 lenient default: clean changes (not denied, not gitignored, inside the repo) pass straight
  * through to [[Classification.Allow]] when `requireExplicitAllow` is off.
  */
class ChangeCollectorAllowSuite extends munit.FunSuite, ChangeCollectorSupport:

  test("clean source changes all Allow under the lenient default policy") {
    val changes = Vector(
      at("src/main/scala/Main.scala", FileChangeKind.Modified),
      at("README.md", FileChangeKind.Added),
      at("build.sbt", FileChangeKind.Modified)
    )
    classify(changes, StagingConfig.Default) match
      case Classification.Allow(included) => assertEquals(included, changes)
      case other => fail(s"expected Allow, got $other")
  }

  test("a renamed file is classified by its destination path") {
    val change = at(
      "src/main/scala/Renamed.scala",
      FileChangeKind.Renamed(from = repoRoot / os.RelPath("src/main/scala/Old.scala"))
    )
    classify(Vector(change), StagingConfig.Default) match
      case Classification.Allow(included) => assertEquals(included, Vector(change))
      case other => fail(s"expected Allow, got $other")
  }

  test("empty change set is a (vacuous) Allow") {
    classify(Vector.empty, StagingConfig.Default) match
      case Classification.Allow(included) => assert(included.isEmpty)
      case other => fail(s"expected Allow, got $other")
  }

  test("a gitignored path UNDER .forge/specs/ is carved out of rule 4 and Allowed") {
    val change = at(".forge/specs/my-feature/manifest.json", FileChangeKind.Modified, ignored = true)
    classify(Vector(change), StagingConfig.Default) match
      case Classification.Allow(included) => assertEquals(included, Vector(change))
      case other => fail(s"expected Allow (rule-4 carve-out), got $other")
  }
