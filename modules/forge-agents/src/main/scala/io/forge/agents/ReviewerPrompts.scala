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

  /** §7.11 `FailureClassifier` body (Task 3.1.4) — packages the gate failure log plus the profile's known commands
    * under stable headers the `failure-classifier.<cli>.md` system prompt refers to. The available commands are
    * rendered so the model can name a `suggested` kind the repo actually exposes (a `DeterministicFix` only routes to a
    * local run when the profile declares the matching autofix command). The log is fenced so the model can tell its
    * boundaries from the surrounding sections.
    */
  def classifyFailureBody(input: FailureClassifierInput): String =
    val commands =
      if input.profile.commands.isEmpty then "(none)"
      else
        input.profile.commands
          .map { c =>
            val argv = c.argv.mkString(" ")
            s"- ${c.kind.asString}: `$argv` (${c.determinism.asString}, autofix=${c.autofix}, required=${c.required})"
          }
          .mkString("\n")
    s"""## Feature
       |${input.featureId.value}
       |
       |## Gate
       |${input.gate}
       |
       |## Build tool
       |${input.profile.buildTool}
       |
       |## Known commands
       |$commands
       |
       |## Failure log
       |```
       |${input.failureLog.stripLineEnd}
       |```
       |""".stripMargin

  /** §7.11 `ConventionLearner` body (Task 3.2) — packages the run's observed failures, the profile's known commands,
    * and the current CLAUDE.md under stable headers the `learn-conventions.<cli>.md` system prompt refers to. The known
    * commands are rendered so the model proposes an `addCommands` entry the repo doesn't already have (rather than
    * duplicating one); the observed failures carry the route the spine chose so the model can tell which failures a new
    * convention would have avoided; the observed reviewer comments (decision D8) carry the gate/round so the model can
    * spot a recurring blocker a CLAUDE.md note could pre-empt; the CLAUDE.md is included so a `claudeMdProposal` is
    * phrased relative to what the doc already says. A missing CLAUDE.md / empty failure / empty reviewer-comment list
    * is rendered as an explicit "(none)".
    */
  def learnConventionsBody(input: ConventionLearnerInput): String =
    val commands =
      if input.profile.commands.isEmpty then "(none)"
      else
        input.profile.commands
          .map { c =>
            val argv = c.argv.mkString(" ")
            s"- ${c.kind.asString}: `$argv` (${c.determinism.asString}, autofix=${c.autofix}, required=${c.required})"
          }
          .mkString("\n")
    val failures =
      if input.failures.isEmpty then "(none)"
      else
        input.failures
          .map { f =>
            val sug = f.suggested.map(s => s", suggested=$s").getOrElse("")
            s"- ${f.gate}/${f.kind} → ${f.route}$sug: ${f.evidence}"
          }
          .mkString("\n")
    val reviewerComments =
      if input.reviewerComments.isEmpty then "(none)"
      else
        input.reviewerComments
          .map { c =>
            val round = c.round.map(r => s" round $r").getOrElse("")
            s"- ${c.gate}$round: ${c.blocker}"
          }
          .mkString("\n")
    val claude = input.claudeDoc.map(_.stripLineEnd).filter(_.nonEmpty).getOrElse("(none)")
    s"""## Feature
       |${input.featureId.value}
       |
       |## Build tool
       |${input.profile.buildTool}
       |
       |## Known commands
       |$commands
       |
       |## Observed failures (this feature's run)
       |$failures
       |
       |## Observed reviewer comments (this feature's run)
       |$reviewerComments
       |
       |## Current CLAUDE.md
       |$claude
       |""".stripMargin
