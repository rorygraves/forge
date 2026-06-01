package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import io.forge.app.cli.CliParser
import io.forge.app.config.ForgeConfig
import io.forge.core.FeatureId
import io.forge.core.fsm.Feature
import io.forge.core.log.Action
import io.forge.core.manifest.{FileManifestStore, Manifest}
import io.forge.core.paths.ForgePaths
import io.forge.core.state.FileStateCache
import io.forge.core.status.StatusFields

import upickle.default as upickle

/** Task 1.4.13 M4 — `forge status [<feature>]`. Per §2.5 polish: current state, current piece, last action, and budget
  * remaining at a glance.
  *
  * **Read-only (§15).** Status never acquires the lock and never mutates state. It reads the rebuildable
  * [[FileStateCache]] directly (the §11.0 fast path) rather than running [[io.forge.core.state.RebuildState]] — a
  * `forge run` may be mid-flight, and verifying-against-log would write. When no cache is present yet (a freshly `forge
  * new`'d feature, or post-`rebuild-state` deletion) the report renders from the manifest alone and points the operator
  * at `forge run` / `forge rebuild-state`. The "last action" line decodes the final NDJSON line of
  * `.forge/log/<feature>.jsonl` in place — it does **not** call `ActionLog.replay`, whose repair-on-read could write.
  *
  * With no feature argument it prints a one-line-per-feature overview across `.forge/specs/`. The §2.5 golden-file
  * formatting polish is Task 1.4.15 O2; this is the v1 rendering it builds on.
  */
object StatusReport:

  private[command] enum Result:
    case Rendered(text: String)
    case FeatureNotFound(id: FeatureId)

  def run(paths: ForgePaths, config: ForgeConfig, args: Vector[String]): IO[ExitCode] =
    CliParser.optionalFeature(args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(None) => overview(paths).flatMap(text => Console[IO].println(text).as(ExitCode.Success))
      case Right(Some(id)) =>
        describe(paths, config, id).flatMap {
          case Result.Rendered(text) => Console[IO].println(text).as(ExitCode.Success)
          case Result.FeatureNotFound(missing) =>
            Console[IO]
              .errorln(s"forge status ${missing.value}: no such feature (no manifest at ${paths.manifest(missing)}).")
              .as(ExitCode(1))
        }

  /** Single-feature report. Reads the manifest (authoritative title/mode/pieces), the optional state cache, and the
    * last logged action.
    */
  private[command] def describe(paths: ForgePaths, config: ForgeConfig, id: FeatureId): IO[Result] =
    IO.blocking(os.exists(paths.manifest(id))).flatMap {
      case false => IO.pure(Result.FeatureNotFound(id))
      case true =>
        new FileManifestStore(paths).load(id).flatMap {
          case Left(failure) =>
            IO.pure(
              Result.Rendered(s"feature ${id.value}: manifest present but unreadable — ${failure.cause.getMessage}")
            )
          case Right(manifest) =>
            for
              cached <- new FileStateCache(paths).load(id)
              last <- lastAction(paths, id)
            yield Result.Rendered(renderFeature(manifest, cached, last, config))
        }
    }

  /** No-feature overview: one summary line per feature directory under `.forge/specs/`. */
  private[command] def overview(paths: ForgePaths): IO[String] =
    val cache = new FileStateCache(paths)
    IO.blocking {
      if !os.exists(paths.specsRoot) then Vector.empty[FeatureId]
      else
        os.list(paths.specsRoot)
          .filter(p => os.isDir(p) && os.exists(p / "manifest.json"))
          .map(p => FeatureId(p.last))
          .toVector
          .sortBy(_.value)
    }.flatMap {
      case ids if ids.isEmpty => IO.pure(s"no features found under ${paths.specsRoot}.")
      case ids => summaryLines(ids, cache).map(_.mkString("\n"))
    }

  /** One `id: <state>` line per feature, reading each one's state cache (absent cache → a "run forge run" note). */
  private def summaryLines(ids: Vector[FeatureId], cache: FileStateCache): IO[Vector[String]] =
    ids.foldLeft(IO.pure(Vector.empty[String])) { (accIO, id) =>
      for
        acc <- accIO
        cached <- cache.load(id)
      yield acc :+ s"${id.value}: ${cached.map(f => StatusFields.stateLabel(f.state)).getOrElse("no state cache (run forge run)")}"
    }

  /** Pure render of a single feature's status block — unit-testable without I/O. */
  private[command] def renderFeature(
      manifest: Manifest,
      cached: Option[Feature],
      lastAction: Option[Action],
      config: ForgeConfig
  ): String =
    val state = cached.map(_.state)
    val stateLine = state.map(StatusFields.stateLabel).getOrElse(StatusFields.NoStateCacheLabel)
    val pieceLine = StatusFields.pieceLabel(manifest, state)
    val lastLine = StatusFields.lastActionLabel(lastAction)
    val budgetLine = StatusFields.budgetLine(cached.map(_.cost), config.maxFeatureCostUsd, config.maxPieceCostUsd)
    s"""feature ${manifest.featureId.value} — "${manifest.title}"  [${manifest.mode}]
       |  state:   $stateLine
       |  piece:   $pieceLine
       |  last:    $lastLine
       |  budget:  $budgetLine""".stripMargin

  /** Decode the last non-blank NDJSON line of the feature log as an [[Action]] without rewriting the file. A malformed
    * tail decodes to `None` (status degrades to "no actions logged" rather than failing).
    */
  private[command] def lastAction(paths: ForgePaths, id: FeatureId): IO[Option[Action]] =
    IO.blocking {
      val p = paths.featureLog(id)
      if !os.exists(p) then None
      else
        os.read
          .lines(p)
          .reverseIterator
          .map(_.trim)
          .find(_.nonEmpty)
          .flatMap(line => scala.util.Try(upickle.read[Action](line)).toOption)
    }
