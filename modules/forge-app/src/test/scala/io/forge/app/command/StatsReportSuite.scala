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
      success: Boolean = true
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
        "success" -> ujson.Bool(success)
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
