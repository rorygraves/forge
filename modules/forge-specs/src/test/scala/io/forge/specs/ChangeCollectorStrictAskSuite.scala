package io.forge.specs

import io.forge.core.QuestionSeverity

/** Task 1.4.5 E6 — the rule-5-strict variant the spec calls out: `requireExplicitAllow: true` + a path NOT matched by
  * `allowPatterns` goes to [[Classification.Ask]], it is **not** silently filtered. Asserts the surfaced [[Question]]
  * is structurally well-formed so it routes cleanly through the driver's `AskUserQuestion` mechanism (§10.4); the
  * orchestrator (Task 1.4.10) applies the §10.1 "default option Deny" on no-answer.
  */
class ChangeCollectorStrictAskSuite extends munit.FunSuite, ChangeCollectorSupport:

  private val strict = StagingConfig(
    requireExplicitAllow = true,
    denyPatterns = StagingConfig.DefaultDenyPatterns,
    allowPatterns = Vector("src/**")
  )

  test("strict mode Asks about a path not covered by allowPatterns (never silently dropped)") {
    val change = at("docs/notes.md", FileChangeKind.Added)
    classify(Vector(change), strict) match
      case Classification.Ask(questions, included) =>
        assert(included.isEmpty, "asked path must not appear in included")
        assertEquals(questions.size, 1)
        val q = questions.head
        assert(q.text.nonEmpty, "question text must be non-empty")
        assert(q.text.contains("docs/notes.md"), "question should name the path under decision")
        assertEquals(q.options, Vector("Allow", "Deny"))
        assertEquals(q.allowFreeText, false)
        assertEquals(q.severity, QuestionSeverity.Blocking)
      case other => fail(s"expected Ask, got $other")
  }

  test("mixed set — allowed paths are included, unmatched paths each produce a question") {
    val allowed = at("src/Main.scala", FileChangeKind.Modified)
    val asked1 = at("docs/a.md", FileChangeKind.Added)
    val asked2 = at("scripts/b.sh", FileChangeKind.Added)
    classify(Vector(allowed, asked1, asked2), strict) match
      case Classification.Ask(questions, included) =>
        assertEquals(included, Vector(allowed))
        assertEquals(questions.size, 2)
        assert(questions.forall(q => q.options == Vector("Allow", "Deny") && !q.allowFreeText))
      case other => fail(s"expected Ask, got $other")
  }
