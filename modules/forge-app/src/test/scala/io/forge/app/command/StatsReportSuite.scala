package io.forge.app.command

import cats.effect.ExitCode
import cats.effect.unsafe.implicits.global
import io.forge.core.{FeatureId, PieceId}
import io.forge.core.fsm.SessionPhase
import io.forge.core.log.Action
import io.forge.core.paths.ForgePaths

import java.time.Instant

/** Slice 2.0 Task 2.0.3 — `forge stats <feature>`. The fold ([[StatsReport.fold]]) and render ([[StatsReport.render]])
  * are exercised as pure seams; the handler path is exercised on a missing log and a synthetic log (read-only, no lock
  * / git / connector — mirrors [[ReadOnlyHandlerSuite]]).
  *
  * The fixture log mirrors the exact `session.complete` / `cost.update` wire shapes the orchestrator writes
  * (`OrchestratorCostUpdateWriterSuite`): a Spec turn then two Implement turns, with the running `featureTotalUsd`
  * accumulating across turns.
  */
class StatsReportSuite extends munit.FunSuite:

  private val tempFixture = FunFixture[os.Path](
    setup = _ => os.temp.dir(prefix = "forge-stats-"),
    teardown = dir => if os.exists(dir) then os.remove.all(dir)
  )

  private val featureId = FeatureId("feat")
  private val p1 = PieceId("p1")

  private var seq = 0L
  private def nextSeq(): Long = { seq += 1; seq }

  private def sessionComplete(
      phase: SessionPhase,
      piece: Option[PieceId],
      durationMs: Option[Long],
      turnCostUsd: BigDecimal,
      success: Boolean = true,
      resumed: Boolean = false
  ): Action =
    Action(
      seq = nextSeq(),
      at = Instant.parse("2026-05-31T10:00:00Z"),
      feature = featureId,
      piece = piece,
      actor = Some("driver"),
      role = Some("driver"),
      kind = "session.complete",
      payload = ujson.Obj(
        "phase" -> ujson.Str(phase.toString),
        "piece" -> piece.map(p => ujson.Str(p.value)).getOrElse(ujson.Null),
        "durationMs" -> durationMs.map(d => ujson.Num(d.toDouble)).getOrElse(ujson.Null),
        "model" -> ujson.Str("haiku"),
        "turnCostUsd" -> ujson.Num(turnCostUsd.toDouble),
        "success" -> ujson.Bool(success),
        "resumed" -> ujson.Bool(resumed)
      )
    )

  private def costUpdate(piece: Option[PieceId], usd: BigDecimal, featureTotalUsd: BigDecimal): Action =
    Action(
      seq = nextSeq(),
      at = Instant.parse("2026-05-31T10:00:00Z"),
      feature = featureId,
      piece = piece,
      actor = Some("driver"),
      role = Some("driver"),
      kind = "cost.update",
      payload = ujson.Obj(
        "provider" -> ujson.Str("anthropic"),
        "model" -> ujson.Str("haiku"),
        "inputTokens" -> ujson.Num(1200),
        "outputTokens" -> ujson.Num(340),
        "usd" -> ujson.Num(usd.toDouble),
        "featureTotalUsd" -> ujson.Num(featureTotalUsd.toDouble),
        "pieceTotalUsd" -> ujson.Num(usd.toDouble),
        "turnTotalUsd" -> ujson.Num(usd.toDouble)
      )
    )

  private def transition(): Action =
    Action(nextSeq(), Instant.parse("2026-05-31T10:00:00Z"), featureId, None, None, None, "fsm.transition", ujson.Obj())

  /** An `fsm.transition` at `atTs`, optionally carrying a wait marker (`wait` enter/leave). Task 2.0.4. */
  private def transitionAt(atTs: String, wait: Option[(String, String)] = None): Action =
    val base = ujson.Obj("from" -> ujson.Str("X"), "to" -> ujson.Str("Y"))
    wait.foreach { case (edge, kind) =>
      base("wait") = ujson.Obj("edge" -> ujson.Str(edge), "kind" -> ujson.Str(kind))
    }
    Action(nextSeq(), Instant.parse(atTs), featureId, None, None, None, "fsm.transition", base)

  /** An `audit.resume_from_nhi` marker (Task 2.0.6) — the orchestrator's wire shape for an operator resume from NHI. */
  private def auditResume(piece: Option[PieceId] = None): Action =
    Action(
      nextSeq(),
      Instant.parse("2026-05-31T10:00:00Z"),
      featureId,
      piece,
      actor = Some("operator"),
      role = None,
      "audit.resume_from_nhi",
      ujson.Obj("hint" -> ujson.Str("RunAnotherFixup"), "reason" -> ujson.Str("ci exhausted"))
    )

  // --- fold ----------------------------------------------------------------

  test("fold aggregates turns / wall-clock / cost per phase and a feature total"):
    val actions = Vector(
      sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50")),
      costUpdate(None, BigDecimal("1.50"), BigDecimal("1.50")),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(9100), BigDecimal("1.50")),
      costUpdate(Some(p1), BigDecimal("1.50"), BigDecimal("3.00")),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(3000), BigDecimal("0.50")),
      costUpdate(Some(p1), BigDecimal("0.50"), BigDecimal("3.50"))
    )
    val summary = StatsReport.fold(actions)
    // Phases are ordered Spec then Implement.
    assertEquals(summary.phases.map(_.phase), Vector(SessionPhase.Spec, SessionPhase.Implement))
    val spec = summary.phases.head
    assertEquals(spec.turns, 1)
    assertEquals(spec.knownDurationMs, 5000L)
    assertEquals(spec.usd, BigDecimal("1.50"))
    val impl = summary.phases(1)
    assertEquals(impl.turns, 2)
    assertEquals(impl.knownDurationMs, 12100L)
    assertEquals(impl.usd, BigDecimal("2.00"))
    assertEquals(summary.totalTurns, 3)
    assertEquals(summary.totalKnownDurationMs, 17100L)
    // featureUsd is taken from the last cost.update's running total (authoritative), here 3.50.
    assertEquals(summary.featureUsd, BigDecimal("3.50"))
    assert(summary.costAvailable)

  test("fold falls back to summed turn cost when no cost.update is present"):
    val actions = Vector(
      sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.25")),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(2000), BigDecimal("0.75"))
    )
    val summary = StatsReport.fold(actions)
    assertEquals(summary.featureUsd, BigDecimal("2.00"))
    assert(summary.costAvailable)

  test("fold records missing durations without crashing"):
    val actions = Vector(
      sessionComplete(SessionPhase.Implement, Some(p1), None, BigDecimal("1.00")),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(4000), BigDecimal("1.00"))
    )
    val summary = StatsReport.fold(actions)
    val impl = summary.phases.head
    assertEquals(impl.turns, 2)
    assertEquals(impl.knownDurationMs, 4000L)
    assertEquals(impl.missingDurations, 1)
    assertEquals(summary.totalMissingDurations, 1)

  test("fold over a log with no session/cost entries → empty, cost unavailable"):
    val summary = StatsReport.fold(Vector(transition(), transition()))
    assert(summary.phases.isEmpty)
    assertEquals(summary.totalTurns, 0)
    assert(!summary.costAvailable)

  test("fold drops session.complete records with an unrecognised phase string"):
    val bogus = sessionComplete(SessionPhase.Spec, None, Some(1000), BigDecimal("1.0"))
      .copy(payload = ujson.Obj("phase" -> ujson.Str("Frobnicate"), "turnCostUsd" -> ujson.Num(1.0)))
    val summary = StatsReport.fold(Vector(bogus))
    assert(summary.phases.isEmpty)

  // --- wait fold (Task 2.0.4) ----------------------------------------------

  test("foldWaits brackets a closed wait interval into its kind (enter → next transition)"):
    val actions = Vector(
      transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "design-merge")),
      transitionAt("2026-05-31T10:30:00Z", wait = Some("leave" -> "design-merge"))
    )
    val summary = StatsReport.fold(actions)
    assertEquals(summary.waitMsByKind.get("design-merge"), Some(1800000L))
    assertEquals(summary.totalWaitMs, 1800000L)
    assertEquals(summary.unclosedWaitKind, None)

  test("foldWaits closes a wait on the next transition even without an explicit leave marker"):
    // enter piece-merge, then a plain (unmarked) transition out — the wait still closes at that transition.
    val actions = Vector(
      transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "piece-merge")),
      transitionAt("2026-05-31T10:05:00Z")
    )
    val summary = StatsReport.fold(actions)
    assertEquals(summary.waitMsByKind.get("piece-merge"), Some(300000L))
    assertEquals(summary.unclosedWaitKind, None)

  test("foldWaits sums distinct wait kinds across a run"):
    val actions = Vector(
      transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "design-merge")),
      transitionAt("2026-05-31T10:10:00Z", wait = Some("leave" -> "design-merge")),
      transitionAt("2026-05-31T10:20:00Z", wait = Some("enter" -> "piece-merge")),
      transitionAt("2026-05-31T10:25:00Z", wait = Some("leave" -> "piece-merge"))
    )
    val summary = StatsReport.fold(actions)
    assertEquals(summary.waitMsByKind.get("design-merge"), Some(600000L))
    assertEquals(summary.waitMsByKind.get("piece-merge"), Some(300000L))
    assertEquals(summary.totalWaitMs, 900000L)

  test("foldWaits flags an interval still open at end of log (crash mid-wait) rather than guessing"):
    val actions = Vector(
      transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "intervention"))
    )
    val waits = StatsReport.foldWaits(actions)
    assertEquals(waits.open.map(_._2), Some("intervention"))
    assertEquals(waits.byKind, Map.empty[String, Long])
    assertEquals(StatsReport.fold(actions).unclosedWaitKind, Some("intervention"))

  test("fold over a pre-marker log records no waits"):
    val summary = StatsReport.fold(
      Vector(sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.5")), transition())
    )
    assert(summary.waitMsByKind.isEmpty)
    assertEquals(summary.unclosedWaitKind, None)

  test(
    "fold counts audit.resume_from_nhi markers and the per-phase timeline stays coherent across the resume boundary"
  ):
    // An Implement turn settles, the run NHIs + is resumed in place, then a second Implement (fix-up) turn settles. The
    // resume marker must neither be counted as a session nor disturb the per-phase aggregation (Task 2.0.6).
    val actions = Vector(
      sessionComplete(SessionPhase.Implement, Some(p1), Some(9000), BigDecimal("1.50")),
      auditResume(Some(p1)),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(3000), BigDecimal("0.50"))
    )
    val summary = StatsReport.fold(actions)
    assertEquals(summary.resumeCount, 1)
    val implement = summary.phases.find(_.phase == SessionPhase.Implement).getOrElse(fail("expected implement phase"))
    assertEquals(implement.turns, 2, "the resume marker is not a session — both Implement turns still aggregate")
    assertEquals(implement.knownDurationMs, 12000L)

  test("fold of a never-resumed run has resumeCount 0"):
    val summary = StatsReport.fold(Vector(sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50"))))
    assertEquals(summary.resumeCount, 0)

  // --- resumed-turn fold (D3-4, roadmap §3.5) ------------------------------

  test("fold counts session.complete records flagged resumed=true per phase (D3-4)"):
    // A fresh implement spawn settles, the run NHIs + a process restart resumes the *same* driver session (D3-3), and
    // the resumed turn settles. Both are Implement turns; only the second is a resumed continuation.
    val actions = Vector(
      sessionComplete(SessionPhase.Implement, Some(p1), Some(9000), BigDecimal("9.56"), resumed = false),
      sessionComplete(SessionPhase.Implement, Some(p1), Some(3000), BigDecimal("0.50"), resumed = true)
    )
    val impl = StatsReport.fold(actions).phases.find(_.phase == SessionPhase.Implement).getOrElse(fail("no implement"))
    assertEquals(impl.turns, 2)
    assertEquals(impl.resumedTurns, 1, "only the second Implement turn continued an existing session")

  test("fold treats a pre-D3-4 session.complete (no `resumed` field) as a fresh spawn, not a resume"):
    val legacy = sessionComplete(SessionPhase.Implement, Some(p1), Some(4000), BigDecimal("1.0"))
      .copy(payload = ujson.Obj("phase" -> ujson.Str("Implement"), "turnCostUsd" -> ujson.Num(1.0)))
    val impl = StatsReport.fold(Vector(legacy)).phases.head
    assertEquals(impl.resumedTurns, 0)

  // --- render --------------------------------------------------------------

  test("render shows a per-phase table with a total row"):
    val summary = StatsReport.fold(
      Vector(
        sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50")),
        sessionComplete(SessionPhase.Implement, Some(p1), Some(9100), BigDecimal("1.50")),
        costUpdate(Some(p1), BigDecimal("1.50"), BigDecimal("3.00"))
      )
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("feature feat"), text)
    assert(text.contains("spec"), text)
    assert(text.contains("implement"), text)
    assert(text.contains("$3.00"), text)
    assert(text.contains("total"), text)

  test("render of an empty summary degrades gracefully"):
    val text = StatsReport.render(featureId, StatsReport.fold(Vector.empty))
    assert(text.contains("no session data recorded"), text)

  test("render notes a missing-duration caveat and cost-unavailable when applicable"):
    val summary = StatsReport.fold(
      Vector(sessionComplete(SessionPhase.Implement, Some(p1), None, BigDecimal("0")))
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("no recorded duration"), text)
    assert(text.contains("cost unavailable"), text)

  test("render shows a waiting (human) breakdown distinct from the working total"):
    val summary = StatsReport.fold(
      Vector(
        sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50")),
        transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "design-merge")),
        transitionAt("2026-05-31T10:30:00Z", wait = Some("leave" -> "design-merge"))
      )
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("waiting (human)"), text)
    assert(text.contains("design merge"), text)
    assert(text.contains("30m 0s"), text)

  test("render of a run that never waited omits the waiting block"):
    val summary = StatsReport.fold(Vector(sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50"))))
    val text = StatsReport.render(featureId, summary)
    assert(!text.contains("waiting (human)"), text)

  test("render notes operator resumes (timeline recovered in place, not truncated)"):
    val summary = StatsReport.fold(
      Vector(
        sessionComplete(SessionPhase.Implement, Some(p1), Some(9000), BigDecimal("1.50")),
        auditResume(Some(p1)),
        auditResume(Some(p1)),
        sessionComplete(SessionPhase.Implement, Some(p1), Some(3000), BigDecimal("0.50"))
      )
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("2 operator resumes from needs-human-intervention"), text)
    assert(text.contains("recovered in place"), text)

  test("render of a never-resumed run omits the resume note"):
    val summary = StatsReport.fold(Vector(sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50"))))
    val text = StatsReport.render(featureId, summary)
    assert(!text.contains("operator resume"), text)

  test("render annotates a phase whose turn(s) resumed an existing driver session (D3-4)"):
    val summary = StatsReport.fold(
      Vector(
        sessionComplete(SessionPhase.Implement, Some(p1), Some(9000), BigDecimal("9.56"), resumed = false),
        sessionComplete(SessionPhase.Implement, Some(p1), Some(3000), BigDecimal("0.50"), resumed = true)
      )
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("1 resumed"), text)
    assert(text.contains("re-exploration avoided"), text)

  test("render of a run with no resumed turns omits the resumed sub-line"):
    val summary =
      StatsReport.fold(Vector(sessionComplete(SessionPhase.Implement, Some(p1), Some(4000), BigDecimal("1.0"))))
    val text = StatsReport.render(featureId, summary)
    assert(!text.contains("resumed"), text)

  test("render notes an open wait (process stopped mid-wait)"):
    val summary = StatsReport.fold(
      Vector(
        sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.50")),
        transitionAt("2026-05-31T10:00:00Z", wait = Some("enter" -> "intervention"))
      )
    )
    val text = StatsReport.render(featureId, summary)
    assert(text.contains("still open at the end of the log"), text)
    assert(text.contains("intervention"), text)

  // --- handler path --------------------------------------------------------

  tempFixture.test("stats on a feature with no log → exit 0 (nothing to fold)"): root =>
    val code = StatsReport.run(new ForgePaths(root), Vector(featureId.value)).unsafeRunSync()
    assertEquals(code, ExitCode.Success)

  tempFixture.test("stats with no feature argument → exit 64 (usage)"): root =>
    val code = StatsReport.run(new ForgePaths(root), Vector.empty).unsafeRunSync()
    assertEquals(code, ExitCode(64))

  tempFixture.test("readActions decodes a synthetic log, skipping a malformed tail line"): root =>
    val paths = new ForgePaths(root)
    val logPath = paths.featureLog(featureId)
    os.makeDir.all(logPath / os.up)
    val good = upickle.default.write(sessionComplete(SessionPhase.Implement, Some(p1), Some(4000), BigDecimal("1.0")))
    os.write(logPath, good + "\n{ this is not json\n")
    val actions = StatsReport.readActions(paths, featureId).unsafeRunSync()
    assertEquals(actions.size, 1)
    assertEquals(actions.head.kind, "session.complete")

  tempFixture.test("stats on a synthetic log → exit 0"): root =>
    val paths = new ForgePaths(root)
    val logPath = paths.featureLog(featureId)
    os.makeDir.all(logPath / os.up)
    os.write(
      logPath,
      upickle.default.write(sessionComplete(SessionPhase.Spec, None, Some(5000), BigDecimal("1.5"))) + "\n"
    )
    val code = StatsReport.run(paths, Vector(featureId.value)).unsafeRunSync()
    assertEquals(code, ExitCode.Success)
