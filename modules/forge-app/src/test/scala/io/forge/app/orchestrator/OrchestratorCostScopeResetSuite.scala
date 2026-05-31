package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import fs2.Stream
import io.forge.agents.{AgentEvent, AgentSession}
import io.forge.app.monitor.{MonitorOutcome, MonitorReport, SessionLimits, SessionMonitor}
import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.pr.{CheckConclusion, CheckResult, CheckRollup, CheckState, PrSnapshot, PrState}
import io.forge.core.state.FileStateCache
import io.forge.specs.FileSpecStore

import OrchestratorTestKit.*

/** Slice 2.0 D2 (`design-2.0.md` Task 2.0.1 turn/piece reset) — the orchestrator owns the per-turn / per-piece reset of
  * the running [[CostTotals]] the [[SessionMonitor]] accumulates into. The monitor only ever *adds* each `CostUpdate`
  * delta (`RealSessionMonitor.handleEvent`), so if the orchestrator never resets `CostTotals.turn` / `.piece` they
  * accrue across turns/pieces and the §12 per-turn cap (`maxTurnCostUsd`) silently becomes a cumulative-spend check.
  *
  * Both tests drive the real loop with a recording monitor that snapshots the `runningTotals` the orchestrator hands it
  * **at turn entry**, then folds a fixed per-turn spend into the shared ref (mirroring `RealSessionMonitor`'s
  * `updateAndGet`) and applies the real §12 turn-cap rule (`RealSessionMonitor.applyCaps`): over-cap →
  * `TurnBudgetBreached`, else a clean `Settled`.
  *
  *   - Test 1 (two pieces): each new piece's implement turn starts at `turn = 0` AND `piece = 0`; `feature` carries
  *     forward. Two $1.50 turns stay under the $2.00 cap, so the run reaches `FeatureDone` rather than NHI-ing on a
  *     false cumulative breach.
  *   - Test 2 (one piece, implement → CI-fail → fix-up): the fix-up turn is the *same* piece, so `piece` carries the
  *     implement turn's spend, but `turn` still resets to zero.
  */
class OrchestratorCostScopeResetSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-orch-cost-reset-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  /** When a save lands the feature in `PieceAwaitingMerge`, feed the watcher a merged snapshot so the merge advances
    * (mirrors `OrchestratorE2EHappyPathSuite.offerMergeOnAwaitingMerge`).
    */
  private def offerMergeOnAwaitingMerge(watcher: FakePRWatcher, f: io.forge.core.fsm.Feature): IO[Unit] =
    f.state match
      case s: FsmState.PieceAwaitingMerge => watcher.offer(s.prNumber, snapshotResult(mergedSnapshot(s.prNumber)))
      case _ => IO.unit

  /** Records the `runningTotals` observed at the start of each driver turn, folds a fixed per-turn spend into the
    * shared ref, and applies the real §12 turn-cap rule.
    */
  private final class RecordingMonitor(
      spendPerTurn: BigDecimal,
      observedAtEntry: Ref[IO, Vector[(SessionPhase, CostTotals)]]
  ) extends SessionMonitor:
    override def monitor(
        phase: SessionPhase,
        piece: Option[PieceId],
        session: AgentSession,
        events: Stream[IO, AgentEvent],
        limits: SessionLimits,
        runningTotals: Ref[IO, CostTotals]
    ): IO[MonitorReport] =
      for
        entry <- runningTotals.get
        _ <- observedAtEntry.update(_ :+ (phase, entry))
        updated <- runningTotals.updateAndGet(t =>
          t.copy(feature = t.feature + spendPerTurn, piece = t.piece + spendPerTurn, turn = t.turn + spendPerTurn)
        )
        outcome =
          if updated.turn > limits.maxTurnCostUsd then
            MonitorOutcome.TurnBudgetBreached(phase, updated.turn, limits.maxTurnCostUsd, None)
          else MonitorOutcome.Settled(phase, SettleOutcome.Clean)
      yield MonitorReport(phase, outcome, turnCost = None, durationMs = None)

  /** An open PR whose single observed check has completed with a failing conclusion. The §8 gate promotes the observed
    * check into `required` and forwards immediately on a bad conclusion (`CiReadiness` rule), routing the piece to a
    * fix-up via `PieceAwaitingCi → PieceCiFailed`.
    */
  private def ciFailedSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks =
        CheckRollup(Vector.empty, Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Failure)))),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  // Each implement/fix-up turn spends $1.50: under the $2.00 per-turn cap individually, but $3.00 cumulatively — so a
  // missing turn-reset would trip the cap on the second turn.
  private val spendPerTurn = BigDecimal("1.50")

  tempFixture.test("two pieces: turn AND piece totals reset on each new piece's first turn"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)

    val featureId = FeatureId("feat")
    val designPr = PrNumber(100)
    val p1 = PieceId("p1")
    val p2 = PieceId("p2")
    val p1Pr = PrNumber(201)
    val p2Pr = PrNumber(202)
    val prFor: PieceId => PrNumber = p => if p == p1 then p1Pr else p2Pr
    val m = mkManifest(featureId, Vector(piecePending(p1, 1), piecePending(p2, 2)))
    val start = featureAt(featureId, m, FsmState.DesignReady)

    val (out, observed) = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      observedAtEntry <- Ref.of[IO, Vector[(SessionPhase, CostTotals)]](Vector.empty)
      _ <- watcher.offer(p1Pr, snapshotResult(ciReadySnapshot(p1Pr)))
      _ <- watcher.offer(p2Pr, snapshotResult(ciReadySnapshot(p2Pr)))
      monitor = new RecordingMonitor(spendPerTurn, observedAtEntry)
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, prFor)
      hookCache = new HookStateCache(baseCache, f => offerMergeOnAwaitingMerge(watcher, f))
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
      observed <- observedAtEntry.get
    yield (out, observed)).unsafeRunSync()

    // Both pieces implemented and merged with no false budget breach.
    assertEquals(out.state, FsmState.FeatureDone: FsmState)

    val implementTurns = observed.filter(_._1 == SessionPhase.Implement)
    assertEquals(implementTurns.length, 2, s"expected 2 implement turns, got: $observed")

    val turn1Entry = implementTurns(0)._2
    val turn2Entry = implementTurns(1)._2

    // Turn 1 (piece p1) starts from zero on every scope.
    assertEquals(turn1Entry.turn, BigDecimal(0), s"turn-1 entry turn total: $turn1Entry")
    assertEquals(turn1Entry.piece, BigDecimal(0), s"turn-1 entry piece total: $turn1Entry")

    // Turn 2 (piece p2): `turn` reset (new turn) AND `piece` reset (new piece); `feature` carried turn 1's spend. This
    // is the heart of D2 — without the reset both turn and piece would read 1.50.
    assertEquals(turn2Entry.turn, BigDecimal(0), s"turn-2 entry turn total should reset: $turn2Entry")
    assertEquals(turn2Entry.piece, BigDecimal(0), s"turn-2 entry piece total should reset (new piece): $turn2Entry")
    assertEquals(turn2Entry.feature, spendPerTurn, s"turn-2 entry feature total should carry forward: $turn2Entry")

  tempFixture.test("one piece, implement → CI-fail → fix-up: piece total carries, turn total resets"): root =>
    val paths = new ForgePaths(repoRoot = root)
    val specStore = new FileSpecStore(paths)
    val manifestStore = new FileManifestStore(paths)
    val baseCache = new FileStateCache(paths)

    val featureId = FeatureId("feat")
    val designPr = PrNumber(100)
    val p1 = PieceId("p1")
    val piecePr = PrNumber(201)
    val m = mkManifest(featureId, Vector(piecePending(p1, 1)))
    val start = featureAt(featureId, m, FsmState.DesignReady)

    val (out, observed) = (for
      logImpl <- FileActionLog(paths)
      watcher <- FakePRWatcher.make
      observedAtEntry <- Ref.of[IO, Vector[(SessionPhase, CostTotals)]](Vector.empty)
      // Only the CI-fail snapshot is queued up front. The green snapshot is offered *after* the failure is consumed
      // (on the `PieceCiFailed` save) so the per-PR queue never holds two items at once — `fromQueueUnterminated`
      // greedily dequeues all currently-available items into one chunk, and `.head` would drop the second.
      _ <- watcher.offer(piecePr, snapshotResult(ciFailedSnapshot(piecePr)))
      monitor = new RecordingMonitor(spendPerTurn, observedAtEntry)
      reviewer = FakeReviewerCall.happyPath
      sideEffects = new FakeSideEffects(designPr, _ => piecePr)
      onSave = (f: io.forge.core.fsm.Feature) =>
        f.state match
          case _: FsmState.PieceCiFailed => watcher.offer(piecePr, snapshotResult(ciReadySnapshot(piecePr)))
          case _ => offerMergeOnAwaitingMerge(watcher, f)
      hookCache = new HookStateCache(baseCache, onSave)
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
      observed <- observedAtEntry.get
    yield (out, observed)).unsafeRunSync()

    assertEquals(out.state, FsmState.FeatureDone: FsmState)

    val implementEntry = observed
      .collectFirst { case (SessionPhase.Implement, t) => t }
      .getOrElse(fail(s"no implement turn observed: $observed"))
    val fixupEntry = observed
      .collectFirst { case (SessionPhase.Fixup, t) => t }
      .getOrElse(fail(s"no fix-up turn observed: $observed"))

    // Implement turn (p1's first turn): both per-turn and per-piece start at zero.
    assertEquals(implementEntry.turn, BigDecimal(0), s"implement entry turn total: $implementEntry")
    assertEquals(implementEntry.piece, BigDecimal(0), s"implement entry piece total: $implementEntry")

    // Fix-up turn (same piece p1): `turn` reset to zero, but `piece` carries the implement turn's $1.50 spend, and
    // `feature` likewise carries it. Same-piece fix-ups must NOT reset the per-piece accumulator.
    assertEquals(fixupEntry.turn, BigDecimal(0), s"fix-up entry turn total should reset: $fixupEntry")
    assertEquals(fixupEntry.piece, spendPerTurn, s"fix-up entry piece total should carry (same piece): $fixupEntry")
    assertEquals(fixupEntry.feature, spendPerTurn, s"fix-up entry feature total should carry: $fixupEntry")
