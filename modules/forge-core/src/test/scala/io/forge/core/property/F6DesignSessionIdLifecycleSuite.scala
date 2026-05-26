package io.forge.core.property

import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F6 — §17 slice-2 invariant 7: `designSessionId` lifecycle.
  *
  * §6.1 specifies:
  *   - populated at first `InteractiveSpec` spawn / design-revision resume;
  *   - retained through every design-phase state (`DesignReviewing`, `DesignNeedsHumanInput`, `DesignAwaitingMerge`,
  *     `DesignPrFeedback`);
  *   - cleared on entering `DesignReady`;
  *   - `<actor>.resume` with `newSessionId == oldSessionId` is idempotent (pinned-CLI norm).
  *
  * Property: walk the happy-path trajectory. After the first `SessionSpawned(_, _, _, None)`, the projection is
  * `Some(_)` through every design-phase state; on entering `DesignReady`, the projection becomes `None`.
  */
class F6DesignSessionIdLifecycleSuite extends ScalaCheckSuite:

  private def isDesignPhaseState(s: FsmState): Boolean = s match
    case FsmState.InteractiveSpec => true
    case _: FsmState.DesignReviewing => true
    case _: FsmState.DesignNeedsHumanInput => true
    case _: FsmState.DesignAwaitingMerge => true
    case _: FsmState.DesignPrFeedback => true
    case _ => false

  property("F6 — designSessionId populated through every design-phase state after the first spec spawn") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // Find the first SessionSpawned with piece=None — that's the spec driver's spawn.
      val firstSpawnIdx = run.events.indexWhere {
        case FsmEvent.SessionSpawned(_, _, _, None) => true
        case _ => false
      }
      if firstSpawnIdx < 0 then proved
      else
        val violations = run.steps.zipWithIndex.drop(firstSpawnIdx + 1).flatMap { case (step, idx) =>
          val resultingFeature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
          if isDesignPhaseState(step.toState) && resultingFeature.designSessionId.isEmpty then
            Some(s"design-phase state ${step.toState} but designSessionId=None")
          else None
        }
        violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F6 — designSessionId cleared on entering DesignReady") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val violations = run.steps.zipWithIndex.flatMap { case (step, idx) =>
        step.toState match
          case FsmState.DesignReady =>
            val resultingFeature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
            if resultingFeature.designSessionId.isDefined then
              Some(s"DesignReady entered with designSessionId=${resultingFeature.designSessionId}")
            else None
          case _ => None
      }
      violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F6 — SessionResumed with newSessionId == oldSessionId is idempotent on the projection") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // Find the post-spec-spawn projection, then apply a same-id SessionResumed and confirm the projection doesn't
      // change. (Idempotency proof; the §6.1 pinned-CLI invariant.)
      val firstSpawnIdx = run.events.indexWhere:
        case FsmEvent.SessionSpawned(_, _, _, None) => true
        case _ => false
      if firstSpawnIdx < 0 then proved
      else
        val afterSpawn = reconstructFeatureAt(run.seed, run.events.take(firstSpawnIdx + 1))
        afterSpawn.designSessionId match
          case Some(sid) =>
            val sameIdResume = FsmEvent.SessionResumed(
              actor = "claude",
              role = "spec-driver",
              oldSessionId = sid,
              newSessionId = sid,
              piece = None
            )
            val (after, _) = Fsm.transition(afterSpawn, sameIdResume)
            (after.designSessionId == Some(sid)) :| s"projection drift after same-id resume: ${after.designSessionId}"
          case None => proved
    }
  }

  private def reconstructFeatureAt(seed: Feature, events: Vector[FsmEvent]): Feature =
    events.foldLeft(seed) { case (f, ev) =>
      val (next, _) = Fsm.transition(f, ev)
      next
    }
