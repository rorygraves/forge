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

  /** §7.11 `RepoProfiler` body — packages the agent docs, build files, and CI workflow files under stable headers the
    * `repo-profile.<cli>.md` system prompt refers to. A missing doc is rendered as an explicit "(none)" rather than
    * omitted, so the model can tell "absent" from "I forgot to include it".
    */
  def repoProfileBody(input: RepoProfilerInput): String =
    def doc(label: String, content: Option[String]): String =
      val body = content.map(_.stripLineEnd).filter(_.nonEmpty).getOrElse("(none)")
      s"## $label\n$body"
    def files(label: String, fs: Vector[RepoFile]): String =
      if fs.isEmpty then s"## $label\n(none)"
      else
        val rendered = fs
          .map(f => s"### ${f.path}\n${f.content.stripLineEnd}")
          .mkString("\n\n")
        s"## $label\n$rendered"
    List(
      s"## Repo\n${input.repoName}",
      doc("AGENTS.md", input.agentsDoc),
      doc("CLAUDE.md", input.claudeDoc),
      files("Build files", input.buildFiles),
      files("CI workflow files", input.workflowFiles)
    ).mkString("\n\n") + "\n"
