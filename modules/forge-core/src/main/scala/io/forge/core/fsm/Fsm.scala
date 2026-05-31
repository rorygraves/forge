package io.forge.core.fsm

import io.forge.core.*
import io.forge.core.log.ActionDraft
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.pr.{PrSnapshot, PrState, ReviewDecision}
import io.forge.core.review.{DesignReviewVerdict, PrReviewVerdict, RefineVerdict}

/** §6.2 — wrapper around the `NeedsHumanIntervention` target that `requireSessionId` returns on `None`. Lives as its
  * own type (rather than `FsmState.NeedsHumanIntervention` directly) so the orchestrator's `requireSessionId` call site
  * can pattern-match on `Left(FsmTransition(state))` and forward it into the FSM via
  * `FsmEvent.RequiredSessionIdMissing` without ambiguity.
  */
final case class FsmTransition(state: FsmState)

/** §11 config knobs that gate FSM-internal decisions (round caps, attempt caps).
  *
  * v1.2 carries these as `config.maxDesignReviewRounds` / `config.maxFixupRounds` (§11.2 step 12, §11.5). The pure FSM
  * needs them to decide between "advance to next round" and "transition to `NeedsHumanIntervention`", so they're
  * plumbed through `Fsm.transition`'s third argument with sensible defaults (matching the v1.2 defaults of 3 and 3).
  *
  * This is a PR-C engineering call: the design-2.2 PR-C sketch wrote the signature as `(Feature, FsmEvent) => (Feature,
  * Vector[ActionDraft])`, but the §11 transition rules genuinely depend on these caps. Keeping them in a small
  * `FsmConfig` (rather than burying them on `Feature`) lets Slice 4 inject the orchestrator config without re-shaping
  * `Feature`.
  */
final case class FsmConfig(
    maxDesignReviewRounds: Int = 3,
    maxFixupRounds: Int = 3
)

object FsmConfig:
  val default: FsmConfig = FsmConfig()

/** §6 / §11 — pure FSM. `Fsm.transition` consumes `(Feature, FsmEvent)` and emits `(Feature', Vector[ActionDraft])`.
  *
  * The implementation is organised around three layers (matched in `transition` below):
  *
  *   1. **Cross-cutting events** that fire from many states (`UserCommandReceived(Abandon)`, `BudgetBreached`,
  *      `TurnBudgetBreached`, `HarnessError`, `RequiredSessionIdMissing`). These short-circuit state-specific logic. 2.
  *      **State-specific transitions** per §11.x, structured by current state. 3. **Default fall-through**: silent
  *      no-op. The action log already records the event; missing transitions show up as "state unchanged across events"
  *      in replay rather than crashing.
  *
  * No clock, no IO, no log-sequence allocator. `ActionDraft` (PR-B B3) is the unstamped sibling of `Action` — the
  * orchestrator's `ActionLog.append` (PR-D) stamps `seq`/`at` on the way to disk.
  */
object Fsm:

  /** §6.2 helper. Used by the orchestrator wherever it would otherwise call `.get` on a required session id. On `None`,
    * returns `Left(FsmTransition(NeedsHumanIntervention(reason, hint)))` so the caller can construct a
    * `RequiredSessionIdMissing(reason, hint)` FSM event AND a `harness.missing_session_id` log entry (§19) without two
    * code paths drifting.
    *
    * Pure. No throws on `None`. The signature mirrors the §6.2 sketch (the `[A]` type parameter from the sketch is
    * vestigial — the helper always returns `String` — so we drop it).
    */
  def requireSessionId(
      sessionId: Option[String],
      reason: String,
      hint: ResumeHint
  ): Either[FsmTransition, String] =
    sessionId match
      case Some(id) => Right(id)
      case None => Left(FsmTransition(FsmState.NeedsHumanIntervention(reason, hint)))

  /** §11 — pure transition. */
  def transition(
      feature: Feature,
      event: FsmEvent,
      config: FsmConfig = FsmConfig.default
  ): (Feature, Vector[ActionDraft]) =
    handleCrossCutting(feature, event, config).getOrElse(handleStateSpecific(feature, event, config))

  // ---------------------------------------------------------------------------
  // Cross-cutting events (handled first; short-circuit state-specific logic).
  // ---------------------------------------------------------------------------

  private def handleCrossCutting(
      feature: Feature,
      event: FsmEvent,
      config: FsmConfig
  ): Option[(Feature, Vector[ActionDraft])] =
    val _ = config
    event match
      case FsmEvent.UserCommandReceived(UserCommand.Abandon(reason)) if !isTerminal(feature.state) =>
        Some(toAbandoned(feature, reason))

      case FsmEvent.UserCommandReceived(UserCommand.Resume(hint))
          if feature.state.isInstanceOf[FsmState.NeedsHumanIntervention] =>
        Some(handleResume(feature, hint))

      // §15 `forge refresh-cache`: bump the branch-protection cache epoch only — no lifecycle transition. Mirrors the
      // epoch bump in `handleResume` but stays in the current state and emits no drafts (the state is unchanged, so
      // there is no `fsm.transition` to record). The terminal-state guard lives in the command derivation
      // (`deriveRefreshCache`); the pure FSM stays total here.
      case FsmEvent.UserCommandReceived(UserCommand.RefreshCache) =>
        Some((feature.copy(branchProtectionCacheEpoch = feature.branchProtectionCacheEpoch + 1), Vector.empty))

      case FsmEvent.BudgetBreached(scope, message) if !isTerminal(feature.state) =>
        val _ = scope
        Some(toNeedsHumanIntervention(feature, s"budget exceeded: $message", hintFromState(feature)))

      case FsmEvent.TurnBudgetBreached(phase, message) if !isTerminal(feature.state) =>
        val _ = phase
        Some(toNeedsHumanIntervention(feature, s"turn budget exceeded: $message", hintFromState(feature)))

      case FsmEvent.HarnessError(reason) if !isTerminal(feature.state) =>
        Some(toNeedsHumanIntervention(feature, reason, hintFromState(feature)))

      case FsmEvent.RequiredSessionIdMissing(reason, hint) if !isTerminal(feature.state) =>
        Some(toNeedsHumanIntervention(feature, reason, hint))

      case _ => None

  // ---------------------------------------------------------------------------
  // State-specific transitions per §11.
  // ---------------------------------------------------------------------------

  private def handleStateSpecific(
      feature: Feature,
      event: FsmEvent,
      config: FsmConfig
  ): (Feature, Vector[ActionDraft]) =
    feature.state match
      // --- §11.1 Spec phase ---
      case FsmState.Drafting => specDraftingTransitions(feature, event)
      case FsmState.InteractiveSpec => specInteractiveTransitions(feature, event)

      // --- §11.2 Design review ---
      case s: FsmState.DesignReviewing => designReviewingTransitions(feature, s, event, config)
      case s: FsmState.DesignNeedsHumanInput => designNeedsHumanInputTransitions(feature, s, event)

      // --- §11.3 Design PR gate ---
      case s: FsmState.DesignAwaitingMerge => designAwaitingMergeTransitions(feature, s, event)
      case s: FsmState.DesignPrFeedback => designPrFeedbackTransitions(feature, s, event)
      case FsmState.DesignReady => designReadyTransitions(feature, event)

      // --- §11.4 / §11.5 / §11.6 Implementation ---
      case s: FsmState.PieceImplementing => pieceImplementingTransitions(feature, s, event)
      case s: FsmState.PieceAwaitingCi => pieceAwaitingCiTransitions(feature, s, event, config)
      case s: FsmState.PieceAwaitingReview => pieceAwaitingReviewTransitions(feature, s, event, config)
      case s: FsmState.PieceCiFailed => pieceCiFailedTransitions(feature, s, event)
      case s: FsmState.PieceReviewFailed => pieceReviewFailedTransitions(feature, s, event)
      case s: FsmState.PieceFixingUp => pieceFixingUpTransitions(feature, s, event)
      case s: FsmState.PieceAwaitingMerge => pieceAwaitingMergeTransitions(feature, s, event, config)

      // --- §11.7 Refining / planning ---
      case s: FsmState.Refining => refiningTransitions(feature, s, event)
      case s: FsmState.PlanningUpdate => planningUpdateTransitions(feature, s, event)

      // --- Terminal / recovery: no further FSM transitions except via cross-cutting Resume/Abandon. ---
      case _: FsmState.NeedsHumanIntervention => noop(feature)
      case FsmState.FeatureDone => noop(feature)
      case _: FsmState.Abandoned => noop(feature)

  // ---------------------------------------------------------------------------
  // §11.1 Spec phase
  // ---------------------------------------------------------------------------

  private def specDraftingTransitions(feature: Feature, event: FsmEvent): (Feature, Vector[ActionDraft]) =
    event match
      // §11.1 step 2: spec-driver spawn projects designSessionId and transitions to InteractiveSpec.
      case FsmEvent.SessionSpawned(_, _, sessionId, None) =>
        val to = FsmState.InteractiveSpec
        val updated = feature.copy(state = to, designSessionId = Some(sessionId))
        (updated, Vector(fsmTransitionDraft(feature, FsmState.Drafting, to)))
      case _ => noop(feature)

  private def specInteractiveTransitions(feature: Feature, event: FsmEvent): (Feature, Vector[ActionDraft]) =
    event match
      // §11.1 step 6: settle clean → DesignReviewing(1).
      case FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.Clean) =>
        val to = FsmState.DesignReviewing(round = 1)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, FsmState.InteractiveSpec, to)))

      // §11.1 step 4: /done is the operator signal that precedes the final-message + settle. Treat it as a defensive
      // alternative trigger for the same transition so the FSM is robust to either ordering at the orchestrator side.
      case FsmEvent.UserCommandReceived(UserCommand.Done) =>
        val to = FsmState.DesignReviewing(round = 1)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, FsmState.InteractiveSpec, to)))

      // §11.1 step 6: settle timeout / adapter error → NHI(AbortOrAbandon).
      case FsmEvent.SettleTimeout(SessionPhase.Spec, _) =>
        toNeedsHumanIntervention(feature, "spec settle timeout", ResumeHint.AbortOrAbandon)
      case FsmEvent.Settled(SessionPhase.Spec, SettleOutcome.AdapterError(msg)) =>
        toNeedsHumanIntervention(feature, s"spec adapter error: $msg", ResumeHint.AbortOrAbandon)
      case _ => noop(feature)

  // ---------------------------------------------------------------------------
  // §11.2 Design review
  // ---------------------------------------------------------------------------

  private def designReviewingTransitions(
      feature: Feature,
      state: FsmState.DesignReviewing,
      event: FsmEvent,
      config: FsmConfig
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.2 step 13: verdict approve. The FSM stays in DesignReviewing(round) — orchestrator handles step 13's I/O
      // (commit, gh pr create, persist manifest.designPr) and then forwards a DesignPrSnapshotUpdated event to
      // transition into DesignAwaitingMerge. This keeps the FSM free of a per-PR-open event variant.
      case FsmEvent.DesignReviewReceived(_, DesignReviewVerdict.Approve) => noop(feature)

      // §11.2 step 12: request_changes. Validate designSessionId per §11.0 step 5; on missing → NHI(ReopenDesign(None)).
      // On present → next revision round (gated by config.maxDesignReviewRounds).
      // C14 (closed, v1.3 §7.10(a) / Task 1.4.14): when the orchestrator handles this transition it calls
      // driver.resumeStreamingSpec(sessionId, systemPromptPath, revisionMessage) — the trait now carries the driver
      // prompt path so the Codex connector re-prepends the §7.10(a) system block on resume (Claude ignores it). The FSM
      // itself does not branch on Mode.
      case FsmEvent.DesignReviewReceived(round, DesignReviewVerdict.RequestChanges(_)) =>
        requireSessionId(
          feature.designSessionId,
          "missing design session id, cannot resume",
          ResumeHint.ReopenDesign(currentDesignPr(feature))
        ) match
          case Left(ft) =>
            toNeedsHumanIntervention(
              feature,
              "missing design session id, cannot resume",
              ft.state match
                case FsmState.NeedsHumanIntervention(_, hint) => hint
                case _ => ResumeHint.ReopenDesign(currentDesignPr(feature))
            )
          case Right(_) =>
            val nextRound = round + 1
            if nextRound > config.maxDesignReviewRounds then
              toNeedsHumanIntervention(
                feature,
                "design did not converge",
                ResumeHint.ReopenDesign(currentDesignPr(feature))
              )
            else
              val to = FsmState.DesignReviewing(nextRound)
              val updated = feature.copy(state = to)
              (updated, Vector(fsmTransitionDraft(feature, state, to)))

      // §11.2 step 11: blocking questions → DesignNeedsHumanInput. designSessionId persists.
      case FsmEvent.DesignReviewReceived(round, DesignReviewVerdict.BlockingQuestions(qs)) =>
        val to = FsmState.DesignNeedsHumanInput(round, qs)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))

      // §11.2 step 13 continued — DesignPrSnapshotUpdated arrives once the orchestrator has opened the design PR.
      // The snapshot's `number` is the design PR's number; persist it as manifest.designPr and transition.
      case FsmEvent.DesignPrSnapshotUpdated(snapshot) if snapshot.state == PrState.Open =>
        val to = FsmState.DesignAwaitingMerge(snapshot.number)
        val updatedManifest = feature.manifest.copy(designPr = Some(snapshot.number))
        val updated = feature.copy(state = to, manifest = updatedManifest)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))

      // SessionResumed during DesignReviewing(round): projects newSessionId, no state change.
      case FsmEvent.SessionResumed(_, _, _, newSessionId, None) =>
        (feature.copy(designSessionId = Some(newSessionId)), Vector.empty)

      case FsmEvent.SettleTimeout(SessionPhase.DesignRevision, _) =>
        toNeedsHumanIntervention(
          feature,
          "design revision settle timeout",
          ResumeHint.ReopenDesign(currentDesignPr(feature))
        )

      // §B3 option (a) / S2-8: reviewer-side wall-clock cap. The orchestrator's `designReviewEvent` maps
      // `ReviewerOutcome.Timeout` → `SettleTimeout(SessionPhase.DesignReview, _)` (distinct from the driver-side
      // DesignRevision phase above). Route to NHI with the same design-phase hint as the revision/converge paths.
      case FsmEvent.SettleTimeout(SessionPhase.DesignReview, _) =>
        toNeedsHumanIntervention(
          feature,
          "design review settle timeout",
          ResumeHint.ReopenDesign(currentDesignPr(feature))
        )

      case _ => noop(feature)

  private def designNeedsHumanInputTransitions(
      feature: Feature,
      state: FsmState.DesignNeedsHumanInput,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.2 step 11: human answered the blocking questions → resume the revision round.
      case FsmEvent.DesignReviewClarified(round) =>
        val to = FsmState.DesignReviewing(round + 1)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))
      case _ => noop(feature)

  // ---------------------------------------------------------------------------
  // §11.3 Design PR gate
  // ---------------------------------------------------------------------------

  private def designAwaitingMergeTransitions(
      feature: Feature,
      state: FsmState.DesignAwaitingMerge,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // PR-number guard: a snapshot whose number doesn't match the current DesignAwaitingMerge target is almost
      // certainly stale (e.g., the operator did Resume(ReopenDesign) and the old PRWatcher emitted a residual poll).
      // Silently no-op rather than letting an unrelated PR's MERGED/CLOSED state drive the FSM.
      case FsmEvent.DesignPrSnapshotUpdated(snapshot) if snapshot.number != state.prNumber =>
        noop(feature)

      case FsmEvent.DesignPrSnapshotUpdated(snapshot) =>
        snapshot.state match
          case PrState.Merged if snapshot.mergedAt.isDefined =>
            // §11.3: merged → DesignReady. Clear designSessionId per §6.1 and reset designPrFeedbackRound (the design
            // phase is now closed; if Resume(ReopenDesign) later re-enters design, the counter starts fresh).
            val to = FsmState.DesignReady
            val updated = feature.copy(state = to, designSessionId = None, designPrFeedbackRound = 0)
            (updated, Vector(fsmTransitionDraft(feature, state, to)))

          case PrState.Closed =>
            // §11.3: closed without merge → NHI(ReopenDesign(Some(prNumber))).
            toNeedsHumanIntervention(
              feature,
              "design PR closed without merge",
              ResumeHint.ReopenDesign(Some(state.prNumber))
            )

          case PrState.Open
              if snapshot.reviewDecision.contains(ReviewDecision.ChangesRequested) ||
                snapshot.unseenComments.nonEmpty =>
            // §11.3: new human comment or CHANGES_REQUESTED → DesignPrFeedback(prNumber, lastRound + 1). The
            // monotonic counter behind §11.3's "round + 1" lives on Feature.designPrFeedbackRound; without it,
            // successive feedback cycles all restart at round = 1 and reuse audit filenames + snapshot tags
            // (`design-pr-feedback-r1-answers.md`, `<prefix>/_snapshots/<feat>/design-r1`). Validate designSessionId
            // per §11.0 step 5 — on missing, the orchestrator can't drive the revision, so →
            // NHI(ReopenDesign(Some(prNumber))).
            requireSessionId(
              feature.designSessionId,
              "missing design session id, cannot resume",
              ResumeHint.ReopenDesign(Some(state.prNumber))
            ) match
              case Left(ft) =>
                val hint = ft.state match
                  case FsmState.NeedsHumanIntervention(_, h) => h
                  case _ => ResumeHint.ReopenDesign(Some(state.prNumber))
                toNeedsHumanIntervention(feature, "missing design session id, cannot resume", hint)
              case Right(_) =>
                val nextRound = feature.designPrFeedbackRound + 1
                val to = FsmState.DesignPrFeedback(state.prNumber, round = nextRound)
                val updated = feature.copy(state = to)
                (updated, Vector(fsmTransitionDraft(feature, state, to)))

          case _ => noop(feature)
      case _ => noop(feature)

  private def designPrFeedbackTransitions(
      feature: Feature,
      state: FsmState.DesignPrFeedback,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.3 step 6: after a clean revision settle, return to DesignAwaitingMerge. Persist
      // designPrFeedbackRound = state.round so the next entry into DesignPrFeedback bumps to round + 1 instead of
      // restarting at 1 (audit-filename and snapshot-tag collision).
      case FsmEvent.Settled(SessionPhase.DesignRevision, SettleOutcome.Clean) =>
        val to = FsmState.DesignAwaitingMerge(state.prNumber)
        val updated = feature.copy(state = to, designPrFeedbackRound = state.round)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))

      // §11.3 step 2: SessionResumed projects newSessionId (idempotent under pinned CLIs).
      // C14 awareness: orchestrator-side, the Codex resume call cannot re-apply --system-prompt-file
      // (design-rationale C14). Slice 4 must re-issue role framing in the feedbackMessage for Codex. The FSM does not
      // branch on Mode.
      case FsmEvent.SessionResumed(_, _, _, newSessionId, None) =>
        (feature.copy(designSessionId = Some(newSessionId)), Vector.empty)

      case FsmEvent.SettleTimeout(SessionPhase.DesignRevision, _) =>
        toNeedsHumanIntervention(
          feature,
          "design PR feedback settle timeout",
          ResumeHint.ReopenDesign(Some(state.prNumber))
        )

      case _ => noop(feature)

  private def designReadyTransitions(feature: Feature, event: FsmEvent): (Feature, Vector[ActionDraft]) =
    event match
      // §11.4 step 1: BranchCreated mutates the manifest (status="in_progress", baseSha=...) BEFORE transitioning to
      // PieceImplementing. The FSM does both atomically; the orchestrator persists feature.manifest after this returns.
      case FsmEvent.BranchCreated(piece, _, baseSha) =>
        mutatePiece(feature, piece)(_.copy(status = PieceStatus.InProgress, baseSha = Some(baseSha))) match
          case Left(err) =>
            toNeedsHumanIntervention(feature, err, ResumeHint.AbortOrAbandon)
          case Right(updatedManifest) =>
            val to = FsmState.PieceImplementing(piece)
            val updated = feature.copy(state = to, manifest = updatedManifest)
            (updated, Vector(fsmTransitionDraft(feature, FsmState.DesignReady, to, piece = Some(piece))))
      case _ => noop(feature)

  // ---------------------------------------------------------------------------
  // §11.4 Implementation / §11.5 CI & review / §11.6 Fix-up
  // ---------------------------------------------------------------------------

  private def pieceImplementingTransitions(
      feature: Feature,
      state: FsmState.PieceImplementing,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.4 step 2: SessionSpawned projects currentPieceSessionId.
      case FsmEvent.SessionSpawned(_, _, sid, Some(p)) if p == state.p =>
        (feature.copy(currentPieceSessionId = Some(sid)), Vector.empty)

      // §11.4 step 1 (idempotent — re-entering PieceImplementing via Refining → PieceImplementing(next), then
      // BranchCreated for the new piece arrives here): persist baseSha + status, no state change.
      case FsmEvent.BranchCreated(piece, _, baseSha) if piece == state.p =>
        mutatePiece(feature, piece)(_.copy(status = PieceStatus.InProgress, baseSha = Some(baseSha))) match
          case Left(err) => toNeedsHumanIntervention(feature, err, ResumeHint.AbortOrAbandon)
          case Right(updatedManifest) => (feature.copy(manifest = updatedManifest), Vector.empty)

      // §11.4 step 6: PR opened → PieceAwaitingCi, persist manifest[p].prNumber.
      case FsmEvent.PrOpened(piece, prNumber) if piece == state.p =>
        mutatePiece(feature, piece)(_.copy(prNumber = Some(prNumber))) match
          case Left(err) => toNeedsHumanIntervention(feature, err, ResumeHint.AbortOrAbandon)
          case Right(updatedManifest) =>
            val to = FsmState.PieceAwaitingCi(piece, prNumber)
            val updated = feature.copy(state = to, manifest = updatedManifest)
            (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(piece))))

      // §11.4 step 5/6: settle bounds → NHI on timeout/adapter error.
      case FsmEvent.SettleTimeout(SessionPhase.Implement, _) =>
        toNeedsHumanIntervention(
          feature,
          s"implement settle timeout for piece ${state.p.value}",
          ResumeHint.ResolveLocalImplementationChanges(state.p, feature.manifest.pieceBranch(state.p))
        )
      case FsmEvent.Settled(SessionPhase.Implement, SettleOutcome.AdapterError(msg)) =>
        toNeedsHumanIntervention(
          feature,
          s"implement adapter error for piece ${state.p.value}: $msg",
          ResumeHint.ResolveLocalImplementationChanges(state.p, feature.manifest.pieceBranch(state.p))
        )
      case _ => noop(feature)

  private def pieceAwaitingCiTransitions(
      feature: Feature,
      state: FsmState.PieceAwaitingCi,
      event: FsmEvent,
      config: FsmConfig
  ): (Feature, Vector[ActionDraft]) =
    event match
      // PR-number guard: a piece snapshot whose number doesn't match the state's prNumber is stale (e.g., from a
      // closed prior PR for the same piece). Silently no-op.
      case FsmEvent.PrSnapshotUpdated(piece, snapshot) if piece == state.p && snapshot.number == state.prNumber =>
        ciOutcome(snapshot) match
          case CiOutcome.Failed =>
            // §11.5: required check failed → attempts+=1, then PieceCiFailed (gated by maxFixupRounds).
            bumpAttemptsAndGate(
              feature,
              state,
              state.p,
              state.prNumber,
              config,
              onWithinGate = attempt =>
                val to = FsmState.PieceCiFailed(state.p, state.prNumber, attempt)
                (to, fsmTransitionDraft(feature, state, to, piece = Some(state.p)))
              ,
              exhaustedReason = s"piece ${state.p.value} fix-up exhausted after CI failure",
              exhaustedHint = ResumeHint.RunAnotherFixup(state.p, state.prNumber)
            )
          case CiOutcome.Ready =>
            val to = FsmState.PieceAwaitingReview(state.p, state.prNumber)
            val updated = feature.copy(state = to)
            (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))
          case CiOutcome.Pending => noop(feature)

      // §8: CheckDiscoveryComplete is informational (it unblocks the §11.5 ready transition by telling the orchestrator
      // the required-check set is now known). The actual ready/fail decision still flows through PrSnapshotUpdated.
      case FsmEvent.CheckDiscoveryComplete(piece, prNumber) if piece == state.p && prNumber == state.prNumber =>
        noop(feature)

      // §8 discovery rules 1-3: the orchestrator's CI-readiness gate gave up (no checks / required check missing /
      // too few observed). Route to NeedsHumanIntervention with the §8-mandated ResumeAfterHumanPush hint. The FSM
      // stays CiPolicy-agnostic — `reason` is pre-rendered by the orchestrator (CiReadiness.evaluate).
      case FsmEvent.CiReadinessBlocked(piece, prNumber, reason) if piece == state.p && prNumber == state.prNumber =>
        toNeedsHumanIntervention(feature, reason, ResumeHint.ResumeAfterHumanPush(state.p, state.prNumber))

      case _ => noop(feature)

  private def pieceAwaitingReviewTransitions(
      feature: Feature,
      state: FsmState.PieceAwaitingReview,
      event: FsmEvent,
      config: FsmConfig
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.5 PieceAwaitingReview: reviewer approve → PieceAwaitingMerge. (Orchestrator ensures no concurrent human
      // CHANGES_REQUESTED override by checking the snapshot first before forwarding the verdict.)
      case FsmEvent.CodeReviewVerdict(piece, PrReviewVerdict.Approve) if piece == state.p =>
        val to = FsmState.PieceAwaitingMerge(state.p, state.prNumber)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))

      // §11.5 PieceAwaitingReview: reviewer request_changes → attempts+=1, PieceReviewFailed (gated by maxFixupRounds).
      case FsmEvent.CodeReviewVerdict(piece, PrReviewVerdict.RequestChanges(_)) if piece == state.p =>
        bumpAttemptsAndGate(
          feature,
          state,
          state.p,
          state.prNumber,
          config,
          onWithinGate = attempt =>
            val to = FsmState.PieceReviewFailed(state.p, state.prNumber, attempt)
            (to, fsmTransitionDraft(feature, state, to, piece = Some(state.p)))
          ,
          exhaustedReason = s"piece ${state.p.value} fix-up exhausted after review request_changes",
          exhaustedHint = ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      // §11.5 PieceAwaitingReview: human comment / human CHANGES_REQUESTED → attempts+=1, PieceReviewFailed.
      // PR-number guard: only act on snapshots whose number matches the current state's prNumber.
      case FsmEvent.PrSnapshotUpdated(piece, snapshot)
          if piece == state.p && snapshot.number == state.prNumber && humanOverride(snapshot) =>
        bumpAttemptsAndGate(
          feature,
          state,
          state.p,
          state.prNumber,
          config,
          onWithinGate = attempt =>
            val to = FsmState.PieceReviewFailed(state.p, state.prNumber, attempt)
            (to, fsmTransitionDraft(feature, state, to, piece = Some(state.p)))
          ,
          exhaustedReason = s"piece ${state.p.value} fix-up exhausted after human override",
          exhaustedHint = ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      // §B3 option (a) / S2-8: reviewer-side wall-clock cap. The orchestrator's `prReviewEvent` maps
      // `ReviewerOutcome.Timeout` → `SettleTimeout(SessionPhase.CodeReview, _)`. Route to NHI with the same fix-up
      // hint the review-failed / human-override paths use.
      case FsmEvent.SettleTimeout(SessionPhase.CodeReview, _) =>
        toNeedsHumanIntervention(
          feature,
          s"code review settle timeout for piece ${state.p.value}",
          ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      case _ => noop(feature)

  private def pieceAwaitingMergeTransitions(
      feature: Feature,
      state: FsmState.PieceAwaitingMerge,
      event: FsmEvent,
      config: FsmConfig
  ): (Feature, Vector[ActionDraft]) =
    event match
      // PR-number guard for Merged: a Merged event whose prNumber doesn't match the state is high-stakes (merge is
      // irreversible and the handler mutates manifest[p].{prNumber, mergeCommit, mergedAt}). Route to NHI rather than
      // silently treating it as stale.
      case FsmEvent.Merged(piece, prNumber, _, _, _) if piece == state.p && prNumber != state.prNumber =>
        prNumberMismatch(feature, state, "Merged", state.prNumber, prNumber, Some(state.p))

      // §11.5 step 1: merge detected → atomic manifest mutation + Refining transition + audit.piece_merged.
      // Idempotency rule (PR-B B4): if manifest[p] already merged with matching fields, the manifest mutation is a
      // no-op but the state still transitions to Refining and the drafts still fire. If the existing merged record
      // disagrees on prNumber/mergeCommit/mergedAt, transition to NHI(AbortOrAbandon) + harness.error
      // merged_field_mismatch draft.
      case FsmEvent.Merged(piece, prNumber, mergeCommit, mergedAt, observedAt) if piece == state.p =>
        handleMerged(feature, state, piece, prNumber, mergeCommit, mergedAt, observedAt)

      // §11.5 PieceAwaitingMerge: PR closed without merge → NHI(RunAnotherFixup).
      // PR-number guard: only act on snapshots whose number matches the current state's prNumber.
      case FsmEvent.PrSnapshotUpdated(piece, snapshot)
          if piece == state.p && snapshot.number == state.prNumber && snapshot.state == PrState.Closed =>
        toNeedsHumanIntervention(
          feature,
          s"piece ${state.p.value} PR closed without merge",
          ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      // §11.5 PieceAwaitingMerge: new human comment / human CHANGES_REQUESTED → attempts+=1, PieceReviewFailed.
      // PR-number guard: only act on snapshots whose number matches the current state's prNumber.
      case FsmEvent.PrSnapshotUpdated(piece, snapshot)
          if piece == state.p && snapshot.number == state.prNumber && humanOverride(snapshot) =>
        bumpAttemptsAndGate(
          feature,
          state,
          state.p,
          state.prNumber,
          config,
          onWithinGate = attempt =>
            val to = FsmState.PieceReviewFailed(state.p, state.prNumber, attempt)
            (to, fsmTransitionDraft(feature, state, to, piece = Some(state.p)))
          ,
          exhaustedReason = s"piece ${state.p.value} fix-up exhausted after human override pre-merge",
          exhaustedHint = ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      case _ => noop(feature)

  private def pieceCiFailedTransitions(
      feature: Feature,
      state: FsmState.PieceCiFailed,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.6: fresh fix-up driver session → PieceFixingUp. Updates currentPieceSessionId.
      case FsmEvent.SessionSpawned(_, _, sid, Some(p)) if p == state.p =>
        val to = FsmState.PieceFixingUp(state.p, state.prNumber, state.attempt)
        val updated = feature.copy(state = to, currentPieceSessionId = Some(sid))
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))
      case _ => noop(feature)

  private def pieceReviewFailedTransitions(
      feature: Feature,
      state: FsmState.PieceReviewFailed,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.6: fresh fix-up driver session → PieceFixingUp.
      case FsmEvent.SessionSpawned(_, _, sid, Some(p)) if p == state.p =>
        val to = FsmState.PieceFixingUp(state.p, state.prNumber, state.attempt)
        val updated = feature.copy(state = to, currentPieceSessionId = Some(sid))
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))
      case _ => noop(feature)

  private def pieceFixingUpTransitions(
      feature: Feature,
      state: FsmState.PieceFixingUp,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §11.6 "fresh driver session" (defensive). The usual path arrives here via
      // PieceCiFailed/PieceReviewFailed.SessionSpawned, which handles the projection. But if the orchestrator emits a
      // late SessionSpawned for the same piece while already in PieceFixingUp (e.g., a recovered-from-crash respawn
      // or, post-review-fix, a fresh runFixup after Resume(RunAnotherFixup) routed via PieceCiFailed → PieceFixingUp
      // and the next runFixup), we still want currentPieceSessionId to track the latest session id rather than
      // silently dropping the event. §6.1 projection invariant.
      case FsmEvent.SessionSpawned(_, _, sid, Some(p)) if p == state.p =>
        (feature.copy(currentPieceSessionId = Some(sid)), Vector.empty)

      // §11.6: fix-up settle clean → PieceAwaitingCi. currentPieceSessionId retained per §6.1.
      case FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.Clean) =>
        val to = FsmState.PieceAwaitingCi(state.p, state.prNumber)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))

      case FsmEvent.SettleTimeout(SessionPhase.Fixup, _) =>
        toNeedsHumanIntervention(
          feature,
          s"fix-up settle timeout for piece ${state.p.value}",
          ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )
      case FsmEvent.Settled(SessionPhase.Fixup, SettleOutcome.AdapterError(msg)) =>
        toNeedsHumanIntervention(
          feature,
          s"fix-up adapter error for piece ${state.p.value}: $msg",
          ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )
      case _ => noop(feature)

  // ---------------------------------------------------------------------------
  // §11.7 Refining / Planning
  // ---------------------------------------------------------------------------

  private def refiningTransitions(
      feature: Feature,
      state: FsmState.Refining,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      case FsmEvent.RefineOutcome(RefineVerdict.NoChange) =>
        advanceAfterRefine(feature, state)
      case FsmEvent.RefineOutcome(RefineVerdict.UpdatePlan(patch)) =>
        val to = FsmState.PlanningUpdate(reason = patch.reason, patch = patch)
        val updated = feature.copy(state = to, currentPieceSessionId = None)
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))
      case FsmEvent.RefineOutcome(RefineVerdict.ReopenDesign(_)) =>
        val to = FsmState.NeedsHumanIntervention(
          reason = "refinery flagged design drift",
          resumeHint = ResumeHint.ReopenDesign(None)
        )
        val updated = feature.copy(state = to, currentPieceSessionId = None)
        (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))

      // §B3 option (a) / S3-5: refinery wall-clock cap. The orchestrator's `refineEvent` maps
      // `ReviewerOutcome.Timeout` → `SettleTimeout(SessionPhase.Refine, _)`. The piece is already merged at this point,
      // so the recovery hint is RunAnotherFixup (matching hintFromState for Refining); `toNeedsHumanIntervention`
      // clears the stale currentPieceSessionId per §6.1.
      case FsmEvent.SettleTimeout(SessionPhase.Refine, _) =>
        toNeedsHumanIntervention(
          feature,
          s"refine settle timeout for piece ${state.p.value}",
          ResumeHint.RunAnotherFixup(state.p, state.prNumber)
        )

      case _ => noop(feature)

  private def planningUpdateTransitions(
      feature: Feature,
      state: FsmState.PlanningUpdate,
      event: FsmEvent
  ): (Feature, Vector[ActionDraft]) =
    event match
      // §14.3: operator accepts → apply patch, then advance to next piece.
      case FsmEvent.PlanningDecision(_, patch, PlanningChoice.Accept) =>
        applyPlanningPatch(feature, state, patch)
      case FsmEvent.PlanningDecision(_, _, PlanningChoice.EditAndAccept(patch)) =>
        applyPlanningPatch(feature, state, patch)
      case FsmEvent.PlanningDecision(_, _, PlanningChoice.Reject) =>
        // Reject: advance without modifying the manifest. Use nextPending under the unchanged manifest.
        val to = nextStateAfterPiece(feature)
        val updated = feature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))
      case _ => noop(feature)

  private def applyPlanningPatch(
      feature: Feature,
      state: FsmState.PlanningUpdate,
      patch: io.forge.core.manifest.ManifestPatch
  ): (Feature, Vector[ActionDraft]) =
    patch.applyTo(feature.manifest) match
      case Left(errs) =>
        toNeedsHumanIntervention(
          feature,
          s"manifest patch failed: ${errs.mkString("; ")}",
          ResumeHint.AbortOrAbandon
        )
      case Right(updatedManifest) =>
        val mutatedFeature = feature.copy(manifest = updatedManifest)
        val to = nextStateAfterPiece(mutatedFeature)
        val updated = mutatedFeature.copy(state = to)
        (updated, Vector(fsmTransitionDraft(feature, state, to)))

  // ---------------------------------------------------------------------------
  // §11.5 Merged handler — atomic mutation + idempotency + mismatch detection.
  // ---------------------------------------------------------------------------

  private def handleMerged(
      feature: Feature,
      state: FsmState.PieceAwaitingMerge,
      piece: PieceId,
      prNumber: PrNumber,
      mergeCommit: Sha,
      mergedAt: java.time.Instant,
      observedAt: java.time.Instant
  ): (Feature, Vector[ActionDraft]) =
    feature.manifest.pieces.find(_.id == piece) match
      case None =>
        toNeedsHumanIntervention(
          feature,
          s"Merged event for unknown piece ${piece.value}",
          ResumeHint.AbortOrAbandon
        )

      case Some(p) if p.status == PieceStatus.Merged =>
        // Idempotency rule: if the existing merged record matches, the mutation is a no-op but the state still
        // transitions to Refining and the drafts still fire (this is what makes RebuildState.reconcile case (c) safe).
        val matches = p.prNumber.contains(prNumber) &&
          p.mergeCommit.contains(mergeCommit) &&
          p.mergedAt.contains(mergedAt)
        if matches then
          val to = FsmState.Refining(piece, prNumber, observedAt)
          val updated = feature.copy(state = to)
          val drafts = Vector(
            fsmTransitionDraft(feature, state, to, piece = Some(piece)),
            auditPieceMergedDraft(feature, piece, prNumber, mergeCommit, mergedAt)
          )
          (updated, drafts)
        else
          // Mismatch: existing merged record disagrees on prNumber/mergeCommit/mergedAt → NHI(AbortOrAbandon) and
          // emit harness.error merged_field_mismatch (PR-B B4 contract).
          val mismatchDraft = ActionDraft(
            feature = feature.id,
            piece = Some(piece),
            actor = None,
            role = None,
            kind = "harness.error",
            payload = ujson.Obj(
              "kind" -> ujson.Str("merged_field_mismatch"),
              "piece" -> ujson.Str(piece.value),
              "expected" -> ujson.Obj(
                "prNumber" -> ujson.Num(prNumber.value.toDouble),
                "mergeCommit" -> ujson.Str(mergeCommit.value),
                "mergedAt" -> ujson.Str(mergedAt.toString)
              ),
              "observed" -> ujson.Obj(
                "prNumber" -> p.prNumber.map(n => ujson.Num(n.value.toDouble)).getOrElse(ujson.Null),
                "mergeCommit" -> p.mergeCommit.map(c => ujson.Str(c.value)).getOrElse(ujson.Null),
                "mergedAt" -> p.mergedAt.map(t => ujson.Str(t.toString)).getOrElse(ujson.Null)
              )
            )
          )
          val to = FsmState.NeedsHumanIntervention(
            reason = s"manifest merged record disagrees with Merged event for piece ${piece.value}",
            resumeHint = ResumeHint.AbortOrAbandon
          )
          // §6.1: NHI from a piece state clears currentPieceSessionId.
          val updated = feature.copy(state = to, currentPieceSessionId = None)
          (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(piece)), mismatchDraft))

      case Some(_) =>
        // Normal merge path: mutate manifest atomically + transition + audit draft.
        mutatePiece(feature, piece) { p =>
          p.copy(
            status = PieceStatus.Merged,
            prNumber = Some(prNumber),
            mergeCommit = Some(mergeCommit),
            mergedAt = Some(mergedAt)
          )
        } match
          case Left(err) =>
            toNeedsHumanIntervention(feature, err, ResumeHint.AbortOrAbandon)
          case Right(updatedManifest) =>
            val to = FsmState.Refining(piece, prNumber, observedAt)
            val updated = feature.copy(state = to, manifest = updatedManifest)
            val drafts = Vector(
              fsmTransitionDraft(feature, state, to, piece = Some(piece)),
              auditPieceMergedDraft(feature, piece, prNumber, mergeCommit, mergedAt)
            )
            (updated, drafts)

  // ---------------------------------------------------------------------------
  // §11.7 next-state computation after a refine NoChange / patch Reject.
  // ---------------------------------------------------------------------------

  private def advanceAfterRefine(
      feature: Feature,
      state: FsmState.Refining
  ): (Feature, Vector[ActionDraft]) =
    val to = nextStateAfterPiece(feature)
    val updated = feature.copy(state = to, currentPieceSessionId = None)
    (updated, Vector(fsmTransitionDraft(feature, state, to, piece = Some(state.p))))

  private def nextStateAfterPiece(feature: Feature): FsmState =
    feature.manifest.nextPending match
      case Some(next) => FsmState.PieceImplementing(next)
      case None => FsmState.FeatureDone

  // ---------------------------------------------------------------------------
  // Resume command handling (from NHI).
  // ---------------------------------------------------------------------------

  private def handleResume(feature: Feature, hint: ResumeHint): (Feature, Vector[ActionDraft]) =
    val bumped = feature.copy(branchProtectionCacheEpoch = feature.branchProtectionCacheEpoch + 1)
    val from = feature.state

    // ResumeOutcome captures (target state, manifest after any Resume-time mutation, session-id projection updates,
    // round-counter resets). Per-hint case classes keep the per-hint policy obvious — §11.6 in particular requires a
    // fresh fix-up session so Resume(RunAnotherFixup) must land in a pre-fix-up state (PieceCiFailed) with the stale
    // currentPieceSessionId cleared; the orchestrator's runFixup → SessionSpawned drives the actual PieceFixingUp
    // transition with the new session id. Resume(ReopenDesign) re-opens design phase, so designPrFeedbackRound resets.
    final case class ResumeOutcome(
        toState: FsmState,
        newManifest: Manifest,
        clearCurrentPieceSession: Boolean = false,
        resetDesignPrFeedback: Boolean = false
    )

    val outcome: ResumeOutcome = hint match
      case ResumeHint.ResumeAfterHumanPush(p, pr) =>
        ResumeOutcome(FsmState.PieceAwaitingCi(p, pr), bumped.manifest)
      case ResumeHint.CommitAndPushHumanFix(p, pr) =>
        ResumeOutcome(FsmState.PieceAwaitingCi(p, pr), bumped.manifest)
      case ResumeHint.RunAnotherFixup(p, pr) =>
        // §11.6 "Fresh driver session": Resume hands control back to the orchestrator at a pre-fix-up state. The
        // existing PieceCiFailed.SessionSpawned handler then drives PieceFixingUp with the new sessionId. Going
        // directly to PieceFixingUp here would (1) skip the SessionSpawned step the orchestrator emits via runFixup,
        // and (2) carry forward the prior fix-up's stale currentPieceSessionId — both of which violate §6.1's
        // projection invariant for currentPieceSessionId.
        val attempt = bumped.manifest.pieces.find(_.id == p).map(_.attempts).getOrElse(0)
        ResumeOutcome(
          FsmState.PieceCiFailed(p, pr, attempt),
          bumped.manifest,
          clearCurrentPieceSession = true
        )
      case ResumeHint.ResolveLocalImplementationChanges(p, _) =>
        // Operator resolved local changes; the prior impl driver session is gone. Clear and let the next SessionSpawned
        // re-project from PieceImplementing.
        ResumeOutcome(FsmState.PieceImplementing(p), bumped.manifest, clearCurrentPieceSession = true)
      case ResumeHint.ReopenDesign(_) =>
        ResumeOutcome(FsmState.DesignReviewing(round = 1), bumped.manifest, resetDesignPrFeedback = true)
      case ResumeHint.ApplyPlanningUpdate(patch) =>
        patch.applyTo(bumped.manifest) match
          case Left(_) => ResumeOutcome(from, bumped.manifest) // stay in NHI if patch fails to apply
          case Right(newManif) => ResumeOutcome(nextStateAfterPiece(bumped.copy(manifest = newManif)), newManif)
      case ResumeHint.AbortOrAbandon =>
        ResumeOutcome(FsmState.Abandoned("operator aborted via Resume(AbortOrAbandon)"), bumped.manifest)

    val mutated = bumped.copy(
      manifest = outcome.newManifest,
      state = outcome.toState,
      currentPieceSessionId = if outcome.clearCurrentPieceSession then None else bumped.currentPieceSessionId,
      designPrFeedbackRound = if outcome.resetDesignPrFeedback then 0 else bumped.designPrFeedbackRound
    )
    if from == outcome.toState then (mutated, Vector.empty)
    else (mutated, Vector(fsmTransitionDraft(feature, from, outcome.toState)))

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** §11.5 + §11.6 attempts-counter helper. Atomically persists `manifest[p].attempts += 1` and gates by
    * `config.maxFixupRounds`: if within the gate, computes the next state via `onWithinGate(attempt)`; otherwise →
    * NHI(exhaustedReason, exhaustedHint).
    */
  private def bumpAttemptsAndGate(
      feature: Feature,
      from: FsmState,
      p: PieceId,
      pr: PrNumber,
      config: FsmConfig,
      onWithinGate: Int => (FsmState, ActionDraft),
      exhaustedReason: String,
      exhaustedHint: ResumeHint
  ): (Feature, Vector[ActionDraft]) =
    val _ = pr
    val _ = from
    feature.manifest.pieces.find(_.id == p) match
      case None =>
        toNeedsHumanIntervention(feature, s"unknown piece ${p.value}", ResumeHint.AbortOrAbandon)
      case Some(piece) =>
        val newAttempts = piece.attempts + 1
        mutatePiece(feature, p)(_.copy(attempts = newAttempts)) match
          case Left(err) =>
            toNeedsHumanIntervention(feature, err, ResumeHint.AbortOrAbandon)
          case Right(updatedManifest) =>
            val mutatedFeature = feature.copy(manifest = updatedManifest)
            if newAttempts <= config.maxFixupRounds then
              val (to, draft) = onWithinGate(newAttempts)
              (mutatedFeature.copy(state = to), Vector(draft))
            else toNeedsHumanIntervention(mutatedFeature, exhaustedReason, exhaustedHint)

  /** Apply a per-piece mutation. Re-validates the resulting manifest; on validation failure, returns the joined error
    * message.
    */
  private def mutatePiece(
      feature: Feature,
      pieceId: PieceId
  )(f: Piece => Piece): Either[String, Manifest] =
    val idx = feature.manifest.pieces.indexWhere(_.id == pieceId)
    if idx < 0 then Left(s"unknown piece ${pieceId.value}")
    else
      val updated =
        feature.manifest.copy(pieces = feature.manifest.pieces.updated(idx, f(feature.manifest.pieces(idx))))
      updated.validate match
        case Left(errs) => Left(errs.mkString("; "))
        case Right(m) => Right(m)

  private def toNeedsHumanIntervention(
      feature: Feature,
      reason: String,
      hint: ResumeHint
  ): (Feature, Vector[ActionDraft]) =
    val to = FsmState.NeedsHumanIntervention(reason, hint)
    // §6.1: currentPieceSessionId is cleared on advancing to NeedsHumanIntervention. From design-phase states the
    // field is already None (it's only ever populated in piece states), so this is a no-op there; from piece-phase
    // states (PieceImplementing/PieceAwaitingCi/.../Refining) it drops the now-stale projection. designSessionId is
    // intentionally NOT cleared here — §6.1 ties its clear to entering DesignReady, and the
    // `Resume(ReopenDesign)` path handles design-phase re-entry with its own designPrFeedbackRound reset.
    val updated = feature.copy(state = to, currentPieceSessionId = None)
    val drafts =
      if feature.state == to then Vector.empty
      else Vector(fsmTransitionDraft(feature, feature.state, to))
    (updated, drafts)

  /** PR-number mismatch helper for high-stakes events (currently `Merged`). Snapshots are silently no-op'd elsewhere
    * since polling can lag, but a `Merged` event with a wrong prNumber would irreversibly write the wrong PR into the
    * manifest's piece record. Surface the mismatch as `NHI(AbortOrAbandon)` and emit a paired `harness.error
    * pr_number_mismatch` draft so the operator has the full forensic context.
    */
  private def prNumberMismatch(
      feature: Feature,
      from: FsmState,
      eventKind: String,
      expected: PrNumber,
      observed: PrNumber,
      piece: Option[PieceId]
  ): (Feature, Vector[ActionDraft]) =
    val to = FsmState.NeedsHumanIntervention(
      reason = s"$eventKind PR number mismatch: state=${expected.value}, event=${observed.value}",
      resumeHint = ResumeHint.AbortOrAbandon
    )
    val mismatchDraft = ActionDraft(
      feature = feature.id,
      piece = piece,
      actor = None,
      role = None,
      kind = "harness.error",
      payload = ujson.Obj(
        "kind" -> ujson.Str("pr_number_mismatch"),
        "event" -> ujson.Str(eventKind),
        "expectedPr" -> ujson.Num(expected.value.toDouble),
        "observedPr" -> ujson.Num(observed.value.toDouble)
      )
    )
    // §6.1: NHI from a piece state clears currentPieceSessionId.
    val updated = feature.copy(state = to, currentPieceSessionId = None)
    (updated, Vector(fsmTransitionDraft(feature, from, to, piece = piece), mismatchDraft))

  private def toAbandoned(feature: Feature, reason: String): (Feature, Vector[ActionDraft]) =
    val to = FsmState.Abandoned(reason)
    val updated = feature.copy(state = to)
    val draft = fsmTransitionDraft(feature, feature.state, to)
    val cmdDraft = ActionDraft(
      feature = feature.id,
      piece = None,
      actor = None,
      role = None,
      kind = "user.command",
      payload = ujson.Obj("cmd" -> ujson.Str("abandon"), "reason" -> ujson.Str(reason))
    )
    (updated, Vector(cmdDraft, draft))

  private def noop(feature: Feature): (Feature, Vector[ActionDraft]) = (feature, Vector.empty)

  private def isTerminal(state: FsmState): Boolean = state match
    case FsmState.FeatureDone | _: FsmState.Abandoned => true
    case _ => false

  /** Default ResumeHint for the current state — used by HarnessError / BudgetBreached / TurnBudgetBreached when the
    * orchestrator hasn't supplied one explicitly. The hint mirrors §11.x's recovery rules for that state.
    */
  private def hintFromState(feature: Feature): ResumeHint = feature.state match
    case FsmState.Drafting | FsmState.InteractiveSpec => ResumeHint.AbortOrAbandon
    case _: FsmState.DesignReviewing => ResumeHint.ReopenDesign(currentDesignPr(feature))
    case _: FsmState.DesignNeedsHumanInput => ResumeHint.ReopenDesign(currentDesignPr(feature))
    case s: FsmState.DesignAwaitingMerge => ResumeHint.ReopenDesign(Some(s.prNumber))
    case s: FsmState.DesignPrFeedback => ResumeHint.ReopenDesign(Some(s.prNumber))
    case FsmState.DesignReady => ResumeHint.ReopenDesign(currentDesignPr(feature))
    case s: FsmState.PieceImplementing =>
      ResumeHint.ResolveLocalImplementationChanges(s.p, feature.manifest.pieceBranch(s.p))
    case s: FsmState.PieceAwaitingCi => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.PieceAwaitingReview => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.PieceCiFailed => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.PieceReviewFailed => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.PieceFixingUp => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.PieceAwaitingMerge => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case s: FsmState.Refining => ResumeHint.RunAnotherFixup(s.p, s.prNumber)
    case _: FsmState.PlanningUpdate => ResumeHint.AbortOrAbandon
    case s: FsmState.NeedsHumanIntervention => s.resumeHint
    case FsmState.FeatureDone => ResumeHint.AbortOrAbandon
    case _: FsmState.Abandoned => ResumeHint.AbortOrAbandon

  private def currentDesignPr(feature: Feature): Option[PrNumber] = feature.manifest.designPr

  private def fsmTransitionDraft(
      feature: Feature,
      from: FsmState,
      to: FsmState,
      actor: Option[String] = None,
      role: Option[String] = None,
      piece: Option[PieceId] = None,
      extras: Vector[(String, ujson.Value)] = Vector.empty
  ): ActionDraft =
    // Encode `from`/`to` as full `FsmState` JSON via the derived ReadWriter. uPickle's Scala-3-enum encoding renders
    // singleton cases as bare strings (`"Drafting"`) and parameterized cases as `{"$type": "...", ...}`, so the §19
    // wire example (`"from": "PieceImplementing"`) is the singleton-case form of this encoding. Full encoding lets
    // `Feature.foldEvents` (PR-D D4) reconstruct the running `FsmState` with all parameters (piece id, prNumber, round,
    // attempt, startedAt) — which `RebuildState.reconcile` (PR-E E4) needs to anchor its case-(c) match on `t.from ==
    // PieceAwaitingMerge(p.id, p.prNumber.get)` rather than on tag-only equality.
    val payload = ujson.Obj(
      "from" -> upickle.default.writeJs[FsmState](from),
      "to" -> upickle.default.writeJs[FsmState](to)
    )
    extras.foreach { case (k, v) => payload(k) = v }
    ActionDraft(feature.id, piece, actor, role, "fsm.transition", payload)

  private def auditPieceMergedDraft(
      feature: Feature,
      piece: PieceId,
      prNumber: PrNumber,
      mergeCommit: Sha,
      mergedAt: java.time.Instant
  ): ActionDraft =
    ActionDraft(
      feature = feature.id,
      piece = Some(piece),
      actor = None,
      role = None,
      kind = "audit.piece_merged",
      payload = ujson.Obj(
        "p" -> ujson.Str(piece.value),
        "prNumber" -> ujson.Num(prNumber.value.toDouble),
        "mergeCommit" -> ujson.Str(mergeCommit.value),
        "mergedAt" -> ujson.Str(mergedAt.toString)
      )
    )

  // §11.5 CI rollup classification. Required-check failures are anything with a "bad" conclusion; "ready" means every
  // required check has conclusion = Success; otherwise still pending.
  private enum CiOutcome:
    case Failed, Ready, Pending

  private def ciOutcome(snapshot: PrSnapshot): CiOutcome =
    val required = snapshot.requiredChecks.required
    if required.isEmpty then CiOutcome.Pending
    else if required.exists(c => c.conclusion.exists(isBadConclusion)) then CiOutcome.Failed
    else if required.forall(c => c.conclusion.contains(io.forge.core.pr.CheckConclusion.Success)) then CiOutcome.Ready
    else CiOutcome.Pending

  private def isBadConclusion(c: io.forge.core.pr.CheckConclusion): Boolean = c match
    case io.forge.core.pr.CheckConclusion.Failure | io.forge.core.pr.CheckConclusion.TimedOut |
        io.forge.core.pr.CheckConclusion.Cancelled | io.forge.core.pr.CheckConclusion.StartupFailure |
        io.forge.core.pr.CheckConclusion.ActionRequired =>
      true
    case _ => false

  /** §11.5: a human override is a human `CHANGES_REQUESTED` or one or more unseen human comments. Forge-authored
    * activity is filtered by the orchestrator before the snapshot reaches the FSM, so any `unseenComments` at this
    * layer counts as a human comment.
    */
  private def humanOverride(snapshot: PrSnapshot): Boolean =
    snapshot.reviewDecision.contains(ReviewDecision.ChangesRequested) || snapshot.unseenComments.nonEmpty
