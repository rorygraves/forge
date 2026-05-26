package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.pr.{
  CheckConclusion,
  CheckResult,
  CheckRollup,
  CheckState,
  PrComment,
  PrSnapshot,
  PrState,
  ReviewDecision
}

import java.time.Instant

/** Shared FSM-suite fixtures. Lives here (not in `FsmFixturesObject`) so each `Fsm_11_*Suite` can import the bare names
  * without ceremony. The fixtures keep `Manifest.validate` happy by default; suites that want a specific shape (e.g.
  * one piece merged + one pending) override pieces directly.
  */
object FsmFixtures:

  val FeatureA: FeatureId = FeatureId("stripe-webhook")
  val P1: PieceId = PieceId("p1")
  val P2: PieceId = PieceId("p2")
  val P3: PieceId = PieceId("p3")

  val DesignPr: PrNumber = PrNumber(4290)
  val P1Pr: PrNumber = PrNumber(4291)
  val P2Pr: PrNumber = PrNumber(4292)

  val Sha40: Sha = Sha("a" * 40)
  val Sha40Other: Sha = Sha("b" * 40)
  val DesignBranch: BranchName = BranchName("forge/stripe-webhook/design")
  val P1Branch: BranchName = BranchName("forge/stripe-webhook/p1")

  val MergedAt: Instant = Instant.parse("2026-05-26T12:00:00Z")
  val ObservedAt: Instant = Instant.parse("2026-05-26T12:00:05Z")

  def piecePending(id: PieceId, order: Int): Piece =
    Piece(
      id = id,
      order = order,
      title = s"Piece ${id.value}",
      summary = s"summary ${id.value}",
      specPath = s".forge/specs/${FeatureA.value}/pieces/${id.value}.md",
      acceptanceHash = "sha256:" + ("0" * 64),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  def pieceInProgress(
      id: PieceId,
      order: Int,
      baseSha: Sha = Sha40,
      prNumber: Option[PrNumber] = None,
      attempts: Int = 0
  ): Piece =
    piecePending(id, order).copy(
      status = PieceStatus.InProgress,
      baseSha = Some(baseSha),
      prNumber = prNumber,
      attempts = attempts
    )

  def pieceMerged(
      id: PieceId,
      order: Int,
      prNumber: PrNumber,
      mergeCommit: Sha = Sha40Other,
      mergedAt: Instant = MergedAt,
      attempts: Int = 0
  ): Piece =
    piecePending(id, order).copy(
      status = PieceStatus.Merged,
      baseSha = Some(Sha40),
      prNumber = Some(prNumber),
      mergeCommit = Some(mergeCommit),
      mergedAt = Some(mergedAt),
      attempts = attempts
    )

  def manifest(pieces: Vector[Piece], designPr: Option[PrNumber] = None): Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = FeatureA,
      title = "Add Stripe webhook receiver",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = designPr,
      pieces = pieces
    )

  def featureIn(
      state: FsmState,
      pieces: Vector[Piece] = Vector(piecePending(P1, 1), piecePending(P2, 2)),
      designSessionId: Option[String] = None,
      currentPieceSessionId: Option[String] = None,
      designPr: Option[PrNumber] = None,
      branchProtectionCacheEpoch: Long = 0L,
      designPrFeedbackRound: Int = 0
  ): Feature =
    Feature(
      id = FeatureA,
      manifest = manifest(pieces, designPr),
      state = state,
      cost = io.forge.core.cost.CostTotals.zero,
      designSessionId = designSessionId,
      currentPieceSessionId = currentPieceSessionId,
      branchProtectionCacheEpoch = branchProtectionCacheEpoch,
      designPrFeedbackRound = designPrFeedbackRound
    )

  // --- PR snapshot helpers ---

  def emptySnapshot(number: PrNumber, state: PrState): PrSnapshot =
    PrSnapshot(
      number = number,
      state = state,
      mergedAt = if state == PrState.Merged then Some(MergedAt) else None,
      mergeCommit = if state == PrState.Merged then Some(Sha40Other) else None,
      requiredChecks = CheckRollup.empty,
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  def successfulCi(number: PrNumber): PrSnapshot =
    emptySnapshot(number, PrState.Open).copy(
      requiredChecks = CheckRollup(
        required = Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Success))),
        observed = Vector.empty
      )
    )

  def failedCi(number: PrNumber): PrSnapshot =
    emptySnapshot(number, PrState.Open).copy(
      requiredChecks = CheckRollup(
        required = Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Failure))),
        observed = Vector.empty
      )
    )

  def changesRequestedSnapshot(number: PrNumber): PrSnapshot =
    emptySnapshot(number, PrState.Open).copy(reviewDecision = Some(ReviewDecision.ChangesRequested))

  def humanCommentSnapshot(number: PrNumber): PrSnapshot =
    emptySnapshot(number, PrState.Open).copy(
      unseenComments = Vector(
        PrComment("c1", "alice", "needs work", Instant.parse("2026-05-26T11:59:00Z"), None, None)
      )
    )

  def closedSnapshot(number: PrNumber): PrSnapshot = emptySnapshot(number, PrState.Closed)

  def mergedSnapshot(number: PrNumber): PrSnapshot = emptySnapshot(number, PrState.Merged)
