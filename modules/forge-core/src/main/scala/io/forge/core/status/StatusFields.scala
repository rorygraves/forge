package io.forge.core.status

import io.forge.core.PieceId
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.FsmState
import io.forge.core.log.Action
import io.forge.core.manifest.{Manifest, PieceStatus}

/** Pure render helpers for a feature's at-a-glance status fields — the FSM-state label, active-piece label, last-action
  * line, and budget-vs-cap line.
  *
  * Lifted out of `io.forge.app.command.StatusReport` so the §2.5 `forge status` block **and** the Slice-2.1 TUI
  * snapshot builder ([[io.forge.tui.TuiSnapshot]] via `TuiSnapshotBuilder`) render the *same* field semantics rather
  * than each re-deriving them — the `design-2.1-tui.md` Task 2.1.2 requirement to "reuse `StatusReport.renderFeature`'s
  * field semantics". `forge-tui` cannot depend on `forge-app` (the dependency runs the other way: `forge-app dependsOn
  * forge-tui`), so the shared pure bits live here in `forge-core`. `StatusReport` delegates to these helpers and its
  * `StatusReportGoldenSuite` pins the rendering byte-for-byte, so the two views cannot drift.
  */
object StatusFields:

  /** Fallback state line shown when no state cache exists yet (a freshly `forge new`'d feature, or a
    * post-`rebuild-state` deletion). Matches the §2.5 status wording.
    */
  val NoStateCacheLabel: String = "no state cache yet — run `forge run` or `forge rebuild-state`"

  /** A compact human label for each FSM state. Total over the FSM. */
  def stateLabel(s: FsmState): String = s match
    case FsmState.Drafting => "drafting (spec not started)"
    case FsmState.InteractiveSpec => "interactive spec in progress"
    case FsmState.DesignReviewing(round) => s"design review round $round"
    case FsmState.DesignNeedsHumanInput(round, questions) =>
      s"design review round $round — awaiting human answers (${questions.size} question(s))"
    case FsmState.DesignAwaitingMerge(pr) => s"design PR #${pr.value} open — awaiting merge"
    case FsmState.DesignPrFeedback(pr, round) => s"design PR #${pr.value} feedback round $round"
    case FsmState.DesignReady => "design merged — ready to implement"
    case FsmState.PieceImplementing(p) => s"implementing piece ${p.value}"
    case FsmState.PieceAwaitingCi(p, pr) => s"piece ${p.value} PR #${pr.value} — awaiting CI"
    case FsmState.PieceAwaitingReview(p, pr) => s"piece ${p.value} PR #${pr.value} — awaiting review/merge"
    case FsmState.PieceCiFailed(p, pr, attempt) => s"piece ${p.value} PR #${pr.value} — CI failed (attempt $attempt)"
    case FsmState.PieceReviewFailed(p, pr, attempt) =>
      s"piece ${p.value} PR #${pr.value} — review requested changes (attempt $attempt)"
    case FsmState.PieceFixingUp(p, pr, attempt) =>
      s"piece ${p.value} PR #${pr.value} — fix-up running (attempt $attempt)"
    case FsmState.PieceBuildFailed(p, attempt) =>
      s"piece ${p.value} — local build failed pre-PR (fix-up attempt $attempt)"
    case FsmState.PieceBuildFixingUp(p, attempt) =>
      s"piece ${p.value} — pre-PR build fix-up running (attempt $attempt)"
    case FsmState.PieceAwaitingMerge(p, pr) => s"piece ${p.value} PR #${pr.value} — green, awaiting merge"
    case FsmState.Refining(p, pr, _) => s"refining after merge of piece ${p.value} (PR #${pr.value})"
    case FsmState.PlanningUpdate(reason, _) => s"planning update proposed — $reason"
    case FsmState.NeedsHumanIntervention(reason, _) => s"needs human intervention — $reason"
    case FsmState.FeatureDone => "✓ complete (all pieces merged and refined)"
    case FsmState.Abandoned(reason) => s"abandoned — $reason"

  /** The active piece id a state carries, if any. */
  def pieceOf(s: FsmState): Option[PieceId] = s match
    case FsmState.PieceImplementing(p) => Some(p)
    case FsmState.PieceAwaitingCi(p, _) => Some(p)
    case FsmState.PieceAwaitingReview(p, _) => Some(p)
    case FsmState.PieceCiFailed(p, _, _) => Some(p)
    case FsmState.PieceReviewFailed(p, _, _) => Some(p)
    case FsmState.PieceFixingUp(p, _, _) => Some(p)
    case FsmState.PieceBuildFailed(p, _) => Some(p)
    case FsmState.PieceBuildFixingUp(p, _) => Some(p)
    case FsmState.PieceAwaitingMerge(p, _) => Some(p)
    case FsmState.Refining(p, _, _) => Some(p)
    case _ => None

  /** The active-piece label: `<id> — <title>` for a state carrying an active piece, else a manifest piece-count
    * summary. `state` is `None` when there is no state cache yet (manifest-only rendering).
    */
  def pieceLabel(manifest: Manifest, state: Option[FsmState]): String =
    state.flatMap(pieceOf) match
      case Some(p) =>
        val title = manifest.pieces.find(_.id == p).map(pp => s" — ${pp.title}").getOrElse("")
        s"${p.value}$title"
      case None => pieceSummary(manifest)

  /** Manifest piece-count summary, shown when no single piece is active. */
  def pieceSummary(manifest: Manifest): String =
    if manifest.pieces.isEmpty then "— (no pieces decomposed yet)"
    else
      val merged = manifest.pieces.count(_.status == PieceStatus.Merged)
      val inProgress = manifest.pieces.count(_.status == PieceStatus.InProgress)
      val pending = manifest.pieces.count(_.status == PieceStatus.Pending)
      s"— (${manifest.pieces.size} pieces: $merged merged, $inProgress in progress, $pending pending)"

  /** The last-action line: `<kind> @ <at>`, or a placeholder when nothing is logged. */
  def lastActionLabel(last: Option[Action]): String =
    last match
      case Some(a) => s"${a.kind} @ ${a.at}"
      case None => "— (no actions logged)"

  /** The budget-vs-cap line: feature + piece spend against their §18 caps, or a placeholder when no cost is recorded
    * (no state cache yet). `cost` is `None` for the manifest-only rendering.
    */
  def budgetLine(cost: Option[CostTotals], maxFeatureCostUsd: Double, maxPieceCostUsd: Double): String =
    cost match
      case Some(c) =>
        f"feature $$${c.feature.toDouble}%.2f / $$${maxFeatureCostUsd}%.2f · " +
          f"piece $$${c.piece.toDouble}%.2f / $$${maxPieceCostUsd}%.2f"
      case None => "— (no cost recorded yet)"
