package io.forge.app.orchestrator

import cats.effect.{IO, Ref}
import io.forge.agents.{
  ConventionLearnerInput,
  DesignReview,
  FailureClassifierInput,
  ObservedFailure,
  PrReview,
  RefineOutcome as AgentRefineOutcome,
  RefineResult,
  ReviewVerdict
}
import io.forge.app.config.ForgeConfig
import io.forge.app.monitor.{MonitorOutcome, MonitorReport, SessionLimits, SessionMonitor}
import io.forge.app.reviewer.{ReviewerCall, ReviewerLimits, ReviewerOutcome}
import io.forge.core.{CiPolicy, PieceId, PrNumber}
import io.forge.core.cost.{Cost, CostTotals}
import io.forge.core.fsm.{
  Feature,
  Fsm,
  FsmConfig,
  FsmEvent,
  FsmState,
  ResumeHint,
  SessionPhase,
  SettleOutcome,
  UserCommand
}
import io.forge.core.log.ActionDraft
import io.forge.core.manifest.{ManifestPatch, ManifestStore}
import io.forge.core.pr.{PrState, ReviewDecision}
import io.forge.core.profile.{
  CommandKind,
  ConventionDeltas,
  ConventionsLearnedAction,
  Determinism,
  FailureClassifiedAction,
  FailureKind,
  FixupRoute,
  ProfileSnapshot,
  ProfileStore,
  RepoCommand,
  RepoProfile,
  RuleBasedFailureClassifier
}
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}
import io.forge.core.state.{RebuildState, StateCache}
import io.forge.git.branch.protection.{OverlaySource, RequiredChecksOverlay}
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
/** §8 per-`PieceAwaitingCi`-visit CI-gate state, local to one `pieceCiWatcherIO` call: `gateEnteredAt` is the monotonic
  * stamp of the first poll (the discovery-window anchor), and `disc` carries `CiReadiness`'s consecutive-green count.
  */
private final case class CiGate(
    gateEnteredAt: Option[FiniteDuration],
    disc: CiDiscoveryState,
    autofixApplied: Boolean = false
)
private object CiGate:
  val initial: CiGate = CiGate(None, CiDiscoveryState.initial)

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
    userInput: IO[UserCommand] = IO.never,
    profileStore: ProfileStore = ProfileStore.none
):

  private val fsmConfig = FsmConfig(config.maxDesignReviewRounds, config.maxFixupRounds)

  /** §8.2 deterministic failure router over the Tier-1 rules classifier. Consulted on a CI-gate failure when a profile
    * is present and `adapt.enabled` (§11.5); an unprofiled / disabled run keeps the 1.6 blind fix-up.
    */
  private val failureRouter = new FailureRouter(new RuleBasedFailureClassifier)

  /** v1 reviewer wall-clock cap (Task 1.4.7). The per-method config knob is carry-forward **S4-5** (Task 1.4.9
    * ForgeConfig deferral); until then the 3-minute cap is hard-wired here as it is in the reviewer-call wiring.
    */
  private val reviewerWallClock: FiniteDuration = 3.minutes

  /** §8 CI-readiness policy (§18 `ci.policy`). An unparseable value defaults to the §18 default rather than failing the
    * run — the config layer (Task 1.4.9) owns validation.
    */
  private val ciPolicy: CiPolicy =
    CiPolicy.fromString(config.ci.policy).getOrElse(CiPolicy.BranchProtectionThenObserved)

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
        val (feature0, drafts0) = RestartRecovery.recover(rebuilt.feature, rebuilt.inFlightSessions, fsmConfig)
        // §15 / TerminalReport: a few NHI hints are documented to "continue with `forge run`"
        // (ResolveLocalImplementationChanges, ApplyPlanningUpdate) — there is no `forge resume --flag` for them.
        // Apply the stored hint's Resume here so `forge run` honours that contract; the flag-gated hints
        // (after-human-push / commit-human-fix / run-fixup) and the manual ones (ReopenDesign → `forge spec`,
        // AbortOrAbandon → `forge abandon`) are left for their explicit commands.
        val (feature1, drafts1) = resumeIfRunRecoverable(feature0)
        val drafts = drafts0 ++ drafts1
        val recoverDrafts = if drafts.nonEmpty then persistTransition(feature1, drafts) else IO.unit
        // §11.0: bind the run to the committed RepoProfile (snapshot its hash into the log) before the first
        // transition, so a replay uses the profile-as-of-this-run. Emitted once per run; an unprofiled / disabled run
        // skips it and behaves exactly as 1.6.
        // Post-settle recovery (roadmap §3.5 Unit B): a piece driver that settled clean but crashed before its
        // transition persisted is recovered by re-running the idempotent §11 side effect, NOT re-spawned. Mutually
        // exclusive with the in-flight path above (the same tail spawn is either marked settled or not), so when
        // `RestartRecovery` routed an in-flight session to NHI this is a no-op.
        recoverDrafts >> resolveProfile.flatMap { profile =>
          snapshotProfile(featureId, profile) >>
            postSettleRecover(feature1, rebuilt.settledButUnadvanced, profile).flatMap(driveWith(_, profile))
        }
    }

  /** §11.0 — resolve the committed profile **once** per run (Task 3.0.2). `None` when `adapt.enabled` is false (pure
    * 1.6 behaviour) or the repo is unprofiled; a malformed `profile.json` propagates as an error per §6.5 (it is
    * committed source of truth, not a rebuildable cache). The resolved value is threaded as a **read-only input** to
    * the §8.2 router (see [[routeCiFailure]]) — `Fsm.transition` never reads `ProfileStore`, so a replay reproduces the
    * run without consulting any profile (the replayability invariant; `profile.snapshot` / `profile.failure_classified`
    * are audit-only no-op projections in `Replay`).
    */
  private def resolveProfile: IO[Option[RepoProfile]] =
    if !config.adapt.enabled then IO.pure(None) else profileStore.load()

  /** §11.0 — append a `profile.snapshot` binding this run to the resolved profile's `contentHash`, so a replay uses the
    * profile-as-of-this-run. A `None` (unprofiled / `adapt.enabled = false`) is a no-op (1.6).
    */
  private def snapshotProfile(featureId: io.forge.core.FeatureId, profile: Option[RepoProfile]): IO[Unit] =
    profile match
      case Some(p) => log.append(featureId, ProfileSnapshot.draft(featureId, p)).void
      case None => IO.unit

  /** roadmap §3.5 Unit B — the effectful post-settle recovery step. For a [[RebuildState.SettledSession]] whose driver
    * phase maps to a recoverable piece-driver effect (`ClassifyCommitOpenPr` / `ClassifyCommitPush`), re-run that
    * idempotent side effect and apply the synthesized event, advancing the FSM past the settle without re-spawning the
    * driver (Unit A made `classifyCommitOpenPr` idempotent — it reuses the open PR for the branch; `classifyCommitPush`
    * is naturally idempotent). A settled-but-unadvanced session for any other phase is impossible (the writer only
    * marks `Implement` / `Fixup` settles), but is routed conservatively to NHI rather than falling through to the loop
    * — which would re-spawn the driver, strictly worse than NHI.
    *
    * `PostSettleSynthesis.plan` is consulted only for its (state, phase)-keyed effect; the synthesized event comes from
    * `runSettleEffect` (the real `PrOpened` with the looked-up PR number), exactly as the live settle path does. At
    * most one settled session exists (single-driver), so `headOption` is total.
    */
  private[orchestrator] def postSettleRecover(
      feature: Feature,
      settled: Vector[RebuildState.SettledSession],
      profile: Option[RepoProfile]
  ): IO[Feature] =
    settled.headOption match
      case None => IO.pure(feature)
      case Some(s) =>
        PostSettleSynthesis.plan(feature.state, MonitorOutcome.Settled(s.phase, SettleOutcome.Clean)).effect match
          case eff @ (SettleEffect.ClassifyCommitOpenPr | SettleEffect.ClassifyCommitPush) =>
            runSettleEffect(feature, eff, profile).flatMap(applyAndPersist(feature, _))
          case _ =>
            applyAndPersist(
              feature,
              FsmEvent.HarnessError(
                s"post-settle recovery: unexpected settled-but-unadvanced ${s.phase} session in ${feature.state}"
              )
            )

  /** §15: `forge run` resumes the NHI hints whose [[io.forge.app.command.TerminalReport.recovery]] names `forge run` as
    * the recovery (the others have explicit `forge resume --flag` / `forge spec` / `forge abandon` paths). Applied once
    * at startup; if the resumed work re-NHIs, `drive` stops at the new terminal and the next `forge run` retries.
    */
  private def resumeIfRunRecoverable(feature: Feature): (Feature, Vector[io.forge.core.log.ActionDraft]) =
    feature.state match
      case FsmState.NeedsHumanIntervention(_, hint) if Orchestrator.runRecoverableHint(hint) =>
        Fsm.transition(feature, FsmEvent.UserCommandReceived(UserCommand.Resume(hint)), fsmConfig)
      case _ => (feature, Vector.empty)

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
    * act on at all (the whole `Feature` is unchanged AND no drafts) is surfaced as a
    * [[Orchestrator.CommandOutcome.Rejected]] rather than silently driving an unchanged feature.
    *
    * The no-op test compares the **whole** feature, not just `state`: `forge refresh-cache`
    * (`UserCommand.RefreshCache`, M7) bumps `branchProtectionCacheEpoch` without a lifecycle transition or any drafts,
    * so a `state`-only check would wrongly reject it as a no-op. resume/abandon always change `state` or emit drafts,
    * so this is behaviour-preserving for them.
    */
  private[orchestrator] def applyUserCommandTo(
      feature0: Feature,
      deriveCommand: Feature => Either[String, UserCommand]
  ): IO[Orchestrator.CommandOutcome] =
    deriveCommand(feature0) match
      case Left(reason) => IO.pure(Orchestrator.CommandOutcome.Rejected(feature0.state, reason))
      case Right(cmd) =>
        val (f1, drafts) = Fsm.transition(feature0, FsmEvent.UserCommandReceived(cmd), fsmConfig)
        if f1 == feature0 && drafts.isEmpty then
          IO.pure(
            Orchestrator.CommandOutcome.Rejected(feature0.state, s"command had no effect in state ${feature0.state}")
          )
        else persistTransition(f1, drafts) >> drive(f1).map(Orchestrator.CommandOutcome.Driven.apply)

  /** Drive a feature from the given state to a loop-terminal state. Exposed for the J5 e2e suites, which construct the
    * starting `Feature` directly rather than seeding a full action-log history through `run`. Resolves the profile
    * itself (Task 3.0.2) so a direct-`drive` caller routes against the committed profile exactly as `run` does; `run`
    * uses [[driveWith]] directly to reuse the profile it already resolved for the `profile.snapshot`.
    */
  private[orchestrator] def drive(feature: Feature): IO[Feature] =
    resolveProfile.flatMap(driveWith(feature, _))

  /** [[drive]] with the run's already-resolved profile threaded in as a read-only input to the §8.2 router. */
  private def driveWith(feature: Feature, profile: Option[RepoProfile]): IO[Feature] =
    if isLoopTerminal(feature.state) then IO.pure(feature)
    else
      for
        driverRef <- Ref.of[IO, Option[ActiveSession]](None)
        totalsRef <- Ref.of[IO, CostTotals](CostTotals.zero)
        out <- loop(feature, profile, driverRef, totalsRef, justEntered = true)
        // §11.7: on the transition to FeatureDone, consult the ConventionLearner out of band. Runs *after* the loop
        // reached its terminal, never gating it; `maybeLearnConventions` no-ops unless `out` is FeatureDone.
        _ <- maybeLearnConventions(out, profile)
      yield out

  // ---------------------------------------------------------------------------
  // §11.7 ConventionLearner (out of band, advisory)
  // ---------------------------------------------------------------------------

  /** §11.7 — out-of-band `ConventionLearner` on the transition to `FeatureDone` (Task 3.2). Advisory and
    * **never-blocking** (the §14.2 refinery posture): the feature has already reached `FeatureDone` deterministically;
    * this runs after the loop and any failure is logged-and-dropped (`handleErrorWith`) — it never turns a done feature
    * into a crash, and `Fsm.transition` never observes it (the profile delta reaches a later run only as a committed
    * `.forge/profile.json` input, preserving replayability).
    *
    * Gated on `adapt.enabled` + `adapt.conventionLearner` + a resolved profile (an unprofiled / disabled run has no
    * profile to delta against, and emits no `profile.failure_classified` signal anyway). The §7.11 **cost lever**: the
    * sensor is consulted **only** when the feature actually hit a classified gate failure
    * ([[Orchestrator.observedFailures]] over the log) — a clean run learns nothing and spends no reviewer call. On a
    * settled proposal: fresh command deltas merge into the committed profile via `ProfileStore.save` (a
    * human-reviewable diff), the proposed CLAUDE.md edit is persisted to the feature's audit dir for human review (**no
    * autonomous doc mutation / no PR** — the auto-PR-open is a deferred follow-up, design-3.0 §4), and a
    * `profile.conventions_learned` audit action records what was proposed.
    */
  private def maybeLearnConventions(feature: Feature, profile: Option[RepoProfile]): IO[Unit] =
    val body = (feature.state, profile) match
      case (FsmState.FeatureDone, Some(p)) if config.adapt.enabled && config.adapt.conventionLearner =>
        log.replay(feature.id).map(Orchestrator.observedFailures).flatMap { failures =>
          if failures.isEmpty then IO.unit
          else
            readClaudeDoc.flatMap { claudeDoc =>
              val input = ConventionLearnerInput(feature.id, p, claudeDoc, failures)
              reviewer.learnConventions(input, ReviewerLimits(reviewerWallClock)).flatMap {
                case ReviewerOutcome.Settled(deltas) => applyConventionDeltas(feature.id, p, deltas)
                case _ => IO.unit // Timeout / AdapterFailure — advisory, dropped (§14.2)
              }
            }
        }
      case _ => IO.unit
    body.handleErrorWith(_ => IO.unit)

  /** Apply a settled [[ConventionDeltas]] proposal: merge fresh command deltas into the committed profile (skipping the
    * `save` when nothing is fresh — see [[ConventionDeltas.freshCommands]]), persist any proposed CLAUDE.md edit for
    * human review, and record the `profile.conventions_learned` audit action regardless (so the run shows the learner
    * was consulted, even when it proposed nothing actionable).
    */
  private def applyConventionDeltas(
      featureId: io.forge.core.FeatureId,
      profile: RepoProfile,
      deltas: ConventionDeltas
  ): IO[Unit] =
    val fresh = deltas.freshCommands(profile)
    val saveProfile = if fresh.isEmpty then IO.unit else profileStore.save(deltas.applyTo(profile))
    val writeProposal = deltas.claudeMdProposal match
      case Some(proposal) => writeConventionProposal(featureId, proposal, deltas.summary)
      case None => IO.unit
    saveProfile >>
      writeProposal >>
      log
        .append(
          featureId,
          ConventionsLearnedAction.draft(featureId, fresh, deltas.claudeMdProposal.isDefined, deltas.summary)
        )
        .void

  /** Persist the proposed CLAUDE.md addition to the feature's committed audit dir (`.forge/specs/<feature>/audit/`) for
    * human review. **Never** edits CLAUDE.md or opens a PR (§11.7 "no autonomous doc mutation"; the auto-PR-open is a
    * deferred follow-up, design-3.0 §4).
    */
  private def writeConventionProposal(
      featureId: io.forge.core.FeatureId,
      proposal: io.forge.core.profile.ClaudeMdProposal,
      summary: String
  ): IO[Unit] =
    IO.blocking {
      val target = paths.audit(featureId, "learned-conventions.md")
      os.makeDir.all(target / os.up)
      val md =
        s"""# Proposed CLAUDE.md convention (forge ConventionLearner)
           |
           |_Proposed for **${featureId.value}** — review and merge into CLAUDE.md by hand; Forge does not open this PR itself (§11.7)._
           |
           |## Summary
           |$summary
           |
           |## Rationale
           |${proposal.rationale}
           |
           |## Suggested addition
           |${proposal.suggestedAddition}
           |""".stripMargin
      os.write.over(target, md)
    }

  /** The repo's current CLAUDE.md, threaded to the learner so a proposed addition is phrased relative to what the doc
    * already says. `None` when the repo has no CLAUDE.md.
    */
  private def readClaudeDoc: IO[Option[String]] =
    IO.blocking {
      val f = paths.repoRoot / "CLAUDE.md"
      if os.exists(f) && os.isFile(f) then Some(os.read(f)) else None
    }

  // ---------------------------------------------------------------------------
  // The loop
  // ---------------------------------------------------------------------------

  private def loop(
      feature: Feature,
      profile: Option[RepoProfile],
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals],
      justEntered: Boolean
  ): IO[Feature] =
    if isLoopTerminal(feature.state) then IO.pure(feature)
    else
      val entry =
        if justEntered then runEntryHook(feature, driverRef, totalsRef) else IO.pure(Option.empty[FsmEvent])
      entry.flatMap {
        // An entry-hook-synthesized event: apply, persist, then re-run the entry hook (steps chain).
        case Some(event) =>
          applyAndPersist(feature, event).flatMap(f1 => loop(f1, profile, driverRef, totalsRef, justEntered = true))
        // No entry event: race the selected sources, then recurse with justEntered = state-changed.
        case None =>
          driverRef.get.flatMap { active =>
            val sources = EventSources.select(feature.state, active)
            if sources.isEmpty then
              IO.raiseError(new IllegalStateException(s"orchestrator: no event sources for state ${feature.state}"))
            else
              raceAll(sources.map(sourceIO(feature, active, _, profile, totalsRef)))
                .flatMap(winner => handleWinner(feature, winner, driverRef, totalsRef, profile))
                .flatMap(f1 => loop(f1, profile, driverRef, totalsRef, justEntered = f1.state != feature.state))
          }
      }

  /** Apply one event through the pure FSM and persist atomically; returns the resulting feature. `extraDrafts` are
    * prepended to the transition's own drafts so they land in the **same** atomic append as the transition (a crash
    * after the transition still has them on record — Slice 2.0 cost/session audit drafts rely on this ordering).
    */
  private def applyAndPersist(
      feature: Feature,
      event: FsmEvent,
      extraDrafts: Vector[io.forge.core.log.ActionDraft] = Vector.empty
  ): IO[Feature] =
    val (f1, drafts) = Fsm.transition(feature, event, fsmConfig)
    persistTransition(f1, extraDrafts ++ drafts).as(f1)

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
  private def runEntryHook(
      feature: Feature,
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals]
  ): IO[Option[FsmEvent]] =
    feature.state match
      case FsmState.Drafting =>
        sideEffects.launchSpec(feature).flatMap(as => store(driverRef, totalsRef, as).as(Some(spawned(as, None))))

      case FsmState.InteractiveSpec =>
        driverRef.get.flatMap {
          case Some(_) => IO.pure(None)
          case None => sideEffects.launchSpec(feature).flatMap(as => store(driverRef, totalsRef, as).as(None))
        }

      case s: FsmState.DesignReviewing =>
        // round == 1 → fresh review (reviewer source); round > 1 → resume the revision driver after request_changes.
        driverRef.get.flatMap {
          case None if s.round > 1 =>
            sideEffects
              .resumeDesignRevision(feature, s.round)
              .flatMap(as => store(driverRef, totalsRef, as).as(Some(resumed(as, feature.designSessionId, None))))
          case _ => IO.pure(None)
        }

      case s: FsmState.DesignPrFeedback =>
        driverRef.get.flatMap {
          case None =>
            sideEffects
              .resumeDesignFeedback(feature, s.prNumber, s.round)
              .flatMap(as => store(driverRef, totalsRef, as).as(Some(resumed(as, feature.designSessionId, None))))
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
            case Some(_) => IO.pure(None)
            // D3-3 (roadmap §3.5): a durable `currentPieceSessionId` with no live driver means a process restart hit
            // mid-implement (a fresh forward entry always has `currentPieceSessionId = None` — it is cleared on every
            // path into PieceImplementing and set only by the spawn below). Gate on the worktree classifier and resume
            // instead of re-spawning; an unsafe worktree routes to NHI. Without a session id this is the normal first
            // turn → fresh spawn (resetting the per-piece cost accumulator; §6 / §12 — `piece` resets on the next
            // PieceImplementing, but NOT on a same-piece fix-up).
            case None =>
              feature.currentPieceSessionId match
                case Some(sid) => resumePieceDriver(feature, s.p, sid, SessionPhase.Implement, driverRef, totalsRef)
                case None =>
                  totalsRef.update(_.copy(piece = BigDecimal(0))) >>
                    sideEffects
                      .launchImplement(feature, s.p)
                      .flatMap(as => store(driverRef, totalsRef, as).as(Some(spawned(as, Some(s.p)))))
          }

      case s: FsmState.PieceCiFailed =>
        sideEffects
          .launchFixup(feature, s.p, s.attempt)
          .flatMap(as => store(driverRef, totalsRef, as).as(Some(spawned(as, Some(s.p)))))

      case s: FsmState.PieceReviewFailed =>
        sideEffects
          .launchFixup(feature, s.p, s.attempt)
          .flatMap(as => store(driverRef, totalsRef, as).as(Some(spawned(as, Some(s.p)))))

      case s: FsmState.PieceFixingUp =>
        driverRef.get.flatMap {
          case Some(_) => IO.pure(None)
          // D3-3: same restart resume-gate as PieceImplementing (fix-up-phase). A durable session id + no live driver
          // ⇒ resume; otherwise fresh `launchFixup`.
          case None =>
            feature.currentPieceSessionId match
              case Some(sid) => resumePieceDriver(feature, s.p, sid, SessionPhase.Fixup, driverRef, totalsRef)
              case None =>
                sideEffects
                  .launchFixup(feature, s.p, s.attempt)
                  .flatMap(as => store(driverRef, totalsRef, as).as(Some(spawned(as, Some(s.p)))))
        }

      // Watcher / reviewer / user-Q&A states have no entry side effect: fall through to the race.
      case _ => IO.pure(None)

  /** D3-3 (roadmap §3.5 driver-respawn-avoidance) — the restart resume-gate for a piece driver, default-on once the
    * D3-2 classifier reports the worktree safe (the 2026-06-01 decision). The classifier carries the safety burden:
    *   - `Clean` / `DriverUncommittedOnly` (safe) → resume the existing session via the D3-1 seam
    *     ([[SideEffects.resumeImplement]] / [[SideEffects.resumeFixup]]) instead of re-spawning and re-paying the
    *     exploration (gap #10). The resulting `SessionResumed` projects `currentPieceSessionId` + emits the §19
    *     `<actor>.resume` durability entry (so a *second* restart resumes again, idempotently).
    *   - `UnexpectedDivergence` (committed/operator work, detached state) or a `Left` (git read failed / no branch) → a
    *     `HarnessError`, which routes to NHI with the same phase hint the old unconditional-NHI restart produced
    *     (`ResolveLocalImplementationChanges` / `RunAnotherFixup`).
    */
  private def resumePieceDriver(
      feature: Feature,
      piece: PieceId,
      sessionId: String,
      phase: SessionPhase,
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals]
  ): IO[Option[FsmEvent]] =
    sideEffects.classifyPieceWorktree(feature, piece).flatMap {
      case Right(safety) if safety.safeToResume =>
        val resume = phase match
          case SessionPhase.Fixup => sideEffects.resumeFixup(feature, piece, sessionId)
          case _ => sideEffects.resumeImplement(feature, piece, sessionId)
        resume.flatMap(as => store(driverRef, totalsRef, as).as(Some(resumed(as, Some(sessionId), Some(piece)))))
      case Right(unsafe) =>
        IO.pure(
          Some(
            FsmEvent.HarnessError(
              s"resume gate: ${piece.value} worktree is $unsafe on restart; routing to manual intervention"
            )
          )
        )
      case Left(reason) =>
        IO.pure(Some(FsmEvent.HarnessError(s"resume gate: $reason")))
    }

  /** A driver turn begins here: store the freshly spawned/resumed session and reset the per-turn cost accumulator to
    * zero. The orchestrator owns this reset per the [[io.forge.app.monitor.SessionMonitor]] contract — the monitor only
    * ever *adds* each `CostUpdate` delta. Without the reset, `CostTotals.turn` accrues across turns and the §12
    * per-turn cap (`maxTurnCostUsd`) effectively checks cumulative feature spend rather than this turn's. Per-piece
    * reset is the caller's responsibility (only a *new* piece's first turn resets `piece`; a fix-up turn on the same
    * piece keeps accumulating).
    */
  private def store(
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals],
      as: ActiveSession
  ): IO[Unit] =
    driverRef.set(Some(as)) >> totalsRef.update(_.copy(turn = BigDecimal(0)))

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
      profile: Option[RepoProfile],
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

      case EventSource.Watcher(pr) => watcherIO(feature, pr, profile)
      case EventSource.Reviewer(method) => reviewerIO(feature, method)
      case EventSource.Repl => IO.never // bidirectional `forge spec` REPL — Task 1.4.13
      case EventSource.UserQa => userInput.map(RaceResult.FromUser.apply)

  private def watcherIO(feature: Feature, pr: PrNumber, profile: Option[RepoProfile]): IO[RaceResult] =
    // §8: PieceAwaitingCi runs the CI-readiness gate (overlay promotion + discovery window + stableGreenPolls); the
    // other watcher states consume one poll snapshot and hand it straight to the FSM.
    feature.state match
      case s: FsmState.PieceAwaitingCi => pieceCiWatcherIO(feature, s.p, pr, profile)
      case _ => plainWatcherIO(feature, pr)

  private def plainWatcherIO(feature: Feature, pr: PrNumber): IO[RaceResult] =
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

  /** §8 CI-readiness gate for a piece PR. Self-contained sub-loop: the gate clock + consecutive-green count live in one
    * `Ref[CiGate]` **local to this call**, so they reset by construction on every re-entry to `PieceAwaitingCi` (e.g.
    * after a fix-up re-push). The count MUST stay local — a `loop`-threaded ref could carry a stale green count across
    * a fix-up and declare premature readiness. Elapsed time uses `IO.monotonic` (deterministic under `TestControl`).
    * The overlay is fetched once per gate visit (it is cache-first inside `BranchManager`).
    */
  private def pieceCiWatcherIO(
      feature: Feature,
      piece: PieceId,
      pr: PrNumber,
      profile: Option[RepoProfile]
  ): IO[RaceResult] =
    sideEffects.requiredChecksOverlay(feature).flatMap {
      case Left(reason) =>
        IO.pure(RaceResult.FromWatcher(FsmEvent.HarnessError(s"required-checks overlay failed: $reason")))
      case Right(overlay) =>
        val auditUnauthorized =
          if overlay.source == OverlaySource.Unauthorized then
            log.append(feature.id, protectionUnauthorizedDraft(feature, piece, pr)).void
          else IO.unit
        for
          _ <- auditUnauthorized
          seed <- baselineStore.load(feature.id, pr)
          baselineRef <- Ref.of[IO, PollBaseline](seed)
          gateRef <- Ref.of[IO, CiGate](CiGate.initial)
          ev <- watcher
            .watch(pr, baselineRef)
            .evalTap {
              case _: PollResult.Snapshot => baselineRef.get.flatMap(baselineStore.save(feature.id, pr, _))
              case _ => IO.unit
            }
            .evalMap(ciPollToEvent(feature, piece, pr, overlay, gateRef, profile, _))
            .unNone
            .head
            .compile
            .lastOrError
        yield RaceResult.FromWatcher(ev)
    }

  private def ciPollToEvent(
      feature: Feature,
      piece: PieceId,
      pr: PrNumber,
      overlay: RequiredChecksOverlay,
      gateRef: Ref[IO, CiGate],
      profile: Option[RepoProfile],
      result: PollResult
  ): IO[Option[FsmEvent]] =
    result match
      case PollResult.RateLimited(_) => IO.pure(None) // the watch stream absorbs back-off (S3-4)
      case PollResult.Failed(err) => IO.pure(Some(FsmEvent.HarnessError(s"PR poll failed: $err")))
      case PollResult.Snapshot(decoded) =>
        IO.monotonic.flatMap { now =>
          gateRef.get.flatMap { gate =>
            val enteredAt = gate.gateEnteredAt.getOrElse(now)
            val elapsed = now - enteredAt
            CiReadiness.evaluate(ciPolicy, config.ci, overlay, decoded.snapshot, gate.disc, elapsed) match
              case CiDecision.KeepPolling(next) =>
                gateRef.set(gate.copy(gateEnteredAt = Some(enteredAt), disc = next)).as(None)
              case CiDecision.Forward(rollup) =>
                if CiReadiness.isFailure(rollup) then
                  routeCiFailure(feature, piece, pr, rollup, decoded.snapshot, gateRef, gate, profile)
                else
                  val auditSkipped =
                    if ciPolicy == CiPolicy.None then log.append(feature.id, ciSkippedDraft(feature, piece, pr)).void
                    else IO.unit
                  auditSkipped.as(
                    Some(FsmEvent.PrSnapshotUpdated(piece, decoded.snapshot.copy(requiredChecks = rollup)))
                  )
              case CiDecision.Blocked(reason) =>
                IO.pure(Some(FsmEvent.CiReadinessBlocked(piece, pr, reason)))
          }
        }

  /** §8.2 classified routing on a required-check failure. Falls back to the 1.6 blind fix-up (emit the Failed
    * `PrSnapshotUpdated` so the FSM does `attempts += 1` → `PieceCiFailed`) when the run is unprofiled, `adapt` is
    * disabled, the failing run id can't be recovered, or the log fetch fails — Phase 3 never blocks an unprofiled repo.
    * When it does route, it appends `profile.failure_classified` (§19) **before** acting, so replay reuses the recorded
    * perception.
    *
    *   - `RunLocalCommand` → run the autofix, amend+push, keep polling (NO `attempts` increment). Guarded by
    *     `gate.autofixApplied` so a failure that recurs after the autofix degrades to a driver fix-up rather than
    *     looping; an autofix that fails / changes nothing degrades likewise.
    *   - `DriverFixup` → the existing Failed path (`attempts += 1`); the full failing log is in `<p>.failures.md`.
    *   - `Retry` / `BackOff` → keep polling (no `attempts` increment).
    *   - `Escalate` → `CiReadinessBlocked` → NeedsHumanIntervention.
    */
  private def routeCiFailure(
      feature: Feature,
      piece: PieceId,
      pr: PrNumber,
      rollup: io.forge.core.pr.CheckRollup,
      snapshot: io.forge.core.pr.PrSnapshot,
      gateRef: Ref[IO, CiGate],
      gate: CiGate,
      profile: Option[RepoProfile]
  ): IO[Option[FsmEvent]] =
    val blindFixup: IO[Option[FsmEvent]] =
      IO.pure(Some(FsmEvent.PrSnapshotUpdated(piece, snapshot.copy(requiredChecks = rollup))))
    val runId = CiReadiness.firstFailingCheck(rollup).flatMap(_.runId)
    // `profile` is the run's read-only input (Task 3.0.2): `None` when unprofiled OR `adapt.enabled = false`
    // (`resolveProfile` folds the disable into a `None`), so the blind 1.6 fix-up covers both.
    profile match
      case None => blindFixup
      case Some(p) =>
        runId match
          case None => blindFixup // no Actions run id on the failing check — can't fetch the log; route blind.
          case Some(id) =>
            sideEffects.fetchFailingLog(id).flatMap {
              case Left(_) => blindFixup // gh fetch failed; don't strand the failure — route blind.
              case Right(failureLog) =>
                maybeLlmClassify(feature, failureLog, failureRouter.route(p, failureLog, config.adapt), p).flatMap {
                  routed =>
                    val classifiedDraft =
                      FailureClassifiedAction
                        .draft(feature.id, piece, "ci", routed.classification, routed.route, routed.source)
                    log.append(feature.id, classifiedDraft) >> act(
                      feature,
                      piece,
                      pr,
                      snapshot,
                      rollup,
                      gateRef,
                      gate,
                      routed.route
                    )
                }
            }

  /** §7.11 cost lever (Task 3.1.4) — when the deterministic rules classifier could not pin the failure (its
    * `Classification` is `Unknown`, which routes to `Escalate`) and `adapt.llmClassifierOnUnknown` is set, consult the
    * LLM `classifyFailure` sensor and re-route from its proposal (recording `source = "llm"`). The rules baseline
    * handles every dogfood case for free; the LLM tail pays a reviewer-call **only** for the genuinely ambiguous
    * failure. Degrades safely: a [[ReviewerOutcome.Timeout]] / adapter / process failure keeps the rules `Escalate` —
    * Forge never blocks a feature on a stalled sensor (it falls through to the safe human-escalation the rules already
    * chose). When the LLM itself returns `Unknown`, that too routes to `Escalate`, now stamped `source = "llm"` so the
    * §19 audit shows the sensor was consulted.
    */
  private def maybeLlmClassify(
      feature: Feature,
      failureLog: String,
      rulesRouted: RoutedFailure,
      profile: RepoProfile
  ): IO[RoutedFailure] =
    if !config.adapt.llmClassifierOnUnknown || rulesRouted.classification.kind != FailureKind.Unknown then
      IO.pure(rulesRouted)
    else
      val input = FailureClassifierInput(feature.id, gate = "ci", failureLog = failureLog, profile = profile)
      reviewer.classifyFailure(input, ReviewerLimits(reviewerWallClock)).map {
        case ReviewerOutcome.Settled(llm) =>
          failureRouter.routeFrom(llm, profile, failureLog, config.adapt, source = "llm")
        case _ => rulesRouted // timeout / adapter / process failure → keep the rules Escalate (don't block on a stall).
      }

  /** Act on the deterministic [[FixupRoute]] (the §8.2 row table). `blindFixup`-shaped events re-use the existing
    * `PrSnapshotUpdated` → Failed path so the FSM owns `attempts`; `RunLocalCommand` / `Retry` / `BackOff` never touch
    * `attempts` (they return `None`, keeping the watch stream polling in-place after the side effect).
    */
  private def act(
      feature: Feature,
      piece: PieceId,
      pr: PrNumber,
      snapshot: io.forge.core.pr.PrSnapshot,
      rollup: io.forge.core.pr.CheckRollup,
      gateRef: Ref[IO, CiGate],
      gate: CiGate,
      route: FixupRoute
  ): IO[Option[FsmEvent]] =
    val driverFixup: IO[Option[FsmEvent]] =
      IO.pure(Some(FsmEvent.PrSnapshotUpdated(piece, snapshot.copy(requiredChecks = rollup))))
    route match
      case FixupRoute.RunLocalCommand(command) =>
        if gate.autofixApplied then driverFixup // already autofixed once this gate visit; the failure recurred.
        else
          sideEffects.runLocalAutofixAndPush(feature, piece, command).flatMap {
            case Right(()) =>
              // No FSM transition: the amend+push re-triggers CI; keep polling this same gate (attempts untouched).
              gateRef.update(_.copy(autofixApplied = true)).as(None)
            case Left(_) => driverFixup // autofix failed / changed nothing — let a driver fix it (with the full log).
          }
      case _: FixupRoute.DriverFixup => driverFixup
      case FixupRoute.Retry => IO.pure(None)
      case _: FixupRoute.BackOff => IO.pure(None)
      case FixupRoute.Escalate(reason) => IO.pure(Some(FsmEvent.CiReadinessBlocked(piece, pr, reason)))

  // ---------------------------------------------------------------------------
  // §8.3 local format gate (shift-left, pre-PR — Task 3.1.3)
  // ---------------------------------------------------------------------------

  /** §8.3 — run the profile's `required` deterministic in-place `Format` autofix commands on the working tree BEFORE
    * the §11.4-step-6 piece commit (the [[runSettleEffect]] `ClassifyCommitOpenPr` step), so the driver's
    * non-conformant output is rewritten in place and the piece commit is format-clean before it ever reaches CI
    * (dogfood #3, zero round-trip). Records a `profile.local_gate` audit action (a no-op `Replay` projection, like
    * `ci.skipped`) when the gate runs. Best-effort and additive: the [[SideEffects.runLocalFormatGate]] result is
    * ignored (`.void`) — a formatter that fails / changes nothing leaves the tree as-is and the commit proceeds exactly
    * as 1.6 (the failure, if real, then surfaces at the CI gate and routes via §8.2). An empty command set (unprofiled
    * / `adapt.localGate` off / no `Format` command) is a no-op, so the unprofiled path is byte-identical to 1.6.
    */
  private def runLocalFormatGate(feature: Feature, piece: PieceId, profile: Option[RepoProfile]): IO[Unit] =
    localFormatGateCommands(profile) match
      case Vector() => IO.unit
      case commands =>
        log.append(feature.id, localGateDraft(feature, piece, commands)) >>
          sideEffects.runLocalFormatGate(feature, piece, commands).void

  /** Pure resolver — the profile's `required`, `Deterministic`, in-place `autofix` `Format` commands, or empty when the
    * local gate is off (`adapt.localGate` / `adapt.autofix`), the run is unprofiled, or the profile declares no such
    * command (each ⇒ exact 1.6 behaviour). `Heuristic` commands (a test suite) are never run locally (§8.3): the
    * `Deterministic` filter excludes them. `adapt.enabled = false` is already folded into a `None` profile by
    * [[resolveProfile]], so it needs no separate check here.
    */
  private def localFormatGateCommands(profile: Option[RepoProfile]): Vector[RepoCommand] =
    if !config.adapt.localGate || !config.adapt.autofix then Vector.empty
    else
      profile.toVector
        .flatMap(_.commands)
        .filter(c =>
          c.kind == CommandKind.Format && c.required && c.autofix && c.determinism == Determinism.Deterministic
        )

  /** §8.3 / §19 — the local format gate's audit record: which deterministic autofix commands ran pre-PR. A new
    * `profile.*` kind (the 1.7 §19 table enumerates only `profile.snapshot` / `profile.failure_classified`; this gap is
    * filed in design-3.0 §4 for the next revision). A no-op `Replay` projection (default branch), consumed by `forge
    * stats` / the TUI.
    */
  private def localGateDraft(feature: Feature, piece: PieceId, commands: Vector[RepoCommand]): ActionDraft =
    ActionDraft(
      feature.id,
      Some(piece),
      actor = None,
      role = None,
      kind = "profile.local_gate",
      payload = ujson.Obj(
        "gate" -> ujson.Str("local"),
        "kind" -> ujson.Str("format"),
        "commands" -> ujson.Arr(commands.map(c => ujson.Str(c.argv.mkString(" ")))*)
      )
    )

  private def ciSkippedDraft(feature: Feature, piece: PieceId, pr: PrNumber): ActionDraft =
    ActionDraft(
      feature.id,
      Some(piece),
      actor = None,
      role = None,
      kind = "ci.skipped",
      payload = ujson.Obj("prNumber" -> ujson.Num(pr.value.toDouble), "policy" -> ujson.Str("none"))
    )

  /** Slice 2.0 (Tasks 2.0.1 / 2.0.2): the audit drafts written when a driver session settles. The §19 `cost.update`
    * (Task 2.0.1) is emitted only when the turn actually spent (`report.turnCost` is `Some`) — a turn with no cost
    * event has nothing to attribute and the totals are unchanged. The `session.complete` (Task 2.0.2) is emitted for
    * every settle so per-phase timing/attribution is recorded even for a zero-cost or failed turn.
    */
  private def settleAuditDrafts(
      feature: Feature,
      report: MonitorReport,
      totals: CostTotals,
      resumed: Boolean
  ): Vector[ActionDraft] =
    val piece = pieceOf(feature.state)
    val role = roleOf(report.phase)
    report.turnCost.toVector.map(costUpdateDraft(feature, piece, role, totals, _)) :+
      sessionCompleteDraft(feature, report, piece, role, resumed)

  /** §19 `cost.update` — `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd, pieceTotalUsd,
    * turnTotalUsd }`. The three totals are the running scopes off [[CostTotals]] (`Replay.applyCostUpdate` projects
    * them back onto `Feature.cost`); the per-turn `provider/model/tokens/usd` describe this turn's delta. USD amounts
    * are written `.toDouble` to match the `BigDecimal(numOpt)` read path in `Replay.applyCostUpdate`.
    */
  private def costUpdateDraft(
      feature: Feature,
      piece: Option[PieceId],
      role: String,
      totals: CostTotals,
      turn: Cost
  ): ActionDraft =
    ActionDraft(
      feature.id,
      piece,
      actor = Some("driver"),
      role = Some(role),
      kind = "cost.update",
      payload = ujson.Obj(
        "provider" -> ujson.Str(turn.provider),
        "model" -> ujson.Str(turn.model),
        "inputTokens" -> ujson.Num(turn.inputTokens.toDouble),
        "outputTokens" -> ujson.Num(turn.outputTokens.toDouble),
        "usd" -> ujson.Num(turn.usd.toDouble),
        "featureTotalUsd" -> ujson.Num(totals.feature.toDouble),
        "pieceTotalUsd" -> ujson.Num(totals.piece.toDouble),
        "turnTotalUsd" -> ujson.Num(totals.turn.toDouble)
      )
    )

  /** §19 `session.complete` (new in Slice 2.0 Task 2.0.2; `resumed` added D3-4) — `{ phase, piece, durationMs, model,
    * turnCostUsd, success, resumed }`. An audit-only record (a no-op projection in `Replay`); folded by `forge stats`
    * (Task 2.0.3) into per-phase timing and attribution. `durationMs` is the CLI's own turn duration (`None` → `null`
    * on a timeout/kill with no `Result`). `resumed` (D3-4, roadmap §3.5) is `true` when this turn *continued* an
    * existing driver session via `--resume` (the resume side effects) rather than spawning fresh — the discriminator
    * that lets `forge stats` count a resumed turn as the gap-#10 re-exploration-avoided saving.
    */
  private def sessionCompleteDraft(
      feature: Feature,
      report: MonitorReport,
      piece: Option[PieceId],
      role: String,
      resumed: Boolean
  ): ActionDraft =
    ActionDraft(
      feature.id,
      piece,
      actor = Some("driver"),
      role = Some(role),
      kind = "session.complete",
      payload = ujson.Obj(
        "phase" -> ujson.Str(report.phase.toString),
        "piece" -> piece.map(p => ujson.Str(p.value)).getOrElse(ujson.Null),
        "durationMs" -> report.durationMs.map(d => ujson.Num(d.toDouble)).getOrElse(ujson.Null),
        "model" -> ujson.Str(report.turnCost.map(_.model).getOrElse("")),
        "turnCostUsd" -> ujson.Num(report.turnCost.map(_.usd).getOrElse(BigDecimal(0)).toDouble),
        "success" -> ujson.Bool(Orchestrator.outcomeSuccess(report.outcome)),
        "resumed" -> ujson.Bool(resumed)
      )
    )

  /** roadmap §3.5 Unit B — append the `monitor.outcome` marker for a piece-driver post-settle effect
    * (`ClassifyCommitOpenPr` / `ClassifyCommitPush`), the discriminator the cold rebuild uses (`RebuildState`'s
    * `settledButUnadvanced` vs `inFlightSessions`) to route the post-settle crash window to recovery rather than NHI or
    * a silent driver re-spawn. A no-op for every other effect — the design-phase settles keep the conservative
    * in-flight → NHI behaviour, so no marker must exist for them (a marker without recovery would re-spawn the design
    * driver, strictly worse than NHI).
    */
  private def recordSettleMarker(feature: Feature, report: MonitorReport, eff: SettleEffect): IO[Unit] =
    eff match
      case SettleEffect.ClassifyCommitOpenPr | SettleEffect.ClassifyCommitPush =>
        log.append(feature.id, monitorOutcomeDraft(feature, report)).void
      case _ => IO.unit

  /** The §19 `monitor.outcome` audit marker (a no-op `Replay` projection; consumed only by `RebuildState`'s log-tail
    * settle detection). `piece` matches the spawn it closes so the projections' same-piece-key check lines up.
    */
  private def monitorOutcomeDraft(feature: Feature, report: MonitorReport): ActionDraft =
    ActionDraft(
      feature.id,
      pieceOf(feature.state),
      actor = Some("driver"),
      role = Some(roleOf(report.phase)),
      kind = RebuildState.MonitorOutcomeKind,
      payload = ujson.Obj(
        "kind" -> ujson.Str("settled"),
        "phase" -> ujson.Str(report.phase.toString),
        "settle" -> ujson.Str("clean")
      )
    )

  private def protectionUnauthorizedDraft(feature: Feature, piece: PieceId, pr: PrNumber): ActionDraft =
    ActionDraft(
      feature.id,
      Some(piece),
      actor = None,
      role = None,
      kind = "harness.protection_unauthorized",
      payload = ujson.Obj(
        "prNumber" -> ujson.Num(pr.value.toDouble),
        "base" -> ujson.Str(feature.manifest.baseBranch.value)
      )
    )

  private def pollResultToEvent(feature: Feature, result: PollResult): IO[Option[FsmEvent]] =
    result match
      case PollResult.Snapshot(decoded) => watcherEventFor(feature.state, decoded)
      case PollResult.RateLimited(_) => IO.pure(None) // the watch stream absorbs back-off (S3-4)
      case PollResult.Failed(err) => IO.pure(Some(FsmEvent.HarnessError(s"PR poll failed: $err")))

  // PieceAwaitingCi is handled by pieceCiWatcherIO (the §8 gate), never by this generic path. Returns None to keep
  // polling (so a no-op poll doesn't win the source race), Some(event) to drive a transition.
  private def watcherEventFor(state: FsmState, decoded: DecodedSnapshot): IO[Option[FsmEvent]] =
    val snap = decoded.snapshot
    state match
      case _: FsmState.DesignAwaitingMerge => IO.pure(Some(FsmEvent.DesignPrSnapshotUpdated(snap)))
      case s: FsmState.PieceAwaitingReview =>
        // gap #13: in PieceAwaitingReview the reviewer one-shot races this watcher. A no-op snapshot must NOT win
        // (it would cancel the in-flight reviewer every poll → the verdict never arrives). Only emit when the human
        // actually overrode (CHANGES_REQUESTED / unseen comment); otherwise keep polling and let the reviewer finish.
        IO.pure(Orchestrator.pieceReviewWatcherEvent(s.p, snap))
      case s: FsmState.PieceAwaitingMerge =>
        (snap.state, snap.mergeCommit, snap.mergedAt) match
          case (PrState.Merged, Some(mc), Some(ma)) =>
            IO.realTimeInstant.map(now => Some(FsmEvent.Merged(s.p, s.prNumber, mc, ma, now)))
          case _ => IO.pure(Some(FsmEvent.PrSnapshotUpdated(s.p, snap)))
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
      driverRef: Ref[IO, Option[ActiveSession]],
      totalsRef: Ref[IO, CostTotals],
      profile: Option[RepoProfile]
  ): IO[Feature] =
    winner match
      case RaceResult.FromMonitor(report) =>
        // Source-driven session clear: the subprocess is gone whatever the outcome. Slice 2.0: snapshot the running
        // cost totals (the monitor just folded this turn's spend into the shared ref) and draft the §19 `cost.update`
        // + `session.complete` audit records; they are persisted in the SAME atomic append as the settle transition
        // (`applyAndPersist`'s `extraDrafts` / the prepend below), so a crash after the transition still has them.
        driverRef.getAndSet(None).flatMap { active =>
          totalsRef.get.flatMap { totals =>
            val auditDrafts = settleAuditDrafts(feature, report, totals, resumed = active.exists(_.resumed))
            val plan = PostSettleSynthesis.plan(feature.state, report.outcome)
            plan.effect match
              case SettleEffect.None =>
                // Pass-through outcome. Apply the converted event directly; if it no-ops (an unhandled driver settle
                // such as HitQuestionLimit), route to NHI rather than spin on a now-session-less state.
                val raw = passThroughEvent(plan.synthesis, report.outcome)
                val (cand, drafts) = Fsm.transition(feature, raw, fsmConfig)
                if cand.state == feature.state then
                  applyAndPersist(
                    feature,
                    FsmEvent.HarnessError(s"unhandled driver settle in ${feature.state}: ${report.outcome}"),
                    auditDrafts
                  )
                else persistTransition(cand, auditDrafts ++ drafts).as(cand)
              case eff =>
                // Driver settled clean: run the §11 side effect FIRST, then apply the synthesized event. For the
                // post-settle-recoverable piece-driver effects, first durably record a `monitor.outcome` marker (roadmap
                // §3.5 Unit B): a crash anywhere between this settle and the transition persist then routes the cold
                // rebuild to `RebuildState.settledButUnadvanced` (re-run the idempotent side effect — `run`'s
                // `postSettleRecover`) instead of re-spawning the driver from scratch (the D3 exploration cost) or
                // emitting an unactionable NHI. The marker is written as its own append, before the effect, so it is on
                // disk for the whole window.
                recordSettleMarker(feature, report, eff) >>
                  runSettleEffect(feature, eff, profile).flatMap(applyAndPersist(feature, _, auditDrafts))
          }
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

  private def runSettleEffect(feature: Feature, eff: SettleEffect, profile: Option[RepoProfile]): IO[FsmEvent] =
    val resultIO: IO[Either[String, FsmEvent]] = eff match
      case SettleEffect.CoherencePostCheck => sideEffects.coherencePostCheck(feature)
      case SettleEffect.UpdateDesignAssets => sideEffects.updateDesignAssets(feature)
      case SettleEffect.RepushDesignFeedback =>
        feature.state match
          case s: FsmState.DesignPrFeedback => sideEffects.repushDesignFeedback(feature, s.prNumber, s.round)
          case other => IO.pure(Left(s"RepushDesignFeedback in unexpected state $other"))
      case SettleEffect.ClassifyCommitOpenPr =>
        feature.state match
          // §8.3 (Task 3.1.3): run the local format gate on the working tree BEFORE the commit so the driver's output
          // is rewritten in place and the piece commit is format-clean before it reaches CI (dogfood #3). The gate is a
          // no-op when unprofiled / `adapt.localGate` off (exact 1.6 behaviour), so this preserves the unprofiled path.
          case s: FsmState.PieceImplementing =>
            runLocalFormatGate(feature, s.p, profile) >> sideEffects.classifyCommitOpenPr(feature, s.p)
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
      oldSessionId = oldSessionId,
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
  /** The `NeedsHumanIntervention` hints whose `TerminalReport.recovery` directs the operator to `forge run` (they have
    * no `forge resume --flag`): the implement driver left local changes to resolve, or a refinery planning update is
    * approved. `forge run` applies their `Resume` at startup (see `resumeIfRunRecoverable`). The flag-gated hints
    * (`ResumeAfterHumanPush` / `CommitAndPushHumanFix` / `RunAnotherFixup`) and the manual ones (`ReopenDesign` →
    * `forge spec`, `AbortOrAbandon` → `forge abandon`) are deliberately excluded.
    */
  def runRecoverableHint(hint: ResumeHint): Boolean = hint match
    case _: ResumeHint.ResolveLocalImplementationChanges => true
    case _: ResumeHint.ApplyPlanningUpdate => true
    case _ => false

  /** §11.7 — distil the §19 `profile.failure_classified` actions from a feature's log into the §7.11
    * `ConventionLearner`'s failure→remedy signal (the `gate`/`kind`/`route`/`evidence` the spine recorded). Pure +
    * total; a malformed payload is skipped rather than raised, since the learner is advisory. An empty result is the
    * §7.11 cost lever: no classified failure ⇒ the orchestrator never spends a learner reviewer-call.
    */
  private[orchestrator] def observedFailures(actions: Vector[io.forge.core.log.Action]): Vector[ObservedFailure] =
    actions.filter(_.kind == FailureClassifiedAction.Kind).flatMap { a =>
      a.payload.objOpt.flatMap { o =>
        for
          gate <- o.get("gate").flatMap(_.strOpt)
          kind <- o.get("kind").flatMap(_.strOpt)
          route <- o.get("route").flatMap(_.strOpt)
          evidence <- o.get("evidence").flatMap(_.strOpt)
        yield ObservedFailure(
          gate = gate,
          kind = kind,
          suggested = o.get("suggested").flatMap(_.strOpt),
          route = route,
          evidence = evidence
        )
      }
    }

  /** Slice 2.0 Task 2.0.2: the `success` field of a `session.complete` audit record. Only a clean settle counts as a
    * successful session; a non-zero adapter result, a settle-timeout, or a budget breach is `false`.
    */
  def outcomeSuccess(outcome: MonitorOutcome): Boolean = outcome match
    case MonitorOutcome.Settled(_, SettleOutcome.Clean) => true
    case _ => false

  /** §11.5 — a human override on a piece PR: a `CHANGES_REQUESTED` review decision or any unseen (non-bot) comment.
    * Forge's own reviewer verdict does not flow through the watcher; it arrives via the reviewer source.
    */
  def isHumanOverride(snap: io.forge.core.pr.PrSnapshot): Boolean =
    snap.reviewDecision.contains(ReviewDecision.ChangesRequested) || snap.unseenComments.nonEmpty

  /** gap #13: the PieceAwaitingReview watcher emits ONLY on a human override, so a no-op poll cannot win the source
    * race and cancel the in-flight reviewer one-shot (which is the primary verdict source). `None` ⇒ keep polling.
    */
  def pieceReviewWatcherEvent(p: PieceId, snap: io.forge.core.pr.PrSnapshot): Option[FsmEvent] =
    Option.when(isHumanOverride(snap))(FsmEvent.PrSnapshotUpdated(p, snap))

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
  case FromMonitor(report: MonitorReport)
  case FromWatcher(event: FsmEvent)
  case FromReviewer(event: FsmEvent)
  case FromUser(cmd: UserCommand)
