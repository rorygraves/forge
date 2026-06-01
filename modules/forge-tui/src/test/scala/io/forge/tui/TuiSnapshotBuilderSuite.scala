package io.forge.tui

import cats.effect.unsafe.implicits.global
import io.forge.core.{BranchName, FeatureId, Mode, PieceId, PrNumber, Question, QuestionSeverity, Sha}
import io.forge.core.cost.CostTotals
import io.forge.core.fsm.{Feature, FsmState, ResumeHint}
import io.forge.core.log.Action
import io.forge.core.manifest.{Manifest, Piece, PieceStatus}
import io.forge.core.paths.ForgePaths
import io.forge.core.status.StatusFields

import java.time.Instant

/** Slice 2.1 Task 2.1.2 coverage: the pure [[TuiSnapshotBuilder.build]] fold (status fields, the StatsReport-style turn
  * roll-up, and the [[ActivePane.forState]] selection) plus the read-only [[TuiSnapshotBuilder.load]] IO seam.
  *
  * The status-pane field semantics are shared with `forge status` via `forge-core`'s `StatusFields` (which
  * `StatusReport` delegates to and `StatusReportGoldenSuite` pins byte-for-byte), so these tests assert the
  * *projection* — that the builder reads the right field for each pane slot and maps states to panes per the design —
  * rather than re-pinning the label wording.
  */
class TuiSnapshotBuilderSuite extends munit.FunSuite:

  private val featureId = FeatureId("stripe-webhook")
  private val p1 = PieceId("p1")
  private val pr7 = PrNumber(7)

  private def piece(id: String, title: String, status: PieceStatus, order: Int, pr: Option[PrNumber] = None): Piece =
    Piece(
      id = PieceId(id),
      order = order,
      title = title,
      summary = "s",
      specPath = s"pieces/$id.md",
      acceptanceHash = "h",
      status = status,
      baseSha = if status == PieceStatus.Pending then None else Some(Sha("abc1234")),
      prNumber = pr,
      mergeCommit = None,
      mergedAt = None,
      attempts = 0
    )

  private val manifest: Manifest =
    Manifest(
      schemaVersion = Manifest.CurrentSchemaVersion,
      featureId = featureId,
      title = "Add Stripe webhook receiver",
      baseBranch = BranchName("main"),
      branchPrefix = "forge",
      mode = Mode.ClaudeDriver,
      designPr = None,
      pieces = Vector(
        piece("p1", "Wire the parser", PieceStatus.InProgress, 1, Some(pr7)),
        piece("p2", "Add the cache", PieceStatus.Pending, 2)
      )
    )

  private def featureIn(
      state: FsmState,
      cost: CostTotals = CostTotals(BigDecimal("1.50"), BigDecimal("0.75"), 0)
  ): Feature =
    Feature.initial(featureId, manifest).copy(state = state, cost = cost)

  private def action(seq: Long, kind: String, piece: Option[PieceId] = None): Action =
    Action(
      seq = seq,
      at = Instant.parse("2026-06-01T10:00:00Z"),
      feature = featureId,
      piece = piece,
      actor = None,
      role = None,
      kind = kind,
      payload = ujson.Obj()
    )

  /** A representative log: two settled turns (`session.complete`) plus a trailing transition. */
  private val actions: Vector[Action] = Vector(
    action(1, "session.start", Some(p1)),
    action(2, "session.complete", Some(p1)),
    action(3, "cost.update"),
    action(4, "session.complete", Some(p1)),
    action(5, "fsm.transition", Some(p1))
  )

  // --- pure build: status fields -------------------------------------------

  test("build maps the manifest + cache into the status fields"):
    val snap = TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), actions, 25.0, 8.0)
    assertEquals(snap.featureId, "stripe-webhook")
    assertEquals(snap.title, "Add Stripe webhook receiver")
    assertEquals(snap.mode, "ClaudeDriver")
    assertEquals(snap.stateLabel, "implementing piece p1")
    assertEquals(snap.pieceLabel, "p1 — Wire the parser")
    // last action = the most recent log line (highest seq, last in file order).
    assertEquals(snap.lastAction, "fsm.transition @ 2026-06-01T10:00:00Z")

  test("budget line reuses the status budget-vs-cap and appends a StatsReport-style turn roll-up"):
    val snap = TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), actions, 25.0, 8.0)
    assertEquals(snap.budgetLine, "feature $1.50 / $25.00 · piece $0.75 / $8.00 · 2 turns")

  test("budget roll-up is singular for one turn and suppressed for none"):
    val one =
      TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), actions.take(2), 25.0, 8.0)
    assert(one.budgetLine.endsWith("· 1 turn"), one.budgetLine)
    val none =
      TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), Vector.empty, 25.0, 8.0)
    assertEquals(none.budgetLine, "feature $1.50 / $25.00 · piece $0.75 / $8.00")

  test("no state cache → manifest-only fallback (no-cache hints, LogTail, empty lines)"):
    val snap = TuiSnapshotBuilder.build(manifest, None, Vector.empty, 25.0, 8.0)
    assertEquals(snap.stateLabel, StatusFields.NoStateCacheLabel)
    assertEquals(snap.pieceLabel, "— (2 pieces: 0 merged, 1 in progress, 1 pending)")
    assertEquals(snap.lastAction, "— (no actions logged)")
    assertEquals(snap.budgetLine, "— (no cost recorded yet)")
    assertEquals(snap.activePane, ActivePane.LogTail)
    assertEquals(snap.activeLines, Vector.empty[String])

  // --- pane selection -------------------------------------------------------

  test("ActivePane.forState maps states to panes per the design"):
    assertEquals(ActivePane.forState(FsmState.PieceImplementing(p1)), ActivePane.Streaming)
    assertEquals(ActivePane.forState(FsmState.PieceFixingUp(p1, pr7, 1)), ActivePane.Streaming)
    assertEquals(
      ActivePane.forState(FsmState.Refining(p1, pr7, Instant.parse("2026-06-01T10:00:00Z"))),
      ActivePane.Streaming
    )
    assertEquals(ActivePane.forState(FsmState.DesignNeedsHumanInput(1, Vector.empty)), ActivePane.Question)
    assertEquals(
      ActivePane.forState(FsmState.NeedsHumanIntervention("boom", ResumeHint.AbortOrAbandon)),
      ActivePane.Question
    )
    assertEquals(ActivePane.forState(FsmState.PieceAwaitingCi(p1, pr7)), ActivePane.Idle)
    assertEquals(ActivePane.forState(FsmState.PieceAwaitingReview(p1, pr7)), ActivePane.Idle)
    assertEquals(ActivePane.forState(FsmState.PieceAwaitingMerge(p1, pr7)), ActivePane.Idle)
    assertEquals(ActivePane.forState(FsmState.DesignAwaitingMerge(pr7)), ActivePane.Idle)
    // spec/design driver phases + terminals fall to the committed-log view.
    assertEquals(ActivePane.forState(FsmState.InteractiveSpec), ActivePane.LogTail)
    assertEquals(ActivePane.forState(FsmState.DesignReviewing(1)), ActivePane.LogTail)
    assertEquals(ActivePane.forState(FsmState.FeatureDone), ActivePane.LogTail)

  // --- active-pane lines ----------------------------------------------------

  test("LogTail / Streaming render the committed log tail, capped and oldest→newest"):
    // PieceImplementing → Streaming; both Streaming and LogTail surface the tail.
    val snap = TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), actions, 25.0, 8.0)
    assertEquals(snap.activePane, ActivePane.Streaming)
    assertEquals(snap.activeLines.size, actions.size)
    assertEquals(snap.activeLines.head, "#1 session.start [p1] @ 2026-06-01T10:00:00Z")
    assertEquals(snap.activeLines.last, "#5 fsm.transition [p1] @ 2026-06-01T10:00:00Z")

  test("log tail under the cap is shown in full with no truncation marker"):
    val many = (1L to 15L).map(s => action(s, "cost.update")).toVector
    val snap = TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), many, 25.0, 8.0)
    assertEquals(snap.activeLines.size, 15)
    assert(snap.activeLines.head.startsWith("#1 "), snap.activeLines.head)
    assert(snap.activeLines.last.startsWith("#15 "), snap.activeLines.last)
    assert(!snap.activeLines.exists(_.contains("not shown")), snap.activeLines.toString)

  test("log tail over the cap keeps the most-recent lines and prepends a truncation marker"):
    // 510 actions over the 500-line cap → 10 dropped, surfaced as a marker line above the 500 kept (oldest→newest).
    val many = (1L to 510L).map(s => action(s, "cost.update")).toVector
    val snap = TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceImplementing(p1))), many, 25.0, 8.0)
    assertEquals(snap.activeLines.size, 501) // 500 kept + 1 marker
    assert(snap.activeLines.head.contains("10 older actions not shown"), snap.activeLines.head)
    assert(snap.activeLines.head.contains("scrollback capped at 500"), snap.activeLines.head)
    assert(snap.activeLines(1).startsWith("#11 "), snap.activeLines(1)) // oldest *shown* action
    assert(snap.activeLines.last.startsWith("#510 "), snap.activeLines.last)

  test("Question pane lists the pending design questions + the answer pointer"):
    val qs = Vector(
      Question("Why verify the signature?", Vector("a", "b"), allowFreeText = true, QuestionSeverity.Blocking),
      Question("Which store?", Vector("redis", "pg"), allowFreeText = false, QuestionSeverity.Blocking)
    )
    val snap =
      TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.DesignNeedsHumanInput(2, qs))), actions, 25.0, 8.0)
    assertEquals(snap.activePane, ActivePane.Question)
    assert(snap.activeLines.contains("design review round 2 — 2 question(s):"), snap.activeLines.toString)
    assert(snap.activeLines.contains("  [blocking] Why verify the signature?"), snap.activeLines.toString)
    assert(snap.activeLines.contains("    options: a, b"), snap.activeLines.toString)
    assert(snap.activeLines.contains("    free text allowed"), snap.activeLines.toString)
    assert(snap.activeLines.contains("  [blocking] Which store?"), snap.activeLines.toString)
    assert(snap.activeLines.contains("display-only; answer via: forge spec"), snap.activeLines.toString)

  test("Question pane for needs-human-intervention shows the reason + resume pointer"):
    val snap = TuiSnapshotBuilder.build(
      manifest,
      Some(
        featureIn(FsmState.NeedsHumanIntervention("ci failed after 3 fix-ups", ResumeHint.RunAnotherFixup(p1, pr7)))
      ),
      actions,
      25.0,
      8.0
    )
    assertEquals(snap.activePane, ActivePane.Question)
    assert(snap.activeLines.contains("  ci failed after 3 fix-ups"), snap.activeLines.toString)
    assert(snap.activeLines.contains("display-only; answer via: forge resume"), snap.activeLines.toString)

  // Finding 3: the Question pane surfaces only what the §15 read-only projection can observe in the cached FSM state.
  // A driver's mid-turn AskUserQuestion is never written to the action log as a durable unanswered-question record
  // (the orchestrator commits no `.ask_user_question` kind), so the TUI cannot — and no longer claims to — show it; a
  // log tail that happens to contain such a row is inert. Live driver questions await §4 T4 (durable event / live tap).
  test("Question pane ignores action-log content — only the cached state drives it"):
    val ask = action(6, "claude.ask_user_question").copy(
      payload = ujson.Obj("question" -> ujson.Str("Continue with the smaller migration?"))
    )
    val snap = TuiSnapshotBuilder.build(
      manifest,
      Some(featureIn(FsmState.NeedsHumanIntervention("driver paused", ResumeHint.AbortOrAbandon))),
      actions :+ ask,
      25.0,
      8.0
    )
    assertEquals(
      snap.activeLines,
      Vector("needs human intervention:", "  driver paused", "display-only; answer via: forge resume")
    )
    assert(!snap.activeLines.exists(_.contains("Continue with the smaller migration?")), snap.activeLines.toString)

  test("Idle pane carries no committed lines (the view paints a placeholder)"):
    val snap =
      TuiSnapshotBuilder.build(manifest, Some(featureIn(FsmState.PieceAwaitingCi(p1, pr7))), actions, 25.0, 8.0)
    assertEquals(snap.activePane, ActivePane.Idle)
    assertEquals(snap.activeLines, Vector.empty[String])

  // --- read-only load (IO seam) --------------------------------------------

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-tui-builder-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private def writeManifest(paths: ForgePaths): Unit =
    val mp = paths.manifest(featureId)
    os.makeDir.all(mp / os.up)
    os.write(mp, Manifest.toJson(manifest))

  private def writeLog(paths: ForgePaths): Unit =
    val lp = paths.featureLog(featureId)
    os.makeDir.all(lp / os.up)
    os.write(lp, actions.map(a => upickle.default.write(a)).mkString("", "\n", "\n"))

  tempFixture.test("load folds committed manifest + cache + log into a snapshot"): root =>
    val paths = new ForgePaths(root)
    writeManifest(paths)
    writeLog(paths)
    new io.forge.core.state.FileStateCache(paths)
      .save(featureId, featureIn(FsmState.PieceImplementing(p1)))
      .unsafeRunSync()
    val snap = TuiSnapshotBuilder.load(paths, featureId, 25.0, 8.0).unsafeRunSync()
    assertEquals(snap.map(_.stateLabel), Some("implementing piece p1"))
    assertEquals(snap.map(_.budgetLine), Some("feature $1.50 / $25.00 · piece $0.75 / $8.00 · 2 turns"))
    assertEquals(snap.map(_.activePane), Some(ActivePane.Streaming))
    assertEquals(snap.map(_.activeLines.last), Some("#5 fsm.transition [p1] @ 2026-06-01T10:00:00Z"))

  tempFixture.test("load on a feature with a manifest but no cache → manifest-only snapshot"): root =>
    val paths = new ForgePaths(root)
    writeManifest(paths)
    val snap = TuiSnapshotBuilder.load(paths, featureId, 25.0, 8.0).unsafeRunSync()
    assertEquals(snap.map(_.stateLabel), Some(StatusFields.NoStateCacheLabel))
    assertEquals(snap.map(_.activePane), Some(ActivePane.LogTail))

  tempFixture.test("load on an unknown feature (no manifest) → None"): root =>
    val snap = TuiSnapshotBuilder.load(new ForgePaths(root), featureId, 25.0, 8.0).unsafeRunSync()
    assertEquals(snap, None)

  tempFixture.test("load decodes the log in place (read-only) — the file is left byte-for-byte unchanged"): root =>
    val paths = new ForgePaths(root)
    writeManifest(paths)
    writeLog(paths)
    val before = os.read(paths.featureLog(featureId))
    val _ = TuiSnapshotBuilder.load(paths, featureId, 25.0, 8.0).unsafeRunSync()
    assertEquals(os.read(paths.featureLog(featureId)), before)
