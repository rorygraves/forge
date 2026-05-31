package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.CliParser
import io.forge.core.FeatureId
import io.forge.core.fsm.SessionPhase
import io.forge.core.log.Action
import io.forge.core.paths.ForgePaths

import upickle.default as upickle

/** Slice 2.0 Task 2.0.3 — `forge stats <feature>`. A read-only fold of `.forge/log/<feature>.jsonl` into a per-phase
  * cost / wall-clock / turn-count breakdown — the direct answer to "did this run efficiently" (design-2.0 §0 exit
  * criterion #3).
  *
  * **Read-only (§15).** Like `forge status` / `forge tail`, this never acquires the lock and never mutates state — it
  * decodes the committed log in place (skipping any malformed tail line) and folds it. It does **not** call
  * `ActionLog.replay`, whose repair-on-read could write.
  *
  * **Data source.** The fold reads the two Slice-2.0 audit kinds:
  *   - `session.complete` (Task 2.0.2) — one per settled driver session, carrying `{ phase, piece, durationMs,
  *     turnCostUsd, success }`. This is the per-phase source: it has the phase tag, the CLI wall-clock, and the turn's
  *     cost, so turns / wall-clock / USD all fold from it.
  *   - `cost.update` (Task 2.0.1) — the running `featureTotalUsd` is the §13 single-writer authoritative total; its
  *     last value pins the feature-total cost (and its mere presence proves cost data was captured even if every turn's
  *     `turnCostUsd` happened to be zero).
  *
  * A log produced before the Slice-2.0 capture gap closed (the szork MVP run had **zero** cost entries) has neither
  * kind: the report degrades to "no session data recorded" rather than crashing (design-2.0 §1 Task 2.0.3 "an empty /
  * partial log degrades gracefully").
  *
  * The working-vs-waiting split (design-2.0 §0 #4) is Task 2.0.4's wait-markers; when those land, [[fold]] gains a wait
  * column. This task ships the per-phase working-time table it builds on.
  */
object StatsReport:

  /** Aggregated stats for one [[SessionPhase]] (folded from that phase's `session.complete` records). */
  final case class PhaseStats(
      phase: SessionPhase,
      turns: Int,
      knownDurationMs: Long,
      /** `session.complete` records in this phase whose `durationMs` was `null` (timeout / kill with no `Result`). */
      missingDurations: Int,
      usd: BigDecimal
  )

  /** The whole-feature fold. `phases` is ordered by [[PhaseOrder]] (only phases that appear). `featureUsd` prefers the
    * authoritative running total off the last `cost.update`; absent any `cost.update` it is the summed per-turn cost.
    */
  final case class Summary(
      phases: Vector[PhaseStats],
      totalTurns: Int,
      totalKnownDurationMs: Long,
      totalMissingDurations: Int,
      featureUsd: BigDecimal,
      /** True if any cost was captured at all (`cost.update` present, or a non-zero `turnCostUsd`). Drives the "cost:
        * unavailable" degradation for pre-observability logs.
        */
      costAvailable: Boolean
  )

  /** Canonical render order — the §11 lifecycle order, so the table reads top-to-bottom as the run progressed. */
  private val PhaseOrder: Vector[SessionPhase] = Vector(
    SessionPhase.Spec,
    SessionPhase.DesignReview,
    SessionPhase.DesignRevision,
    SessionPhase.Implement,
    SessionPhase.Fixup,
    SessionPhase.CodeReview,
    SessionPhase.Refine
  )

  def run(paths: ForgePaths, args: Vector[String]): IO[ExitCode] =
    CliParser.requireFeature("stats", args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(id) =>
        val path = paths.featureLog(id)
        IO.blocking(os.exists(path)).flatMap {
          case false =>
            Console[IO]
              .println(s"forge stats ${id.value}: no log yet at $path (the feature has produced no actions).")
              .as(ExitCode.Success)
          case true =>
            readActions(paths, id).flatMap(actions =>
              Console[IO].println(render(id, fold(actions))).as(ExitCode.Success)
            )
        }

  /** Decode every NDJSON line of the feature log as an [[Action]], skipping any malformed line (a partially-written
    * tail decodes to nothing rather than failing the whole report). Does not rewrite the file.
    */
  private[command] def readActions(paths: ForgePaths, id: FeatureId): IO[Vector[Action]] =
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

  /** Pure fold of a feature's actions into a [[Summary]] — the unit-testable seam (no I/O). */
  private[command] def fold(actions: Vector[Action]): Summary =
    val sessions = actions.filter(_.kind == "session.complete")
    val costUpdates = actions.filter(_.kind == "cost.update")

    // Group settled sessions by their decoded phase (an unrecognised phase string is dropped — it cannot be attributed
    // to a known lifecycle bucket, and silently mis-bucketing would be worse than omitting it).
    val byPhase: Map[SessionPhase, Vector[Action]] =
      sessions.groupBy(a => phaseOf(a)).collect { case (Some(p), as) => p -> as }

    val phaseStats: Vector[PhaseStats] =
      PhaseOrder.flatMap { phase =>
        byPhase.get(phase).map { as =>
          val durations = as.map(durationMsOf)
          PhaseStats(
            phase = phase,
            turns = as.size,
            knownDurationMs = durations.flatten.sum,
            missingDurations = durations.count(_.isEmpty),
            usd = as.map(turnCostUsdOf).sum
          )
        }
      }

    val summedUsd = phaseStats.map(_.usd).sum
    // The last cost.update's featureTotalUsd is the §13 single-writer running total — authoritative when present.
    val featureUsd = costUpdates.lastOption.flatMap(featureTotalUsdOf).getOrElse(summedUsd)

    Summary(
      phases = phaseStats,
      totalTurns = phaseStats.map(_.turns).sum,
      totalKnownDurationMs = phaseStats.map(_.knownDurationMs).sum,
      totalMissingDurations = phaseStats.map(_.missingDurations).sum,
      featureUsd = featureUsd,
      costAvailable = costUpdates.nonEmpty || summedUsd > 0
    )

  /** Render a [[Summary]] as the operator-facing block — the unit-testable seam (no I/O). */
  private[command] def render(id: FeatureId, summary: Summary): String =
    if summary.phases.isEmpty then s"""feature ${id.value} — run stats
         |  no session data recorded yet.
         |  (Cost / latency / turns are captured per driver session from Slice 2.0 onward; a log written before then,
         |   or a run that has not yet settled a driver turn, has nothing to fold.)""".stripMargin
    else
      val header = f"  ${"phase"}%-16s${"turns"}%7s${"wall-clock"}%14s${"cost"}%12s"
      val rule = "  " + ("─" * (16 + 7 + 14 + 12))
      val rows = summary.phases.map(renderRow)
      val totalCost = if summary.costAvailable then f"$$${summary.featureUsd}%.2f" else "unavailable"
      val totalRow =
        f"  ${"total"}%-16s${summary.totalTurns}%7d${formatDuration(summary.totalKnownDurationMs, summary.totalMissingDurations)}%14s${totalCost}%12s"
      val notes = renderNotes(summary)
      (Vector(s"""feature ${id.value} — run stats""", "", header, rule) ++ rows ++ Vector(rule, totalRow) ++ notes)
        .mkString("\n")

  private def renderRow(p: PhaseStats): String =
    val cost = if p.usd > 0 then f"$$${p.usd}%.2f" else "—"
    f"  ${label(p.phase)}%-16s${p.turns}%7d${formatDuration(p.knownDurationMs, p.missingDurations)}%14s${cost}%12s"

  /** Footnotes appended below the table: a wall-clock caveat when some sessions lacked a duration, and the
    * cost-unavailable hint for pre-observability logs.
    */
  private def renderNotes(summary: Summary): Vector[String] =
    val durationNote =
      if summary.totalMissingDurations > 0 then
        Vector(
          s"  note: ${summary.totalMissingDurations} session(s) had no recorded duration " +
            "(timed out or were killed before a result); wall-clock excludes them."
        )
      else Vector.empty
    val costNote =
      if !summary.costAvailable then
        Vector("  note: cost unavailable — this log predates per-session cost capture (Slice 2.0).")
      else Vector.empty
    durationNote ++ costNote

  /** Human label for the table's phase column — the §11 lifecycle name, lower-kebab. */
  private def label(p: SessionPhase): String = p match
    case SessionPhase.Spec => "spec"
    case SessionPhase.DesignReview => "design-review"
    case SessionPhase.DesignRevision => "design-revision"
    case SessionPhase.Implement => "implement"
    case SessionPhase.Fixup => "fixup"
    case SessionPhase.CodeReview => "code-review"
    case SessionPhase.Refine => "refine"

  /** `123ms` / `9.1s` / `1m 30s`; a `+n?` suffix flags `missing` sessions whose duration is unknown. */
  private def formatDuration(ms: Long, missing: Int): String =
    val base =
      if ms <= 0 && missing > 0 then "?"
      else if ms < 1000 then s"${ms}ms"
      else if ms < 60000 then f"${ms / 1000.0}%.1fs"
      else
        val totalSec = ms / 1000
        s"${totalSec / 60}m ${totalSec % 60}s"
    if missing > 0 && ms > 0 then s"$base+?" else base

  // --- payload accessors (tolerant: a missing/mistyped field folds to None / 0, never throws) ---

  private def phaseOf(a: Action): Option[SessionPhase] =
    a.payload.objOpt
      .flatMap(_.get("phase"))
      .flatMap(_.strOpt)
      .flatMap(s => scala.util.Try(SessionPhase.valueOf(s)).toOption)

  private def durationMsOf(a: Action): Option[Long] =
    a.payload.objOpt.flatMap(_.get("durationMs")).flatMap(_.numOpt).map(_.toLong)

  private def turnCostUsdOf(a: Action): BigDecimal =
    a.payload.objOpt.flatMap(_.get("turnCostUsd")).flatMap(_.numOpt).map(BigDecimal(_)).getOrElse(BigDecimal(0))

  private def featureTotalUsdOf(a: Action): Option[BigDecimal] =
    a.payload.objOpt.flatMap(_.get("featureTotalUsd")).flatMap(_.numOpt).map(BigDecimal(_))
