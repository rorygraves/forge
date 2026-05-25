package io.forge.agents

/** Renders the **user-message body** for each reviewer one-shot. The reviewer-role *system* prompt
  * (`~/.forge/prompts/{design-review,code-review,refine}.<reviewer>.md`) carries the instructions; the body here just
  * packages the inputs in a stable shape so the system prompt can refer to sections by header.
  *
  * Kept in one place so both connectors send byte-identical bodies for the same input — the schema regression suite
  * (§17 Slice 1: "≥19/20 valid outputs per schema for each reviewer") is meaningful only if the inputs match.
  */
object ReviewerPrompts:

  def designReviewBody(input: DesignReviewInput): String =
    s"""## Feature
       |${input.featureId.value}
       |
       |## Review round
       |${input.round}
       |
       |## design.md
       |${input.designMarkdown.stripLineEnd}
       |""".stripMargin

  def prReviewBody(input: PrReviewInput): String =
    val files =
      if input.changedFiles.isEmpty then "(no files reported)"
      else input.changedFiles.map(f => s"- $f").mkString("\n")
    s"""## Feature
       |${input.featureId.value}
       |
       |## Piece
       |${input.pieceId.value}
       |
       |## PR
       |#${input.prNumber.value}
       |
       |## Piece spec
       |${input.pieceSpec.stripLineEnd}
       |
       |## Changed files
       |$files
       |
       |## Diff
       |${input.diff.stripLineEnd}
       |""".stripMargin

  def refineBody(input: RefineInput): String =
    s"""## Feature
       |${input.featureId.value}
       |
       |## Just-merged piece
       |${input.mergedPieceId.value}
       |
       |## design.md
       |${input.designMarkdown.stripLineEnd}
       |
       |## manifest.json
       |${input.manifestJson.stripLineEnd}
       |""".stripMargin
