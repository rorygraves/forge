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
  * recovery. Task 1.4.15 O1 lands the §2.5 "six-path" polish: [[recovery]] now renders a one-paragraph operator
  * description for every [[ResumeHint]] variant (what stopped, what to do by hand, the exact command). The copy is
  * exhaustive over the sealed enum (the `match` is total — adding a variant fails compilation) and individually tested
  * (`TerminalReportSuite`), with a data-driven row asserting every variant names a `forge ` command.
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
      s"Forge is waiting on you to push a fix to the branch behind PR #${prNumber.value} (piece ${p.value}). " +
        s"Push your commits, then hand control back with: forge resume ${id.value} --after-human-push ${p.value}"
    case ResumeHint.CommitAndPushHumanFix(p, prNumber) =>
      s"You have an uncommitted local fix for piece ${p.value} (PR #${prNumber.value}). Let Forge commit and push it " +
        s"for you with: forge resume ${id.value} --commit-human-fix ${p.value}"
    case ResumeHint.RunAnotherFixup(p, prNumber) =>
      s"Piece ${p.value} (PR #${prNumber.value}) needs another automated fix-up pass. Re-run the fix-up driver with: " +
        s"forge resume ${id.value} --run-fixup ${p.value}"
    case ResumeHint.ResolveLocalImplementationChanges(p, branch) =>
      s"Piece ${p.value} left uncommitted changes in the working copy on branch ${branch.value}. Resolve them by hand " +
        s"(commit the work you want, discard the rest), then continue headlessly with: forge run ${id.value}"
    case ResumeHint.ReopenDesign(prNumber) =>
      val prSuffix = prNumber.map(pr => s" The design PR is #${pr.value}.").getOrElse("")
      s"The design needs another pass before implementation can continue.$prSuffix Re-open the spec/design loop with: " +
        s"forge spec ${id.value}"
    case ResumeHint.ApplyPlanningUpdate(patch) =>
      s"The refinery proposed a planning update (${patch.reason}). Review it; once you approve, continue with: " +
        s"forge run ${id.value}"
    case ResumeHint.AbortOrAbandon =>
      s"There is no automated recovery for this state. If you cannot resolve it by hand, abandon the feature with: " +
        s"forge abandon ${id.value}"
