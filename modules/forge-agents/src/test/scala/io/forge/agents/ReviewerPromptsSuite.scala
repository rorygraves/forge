package io.forge.agents

import io.forge.core.{FeatureId, PieceId, PrNumber}

class ReviewerPromptsSuite extends munit.FunSuite:

  test("designReviewBody includes feature id, round, and the verbatim design markdown"):
    val body = ReviewerPrompts.designReviewBody(
      DesignReviewInput(FeatureId("feat-x"), round = 2, designMarkdown = "# Title\n\nA paragraph.")
    )
    assert(body.contains("## Feature\nfeat-x"), clue = body)
    assert(body.contains("## Review round\n2"), clue = body)
    assert(body.contains("# Title"))
    assert(body.contains("A paragraph."))

  test("prReviewBody includes piece spec, PR number, diff, and changed files as a list"):
    val body = ReviewerPrompts.prReviewBody(
      PrReviewInput(
        featureId = FeatureId("feat-x"),
        pieceId = PieceId("p1"),
        prNumber = PrNumber(42),
        pieceSpec = "Implement Foo",
        diff = "diff --git a b\n+new line",
        changedFiles = Vector("src/A.scala", "src/B.scala")
      )
    )
    assert(body.contains("## Piece\np1"), clue = body)
    assert(body.contains("## PR\n#42"), clue = body)
    assert(body.contains("## Piece spec\nImplement Foo"), clue = body)
    assert(body.contains("- src/A.scala"), clue = body)
    assert(body.contains("- src/B.scala"), clue = body)
    assert(body.contains("+new line"), clue = body)

  test("prReviewBody renders empty changedFiles list with a placeholder"):
    val body = ReviewerPrompts.prReviewBody(
      PrReviewInput(FeatureId("feat"), PieceId("p1"), PrNumber(1), "spec", "diff", Vector.empty)
    )
    assert(body.contains("(no files reported)"), clue = body)

  test("refineBody includes both the design markdown and the manifest JSON"):
    val body = ReviewerPrompts.refineBody(
      RefineInput(
        featureId = FeatureId("feat"),
        mergedPieceId = PieceId("p2"),
        designMarkdown = "# Design",
        manifestJson = """{"pieces":[{"id":"p2","status":"merged"}]}"""
      )
    )
    assert(body.contains("## Just-merged piece\np2"), clue = body)
    assert(body.contains("# Design"), clue = body)
    assert(body.contains("\"status\":\"merged\""), clue = body)
