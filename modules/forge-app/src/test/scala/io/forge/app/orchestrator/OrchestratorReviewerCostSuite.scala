package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession, DesignReview, PrReview, ReviewVerdict}
import io.forge.app.monitor.{MonitorOutcome, MonitorReport, SessionLimits, SessionMonitor}
import io.forge.app.reviewer.ReviewerOutcome
import io.forge.core.*
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.Action
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildState}
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** S4-3 (Slice 2.2 B4) — reviewer/sensor spend now joins `Feature.cost`. The settled design + code reviews carry a cost
  * on `ReviewerOutcome.Settled`; the orchestrator folds it into the running totals and co-persists a `cost.update` with
  * `actor = "reviewer"`. A from-scratch `RebuildState.run` then projects it onto `Feature.cost` exactly like a driver
  * cost.update (Replay is actor-agnostic). The driver monitor here reports **no** turn cost, so every `cost.update` in
  * the committed log is a reviewer one — isolating the new path.
  */
class OrchestratorReviewerCostSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-reviewer-cost-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** A monitor that settles every driver turn cleanly with no cost — so the only `cost.update`s are reviewer ones. */
  private final class NoCostMonitor extends SessionMonitor:
    override def monitor(
        phase: SessionPhase,
        piece: Option[PieceId],
        session: AgentSession,
        events: Stream[IO, AgentEvent],
        limits: SessionLimits,
        runningTotals: Ref[IO, CostTotals]
    ): IO[MonitorReport] =
      IO.pure(MonitorReport(phase, MonitorOutcome.Settled(phase, SettleOutcome.Clean), None, Some(0L)))

  private val reviewerCost =
    Cost(provider = "anthropic", model = "haiku", inputTokens = 100, outputTokens = 50, usd = BigDecimal("0.10"))

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: io.forge.core.fsm.Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  private def num(a: Action, key: String): Option[Double] =
    a.payload.objOpt.flatMap(_.get(key)).flatMap(_.numOpt)

  tempFixture.test("settled design + code reviews write actor=reviewer cost.update; spend folds into Feature.cost"):
    root =>
      val paths = new ForgePaths(repoRoot = root)
      val specStore = new FileSpecStore(paths)
      val manifestStore = new FileManifestStore(paths)
      val cache = new FileStateCache(paths)

      val featureId = FeatureId("feat")
      val designPr = PrNumber(100)
      val piecePr = PrNumber(200)
      val p1 = PieceId("p1")
      val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
      val start = featureAt(featureId, m, FsmState.Drafting)

      val reviewer = new FakeReviewerCall(
        ReviewerOutcome
          .Settled(DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok"), Some(reviewerCost)),
        ReviewerOutcome.Settled(PrReview(ReviewVerdict.Approve, Vector.empty, "ok"), Some(reviewerCost)),
        FakeReviewerCall.refineNoChange
      )

      val (out, log, rebuilt) = (for
        logImpl <- FileActionLog(paths)
        watcher <- FakePRWatcher.make
        _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
        _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
        monitor = new NoCostMonitor
        sideEffects = new FakeSideEffects(designPr, _ => piecePr)
        hookCache = new HookStateCache(cache, f => offerMergeOnAwaitingMerge(watcher, f))
        orch = new Orchestrator(
          sideEffects,
          monitor,
          watcher,
          reviewer,
          specStore,
          manifestStore,
          logImpl,
          hookCache,
          paths,
          testConfig
        )
        out <- orch.drive(start)
        log <- logImpl.replay(featureId)
        rebuilt <- RebuildState.run(featureId, paths, manifestStore, logImpl, cache)
      yield (out, log, rebuilt)).unsafeRunSync()

      assertEquals(out.state, FsmState.FeatureDone: FsmState)

      // (i) every cost.update in the log is a reviewer one (the monitor reported no driver cost) and carries the
      // injected per-call cost.
      val costs = log.filter(_.kind == "cost.update")
      assert(costs.nonEmpty, "expected at least the design + code review reviewer cost.updates")
      assert(
        costs.forall(_.actor.contains("reviewer")),
        s"every cost.update must be actor=reviewer, got ${costs.map(_.actor)}"
      )
      assert(
        costs.forall(c => num(c, "usd").contains(0.1)),
        s"each reviewer cost.update usd should be 0.10: ${costs.map(num(_, "usd"))}"
      )

      // (ii) a design-review cost.update (piece None) and a code-review cost.update (piece p1) are both present.
      assert(costs.exists(_.piece.isEmpty), "expected a design-review reviewer cost.update (piece None)")
      assert(costs.exists(_.piece.contains(p1)), "expected a code-review reviewer cost.update (piece p1)")

      // (iii) the reviewer spend folds onto Feature.cost via a from-scratch rebuild: feature total = sum of reviewer
      // usd; turn stays 0 (reviewer spend is never a driver turn).
      val feature = rebuilt match
        case Right(r) => r.feature
        case Left(e) => fail(s"RebuildState rejected the log: $e")
      val expectedFeatureTotal = BigDecimal("0.10") * costs.length
      assertEquals(feature.cost.feature, expectedFeatureTotal)
      assertEquals(feature.cost.turn, BigDecimal(0))
