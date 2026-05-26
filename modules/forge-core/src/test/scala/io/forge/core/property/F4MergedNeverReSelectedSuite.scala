package io.forge.core.property

import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}
import io.forge.core.manifest.PieceStatus

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F4 — §17 slice-2 invariant 5: a merged piece is never re-selected by the next-pending walk.
  *
  * Property: after running the happy-path trajectory, every state of the form `PieceImplementing(p)` in the trace
  * references a piece whose seed-manifest status was `Pending` (never one that was already `Merged`). The §11.7
  * `Manifest.nextPending` selector skips merged pieces; F4 is the property-level check.
  */
class F4MergedNeverReSelectedSuite extends ScalaCheckSuite:

  property("F4 — every PieceImplementing(p) in the trace targets a piece that was pending in the seed manifest") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val mergedAtSeed = seed.manifest.pieces.filter(_.status == PieceStatus.Merged).map(_.id).toSet
      val run = FsmTrajectory.happyPath(seed)
      val violations = run.states.collect:
        case FsmState.PieceImplementing(p) if mergedAtSeed.contains(p) => p
      violations.isEmpty :| s"PieceImplementing re-selected merged pieces $violations from seed-merged $mergedAtSeed"
    }
  }

  property("F4 — every Refining(p, _, _) and PieceAwaitingMerge(p, _) in the trace targets a non-seed-merged piece") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val mergedAtSeed = seed.manifest.pieces.filter(_.status == PieceStatus.Merged).map(_.id).toSet
      val run = FsmTrajectory.happyPath(seed)
      val violations = run.states.collect:
        case FsmState.Refining(p, _, _) if mergedAtSeed.contains(p) => p
        case FsmState.PieceAwaitingMerge(p, _) if mergedAtSeed.contains(p) => p
      violations.isEmpty :| s"piece states for already-merged seed pieces $violations"
    }
  }
