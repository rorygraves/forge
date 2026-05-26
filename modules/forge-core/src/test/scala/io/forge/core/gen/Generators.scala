package io.forge.core.gen

import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.*
import io.forge.core.manifest.{Manifest, ManifestPatch, ManifestPatchOp, Piece, PieceStatus}

import java.time.Instant
import org.scalacheck.{Arbitrary, Gen}

/** PR-F F0 — shared ScalaCheck generators for the §17 slice-2 invariant property suites.
  *
  * The generators are biased toward **legal** manifests: every produced `Manifest` already passes `Manifest.validate`,
  * and the §5.5 merged-prefix invariant is respected by construction. Illegal inputs (duplicate piece ids,
  * status/baseSha mismatches, broken prefix) are tested separately in named negative-path suites; the property suites
  * for F1–F13 operate over the legal subset because that's what the FSM is contracted against.
  *
  * Generators here are small (3–8 pieces, short ids) — property tests run with the default `ScalaCheckSuite`
  * configuration (100 samples) and we'd rather get many samples cheap than a few expensive ones.
  */
object Generators:

  // ---------------------------------------------------------------------------
  // Primitive ids and shas
  // ---------------------------------------------------------------------------

  /** A small set of valid FeatureId slugs — generated rather than fixed so two suites in parallel don't collide on file
    * paths when a property runs against a real temp directory.
    */
  val genFeatureId: Gen[FeatureId] =
    for
      letter <- Gen.alphaLowerChar
      tail <- Gen.listOfN(6, Gen.oneOf(('a' to 'z') ++ ('0' to '9') :+ '-')).map(_.mkString)
    yield FeatureId(s"$letter$tail")

  /** Piece ids are always `p<N>`. The N is taken from the position in the manifest by the manifest generators below, so
    * this is only used for one-off generation outside a manifest.
    */
  def pieceIdFor(idx: Int): PieceId = PieceId(s"p${idx + 1}")

  val genPieceId: Gen[PieceId] = Gen.choose(1, 50).map(n => PieceId(s"p$n"))

  /** A SHA-style 40-char hex string. */
  val genSha: Gen[Sha] =
    Gen.listOfN(40, Gen.oneOf(('0' to '9') ++ ('a' to 'f'))).map(cs => Sha(cs.mkString))

  /** PR numbers stay small to keep failure output readable. */
  val genPrNumber: Gen[PrNumber] = Gen.choose(1, 10000).map(PrNumber(_))

  /** Instants in a narrow band around a fixed epoch so equality assertions are stable. */
  private val Epoch: Instant = Instant.parse("2026-05-26T12:00:00Z")
  val genInstant: Gen[Instant] = Gen.choose(0L, 86400L).map(Epoch.plusSeconds)

  given Arbitrary[FeatureId] = Arbitrary(genFeatureId)
  given Arbitrary[PieceId] = Arbitrary(genPieceId)
  given Arbitrary[Sha] = Arbitrary(genSha)
  given Arbitrary[PrNumber] = Arbitrary(genPrNumber)

  // ---------------------------------------------------------------------------
  // Pieces and manifests
  // ---------------------------------------------------------------------------

  /** Build a pending piece with id = `p<idx+1>` and order = `idx + 1`. */
  def pendingPiece(idx: Int): Piece =
    Piece(
      id = pieceIdFor(idx),
      order = idx + 1,
      title = s"Piece p${idx + 1}",
      summary = s"summary p${idx + 1}",
      specPath = s".forge/specs/feature/pieces/p${idx + 1}.md",
      acceptanceHash = "sha256:" + ("0" * 64),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  /** Build a piece that already has `status = Merged` plus the §5.1 required fields. */
  def mergedPiece(idx: Int, prNumber: PrNumber, mergeCommit: Sha, mergedAt: Instant): Piece =
    pendingPiece(idx).copy(
      status = PieceStatus.Merged,
      baseSha = Some(mergeCommit), // any 40-char SHA satisfies the in_progress baseSha invariant
      prNumber = Some(prNumber),
      mergeCommit = Some(mergeCommit),
      mergedAt = Some(mergedAt)
    )

  /** Build a piece with `status = InProgress` (used by F12 to test the §5.1 baseSha invariant). */
  def inProgressPiece(idx: Int, baseSha: Sha, prNumber: Option[PrNumber] = None, attempts: Int = 0): Piece =
    pendingPiece(idx).copy(
      status = PieceStatus.InProgress,
      baseSha = Some(baseSha),
      prNumber = prNumber,
      attempts = attempts
    )

  /** A legal manifest with `pieceCount` pieces. `mergedPrefix` of them are merged (a contiguous prefix per §5.5); the
    * rest are pending. Useful directly for F4/F10/F11/F12.
    */
  def manifestWith(
      featureId: FeatureId,
      pieceCount: Int,
      mergedPrefix: Int,
      prNumberBase: Int = 1000
  ): Manifest =
    require(pieceCount >= 1, "pieceCount must be ≥ 1")
    require(mergedPrefix >= 0 && mergedPrefix <= pieceCount, "mergedPrefix out of range")
    val pieces = (0 until pieceCount).toVector.map: i =>
      if i < mergedPrefix then
        mergedPiece(
          idx = i,
          prNumber = PrNumber(prNumberBase + i),
          mergeCommit = Sha(("a" + ("0" * 39)).updated(1, ('0' + (i % 10)).toChar)),
          mergedAt = Epoch.plusSeconds(i.toLong)
        )
      else pendingPiece(i)
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "Test feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = pieces
    )

  /** Generator over legal manifests with 1–6 pieces and a (possibly empty) merged prefix. */
  val genManifest: Gen[Manifest] =
    for
      featureId <- genFeatureId
      pieceCount <- Gen.choose(1, 6)
      mergedPrefix <- Gen.choose(0, pieceCount)
    yield manifestWith(featureId, pieceCount, mergedPrefix)

  given Arbitrary[Manifest] = Arbitrary(genManifest)

  /** Build a fresh `Feature` (initial state, no sessions) from a manifest. */
  def initialFeature(manifest: Manifest): Feature =
    Feature(
      id = manifest.featureId,
      manifest = manifest,
      state = FsmState.Drafting,
      cost = CostTotals.zero,
      designSessionId = None,
      currentPieceSessionId = None,
      branchProtectionCacheEpoch = 0L,
      designPrFeedbackRound = 0
    )

  /** Generator over initial features. Use [[FsmTrajectory]] (this object) to drive them through legal state sequences.
    */
  val genInitialFeature: Gen[Feature] = genManifest.map(initialFeature)

  given Arbitrary[Feature] = Arbitrary(genInitialFeature)

  // ---------------------------------------------------------------------------
  // FsmState (one for each variant; biased so each variant gets sampled)
  // ---------------------------------------------------------------------------

  val genFsmState: Gen[FsmState] =
    val singletons: Gen[FsmState] = Gen.oneOf(
      FsmState.Drafting,
      FsmState.InteractiveSpec,
      FsmState.DesignReady,
      FsmState.FeatureDone
    )
    val parametric: Gen[FsmState] = Gen.oneOf(
      Gen.choose(1, 5).map(FsmState.DesignReviewing(_)),
      Gen.choose(1, 5).map(r => FsmState.DesignNeedsHumanInput(r, Vector.empty)),
      genPrNumber.map(FsmState.DesignAwaitingMerge(_)),
      for pr <- genPrNumber; r <- Gen.choose(1, 5) yield FsmState.DesignPrFeedback(pr, r),
      genPieceId.map(FsmState.PieceImplementing(_)),
      for p <- genPieceId; pr <- genPrNumber yield FsmState.PieceAwaitingCi(p, pr),
      for p <- genPieceId; pr <- genPrNumber yield FsmState.PieceAwaitingReview(p, pr),
      for p <- genPieceId; pr <- genPrNumber; a <- Gen.choose(0, 5) yield FsmState.PieceCiFailed(p, pr, a),
      for p <- genPieceId; pr <- genPrNumber; a <- Gen.choose(0, 5) yield FsmState.PieceReviewFailed(p, pr, a),
      for p <- genPieceId; pr <- genPrNumber; a <- Gen.choose(0, 5) yield FsmState.PieceFixingUp(p, pr, a),
      for p <- genPieceId; pr <- genPrNumber yield FsmState.PieceAwaitingMerge(p, pr),
      for p <- genPieceId; pr <- genPrNumber; t <- genInstant yield FsmState.Refining(p, pr, t)
    )
    Gen.oneOf(singletons, parametric)

  given Arbitrary[FsmState] = Arbitrary(genFsmState)

  // ---------------------------------------------------------------------------
  // ManifestPatchOp (for F10 / F11)
  // ---------------------------------------------------------------------------

  /** Generate an `AddPiece` op against the current manifest — uses a fresh piece id not already present, and an `after`
    * pointer either pointing at the last merged piece (legal) or at a pending piece (legal).
    */
  def genAddPieceOp(m: Manifest): Gen[ManifestPatchOp.AddPiece] =
    val takenIds = m.pieces.map(_.id.value).toSet
    val freshIdx = Iterator.from(m.pieces.size).find(i => !takenIds.contains(s"p${i + 1}")).get
    val newPiece = pendingPiece(freshIdx)
    val mergedLast = m.pieces.takeWhile(_.status == PieceStatus.Merged).lastOption.map(_.id)
    val candidatesForAfter: Vector[Option[PieceId]] =
      mergedLast.toVector.map(Some(_)) ++ m.pieces.filter(_.status == PieceStatus.Pending).map(p => Some(p.id))
    val afterGen: Gen[Option[PieceId]] =
      if candidatesForAfter.nonEmpty then Gen.oneOf(candidatesForAfter)
      else Gen.const(None)
    afterGen.map(after => ManifestPatchOp.AddPiece(after = after, piece = newPiece))

  /** Generate a `RemovePiece` op targeting any piece in the manifest — including merged ones (the §11 contract requires
    * those to be rejected, which is what F10 asserts).
    */
  def genRemovePieceOp(m: Manifest): Gen[ManifestPatchOp.RemovePiece] =
    if m.pieces.nonEmpty then Gen.oneOf(m.pieces.map(_.id)).map(ManifestPatchOp.RemovePiece(_))
    else Gen.const(ManifestPatchOp.RemovePiece(PieceId("p1")))

  /** Generate an `EditPiece` op against any piece in the manifest. */
  def genEditPieceOp(m: Manifest): Gen[ManifestPatchOp.EditPiece] =
    val idGen: Gen[PieceId] =
      if m.pieces.nonEmpty then Gen.oneOf(m.pieces.map(_.id)) else Gen.const(PieceId("p1"))
    for
      id <- idGen
      title <- Gen.option(Gen.alphaNumStr.suchThat(_.nonEmpty).map(s => s"title-$s"))
    yield ManifestPatchOp.EditPiece(id = id, title = title, summary = None, specPath = None, acceptanceHash = None)

  /** Generate a `ReorderPieces` op. Only sometimes legal (must preserve the merged prefix); F11 asserts the rule. */
  def genReorderOp(m: Manifest): Gen[ManifestPatchOp.ReorderPieces] =
    val mergedCount = m.pieces.count(_.status == PieceStatus.Merged)
    val (mergedIds, pendingIds) = m.pieces.map(_.id).splitAt(mergedCount)
    val reorderedPending: Gen[Vector[PieceId]] =
      if pendingIds.isEmpty then Gen.const(Vector.empty)
      else Gen.const(pendingIds.reverse) // a deterministic non-identity permutation when ≥ 2 pending
    reorderedPending.map(p => ManifestPatchOp.ReorderPieces(mergedIds ++ p))

  /** Generate an arbitrary `ManifestPatchOp` against the current manifest. Includes mixes of legal and illegal ops.
    */
  def genPatchOp(m: Manifest): Gen[ManifestPatchOp] =
    Gen.oneOf(genAddPieceOp(m), genRemovePieceOp(m), genEditPieceOp(m), genReorderOp(m))

  /** Generate a `ManifestPatch` of 1–3 ops. Each op is sampled against the *initial* manifest — the patch may produce
    * an op-local error or a validate-time error, which the property assertions test for.
    */
  def genManifestPatch(m: Manifest): Gen[ManifestPatch] =
    for
      count <- Gen.choose(1, 3)
      ops <- Gen.listOfN(count, genPatchOp(m))
    yield ManifestPatch(reason = "property-test patch", ops = ops.toVector)
