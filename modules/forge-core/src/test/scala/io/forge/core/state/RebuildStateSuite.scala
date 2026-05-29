package io.forge.core.state

import cats.effect.unsafe.implicits.global
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmFixtures, FsmState}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.{FileActionLog, FoldResult, ObservedTransition, Replay}
import io.forge.core.manifest.{FileManifestStore, Manifest}
import io.forge.core.paths.ForgePaths

import java.time.Instant
import upickle.default.writeJs

/** PR-E E4 — exercises [[RebuildState.reconcile]] (pure rule) and [[RebuildState.run]] (full pipeline).
  *
  * `reconcile` is the heart of the section: it classifies merged pieces into the four §11.5 sub-cases and either
  * synthesizes repair drafts (case (b)) or applies a synthetic `Merged` event through the FSM (case (c)). The unit
  * cases here cover each branch; F13 (PR-F) will lift these patterns into property-style fixtures driven from disk.
  */
class RebuildStateSuite extends munit.FunSuite:

  private val ts0 = Instant.parse("2026-05-26T12:00:00Z")
  private def at(n: Int): Instant = ts0.plusSeconds(n.toLong)

  // --- reconcile case (a) — fully recovered ---

  test("reconcile case (a) — transitionLogged && auditLogged → no drafts, feature unchanged"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.Refining(P1, P1Pr, startedAt = MergedAt))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector(
        ObservedTransition(
          from = FsmState.PieceAwaitingMerge(P1, P1Pr),
          to = FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
          piece = Some(P1),
          at = at(0)
        )
      ),
      observedPieceMerges = Set(P1)
    )
    val Right(result) = RebuildState.reconcile(foldResult, manifest): @unchecked
    assertEquals(result.feature, seed)
    assertEquals(result.draftsToAppend, Vector.empty)

  // --- reconcile case (b) — partial-batch ---

  test("reconcile case (b) — transitionLogged && !auditLogged → synthesizes audit + harness drafts at Refining"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.Refining(P1, P1Pr, startedAt = MergedAt))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector(
        ObservedTransition(
          from = FsmState.PieceAwaitingMerge(P1, P1Pr),
          to = FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
          piece = Some(P1),
          at = at(0)
        )
      ),
      observedPieceMerges = Set.empty
    )
    val Right(result) = RebuildState.reconcile(foldResult, manifest): @unchecked
    // Feature unchanged in case (b) — the fold already reached the post-transition state.
    assertEquals(result.feature, seed)
    assertEquals(result.draftsToAppend.size, 2)
    val audit = result.draftsToAppend(0)
    val harness = result.draftsToAppend(1)
    assertEquals(audit.kind, "audit.piece_merged")
    assertEquals(audit.payload("p").str, P1.value)
    assertEquals(audit.payload("prNumber").num.toInt, P1Pr.value)
    assertEquals(harness.kind, "harness.crash_recovered")
    assertEquals(harness.payload("reason").str, "partial_batch_missing_audit")
    assertEquals(harness.payload("piece").str, P1.value)

  test("reconcile case (b) — fold-state can be a forward state (e.g. PieceImplementing(next))"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)))
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.PieceImplementing(P2))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector(
        ObservedTransition(
          from = FsmState.PieceAwaitingMerge(P1, P1Pr),
          to = FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
          piece = Some(P1),
          at = at(0)
        ),
        ObservedTransition(
          from = FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
          to = FsmState.PieceImplementing(P2),
          piece = Some(P1),
          at = at(1)
        )
      ),
      observedPieceMerges = Set.empty // audit missing
    )
    val Right(result) = RebuildState.reconcile(foldResult, manifest): @unchecked
    // Case (b) does not synthesize FSM transitions — the feature state is whatever the fold reached.
    assertEquals(result.feature.state, FsmState.PieceImplementing(P2): FsmState)
    assertEquals(result.draftsToAppend.map(_.kind), Vector("audit.piece_merged", "harness.crash_recovered"))

  // --- reconcile case (c) — §11.5 crash window proper ---

  test("reconcile case (c) — no transition + no audit + fold-state=PieceAwaitingMerge → synthetic Merged"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.PieceAwaitingMerge(P1, P1Pr))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector.empty,
      observedPieceMerges = Set.empty
    )
    val Right(result) = RebuildState.reconcile(foldResult, manifest): @unchecked
    // The synthetic Merged transitions to Refining(piece, prNumber, observedAt = mergedAt).
    result.feature.state match
      case FsmState.Refining(p, pr, startedAt) =>
        assertEquals(p, P1)
        assertEquals(pr, P1Pr)
        assertEquals(startedAt, MergedAt)
      case other => fail(s"expected Refining, got $other")
    // The drafts should include fsm.transition + audit.piece_merged + harness.crash_recovered (synthetic_merged reason).
    assertEquals(
      result.draftsToAppend.map(_.kind),
      Vector("fsm.transition", "audit.piece_merged", "harness.crash_recovered")
    )
    val harness = result.draftsToAppend(2)
    assertEquals(harness.payload("reason").str, "crash_window_synthetic_merged")

  test("reconcile case (c) bad fold-state — fold ended at PieceAwaitingReview → InconsistentRecovery"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.PieceAwaitingReview(P1, P1Pr))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector.empty,
      observedPieceMerges = Set.empty
    )
    val Left(err) = RebuildState.reconcile(foldResult, manifest): @unchecked
    err match
      case RebuildError.InconsistentRecovery(reason) => assert(reason.contains("PieceAwaitingMerge"))
      case other => fail(s"expected InconsistentRecovery, got $other")

  // --- reconcile case (d) — audit without transition is structurally impossible ---

  test("reconcile case (d) — audit without transition → InconsistentRecovery"):
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    val seed = Feature.initial(FeatureA, manifest)
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector.empty,
      observedPieceMerges = Set(P1) // audit logged without the paired transition
    )
    val Left(err) = RebuildState.reconcile(foldResult, manifest): @unchecked
    err match
      case RebuildError.InconsistentRecovery(reason) => assert(reason.contains("missing"))
      case other => fail(s"expected InconsistentRecovery, got $other")

  // --- multi-piece divergence ---

  test("reconcile — two pieces in case (c) → InconsistentRecovery(multi-piece partial merge)"):
    val manifest = FsmFixtures.manifest(
      Vector(pieceMerged(P1, 1, P1Pr), pieceMerged(P2, 2, P2Pr))
    )
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.PieceAwaitingMerge(P2, P2Pr))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector.empty,
      observedPieceMerges = Set.empty
    )
    val Left(err) = RebuildState.reconcile(foldResult, manifest): @unchecked
    err match
      case RebuildError.InconsistentRecovery(reason) => assert(reason.contains("multi-piece"))
      case other => fail(s"expected multi-piece InconsistentRecovery, got $other")

  test("reconcile — mixed case (a) + case (b) across two pieces composes cleanly"):
    val manifest = FsmFixtures.manifest(
      Vector(pieceMerged(P1, 1, P1Pr), pieceMerged(P2, 2, P2Pr), piecePending(P3, 3))
    )
    val seed = Feature
      .initial(FeatureA, manifest)
      .copy(state = FsmState.PieceImplementing(P3))
    val foldResult = FoldResult(
      feature = seed,
      observedTransitions = Vector(
        ObservedTransition(
          from = FsmState.PieceAwaitingMerge(P1, P1Pr),
          to = FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
          piece = Some(P1),
          at = at(0)
        ),
        ObservedTransition(
          from = FsmState.PieceAwaitingMerge(P2, P2Pr),
          to = FsmState.Refining(P2, P2Pr, startedAt = MergedAt),
          piece = Some(P2),
          at = at(1)
        )
      ),
      observedPieceMerges = Set(P1) // P2's audit missing → case (b) for P2
    )
    val Right(result) = RebuildState.reconcile(foldResult, manifest): @unchecked
    // Only P2 triggers repair drafts (P1 is fully recovered, case (a)).
    assertEquals(result.draftsToAppend.size, 2)
    assertEquals(result.draftsToAppend.head.kind, "audit.piece_merged")
    assertEquals(result.draftsToAppend.head.payload("p").str, P2.value)
    assertEquals(result.feature.state, FsmState.PieceImplementing(P3): FsmState)

  // --- run pipeline end-to-end ---

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-rebuild-state-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  tempFixture.test("run end-to-end — clean manifest + empty log → Right(initial Feature) + cache written"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifestStore = new FileManifestStore(paths)
    val manifest = FsmFixtures.manifest(Vector(piecePending(P1, 1)))
    seedManifest(paths, manifest)

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Right(RebuildState.RebuildResult(f, inFlight)) =>
        assertEquals(f, Feature.initial(FeatureA, manifest))
        assertEquals(inFlight, Vector.empty) // Drafting carries no driver session
      case Left(err) => fail(s"expected Right, got Left($err)")
    val cached = cache.load(FeatureA).unsafeRunSync()
    assertEquals(cached.map(_.state), Some(FsmState.Drafting: FsmState))

  tempFixture.test("run end-to-end — missing manifest → Left(ManifestLoadFailed)"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifestStore = new FileManifestStore(paths)
    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Left(RebuildError.ManifestLoadFailed(id, _)) => assertEquals(id, FeatureA)
      case other => fail(s"expected ManifestLoadFailed, got $other")
    // Cache should NOT have been written on a manifest failure.
    assertEquals(cache.load(FeatureA).unsafeRunSync(), None)

  tempFixture.test("run end-to-end — log with case (c) crash window → repair drafts written + cache updated"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifestStore = new FileManifestStore(paths)
    // Manifest says P1 is merged.
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    seedManifest(paths, manifest)
    // Seed log with the trail up to PieceAwaitingMerge but NOT the Refining transition nor the audit. This is the
    // §11.5 crash window: manifest committed, FSM transition + audit never made it to disk.
    val initialFeature = Feature.initial(FeatureA, manifest)
    seedLogReachingPieceAwaitingMerge(paths, log, initialFeature)

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Right(RebuildState.RebuildResult(f, inFlight)) =>
        f.state match
          case FsmState.Refining(p, pr, _) =>
            assertEquals(p, P1)
            assertEquals(pr, P1Pr)
          case other => fail(s"expected Refining, got $other")
        assertEquals(inFlight, Vector.empty) // recovered to Refining — no live driver
      case Left(err) => fail(s"expected Right, got Left($err)")
    // The log should have gained the fsm.transition + audit.piece_merged + harness.crash_recovered repair entries.
    val replayed = log.replay(FeatureA).unsafeRunSync()
    val kinds = replayed.map(_.kind)
    assert(
      kinds.containsSlice(Vector("fsm.transition", "audit.piece_merged", "harness.crash_recovered")),
      s"expected repair drafts at log tail, got $kinds"
    )
    // Cache reflects the repaired state.
    val cached = cache.load(FeatureA).unsafeRunSync()
    cached.flatMap(c => Option(c.state)) match
      case Some(FsmState.Refining(_, _, _)) => ()
      case other => fail(s"cache should reflect Refining, got $other")

  tempFixture.test("run end-to-end — case (c) bad fold-state → Left(InconsistentRecovery) + cache untouched"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val cache = new FileStateCache(paths)
    val log = FileActionLog(paths).unsafeRunSync()
    val manifestStore = new FileManifestStore(paths)
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    seedManifest(paths, manifest)
    // Seed log so fold-state ends at PieceAwaitingReview (NOT PieceAwaitingMerge) — invalid for case (c).
    seedLogReachingPieceAwaitingReview(paths, log, Feature.initial(FeatureA, manifest))

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Left(RebuildError.InconsistentRecovery(reason)) => assert(reason.contains("PieceAwaitingMerge"))
      case other => fail(s"expected InconsistentRecovery, got $other")
    // Cache was not written.
    assertEquals(cache.load(FeatureA).unsafeRunSync(), None)

  // --- helpers ---

  private def seedManifest(paths: ForgePaths, manifest: Manifest): Unit =
    val path = paths.manifest(FeatureA)
    os.makeDir.all(path / os.up)
    os.write(path, Manifest.toJson(manifest))

  /** Seed a log whose fold-state is PieceAwaitingMerge(P1, P1Pr). The manifest passed in already has P1 status=Merged
    * for the case-(c) recovery test; the initial feature seed walks through the pre-merge states by emitting
    * `fsm.transition` lines onto the canonical action log.
    *
    * Note: `Fsm.transition` would refuse to drive a feature whose manifest has piece P1 as merged into
    * `BranchCreated(P1)` (the piece is no longer pending), so we synthesise the transitions directly via the same
    * payload shape `Fsm.fsmTransitionDraft` emits. The fold then walks them back via `Replay.foldEvents`.
    */
  private def seedLogReachingPieceAwaitingMerge(paths: ForgePaths, log: FileActionLog, initial: Feature): Unit =
    val transitions = Vector[(FsmState, FsmState)](
      FsmState.Drafting -> FsmState.InteractiveSpec,
      FsmState.InteractiveSpec -> FsmState.DesignReviewing(1),
      FsmState.DesignReviewing(1) -> FsmState.DesignReady,
      FsmState.DesignReady -> FsmState.PieceImplementing(P1),
      FsmState.PieceImplementing(P1) -> FsmState.PieceAwaitingCi(P1, P1Pr),
      FsmState.PieceAwaitingCi(P1, P1Pr) -> FsmState.PieceAwaitingReview(P1, P1Pr),
      FsmState.PieceAwaitingReview(P1, P1Pr) -> FsmState.PieceAwaitingMerge(P1, P1Pr)
    )
    val drafts = transitions.map { case (from, to) =>
      io.forge.core.log.ActionDraft(
        feature = initial.id,
        piece = pieceOf(to),
        actor = None,
        role = None,
        kind = "fsm.transition",
        payload = ujson.Obj("from" -> writeJs[FsmState](from), "to" -> writeJs[FsmState](to))
      )
    }
    val _ = log.appendAll(initial.id, drafts).unsafeRunSync()
    // Sanity: the freshly-written log should fold back to PieceAwaitingMerge.
    val actions = log.replay(initial.id).unsafeRunSync()
    val Right(foldResult) = Replay.foldEvents(initial, actions): @unchecked
    assert(
      foldResult.feature.state == FsmState.PieceAwaitingMerge(P1, P1Pr),
      s"fixture seeded wrong fold-state: ${foldResult.feature.state}"
    )

  /** Seed a log whose fold-state is PieceAwaitingReview — used for the case-(c) bad-fold-state test. */
  private def seedLogReachingPieceAwaitingReview(paths: ForgePaths, log: FileActionLog, initial: Feature): Unit =
    val transitions = Vector[(FsmState, FsmState)](
      FsmState.Drafting -> FsmState.InteractiveSpec,
      FsmState.InteractiveSpec -> FsmState.DesignReviewing(1),
      FsmState.DesignReviewing(1) -> FsmState.DesignReady,
      FsmState.DesignReady -> FsmState.PieceImplementing(P1),
      FsmState.PieceImplementing(P1) -> FsmState.PieceAwaitingCi(P1, P1Pr),
      FsmState.PieceAwaitingCi(P1, P1Pr) -> FsmState.PieceAwaitingReview(P1, P1Pr)
    )
    val drafts = transitions.map { case (from, to) =>
      io.forge.core.log.ActionDraft(
        feature = initial.id,
        piece = pieceOf(to),
        actor = None,
        role = None,
        kind = "fsm.transition",
        payload = ujson.Obj("from" -> writeJs[FsmState](from), "to" -> writeJs[FsmState](to))
      )
    }
    val _ = log.appendAll(initial.id, drafts).unsafeRunSync()

  private def pieceOf(state: FsmState): Option[PieceId] = state match
    case FsmState.PieceImplementing(p) => Some(p)
    case FsmState.PieceAwaitingCi(p, _) => Some(p)
    case FsmState.PieceAwaitingReview(p, _) => Some(p)
    case FsmState.PieceCiFailed(p, _, _) => Some(p)
    case FsmState.PieceReviewFailed(p, _, _) => Some(p)
    case FsmState.PieceFixingUp(p, _, _) => Some(p)
    case FsmState.PieceAwaitingMerge(p, _) => Some(p)
    case FsmState.Refining(p, _, _) => Some(p)
    case _ => None
