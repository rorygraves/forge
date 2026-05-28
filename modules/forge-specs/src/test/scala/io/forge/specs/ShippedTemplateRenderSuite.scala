package io.forge.specs

import io.forge.specs.HandlebarsLite.Value
import io.forge.specs.HandlebarsLite.Value.*

import java.nio.charset.StandardCharsets

/** Drift-proofs the design-rationale T2 claim that `HandlebarsLite` renders Forge's shipped `.hbs` templates. Each
  * template is loaded from the test classpath (the `assets/` unmanaged resource dir wired in build.sbt) and rendered
  * with a representative context; the suite asserts (a) no template uses a construct the renderer can't handle (every
  * render returns `Right`) and (b) the two constructs the answer/PR-body templates rely on beyond the decomposition
  * subset — `array.length` and `{{@index}}` — produce the right output. If a future template introduces an unsupported
  * construct, the matching render flips to `Left` here rather than silently mis-rendering at run time.
  */
class ShippedTemplateRenderSuite extends munit.FunSuite:

  private def template(name: String): String =
    val in = getClass.getResourceAsStream(s"/templates/$name")
    assert(in != null, s"$name not on the test classpath — check forge-specs build.sbt resource dirs")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def render(name: String, ctx: Value.Obj): String =
    HandlebarsLite.render(template(name), ctx, TemplateHelpers.all) match
      case Right(s) => s
      case Left(e) => fail(s"$name failed to render: $e")

  private def qa(question: String, answer: String, severity: String): Value.Obj =
    Obj(Map("question" -> Str(question), "answer" -> Str(answer), "severity" -> Str(severity)))

  private def piece(id: String, title: String): Value.Obj =
    Obj(Map("id" -> Str(id), "title" -> Str(title)))

  /** One context rich enough for every shipped template; extra keys a given template doesn't use are harmless. */
  private def context(mergedPieces: Vector[Value.Obj]): Value.Obj =
    Obj(
      Map(
        "feature" -> Obj(
          Map(
            "id" -> Str("stripe-webhook"),
            "title" -> Str("Add Stripe webhook receiver"),
            "baseBranch" -> Str("main"),
            "branchPrefix" -> Str("forge"),
            "designPr" -> Str("7")
          )
        ),
        "piece" -> Obj(
          Map(
            "id" -> Str("p2"),
            "order" -> Str("2"),
            "title" -> Str("Persist webhook events"),
            "summary" -> Str("Store verified events"),
            "spec" -> Str("## Spec\n\nbody"),
            "attempts" -> Str("1")
          )
        ),
        "pieces" -> Arr(
          Vector(
            Obj(
              Map(
                "order" -> Str("1"),
                "id" -> Str("p1"),
                "title" -> Str("Add route"),
                "summary" -> Str("route + verify"),
                "status" -> Str("merged"),
                "prNumber" -> Str("42"),
                "mergeCommit" -> Str("abc1234")
              )
            )
          )
        ),
        "mergedPieces" -> Arr(mergedPieces),
        "auditSummary" -> Str("- opened PR #42"),
        "capturedAt" -> Str("2026-05-28T00:00:00Z"),
        "prNumber" -> Str("42"),
        "round" -> Str("1"),
        "attempt" -> Str("1"),
        "qa" -> Arr(Vector(qa("Why?", "Because.", "blocker"), qa("How?", "Thus.", "nit")))
      )
    )

  private val AllTemplates = Vector(
    "decomposition.md.hbs",
    "pr-body.md.hbs",
    "spec-answers.md.hbs",
    "design-review-r1-answers.md.hbs",
    "design-pr-feedback-r1-answers.md.hbs",
    "impl-answers.md.hbs",
    "fixup-r1-answers.md.hbs"
  )

  AllTemplates.foreach: name =>
    test(s"$name renders with no unsupported construct"): // returns Right, else render() fails
      val _ = render(name, context(Vector(piece("p1", "Add route"))))

  test("pr-body .length guard renders the merged section when mergedPieces is non-empty"):
    val out = render("pr-body.md.hbs", context(Vector(piece("p1", "Add route"))))
    assert(out.contains("## Pieces already merged"), out)
    assert(out.contains("- `p1` — Add route"), out)

  test("pr-body .length guard skips the merged section when mergedPieces is empty"):
    val out = render("pr-body.md.hbs", context(Vector.empty))
    assert(!out.contains("Pieces already merged"), out)

  test("answer template numbers questions 1-based via questionNumber(@index)"):
    val out = render("spec-answers.md.hbs", context(Vector.empty))
    assert(out.contains("## Q1. Why?"), out)
    assert(out.contains("## Q2. How?"), out)
