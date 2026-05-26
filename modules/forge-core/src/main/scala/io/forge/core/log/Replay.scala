package io.forge.core.log

import io.forge.core.*
import io.forge.core.Json.given
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, FsmState}

import java.time.Instant
import upickle.default.{read, ReadWriter}

/** §6 / §19 — replay-time observation of a single `fsm.transition` action.
  *
  * `from` and `to` are decoded from the action's payload via the derived `FsmState` ReadWriter. The piece is taken from
  * the action's top-level `piece` field (not the payload), matching the §19 wire example where piece-targeted FSM
  * transitions tag the row but the payload carries only the from/to states (and any per-transition extras).
  *
  * `at` is the action's stamped timestamp, surfaced here so `RebuildState.reconcile` (PR-E E4) can order multi-piece
  * recovery actions deterministically.
  */
final case class ObservedTransition(
    from: FsmState,
    to: FsmState,
    piece: Option[PieceId],
    at: Instant
)

/** §6 / PR-D D4 — output of [[Feature.foldEvents]].
  *
  * The replay walks the log applying each action onto an initial `Feature` and produces:
  *
  *   - `feature`: the projected state at the tail of the log (state, session-id projections, cost totals,
  *     `branchProtectionCacheEpoch`).
  *   - `observedTransitions`: every `fsm.transition` action in order — the post-pass in `RebuildState.reconcile` (E4)
  *     needs to know which transitions were logged, not just where they landed.
  *   - `observedPieceMerges`: the set of `PieceId`s that had an `audit.piece_merged` entry. Mirrors the structurally
  *     impossible (d) case in §11.5 reconcile: an audit without a transition.
  *
  * Manifest content is **not** projected here — `manifest.json` is the committed source of truth (§4). The caller
  * builds the initial `Feature` from a manifest (`Feature.initial(id, manifest)` is the standard seed) and folds the
  * log onto it.
  */
final case class FoldResult(
    feature: Feature,
    observedTransitions: Vector[ObservedTransition],
    observedPieceMerges: Set[PieceId]
)

/** Per-line replay failures. **Local to `io.forge.core.log`** — does NOT extend `RebuildError`. The rebuild layer (PR-E
  * E4) lifts via `RebuildError.ReplayInconsistent(cause: ReplayError)` so each layer keeps its own error vocabulary;
  * the replay layer doesn't depend on `io.forge.core.state` types.
  */
sealed trait ReplayError derives ReadWriter:
  def at: Long
object ReplayError:
  /** `seq` is not strictly increasing. */
  final case class NonMonotonicSeq(at: Long, observed: Long) extends ReplayError derives ReadWriter

  /** `<actor>.resume` references a `sessionId` that no prior `<actor>.spawn` introduced. */
  final case class ResumeWithoutSpawn(at: Long, actor: String, sessionId: String) extends ReplayError derives ReadWriter

  /** `fsm.transition` whose payload `from` disagrees with the fold's running state at that point in the replay. */
  final case class TransitionFromMismatch(at: Long, payloadFrom: FsmState, observed: FsmState) extends ReplayError
      derives ReadWriter

  /** `audit.piece_merged` payload's `prNumber` disagrees with the manifest's record for the same piece. */
  final case class AuditPrNumberMismatch(
      at: Long,
      piece: PieceId,
      logPrNumber: PrNumber,
      manifestPrNumber: Option[PrNumber]
  ) extends ReplayError derives ReadWriter

  /** A required payload field is malformed or missing. Surfaced as a single bucket because per-kind shape parsing isn't
    * the replay layer's job — `Feature.foldEvents` only inspects fields it depends on for the §6.1 projections.
    */
  final case class MalformedPayload(at: Long, kind: String, reason: String) extends ReplayError derives ReadWriter

/** §6 / §19 / PR-D D4 — pure log replay onto an initial `Feature`.
  *
  * The replay maintains three projections per §6.1:
  *
  *   - `feature.state`: walked by `fsm.transition` actions. Each step verifies the payload's `from` matches the running
  *     state (else `TransitionFromMismatch`) and advances to `to`.
  *   - `feature.designSessionId` / `feature.currentPieceSessionId`: projected from `<actor>.spawn` and `<actor>.resume`
  *     payloads. The running state's piece-vs-design phase determines which projection an actor spawn updates (matching
  *     `Fsm.transition`'s SessionSpawned handler).
  *   - `feature.cost`: projected from `cost.update` payloads (`{ provider, model, inputTokens, outputTokens, usd }`,
  *     with running totals on each line — replay uses the per-line `usd` to compute `feature` / `piece` / `turn` totals
  *     consistent with the FSM's writer side).
  *
  * `audit.piece_merged` entries are collected into `observedPieceMerges` and cross-checked against the seed manifest's
  * piece records (rejecting `AuditPrNumberMismatch` when the log's recorded `prNumber` disagrees with the manifest).
  *
  * `harness.error { kind: "log_truncated" }` entries (written by [[FileActionLog]]'s replay-repair contract) are a
  * **no-op projection** — they don't affect any of the projections above or the observed-transitions / observed-merges
  * accumulators. They exist as forensic markers, not as domain events.
  *
  * `foldEvents` does **not** synthesize recovery transitions or repair missing companion entries — that's
  * `RebuildState.reconcile`'s job in PR-E E4, and is the only place in Slice 2 that writes recovery actions back to the
  * log.
  */
object Replay:

  /** Fold a `Vector[Action]` onto an `initial: Feature`. Stops at the first inconsistency and returns it on the `Left`;
    * otherwise returns the projected `Feature` plus the observed transitions and audit'd piece merges.
    */
  def foldEvents(initial: Feature, actions: Vector[Action]): Either[ReplayError, FoldResult] =
    val initialState = FoldState(
      feature = initial,
      lastSeq = None,
      observedTransitions = Vector.empty,
      observedPieceMerges = Set.empty,
      knownSessionIds = Map.empty
    )
    val folded: Either[ReplayError, FoldState] =
      actions.foldLeft[Either[ReplayError, FoldState]](Right(initialState)) {
        case (Left(err), _) => Left(err)
        case (Right(st), action) => step(st, action)
      }
    folded.map(s =>
      FoldResult(
        feature = s.feature,
        observedTransitions = s.observedTransitions,
        observedPieceMerges = s.observedPieceMerges
      )
    )

  // ---------------------------------------------------------------------------
  // Per-action projection step.
  // ---------------------------------------------------------------------------

  private final case class FoldState(
      feature: Feature,
      lastSeq: Option[Long],
      observedTransitions: Vector[ObservedTransition],
      observedPieceMerges: Set[PieceId],
      // Per-actor session ids the log has introduced via `<actor>.spawn` or `<actor>.resume`'s `newSessionId`. Keyed
      // by actor name so a `codex.resume` that references a session id only `claude.spawn` introduced fails
      // `ResumeWithoutSpawn` — matching the §19 "no prior `<actor>.spawn`" rule on a per-actor basis. (A global Set
      // would silently accept the cross-actor case.)
      knownSessionIds: Map[String, Set[String]]
  )

  private def step(st: FoldState, action: Action): Either[ReplayError, FoldState] =
    // §19 `seq` monotonicity is independent of `kind` — enforce it before dispatching.
    val seqCheck: Either[ReplayError, Unit] = st.lastSeq match
      case Some(prev) if action.seq <= prev =>
        Left(ReplayError.NonMonotonicSeq(at = action.seq, observed = prev))
      case _ => Right(())
    seqCheck.flatMap { _ =>
      val nextSeqState = st.copy(lastSeq = Some(action.seq))
      action.kind match
        case "fsm.transition" => applyFsmTransition(nextSeqState, action)
        case k if isSpawnKind(k) => applySessionSpawn(nextSeqState, action, k)
        case k if isResumeKind(k) => applySessionResume(nextSeqState, action, k)
        case "cost.update" => applyCostUpdate(nextSeqState, action)
        case "audit.piece_merged" => applyAuditPieceMerged(nextSeqState, action)
        case "harness.error" if isLogTruncated(action) => Right(nextSeqState)
        case _ =>
          // Every other §19 `kind` (summary-only `<actor>.user_message`, `.assistant_text`, `.tool_use`,
          // `.ask_user_question`, `.halt_respawn`, `.schema_invalid`, `.process_retry`, `gh.poll`, `gh.action`,
          // `review.*`, `user.command`, every other `harness.*` flavour) is a no-op projection at the replay layer.
          // Higher-level passes (TUI tail, audit rendering, reviewer-side replay) can decode them per-kind; the FSM's
          // §6.1 projections only depend on the kinds explicitly handled above.
          Right(nextSeqState)
    }

  // --- fsm.transition ---

  private def applyFsmTransition(st: FoldState, action: Action): Either[ReplayError, FoldState] =
    decodeFromTo(action) match
      case Left(err) => Left(err)
      case Right((from, to)) =>
        if from != st.feature.state then
          Left(ReplayError.TransitionFromMismatch(at = action.seq, payloadFrom = from, observed = st.feature.state))
        else
          val obs = ObservedTransition(from = from, to = to, piece = action.piece, at = action.at)
          val updated = applyTransitionProjections(st.feature, from, to)
          Right(st.copy(feature = updated, observedTransitions = st.observedTransitions :+ obs))

  /** §6.1 lifecycle projection rules driven by an `fsm.transition` entry. Mirrors every non-state `Feature` mutation
    * `Fsm.transition` performs alongside the state change — replaying without these would leave PR-E's state-cache
    * verification rebuilding stale projections from an otherwise-valid log.
    *
    * The rules are derived from `Fsm.scala`:
    *
    *   1. **DesignReady entry** (`DesignAwaitingMerge → DesignReady`, line 295) clears `designSessionId` per §6.1 and
    *      resets `designPrFeedbackRound = 0` (the design phase is closing; the next `Resume(ReopenDesign)` starts
    *      fresh). 2. **Refining-departure** (`Refining → *`, lines 638/645/782) and **NHI-entry** (any `* →
    *      NeedsHumanIntervention` via `toNeedsHumanIntervention` / `prNumberMismatch` / `handleMerged` mismatch — all
    *      clear `currentPieceSessionId`) drop the now-stale piece-session projection. Symmetric on both sides because
    *      `Fsm.scala` enforces the invariant from both directions: the §6.1 "cleared at advance" rule (Refining
    *      handlers) and the round-2 fix's "cleared on NHI" rule (`toNeedsHumanIntervention`). 3. **DesignPrFeedback →
    *      DesignAwaitingMerge** (line 345) records `designPrFeedbackRound = state.round` so the next entry into
    *      `DesignPrFeedback` becomes `round + 1` (avoiding audit-filename / snapshot-tag collisions — see PR-C
    *      review-round-1 fix and carry-forward S2-6). 4. **NHI departure** (`NeedsHumanIntervention → *`) is a `Resume`
    *      (the only path out of NHI); `handleResume` always bumps `branchProtectionCacheEpoch` (§8.1) so cached
    *      branch-protection results from a prior orchestrator process are invalidated. Per-hint deltas:
    *      - `to = DesignReviewing(_)` ⇒ `Resume(ReopenDesign)` ⇒ reset `designPrFeedbackRound = 0`.
    *      - `to = PieceCiFailed(_, _, _)` ⇒ `Resume(RunAnotherFixup)` ⇒ clear `currentPieceSessionId` (§11.6
    *        fresh-driver-session contract; PR-C review-round-1 fix).
    *      - `to = PieceImplementing(_)` ⇒ `Resume(ResolveLocalImplementationChanges)` ⇒ clear `currentPieceSessionId`
    *        (operator resolved local changes; prior impl session is gone).
    *      - Other Resume targets (PieceAwaitingCi from ResumeAfterHumanPush/CommitAndPushHumanFix; FeatureDone or next
    *        PieceImplementing from ApplyPlanningUpdate via `nextStateAfterPiece`; Abandoned from AbortOrAbandon)
    *        preserve `currentPieceSessionId` — though in practice it's already `None` because entering NHI cleared it.
    *
    * Note: this function does NOT mutate `Feature.manifest`. Manifest is the §4 committed source of truth: writer-side
    * mutations (`baseSha`, `prNumber`, `mergeCommit`, `mergedAt`) land via atomic temp+rename to `manifest.json`
    * **before** the `fsm.transition` line is appended. Replay seeds the initial `Feature` from `manifest.json` (PR-E E4
    * pipeline step 2) so the manifest is already in its post-transition shape before the fold begins.
    */
  private def applyTransitionProjections(feature: Feature, from: FsmState, to: FsmState): Feature =
    val base = feature.copy(state = to)

    val designReadyApplied =
      if to == FsmState.DesignReady then base.copy(designSessionId = None, designPrFeedbackRound = 0)
      else base

    val pieceSessionCleared = (from, to) match
      case (_, _: FsmState.NeedsHumanIntervention) =>
        designReadyApplied.copy(currentPieceSessionId = None)
      case (_: FsmState.Refining, _) =>
        designReadyApplied.copy(currentPieceSessionId = None)
      case _ => designReadyApplied

    val feedbackRoundBumped = (from, to) match
      case (FsmState.DesignPrFeedback(_, round), _: FsmState.DesignAwaitingMerge) =>
        pieceSessionCleared.copy(designPrFeedbackRound = round)
      case _ => pieceSessionCleared

    from match
      case _: FsmState.NeedsHumanIntervention =>
        val epochBumped =
          feedbackRoundBumped.copy(
            branchProtectionCacheEpoch = feedbackRoundBumped.branchProtectionCacheEpoch + 1L
          )
        to match
          case _: FsmState.DesignReviewing => epochBumped.copy(designPrFeedbackRound = 0)
          case _: FsmState.PieceCiFailed => epochBumped.copy(currentPieceSessionId = None)
          case _: FsmState.PieceImplementing => epochBumped.copy(currentPieceSessionId = None)
          case _ => epochBumped
      case _ => feedbackRoundBumped

  private def decodeFromTo(action: Action): Either[ReplayError, (FsmState, FsmState)] =
    try
      val fromJs = action.payload.obj.getOrElse("from", throw new NoSuchElementException("from"))
      val toJs = action.payload.obj.getOrElse("to", throw new NoSuchElementException("to"))
      val from = read[FsmState](fromJs)
      val to = read[FsmState](toJs)
      Right((from, to))
    catch
      case t: Throwable =>
        Left(ReplayError.MalformedPayload(at = action.seq, kind = action.kind, reason = t.getMessage))

  // --- <actor>.spawn / <actor>.resume ---

  /** §19: `<actor>.spawn` payloads carry `{ sessionId, role, ... }`; the `<actor>` prefix on `kind` names the
    * connector. Replay records the session id under that actor's bucket — `codex.spawn` cannot satisfy a later
    * `claude.resume`'s `oldSessionId` reference (or vice versa). The per-action piece selects which §6.1 projection
    * (design vs piece) the session id flows into.
    */
  private def applySessionSpawn(st: FoldState, action: Action, kind: String): Either[ReplayError, FoldState] =
    readSessionId(action) match
      case Left(err) => Left(err)
      case Right(sid) =>
        val actor = action.actor.getOrElse(kindActor(kind))
        val updatedKnown = addKnownSession(st.knownSessionIds, actor, sid)
        val projected = projectSession(st.feature, action.piece, Some(sid))
        Right(st.copy(feature = projected, knownSessionIds = updatedKnown))

  /** §19: `<actor>.resume` carries `{ oldSessionId, newSessionId }`. Under the pinned CLIs (Slice 0) the two are equal,
    * but replay must tolerate the forward-compatible case where they differ — and reject a resume that references a
    * session id **the same actor** never introduced via a spawn. Cross-actor references (e.g., `codex.resume` targeting
    * a session id only `claude.spawn` produced) are exactly the case the per-actor map is here to catch.
    */
  private def applySessionResume(st: FoldState, action: Action, kind: String): Either[ReplayError, FoldState] =
    val obj = action.payload.objOpt.getOrElse(ujson.Obj().obj)
    val oldOpt = obj.get("oldSessionId").flatMap(_.strOpt)
    val newOpt = obj.get("newSessionId").flatMap(_.strOpt)
    (oldOpt, newOpt) match
      case (Some(oldSid), Some(newSid)) =>
        val actor = action.actor.getOrElse(kindActor(kind))
        val actorKnown = st.knownSessionIds.getOrElse(actor, Set.empty)
        if !actorKnown.contains(oldSid) then
          Left(ReplayError.ResumeWithoutSpawn(at = action.seq, actor = actor, sessionId = oldSid))
        else
          val projected = projectSession(st.feature, action.piece, Some(newSid))
          val updatedKnown = addKnownSession(st.knownSessionIds, actor, newSid)
          Right(st.copy(feature = projected, knownSessionIds = updatedKnown))
      case _ =>
        Left(
          ReplayError.MalformedPayload(
            at = action.seq,
            kind = kind,
            reason = "missing oldSessionId/newSessionId on <actor>.resume"
          )
        )

  private def addKnownSession(
      known: Map[String, Set[String]],
      actor: String,
      sid: String
  ): Map[String, Set[String]] =
    known.updated(actor, known.getOrElse(actor, Set.empty) + sid)

  private def readSessionId(action: Action): Either[ReplayError, String] =
    action.payload.objOpt.flatMap(_.get("sessionId")).flatMap(_.strOpt) match
      case Some(sid) => Right(sid)
      case None =>
        Left(
          ReplayError.MalformedPayload(
            at = action.seq,
            kind = action.kind,
            reason = "missing sessionId on <actor>.spawn"
          )
        )

  /** §6.1 projection: `designSessionId` is updated by spawn/resume actions with `piece = None` (the design driver runs
    * outside any piece); `currentPieceSessionId` is updated by spawn/resume actions with a piece set.
    */
  private def projectSession(feature: Feature, piece: Option[PieceId], sid: Option[String]): Feature =
    piece match
      case None => feature.copy(designSessionId = sid)
      case Some(_) => feature.copy(currentPieceSessionId = sid)

  // --- cost.update ---

  /** §19 `cost.update` payload: `{ provider, model, inputTokens, outputTokens, usd, featureTotalUsd, pieceTotalUsd,
    * turnTotalUsd }`. Replay projects the totals directly — the writer side computed them under the §13 single-writer
    * invariant so the last-write-wins view matches the running scopes.
    */
  private def applyCostUpdate(st: FoldState, action: Action): Either[ReplayError, FoldState] =
    val obj = action.payload.objOpt.getOrElse(ujson.Obj().obj)
    val feat = obj.get("featureTotalUsd").flatMap(_.numOpt)
    val piece = obj.get("pieceTotalUsd").flatMap(_.numOpt)
    val turn = obj.get("turnTotalUsd").flatMap(_.numOpt)
    (feat, piece, turn) match
      case (Some(f), Some(p), Some(t)) =>
        val updated = st.feature.copy(cost = CostTotals(BigDecimal(f), BigDecimal(p), BigDecimal(t)))
        Right(st.copy(feature = updated))
      case _ =>
        Left(
          ReplayError.MalformedPayload(
            at = action.seq,
            kind = "cost.update",
            reason = "missing featureTotalUsd/pieceTotalUsd/turnTotalUsd"
          )
        )

  // --- audit.piece_merged ---

  /** §19 `audit.piece_merged` payload: `{ p, prNumber, mergeCommit, mergedAt }`. Replay collects the piece id into
    * `observedPieceMerges` and cross-checks `prNumber` against the manifest's record (which the seed `Feature`
    * carries). Mismatch surfaces as `AuditPrNumberMismatch`; the manifest is the §4 source of truth.
    */
  private def applyAuditPieceMerged(st: FoldState, action: Action): Either[ReplayError, FoldState] =
    val obj = action.payload.objOpt.getOrElse(ujson.Obj().obj)
    val pieceStr = obj.get("p").orElse(obj.get("piece")).flatMap(_.strOpt)
    val prNumberJs = obj.get("prNumber").flatMap(_.numOpt)
    (pieceStr, prNumberJs) match
      case (Some(pStr), Some(pr)) =>
        val piece = PieceId(pStr)
        val logPr = PrNumber(pr.toInt)
        val manifestPr = st.feature.manifest.pieces.find(_.id == piece).flatMap(_.prNumber)
        if !manifestPr.contains(logPr) then
          Left(
            ReplayError.AuditPrNumberMismatch(
              at = action.seq,
              piece = piece,
              logPrNumber = logPr,
              manifestPrNumber = manifestPr
            )
          )
        else Right(st.copy(observedPieceMerges = st.observedPieceMerges + piece))
      case _ =>
        Left(
          ReplayError.MalformedPayload(
            at = action.seq,
            kind = "audit.piece_merged",
            reason = "missing p/prNumber on audit.piece_merged"
          )
        )

  // --- helpers ---

  private val SpawnSuffix = ".spawn"
  private val ResumeSuffix = ".resume"

  private def isSpawnKind(k: String): Boolean = k.endsWith(SpawnSuffix)
  private def isResumeKind(k: String): Boolean = k.endsWith(ResumeSuffix)
  private def kindActor(k: String): String =
    if k.endsWith(SpawnSuffix) then k.dropRight(SpawnSuffix.length)
    else if k.endsWith(ResumeSuffix) then k.dropRight(ResumeSuffix.length)
    else k

  private def isLogTruncated(action: Action): Boolean =
    action.payload.objOpt.flatMap(_.get("kind")).flatMap(_.strOpt).contains("log_truncated")
