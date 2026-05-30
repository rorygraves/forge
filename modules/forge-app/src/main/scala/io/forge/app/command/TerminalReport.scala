package io.forge.app.command

import cats.effect.ExitCode
import io.forge.core.FeatureId
import io.forge.core.fsm.{Feature, FsmState, ResumeHint}

/** Task 1.4.10-d2c — render a loop-terminal [[Feature]] (the value `Orchestrator.run` returns) into an operator-facing
  * message + process [[ExitCode]].
  *
  * The [[Orchestrator]] only ever returns one of three terminal states — `FeatureDone`, `Abandoned`, or
  * `NeedsHumanIntervention` — so `render` is total over what `forge run` can observe; an `other` arm guards against a
  * future loop-termination bug rather than a reachable case.
  *
  * **`NeedsHumanIntervention` rendering** pairs the reason with the concrete next command per the [[ResumeHint]]. Three
  * hints map to a literal `forge resume <feature> --<flag> <piece>` (the §15 flag set the CLI parser accepts); the
  * other four have no `forge resume` flag in v1 and map to the appropriate `forge spec` / `forge abandon` / manual
  * recovery. The full §2.5 "six-path" polish — exhaustive, individually-tested hint copy — lands in Task 1.4.15; this
  * is the v1 best-effort rendering that makes every NHI actionable today.
  *
  * **Exit codes.** `FeatureDone` → `0`. `Abandoned` / `NeedsHumanIntervention` → `1` (the run stopped before completing
  * the feature — distinct from the structured boot codes in [[io.forge.app.Main]]: `2` lock, `64` usage, `66`
  * repo-root, `78` config). The defensive non-terminal arm → `70` (`EX_SOFTWARE`).
  */
object TerminalReport:

  final case class Rendered(message: String, exitCode: ExitCode)

  /** Render a loop-terminal feature. `command` is the operator command whose drive produced this state (`run`,
    * `resume`, `abandon`) so the message prefix matches what the operator typed — `forge resume` and `forge abandon`
    * both drive the loop via [[io.forge.app.orchestrator.Orchestrator.applyUserCommand]] and reuse this renderer.
    */
  def render(feature: Feature, command: String = "run"): Rendered =
    feature.state match
      case FsmState.FeatureDone =>
        Rendered(
          s"forge $command ${feature.id.value}: ✓ feature complete — all pieces merged and refined.",
          ExitCode.Success
        )
      case FsmState.Abandoned(reason) =>
        Rendered(s"forge $command ${feature.id.value}: feature is abandoned — $reason", ExitCode(1))
      case FsmState.NeedsHumanIntervention(reason, hint) =>
        Rendered(nhiMessage(feature.id, command, reason, hint), ExitCode(1))
      case other =>
        Rendered(
          s"forge $command ${feature.id.value}: stopped in non-terminal state $other — this is a bug, please report it.",
          ExitCode(70)
        )

  private def nhiMessage(id: FeatureId, command: String, reason: String, hint: ResumeHint): String =
    s"""forge $command ${id.value}: needs human intervention — $reason
       |  → ${recovery(id, hint)}""".stripMargin

  /** The concrete recovery action for each [[ResumeHint]]. Public so `forge resume`'s hint-mismatch message
    * ([[ResumeFeature]]) can name the recovery the feature actually awaits.
    */
  def recovery(id: FeatureId, hint: ResumeHint): String = hint match
    case ResumeHint.ResumeAfterHumanPush(p, prNumber) =>
      s"after pushing your fix to PR #${prNumber.value}, run: forge resume ${id.value} --after-human-push ${p.value}"
    case ResumeHint.CommitAndPushHumanFix(p, prNumber) =>
      s"to commit & push your local fix for PR #${prNumber.value}, run: " +
        s"forge resume ${id.value} --commit-human-fix ${p.value}"
    case ResumeHint.RunAnotherFixup(p, prNumber) =>
      s"to re-run the fix-up driver on PR #${prNumber.value}, run: forge resume ${id.value} --run-fixup ${p.value}"
    case ResumeHint.ResolveLocalImplementationChanges(p, branch) =>
      s"resolve the uncommitted changes for piece ${p.value} on branch ${branch.value} " +
        s"(commit or discard), then re-run: forge run ${id.value}"
    case ResumeHint.ReopenDesign(prNumber) =>
      val prSuffix = prNumber.map(pr => s" (design PR #${pr.value})").getOrElse("")
      s"re-open the design phase$prSuffix: forge spec ${id.value}"
    case ResumeHint.ApplyPlanningUpdate(patch) =>
      s"review the proposed planning update (${patch.reason}) and re-run: forge run ${id.value}"
    case ResumeHint.AbortOrAbandon =>
      s"no automated recovery is available — run: forge abandon ${id.value}"
