package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import cats.effect.unsafe.implicits.global
import io.forge.app.cli.CliParser
import io.forge.app.config.ForgeConfig
import io.forge.core.paths.ForgePaths
import io.forge.tui.{ForgeTui, TuiSnapshot, TuiSnapshotBuilder}

import scala.concurrent.Future

/** Slice 2.1 Task 2.1.3 — `forge tui <feature>`: the read-only Elm-architecture dashboard.
  *
  * Resolves the feature, builds an initial [[TuiSnapshot]] from committed data, and hands [[ForgeTui]] a `reload` thunk
  * that re-folds the snapshot on each 1s tick — so a concurrent `forge run` is reflected within the poll interval.
  *
  * **Read-only (§15).** Like `status` / `tail` / `stats`, this never acquires the [[io.forge.app.command]] process lock
  * and never mutates state: the snapshot builder reads the rebuildable [[io.forge.core.state.FileStateCache]] directly
  * (never `RebuildState`) and decodes the action log in place (never `ActionLog.replay`), so it is safe to run against
  * an in-flight `forge run`. The reload thunk inherits that guarantee — [[TuiSnapshotBuilder.load]] is the same pure
  * fold the initial build uses.
  */
object TuiCommand:

  def run(paths: ForgePaths, config: ForgeConfig, args: Vector[String]): IO[ExitCode] =
    CliParser.requireFeature("tui", args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(id) =>
        // The per-tick reload bridges its read-only IO fold into the `Future` the termflow `Cmd.FCmd` expects, via the
        // global CE runtime. It re-uses TuiSnapshotBuilder.load — the same read-only fold as the initial build below.
        val load: IO[Option[TuiSnapshot]] =
          TuiSnapshotBuilder.load(paths, id, config.maxFeatureCostUsd, config.maxPieceCostUsd)
        val reload: () => Future[Option[TuiSnapshot]] = () => load.unsafeToFuture()
        load.flatMap {
          case None =>
            Console[IO]
              .errorln(s"forge tui ${id.value}: no such feature (no manifest at ${paths.manifest(id)}).")
              .as(ExitCode(1))
          case Some(initial) =>
            // The termflow runtime loop owns the calling thread until the user quits; run it on the blocking pool.
            IO.blocking(ForgeTui.run(initial, reload)).as(ExitCode.Success)
        }
