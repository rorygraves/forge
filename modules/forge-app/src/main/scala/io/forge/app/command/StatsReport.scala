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
  * **Working-vs-waiting split (design-2.0 §0 #4, Task 2.0.4).** The per-phase table above is Forge *working* time (the
  * sum of driver-session durations). Time the loop spends parked on the operator — answering blocking questions,
  * merging the design/piece PR, running `forge resume` — is bracketed separately: every `fsm.transition` into one of
  * the human-blocking states (`DesignNeedsHumanInput` / `DesignAwaitingMerge` / `PieceAwaitingMerge` /
  * `NeedsHumanIntervention`) carries a `wait` marker (`Fsm.humanWaitKind`), and [[foldWaits]] pairs each enter marker
  * with the next transition to recover the wait interval. The result renders as a distinct "waiting (human)" breakdown
  * below the totals, so a 35-minute "waiting for the operator to merge" reads as waiting, not Forge being slow. Waits
  * are reported in their own section rather than subtracted from a phase row because they fall *between* driver
  * sessions, not inside any single phase's work. A wait still open at the end of the log (the process crashed mid-wait)
  * degrades to "end unknown" rather than mis-attributing an unbounded interval.
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
      costAvailable: Boolean,
      /** Human-wait time, in milliseconds, attributed by wait kind (Task 2.0.4). Empty for a run that never blocked on
        * the operator (or a pre-marker log). Keyed by the `Fsm.humanWaitKind` tag; ordered for render by [[WaitOrder]].
        */
      waitMsByKind: Map[String, Long],
      /** The wait kind of an interval still open at the end of the log — the loop was parked on a human when the record
        * ended (process crashed / killed mid-wait). `Some` ⇒ render an "end unknown" caveat rather than an interval.
        */
      unclosedWaitKind: Option[String]
  ):
    /** Total bracketed (closed) human-wait across all kinds. Excludes any [[unclosedWaitKind]] interval (no end ⇒ no
      * measurable duration).
      */
    def totalWaitMs: Long = waitMsByKind.values.sum

  /** Render order + human label for the human-wait kinds (the `Fsm.humanWaitKind` tags), lifecycle order. Any tag not
    * listed here still renders (under its raw tag) so a newly-added wait kind degrades visibly rather than vanishing.
    */
  private val WaitOrder: Vector[(String, String)] = Vector(
    "design-input" -> "design input",
    "design-merge" -> "design merge",
    "piece-merge" -> "piece merge",
    "intervention" -> "intervention"
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

    val waits = foldWaits(actions)

    Summary(
      phases = phaseStats,
      totalTurns = phaseStats.map(_.turns).sum,
      totalKnownDurationMs = phaseStats.map(_.knownDurationMs).sum,
      totalMissingDurations = phaseStats.map(_.missingDurations).sum,
      featureUsd = featureUsd,
      costAvailable = costUpdates.nonEmpty || summedUsd > 0,
      waitMsByKind = waits.byKind,
      unclosedWaitKind = waits.open.map(_._2)
    )

  /** Accumulator for the [[foldWaits]] walk: closed wait time per kind, plus the one currently-open interval (if any).
    */
  private[command] final case class WaitAccum(byKind: Map[String, Long], open: Option[(java.time.Instant, String)])

  /** Pure fold of the `fsm.transition` markers into human-wait time per kind (Task 2.0.4). Walks the transitions in
    * `seq` order maintaining at most one open interval: an `enter` marker opens it; the **next** transition (whatever
    * it is — the matching `leave`, a self-poll re-`enter`, or a switch into another wait) closes it, accumulating
    * `next.at - enter.at` to the opening kind. This closes-on-next-transition rule is robust to self-poll transitions
    * and to a missing `leave` edge: a wait left open at the end of the log (process crashed mid-wait) is reported via
    * [[WaitAccum.open]] rather than mis-attributed an unbounded duration. Clamps any negative delta (clock skew) to 0.
    */
  private[command] def foldWaits(actions: Vector[Action]): WaitAccum =
    val transitions = actions.filter(_.kind == "fsm.transition").sortBy(_.seq)
    transitions.foldLeft(WaitAccum(Map.empty, None)) { (acc, t) =>
      val closed = acc.open match
        case Some((start, kind)) =>
          val ms = math.max(0L, java.time.Duration.between(start, t.at).toMillis)
          acc.copy(byKind = acc.byKind.updatedWith(kind)(prev => Some(prev.getOrElse(0L) + ms)), open = None)
        case None => acc
      waitEnterKindOf(t) match
        case Some(kind) => closed.copy(open = Some((t.at, kind)))
        case None => closed
    }

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
      val waitBlock = renderWaits(summary)
      val notes = renderNotes(summary)
      (Vector(s"""feature ${id.value} — run stats""", "", header, rule) ++ rows ++ Vector(rule, totalRow)
        ++ waitBlock ++ notes).mkString("\n")

  /** The "waiting (human)" block rendered below the totals when the run blocked on the operator — the
    * working-vs-waiting split (Task 2.0.4). The total row above is Forge working time; this is wall-clock spent parked
    * on a human. Empty when the run never waited; a wait still open at end-of-log carries no duration and is surfaced
    * by [[renderNotes]].
    */
  private def renderWaits(summary: Summary): Vector[String] =
    val total = summary.totalWaitMs
    // Only render the block for measured (closed) wait time. An interval still open at end-of-log has no duration; it is
    // surfaced by the [[renderNotes]] caveat instead of a misleading "0ms" row.
    if total <= 0 then Vector.empty
    else
      val headerLine = f"  ${"waiting (human)"}%-16s${""}%7s${formatDuration(total, 0)}%14s"
      // Ordered known kinds first, then any unrecognised kind under its raw tag (visible degradation).
      val known = WaitOrder.map(_._1).toSet
      val orderedKinds =
        WaitOrder.collect { case (tag, lbl) if summary.waitMsByKind.contains(tag) => tag -> lbl } ++
          summary.waitMsByKind.keys.filterNot(known).toVector.sorted.map(tag => tag -> tag)
      val kindRows = orderedKinds.map { case (tag, lbl) =>
        f"    ${lbl}%-14s${""}%7s${formatDuration(summary.waitMsByKind.getOrElse(tag, 0L), 0)}%14s"
      }
      Vector("") ++ Vector(headerLine) ++ kindRows

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
    val waitNote =
      summary.unclosedWaitKind match
        case Some(kind) =>
          Vector(
            s"  note: a '${waitLabel(kind)}' wait was still open at the end of the log " +
              "(process stopped mid-wait); its duration is unknown and excluded from the waiting total."
          )
        case None => Vector.empty
    durationNote ++ costNote ++ waitNote

  /** Human label for a wait kind (the [[WaitOrder]] mapping); falls back to the raw tag for an unrecognised kind. */
  private def waitLabel(kind: String): String =
    WaitOrder.collectFirst { case (tag, lbl) if tag == kind => lbl }.getOrElse(kind)

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

  /** The wait kind of an `enter` marker on an `fsm.transition` payload (`{ wait: { edge: "enter", kind } }`), or `None`
    * for a `leave` marker or a transition that is not a human-wait boundary (Task 2.0.4).
    */
  private def waitEnterKindOf(a: Action): Option[String] =
    for
      obj <- a.payload.objOpt
      waitVal <- obj.get("wait")
      waitObj <- waitVal.objOpt
      edge <- waitObj.get("edge").flatMap(_.strOpt)
      if edge == "enter"
      kind <- waitObj.get("kind").flatMap(_.strOpt)
    yield kind
