package io.forge.app.orchestrator

import io.forge.core.fsm.{Feature, Fsm, FsmConfig, FsmEvent, FsmState, SessionPhase}
import io.forge.core.log.ActionDraft
import io.forge.core.state.RebuildState.InFlightSession

/** Task 1.4.10 J2 restart recovery (process-crash handling) — the pure mapping from an in-flight driver session
  * detected on process start to the synthetic `HarnessError` the orchestrator feeds through `Fsm.transition` *before*
  * any source-racing begins.
  *
  * `currentDriverSession` always starts at `None` on a fresh process: a prior subprocess does not survive Forge process
  * death, regardless of whether Claude / Codex preserve their session id under `--resume`. So `RebuildState.run`
  * projects the unmatched `spawn`/`resume` markers into `InFlightSession`s, and the orchestrator routes each to NHI
  * with a phase-appropriate hint so the operator decides recovery (no transparent resume — streaming sessions have no
  * in-flight message to re-issue, and headless worktrees may carry partial uncommitted changes).
  *
  * **The hint is the FSM's job, not ours.** Each synthetic `HarnessError` lands via `Fsm.transition`'s `HarnessError`
  * catch-all, which calls `hintFromState` to pick the `ResumeHint`. The doc's per-phase recovery table merely *mirrors*
  * what `hintFromState` already produces (`InteractiveSpec → AbortOrAbandon`, `DesignReviewing → ReopenDesign(None)`
  * while no PR exists, `DesignPrFeedback → ReopenDesign(Some(pr))`, `PieceImplementing →
  * ResolveLocalImplementationChanges`, `PieceFixingUp → RunAnotherFixup`). This object only produces the typed `reason:
  * String`; [[recover]] then trusts the FSM's default-hint table. `OrchestratorRestartSuite` pins that the two agree.
  */
object RestartRecovery:

  /** The synthetic `HarnessError` for one in-flight session, keyed on `(phase, state)` per the doc's recovery table. An
    * unexpected `(phase, state)` pair (a corrupt/inconsistent log) still yields a `HarnessError` — recovery routes it
    * to NHI rather than crashing the recovery path itself.
    */
  def syntheticHarnessError(session: InFlightSession, state: FsmState): FsmEvent.HarnessError =
    FsmEvent.HarnessError(reason(session.phase, state))

  private def reason(phase: SessionPhase, state: FsmState): String =
    (phase, state) match
      case (SessionPhase.Spec, FsmState.InteractiveSpec) =>
        "spec session interrupted by process restart"
      case (SessionPhase.DesignRevision, _: FsmState.DesignReviewing) =>
        "design revision interrupted by process restart"
      case (SessionPhase.DesignRevision, _: FsmState.DesignPrFeedback) =>
        "design PR feedback session interrupted by process restart"
      case (SessionPhase.Implement, _: FsmState.PieceImplementing) =>
        "implementation interrupted by process restart; worktree may have uncommitted changes"
      case (SessionPhase.Fixup, _: FsmState.PieceFixingUp) =>
        "fix-up interrupted by process restart; worktree may have uncommitted changes"
      case _ =>
        s"$phase session interrupted by process restart (unexpected state $state)"

  /** Fold each in-flight session through `Fsm.transition` as a synthetic `HarnessError`, accumulating the action
    * drafts. This is the loop-start recovery block (the `for each s in inFlightSessions` step of the J1 pseudocode),
    * kept pure here so `Fsm.transition`'s purity makes it directly table-testable. The first session lands the feature
    * in NHI; any subsequent session re-routes through the same catch-all (NHI is non-terminal), which is harmless — a
    * coherent single-feature log carries at most one in-flight driver session.
    */
  def recover(
      feature: Feature,
      sessions: Vector[InFlightSession],
      config: FsmConfig = FsmConfig.default
  ): (Feature, Vector[ActionDraft]) =
    sessions.foldLeft((feature, Vector.empty[ActionDraft])) { case ((f, drafts), s) =>
      val (f2, d2) = Fsm.transition(f, syntheticHarnessError(s, f.state), config)
      (f2, drafts ++ d2)
    }
