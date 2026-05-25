package io.forge.agents

import io.forge.core.QuestionSeverity

class ReviewDecodersSuite extends munit.FunSuite:

  // --- DesignReview ---

  test("designReview: minimal approve payload with empty arrays decodes cleanly"):
    val v = ujson.read("""{"verdict":"approve","blockers":[],"questions":[],"summary":"ok"}""")
    val result = ReviewDecoders.designReview(v)
    assertEquals(result, Right(DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok")))

  test("designReview: omitted blockers/questions arrays default to empty"):
    val v = ujson.read("""{"verdict":"approve","summary":"ok"}""")
    val result = ReviewDecoders.designReview(v)
    assertEquals(result, Right(DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok")))

  test("designReview: request_changes with blockers and a clarifying question"):
    val v = ujson.read(
      """{"verdict":"request_changes",
        |"blockers":[{"summary":"missing API contract","path":"src/api.scala","line":42,"anchorText":"def foo"}],
        |"questions":[{"text":"Should we cache?","options":["yes","no"],"allowFreeText":false,"severity":"clarifying"}],
        |"summary":"Needs work."}""".stripMargin
    )
    val result = ReviewDecoders.designReview(v)
    assert(result.isRight, clue = result)
    val r = result.toOption.get
    assertEquals(r.verdict, ReviewVerdict.RequestChanges)
    assertEquals(r.blockers.size, 1)
    assertEquals(r.blockers.head.path, Some("src/api.scala"))
    assertEquals(r.blockers.head.line, Some(42))
    assertEquals(r.questions.size, 1)
    assertEquals(r.questions.head.severity, QuestionSeverity.Clarifying)
    assertEquals(r.questions.head.options, Vector("yes", "no"))
    assertEquals(r.questions.head.allowFreeText, false)

  test("designReview: blocker with only summary keeps optional fields as None"):
    val v = ujson.read("""{"verdict":"request_changes","blockers":[{"summary":"x"}],"summary":"s"}""")
    val r = ReviewDecoders.designReview(v).toOption.get
    assertEquals(r.blockers.head, ReviewBlocker(summary = "x", path = None, line = None, anchorText = None))

  test("designReview: unknown verdict returns Left for adapter-error routing"):
    val v = ujson.read("""{"verdict":"maybe","summary":"x"}""")
    assert(
      ReviewDecoders.designReview(v).left.exists(_.contains("unknown verdict 'maybe'")),
      clue = "expected Left mentioning the bad verdict"
    )

  test("designReview: missing summary returns Left"):
    val v = ujson.read("""{"verdict":"approve","blockers":[]}""")
    assert(ReviewDecoders.designReview(v).left.exists(_.contains("'summary'")), clue = "Left should name summary")

  test("designReview: unknown severity returns Left naming the question index"):
    val v = ujson.read(
      """{"verdict":"approve","questions":[{"text":"q?","severity":"impossible"}],"summary":"s"}"""
    )
    val result = ReviewDecoders.designReview(v)
    assert(result.left.exists(_.contains("questions[0].severity")), clue = result)

  // --- PrReview ---

  test("prReview: minimal approve payload decodes"):
    val v = ujson.read("""{"verdict":"approve","blockers":[],"summary":"LGTM"}""")
    assertEquals(
      ReviewDecoders.prReview(v),
      Right(PrReview(ReviewVerdict.Approve, Vector.empty, "LGTM"))
    )

  test("prReview: ignores stray top-level fields not in the schema (forward-compat)"):
    val v = ujson.read("""{"verdict":"approve","blockers":[],"summary":"s","extra_field":"ignore me"}""")
    val r = ReviewDecoders.prReview(v)
    assert(r.isRight, clue = r)
    assertEquals(r.toOption.get.summary, "s")

  test("prReview: unknown verdict returns Left"):
    val v = ujson.read("""{"verdict":"maybe","blockers":[],"summary":"s"}""")
    assert(ReviewDecoders.prReview(v).isLeft)

  // --- RefineResult ---

  test("refineResult: no_change without patch decodes"):
    val v = ujson.read("""{"outcome":"no_change","reason":"all aligned"}""")
    assertEquals(
      ReviewDecoders.refineResult(v),
      Right(RefineResult(RefineOutcome.NoChange, "all aligned", None))
    )

  test("refineResult: update_plan preserves patch verbatim as JSON string"):
    val v = ujson.read("""{"outcome":"update_plan","reason":"add piece","patch":{"add":["p4"]}}""")
    val r = ReviewDecoders.refineResult(v).toOption.get
    assertEquals(r.outcome, RefineOutcome.UpdatePlan)
    // Patch text is JSON-equivalent to the source.
    assertEquals(ujson.read(r.patchJson.get).obj.get("add").flatMap(_.arrOpt).map(_.size), Some(1))

  test("refineResult: update_plan WITHOUT patch returns Left — §14.3 needs the patch to build a ManifestPatch"):
    val v = ujson.read("""{"outcome":"update_plan","reason":"forgot patch"}""")
    val r = ReviewDecoders.refineResult(v)
    assert(r.left.exists(_.contains("requires a 'patch'")), clue = r)

  test("refineResult: no_change with a stray patch field drops it (patch only meaningful for update_plan)"):
    val v = ujson.read("""{"outcome":"no_change","reason":"all aligned","patch":{"ignored":true}}""")
    val r = ReviewDecoders.refineResult(v).toOption.get
    assertEquals(r.outcome, RefineOutcome.NoChange)
    assertEquals(r.patchJson, None)

  test("refineResult: reopen_design path"):
    val v = ujson.read("""{"outcome":"reopen_design","reason":"design drift detected"}""")
    val r = ReviewDecoders.refineResult(v).toOption.get
    assertEquals(r.outcome, RefineOutcome.ReopenDesign)
    assertEquals(r.patchJson, None)

  test("refineResult: unknown outcome returns Left"):
    val v = ujson.read("""{"outcome":"refactor","reason":"x"}""")
    assert(
      ReviewDecoders.refineResult(v).left.exists(_.contains("unknown outcome 'refactor'")),
      clue = "expected Left naming the bad outcome"
    )

  test("designReview: non-object root returns Left"):
    val v = ujson.read("""[1,2,3]""")
    assert(ReviewDecoders.designReview(v).left.exists(_.contains("expected JSON object")))
