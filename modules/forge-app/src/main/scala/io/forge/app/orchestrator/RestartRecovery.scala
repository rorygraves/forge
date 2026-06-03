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
  * in-flight message to re-issue).
  *
  * **D3-3 (roadmap §3.5 driver-respawn-avoidance) exception.** A *headless piece driver* in-flight session
  * (`(Implement, PieceImplementing)` / `(Fixup, PieceFixingUp)`) is **not** synthesised to NHI here. Headless drivers
  * gained a `--resume` seam (D3-1) and the worktree-safety classifier (D3-2) can now tell the expected
  * driver-uncommitted state from unexpected divergence, so the resume-vs-NHI decision is gated — and that gate is IO
  * (it reads `git`), so it lives in the orchestrator loop's entry hook (`Orchestrator.resumePieceDriver`), not in this
  * pure mapping. [[recover]] therefore *leaves* those sessions' features in their driver state and lets the loop resume
  * (safe) or route to NHI (unsafe). Every other in-flight phase — spec / design, and any inconsistent `(phase, state)`
  * pair — keeps the unconditional synthetic-NHI behaviour.
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
      case (SessionPhase.Fixup, _: FsmState.PieceBuildFixingUp) =>
        "pre-PR build fix-up interrupted by process restart; worktree may have uncommitted changes"
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
      // D3-3: a resumable piece-driver session is left for the loop's IO-gated resume-vs-NHI decision (see the class
      // doc) — no synthetic NHI here.
      if loopResumablePieceDriver(s.phase, f.state) then (f, drafts)
      else
        val (f2, d2) = Fsm.transition(f, syntheticHarnessError(s, f.state), config)
        (f2, drafts ++ d2)
    }

  /** D3-3: the `(phase, state)` pairs whose resume the orchestrator loop gates on the D3-2 worktree classifier rather
    * than this pure mapping. Only the *consistent* piece-driver pairs qualify — an inconsistent pair (e.g. an
    * `Implement` session while the FSM is in `InteractiveSpec`) still falls through to the defensive synthetic NHI.
    */
  private def loopResumablePieceDriver(phase: SessionPhase, state: FsmState): Boolean =
    (phase, state) match
      case (SessionPhase.Implement, _: FsmState.PieceImplementing) => true
      case (SessionPhase.Fixup, _: FsmState.PieceFixingUp) => true
      // §8.3 / §11.4 (1.8) pre-PR build fix-up — a Fixup-phase piece driver, resumable like PieceFixingUp.
      case (SessionPhase.Fixup, _: FsmState.PieceBuildFixingUp) => true
      case _ => false
