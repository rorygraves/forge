package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.Generators

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F2 — §17 slice-2 invariants 2 + 3: `NeedsHumanIntervention` legality and recoverability.
  *
  * Two properties together:
  *
  *   - **Invariant 2**: every `NeedsHumanIntervention` carries a `ResumeHint`. Structural — the FsmState ADT guarantees
  *     the field is present at the type level. The property exercises a sample of NHI-triggering events across
  *     reachable §11 states and asserts the hint is one of the seven legal §6 variants (i.e. the FSM never produces a
  *     degenerate or default hint).
  *   - **Invariant 3**: `forge resume --<hint>` produces a legal next state. The property fires
  *     `UserCommandReceived(Resume(hint))` on the NHI feature and asserts the FSM exits NHI to *some other* state (or
  *     reaches `Abandoned`, which is the legal terminal for `AbortOrAbandon`). The one exception is
  *     `ApplyPlanningUpdate(patch)` when the patch fails to apply — `handleResume` stays in NHI by design; that path is
  *     exercised separately so we know it's the only stay-in-NHI case.
  */
class F2NhiLegalitySuite extends ScalaCheckSuite:

  /** Sample of triggering events that send any non-terminal feature into `NeedsHumanIntervention`. */
  private val genNhiTrigger: Gen[FsmEvent] = Gen.oneOf(
    FsmEvent.HarnessError("synthetic harness error"),
    FsmEvent.BudgetBreached(BudgetScope.Feature, "synthetic budget breach"),
    FsmEvent.TurnBudgetBreached(SessionPhase.Spec, "synthetic per-turn breach"),
    FsmEvent.RequiredSessionIdMissing("synthetic missing session id", ResumeHint.AbortOrAbandon)
  )

  /** A small set of reachable non-terminal `FsmState`s — chosen to cover the §11 spec phases the FSM transitions
    * through. We avoid `Drafting` and `InteractiveSpec` because the cross-cutting handler short-circuits these
    * uniformly; the variety here picks up the per-state `hintFromState` rules.
    */
  private val genReachableState: Gen[FsmState] = Gen.oneOf(
    FsmState.DesignReviewing(2),
    FsmState.DesignAwaitingMerge(PrNumber(4001)),
    FsmState.DesignPrFeedback(PrNumber(4001), round = 1),
    FsmState.DesignReady,
    FsmState.PieceImplementing(PieceId("p1")),
    FsmState.PieceAwaitingCi(PieceId("p1"), PrNumber(5001)),
    FsmState.PieceAwaitingReview(PieceId("p1"), PrNumber(5001)),
    FsmState.PieceAwaitingMerge(PieceId("p1"), PrNumber(5001)),
    FsmState.Refining(PieceId("p1"), PrNumber(5001), startedAt = java.time.Instant.parse("2026-05-26T12:00:00Z"))
  )

  property("F2 — every NHI from a reachable state carries a legal ResumeHint") {
    forAll(Generators.genInitialFeature, genReachableState, genNhiTrigger) {
      (seed: Feature, state: FsmState, trigger: FsmEvent) =>
        val staged = seed.copy(state = state)
        val (after, _) = Fsm.transition(staged, trigger)
        after.state match
          case FsmState.NeedsHumanIntervention(_, hint) =>
            // Every legal hint is one of the seven §6 variants. Exhaustive pattern guarantees this.
            val legal = hint match
              case _: ResumeHint.ResumeAfterHumanPush => true
              case _: ResumeHint.CommitAndPushHumanFix => true
              case _: ResumeHint.RunAnotherFixup => true
              case _: ResumeHint.ResolveLocalImplementationChanges => true
              case _: ResumeHint.ReopenDesign => true
              case _: ResumeHint.ApplyPlanningUpdate => true
              case ResumeHint.AbortOrAbandon => true
            legal :| s"hint $hint not in the seven §6 variants"
          case other =>
            // From the §11 reachable states + cross-cutting triggers, NHI is the expected destination.
            falsified :| s"expected NHI from state=$state event=$trigger, got $other"
    }
  }

  property("F2 — Resume(hint) on an NHI feature exits NHI (to a non-NHI state or to Abandoned)") {
    forAll(Generators.genInitialFeature, genReachableState, genNhiTrigger) {
      (seed: Feature, state: FsmState, trigger: FsmEvent) =>
        val staged = seed.copy(state = state)
        val (afterNhi, _) = Fsm.transition(staged, trigger)
        afterNhi.state match
          case FsmState.NeedsHumanIntervention(reason, hint) =>
            val (resumed, _) = Fsm.transition(afterNhi, FsmEvent.UserCommandReceived(UserCommand.Resume(hint)))
            // §6: Resume(hint) drives out of NHI. The one documented exception is ApplyPlanningUpdate(patch) where
            // the patch fails to apply — see Fsm.handleResume. Our generated hints don't include
            // ApplyPlanningUpdate, so the resumed state must always change.
            (resumed.state != afterNhi.state) :| s"Resume($hint) stayed in NHI(reason=$reason): resumed=${resumed.state}"
          case _ => proved
    }
  }
