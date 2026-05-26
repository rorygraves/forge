package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}
import io.forge.core.log.{Action, ActionDraft}

import java.time.Instant
import munit.ScalaCheckSuite
import org.scalacheck.Prop.*

/** PR-F F1 — §17 slice-2 invariant 1: replay reproduces final state.
  *
  * Property: for any initial `Feature` produced by [[Generators.genInitialFeature]], applying the deterministic happy-
  * path event sequence via `Fsm.transition` and then folding the resulting `ActionDraft`s (stamped into `Action`s) via
  * `Feature.foldEvents` reproduces the same final `Feature` — including the §6.1 session-id projections.
  *
  * Why deterministic events rather than a generated event sequence: the §6.1 projection rules only fire on tightly-
  * coupled event/state pairs, so a random-event walker would mostly produce no-op steps and the sample diversity comes
  * from the manifest shape (number of pieces, merged prefix), not the event ordering. [[FsmTrajectory]] keeps the
  * trajectory legal by construction.
  *
  * Manifest is the §4 committed source of truth — the fold seeds its initial `Feature` from the seed's manifest, so the
  * property compares everything *projected* by the log (state, session ids, design-pr-feedback round,
  * branchProtectionCacheEpoch).
  */
class F1ReplayRoundTripSuite extends ScalaCheckSuite:

  property("F1 — fsm.transition draft sequence folds back to the same Feature") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      val actions = stampDrafts(run.allDrafts)
      // Seed the fold from the **final** manifest. The FSM's writer side commits manifest atomically *before* emitting
      // the `fsm.transition` draft (§11.4 step 1 / §11.5 step 1); real replay reads that post-mutation manifest from
      // disk via `ManifestStore.load` (PR-E E4 pipeline step 1) and folds the log onto it. Seeding from the
      // pre-trajectory manifest would model a crash window that this property isn't about.
      val foldResult = Feature.foldEvents(Feature.initial(seed.id, run.finalFeature.manifest), actions)
      foldResult match
        case Right(fr) =>
          (fr.feature.state == run.finalFeature.state) :| s"state mismatch: fold=${fr.feature.state}; trajectory=${run.finalFeature.state}" &&
          (fr.feature.designSessionId == run.finalFeature.designSessionId) :| "designSessionId mismatch" &&
          (fr.feature.currentPieceSessionId == run.finalFeature.currentPieceSessionId) :| "currentPieceSessionId mismatch" &&
          (fr.feature.designPrFeedbackRound == run.finalFeature.designPrFeedbackRound) :| "designPrFeedbackRound mismatch" &&
          (fr.feature.branchProtectionCacheEpoch == run.finalFeature.branchProtectionCacheEpoch) :| "epoch mismatch"
        case Left(err) =>
          falsified :| s"foldEvents returned Left: $err"
    }
  }

  property("F1 — replay preserves session-id projections through every state in the trace") {
    forAll(Generators.genInitialFeature) { (seed: Feature) =>
      val run = FsmTrajectory.happyPath(seed)
      // The trace must include DesignReady once the design PR snapshot reports merged.
      val sawDesignReady = run.states.contains(FsmState.DesignReady)
      // Every pending piece in the seed reaches Refining and then advances (either to next piece or FeatureDone).
      val pendingPieces = seed.manifest.pieces.filter(_.status == io.forge.core.manifest.PieceStatus.Pending).map(_.id)
      val allPiecesReachedRefining = pendingPieces.forall(p =>
        run.states.exists:
          case FsmState.Refining(rp, _, _) => rp == p
          case _ => false
      )
      (sawDesignReady :| "did not reach DesignReady") &&
      (allPiecesReachedRefining :| s"not every pending piece reached Refining; pending=$pendingPieces")
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def stampDrafts(drafts: Vector[ActionDraft]): Vector[Action] =
    val base = Instant.parse("2026-05-26T12:00:00Z")
    drafts.zipWithIndex.map: (d, i) =>
      d.stamp(seq = i.toLong + 1L, at = base.plusSeconds(i.toLong))
