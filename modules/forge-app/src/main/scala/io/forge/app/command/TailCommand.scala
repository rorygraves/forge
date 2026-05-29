package io.forge.app.command

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import cats.syntax.foldable.*
import io.forge.app.cli.CliParser
import io.forge.core.paths.ForgePaths

import scala.concurrent.duration.*

/** Task 1.4.13 M11 — `forge tail <feature>`. Tails `.forge/log/<feature>.jsonl`, the operator's live window into a
  * headless `forge run`.
  *
  * **Read-only (§15).** Tail never acquires the lock — it must work *while* a `forge run` holds it. It dumps the
  * current log contents, then follows appends (polling every second) and prints each new line until the operator
  * interrupts (Ctrl-C, which cats-effect turns into fiber cancellation). The follow loop therefore never returns; the
  * `as(ExitCode.Success)` is the type-level terminal that a clean shutdown resolves to.
  *
  * The §2.5 polish (O3 — open + first-line read smoke test) builds on [[existing]], which reads the current contents as
  * a `Vector[String]` and is the unit-testable seam.
  */
object TailCommand:

  /** Poll cadence for following appends. Not a `config` knob — it's a UI cadence, independent of the §18 PR-poll
    * interval (which is minutes-scale and would make a live tail feel dead).
    */
  private val FollowInterval: FiniteDuration = 1.second

  def run(paths: ForgePaths, args: Vector[String]): IO[ExitCode] =
    CliParser.requireFeature("tail", args) match
      case Left(err) => Console[IO].errorln(s"forge: ${err.message}").as(ExitCode(64))
      case Right(id) =>
        val path = paths.featureLog(id)
        IO.blocking(os.exists(path)).flatMap {
          case false =>
            Console[IO]
              .println(s"forge tail ${id.value}: no log yet at $path (the feature has produced no actions).")
              .as(ExitCode.Success)
          case true =>
            existing(path)
              .flatMap { lines =>
                lines.traverse_(Console[IO].println) *> follow(path, lines.size)
              }
              .as(ExitCode.Success)
        }

  /** Current full contents, one entry per NDJSON line. The unit-testable seam (no following). */
  private[command] def existing(path: os.Path): IO[Vector[String]] =
    IO.blocking(if os.exists(path) then os.read.lines(path).toVector else Vector.empty)

  /** Follow loop: every [[FollowInterval]], print any lines appended since the last poll. Re-reads the file each tick
    * (modest log sizes, slow cadence); loops until cancelled.
    */
  private def follow(path: os.Path, alreadyPrinted: Int): IO[Unit] =
    IO.sleep(FollowInterval) *>
      existing(path).flatMap { lines =>
        lines.drop(alreadyPrinted).traverse_(Console[IO].println) *> follow(path, lines.size)
      }
