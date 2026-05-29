package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.CliParser
import io.forge.core.FeatureId
import io.forge.core.log.FileActionLog
import io.forge.core.manifest.FileManifestStore
import io.forge.core.paths.ForgePaths
import io.forge.core.state.{FileStateCache, RebuildError, RebuildState}

/** Task 1.4.13 M9 — `forge rebuild-state <feature>`. Re-derives the [[io.forge.core.fsm.Feature]] projection from the
  * committed `manifest.json` + the canonical action log and rewrites the state cache atomically, via
  * [[RebuildState.run]].
  *
  * **§15 read-only class — but it does write.** `rebuild-state` is classified read-only (no process lock) so it can
  * repair a feature while diagnosing it, yet [[RebuildState.run]] rewrites the cache and may append crash-recovery
  * repair drafts to the log. That is the command's whole purpose (recovery from a corrupt/stale cache), and not holding
  * the lock is deliberate: a stuck `forge run` is exactly when an operator needs to inspect/rebuild. The §2.5 polish
  * (O4 — proof against a deliberately corrupted cache) lands in Task 1.4.15.
  */
object RebuildStateCommand:

  def run(paths: ForgePaths, args: Vector[String]): IO[ExitCode] =
    CliParser.requireFeature("rebuild-state", args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(id) =>
        FileActionLog(paths).flatMap { log =>
          val manifestStore = new FileManifestStore(paths)
          val cache = new FileStateCache(paths)
          RebuildState.run(id, paths, manifestStore, log, cache).flatMap {
            case Right(result) => Console[IO].println(renderSuccess(id, result)).as(ExitCode.Success)
            case Left(err) => Console[IO].errorln(renderError(id, err)).as(ExitCode(1))
          }
        }

  private[command] def renderSuccess(id: FeatureId, result: RebuildState.RebuildResult): String =
    val base =
      s"forge rebuild-state ${id.value}: rebuilt state cache — state is ${StatusReport.stateLabel(result.feature.state)}."
    if result.inFlightSessions.isEmpty then base
    else
      val sessions = result.inFlightSessions
        .map(s => s"    - ${s.phase}${s.piece.map(p => s" (piece ${p.value})").getOrElse("")} session ${s.sessionId}")
        .mkString("\n")
      s"""$base
         |  ${result.inFlightSessions.size} interrupted driver session(s) detected (a prior run died mid-session):
         |$sessions
         |  Re-run `forge run ${id.value}`; it will surface these as needs-human-intervention with a resume hint.""".stripMargin

  private[command] def renderError(id: FeatureId, err: RebuildError): String = err match
    case RebuildError.ManifestLoadFailed(_, cause) =>
      s"forge rebuild-state ${id.value}: cannot load manifest — ${cause.getMessage}. Has the feature been designed yet?"
    case RebuildError.ReplayInconsistent(cause) =>
      s"forge rebuild-state ${id.value}: action log is inconsistent — $cause. Operator intervention required."
    case RebuildError.InconsistentRecovery(reason) =>
      s"forge rebuild-state ${id.value}: state is unrecoverable — $reason. Operator intervention required."
