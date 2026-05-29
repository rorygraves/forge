package io.forge.app.command

import cats.effect.ExitCode
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber}
import io.forge.core.fsm.{Feature, FsmState, ResumeHint}
import io.forge.core.manifest.{Manifest, ManifestPatch}

/** Task 1.4.10-d2c — `TerminalReport` rendering: exit codes per terminal state and an actionable recovery line for
  * every `ResumeHint`.
  */
class TerminalReportSuite extends munit.FunSuite:

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")
  private val pr = PrNumber(42)

  private def featureWith(state: FsmState): Feature =
    val m = Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "t",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector.empty
    )
    Feature.initial(featureId, m).copy(state = state)

  test("FeatureDone → exit 0 with a completion message"):
    val r = TerminalReport.render(featureWith(FsmState.FeatureDone))
    assertEquals(r.exitCode, ExitCode.Success)
    assert(r.message.contains("feature complete"), r.message)

  test("Abandoned → exit 1 carrying the reason"):
    val r = TerminalReport.render(featureWith(FsmState.Abandoned("operator abandoned")))
    assertEquals(r.exitCode, ExitCode(1))
    assert(r.message.contains("operator abandoned"), r.message)

  test("NHI RunAnotherFixup → exit 1 with the literal forge resume --run-fixup command"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("ci failed", ResumeHint.RunAnotherFixup(p1, pr)))
    )
    assertEquals(r.exitCode, ExitCode(1))
    assert(r.message.contains("ci failed"), r.message)
    assert(r.message.contains(s"forge resume ${featureId.value} --run-fixup ${p1.value}"), r.message)

  test("NHI ResumeAfterHumanPush → --after-human-push command"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("waiting", ResumeHint.ResumeAfterHumanPush(p1, pr)))
    )
    assert(r.message.contains(s"forge resume ${featureId.value} --after-human-push ${p1.value}"), r.message)

  test("NHI CommitAndPushHumanFix → --commit-human-fix command"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("dirty", ResumeHint.CommitAndPushHumanFix(p1, pr)))
    )
    assert(r.message.contains(s"forge resume ${featureId.value} --commit-human-fix ${p1.value}"), r.message)

  test("NHI ResolveLocalImplementationChanges → guides to forge run after resolving the WC"):
    val r = TerminalReport.render(
      featureWith(
        FsmState.NeedsHumanIntervention(
          "uncommitted",
          ResumeHint.ResolveLocalImplementationChanges(p1, BranchName("forge/feat/p1"))
        )
      )
    )
    assert(r.message.contains("forge/feat/p1"), r.message)
    assert(r.message.contains(s"forge run ${featureId.value}"), r.message)

  test("NHI ReopenDesign(Some) → forge spec with the design PR noted"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("closed", ResumeHint.ReopenDesign(Some(PrNumber(7)))))
    )
    assert(r.message.contains("#7"), r.message)
    assert(r.message.contains(s"forge spec ${featureId.value}"), r.message)

  test("NHI ReopenDesign(None) → forge spec without a PR reference"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("no session", ResumeHint.ReopenDesign(None)))
    )
    assert(r.message.contains(s"forge spec ${featureId.value}"), r.message)
    assert(!r.message.contains("#"), r.message)

  test("NHI ApplyPlanningUpdate → surfaces the patch reason and forge run"):
    val r = TerminalReport.render(
      featureWith(
        FsmState.NeedsHumanIntervention(
          "refine",
          ResumeHint.ApplyPlanningUpdate(ManifestPatch("add piece", Vector.empty))
        )
      )
    )
    assert(r.message.contains("add piece"), r.message)
    assert(r.message.contains(s"forge run ${featureId.value}"), r.message)

  test("NHI AbortOrAbandon → forge abandon"):
    val r = TerminalReport.render(
      featureWith(FsmState.NeedsHumanIntervention("stuck", ResumeHint.AbortOrAbandon))
    )
    assert(r.message.contains(s"forge abandon ${featureId.value}"), r.message)
