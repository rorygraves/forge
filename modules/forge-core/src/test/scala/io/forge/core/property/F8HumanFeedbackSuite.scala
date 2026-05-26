package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F8 — §17 slice-2 invariant 9: human feedback before merge returns to revision / fix-up.
  *
  * The FSM splits human-feedback handling by phase:
  *   - **Design phase** (`DesignAwaitingMerge`): a snapshot with `ChangesRequested` or unseen comments transitions to
  *     `DesignPrFeedback(prNumber, round + 1)`.
  *   - **Implementation phase** (`PieceAwaitingReview` / `PieceAwaitingMerge`): a snapshot with human override
  *     transitions to `PieceReviewFailed(p, prNumber, attempt+1)`. The subsequent `SessionSpawned` then drives the
  *     `PieceReviewFailed → PieceFixingUp` step (§11.6 fresh-driver-session). F8 asserts the *first* transition is to
  *     `PieceReviewFailed`, not a forward state like `PieceAwaitingMerge` or `Refining`.
  *
  * "Never to a forward state" is the load-bearing claim: §11.5 explicitly says new human comments must not be
  * suppressed by a concurrent reviewer approve / merge.
  */
class F8HumanFeedbackSuite extends ScalaCheckSuite:

  /** A feature seed that's guaranteed to have at least one in-progress piece at the requested id. We build a small
    * fixture manifest directly rather than mutating a generated one — the FSM is sensitive to manifest validity, and
    * patching arbitrary generated manifests in place tends to violate §5.1 invariants (e.g., when the target piece id
    * is already merged in the seed).
    */
  private def featureWithInProgressPiece(piece: PieceId, prNumber: PrNumber): Feature =
    val featureId = FeatureId("test-feature")
    val baseSha = Sha("e" * 40)
    val inProgress = Generators
      .inProgressPiece(idx = 0, baseSha = baseSha, prNumber = Some(prNumber), attempts = 0)
      .copy(id = piece)
    val manifest = io.forge.core.manifest.Manifest(
      schemaVersion = io.forge.core.manifest.Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "Test feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector(inProgress)
    )
    Generators.initialFeature(manifest)

  private val genPieceId: Gen[PieceId] = Gen.oneOf("p1", "p2", "p3").map(PieceId(_))
  private val genFeatureSeed: Gen[Feature] = Generators.genInitialFeature

  /** ScalaCheck-controlled choice between the two "human override" snapshot shapes the FSM treats identically — a
    * reviewer `CHANGES_REQUESTED` decision and one or more unseen human comments. Routing the choice through the
    * generator (rather than `scala.util.Random`) keeps the failing seed reproducible: if the regression hits only one
    * shape, the seed reported by ScalaCheck reproduces it exactly.
    */
  private def genHumanFeedbackSnapshot(prNumber: PrNumber): Gen[io.forge.core.pr.PrSnapshot] =
    Gen.oneOf(
      FsmTrajectory.changesRequestedSnapshot(prNumber),
      FsmTrajectory.humanCommentSnapshot(prNumber)
    )

  property("F8 — DesignAwaitingMerge + human-feedback snapshot → DesignPrFeedback (never forward to DesignReady)") {
    forAll(genFeatureSeed, Generators.genPrNumber) { (seed: Feature, prNumber: PrNumber) =>
      forAll(genHumanFeedbackSnapshot(prNumber)) { snapshot =>
        val staged = seed.copy(
          state = FsmState.DesignAwaitingMerge(prNumber),
          designSessionId = Some("sid-design"),
          manifest = seed.manifest.copy(designPr = Some(prNumber))
        )
        val (after, _) = Fsm.transition(staged, FsmEvent.DesignPrSnapshotUpdated(snapshot))
        after.state match
          case _: FsmState.DesignPrFeedback => proved
          case FsmState.DesignReady => falsified :| s"forward to DesignReady from human-feedback"
          case other => falsified :| s"expected DesignPrFeedback, got $other"
      }
    }
  }

  property("F8 — PieceAwaitingReview + human override → PieceReviewFailed (never forward)") {
    forAll(genPieceId, Generators.genPrNumber) { (piece: PieceId, prNumber: PrNumber) =>
      forAll(genHumanFeedbackSnapshot(prNumber)) { snapshot =>
        val staged = featureWithInProgressPiece(piece, prNumber).copy(
          state = FsmState.PieceAwaitingReview(piece, prNumber),
          currentPieceSessionId = Some("sid-piece")
        )
        val (after, _) = Fsm.transition(staged, FsmEvent.PrSnapshotUpdated(piece, snapshot))
        after.state match
          case _: FsmState.PieceReviewFailed => proved
          case _: FsmState.PieceAwaitingMerge => falsified :| "forward to PieceAwaitingMerge"
          case _: FsmState.Refining => falsified :| "forward to Refining"
          case FsmState.FeatureDone => falsified :| "forward to FeatureDone"
          case other => falsified :| s"unexpected target $other"
      }
    }
  }

  property("F8 — PieceAwaitingMerge + human override → PieceReviewFailed (never to Refining)") {
    forAll(genPieceId, Generators.genPrNumber) { (piece: PieceId, prNumber: PrNumber) =>
      forAll(genHumanFeedbackSnapshot(prNumber)) { snapshot =>
        val staged = featureWithInProgressPiece(piece, prNumber).copy(
          state = FsmState.PieceAwaitingMerge(piece, prNumber),
          currentPieceSessionId = Some("sid-piece")
        )
        val (after, _) = Fsm.transition(staged, FsmEvent.PrSnapshotUpdated(piece, snapshot))
        after.state match
          case _: FsmState.PieceReviewFailed => proved
          case _: FsmState.Refining => falsified :| "forward to Refining"
          case FsmState.FeatureDone => falsified :| "forward to FeatureDone"
          case other => falsified :| s"unexpected target $other"
      }
    }
  }
