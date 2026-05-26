package io.forge.core.property

import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F3 — §17 slice-2 invariant 4: design must complete before implementation begins.
  *
  * Property: in any happy-path trajectory from `Drafting`, the trace `Vector[FsmState]` contains `DesignReady` at some
  * index strictly earlier than the first index of any `PieceImplementing(_)`. The §11.3 design-PR-merge gate is the
  * only legal entry into the implementation phase.
  */
class F3DesignBeforeImplementSuite extends ScalaCheckSuite:

  property("F3 — every PieceImplementing(_) in the trace is preceded by DesignReady") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val states = run.states
      val designReadyIdx = states.indexOf(FsmState.DesignReady)
      val firstImplIdx = states.indexWhere:
        case _: FsmState.PieceImplementing => true
        case _ => false
      if firstImplIdx < 0 then
        // No PieceImplementing reached (e.g., manifest has no pending pieces) — invariant trivially holds.
        proved
      else
        ((designReadyIdx >= 0) :| s"no DesignReady in trace before PieceImplementing: states=$states") &&
        ((designReadyIdx < firstImplIdx) :| s"DesignReady idx=$designReadyIdx must be < PieceImplementing idx=$firstImplIdx; states=$states")
    }
  }
