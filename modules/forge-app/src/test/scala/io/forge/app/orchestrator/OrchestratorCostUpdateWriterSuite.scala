package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession}
import io.forge.app.monitor.{MonitorOutcome, MonitorReport, SessionLimits, SessionMonitor}
import io.forge.core.*
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.{Action, FileActionLog}
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildState}
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Slice 2.0 Tasks 2.0.1 (`cost.update` writer) + 2.0.2 (`session.complete` audit event) — the orchestrator drafts both
  * audit records when a driver session settles, off the [[MonitorReport]] the monitor now surfaces. The read side
  * (`Replay.applyCostUpdate`) was already built and dead; these tests prove it is now fed.
  *
  * Both tests drive the **full** lifecycle from `Drafting` (mirroring `OrchestratorE2EHappyPathSuite`) so the committed
  * log is self-consistent and a fresh, from-scratch `RebuildState.run` replays it. The fake monitor mirrors
  * `RealSessionMonitor`'s shared-totals contract (folds a fixed per-turn spend into the orchestrator's `runningTotals`
  * ref — the orchestrator owns the per-turn/per-piece reset, D2) and returns a `MonitorReport` carrying that turn's
  * aggregate `Cost` + a CLI duration. Each run therefore settles a Spec turn then one Implement turn per piece.
  *
  *   - Test 1 (single piece): the Implement `cost.update` carries the full §19 payload; one `session.complete` lands
  *     per settled driver session; a fresh `RebuildState.run` projects the running totals onto `Feature.cost` (and
  *     tolerates the audit-only `session.complete` as a no-op projection — i.e. replay does not reject the new kind).
  *   - Test 2 (two pieces): the written `cost.update` totals show `feature` carrying across pieces while `turn` and
  *     `piece` restart — D2's reset made observable in the *committed record*, not just the in-memory cap check.
  */
class OrchestratorCostUpdateWriterSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-cost-writer-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: io.forge.core.fsm.Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** Mirrors `RealSessionMonitor`: folds `perTurn.usd` into the shared `runningTotals` (the orchestrator having already
    * reset `turn` / `piece` at the boundary), then returns a clean settle whose `MonitorReport` carries the turn's
    * aggregate cost + the CLI duration.
    */
  private final class CostingMonitor(perTurn: Cost, durationMs: Long) extends SessionMonitor:
    override def monitor(
        phase: SessionPhase,
        piece: Option[PieceId],
        session: AgentSession,
        events: Stream[IO, AgentEvent],
        limits: SessionLimits,
        runningTotals: Ref[IO, CostTotals]
    ): IO[MonitorReport] =
      runningTotals
        .update(t =>
          t.copy(feature = t.feature + perTurn.usd, piece = t.piece + perTurn.usd, turn = t.turn + perTurn.usd)
        )
        .as(MonitorReport(phase, MonitorOutcome.Settled(phase, SettleOutcome.Clean), Some(perTurn), Some(durationMs)))

  private val perTurn =
    Cost(provider = "anthropic", model = "haiku", inputTokens = 1200, outputTokens = 340, usd = BigDecimal("1.50"))
  private val durationMs = 9100L

  private def num(a: Action, key: String): Option[Double] =
    a.payload.objOpt.flatMap(_.get(key)).flatMap(_.numOpt)
  private def str(a: Action, key: String): Option[String] =
    a.payload.objOpt.flatMap(_.get(key)).flatMap(_.strOpt)

  tempFixture.test("single piece: cost.update (full §19 payload) + session.complete land and replay back onto Feature"):
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

      val (out, log, rebuilt) = (for
        logImpl <- FileActionLog(paths)
        watcher <- FakePRWatcher.make
        _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
        _ <- watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
        monitor = new CostingMonitor(perTurn, durationMs)
        sideEffects = new FakeSideEffects(designPr, _ => piecePr)
        hookCache = new HookStateCache(cache, f => offerMergeOnAwaitingMerge(watcher, f))
        orch = new Orchestrator(
          sideEffects,
          monitor,
          watcher,
          FakeReviewerCall.happyPath,
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

      // (i) one cost.update per settled driver turn (Spec, then Implement); the Implement one carries the full §19
      // payload off the turn delta + the three running scopes. The Spec turn spent 1.50, so by the Implement turn the
      // feature scope reads 3.00 while piece/turn restart from this piece/turn's 1.50.
      val costs = log.filter(_.kind == "cost.update")
      assertEquals(costs.length, 2, s"expected a cost.update per driver turn, got ${log.map(_.kind)}")
      val implCost = costs.find(_.piece.contains(p1)).getOrElse(fail(s"no implement cost.update: ${costs}"))
      assertEquals(str(implCost, "provider"), Some("anthropic"))
      assertEquals(str(implCost, "model"), Some("haiku"))
      assertEquals(num(implCost, "inputTokens"), Some(1200.0))
      assertEquals(num(implCost, "outputTokens"), Some(340.0))
      assertEquals(num(implCost, "usd"), Some(1.5))
      assertEquals(num(implCost, "featureTotalUsd"), Some(3.0))
      assertEquals(num(implCost, "pieceTotalUsd"), Some(1.5))
      assertEquals(num(implCost, "turnTotalUsd"), Some(1.5))

      // (ii) session.complete (Task 2.0.2): one per settled driver session, carrying per-phase timing + attribution.
      val sessions = log.filter(_.kind == "session.complete")
      assertEquals(sessions.length, 2, s"expected a session.complete per driver turn, got ${log.map(_.kind)}")
      val implSession = sessions.find(_.piece.contains(p1)).getOrElse(fail(s"no implement session.complete: $sessions"))
      assertEquals(str(implSession, "phase"), Some("Implement"))
      assertEquals(str(implSession, "piece"), Some("p1"))
      assertEquals(num(implSession, "durationMs"), Some(9100.0))
      assertEquals(str(implSession, "model"), Some("haiku"))
      assertEquals(num(implSession, "turnCostUsd"), Some(1.5))
      assertEquals(implSession.payload.objOpt.flatMap(_.get("success")).flatMap(_.boolOpt), Some(true))

      // (iii) the dead read side is now fed: a from-scratch RebuildState projects the final running totals onto
      // Feature.cost. A Left here would mean replay rejected the new session.complete kind.
      val feature = rebuilt match
        case Right(r) => r.feature
        case Left(e) => fail(s"RebuildState rejected the log: $e")
      assertEquals(feature.cost, CostTotals(BigDecimal(3.0), BigDecimal(1.5), BigDecimal(1.5)))

  tempFixture.test("two pieces: written cost.update totals reflect turn/piece reset; feature total carries forward"):
    root =>
      val paths = new ForgePaths(repoRoot = root)
      val specStore = new FileSpecStore(paths)
      val manifestStore = new FileManifestStore(paths)
      val cache = new FileStateCache(paths)

      val featureId = FeatureId("feat")
      val designPr = PrNumber(100)
      val p1 = PieceId("p1")
      val p2 = PieceId("p2")
      val p1Pr = PrNumber(201)
      val p2Pr = PrNumber(202)
      val prFor: PieceId => PrNumber = p => if p == p1 then p1Pr else p2Pr
      val m = mkManifest(featureId, Vector(piecePending(p1, 1), piecePending(p2, 2)))
      val start = featureAt(featureId, m, FsmState.Drafting)

      val (out, log, rebuilt) = (for
        logImpl <- FileActionLog(paths)
        watcher <- FakePRWatcher.make
        _ <- watcher.offer(designPr, snapshotResult(mergedSnapshot(designPr)))
        _ <- watcher.offer(p1Pr, snapshotResult(ciReadySnapshot(p1Pr)))
        _ <- watcher.offer(p2Pr, snapshotResult(ciReadySnapshot(p2Pr)))
        monitor = new CostingMonitor(perTurn, durationMs)
        sideEffects = new FakeSideEffects(designPr, prFor)
        hookCache = new HookStateCache(cache, f => offerMergeOnAwaitingMerge(watcher, f))
        orch = new Orchestrator(
          sideEffects,
          monitor,
          watcher,
          FakeReviewerCall.happyPath,
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

      // One cost.update for the Spec turn (piece None) then one per implemented piece.
      val pieceCosts = log.filter(c => c.kind == "cost.update" && c.piece.isDefined)
      assertEquals(pieceCosts.length, 2, s"expected one piece cost.update per piece, got ${log.map(_.kind)}")
      val c1 = pieceCosts.find(_.piece.contains(p1)).getOrElse(fail(s"no p1 cost.update: $pieceCosts"))
      val c2 = pieceCosts.find(_.piece.contains(p2)).getOrElse(fail(s"no p2 cost.update: $pieceCosts"))

      // p1: Spec (1.50) already accrued onto `feature`, so it reads 3.00; `piece`/`turn` restart at this turn's 1.50.
      assertEquals(num(c1, "featureTotalUsd"), Some(3.0))
      assertEquals(num(c1, "pieceTotalUsd"), Some(1.5))
      assertEquals(num(c1, "turnTotalUsd"), Some(1.5))

      // p2: `feature` carries forward (4.50) while `piece` and `turn` restart again at 1.50 — the heart of D2, now
      // observable in the *written* record rather than only the in-memory cap check.
      assertEquals(num(c2, "featureTotalUsd"), Some(4.5))
      assertEquals(num(c2, "pieceTotalUsd"), Some(1.5))
      assertEquals(num(c2, "turnTotalUsd"), Some(1.5))

      // Last-write-wins projection lands the final scopes on the rebuilt feature.
      val feature = rebuilt match
        case Right(r) => r.feature
        case Left(e) => fail(s"RebuildState rejected the log: $e")
      assertEquals(feature.cost, CostTotals(BigDecimal(4.5), BigDecimal(1.5), BigDecimal(1.5)))
