package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import cats.effect.std.Queue
import fs2.Stream
import io.forge.agents.{
  AgentEvent,
  AgentSession,
  DesignReview,
  DesignReviewInput,
  PrReview,
  PrReviewInput,
  RefineInput,
  RefineOutcome as AgentRefineOutcome,
  RefineResult,
  ReviewVerdict
}
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.{MonitorOutcome, SessionMonitor}
import io.forge.app.reviewer.{ReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.*
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, FsmEvent, FsmState, SessionPhase, SettleOutcome}
import io.forge.core.log.{Action, ActionDraft, ActionLog}
import io.forge.core.manifest.{Manifest, ManifestStore, Piece, PieceStatus}
import io.forge.core.pr.{CheckConclusion, CheckResult, CheckRollup, CheckState, PrSnapshot, PrState}
import io.forge.core.state.{RebuildError, StateCache, VerifyResult}
import io.forge.git.branch.protection.{OverlaySource, RequiredChecksOverlay}
import io.forge.git.watcher.{DecodedSnapshot, PRWatcher, PollBaseline, PollResult}

import java.time.Instant

/** Shared J5 fakes + builders for the orchestrator e2e suites (Task 1.4.10-d1). The behavioural collaborators
  * (`SideEffects`, `SessionMonitor`, `PRWatcher`, `ReviewerCall`) are scripted; the persist triad uses the real `File*`
  * stores against a per-test temp tree so the crash/recovery suite exercises the genuine S2-5 invariant.
  */
object OrchestratorTestKit:

  val BaseSha: Sha = Sha("a" * 40)
  val MergeCommit: Sha = Sha("b" * 40)
  val MergedAt: Instant = Instant.parse("2026-05-29T12:00:00Z")
  val HeadSha: Sha = Sha("c" * 40)

  /** §8: the e2e config drives the CI gate to forward on the first green poll (`stableGreenPolls = 1`); the §18 default
    * of 2 would block a single-`ciReadySnapshot` test since the watcher is the only source in `PieceAwaitingCi`.
    */
  val testConfig: ForgeConfig = ForgeConfig.Default.copy(ci = ForgeConfig.Default.ci.copy(stableGreenPolls = 1))

  // --- manifest / feature builders ---

  def piecePending(id: PieceId, order: Int): Piece =
    Piece(
      id = id,
      order = order,
      title = s"Piece ${id.value}",
      summary = s"summary ${id.value}",
      specPath = s".forge/specs/feat/pieces/${id.value}.md",
      acceptanceHash = "sha256:" + ("0" * 64),
      status = PieceStatus.Pending,
      baseSha = None,
      prNumber = None,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  def mkManifest(featureId: FeatureId, pieces: Vector[Piece], designPr: Option[PrNumber] = None): Manifest =
    Manifest(
      schemaVersion = 1,
      featureId = featureId,
      title = "Test feature",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = designPr,
      pieces = pieces
    )

  def featureAt(featureId: FeatureId, m: Manifest, state: FsmState): Feature =
    Feature.initial(featureId, m).copy(state = state)

  // --- PR snapshots ---

  def openSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(pr, PrState.Open, None, None, CheckRollup(Vector.empty, Vector.empty), None, Vector.empty, Some(true))

  /** A realistic ready snapshot: the watcher/decoder only ever populates `observed` (never `required`). The §8 gate in
    * the orchestrator promotes the observed check into `required`. Pairing this with `testConfig`'s
    * `stableGreenPolls=1` forwards on the first green poll (default 2 would block a single-snapshot test — the watcher
    * is the sole source in `PieceAwaitingCi`).
    */
  def ciReadySnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Open,
      mergedAt = None,
      mergeCommit = None,
      requiredChecks =
        CheckRollup(Vector.empty, Vector(CheckResult("ci", CheckState.Completed, Some(CheckConclusion.Success)))),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(true)
    )

  def mergedSnapshot(pr: PrNumber): PrSnapshot =
    PrSnapshot(
      number = pr,
      state = PrState.Merged,
      mergedAt = Some(MergedAt),
      mergeCommit = Some(MergeCommit),
      requiredChecks = CheckRollup(Vector.empty, Vector.empty),
      reviewDecision = None,
      unseenComments = Vector.empty,
      mergeable = Some(false)
    )

  def snapshotResult(snap: PrSnapshot): PollResult =
    PollResult.Snapshot(DecodedSnapshot(snap, HeadSha, PollBaseline.empty))

  // --- AgentSession fake ---

  final class FakeAgentSession(val sessionId: String) extends AgentSession:
    override def events: Stream[IO, AgentEvent] = Stream.empty
    override def close(): IO[Unit] = IO.unit
    override def kill(): IO[Unit] = IO.unit

  // --- SessionMonitor fake: scripted MonitorOutcome queue (one per driver settle) ---

  final class FakeSessionMonitor(outcomes: Ref[IO, List[MonitorOutcome]]) extends SessionMonitor:
    override def monitor(
        phase: SessionPhase,
        piece: Option[PieceId],
        session: AgentSession,
        events: Stream[IO, AgentEvent],
        limits: io.forge.app.monitor.SessionLimits,
        runningTotals: Ref[IO, CostTotals]
    ): IO[MonitorOutcome] =
      outcomes.modify {
        case h :: t => (t, IO.pure(h))
        case Nil =>
          (Nil, IO.raiseError(new IllegalStateException(s"FakeSessionMonitor: no scripted outcome for $phase")))
      }.flatten

  object FakeSessionMonitor:
    def make(outcomes: MonitorOutcome*): IO[FakeSessionMonitor] =
      Ref.of[IO, List[MonitorOutcome]](outcomes.toList).map(new FakeSessionMonitor(_))

  // --- PRWatcher fake: a cats-effect Queue per PR; watch blocks on empty (so a racing reviewer wins) ---

  final class FakePRWatcher(queues: Ref[IO, Map[PrNumber, Queue[IO, PollResult]]]) extends PRWatcher:
    private def queueFor(pr: PrNumber): IO[Queue[IO, PollResult]] =
      queues.get.flatMap { m =>
        m.get(pr) match
          case Some(q) => IO.pure(q)
          case None => Queue.unbounded[IO, PollResult].flatMap(q => queues.update(_ + (pr -> q)).as(q))
      }

    override def watch(pr: PrNumber, baseline: Ref[IO, PollBaseline]): Stream[IO, PollResult] =
      Stream.eval(queueFor(pr)).flatMap(Stream.fromQueueUnterminated(_))

    override def pollOnce(pr: PrNumber, baseline: PollBaseline): IO[PollResult] =
      queueFor(pr).flatMap(_.tryTake).map(_.getOrElse(PollResult.RateLimited(None)))

    def offer(pr: PrNumber, result: PollResult): IO[Unit] = queueFor(pr).flatMap(_.offer(result))

  object FakePRWatcher:
    def make: IO[FakePRWatcher] =
      Ref.of[IO, Map[PrNumber, Queue[IO, PollResult]]](Map.empty).map(new FakePRWatcher(_))

  // --- ReviewerCall fake: single configured outcome per method; onPrSettled runs when prReview is called ---

  final class FakeReviewerCall(
      designOutcome: ReviewerOutcome[DesignReview],
      prOutcome: ReviewerOutcome[PrReview],
      refineOutcome: ReviewerOutcome[RefineResult]
  ) extends ReviewerCall:
    override def designReview(input: DesignReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[DesignReview]] =
      IO.pure(designOutcome)
    override def prReview(input: PrReviewInput, limits: ReviewerLimits): IO[ReviewerOutcome[PrReview]] =
      IO.pure(prOutcome)
    override def refine(input: RefineInput, limits: ReviewerLimits): IO[ReviewerOutcome[RefineResult]] =
      IO.pure(refineOutcome)

  object FakeReviewerCall:
    val approveDesign: ReviewerOutcome[DesignReview] =
      ReviewerOutcome.Settled(DesignReview(ReviewVerdict.Approve, Vector.empty, Vector.empty, "ok"))
    val approvePr: ReviewerOutcome[PrReview] =
      ReviewerOutcome.Settled(PrReview(ReviewVerdict.Approve, Vector.empty, "ok"))
    val refineNoChange: ReviewerOutcome[RefineResult] =
      ReviewerOutcome.Settled(RefineResult(AgentRefineOutcome.NoChange, "", None))

    def happyPath: FakeReviewerCall = new FakeReviewerCall(approveDesign, approvePr, refineNoChange)

  // --- SideEffects fake: returns the documented success events; launches return fake sessions ---

  final class FakeSideEffects(designPr: PrNumber, prForPiece: PieceId => PrNumber) extends SideEffects:
    private def session(phase: SessionPhase, id: String): ActiveSession = ActiveSession(phase, FakeAgentSession(id))

    override def launchSpec(feature: Feature): IO[ActiveSession] = IO.pure(session(SessionPhase.Spec, "spec-1"))
    override def resumeDesignRevision(feature: Feature, round: Int): IO[ActiveSession] =
      IO.pure(session(SessionPhase.DesignRevision, s"design-rev-$round"))
    override def resumeDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[ActiveSession] =
      IO.pure(session(SessionPhase.DesignRevision, s"design-fb-$round"))
    override def launchImplement(feature: Feature, piece: PieceId): IO[ActiveSession] =
      IO.pure(session(SessionPhase.Implement, s"impl-${piece.value}"))
    override def launchFixup(feature: Feature, piece: PieceId, attempt: Int): IO[ActiveSession] =
      IO.pure(session(SessionPhase.Fixup, s"fixup-${piece.value}-$attempt"))

    override def designReviewInput(feature: Feature, round: Int): IO[Either[String, DesignReviewInput]] =
      IO.pure(Right(DesignReviewInput(feature.id, round, "design.md")))
    override def prReviewInput(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, PrReviewInput]] =
      IO.pure(Right(PrReviewInput(feature.id, piece, pr, "spec", "diff", Vector.empty)))
    override def refineInput(feature: Feature, piece: PieceId): IO[Either[String, RefineInput]] =
      IO.pure(Right(RefineInput(feature.id, piece, "design.md", "{}")))

    override def coherencePostCheck(feature: Feature): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean)))
    override def updateDesignAssets(feature: Feature): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean)))
    override def repushDesignFeedback(feature: Feature, pr: PrNumber, round: Int): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean)))
    override def commitDesignAndOpenPr(feature: Feature): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.DesignPrSnapshotUpdated(openSnapshot(designPr))))
    override def requiredChecksOverlay(feature: Feature): IO[Either[String, RequiredChecksOverlay]] =
      IO.pure(Right(RequiredChecksOverlay(Set.empty, Instant.EPOCH, OverlaySource.Unprotected)))
    override def advancePieceBranch(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.BranchCreated(piece, feature.manifest.pieceBranch(piece), BaseSha)))
    override def classifyCommitOpenPr(feature: Feature, piece: PieceId): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.PrOpened(piece, prForPiece(piece))))
    override def classifyCommitPush(feature: Feature, piece: PieceId, pr: PrNumber): IO[Either[String, FsmEvent]] =
      IO.pure(Right(FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean)))

  // --- StateCache wrapper: records every saved state + runs an onSave hook (delivers merge snapshots on demand) ---

  final class HookStateCache(delegate: StateCache, onSave: Feature => IO[Unit]) extends StateCache:
    override def load(featureId: FeatureId): IO[Option[Feature]] = delegate.load(featureId)
    override def save(featureId: FeatureId, feature: Feature): IO[Unit] =
      delegate.save(featureId, feature) >> onSave(feature)
    override def verifyAgainstLog(
        featureId: FeatureId,
        manifestStore: ManifestStore,
        log: ActionLog
    ): IO[Either[RebuildError, VerifyResult]] =
      delegate.verifyAgainstLog(featureId, manifestStore, log)

  // --- ActionLog wrapper: throws once on the merge-audit append (crash-window injection) ---

  final class FaultOnMergeAuditLog(delegate: ActionLog) extends ActionLog:
    override def append(featureId: FeatureId, draft: ActionDraft): IO[Action] =
      if draft.kind == "audit.piece_merged" then
        IO.raiseError(new RuntimeException("injected crash before merge audit"))
      else delegate.append(featureId, draft)
    override def appendAll(featureId: FeatureId, drafts: Vector[ActionDraft]): IO[Vector[Action]] =
      if drafts.exists(_.kind == "audit.piece_merged") then
        IO.raiseError(new RuntimeException("injected crash before merge audit"))
      else delegate.appendAll(featureId, drafts)
    override def replay(featureId: FeatureId): IO[Vector[Action]] = delegate.replay(featureId)
    override def nextSeq(featureId: FeatureId): IO[Long] = delegate.nextSeq(featureId)
