package io.forge.core.property

import io.forge.core.*
import io.forge.core.fsm.*
import io.forge.core.gen.{FsmTrajectory, Generators}
import io.forge.core.pr.*

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** PR-F F9 — §17 slice-2 invariant 10: CI cannot become "ready" before required-check discovery completes.
  *
  * `CiPolicy.BranchProtectionThenObserved` (the default; §8.1) requires the orchestrator to populate the snapshot's
  * `requiredChecks.required` set before the FSM's `PieceAwaitingCi → PieceAwaitingReview` transition can fire. The
  * `CheckDiscoveryComplete` event is the discovery signal; until then, snapshots arrive with `required.isEmpty` and
  * `ciOutcome` classifies them as `Pending` — the FSM stays in `PieceAwaitingCi`.
  *
  * Under `CiPolicy.None` (§8.1), the gate is skipped — the orchestrator stamps the snapshot's `requiredChecks` to a
  * degenerate "all-success" form, which the FSM treats as ready immediately. The FSM itself is `CiPolicy`-agnostic: the
  * property exercises the snapshot-shape contract that the policy implies.
  */
class F9CiReadinessOrderingSuite extends ScalaCheckSuite:

  private val genPieceId: Gen[PieceId] = Gen.oneOf("p1", "p2").map(PieceId(_))

  private def emptyRollupSnapshot(pr: PrNumber): PrSnapshot =
    FsmTrajectory.openSnapshot(pr) // requiredChecks = CheckRollup.empty by construction

  private def featureInAwaitingCi(piece: PieceId, prNumber: PrNumber): Feature =
    val baseSha = Sha("f" * 40)
    val inProgress = Generators
      .inProgressPiece(idx = 0, baseSha = baseSha, prNumber = Some(prNumber), attempts = 0)
      .copy(id = piece)
    val manifest = io.forge.core.manifest.Manifest(
      schemaVersion = io.forge.core.manifest.Manifest.CurrentSchemaVersion,
      featureId = FeatureId("test-ci"),
      title = "Test feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector(inProgress)
    )
    Generators
      .initialFeature(manifest)
      .copy(
        state = FsmState.PieceAwaitingCi(piece, prNumber),
        currentPieceSessionId = Some("sid-piece")
      )

  property("F9 — PieceAwaitingCi + snapshot with empty required checks → stays in PieceAwaitingCi") {
    forAll(genPieceId, Generators.genPrNumber) { (piece: PieceId, prNumber: PrNumber) =>
      val staged = featureInAwaitingCi(piece, prNumber)
      val (after, _) = Fsm.transition(
        staged,
        FsmEvent.PrSnapshotUpdated(piece, emptyRollupSnapshot(prNumber))
      )
      (after.state == staged.state) :| s"unexpected transition from empty-rollup snapshot: ${after.state}"
    }
  }

  property("F9 — PieceAwaitingCi + snapshot with required checks all Success → PieceAwaitingReview") {
    forAll(genPieceId, Generators.genPrNumber) { (piece: PieceId, prNumber: PrNumber) =>
      val staged = featureInAwaitingCi(piece, prNumber)
      val (after, _) = Fsm.transition(
        staged,
        FsmEvent.PrSnapshotUpdated(piece, FsmTrajectory.successfulCi(prNumber))
      )
      after.state match
        case _: FsmState.PieceAwaitingReview => proved
        case other => falsified :| s"expected PieceAwaitingReview, got $other"
    }
  }

  property("F9 — CheckDiscoveryComplete is a no-op (informational event)") {
    forAll(genPieceId, Generators.genPrNumber) { (piece: PieceId, prNumber: PrNumber) =>
      val staged = featureInAwaitingCi(piece, prNumber)
      val (after, drafts) = Fsm.transition(staged, FsmEvent.CheckDiscoveryComplete(piece, prNumber))
      ((after.state == staged.state) :| s"CheckDiscoveryComplete drove a transition: ${after.state}") &&
      (drafts.isEmpty :| s"CheckDiscoveryComplete emitted drafts: $drafts")
    }
  }
