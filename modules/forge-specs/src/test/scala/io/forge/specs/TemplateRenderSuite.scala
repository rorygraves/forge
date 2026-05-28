package io.forge.specs

import io.forge.specs.HandlebarsLite.Value
import io.forge.specs.HandlebarsLite.Value.*

import java.nio.charset.StandardCharsets

/** Task 1.4.6 F4 — golden-file render of every shipped `.hbs` template.
  *
  * Each template is rendered against one fixed fixture context and compared byte-for-byte to a checked-in golden under
  * `src/test/resources/golden/<template>.golden`. Where [[ShippedTemplateRenderSuite]] proves the renderer *can* handle
  * every construct a template uses (the render returns `Right`), this suite pins the exact rendered *output* — so an
  * accidental edit to a template's prose, or, more importantly, to the §5.3 reconcile-marker shape that `forge
  * reconcile` (Task 1.4.15) parses, fails here loudly instead of shipping a silently-changed PR body / decomposition
  * doc.
  *
  * Regenerating goldens: run `FORGE_UPDATE_GOLDEN=1 sbt "project forge-specs" "testOnly *TemplateRenderSuite"` (or
  * delete a stale golden — a missing golden is written rather than asserted). Inspect the diff before committing.
  */
class TemplateRenderSuite extends munit.FunSuite:

  private val goldenDir: os.Path =
    os.pwd / "modules" / "forge-specs" / "src" / "test" / "resources" / "golden"

  private val updateGolden: Boolean = sys.env.get("FORGE_UPDATE_GOLDEN").contains("1")

  private def template(name: String): String =
    val in = getClass.getResourceAsStream(s"/templates/$name")
    assert(in != null, s"$name not on the test classpath — check forge-specs build.sbt resource dirs")
    try new String(in.readAllBytes(), StandardCharsets.UTF_8)
    finally in.close()

  private def render(name: String): String =
    HandlebarsLite.render(template(name), Context, TemplateHelpers.all) match
      case Right(s) => s
      case Left(e) => fail(s"$name failed to render: $e")

  private def checkGolden(name: String): Unit =
    val rendered = render(name)
    val goldenFile = goldenDir / s"$name.golden"
    if updateGolden || !os.exists(goldenFile) then
      os.makeDir.all(goldenDir)
      os.write.over(goldenFile, rendered)
      // Not a failure: first-run / regeneration writes the golden so the author can inspect + commit it. CI checks out
      // the committed goldens, so this branch only runs locally while authoring a template change.
      println(s"[TemplateRenderSuite] wrote golden $goldenFile (${rendered.length} chars)")
    else
      assertEquals(
        rendered,
        os.read(goldenFile),
        s"$name render drifted from golden — regenerate with FORGE_UPDATE_GOLDEN=1"
      )

  // --- one fixture context, rich enough for every shipped template ------------

  private def piece(fields: (String, Value)*): Value.Obj = Obj(fields.toMap)

  private def qa(question: String, answer: String, severity: String): Value.Obj =
    Obj(Map("question" -> Str(question), "answer" -> Str(answer), "severity" -> Str(severity)))

  private val Context: Value.Obj =
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
            "summary" -> Str("Store verified events idempotently."),
            "spec" -> Str("### Acceptance\n\n- Verified events are persisted exactly once.\n- Replays are no-ops."),
            "attempts" -> Str("1")
          )
        ),
        // §5.5: merged pieces form a contiguous prefix — merged, then in_progress, then pending.
        "pieces" -> Arr(
          Vector(
            piece(
              "order" -> Str("1"),
              "id" -> Str("p1"),
              "title" -> Str("Add webhook route and signature verification"),
              "summary" -> Str("Add `POST /stripe/webhook`. Verify against `STRIPE_WEBHOOK_SECRET`."),
              "status" -> Str("merged"),
              "prNumber" -> Str("42"),
              "mergeCommit" -> Str("abc1234")
            ),
            piece(
              "order" -> Str("2"),
              "id" -> Str("p2"),
              "title" -> Str("Persist webhook events"),
              "summary" -> Str("Store verified events idempotently."),
              "status" -> Str("in_progress")
            ),
            piece(
              "order" -> Str("3"),
              "id" -> Str("p3"),
              "title" -> Str("Expose admin replay"),
              "summary" -> Str("Replay endpoint for ops."),
              "status" -> Str("pending")
            )
          )
        ),
        "mergedPieces" -> Arr(
          Vector(piece("id" -> Str("p1"), "title" -> Str("Add webhook route and signature verification")))
        ),
        "auditSummary" -> Str("- opened PR #43\n- design reviewed: approve"),
        "capturedAt" -> Str("2026-05-28T00:00:00Z"),
        "prNumber" -> Str("43"),
        "round" -> Str("1"),
        "attempt" -> Str("1"),
        "qa" -> Arr(
          Vector(
            qa("Why verify the signature?", "To reject spoofed webhook deliveries.", "blocker"),
            qa("Which secret holds the signing key?", "`STRIPE_WEBHOOK_SECRET`.", "nit")
          )
        )
      )
    )

  private val Templates: Vector[String] = Vector(
    "decomposition.md.hbs",
    "pr-body.md.hbs",
    "spec-answers.md.hbs",
    "design-review-r1-answers.md.hbs",
    "design-pr-feedback-r1-answers.md.hbs",
    "impl-answers.md.hbs",
    "fixup-r1-answers.md.hbs"
  )

  Templates.foreach(name => test(s"$name matches golden")(checkGolden(name)))
