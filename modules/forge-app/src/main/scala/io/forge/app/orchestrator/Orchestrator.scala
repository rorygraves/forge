package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import io.forge.agents.{DesignReview, PrReview, RefineOutcome as AgentRefineOutcome, RefineResult, ReviewVerdict}
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.{MonitorOutcome, SessionLimits, SessionMonitor}
import io.forge.app.reviewer.{ReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.{PieceId, PrNumber}
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, Fsm, FsmConfig, FsmEvent, FsmState, SessionPhase, UserCommand}
import io.forge.core.manifest.{ManifestPatch, ManifestStore}
import io.forge.core.pr.PrState
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}
import io.forge.core.state.{RebuildState, StateCache}
import io.forge.git.watcher.{DecodedSnapshot, PRWatcher, PollBaseline, PollResult}
import io.forge.specs.SpecStore

import scala.concurrent.duration.*
import scala.util.Try

/** The headless feature engine (Task 1.4.10 J1/J2) — the effectful loop that drives a single feature through the §11
  * lifecycle by interleaving the pure `Fsm.transition` reactive core with the side-effect contract.
  *
  * **Increment scope (-d1).** This is the loop engine: state-entry hooks, the source race, the J4 atomic persist, the
  * post-settle synthesis dispatch, and the source-driven session lifecycle. Every git/gh/worktree/Connector/prompt
  * interaction sits behind the injected [[SideEffects]] seam, whose real implementation (plus the `git commit` seam,
  * `Connector` lifetime J3, and `Main` wiring) lands in Task 1.4.10-d2. The J5 suites drive this loop deterministically
  * with scripted fakes against real `File*` stores in a temp tree.
  *
  * **Three sub-phases per iteration** (design-1.4.md J2):
  *   1. entry hook — spawn/resume the driver session, create the next piece branch, or synthesize the
  *      transition-triggering event for the "(entry-hook-only)" states. Entry-hook steps chain (after each synthesized
  *      event the loop re-runs the entry hook, which is idempotent via its `currentDriverSession` / `baseSha` guards).
  *      2. source race — `EventSources.select` names the sources; whichever fires first wins, the losers are cancelled
  *      by `IO.race`. 3. post-settle synthesis — for a driver-session settle the side effect runs **before** the
  *      synthesized event is applied (this resolves §11's Implement-vs-Fixup ordering asymmetry uniformly via
  *      [[PostSettleSynthesis.plan]]).
  *
  * **Session lifecycle is source-driven:** a `SessionMonitor` outcome ALWAYS clears `currentDriverSession` (the
  * subprocess is gone); no other source touches it; the next state's entry hook re-spawns if needed.
  *
  * The atomic persist order — `SpecStore.saveManifest` **then** action-log append **then** state-cache save — is the
  * **S2-5** writer-side invariant; the §11.5 crash window (manifest written, log not) is recovered by
  * `RebuildState.reconcile` on the next `run`.
  */
final class Orchestrator(
    sideEffects: SideEffects,
    monitor: SessionMonitor,
    watcher: PRWatcher,
    reviewer: ReviewerCall,
    specStore: SpecStore,
    manifestStore: ManifestStore,
    log: io.forge.core.log.ActionLog,
    cache: StateCache,
    paths: io.forge.core.paths.ForgePaths,
    config: ForgeConfig,
    userInput: IO[UserCommand] = IO.never
):

  private val fsmConfig = FsmConfig(config.maxDesignReviewRounds, config.maxFixupRounds)

  /** v1 reviewer wall-clock cap (Task 1.4.7). The per-method config knob is carry-forward **S4-5** (Task 1.4.9
    * ForgeConfig deferral); until then the 3-minute cap is hard-wired here as it is in the reviewer-call wiring.
    */
  private val reviewerWallClock: FiniteDuration = 3.minutes

  /** S4-1 — cross-restart persistence of the PRWatcher poll cursor (built from `paths`, no constructor change). */
  private val baselineStore = new PollBaselineStore(paths)

  /** Drive `featureId` from its persisted state to a loop-terminal state (`NeedsHumanIntervention` / `FeatureDone` /
    * `Abandoned`), returning the terminal `Feature`.
    */
  def run(featureId: io.forge.core.FeatureId): IO[Feature] =
    RebuildState.run(featureId, paths, manifestStore, log, cache).flatMap {
      case Left(err) =>
        IO.raiseError(new RuntimeException(s"forge: cannot rebuild state for ${featureId.value}: $err"))
      case Right(rebuilt) =>
        // Restart recovery: route any in-flight driver session to NHI before racing any source.
        val (feature0, drafts) = RestartRecovery.recover(rebuilt.feature, rebuilt.inFlightSessions, fsmConfig)
        (if drafts.nonEmpty then persistTransition(feature0, drafts) else IO.unit) >> drive(feature0)
    }

  /** Apply a single operator `UserCommand` (`forge resume` / `forge abandon`, Task 1.4.13 M5 / M8) against the
    * persisted feature, then drive to a loop-terminal state.
    *
    * The command is applied as a one-shot `Fsm.transition` **before** any entry hook fires, so abandoning a mid-flight
    * feature records the `Abandoned` transition without first re-spawning its driver, and resuming from
    * `NeedsHumanIntervention` lands the new active state before the loop's entry hooks run. `deriveCommand` builds the
    * command from the freshly-rebuilt feature: `forge resume` reads the authoritative [[io.forge.core.fsm.ResumeHint]]
    * (which carries the PR number) off the persisted NHI state, while `forge abandon` ignores the feature. It may
    * reject up front with `Left(reason)`.
    *
    * Restart recovery is deliberately skipped (unlike [[run]]): a feature in NHI has no live driver session, and
    * `abandon` overrides any in-flight session unconditionally.
    */
  def applyUserCommand(
      featureId: io.forge.core.FeatureId,
      deriveCommand: Feature => Either[String, UserCommand]
  ): IO[Orchestrator.CommandOutcome] =
    RebuildState.run(featureId, paths, manifestStore, log, cache).flatMap {
      case Left(err) =>
        IO.raiseError(new RuntimeException(s"forge: cannot rebuild state for ${featureId.value}: $err"))
      case Right(rebuilt) => applyUserCommandTo(rebuilt.feature, deriveCommand)
    }

  /** The persist-and-drive half of [[applyUserCommand]], split out so the e2e suites can drive it from a constructed
    * `Feature` (same rationale as [[drive]]) rather than seeding a full action-log history. A command the FSM does not
    * act on (state unchanged and no drafts) is surfaced as a [[Orchestrator.CommandOutcome.Rejected]] rather than
    * silently driving an unchanged feature.
    */
  private[orchestrator] def applyUserCommandTo(
      feature0: Feature,
      deriveCommand: Feature => Either[String, UserCommand]
  ): IO[Orchestrator.CommandOutcome] =
    deriveCommand(feature0) match
      case Left(reason) => IO.pure(Orchestrator.CommandOutcome.Rejected(feature0.state, reason))
      case Right(cmd) =>
        val (f1, drafts) = Fsm.transition(feature0, FsmEvent.UserCommandReceived(cmd), fsmConfig)
        if f1.state == feature0.state && drafts.isEmpty then
          IO.pure(
            Orchestrator.CommandOutcome.Rejected(feature0.state, s"command had no effect in state ${feature0.state}")
          )
        else persistTransition(f1, drafts) >> drive(f1).map(Orchestrator.CommandOutcome.Driven.apply)

  /** Drive a feature from the given state to a loop-terminal state. Exposed for the J5 e2e suites, which construct the
    * starting `Feature` directly rather than seeding a full action-log history through `run`.
    */
  private[orchestrator] def drive(feature: Feature): IO[Feature] =
    if isLoopTerminal(feature.state) then IO.pure(feature)
    else
      for
        driverRef <- Ref.of[IO, Option[ActiveSession]](None)
        totalsRef <- Ref.of[IO, CostTotals](CostTotals.zero)
        out <- loop(feature, driverRef, totalsRef, justEntered = true)
      yield out

  // ---------------------------------------------------------------------------
  // The loop
  // ---------------------------------------------------------------------------

  private def loop(
      feature: Feature,
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals],
      justEntered: Boolean
  ): IO[Feature] =
    if isLoopTerminal(feature.state) then IO.pure(feature)
    else
      val entry = if justEntered then runEntryHook(feature, driverRef) else IO.pure(Option.empty[FsmEvent])
      entry.flatMap {
        // An entry-hook-synthesized event: apply, persist, then re-run the entry hook (steps chain).
        case Some(event) =>
          applyAndPersist(feature, event).flatMap(f1 => loop(f1, driverRef, totalsRef, justEntered = true))
        // No entry event: race the selected sources, then recurse with justEntered = state-changed.
        case None =>
          driverRef.get.flatMap { active =>
            val sources = EventSources.select(feature.state, active)
            if sources.isEmpty then
              IO.raiseError(new IllegalStateException(s"orchestrator: no event sources for state ${feature.state}"))
            else
              raceAll(sources.map(sourceIO(feature, active, _, totalsRef)))
                .flatMap(winner => handleWinner(feature, winner, driverRef))
                .flatMap(f1 => loop(f1, driverRef, totalsRef, justEntered = f1.state != feature.state))
          }
      }

  /** Apply one event through the pure FSM and persist atomically; returns the resulting feature. */
  private def applyAndPersist(feature: Feature, event: FsmEvent): IO[Feature] =
    val (f1, drafts) = Fsm.transition(feature, event, fsmConfig)
    persistTransition(f1, drafts).as(f1)

  /** J4: manifest first (S2-5 writer invariant), then action log, then state cache. */
  private def persistTransition(feature: Feature, drafts: Vector[io.forge.core.log.ActionDraft]): IO[Unit] =
    for
      _ <- specStore.saveManifest(feature.id, feature.manifest).flatMap {
        case Left(e) => IO.raiseError(new RuntimeException(s"forge: manifest persist failed: $e"))
        case Right(_) => IO.unit
      }
      _ <- if drafts.nonEmpty then log.appendAll(feature.id, drafts).void else IO.unit
      _ <- cache.save(feature.id, feature)
    yield ()

  // ---------------------------------------------------------------------------
  // Sub-phase I — state-entry hooks
  // ---------------------------------------------------------------------------

  /** Returns `Some(event)` to apply + re-enter the entry hook, or `None` to fall through to the source race. */
  private def runEntryHook(feature: Feature, driverRef: Ref[IO, Option[ActiveSession]]): IO[Option[FsmEvent]] =
    feature.state match
      case FsmState.Drafting =>
        sideEffects.launchSpec(feature).flatMap(as => store(driverRef, as).as(Some(spawned(as, None))))

      case FsmState.InteractiveSpec =>
        driverRef.get.flatMap {
          case Some(_) => IO.pure(None)
          case None => sideEffects.launchSpec(feature).flatMap(as => store(driverRef, as).as(None))
        }

      case s: FsmState.DesignReviewing =>
        // round == 1 → fresh review (reviewer source); round > 1 → resume the revision driver after request_changes.
        driverRef.get.flatMap {
          case None if s.round > 1 =>
            sideEffects
              .resumeDesignRevision(feature, s.round)
              .flatMap(as => store(driverRef, as).as(Some(resumed(as, feature.designSessionId, None))))
          case _ => IO.pure(None)
        }

      case s: FsmState.DesignPrFeedback =>
        driverRef.get.flatMap {
          case None =>
            sideEffects
              .resumeDesignFeedback(feature, s.prNumber, s.round)
              .flatMap(as => store(driverRef, as).as(Some(resumed(as, feature.designSessionId, None))))
          case Some(_) => IO.pure(None)
        }

      case FsmState.DesignReady =>
        feature.manifest.nextPending match
          case Some(p) => sideEffects.advancePieceBranch(feature, p).map(e => Some(eventOrHarness(e)))
          case None => IO.pure(Some(FsmEvent.HarnessError("DesignReady reached with no pending pieces")))

      case s: FsmState.PieceImplementing =>
        val branchReady = feature.manifest.pieces.find(_.id == s.p).flatMap(_.baseSha).isDefined
        if !branchReady then sideEffects.advancePieceBranch(feature, s.p).map(e => Some(eventOrHarness(e)))
        else
          driverRef.get.flatMap {
            case None =>
              sideEffects
                .launchImplement(feature, s.p)
                .flatMap(as => store(driverRef, as).as(Some(spawned(as, Some(s.p)))))
            case Some(_) => IO.pure(None)
          }

      case s: FsmState.PieceCiFailed =>
        sideEffects
          .launchFixup(feature, s.p, s.attempt)
          .flatMap(as => store(driverRef, as).as(Some(spawned(as, Some(s.p)))))

      case s: FsmState.PieceReviewFailed =>
        sideEffects
          .launchFixup(feature, s.p, s.attempt)
          .flatMap(as => store(driverRef, as).as(Some(spawned(as, Some(s.p)))))

      case s: FsmState.PieceFixingUp =>
        driverRef.get.flatMap {
          case None =>
            sideEffects
              .launchFixup(feature, s.p, s.attempt)
              .flatMap(as => store(driverRef, as).as(Some(spawned(as, Some(s.p)))))
          case Some(_) => IO.pure(None)
        }

      // Watcher / reviewer / user-Q&A states have no entry side effect: fall through to the race.
      case _ => IO.pure(None)

  private def store(driverRef: Ref[IO, Option[ActiveSession]], as: ActiveSession): IO[Unit] =
    driverRef.set(Some(as))

  // ---------------------------------------------------------------------------
  // Sub-phase II — source race
  // ---------------------------------------------------------------------------

  /** First-wins race; `IO.race` cancels the losing source on completion. */
  private def raceAll(ios: Vector[IO[RaceResult]]): IO[RaceResult] =
    ios.reduceLeft((a, b) => IO.race(a, b).map(_.merge))

  private def sourceIO(
      feature: Feature,
      active: Option[ActiveSession],
      src: EventSource,
      totalsRef: Ref[IO, CostTotals]
  ): IO[RaceResult] =
    src match
      case EventSource.Monitor =>
        active match
          case Some(as) =>
            monitor
              .monitor(
                as.phase,
                pieceOf(feature.state),
                as.session,
                as.session.events,
                sessionLimitsFor(as.phase),
                totalsRef
              )
              .map(RaceResult.FromMonitor.apply)
          case None =>
            IO.raiseError(
              new IllegalStateException(s"Monitor source selected with no active session in ${feature.state}")
            )

      case EventSource.Watcher(pr) => watcherIO(feature, pr)
      case EventSource.Reviewer(method) => reviewerIO(feature, method)
      case EventSource.Repl => IO.never // bidirectional `forge spec` REPL — Task 1.4.13
      case EventSource.UserQa => userInput.map(RaceResult.FromUser.apply)

  private def watcherIO(feature: Feature, pr: PrNumber): IO[RaceResult] =
    // S4-1: seed the cursor from the persisted baseline file so a restart mid-gate resumes where the last poll left
    // off, and persist `decoded.nextBaseline` after each Snapshot poll (the watcher's `stepOnce` sets the ref before
    // emitting, so `baselineRef.get` here reflects the just-decoded cursor).
    baselineStore.load(feature.id, pr).flatMap { seed =>
      Ref.of[IO, PollBaseline](seed).flatMap { baselineRef =>
        watcher
          .watch(pr, baselineRef)
          .evalTap {
            case _: PollResult.Snapshot => baselineRef.get.flatMap(baselineStore.save(feature.id, pr, _))
            case _ => IO.unit
          }
          .evalMap(pollResultToEvent(feature, _))
          .unNone
          .head
          .compile
          .lastOrError
          .map(RaceResult.FromWatcher.apply)
      }
    }

  private def pollResultToEvent(feature: Feature, result: PollResult): IO[Option[FsmEvent]] =
    result match
      case PollResult.Snapshot(decoded) => watcherEventFor(feature.state, decoded).map(Some(_))
      case PollResult.RateLimited(_) => IO.pure(None) // the watch stream absorbs back-off (S3-4)
      case PollResult.Failed(err) => IO.pure(Some(FsmEvent.HarnessError(s"PR poll failed: $err")))

  private def watcherEventFor(state: FsmState, decoded: DecodedSnapshot): IO[FsmEvent] =
    val snap = decoded.snapshot
    state match
      case _: FsmState.DesignAwaitingMerge => IO.pure(FsmEvent.DesignPrSnapshotUpdated(snap))
      case s: FsmState.PieceAwaitingCi => IO.pure(FsmEvent.PrSnapshotUpdated(s.p, snap))
      case s: FsmState.PieceAwaitingReview => IO.pure(FsmEvent.PrSnapshotUpdated(s.p, snap))
      case s: FsmState.PieceAwaitingMerge =>
        (snap.state, snap.mergeCommit, snap.mergedAt) match
          case (PrState.Merged, Some(mc), Some(ma)) =>
            IO.realTimeInstant.map(now => FsmEvent.Merged(s.p, s.prNumber, mc, ma, now))
          case _ => IO.pure(FsmEvent.PrSnapshotUpdated(s.p, snap))
      case other =>
        IO.raiseError(new IllegalStateException(s"PRWatcher source selected for non-watcher state $other"))

  private def reviewerIO(feature: Feature, method: ReviewerMethod): IO[RaceResult] =
    val limits = ReviewerLimits(reviewerWallClock)
    method match
      case ReviewerMethod.DesignReview =>
        val round = feature.state match
          case s: FsmState.DesignReviewing => s.round
          case _ => 1
        sideEffects.designReviewInput(feature, round).flatMap {
          case Left(reason) => IO.pure(RaceResult.FromReviewer(FsmEvent.HarnessError(reason)))
          case Right(in) =>
            reviewer.designReview(in, limits).map(o => RaceResult.FromReviewer(designReviewEvent(round, o)))
        }

      case ReviewerMethod.PrReview =>
        feature.state match
          case s: FsmState.PieceAwaitingReview =>
            sideEffects.prReviewInput(feature, s.p, s.prNumber).flatMap {
              case Left(reason) => IO.pure(RaceResult.FromReviewer(FsmEvent.HarnessError(reason)))
              case Right(in) =>
                reviewer.prReview(in, limits).map(o => RaceResult.FromReviewer(prReviewEvent(s.p, o)))
            }
          case other =>
            IO.raiseError(new IllegalStateException(s"PrReview source selected in non-review state $other"))

      case ReviewerMethod.Refine =>
        feature.state match
          case s: FsmState.Refining =>
            sideEffects.refineInput(feature, s.p).flatMap {
              case Left(reason) => IO.pure(RaceResult.FromReviewer(FsmEvent.HarnessError(reason)))
              case Right(in) =>
                reviewer.refine(in, limits).map(o => RaceResult.FromReviewer(refineEvent(o)))
            }
          case other =>
            IO.raiseError(new IllegalStateException(s"Refine source selected in non-refining state $other"))

  // ---------------------------------------------------------------------------
  // Winner dispatch (+ sub-phase III post-settle synthesis)
  // ---------------------------------------------------------------------------

  private def handleWinner(
      feature: Feature,
      winner: RaceResult,
      driverRef: Ref[IO, Option[ActiveSession]]
  ): IO[Feature] =
    winner match
      case RaceResult.FromMonitor(outcome) =>
        // Source-driven session clear: the subprocess is gone whatever the outcome.
        driverRef.set(None) >> {
          val plan = PostSettleSynthesis.plan(feature.state, outcome)
          plan.effect match
            case SettleEffect.None =>
              // Pass-through outcome. Apply the converted event directly; if it no-ops (an unhandled driver settle
              // such as HitQuestionLimit), route to NHI rather than spin on a now-session-less state.
              val raw = passThroughEvent(plan.synthesis, outcome)
              val (cand, drafts) = Fsm.transition(feature, raw, fsmConfig)
              if cand.state == feature.state then
                applyAndPersist(
                  feature,
                  FsmEvent.HarnessError(s"unhandled driver settle in ${feature.state}: $outcome")
                )
              else persistTransition(cand, drafts).as(cand)
            case eff =>
              // Driver settled clean: run the §11 side effect FIRST, then apply the synthesized event.
              runSettleEffect(feature, eff).flatMap(applyAndPersist(feature, _))
        }

      case RaceResult.FromWatcher(event) => applyAndPersist(feature, event)

      case RaceResult.FromReviewer(event) =>
        // §11.2 step 13: a design-review `Approve` is an FSM no-op — the orchestrator must commit the design assets +
        // open the design PR, then feed the resulting snapshot to advance into DesignAwaitingMerge.
        val (f1, drafts) = Fsm.transition(feature, event, fsmConfig)
        (event, f1.state == feature.state) match
          case (FsmEvent.DesignReviewReceived(_, DesignReviewVerdict.Approve), true) =>
            sideEffects.commitDesignAndOpenPr(feature).flatMap(e => applyAndPersist(feature, eventOrHarness(e)))
          case _ => persistTransition(f1, drafts).as(f1)

      case RaceResult.FromUser(cmd) => applyAndPersist(feature, FsmEvent.UserCommandReceived(cmd))

  private def runSettleEffect(feature: Feature, eff: SettleEffect): IO[FsmEvent] =
    val resultIO: IO[Either[String, FsmEvent]] = eff match
      case SettleEffect.CoherencePostCheck => sideEffects.coherencePostCheck(feature)
      case SettleEffect.UpdateDesignAssets => sideEffects.updateDesignAssets(feature)
      case SettleEffect.RepushDesignFeedback =>
        feature.state match
          case s: FsmState.DesignPrFeedback => sideEffects.repushDesignFeedback(feature, s.prNumber, s.round)
          case other => IO.pure(Left(s"RepushDesignFeedback in unexpected state $other"))
      case SettleEffect.ClassifyCommitOpenPr =>
        feature.state match
          case s: FsmState.PieceImplementing => sideEffects.classifyCommitOpenPr(feature, s.p)
          case other => IO.pure(Left(s"ClassifyCommitOpenPr in unexpected state $other"))
      case SettleEffect.ClassifyCommitPush =>
        feature.state match
          case s: FsmState.PieceFixingUp => sideEffects.classifyCommitPush(feature, s.p, s.prNumber)
          case other => IO.pure(Left(s"ClassifyCommitPush in unexpected state $other"))
      case SettleEffect.None => IO.pure(Left("no side effect"))
    resultIO.map(eventOrHarness)

  // ---------------------------------------------------------------------------
  // Reviewer outcome → FsmEvent projection (forge-agents rich types → forge-core verdicts)
  // ---------------------------------------------------------------------------

  private def designReviewEvent(round: Int, outcome: ReviewerOutcome[DesignReview]): FsmEvent =
    outcome match
      case ReviewerOutcome.Settled(review) => FsmEvent.DesignReviewReceived(round, designVerdict(review))
      case ReviewerOutcome.Timeout => FsmEvent.SettleTimeout(SessionPhase.DesignReview, "design review wall-clock cap")
      case ReviewerOutcome.AdapterFailure(err) => FsmEvent.HarnessError(s"design review failed: ${err.message}")

  private def prReviewEvent(piece: PieceId, outcome: ReviewerOutcome[PrReview]): FsmEvent =
    outcome match
      case ReviewerOutcome.Settled(review) => FsmEvent.CodeReviewVerdict(piece, prVerdict(review))
      case ReviewerOutcome.Timeout => FsmEvent.SettleTimeout(SessionPhase.CodeReview, "code review wall-clock cap")
      case ReviewerOutcome.AdapterFailure(err) => FsmEvent.HarnessError(s"code review failed: ${err.message}")

  private def refineEvent(outcome: ReviewerOutcome[RefineResult]): FsmEvent =
    outcome match
      case ReviewerOutcome.Settled(result) => FsmEvent.RefineOutcome(refineVerdict(result))
      case ReviewerOutcome.Timeout => FsmEvent.SettleTimeout(SessionPhase.Refine, "refine wall-clock cap")
      case ReviewerOutcome.AdapterFailure(err) => FsmEvent.HarnessError(s"refine failed: ${err.message}")

  private def designVerdict(review: DesignReview): DesignReviewVerdict =
    review.verdict match
      case ReviewVerdict.Approve => DesignReviewVerdict.Approve
      case ReviewVerdict.RequestChanges =>
        if review.questions.nonEmpty then DesignReviewVerdict.BlockingQuestions(review.questions)
        else DesignReviewVerdict.RequestChanges(review.blockers.map(_.summary))

  private def prVerdict(review: PrReview): PrReviewVerdict =
    review.verdict match
      case ReviewVerdict.Approve => PrReviewVerdict.Approve
      case ReviewVerdict.RequestChanges => PrReviewVerdict.RequestChanges(review.blockers.map(_.summary))

  private def refineVerdict(result: RefineResult): RefineVerdict =
    result.outcome match
      case AgentRefineOutcome.NoChange => RefineVerdict.NoChange
      case AgentRefineOutcome.ReopenDesign => RefineVerdict.ReopenDesign(result.reason)
      case AgentRefineOutcome.UpdatePlan =>
        val patch = result.patchJson
          .flatMap(json => Try(upickle.default.read[ManifestPatch](json)).toOption)
          .getOrElse(ManifestPatch(result.reason, Vector.empty))
        RefineVerdict.UpdatePlan(patch)

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  private def eventOrHarness(e: Either[String, FsmEvent]): FsmEvent = e.fold(FsmEvent.HarnessError.apply, identity)

  private def passThroughEvent(synthesis: SettleSynthesis, outcome: MonitorOutcome): FsmEvent =
    synthesis match
      case SettleSynthesis.Event(e) => e
      case SettleSynthesis.PrOpenedAfterCreate(_) =>
        PostSettleSynthesis.toFsmEvent(outcome) // unreachable for None effect

  private def spawned(as: ActiveSession, piece: Option[PieceId]): FsmEvent =
    FsmEvent.SessionSpawned(actor = "driver", role = roleOf(as.phase), sessionId = as.session.sessionId, piece = piece)

  private def resumed(as: ActiveSession, oldSessionId: Option[String], piece: Option[PieceId]): FsmEvent =
    FsmEvent.SessionResumed(
      actor = "driver",
      role = roleOf(as.phase),
      oldSessionId = oldSessionId.getOrElse(""),
      newSessionId = as.session.sessionId,
      piece = piece
    )

  private def roleOf(phase: SessionPhase): String = phase match
    case SessionPhase.Spec => "spec"
    case SessionPhase.DesignRevision => "design"
    case SessionPhase.Implement => "implement"
    case SessionPhase.Fixup => "fixup"
    case other => other.toString.toLowerCase

  private def pieceOf(state: FsmState): Option[PieceId] = state match
    case s: FsmState.PieceImplementing => Some(s.p)
    case s: FsmState.PieceFixingUp => Some(s.p)
    case _ => None

  private def sessionLimitsFor(phase: SessionPhase): SessionLimits =
    val settleSec = phase match
      case SessionPhase.Spec => config.settle.specTimeoutSec
      case SessionPhase.DesignRevision => config.settle.designRevisionTimeoutSec
      case SessionPhase.Implement => config.settle.implementTimeoutSec
      case SessionPhase.Fixup => config.settle.fixupTimeoutSec
      case _ => config.settle.implementTimeoutSec
    SessionLimits(
      settleTimeout = settleSec.seconds,
      maxTurnCostUsd = BigDecimal(config.maxTurnCostUsd),
      maxPieceCostUsd = Some(BigDecimal(config.maxPieceCostUsd)),
      maxFeatureCostUsd = Some(BigDecimal(config.maxFeatureCostUsd))
    )

  private def isLoopTerminal(state: FsmState): Boolean = state match
    case _: FsmState.NeedsHumanIntervention | FsmState.FeatureDone | _: FsmState.Abandoned => true
    case _ => false

object Orchestrator:
  /** Result of [[Orchestrator.applyUserCommand]] (Task 1.4.13 M5 / M8). */
  enum CommandOutcome:
    /** The command applied; the feature was then driven to this loop-terminal state. */
    case Driven(terminal: Feature)

    /** The command did not apply in the current state; nothing was mutated. `reason` is operator-facing — e.g. a `forge
      * resume` flag that names a different hint than the one the `NeedsHumanIntervention` carries, or a `forge abandon`
      * against an already-terminal feature.
      */
    case Rejected(currentState: FsmState, reason: String)

/** Loop-local tag for which source produced the racing event — drives the source-driven session-clear rule. */
private enum RaceResult:
  case FromMonitor(outcome: MonitorOutcome)
  case FromWatcher(event: FsmEvent)
  case FromReviewer(event: FsmEvent)
  case FromUser(cmd: UserCommand)
