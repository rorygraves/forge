package io.forge.core.state

import cats.effect.IO
import io.forge.core.*
import io.forge.core.fsm.{Feature, Fsm, FsmEvent, FsmState}
import io.forge.core.log.{ActionDraft, ActionLog, FoldResult, ObservedTransition, Replay}
import io.forge.core.manifest.{Manifest, ManifestStore, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths

/** PR-E E4 — `forge-core` entry point that Slice 4's `forge rebuild-state` CLI delegates to.
  *
  * The pipeline rebuilds a feature's `Feature` projection from its committed manifest plus the canonical action log,
  * repairs any §11.5 crash-window damage to the log, and rewrites the state cache atomically. Every step that can fail
  * surfaces a [[RebuildError]] on the `Either` channel; `IO` only carries genuine I/O exceptions.
  *
  * **Pipeline (design-2.2.md §1.5 E4):**
  *   1. Load `manifest.json` via `manifestStore.load(featureId)`. On failure → `Left(ManifestLoadFailed)`.
  *   1. Seed `initial = Feature.initial(featureId, manifest)` — the manifest is the §4 source of truth; the action log
  *      records FSM transitions over it.
  *   1. Fold the log via `Feature.foldEvents(initial, log.replay(featureId))`. On `Left(replayError)`, lift to
  *      `Left(ReplayInconsistent(replayError))`.
  *   1. Run the pure [[reconcile]] post-pass over `(FoldResult, Manifest)`. Returns the recovered `Feature` plus any
  *      repair drafts that need to be appended to make the on-disk log self-consistent.
  *   1. If reconcile returned drafts, append them via `log.appendAll`.
  *   1. Persist the recovered `Feature` via `cache.save(featureId, _)`.
  *
  * The reconciliation rule is in [[reconcile]] — pure, no I/O, takes the projected `FoldResult` and the seed manifest
  * and decides whether each manifest-merged piece is in case (a) "fully recovered", case (b) "partial-batch repair",
  * case (c) "synthetic Merged transition needed", or case (d) "structurally impossible — refuse". See [[reconcile]]'s
  * docstring for the per-piece classification.
  */
object RebuildState:

  /** End-to-end rebuild. Concretely, this is the entry point `forge rebuild-state <feature>` delegates to in Slice 4;
    * Slice 2 also exercises it from F13 fixtures (PR-F).
    */
  def run(
      featureId: FeatureId,
      paths: ForgePaths,
      manifestStore: ManifestStore,
      log: ActionLog,
      cache: StateCache
  ): IO[Either[RebuildError, Feature]] =
    val _ = paths // currently unused; kept on the signature so Slice-4 wiring + future per-feature lock helpers don't
    // have to thread paths through twice.
    manifestStore.load(featureId).flatMap {
      case Left(failure) => IO.pure(Left(failure))
      case Right(manifest) =>
        val seed = Feature.initial(featureId, manifest)
        log
          .replay(featureId)
          .flatMap { actions =>
            Replay.foldEvents(seed, actions) match
              case Left(replayErr) => IO.pure(Left(RebuildError.ReplayInconsistent(replayErr)))
              case Right(foldResult) =>
                reconcile(foldResult, manifest) match
                  case Left(rebuildErr) => IO.pure(Left(rebuildErr))
                  case Right(reconciled) =>
                    val appendIfNeeded =
                      if reconciled.draftsToAppend.isEmpty then IO.unit
                      else log.appendAll(featureId, reconciled.draftsToAppend).void
                    appendIfNeeded *> cache.save(featureId, reconciled.feature).as(Right(reconciled.feature))
          }
    }

  /** Pure reconciliation rule. Inputs: the log-fold projection + the seed manifest. Output: the post-recovery `Feature`
    * and the repair drafts that need to be appended to make the on-disk log self-consistent.
    *
    * Reconcile **never** touches the log directly — that's [[run]]'s job (pipeline step 5). Keeping the rule pure means
    * F13 can hand fixture `FoldResult` + `Manifest` values and assert on the returned drafts without any I/O.
    *
    * **Per-piece classification.** For each piece `p` in `manifest.pieces.filter(_.status == Merged)` (in manifest
    * order):
    *
    *   - `transitionLogged(p)` — exists in `foldResult.observedTransitions` with `from == PieceAwaitingMerge(p.id,
    *     p.prNumber.get)` AND `to` is `Refining(p.id, p.prNumber.get, _)`. Anchored on **both** piece id and PR number;
    *     piece-id-only would silently accept an unrelated PR's merge as the recovery target. `p.prNumber.get` is safe
    *     because `Manifest.validate` (§5.1) guarantees it's non-None for merged pieces.
    *   - `auditLogged(p)` — `foldResult.observedPieceMerges` contains `p.id`. Piece-id-only is sufficient at this layer
    *     because the per-line PR-number check happens inside `Replay.foldEvents` (`AuditPrNumberMismatch`).
    *
    * Four sub-cases, three reachable:
    *
    *   - **(a) transitionLogged && auditLogged** — fully recovered; no drafts, feature unchanged.
    *   - **(b) transitionLogged && !auditLogged** — partial-batch crash between the `fsm.transition` line and the
    *     `audit.piece_merged` line. The FSM transition already occurred during the fold (the fold-state may be
    *     `Refining(p, _, _)` or any forward state if the log captured later transitions). The repair synthesizes the
    *     missing `audit.piece_merged` from the manifest's piece record + a paired `harness.crash_recovered` draft. The
    *     feature is **not** mutated.
    *   - **(c) !transitionLogged && !auditLogged** — the §11.5 crash window proper. Requires `feature.state ==
    *     PieceAwaitingMerge(p.id, p.prNumber.get)`; anything else means the fold-state is structurally incompatible
    *     (e.g. log says we're still in `PieceAwaitingReview`) and we return `Left(InconsistentRecovery)`. Applies
    *     `Fsm.transition(feature, syntheticMerged)` where `syntheticMerged = FsmEvent.Merged(p.id, p.prNumber.get,
    *     p.mergeCommit.get, p.mergedAt.get, observedAt = p.mergedAt.get)`. The B4 idempotency rule makes this safe
    *     (manifest is already merged, so the mutation is a no-op; the state transition + drafts still fire). `feature`
    *     is updated to the post-transition value; `drafts ++ Vector(harnessCrashRecoveredDraft)` are appended.
    *   - **(d) !transitionLogged && auditLogged** — structurally impossible under §11.5 ordering (audit comes after
    *     transition in the batch). Return `Left(InconsistentRecovery)` — refuse to invent transitions.
    *
    * **Multi-piece divergence:** if two or more pieces fall into case (c), we refuse with
    * `Left(InconsistentRecovery("multi-piece partial merge ..."))`. A second piece can only have entered
    * `PieceAwaitingMerge` after its predecessor's `Refining → PieceImplementing` transition was logged, so genuine
    * multi-piece (c) is unreachable under §11.5 ordering. Cases (a) and (b) compose freely across pieces.
    */
  def reconcile(foldResult: FoldResult, manifest: Manifest): Either[RebuildError, ReconcileResult] =
    val mergedPieces = manifest.pieces.filter(_.status == PieceStatus.Merged)

    // Pass 1 — classify pieces and refuse structurally-impossible / multi-piece cases up front.
    val classifications: Vector[(Piece, PieceCase)] = mergedPieces.map { p =>
      val classification = classify(p, foldResult)
      (p, classification)
    }

    classifications.collectFirst { case (_, PieceCase.Impossible(reason)) => reason } match
      case Some(reason) => Left(RebuildError.InconsistentRecovery(reason))
      case None =>
        val caseCcount = classifications.count {
          case (_, PieceCase.CrashWindow) => true
          case _ => false
        }
        if caseCcount > 1 then
          Left(
            RebuildError.InconsistentRecovery(
              "multi-piece partial merge — operator intervention required"
            )
          )
        else
          // Pass 2 — apply repairs in manifest order. Only case (c) updates the feature.
          val initial: Either[RebuildError, ReconcileAccumulator] =
            Right(ReconcileAccumulator(foldResult.feature, Vector.empty))
          classifications.foldLeft(initial) {
            case (Left(err), _) => Left(err)
            case (Right(acc), (piece, classification)) =>
              applyRepair(acc, piece, classification)
          } match
            case Left(err) => Left(err)
            case Right(acc) => Right(ReconcileResult(acc.feature, acc.drafts))

  // ---------------------------------------------------------------------------
  // Classification + repair helpers (pure).
  // ---------------------------------------------------------------------------

  /** Output of [[reconcile]]. `draftsToAppend` is the in-order list of repair actions [[run]] should `appendAll` before
    * writing the cache.
    */
  final case class ReconcileResult(feature: Feature, draftsToAppend: Vector[ActionDraft])

  private final case class ReconcileAccumulator(feature: Feature, drafts: Vector[ActionDraft])

  /** Per-piece classification result. */
  private enum PieceCase:
    /** (a) `transitionLogged && auditLogged` — no work. */
    case FullyRecovered

    /** (b) `transitionLogged && !auditLogged` — partial-batch crash; synthesize the missing audit entry. */
    case PartialBatch

    /** (c) `!transitionLogged && !auditLogged` — §11.5 crash window; synthesize the `Merged` event. */
    case CrashWindow

    /** (d) / divergence — structurally impossible; surface the reason. */
    case Impossible(reason: String)

  private def classify(piece: Piece, foldResult: FoldResult): PieceCase =
    val prNumber = piece.prNumber.getOrElse(
      throw new IllegalStateException(
        s"reconcile invariant: merged piece ${piece.id.value} has no prNumber — Manifest.validate should have caught"
      )
    )
    val transitionLogged = foldResult.observedTransitions.exists { t =>
      transitionAnchorsMerge(t, piece.id, prNumber)
    }
    val auditLogged = foldResult.observedPieceMerges.contains(piece.id)
    (transitionLogged, auditLogged) match
      case (true, true) => PieceCase.FullyRecovered
      case (true, false) => PieceCase.PartialBatch
      case (false, false) => PieceCase.CrashWindow
      case (false, true) =>
        // (d) Structurally impossible under §11.5 ordering: audit comes after transition in the rendered-batch write.
        // An audit-only orphan implies the log was hand-edited or corrupted.
        PieceCase.Impossible(
          s"piece ${piece.id.value}: audit.piece_merged present but PieceAwaitingMerge → Refining missing"
        )

  /** A transition `t` anchors `piece`'s merge iff its `from` is `PieceAwaitingMerge(piece, prNumber)` AND its `to` is
    * `Refining` for the same piece + PR number. PR number must match on both ends; piece-id-only would accept an
    * unrelated PR's transition (e.g. a hand-edited log).
    */
  private def transitionAnchorsMerge(t: ObservedTransition, piece: PieceId, prNumber: PrNumber): Boolean =
    val fromMatches = t.from match
      case FsmState.PieceAwaitingMerge(p, pr) => p == piece && pr == prNumber
      case _ => false
    val toMatches = t.to match
      case FsmState.Refining(p, pr, _) => p == piece && pr == prNumber
      case _ => false
    fromMatches && toMatches

  private def applyRepair(
      acc: ReconcileAccumulator,
      piece: Piece,
      classification: PieceCase
  ): Either[RebuildError, ReconcileAccumulator] =
    classification match
      case PieceCase.FullyRecovered => Right(acc)

      case PieceCase.PartialBatch =>
        // The FSM transition is already on disk; only the audit entry is missing. Synthesize it from the manifest's
        // record plus a paired `harness.crash_recovered` draft naming the repair. The feature itself is not touched —
        // any forward transitions the log captured already updated the fold's `feature.state`.
        val syntheticAudit = syntheticAuditDraft(acc.feature.id, piece)
        val harness = harnessCrashRecoveredDraft(
          acc.feature.id,
          piece,
          reason = "partial_batch_missing_audit"
        )
        Right(acc.copy(drafts = acc.drafts ++ Vector(syntheticAudit, harness)))

      case PieceCase.CrashWindow =>
        // §11.5 crash window proper: synthesize the `Merged` event and let `Fsm.transition` produce both the
        // fsm.transition draft and the audit.piece_merged draft. The B4 idempotency rule makes this safe (manifest is
        // already merged; mutation is a no-op).
        val prNumber = piece.prNumber.get
        val mergeCommit = piece.mergeCommit.get
        val mergedAt = piece.mergedAt.get
        if !isPieceAwaitingMergeForPiece(acc.feature.state, piece.id, prNumber) then
          Left(
            RebuildError.InconsistentRecovery(
              s"piece ${piece.id.value}: manifest says merged but fold-state is ${acc.feature.state} (expected " +
                s"PieceAwaitingMerge(${piece.id.value}, ${prNumber.value}))"
            )
          )
        else
          // observedAt = mergedAt because at recovery time it's the closest historical fact available; using rebuild-
          // time `now` would distort the Refining.startedAt elapsed clock more aggressively (see PR-B B4).
          val syntheticMerged = FsmEvent.Merged(piece.id, prNumber, mergeCommit, mergedAt, observedAt = mergedAt)
          val (updatedFeature, fsmDrafts) = Fsm.transition(acc.feature, syntheticMerged)
          val harness = harnessCrashRecoveredDraft(
            acc.feature.id,
            piece,
            reason = "crash_window_synthetic_merged"
          )
          Right(
            ReconcileAccumulator(
              feature = updatedFeature,
              drafts = acc.drafts ++ fsmDrafts ++ Vector(harness)
            )
          )

      case PieceCase.Impossible(reason) =>
        // Already short-circuited in the pre-pass, but keep the match exhaustive.
        Left(RebuildError.InconsistentRecovery(reason))

  private def isPieceAwaitingMergeForPiece(state: FsmState, piece: PieceId, prNumber: PrNumber): Boolean =
    state match
      case FsmState.PieceAwaitingMerge(p, pr) => p == piece && pr == prNumber
      case _ => false

  private def syntheticAuditDraft(feature: FeatureId, piece: Piece): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = Some(piece.id),
      actor = None,
      role = None,
      kind = "audit.piece_merged",
      payload = ujson.Obj(
        "p" -> ujson.Str(piece.id.value),
        "prNumber" -> ujson.Num(piece.prNumber.get.value.toDouble),
        "mergeCommit" -> ujson.Str(piece.mergeCommit.get.value),
        "mergedAt" -> ujson.Str(piece.mergedAt.get.toString)
      )
    )

  private def harnessCrashRecoveredDraft(feature: FeatureId, piece: Piece, reason: String): ActionDraft =
    ActionDraft(
      feature = feature,
      piece = Some(piece.id),
      actor = None,
      role = None,
      kind = "harness.crash_recovered",
      payload = ujson.Obj(
        "piece" -> ujson.Str(piece.id.value),
        "reason" -> ujson.Str(reason),
        "manifestPrNumber" -> ujson.Num(piece.prNumber.get.value.toDouble)
      )
    )
