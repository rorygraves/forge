package io.forge.core.property

import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}
import io.forge.core.manifest.PieceStatus

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F12 — §17 slice-2 invariant 13: any piece with `status != pending` has non-null `baseSha`.
  *
  * §5.1 says:
  *   - `pending`: every field null;
  *   - `in_progress`: `baseSha` non-null;
  *   - `merged`: `baseSha`, `prNumber`, `mergeCommit`, `mergedAt` all non-null.
  *
  * `Manifest.validate` checks these per piece. The FSM mutates pieces inline at `BranchCreated` (§11.4 step 1, setting
  * `baseSha`) and `Merged` (§11.5 step 1, setting the merge fields). F12 asserts the invariant holds across every state
  * in a happy-path trajectory, not just at the seed.
  */
class F12BaseShaInvariantSuite extends ScalaCheckSuite:

  property("F12 — every FSM-driven manifest mutation keeps status != pending → baseSha non-null") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // For each step, examine the resulting manifest's pieces. Every non-pending piece must have non-null baseSha.
      val violations = run.steps.zipWithIndex.flatMap { case (_, idx) =>
        val feature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
        feature.manifest.pieces.collect:
          case p if p.status != PieceStatus.Pending && p.baseSha.isEmpty =>
            s"step=$idx piece=${p.id} status=${p.status} baseSha=None"
      }
      violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F12 — every merged piece in the FSM-mutated manifest has prNumber, mergeCommit, mergedAt set") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val violations = run.steps.zipWithIndex.flatMap { case (_, idx) =>
        val feature = reconstructFeatureAt(run.seed, run.events.take(idx + 1))
        feature.manifest.pieces.collect:
          case p
              if p.status == PieceStatus.Merged && (p.prNumber.isEmpty || p.mergeCommit.isEmpty || p.mergedAt.isEmpty) =>
            s"step=$idx piece=${p.id} merged but fields incomplete: prNumber=${p.prNumber} mergeCommit=${p.mergeCommit} mergedAt=${p.mergedAt}"
      }
      violations.isEmpty :| s"violations: $violations"
    }
  }

  property("F12 — the final FSM manifest still validates") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      run.finalFeature.manifest.validate match
        case Right(_) => proved
        case Left(errs) => falsified :| s"final manifest fails validate: ${errs.mkString("; ")}"
    }
  }

  private def reconstructFeatureAt(seed: Feature, events: Vector[FsmEvent]): Feature =
    events.foldLeft(seed) { case (f, ev) =>
      val (next, _) = Fsm.transition(f, ev)
      next
    }
