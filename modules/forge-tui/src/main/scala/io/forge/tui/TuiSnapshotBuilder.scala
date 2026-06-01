package io.forge.tui

import cats.effect.IO
import io.forge.core.{FeatureId, Question}
import io.forge.core.fsm.{Feature, FsmState}
import io.forge.core.log.Action
import io.forge.core.manifest.{FileManifestStore, Manifest}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.core.status.StatusFields

import upickle.default as upickle

/** Slice 2.1 Task 2.1.2 — folds a feature's committed data (state cache + action log + manifest) into the pure
  * [[TuiSnapshot]] the panes render.
  *
  * **Reuses `StatusReport.renderFeature`'s field semantics, not a copy of them.** The status-pane fields (state label,
  * piece label, last action, budget-vs-cap) come from the shared `forge-core` [[StatusFields]] helpers that
  * `StatusReport` also delegates to — so `forge tui` and `forge status` cannot drift (`StatusReportGoldenSuite` pins
  * the `forge status` side byte-for-byte). On top of that the budget line carries a `StatsReport`-style cost/turn
  * roll-up (the turn count folded from `session.complete` records), and the active pane is selected from the FSM state
  * via [[ActivePane.forState]].
  *
  * **Read-only (§15), log-tail-first.** [[load]] mirrors `StatusReport`'s read-only stance exactly: it reads the
  * rebuildable [[FileStateCache]] directly (the §11.0 fast path, never [[io.forge.core.state.RebuildState]], which
  * would write) and decodes `.forge/log/<feature>.jsonl` **in place** (never [[io.forge.core.log.ActionLog.replay]],
  * whose repair-on-read could write). It never takes the §13 process lock, so it is safe to poll against an in-flight
  * `forge run`. The `forge tui` command (Task 2.1.3) calls [[load]] on each refresh tick. A live `AgentEvent` tap for
  * token-level streaming is deferred to Task 2.1.5; until then the Streaming pane stands in with the committed log
  * tail.
  */
object TuiSnapshotBuilder:

  /** Scrollback budget for the LogTail / Streaming active pane: the most-recent N committed actions are rendered
    * oldest→newest. Because Task 2.1.4 made the pane scrollable, this is a real scrollback buffer — not the original
    * 10-line tail, which left `PageUp` with nothing to reveal in production (`forge tui` feeds the *whole* decoded log
    * in; capping at 10 before the UI saw it defeated the scroll feature). When the log is longer than the cap, a marker
    * line is prepended so the truncation is visible rather than silent.
    */
  private val MaxTailLines = 500

  /** Max question prompt lines surfaced in the Question pane. */
  private val MaxQuestionLines = 8

  /** Pure fold of committed data into a [[TuiSnapshot]] — the unit-testable seam (no I/O).
    *
    * `cached` is `None` for a feature with no state cache yet (freshly `forge new`'d, or post-`rebuild-state`): the
    * snapshot then renders manifest-only, matching the §2.5 `forge status` fallback. `actions` is the committed action
    * log decoded in `seq` order (its last entry is the most recent); only the tail is surfaced in the active pane.
    */
  def build(
      manifest: Manifest,
      cached: Option[Feature],
      actions: Vector[Action],
      maxFeatureCostUsd: Double,
      maxPieceCostUsd: Double
  ): TuiSnapshot =
    val state = cached.map(_.state)
    val pane = state.map(ActivePane.forState).getOrElse(ActivePane.LogTail)
    TuiSnapshot(
      featureId = manifest.featureId.value,
      title = manifest.title,
      mode = manifest.mode.toString,
      stateLabel = state.map(StatusFields.stateLabel).getOrElse(StatusFields.NoStateCacheLabel),
      pieceLabel = StatusFields.pieceLabel(manifest, state),
      lastAction = StatusFields.lastActionLabel(actions.lastOption),
      budgetLine = budgetLine(cached, actions, maxFeatureCostUsd, maxPieceCostUsd),
      activePane = pane,
      activeLines = activeLines(pane, state, actions)
    )

  /** Read-only (§15) fold of a feature's committed data into a snapshot, or `None` when there is no readable manifest
    * (no such feature, or a corrupt `manifest.json`). The `forge tui` command (Task 2.1.3) polls this; see the class
    * docstring for the read-only guarantees.
    */
  def load(
      paths: ForgePaths,
      id: FeatureId,
      maxFeatureCostUsd: Double,
      maxPieceCostUsd: Double
  ): IO[Option[TuiSnapshot]] =
    new FileManifestStore(paths).load(id).flatMap {
      case Left(_) => IO.pure(None)
      case Right(manifest) =>
        for
          cached <- new FileStateCache(paths).load(id)
          actions <- readActions(paths, id)
        yield Some(build(manifest, cached, actions, maxFeatureCostUsd, maxPieceCostUsd))
    }

  /** Decode every NDJSON line of the feature log as an [[Action]] in file (`seq`) order, skipping any malformed line (a
    * partially-written tail decodes to nothing rather than failing). **Does not rewrite the file** — the §15 read-only
    * decode, mirroring `StatsReport.readActions` / `StatusReport.lastAction`.
    */
  private[tui] def readActions(paths: ForgePaths, id: FeatureId): IO[Vector[Action]] =
    IO.blocking {
      val p = paths.featureLog(id)
      if !os.exists(p) then Vector.empty
      else
        os.read
          .lines(p)
          .iterator
          .map(_.trim)
          .filter(_.nonEmpty)
          .flatMap(line => scala.util.Try(upickle.read[Action](line)).toOption)
          .toVector
    }

  /** The budget-vs-cap line with a `StatsReport`-style turn roll-up appended. The base (feature + piece spend vs §18
    * caps) is the shared [[StatusFields.budgetLine]] — identical to `forge status`; the ` · N turn(s)` suffix counts
    * the `session.complete` records (one per settled driver turn, the StatsReport turn unit). Suppressed when no turn
    * has settled yet so a brand-new feature's budget line stays clean.
    */
  private def budgetLine(
      cached: Option[Feature],
      actions: Vector[Action],
      maxFeatureCostUsd: Double,
      maxPieceCostUsd: Double
  ): String =
    val base = StatusFields.budgetLine(cached.map(_.cost), maxFeatureCostUsd, maxPieceCostUsd)
    val turns = actions.count(_.kind == "session.complete")
    if turns == 0 then base
    else s"$base · $turns turn${if turns == 1 then "" else "s"}"

  /** The active-pane lines for the selected pane:
    *
    *   - [[ActivePane.LogTail]] / [[ActivePane.Streaming]] → the committed log tail (Streaming stands in with recent
    *     committed activity until the Task 2.1.5 live tap lands);
    *   - [[ActivePane.Question]] → the pending question prompts + a pointer at the existing answer path;
    *   - [[ActivePane.Idle]] → empty (the view shows an "idle" placeholder).
    */
  private def activeLines(pane: ActivePane, state: Option[FsmState], actions: Vector[Action]): Vector[String] =
    pane match
      case ActivePane.LogTail | ActivePane.Streaming => logTailLines(actions)
      case ActivePane.Question => questionLines(state)
      case ActivePane.Idle => Vector.empty

  /** The most-recent [[MaxTailLines]] committed actions (oldest→newest), with a marker line prepended when older
    * history was dropped so the operator can tell the pane is capped rather than empty above.
    */
  private def logTailLines(actions: Vector[Action]): Vector[String] =
    val hidden = actions.size - MaxTailLines
    val tail = actions.takeRight(MaxTailLines).map(actionLine)
    if hidden <= 0 then tail
    else
      s"… $hidden older action${if hidden == 1 then "" else "s"} not shown (scrollback capped at $MaxTailLines)" +: tail

  /** A compact one-line render of an action for the log-tail pane: `#<seq> <kind> [<piece>] @ <ts>`. */
  private def actionLine(a: Action): String =
    val piece = a.piece.map(p => s" [${p.value}]").getOrElse("")
    s"#${a.seq} ${a.kind}$piece @ ${a.at}"

  /** Question-pane lines for the human-blocking states. v1 **displays** the pending questions and points the operator
    * at the existing answer path; answering from the TUI would need the write path + lock, breaking §15 read-only.
    *
    * **Scope (finding 3):** only the questions/reason carried *in the cached FSM state* are surfaced — these are the
    * facts the §15 read-only projection can actually observe. A driver's mid-turn `AskUserQuestion` is **not** written
    * to the action log as a durable "unanswered question" record (the orchestrator only commits the kinds listed in §19
    * — `session.complete` / `cost.update` / `harness.*` / `audit.*` / `user.command` — never a `.ask_user_question`
    * row), so there is nothing for the TUI to poll for those. Surfacing live driver questions needs either a durable
    * "question opened" audit event or the Task 2.1.5 live tap — see `docs/design-2.1-tui.md` §4 T4.
    */
  private def questionLines(state: Option[FsmState]): Vector[String] =
    state match
      case Some(FsmState.DesignNeedsHumanInput(round, questions)) =>
        val header = Vector(s"design review round $round — ${questions.size} question(s):")
        val qs = questions.flatMap(renderQuestion).take(MaxQuestionLines)
        header ++ qs ++ Vector(answerPath("forge spec"))
      case Some(FsmState.NeedsHumanIntervention(reason, _)) =>
        Vector("needs human intervention:", s"  $reason", answerPath("forge resume"))
      case _ => Vector.empty

  private def renderQuestion(q: Question): Vector[String] =
    val prefix = s"  [${q.severity.asString}] "
    val options =
      if q.options.isEmpty then Vector.empty
      else Vector(s"    options: ${q.options.mkString(", ")}")
    val freeText =
      if q.allowFreeText then Vector("    free text allowed")
      else Vector.empty
    val default =
      q.defaultOption.map(d => Vector(s"    default: $d")).getOrElse(Vector.empty)
    Vector(prefix + q.text) ++ options ++ freeText ++ default

  private def answerPath(command: String): String =
    s"display-only; answer via: $command"
