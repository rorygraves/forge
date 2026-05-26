package io.forge.core.log

import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.*
import io.forge.core.fsm.FsmFixtures.*

import java.time.Instant
import upickle.default.writeJs

/** PR-D D4 — replay onto an initial `Feature` projects state, session ids, cost totals, observed transitions, and
  * observed piece merges per §6.1 / §19. Negative paths surface `ReplayError`.
  */
class FeatureFoldEventsSuite extends munit.FunSuite:

  private val ts0 = Instant.parse("2026-05-26T12:00:00Z")
  private def at(n: Int): Instant = ts0.plusSeconds(n.toLong)

  // --- fsm.transition projection ---

  private def fsmTransitionAction(seq: Long, from: FsmState, to: FsmState, piece: Option[PieceId] = None): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = piece,
      actor = None,
      role = None,
      kind = "fsm.transition",
      payload = ujson.Obj("from" -> writeJs[FsmState](from), "to" -> writeJs[FsmState](to))
    )

  test("foldEvents — single fsm.transition advances state and records observation"):
    val seed =
      Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1), piecePending(P2, 2))))
    val log = Vector(
      fsmTransitionAction(0L, FsmState.Drafting, FsmState.InteractiveSpec)
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.InteractiveSpec)
    assertEquals(result.observedTransitions.size, 1)
    assertEquals(result.observedTransitions.head.from, FsmState.Drafting: FsmState)
    assertEquals(result.observedTransitions.head.to, FsmState.InteractiveSpec: FsmState)
    assertEquals(result.observedPieceMerges, Set.empty[PieceId])

  test("foldEvents — chain of transitions threads through Drafting → InteractiveSpec → DesignReviewing(1)"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      fsmTransitionAction(0L, FsmState.Drafting, FsmState.InteractiveSpec),
      fsmTransitionAction(1L, FsmState.InteractiveSpec, FsmState.DesignReviewing(1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignReviewing(1): FsmState)
    assertEquals(result.observedTransitions.size, 2)

  test("foldEvents — TransitionFromMismatch when payload.from disagrees with running state"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      fsmTransitionAction(0L, FsmState.InteractiveSpec, FsmState.DesignReviewing(1))
    )
    val Left(err) = Feature.foldEvents(seed, log): @unchecked
    err match
      case ReplayError.TransitionFromMismatch(0L, FsmState.InteractiveSpec, FsmState.Drafting) => ()
      case other => fail(s"expected TransitionFromMismatch, got $other")

  test("foldEvents — preserves parameterized states (PieceAwaitingMerge → Refining)"):
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(state = FsmState.PieceAwaitingMerge(P1, P1Pr))
    val refining = FsmState.Refining(P1, P1Pr, startedAt = at(2))
    val log = Vector(
      fsmTransitionAction(0L, FsmState.PieceAwaitingMerge(P1, P1Pr), refining, piece = Some(P1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, refining: FsmState)
    assertEquals(result.observedTransitions.head.piece, Some(P1))
    assertEquals(
      result.observedTransitions.head.from,
      FsmState.PieceAwaitingMerge(P1, P1Pr): FsmState
    )
    assertEquals(result.observedTransitions.head.to, refining: FsmState)

  // --- session-id projection ---

  private def spawnAction(
      seq: Long,
      actor: String,
      sessionId: String,
      piece: Option[PieceId]
  ): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = piece,
      actor = Some(actor),
      role = Some("driver"),
      kind = s"$actor.spawn",
      payload = ujson.Obj("sessionId" -> ujson.Str(sessionId), "role" -> ujson.Str("driver"))
    )

  private def resumeAction(
      seq: Long,
      actor: String,
      oldSid: String,
      newSid: String,
      piece: Option[PieceId]
  ): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = piece,
      actor = Some(actor),
      role = Some("driver"),
      kind = s"$actor.resume",
      payload = ujson.Obj("oldSessionId" -> ujson.Str(oldSid), "newSessionId" -> ujson.Str(newSid))
    )

  test("foldEvents — claude.spawn with piece=None projects designSessionId"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val Right(result) = Feature.foldEvents(seed, Vector(spawnAction(0, "claude", "sess-d1", None))): @unchecked
    assertEquals(result.feature.designSessionId, Some("sess-d1"))
    assertEquals(result.feature.currentPieceSessionId, None)

  test("foldEvents — claude.spawn with piece set projects currentPieceSessionId"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val Right(result) = Feature.foldEvents(seed, Vector(spawnAction(0, "claude", "sess-p1", Some(P1)))): @unchecked
    assertEquals(result.feature.designSessionId, None)
    assertEquals(result.feature.currentPieceSessionId, Some("sess-p1"))

  test("foldEvents — claude.resume updates from newSessionId (idempotent under pinned CLIs)"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      spawnAction(0, "claude", "sess-d1", None),
      resumeAction(1, "claude", "sess-d1", "sess-d1", None)
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.designSessionId, Some("sess-d1"))

  test("foldEvents — claude.resume that references an unspawned session id fails ResumeWithoutSpawn"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      resumeAction(0, "claude", "ghost-id", "ghost-id-2", None)
    )
    val Left(err) = Feature.foldEvents(seed, log): @unchecked
    err match
      case ReplayError.ResumeWithoutSpawn(0L, "claude", "ghost-id") => ()
      case other => fail(s"expected ResumeWithoutSpawn, got $other")

  test("foldEvents — codex.resume with a different newSessionId rebinds projection"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      spawnAction(0, "codex", "sess-old", None),
      resumeAction(1, "codex", "sess-old", "sess-new", None)
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.designSessionId, Some("sess-new"))

  // --- cost.update projection ---

  private def costUpdateAction(
      seq: Long,
      featureTotal: Double,
      pieceTotal: Double,
      turnTotal: Double
  ): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = None,
      actor = Some("claude"),
      role = Some("driver"),
      kind = "cost.update",
      payload = ujson.Obj(
        "provider" -> ujson.Str("claude"),
        "model" -> ujson.Str("claude-opus-4-7"),
        "inputTokens" -> ujson.Num(1024),
        "outputTokens" -> ujson.Num(256),
        "usd" -> ujson.Num(0.42),
        "featureTotalUsd" -> ujson.Num(featureTotal),
        "pieceTotalUsd" -> ujson.Num(pieceTotal),
        "turnTotalUsd" -> ujson.Num(turnTotal)
      )
    )

  test("foldEvents — cost.update projects feature/piece/turn totals (last-write-wins)"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      costUpdateAction(0, 0.42, 0.42, 0.42),
      costUpdateAction(1, 1.04, 0.62, 0.20)
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.cost, CostTotals(BigDecimal(1.04), BigDecimal(0.62), BigDecimal(0.20)))

  // --- audit.piece_merged ---

  private def auditMergedAction(seq: Long, piece: PieceId, pr: PrNumber): Action =
    Action(
      seq = seq,
      at = at(seq.toInt),
      feature = FeatureA,
      piece = Some(piece),
      actor = None,
      role = None,
      kind = "audit.piece_merged",
      payload = ujson.Obj(
        "p" -> ujson.Str(piece.value),
        "prNumber" -> ujson.Num(pr.value.toDouble),
        "mergeCommit" -> ujson.Str("a" * 40),
        "mergedAt" -> ujson.Str(MergedAt.toString)
      )
    )

  test("foldEvents — audit.piece_merged with matching prNumber records the piece in observedPieceMerges"):
    val seed = Feature.initial(
      FeatureA,
      FsmFixtures.manifest(Vector(pieceMerged(P1, 1, prNumber = P1Pr), piecePending(P2, 2)))
    )
    val Right(result) = Feature.foldEvents(seed, Vector(auditMergedAction(0L, P1, P1Pr))): @unchecked
    assertEquals(result.observedPieceMerges, Set(P1))

  test("foldEvents — audit.piece_merged with mismatched prNumber raises AuditPrNumberMismatch"):
    val seed = Feature.initial(
      FeatureA,
      FsmFixtures.manifest(Vector(pieceMerged(P1, 1, prNumber = P1Pr), piecePending(P2, 2)))
    )
    val wrongPr = PrNumber(9999)
    val Left(err) = Feature.foldEvents(seed, Vector(auditMergedAction(0L, P1, wrongPr))): @unchecked
    err match
      case ReplayError.AuditPrNumberMismatch(0L, P1, l, Some(m)) =>
        assertEquals(l, wrongPr)
        assertEquals(m, P1Pr)
      case other => fail(s"expected AuditPrNumberMismatch, got $other")

  // --- harness.error log_truncated is a no-op projection ---

  test("foldEvents — harness.error log_truncated is a no-op (does not affect any projection)"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val truncated = Action(
      seq = 5L,
      at = at(5),
      feature = FeatureA,
      piece = None,
      actor = None,
      role = None,
      kind = "harness.error",
      payload = ujson.Obj("kind" -> ujson.Str("log_truncated"), "droppedBytes" -> ujson.Num(73))
    )
    val log = Vector(
      fsmTransitionAction(0L, FsmState.Drafting, FsmState.InteractiveSpec),
      truncated,
      fsmTransitionAction(6L, FsmState.InteractiveSpec, FsmState.DesignReviewing(1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignReviewing(1): FsmState)
    // The truncated marker is NOT added to observedTransitions.
    assertEquals(result.observedTransitions.size, 2)

  // --- monotonic seq ---

  test("foldEvents — NonMonotonicSeq when seq does not strictly increase"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      fsmTransitionAction(5L, FsmState.Drafting, FsmState.InteractiveSpec),
      fsmTransitionAction(5L, FsmState.InteractiveSpec, FsmState.DesignReviewing(1))
    )
    val Left(err) = Feature.foldEvents(seed, log): @unchecked
    err match
      case ReplayError.NonMonotonicSeq(5L, 5L) => ()
      case other => fail(s"expected NonMonotonicSeq, got $other")

  // --- empty log ---

  test("foldEvents — empty log returns seed feature with empty observed sets"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val Right(result) = Feature.foldEvents(seed, Vector.empty): @unchecked
    assertEquals(result.feature, seed)
    assertEquals(result.observedTransitions, Vector.empty[ObservedTransition])
    assertEquals(result.observedPieceMerges, Set.empty[PieceId])

  // ---------------------------------------------------------------------------
  // Review-feedback regressions — §6.1 lifecycle projections on fsm.transition.
  // ---------------------------------------------------------------------------

  test("foldEvents — per-actor session tracking: claude.spawn does not satisfy codex.resume"):
    val seed = Feature.initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
    val log = Vector(
      spawnAction(0, "claude", "sess-1", None),
      resumeAction(1, "codex", "sess-1", "sess-2", None)
    )
    val Left(err) = Feature.foldEvents(seed, log): @unchecked
    err match
      case ReplayError.ResumeWithoutSpawn(1L, "codex", "sess-1") => ()
      case other => fail(s"expected per-actor ResumeWithoutSpawn for codex, got $other")

  test("foldEvents — DesignAwaitingMerge → DesignReady clears designSessionId and resets designPrFeedbackRound"):
    val pieces = Vector(piecePending(P1, 1))
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(pieces))
      .copy(
        state = FsmState.DesignAwaitingMerge(DesignPr),
        designSessionId = Some("sess-d1"),
        designPrFeedbackRound = 3
      )
    val log = Vector(
      fsmTransitionAction(0L, FsmState.DesignAwaitingMerge(DesignPr), FsmState.DesignReady)
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignReady: FsmState)
    assertEquals(result.feature.designSessionId, None)
    assertEquals(result.feature.designPrFeedbackRound, 0)

  test("foldEvents — DesignPrFeedback(_, round) → DesignAwaitingMerge bumps designPrFeedbackRound to round"):
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(state = FsmState.DesignPrFeedback(DesignPr, round = 3), designSessionId = Some("sess-d1"))
    val log = Vector(
      fsmTransitionAction(0L, FsmState.DesignPrFeedback(DesignPr, 3), FsmState.DesignAwaitingMerge(DesignPr))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignAwaitingMerge(DesignPr): FsmState)
    assertEquals(result.feature.designPrFeedbackRound, 3)
    // designSessionId is preserved through the round-trip back to DesignAwaitingMerge (cleared only on DesignReady).
    assertEquals(result.feature.designSessionId, Some("sess-d1"))

  test("foldEvents — Refining → next PieceImplementing clears currentPieceSessionId"):
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(pieceMerged(P1, 1, prNumber = P1Pr), piecePending(P2, 2))))
      .copy(
        state = FsmState.Refining(P1, P1Pr, startedAt = ObservedAt),
        currentPieceSessionId = Some("sess-p1")
      )
    val log = Vector(
      fsmTransitionAction(
        0L,
        FsmState.Refining(P1, P1Pr, startedAt = ObservedAt),
        FsmState.PieceImplementing(P2),
        piece = Some(P1)
      )
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.PieceImplementing(P2): FsmState)
    assertEquals(result.feature.currentPieceSessionId, None)

  test("foldEvents — Refining → FeatureDone clears currentPieceSessionId"):
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(pieceMerged(P1, 1, prNumber = P1Pr))))
      .copy(
        state = FsmState.Refining(P1, P1Pr, startedAt = ObservedAt),
        currentPieceSessionId = Some("sess-p1")
      )
    val log = Vector(
      fsmTransitionAction(
        0L,
        FsmState.Refining(P1, P1Pr, startedAt = ObservedAt),
        FsmState.FeatureDone,
        piece = Some(P1)
      )
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.FeatureDone: FsmState)
    assertEquals(result.feature.currentPieceSessionId, None)

  test("foldEvents — Piece* → NHI clears currentPieceSessionId"):
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(
        state = FsmState.PieceImplementing(P1),
        currentPieceSessionId = Some("sess-p1")
      )
    val nhi = FsmState.NeedsHumanIntervention(
      reason = "implement settle timeout",
      resumeHint = ResumeHint.ResolveLocalImplementationChanges(P1, P1Branch)
    )
    val log = Vector(
      fsmTransitionAction(0L, FsmState.PieceImplementing(P1), nhi, piece = Some(P1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, nhi: FsmState)
    assertEquals(result.feature.currentPieceSessionId, None)

  test("foldEvents — NHI → X bumps branchProtectionCacheEpoch on Resume"):
    val nhi = FsmState.NeedsHumanIntervention(
      reason = "spec settle timeout",
      resumeHint = ResumeHint.AbortOrAbandon
    )
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(state = nhi, branchProtectionCacheEpoch = 4L)
    val log = Vector(
      fsmTransitionAction(0L, nhi, FsmState.Abandoned("operator aborted via Resume(AbortOrAbandon)"))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.branchProtectionCacheEpoch, 5L)

  test("foldEvents — NHI → DesignReviewing(1) resets designPrFeedbackRound (Resume(ReopenDesign))"):
    val nhi = FsmState.NeedsHumanIntervention(
      reason = "design did not converge",
      resumeHint = ResumeHint.ReopenDesign(Some(DesignPr))
    )
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(
        state = nhi,
        designPrFeedbackRound = 7,
        branchProtectionCacheEpoch = 1L
      )
    val log = Vector(
      fsmTransitionAction(0L, nhi, FsmState.DesignReviewing(round = 1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignReviewing(1): FsmState)
    assertEquals(result.feature.designPrFeedbackRound, 0)
    assertEquals(result.feature.branchProtectionCacheEpoch, 2L)

  test("foldEvents — NHI → PieceCiFailed clears currentPieceSessionId (Resume(RunAnotherFixup))"):
    // currentPieceSessionId was already cleared when entering NHI in writer-side. Replay reproduces that invariant: even
    // if a malformed seed carried a non-None value into NHI, the Resume(RunAnotherFixup) target re-clears.
    val nhi = FsmState.NeedsHumanIntervention(
      reason = "piece p1 fix-up exhausted after CI failure",
      resumeHint = ResumeHint.RunAnotherFixup(P1, P1Pr)
    )
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(pieceInProgress(P1, 1, prNumber = Some(P1Pr), attempts = 3))))
      .copy(state = nhi, currentPieceSessionId = Some("stale-piece-sess"))
    val log = Vector(
      fsmTransitionAction(0L, nhi, FsmState.PieceCiFailed(P1, P1Pr, attempt = 3), piece = Some(P1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.PieceCiFailed(P1, P1Pr, 3): FsmState)
    assertEquals(result.feature.currentPieceSessionId, None)

  test(
    "foldEvents — NHI → PieceImplementing clears currentPieceSessionId (Resume(ResolveLocalImplementationChanges))"
  ):
    val nhi = FsmState.NeedsHumanIntervention(
      reason = "implement settle timeout for piece p1",
      resumeHint = ResumeHint.ResolveLocalImplementationChanges(P1, P1Branch)
    )
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(Vector(piecePending(P1, 1))))
      .copy(state = nhi, currentPieceSessionId = Some("stale-piece-sess"))
    val log = Vector(
      fsmTransitionAction(0L, nhi, FsmState.PieceImplementing(P1), piece = Some(P1))
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.PieceImplementing(P1): FsmState)
    assertEquals(result.feature.currentPieceSessionId, None)

  test("foldEvents — full DesignAwaitingMerge ↔ DesignPrFeedback cycle reaches DesignReady cleanly"):
    val pieces = Vector(piecePending(P1, 1))
    val seed = Feature
      .initial(FeatureA, FsmFixtures.manifest(pieces, designPr = Some(DesignPr)))
      .copy(
        state = FsmState.DesignAwaitingMerge(DesignPr),
        designSessionId = Some("sess-d1"),
        designPrFeedbackRound = 0
      )
    val log = Vector(
      // Round 1: enter feedback, settle, back to AwaitingMerge with round=1.
      fsmTransitionAction(
        0L,
        FsmState.DesignAwaitingMerge(DesignPr),
        FsmState.DesignPrFeedback(DesignPr, round = 1)
      ),
      fsmTransitionAction(
        1L,
        FsmState.DesignPrFeedback(DesignPr, 1),
        FsmState.DesignAwaitingMerge(DesignPr)
      ),
      // Round 2: enter feedback again with round=2 (writer would have computed this from designPrFeedbackRound+1).
      fsmTransitionAction(
        2L,
        FsmState.DesignAwaitingMerge(DesignPr),
        FsmState.DesignPrFeedback(DesignPr, round = 2)
      ),
      fsmTransitionAction(
        3L,
        FsmState.DesignPrFeedback(DesignPr, 2),
        FsmState.DesignAwaitingMerge(DesignPr)
      ),
      // PR merged → DesignReady.
      fsmTransitionAction(
        4L,
        FsmState.DesignAwaitingMerge(DesignPr),
        FsmState.DesignReady
      )
    )
    val Right(result) = Feature.foldEvents(seed, log): @unchecked
    assertEquals(result.feature.state, FsmState.DesignReady: FsmState)
    assertEquals(result.feature.designSessionId, None)
    // The second-round bump leaves the projection at 2 between rounds, then DesignReady resets to 0.
    assertEquals(result.feature.designPrFeedbackRound, 0)
