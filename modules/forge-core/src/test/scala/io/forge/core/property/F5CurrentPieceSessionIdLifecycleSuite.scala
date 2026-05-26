package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F5 — §17 slice-2 invariant 6: `currentPieceSessionId` lifecycle.
  *
  * §6.1 specifies:
  *   - populated at `PieceImplementing` / `PieceFixingUp` spawn;
  *   - retained through `PieceAwaitingCi`, `PieceAwaitingReview`, `PieceCiFailed`, `PieceReviewFailed`,
  *     `PieceFixingUp`, `PieceAwaitingMerge`, `Refining`;
  *   - cleared at the advance transition (next-piece or `FeatureDone` or NHI).
  *
  * Property: walk every step in a happy-path trajectory. For each `(fromState, toState)` pair:
  *   - if `toState` is a piece-bearing state in the "retained" set, the projection must be `Some(_)`;
  *   - if `toState` is the next `PieceImplementing` (advance from `Refining`) or `FeatureDone`, the projection must be
  *     `None`.
  */
class F5CurrentPieceSessionIdLifecycleSuite extends ScalaCheckSuite:

  private def isPieceRetainState(s: FsmState): Boolean = s match
    case _: FsmState.PieceImplementing => true
    case _: FsmState.PieceAwaitingCi => true
    case _: FsmState.PieceAwaitingReview => true
    case _: FsmState.PieceCiFailed => true
    case _: FsmState.PieceReviewFailed => true
    case _: FsmState.PieceFixingUp => true
    case _: FsmState.PieceAwaitingMerge => true
    case _: FsmState.Refining => true
    case _ => false

  private def isAdvanceTarget(s: FsmState): Boolean = s match
    case FsmState.FeatureDone => true
    case _: FsmState.NeedsHumanIntervention => true
    case _ => false

  property("F5 — currentPieceSessionId populated through every retain-set piece state after the spawn") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // Walk each step's *resulting* feature. The projection is `Some(sid)` whenever the resulting state is in the
      // retain set, **after the first PieceImplementing SessionSpawned for that piece**. (Before the spawn arrives,
      // the projection is None — that's `BranchCreated`'s pre-spawn moment.)
      // We track whether we've seen a spawn for the current piece since entering its PieceImplementing.
      var sawSpawnForCurrent = false
      var currentPiece: Option[PieceId] = None
      val violations = run.steps.flatMap { step =>
        // Detect entry to a fresh PieceImplementing (advance from Refining or from DesignReady).
        step.toState match
          case FsmState.PieceImplementing(p) if !currentPiece.contains(p) =>
            currentPiece = Some(p)
            sawSpawnForCurrent = false
          case _ => ()
        // Detect SessionSpawned for the current piece (the FSM step's event is in `step.event`).
        step.event match
          case FsmEvent.SessionSpawned(_, _, _, Some(p)) if currentPiece.contains(p) =>
            sawSpawnForCurrent = true
          case _ => ()
        val feature = run.steps.lift(run.steps.indexOf(step)).map(_ => ()).flatMap(_ => None) // placeholder
        // We need the resulting feature at this step; reconstruct by replaying up to this point.
        val resultingFeature = reconstructFeatureAt(run.seed, run.events.take(run.steps.indexOf(step) + 1))
        val to = step.toState
        if isPieceRetainState(to) && sawSpawnForCurrent then
          if resultingFeature.currentPieceSessionId.isEmpty then
            Some(s"retain-state $to but currentPieceSessionId=None")
          else None
        else None
      }
      violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F5 — currentPieceSessionId cleared on advance (FeatureDone) and on NHI") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val violations = run.steps.zipWithIndex.flatMap { case (step, idx) =>
        val resultingFeature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
        if isAdvanceTarget(step.toState) && resultingFeature.currentPieceSessionId.isDefined then
          Some(s"advance/NHI to ${step.toState} left currentPieceSessionId=${resultingFeature.currentPieceSessionId}")
        else None
      }
      violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F5 — Refining → next PieceImplementing clears currentPieceSessionId at the transition") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // Find every Refining → PieceImplementing(next) transition; assert post-state projection is cleared.
      val violations = run.steps.zipWithIndex.flatMap:
        case (step, idx) =>
          (step.fromState, step.toState) match
            case (_: FsmState.Refining, _: FsmState.PieceImplementing) =>
              val resultingFeature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
              if resultingFeature.currentPieceSessionId.isDefined then
                Some(s"Refining → PieceImplementing transition left currentPieceSessionId set")
              else None
            case _ => None
      violations.isEmpty :| s"violations: $violations"
    }
  }

  /** Re-run `Fsm.transition` from `seed` over the first `n` events to get the feature at that point. The happy-path
    * trajectory is deterministic, so this is a constant cost per step.
    */
  private def reconstructFeatureAt(seed: Feature, events: Vector[FsmEvent]): Feature =
    events.foldLeft(seed) { case (f, ev) =>
      val (next, _) = Fsm.transition(f, ev)
      next
    }
