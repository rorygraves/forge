package io.forge.core.property

import cats.effect.unsafe.implicits.global
import io.forge.core.*
import io.forge.core.fsm.{Feature, FsmFixtures, FsmState}
import io.forge.core.fsm.FsmFixtures.*
import io.forge.core.log.{ActionDraft, FileActionLog}
import io.forge.core.manifest.{FileManifestStore, Manifest}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildError, RebuildState}

import upickle.default.writeJs

/** PR-F F13 — §17 slice-2 invariant 14: atomic merge mutation persists across crash (reader side).
  *
  * Five end-to-end fixtures driving `RebuildState.run` against a real temp directory, exercising the four
  * classification sub-cases (a)–(d) from PR-E E4 plus the multi-piece divergence refusal. The reconcile-level cases are
  * already covered by `RebuildStateSuite`; this suite elevates them to the run-pipeline level so the test exercises
  * `ManifestStore` + `ActionLog` + `StateCache` together with `reconcile`.
  *
  * The five fixtures map to invariant 14's recovery sub-cases:
  *   - F13a: case (c) happy crash window — manifest committed, fsm.transition + audit lost.
  *   - F13b: case (b) partial-batch repair, at Refining (b₁) and at a forward state (b₂).
  *   - F13c: case (c) bad fold-state — fold ended somewhere other than `PieceAwaitingMerge`.
  *   - F13d: case (d) audit-only orphan — structurally impossible under §11.5.
  *   - F13e: multi-piece divergence — two pieces in case (c) → refuse.
  */
class F13AtomicMergeCrashSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-f13-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  // ---------------------------------------------------------------------------
  // F13a — case (c) happy crash window
  // ---------------------------------------------------------------------------

  tempFixture.test("F13a — manifest committed + missing fsm.transition + missing audit → synthetic recovery"): root =>
    val (paths, log, cache, manifestStore) = wire(root)
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    writeManifest(paths, manifest)
    val initial = Feature.initial(FeatureA, manifest)
    appendTransitions(log, initial.id, transitionsToAwaitingMerge(P1, P1Pr))

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Right(f) =>
        f.state match
          case FsmState.Refining(p, pr, startedAt) =>
            assertEquals(p, P1)
            assertEquals(pr, P1Pr)
            // observedAt = mergedAt per B4 (closest historical fact available).
            assertEquals(startedAt, MergedAt)
          case other => fail(s"expected Refining, got $other")
      case Left(err) => fail(s"expected Right, got Left($err)")

    val tail = log.replay(FeatureA).unsafeRunSync().map(_.kind)
    assert(
      tail.containsSlice(Vector("fsm.transition", "audit.piece_merged", "harness.crash_recovered")),
      s"expected repair drafts at tail, got $tail"
    )

  // ---------------------------------------------------------------------------
  // F13b₁ — case (b) partial-batch repair at Refining
  // ---------------------------------------------------------------------------

  tempFixture.test("F13b₁ — fsm.transition logged + audit missing, fold-state at Refining → synthesise audit only"):
    root =>
      val (paths, log, cache, manifestStore) = wire(root)
      val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
      writeManifest(paths, manifest)
      val initial = Feature.initial(FeatureA, manifest)
      val transitions = transitionsToAwaitingMerge(P1, P1Pr) :+
        (FsmState.PieceAwaitingMerge(P1, P1Pr) -> FsmState.Refining(P1, P1Pr, startedAt = MergedAt))
      appendTransitions(log, initial.id, transitions)

      val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
      result match
        case Right(f) =>
          f.state match
            case FsmState.Refining(p, pr, _) =>
              assertEquals(p, P1)
              assertEquals(pr, P1Pr)
            case other => fail(s"expected Refining, got $other")
        case Left(err) => fail(s"expected Right, got Left($err)")

      val tail = log.replay(FeatureA).unsafeRunSync().map(_.kind)
      // Case (b): no synthetic fsm.transition — just the missing audit + crash-recovered marker.
      assert(tail.contains("audit.piece_merged"), s"expected audit.piece_merged in tail, got $tail")
      assert(tail.contains("harness.crash_recovered"), s"expected harness.crash_recovered in tail, got $tail")

  // ---------------------------------------------------------------------------
  // F13b₂ — case (b) partial-batch repair at a forward state
  // ---------------------------------------------------------------------------

  tempFixture.test("F13b₂ — fold-state at PieceImplementing(next) → still synthesise audit for the merged piece"):
    root =>
      val (paths, log, cache, manifestStore) = wire(root)
      val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr), piecePending(P2, 2)))
      writeManifest(paths, manifest)
      val initial = Feature.initial(FeatureA, manifest)
      val transitions = transitionsToAwaitingMerge(P1, P1Pr) ++ Vector[(FsmState, FsmState)](
        FsmState.PieceAwaitingMerge(P1, P1Pr) -> FsmState.Refining(P1, P1Pr, startedAt = MergedAt),
        FsmState.Refining(P1, P1Pr, startedAt = MergedAt) -> FsmState.PieceImplementing(P2)
      )
      appendTransitions(log, initial.id, transitions)

      val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
      result match
        case Right(f) =>
          // Case (b) does not synthesise FSM transitions — feature state is whatever the fold reached.
          assertEquals(f.state, FsmState.PieceImplementing(P2): FsmState)
        case Left(err) => fail(s"expected Right, got Left($err)")

      val tail = log.replay(FeatureA).unsafeRunSync().map(_.kind)
      assert(tail.contains("audit.piece_merged"), s"expected audit.piece_merged in tail, got $tail")

  // ---------------------------------------------------------------------------
  // F13c — case (c) bad fold-state
  // ---------------------------------------------------------------------------

  tempFixture.test("F13c — fold-state at PieceAwaitingReview (not PieceAwaitingMerge) → InconsistentRecovery"): root =>
    val (paths, log, cache, manifestStore) = wire(root)
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    writeManifest(paths, manifest)
    val initial = Feature.initial(FeatureA, manifest)
    val transitions = Vector[(FsmState, FsmState)](
      FsmState.Drafting -> FsmState.InteractiveSpec,
      FsmState.InteractiveSpec -> FsmState.DesignReviewing(1),
      FsmState.DesignReviewing(1) -> FsmState.DesignReady,
      FsmState.DesignReady -> FsmState.PieceImplementing(P1),
      FsmState.PieceImplementing(P1) -> FsmState.PieceAwaitingCi(P1, P1Pr),
      FsmState.PieceAwaitingCi(P1, P1Pr) -> FsmState.PieceAwaitingReview(P1, P1Pr)
    )
    appendTransitions(log, initial.id, transitions)

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Left(RebuildError.InconsistentRecovery(reason)) =>
        assert(reason.contains("PieceAwaitingMerge"), s"expected reason mentions PieceAwaitingMerge, got: $reason")
      case other => fail(s"expected InconsistentRecovery, got $other")
    assertEquals(cache.load(FeatureA).unsafeRunSync(), None)

  // ---------------------------------------------------------------------------
  // F13d — case (d) audit-only orphan
  // ---------------------------------------------------------------------------

  tempFixture.test("F13d — audit.piece_merged logged without paired fsm.transition → InconsistentRecovery"): root =>
    val (paths, log, cache, manifestStore) = wire(root)
    val manifest = FsmFixtures.manifest(Vector(pieceMerged(P1, 1, P1Pr)))
    writeManifest(paths, manifest)
    val initial = Feature.initial(FeatureA, manifest)
    // Seed an audit.piece_merged entry without any fsm.transition into PieceAwaitingMerge or Refining.
    val auditDraft = ActionDraft(
      feature = initial.id,
      piece = Some(P1),
      actor = None,
      role = None,
      kind = "audit.piece_merged",
      payload = ujson.Obj(
        "p" -> ujson.Str(P1.value),
        "prNumber" -> ujson.Num(P1Pr.value.toDouble),
        "mergeCommit" -> ujson.Str(Sha40Other.value),
        "mergedAt" -> ujson.Str(MergedAt.toString)
      )
    )
    val _ = log.append(initial.id, auditDraft).unsafeRunSync()

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Left(RebuildError.InconsistentRecovery(reason)) =>
        assert(reason.toLowerCase.contains("missing"), s"expected reason mentions 'missing', got: $reason")
      case other => fail(s"expected InconsistentRecovery, got $other")

  // ---------------------------------------------------------------------------
  // F13e — multi-piece divergence
  // ---------------------------------------------------------------------------

  tempFixture.test("F13e — two pieces marked merged in manifest, neither has a logged transition → refusal"): root =>
    val (paths, log, cache, manifestStore) = wire(root)
    val manifest = FsmFixtures.manifest(
      Vector(pieceMerged(P1, 1, P1Pr), pieceMerged(P2, 2, P2Pr))
    )
    writeManifest(paths, manifest)
    val initial = Feature.initial(FeatureA, manifest)
    // Drive the log far enough to reach PieceAwaitingMerge(P2) — but with NO log evidence of P1's Refining transition.
    val transitions = Vector[(FsmState, FsmState)](
      FsmState.Drafting -> FsmState.InteractiveSpec,
      FsmState.InteractiveSpec -> FsmState.DesignReviewing(1),
      FsmState.DesignReviewing(1) -> FsmState.DesignReady,
      FsmState.DesignReady -> FsmState.PieceImplementing(P2),
      FsmState.PieceImplementing(P2) -> FsmState.PieceAwaitingCi(P2, P2Pr),
      FsmState.PieceAwaitingCi(P2, P2Pr) -> FsmState.PieceAwaitingReview(P2, P2Pr),
      FsmState.PieceAwaitingReview(P2, P2Pr) -> FsmState.PieceAwaitingMerge(P2, P2Pr)
    )
    appendTransitions(log, initial.id, transitions)

    val result = RebuildState.run(FeatureA, paths, manifestStore, log, cache).unsafeRunSync()
    result match
      case Left(RebuildError.InconsistentRecovery(reason)) =>
        assert(reason.contains("multi-piece"), s"expected reason mentions multi-piece, got: $reason")
      case other => fail(s"expected InconsistentRecovery, got $other")

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def wire(root: os.Path): (ForgePaths, FileActionLog, FileStateCache, FileManifestStore) =
    val paths = new ForgePaths(repoRoot = root)
    val log = FileActionLog(paths).unsafeRunSync()
    val cache = new FileStateCache(paths)
    val manifestStore = new FileManifestStore(paths)
    (paths, log, cache, manifestStore)

  private def writeManifest(paths: ForgePaths, manifest: Manifest): Unit =
    val path = paths.manifest(FeatureA)
    os.makeDir.all(path / os.up)
    os.write(path, Manifest.toJson(manifest))

  /** Canonical pre-merge fsm.transition sequence ending at `PieceAwaitingMerge(p, pr)`. */
  private def transitionsToAwaitingMerge(p: PieceId, pr: PrNumber): Vector[(FsmState, FsmState)] =
    Vector[(FsmState, FsmState)](
      FsmState.Drafting -> FsmState.InteractiveSpec,
      FsmState.InteractiveSpec -> FsmState.DesignReviewing(1),
      FsmState.DesignReviewing(1) -> FsmState.DesignReady,
      FsmState.DesignReady -> FsmState.PieceImplementing(p),
      FsmState.PieceImplementing(p) -> FsmState.PieceAwaitingCi(p, pr),
      FsmState.PieceAwaitingCi(p, pr) -> FsmState.PieceAwaitingReview(p, pr),
      FsmState.PieceAwaitingReview(p, pr) -> FsmState.PieceAwaitingMerge(p, pr)
    )

  private def appendTransitions(
      log: FileActionLog,
      featureId: FeatureId,
      transitions: Vector[(FsmState, FsmState)]
  ): Unit =
    val drafts = transitions.map { case (from, to) =>
      ActionDraft(
        feature = featureId,
        piece = pieceOf(to),
        actor = None,
        role = None,
        kind = "fsm.transition",
        payload = ujson.Obj("from" -> writeJs[FsmState](from), "to" -> writeJs[FsmState](to))
      )
    }
    val _ = log.appendAll(featureId, drafts).unsafeRunSync()

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
